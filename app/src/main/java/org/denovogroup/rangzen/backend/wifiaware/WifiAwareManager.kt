/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * WiFi Aware (NAN) manager for peer discovery without user confirmation dialogs
 */
package org.denovogroup.rangzen.backend.wifiaware

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.aware.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.denovogroup.rangzen.backend.FriendStore
import org.denovogroup.rangzen.backend.lan.LanTransport
import org.denovogroup.rangzen.backend.discovery.DiscoveredPeerRegistry
import org.denovogroup.rangzen.backend.discovery.TransportCapabilities
import org.denovogroup.rangzen.backend.telemetry.TelemetryClient
import org.denovogroup.rangzen.backend.telemetry.TelemetryEvent
import timber.log.Timber
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Manager for WiFi Aware (Neighbor Awareness Networking) discovery.
 *
 * Key advantages over WiFi Direct:
 * - No user confirmation dialog required
 * - Doesn't disconnect from regular WiFi
 * - Background discovery works reliably
 * - Similar range and throughput to WiFi Direct
 *
 * Limitations:
 * - Only available on Android 8+ (API 26)
 * - Requires hardware support (~60% of devices)
 * - Both devices must be on same WiFi band (2.4GHz or 5GHz)
 */
@RequiresApi(Build.VERSION_CODES.O)
class WifiAwareManager(
    private val context: Context,
    private val peerRegistry: DiscoveredPeerRegistry
) {
    companion object {
        private const val TAG = "WifiAwareManager"

        // Service type for Murmur discovery
        const val SERVICE_TYPE = "_murmur._tcp"
        const val SERVICE_NAME = "Murmur"

        // TXT record keys (compatible with WiFi Direct DNS-SD)
        const val TXT_KEY_ID = "id"      // Public ID (first 8 chars of derived hash)
        const val TXT_KEY_PORT = "port"  // Exchange port
        const val TXT_KEY_VER = "ver"    // Protocol version

        // Discovery settings
        const val PUBLISH_TTL_SEC = 0  // 0 = until stopped
        const val SUBSCRIBE_TTL_SEC = 0

        // Keepalive: re-report peers to registry so they don't go stale (30s threshold)
        const val KEEPALIVE_INTERVAL_MS = 15_000L
    }

    private var wifiAwareManager: android.net.wifi.aware.WifiAwareManager? = null
    private var wifiAwareSession: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // Track discovered peers by their session+handle
    private val discoveredPeers = ConcurrentHashMap<String, DiscoveredWifiAwarePeer>()

    // Our identity for publishing
    private var localPublicId: String? = null
    private var localPort: Int = 8765  // Default exchange port

    // State
    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _peerCount = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = _peerCount.asStateFlow()

    // Keepalive: periodically re-report discovered peers to the registry
    // so their lastSeen timestamps stay fresh (WiFi Aware's onServiceDiscovered
    // only fires once per peer, unlike BLE which continuously rescans).
    private val keepaliveRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            discoveredPeers.values.forEach { peer ->
                peer.lastSeen = now
                peerRegistry.reportWifiAwarePeer(
                    sessionId = peer.sessionId,
                    peerHandleHash = peer.peerHandle.hashCode(),
                    publicId = peer.publicIdPrefix,
                    port = peer.port,
                    rssi = null
                )
            }
            if (discoveredPeers.isNotEmpty()) {
                Timber.d("$TAG: Keepalive refreshed ${discoveredPeers.size} peers")
            }
            mainHandler.postDelayed(this, KEEPALIVE_INTERVAL_MS)
        }
    }

    // Responder (publisher) network accept state
    private var responderCallback: android.net.ConnectivityManager.NetworkCallback? = null

    // Callbacks
    var onPeerDiscovered: ((DiscoveredWifiAwarePeer) -> Unit)? = null
    var onPeerLost: ((String) -> Unit)? = null
    /** Called when an incoming data path is established (publisher/responder side). */
    var onIncomingConnection: ((java.net.Inet6Address, Int) -> Unit)? = null

    // Broadcast receiver for WiFi Aware availability changes
    private val availabilityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == android.net.wifi.aware.WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED) {
                val isAvailable = wifiAwareManager?.isAvailable == true
                _isAvailable.value = isAvailable
                Timber.d("$TAG: WiFi Aware availability changed: $isAvailable")

                if (!isAvailable && _isActive.value) {
                    // WiFi Aware became unavailable while we were active
                    Timber.w("$TAG: WiFi Aware became unavailable, stopping")
                    stop()
                }
            }
        }
    }

    init {
        if (TransportCapabilities.isWifiAwareSupported(context)) {
            wifiAwareManager = context.getSystemService(Context.WIFI_AWARE_SERVICE)
                as? android.net.wifi.aware.WifiAwareManager
            _isAvailable.value = wifiAwareManager?.isAvailable == true

            // Register for availability changes
            val filter = IntentFilter(android.net.wifi.aware.WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(availabilityReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(availabilityReceiver, filter)
            }

            Timber.i("$TAG: Initialized, available=${_isAvailable.value}")
        } else {
            Timber.i("$TAG: WiFi Aware not supported on this device")
        }
    }

    /**
     * Check if WiFi Aware is supported on this device.
     */
    fun isSupported(): Boolean = wifiAwareManager != null

    /**
     * Start WiFi Aware discovery.
     *
     * @param publicId Our public ID to advertise
     * @param port Port for exchange connections
     */
    fun start(publicId: String, port: Int = 8765) {
        if (wifiAwareManager == null) {
            Timber.w("$TAG: Cannot start - WiFi Aware not supported")
            return
        }

        if (!_isAvailable.value) {
            Timber.w("$TAG: Cannot start - WiFi Aware not available")
            return
        }

        if (_isActive.value) {
            Timber.d("$TAG: Already active")
            return
        }

        localPublicId = publicId
        localPort = port

        Timber.i("$TAG: Starting WiFi Aware discovery, publicId=${publicId.take(8)}...")

        // Attach to WiFi Aware
        wifiAwareManager?.attach(attachCallback, mainHandler)

        trackTelemetry("start_requested")
    }

    /**
     * Stop WiFi Aware discovery and release resources.
     */
    fun stop() {
        Timber.i("$TAG: Stopping WiFi Aware discovery")

        stopKeepalive()
        stopResponderAccept()

        publishSession?.close()
        publishSession = null

        subscribeSession?.close()
        subscribeSession = null

        wifiAwareSession?.close()
        wifiAwareSession = null

        discoveredPeers.clear()
        _peerCount.value = 0
        _isActive.value = false

        trackTelemetry("stopped")
    }

    /**
     * Request a WiFi Aware network data path to a discovered peer.
     *
     * Creates a temporary L3 network between this device and the peer,
     * returning the peer's IPv6 address for TCP-based message exchange.
     *
     * @param peer The discovered WiFi Aware peer to connect to
     * @param timeoutMs Timeout for network request (default 10 seconds)
     * @return Peer's InetAddress if successful, null on failure/timeout
     */
    suspend fun requestNetworkForPeer(
        peer: DiscoveredWifiAwarePeer,
        timeoutMs: Long = 10_000L
    ): WifiAwareNetworkInfo? = withContext(Dispatchers.IO) {
        val session = wifiAwareSession
        if (session == null) {
            Timber.w("$TAG: Cannot request network - no WiFi Aware session")
            return@withContext null
        }

        try {
            val specifier = WifiAwareNetworkSpecifier.Builder(
                subscribeSession ?: publishSession ?: run {
                    Timber.w("$TAG: Cannot request network - no discovery session")
                    return@withContext null
                },
                peer.peerHandle
            ).build()

            val networkRequest = android.net.NetworkRequest.Builder()
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .setNetworkSpecifier(specifier)
                .build()

            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager

            val result = suspendCancellableCoroutine<WifiAwareNetworkInfo?> { continuation ->
                val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        Timber.i("$TAG: WiFi Aware network available for ${peer.sessionId}")
                    }

                    override fun onCapabilitiesChanged(
                        network: android.net.Network,
                        capabilities: android.net.NetworkCapabilities
                    ) {
                        val peerAwareInfo = capabilities.transportInfo
                            as? android.net.wifi.aware.WifiAwareNetworkInfo
                        if (peerAwareInfo != null) {
                            val peerAddress = peerAwareInfo.peerIpv6Addr
                            val peerPort = peerAwareInfo.port
                            Timber.i("$TAG: Got peer address: $peerAddress, port: $peerPort")

                            if (peerAddress != null && !continuation.isCompleted) {
                                continuation.resume(
                                    WifiAwareNetworkInfo(
                                        network = network,
                                        peerAddress = peerAddress,
                                        port = peerPort.takeIf { it > 0 }
                                            ?: LanTransport.EXCHANGE_PORT,
                                        callback = this
                                    )
                                ) {}
                            }
                        }
                    }

                    override fun onLost(network: android.net.Network) {
                        Timber.w("$TAG: WiFi Aware network lost for ${peer.sessionId}")
                        if (!continuation.isCompleted) {
                            continuation.resume(null) {}
                        }
                    }

                    override fun onUnavailable() {
                        Timber.w("$TAG: WiFi Aware network unavailable for ${peer.sessionId}")
                        if (!continuation.isCompleted) {
                            continuation.resume(null) {}
                        }
                    }
                }

                connectivityManager.requestNetwork(networkRequest, callback, mainHandler, timeoutMs.toInt())

                continuation.invokeOnCancellation {
                    try {
                        connectivityManager.unregisterNetworkCallback(callback)
                    } catch (e: Exception) {
                        Timber.w(e, "$TAG: Failed to unregister callback on cancellation")
                    }
                }
            }

            if (result != null) {
                trackTelemetry("network_established", mapOf(
                    "peer_id" to (peer.publicIdPrefix ?: peer.sessionId),
                    "peer_address" to (result.peerAddress.hostAddress ?: "unknown")
                ))
            } else {
                trackTelemetry("network_failed", mapOf(
                    "peer_id" to (peer.publicIdPrefix ?: peer.sessionId)
                ))
            }

            result
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to request network for ${peer.sessionId}")
            trackTelemetry("network_error", mapOf(
                "peer_id" to (peer.publicIdPrefix ?: peer.sessionId),
                "error" to (e.message ?: "unknown")
            ))
            null
        }
    }

    /**
     * Release a previously requested WiFi Aware network.
     * Must be called after exchange completes to free resources.
     */
    fun releaseNetwork(networkInfo: WifiAwareNetworkInfo) {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            connectivityManager.unregisterNetworkCallback(networkInfo.callback)
            Timber.i("$TAG: Released WiFi Aware network")
            trackTelemetry("network_released")
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Failed to release WiFi Aware network")
        }
    }

    /**
     * Clean up resources when no longer needed.
     */
    fun destroy() {
        stop()
        try {
            context.unregisterReceiver(availabilityReceiver)
        } catch (e: Exception) {
            // Ignore if not registered
        }
    }

    // Attach callback
    private val attachCallback = object : AttachCallback() {
        override fun onAttached(session: WifiAwareSession) {
            Timber.i("$TAG: Attached to WiFi Aware session")
            wifiAwareSession = session
            _isActive.value = true

            // Start publishing our service
            startPublish()

            // Start subscribing to discover others
            startSubscribe()

            // Start keepalive to prevent discovered peers from going stale
            startKeepalive()

            trackTelemetry("attached")
        }

        override fun onAttachFailed() {
            Timber.e("$TAG: Failed to attach to WiFi Aware")
            _isActive.value = false
            trackTelemetry("attach_failed")
        }
    }

    /**
     * Start publishing our service so others can discover us.
     */
    private fun startPublish() {
        val session = wifiAwareSession ?: return
        val id = localPublicId ?: return

        // Create service-specific info with our identity
        val serviceInfo = mapOf(
            TXT_KEY_ID to id.take(8),  // Privacy: only first 8 chars
            TXT_KEY_PORT to localPort.toString(),
            TXT_KEY_VER to "1"
        )

        // Convert to byte array format expected by WiFi Aware
        val serviceInfoBytes = serviceInfo.entries
            .joinToString(",") { "${it.key}=${it.value}" }
            .toByteArray(Charsets.UTF_8)

        val config = PublishConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setServiceSpecificInfo(serviceInfoBytes)
            .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
            .setTtlSec(PUBLISH_TTL_SEC)
            .build()

        session.publish(config, publishCallback, mainHandler)
        Timber.d("$TAG: Publishing service with ID ${id.take(8)}...")
    }

    /**
     * Accept incoming WiFi Aware data paths on the publisher side.
     *
     * When another device (subscriber) initiates a data path to us,
     * we need a standing network request to accept it. Without this,
     * the system rejects incoming NDP requests with "can't find a request".
     */
    private fun startResponderAccept() {
        val session = publishSession ?: return
        stopResponderAccept()

        try {
            val specifier = WifiAwareNetworkSpecifier.Builder(session)
                .build()

            val networkRequest = android.net.NetworkRequest.Builder()
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .setNetworkSpecifier(specifier)
                .build()

            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager

            val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    Timber.i("$TAG: Responder - incoming data path available")
                }

                override fun onCapabilitiesChanged(
                    network: android.net.Network,
                    capabilities: android.net.NetworkCapabilities
                ) {
                    val peerAwareInfo = capabilities.transportInfo
                        as? android.net.wifi.aware.WifiAwareNetworkInfo
                    val peerAddress = peerAwareInfo?.peerIpv6Addr
                    if (peerAddress != null) {
                        Timber.i("$TAG: Responder - peer connected: $peerAddress")
                        trackTelemetry("responder_connection", mapOf(
                            "peer_address" to (peerAddress.hostAddress ?: "unknown")
                        ))
                        onIncomingConnection?.invoke(peerAddress, localPort)
                    }
                }

                override fun onLost(network: android.net.Network) {
                    Timber.d("$TAG: Responder - data path lost")
                }
            }

            connectivityManager.requestNetwork(networkRequest, callback, mainHandler)
            responderCallback = callback
            Timber.i("$TAG: Responder accept started on port $localPort")
            trackTelemetry("responder_started")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to start responder accept")
        }
    }

    private fun startKeepalive() {
        mainHandler.removeCallbacks(keepaliveRunnable)
        mainHandler.postDelayed(keepaliveRunnable, KEEPALIVE_INTERVAL_MS)
    }

    private fun stopKeepalive() {
        mainHandler.removeCallbacks(keepaliveRunnable)
    }

    private fun stopResponderAccept() {
        responderCallback?.let { cb ->
            try {
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as android.net.ConnectivityManager
                connectivityManager.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                // Ignore
            }
            responderCallback = null
        }
    }

    private val publishCallback = object : DiscoverySessionCallback() {
        override fun onPublishStarted(session: PublishDiscoverySession) {
            Timber.i("$TAG: Publish started")
            publishSession = session
            trackTelemetry("publish_started")
            // Accept incoming data paths from subscribers
            startResponderAccept()
        }

        override fun onSessionConfigFailed() {
            Timber.e("$TAG: Publish config failed")
            trackTelemetry("publish_failed")
        }

        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
            // Handle incoming messages from peers
            handleIncomingMessage(peerHandle, message)
        }
    }

    /**
     * Start subscribing to discover other Murmur devices.
     */
    private fun startSubscribe() {
        val session = wifiAwareSession ?: return

        val config = SubscribeConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
            .setTtlSec(SUBSCRIBE_TTL_SEC)
            .build()

        session.subscribe(config, subscribeCallback, mainHandler)
        Timber.d("$TAG: Subscribing to discover peers...")
    }

    private val subscribeCallback = object : DiscoverySessionCallback() {
        override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
            Timber.i("$TAG: Subscribe started")
            subscribeSession = session
            trackTelemetry("subscribe_started")
        }

        override fun onSessionConfigFailed() {
            Timber.e("$TAG: Subscribe config failed")
            trackTelemetry("subscribe_failed")
        }

        override fun onServiceDiscovered(
            peerHandle: PeerHandle,
            serviceSpecificInfo: ByteArray?,
            matchFilter: List<ByteArray>?
        ) {
            handlePeerDiscovered(peerHandle, serviceSpecificInfo)
        }

        override fun onServiceDiscoveredWithinRange(
            peerHandle: PeerHandle,
            serviceSpecificInfo: ByteArray?,
            matchFilter: List<ByteArray>?,
            distanceMm: Int
        ) {
            // Enhanced discovery with distance info (Android 9+)
            handlePeerDiscovered(peerHandle, serviceSpecificInfo, distanceMm)
        }

        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
            handleIncomingMessage(peerHandle, message)
        }

        override fun onServiceLost(peerHandle: PeerHandle, reason: Int) {
            handlePeerLost(peerHandle)
        }
    }

    /**
     * Handle discovery of a new peer.
     */
    private fun handlePeerDiscovered(
        peerHandle: PeerHandle,
        serviceSpecificInfo: ByteArray?,
        distanceMm: Int? = null
    ) {
        // Parse service info to extract peer's public ID
        val serviceInfoStr = serviceSpecificInfo?.toString(Charsets.UTF_8) ?: ""
        val parsedInfo = parseServiceInfo(serviceInfoStr)

        val peerId = parsedInfo[TXT_KEY_ID]
        val portStr = parsedInfo[TXT_KEY_PORT]
        val port = portStr?.toIntOrNull()

        // Create a stable session ID from the peer handle
        val sessionId = "nan_${peerHandle.hashCode()}"

        // Check if this is us (comparing our ID prefix)
        if (peerId != null && localPublicId?.startsWith(peerId) == true) {
            Timber.d("$TAG: Ignoring self-discovery")
            return
        }

        Timber.i("$TAG: Discovered peer: id=${peerId ?: "unknown"}, port=$port, distance=${distanceMm}mm")

        // Create peer record
        val peer = DiscoveredWifiAwarePeer(
            peerHandle = peerHandle,
            sessionId = sessionId,
            publicIdPrefix = peerId,
            port = port,
            distanceMm = distanceMm,
            lastSeen = System.currentTimeMillis()
        )

        discoveredPeers[sessionId] = peer
        _peerCount.value = discoveredPeers.size

        // Report to unified registry
        peerRegistry.reportWifiAwarePeer(
            sessionId = sessionId,
            peerHandleHash = peerHandle.hashCode(),
            publicId = peerId,  // May be null or partial
            port = port,
            rssi = null  // WiFi Aware doesn't provide RSSI directly
        )

        onPeerDiscovered?.invoke(peer)

        trackTelemetry("peer_discovered", mapOf(
            "peer_id" to (peerId ?: "unknown"),
            "has_port" to (port != null).toString(),
            "has_distance" to (distanceMm != null).toString()
        ))
    }

    /**
     * Handle peer loss.
     */
    private fun handlePeerLost(peerHandle: PeerHandle) {
        val sessionId = "nan_${peerHandle.hashCode()}"
        val peer = discoveredPeers.remove(sessionId)

        if (peer != null) {
            Timber.i("$TAG: Lost peer: ${peer.publicIdPrefix ?: sessionId}")
            _peerCount.value = discoveredPeers.size
            onPeerLost?.invoke(sessionId)
            trackTelemetry("peer_lost")
        }
    }

    /**
     * Handle incoming message from a peer.
     */
    private fun handleIncomingMessage(peerHandle: PeerHandle, message: ByteArray) {
        val sessionId = "nan_${peerHandle.hashCode()}"
        Timber.d("$TAG: Received message from $sessionId: ${message.size} bytes")

        // Update last seen time
        discoveredPeers[sessionId]?.lastSeen = System.currentTimeMillis()

        // TODO: Process protocol messages for exchange
    }

    /**
     * Send a message to a peer.
     */
    fun sendMessage(sessionId: String, message: ByteArray): Boolean {
        val peer = discoveredPeers[sessionId] ?: return false
        val session = subscribeSession ?: publishSession ?: return false

        return try {
            session.sendMessage(peer.peerHandle, 0, message)
            true
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to send message to $sessionId")
            false
        }
    }

    /**
     * Parse service-specific info string.
     * Format: "key1=value1,key2=value2,..."
     */
    private fun parseServiceInfo(info: String): Map<String, String> {
        return info.split(",")
            .mapNotNull { entry ->
                val parts = entry.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            .toMap()
    }

    /**
     * Get list of currently discovered peers.
     */
    fun getDiscoveredPeers(): List<DiscoveredWifiAwarePeer> {
        return discoveredPeers.values.toList()
    }

    private fun trackTelemetry(event: String, extras: Map<String, String> = emptyMap()) {
        try {
            val payload = mutableMapOf(
                "event" to event,
                "peer_count" to discoveredPeers.size.toString()
            )
            payload.putAll(extras)
            TelemetryClient.getInstance()?.track(
                eventType = "wifi_aware_$event",
                transport = TelemetryEvent.TRANSPORT_WIFI_AWARE,
                payload = payload
            )
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Failed to track telemetry")
        }
    }
}

/**
 * Represents a peer discovered via WiFi Aware.
 */
/**
 * Info about an established WiFi Aware network data path.
 */
data class WifiAwareNetworkInfo(
    val network: android.net.Network,
    val peerAddress: java.net.Inet6Address,
    val port: Int,
    internal val callback: android.net.ConnectivityManager.NetworkCallback
)

data class DiscoveredWifiAwarePeer(
    val peerHandle: PeerHandle,
    val sessionId: String,
    val publicIdPrefix: String?,  // First 8 chars of public ID
    val port: Int?,
    val distanceMm: Int?,  // Distance in millimeters (if ranging supported)
    var lastSeen: Long
)
