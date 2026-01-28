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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.denovogroup.rangzen.backend.FriendStore
import org.denovogroup.rangzen.backend.lan.LanTransport
import org.denovogroup.rangzen.backend.discovery.DiscoveredPeerRegistry
import org.denovogroup.rangzen.backend.discovery.TransportCapabilities
import org.denovogroup.rangzen.backend.telemetry.TelemetryClient
import org.denovogroup.rangzen.backend.telemetry.TelemetryEvent
import timber.log.Timber
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

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

        /**
         * Minimum NDP (NAN Data-path) interfaces required to run responder.
         *
         * WiFi Aware uses NDI (NAN Data-path Interface) resources for data path connections.
         * From AOSP HAL documentation:
         *   - maxNdiInterfaces: "Maximum number of data interfaces (NDI) which can be
         *     created concurrently on the device."
         *   - maxNdpSessions: "Maximum number of data paths (NDP) which can be created
         *     concurrently on the device, across all data interfaces (NDI)."
         * Source: https://android.googlesource.com/platform/hardware/interfaces/+/master/wifi/1.0/types.hal
         *
         * Our responder calls connectivityManager.requestNetwork() which holds an NDI
         * interface in state 101 (waiting for connections). On devices with only 1 NDI:
         *   - Pixel 8 has maxNdiInterfaces=1 (but maxNdpSessions=8)
         *   - Pixel 4a/5 have maxNdiInterfaces=2 (and maxNdpSessions=2)
         *
         * When responder holds the only interface, initiator gets "no interfaces available"
         * error from WifiAwareDataPathStMgr.selectInterfaceForRequest().
         *
         * Solution: Only start responder if device has 2+ NDI interfaces. Single-interface
         * devices can still initiate connections to multi-interface devices.
         *
         * References:
         * - AOSP WiFi Aware: https://source.android.com/docs/core/connect/wifi-aware
         * - Android Characteristics class: https://developer.android.com/reference/android/net/wifi/aware/Characteristics
         * - WiFi HAL NanCapabilities: https://android.googlesource.com/platform/hardware/interfaces/+/master/wifi/1.0/types.hal
         */
        const val MIN_INTERFACES_FOR_RESPONDER = 2
    }

    // Device capability info (populated after attach)
    // See Characteristics.getNumberOfSupportedDataInterfaces() - added in API 31
    // Source: https://learn.microsoft.com/en-us/dotnet/api/android.net.wifi.aware.characteristics
    private var maxNdiInterfaces: Int = 0

    private var wifiAwareManager: android.net.wifi.aware.WifiAwareManager? = null
    private var wifiAwareSession: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // Track discovered peers by their session+handle
    private val discoveredPeers = ConcurrentHashMap<String, DiscoveredWifiAwarePeer>()

    // Active message exchanges per peer (sessionId -> Exchange)
    private val activeExchanges = ConcurrentHashMap<String, WifiAwareExchange>()

    // ==================================================================================
    // WiFi Aware PeerHandle Asymmetry - Critical Implementation Note
    // ==================================================================================
    //
    // WiFi Aware has a non-obvious behavior: the PeerHandle you DISCOVER a peer with
    // is different from the PeerHandle that incoming MESSAGES arrive from.
    //
    // Example flow:
    //   1. We discover peer via onServiceDiscovered() -> PeerHandle A (e.g., nan_186)
    //   2. We send HELLO to PeerHandle A (this works - we can only send to discovered handles)
    //   3. Peer receives our HELLO and sends HELLO_ACK back
    //   4. We receive HELLO_ACK from PeerHandle B (e.g., nan_188) - DIFFERENT handle!
    //   5. Subsequent MESSAGE/ACK packets also arrive from PeerHandle B
    //
    // WiFi Aware restriction: sendMessage() only works with handles from discovery callbacks.
    // Attempting to send to an "incoming" handle fails with "address didn't match/contact us".
    //
    // Solution: We track the mapping from incoming handles to peer publicIds, and from
    // publicIds to exchange sessions. This allows us to route incoming messages correctly
    // even when they arrive from different handles than we discovered.
    //
    // The publicId (first 8 chars of device's identity hash) is included in HELLO/HELLO_ACK
    // payloads and remains constant for each device across handle changes.
    // ==================================================================================

    // Map publicId -> sessionId: Routes messages by peer identity regardless of handle
    // Populated when we start an exchange (we know the peer's publicId from discovery or HELLO)
    private val publicIdToSessionId = ConcurrentHashMap<String, String>()

    // Map incoming handle hash -> publicId: Correlates incoming handles to peer identity
    // Populated when we receive HELLO/HELLO_ACK (which contain publicId in payload)
    // Used to route MESSAGE/MESSAGE_ACK/DONE/ERROR which don't include publicId
    private val incomingHandleToPublicId = ConcurrentHashMap<Int, String>()

    // Coroutine scope for exchanges
    private val exchangeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

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

    // Message exchange callbacks
    /** Called when a message is received from a peer during exchange. */
    var onMessageReceived: ((peerId: String, messageData: ByteArray) -> Unit)? = null
    /** Called when a message exchange completes. */
    var onExchangeComplete: ((peerId: String, result: WifiAwareExchange.ExchangeResult) -> Unit)? = null
    /** Provider for messages to send during exchange. */
    var getMessagesToSend: (() -> List<WifiAwareExchange.MessageToSend>)? = null

    // Pending message send callbacks (for async sendMessage)
    private val pendingMessageCallbacks = ConcurrentHashMap<Int, CompletableDeferred<Boolean>>()

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

        // Cancel all active exchanges
        activeExchanges.values.forEach { it.cancel("Manager stopping") }
        activeExchanges.clear()
        publicIdToSessionId.clear()
        incomingHandleToPublicId.clear()

        // Cancel pending message callbacks
        pendingMessageCallbacks.values.forEach { it.complete(false) }
        pendingMessageCallbacks.clear()

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

        // Validate peer is still in our discovered list (not stale from previous session)
        val currentPeer = discoveredPeers[peer.sessionId]
        if (currentPeer == null) {
            Timber.w("$TAG: Peer ${peer.sessionId} not in discovered list - may be stale")
            return@withContext null
        }
        if (currentPeer.peerHandle !== peer.peerHandle) {
            Timber.w("$TAG: Peer ${peer.sessionId} PeerHandle mismatch - using stale handle")
            return@withContext null
        }

        try {
            val discoverySession = subscribeSession ?: publishSession
            if (discoverySession == null) {
                Timber.w("$TAG: Cannot request network - no discovery session")
                return@withContext null
            }

            val specifier = WifiAwareNetworkSpecifier.Builder(
                discoverySession,
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
        exchangeScope.cancel()
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

            // Query device capabilities to determine NDP interface count.
            // Characteristics.getNumberOfSupportedDataInterfaces() returns max NDI count.
            // This API was added in Android 12 (API 31).
            // Source: https://developer.android.com/reference/android/net/wifi/aware/Characteristics
            val characteristics = wifiAwareManager?.characteristics
            maxNdiInterfaces = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                characteristics?.numberOfSupportedDataInterfaces ?: 0
            } else {
                // Pre-Android 12: assume 2 interfaces (conservative - allows responder)
                // Most WiFi Aware devices have 2, and we can't query pre-API 31
                2
            }
            Timber.i("$TAG: Device has $maxNdiInterfaces NDP interfaces (SDK=${Build.VERSION.SDK_INT})")

            // Clear any stale peers from previous session - PeerHandles are session-specific
            if (discoveredPeers.isNotEmpty()) {
                Timber.w("$TAG: Clearing ${discoveredPeers.size} stale peers from previous session")
                discoveredPeers.clear()
                _peerCount.value = 0
            }

            // Start publishing our service
            startPublish()

            // Start subscribing to discover others
            startSubscribe()

            // Start keepalive to prevent discovered peers from going stale
            startKeepalive()

            trackTelemetry("attached", mapOf("max_ndi_interfaces" to maxNdiInterfaces.toString()))
        }

        override fun onAttachFailed() {
            Timber.e("$TAG: Failed to attach to WiFi Aware")
            _isActive.value = false
            trackTelemetry("attach_failed")
        }

        override fun onAwareSessionTerminated() {
            Timber.w("$TAG: WiFi Aware session terminated - clearing all peers and exchanges")
            // Session terminated - all PeerHandles are now invalid
            // Cancel all active exchanges
            activeExchanges.values.forEach { it.cancel("Session terminated") }
            activeExchanges.clear()
            publicIdToSessionId.clear()
            incomingHandleToPublicId.clear()
            pendingMessageCallbacks.values.forEach { it.complete(false) }
            pendingMessageCallbacks.clear()
            discoveredPeers.clear()
            _peerCount.value = 0
            publishSession = null
            subscribeSession = null
            wifiAwareSession = null
            _isActive.value = false
            trackTelemetry("session_terminated")
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
            // NDP responder disabled - using message-based exchange instead.
            // Message exchange uses discovery layer sendMessage() which doesn't
            // require NDP interfaces, avoiding "no interfaces available" errors
            // and working reliably on all devices regardless of interface count.
            // See WIFI_AWARE_MESSAGE_PLAN.md for details.
            Timber.i("$TAG: Using message-based exchange (NDP responder disabled)")
        }

        override fun onSessionConfigFailed() {
            Timber.e("$TAG: Publish config failed")
            trackTelemetry("publish_failed")
        }

        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
            // Handle incoming messages from peers
            handleIncomingMessage(peerHandle, message)
        }

        override fun onMessageSendSucceeded(messageId: Int) {
            pendingMessageCallbacks.remove(messageId)?.complete(true)
        }

        override fun onMessageSendFailed(messageId: Int) {
            Timber.w("$TAG: Publish message send failed for id=$messageId")
            pendingMessageCallbacks.remove(messageId)?.complete(false)
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

        override fun onMessageSendSucceeded(messageId: Int) {
            pendingMessageCallbacks.remove(messageId)?.complete(true)
        }

        override fun onMessageSendFailed(messageId: Int) {
            Timber.w("$TAG: Subscribe message send failed for id=$messageId")
            pendingMessageCallbacks.remove(messageId)?.complete(false)
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

            // Cancel any active exchange with this peer
            activeExchanges.remove(sessionId)?.cancel("Peer lost")

            onPeerLost?.invoke(sessionId)
            trackTelemetry("peer_lost")
        }
    }

    /**
     * Handle incoming message from a peer.
     * Routes to active exchange or creates new exchange for incoming HELLO.
     *
     * IMPORTANT - WiFi Aware Handle Asymmetry:
     * The PeerHandle we receive messages FROM is different from the PeerHandle we
     * DISCOVERED the peer with. For example:
     *   - We discover peer via onServiceDiscovered() -> handle nan_186
     *   - We send HELLO to nan_186
     *   - Peer's HELLO_ACK arrives from nan_188 (different handle!)
     *   - Subsequent MESSAGE packets also arrive from nan_188
     *
     * We can ONLY send to discovered handles (nan_186). Sending to incoming handles
     * (nan_188) fails with "address didn't match/contact us" error.
     *
     * Routing Strategy:
     * 1. HELLO/HELLO_ACK contain publicId in payload -> extract and record mapping
     * 2. MESSAGE/MESSAGE_ACK/DONE/ERROR don't contain publicId -> use recorded mapping
     *
     * Maps used:
     * - incomingHandleToPublicId: incoming handle -> publicId (recorded from HELLO/HELLO_ACK)
     * - publicIdToSessionId: publicId -> sessionId (recorded when exchange starts)
     * - activeExchanges: sessionId -> exchange instance
     */
    private fun handleIncomingMessage(peerHandle: PeerHandle, message: ByteArray) {
        val incomingHandleId = "nan_${peerHandle.hashCode()}"
        Timber.d("$TAG: Received message from $incomingHandleId: ${message.size} bytes")

        // Parse the message to understand what it is
        val protoMessage = WifiAwareMessageProtocol.ProtocolMessage.deserialize(message)
        if (protoMessage == null) {
            Timber.w("$TAG: Received unparseable message from $incomingHandleId, ignoring")
            return
        }

        val msgTypeName = WifiAwareMessageProtocol.messageTypeName(protoMessage.type)

        // First, check if we have an active exchange that uses this exact incoming handle
        for ((sessionId, exchange) in activeExchanges) {
            if (exchange.peerHandle == peerHandle) {
                Timber.d("$TAG: Routing $msgTypeName to exchange $sessionId (exact handle match)")
                exchange.onMessage(message)
                return
            }
        }

        // For HELLO and HELLO_ACK, extract publicId to find matching peer/exchange
        if (protoMessage.type == WifiAwareMessageProtocol.MessageType.HELLO ||
            protoMessage.type == WifiAwareMessageProtocol.MessageType.HELLO_ACK) {

            val helloPayload = WifiAwareMessageProtocol.HelloPayload.deserialize(protoMessage.payload)
            if (helloPayload == null) {
                Timber.w("$TAG: Received $msgTypeName with invalid payload from $incomingHandleId")
                return
            }

            val peerPublicId = helloPayload.publicIdPrefix
            Timber.i("$TAG: Received $msgTypeName from publicId=$peerPublicId (incoming handle=$incomingHandleId)")

            // Track incoming handle -> publicId for routing subsequent messages (MESSAGE, ACK, DONE)
            // that don't contain publicId in their payload
            incomingHandleToPublicId[peerHandle.hashCode()] = peerPublicId

            // Find active exchange with this peer by publicId
            for ((sessionId, exchange) in activeExchanges) {
                val peer = discoveredPeers[sessionId]
                if (peer?.publicIdPrefix == peerPublicId) {
                    Timber.d("$TAG: Routing $msgTypeName to exchange $sessionId (publicId match)")
                    exchange.onMessage(message)
                    return
                }
            }

            // If HELLO_ACK with no matching exchange, log and ignore (we're not initiating to this peer)
            if (protoMessage.type == WifiAwareMessageProtocol.MessageType.HELLO_ACK) {
                Timber.w("$TAG: Received HELLO_ACK from $peerPublicId but no active exchange found")
                return
            }

            // Handle new HELLO - find discovered peer and start exchange
            handleHelloFromPeer(peerPublicId, protoMessage, message)
            return
        }

        // For MESSAGE, MESSAGE_ACK, DONE, ERROR: These don't contain publicId in their payload.
        // We use a two-step lookup:
        //   1. incomingHandleToPublicId: Look up which publicId this incoming handle belongs to
        //      (recorded when we received HELLO/HELLO_ACK from this handle earlier)
        //   2. publicIdToSessionId: Look up which exchange session is for this publicId
        //      (recorded when we started the exchange)
        // This allows routing even though the incoming handle differs from the discovered handle.
        val peerPublicId = incomingHandleToPublicId[peerHandle.hashCode()]
        if (peerPublicId != null) {
            val sessionId = publicIdToSessionId[peerPublicId]
            if (sessionId != null) {
                val exchange = activeExchanges[sessionId]
                if (exchange != null) {
                    Timber.d("$TAG: Routing $msgTypeName to exchange $sessionId via publicId=$peerPublicId")
                    exchange.onMessage(message)
                    return
                } else {
                    Timber.w("$TAG: Received $msgTypeName for $peerPublicId but exchange $sessionId no longer active")
                }
            } else {
                Timber.w("$TAG: Received $msgTypeName from $peerPublicId but no sessionId mapping found")
            }
        } else {
            Timber.w("$TAG: Received $msgTypeName from unknown handle $incomingHandleId (no publicId recorded)")
        }

        Timber.w("$TAG: Received $msgTypeName from $incomingHandleId with no matching exchange, ignoring")
    }

    /**
     * Handle a HELLO message initiating a new exchange.
     */
    private fun handleHelloFromPeer(peerPublicId: String, protoMessage: WifiAwareMessageProtocol.ProtocolMessage, rawMessage: ByteArray) {
        Timber.i("$TAG: Processing HELLO from publicId=$peerPublicId")

        // CRITICAL: Find the DISCOVERED peer by publicId - this is the handle we can send to
        val discoveredPeer = discoveredPeers.values.find { it.publicIdPrefix == peerPublicId }
        if (discoveredPeer == null) {
            // We received a HELLO from a peer we haven't discovered yet.
            // This can happen if they discovered us but we haven't discovered them.
            // In this case, we can't respond because we don't have a valid outbound handle.
            Timber.w("$TAG: Received HELLO from $peerPublicId but no discovered peer found - cannot respond")
            return
        }

        val sessionId = discoveredPeer.sessionId
        Timber.i("$TAG: Found discovered peer for $peerPublicId: session=$sessionId, " +
                "discovered handle=${discoveredPeer.peerHandle.hashCode()}")

        // Check if we already have an exchange for this peer
        if (activeExchanges.containsKey(sessionId)) {
            Timber.d("$TAG: Exchange already in progress for $sessionId, forwarding HELLO")
            activeExchanges[sessionId]?.onMessage(rawMessage)
            return
        }

        // Start exchange as responder using the DISCOVERED peer handle (for sending)
        Timber.i("$TAG: Starting exchange as responder with $peerPublicId (session=$sessionId)")
        val newExchange = createExchange(discoveredPeer, isInitiator = false)
        activeExchanges[sessionId] = newExchange
        // Track publicId -> sessionId for routing incoming messages
        publicIdToSessionId[peerPublicId] = sessionId
        newExchange.start(exchangeScope)
        newExchange.onMessage(rawMessage)
    }

    /**
     * Send a message to a peer (fire and forget).
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
     * Send a message to a peer asynchronously with delivery confirmation.
     * Uses message ID callback mechanism to confirm delivery.
     */
    suspend fun sendMessageAsync(peerHandle: PeerHandle, message: ByteArray): Boolean {
        val session = subscribeSession ?: publishSession
        if (session == null) {
            Timber.w("$TAG: Cannot send message - no discovery session")
            return false
        }

        return try {
            val messageId = System.identityHashCode(message)
            val deferred = CompletableDeferred<Boolean>()
            pendingMessageCallbacks[messageId] = deferred

            session.sendMessage(peerHandle, messageId, message)

            // Wait for callback with timeout
            withTimeoutOrNull(5000L) {
                deferred.await()
            } ?: run {
                Timber.w("$TAG: Message send timeout for messageId=$messageId")
                pendingMessageCallbacks.remove(messageId)
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to send message")
            false
        }
    }

    /**
     * Start a message exchange with a discovered peer.
     *
     * @param peer The peer to exchange with
     * @return true if exchange was started, false if already in progress or peer not found
     */
    fun startExchange(peer: DiscoveredWifiAwarePeer): Boolean {
        val sessionId = peer.sessionId

        // Check if exchange already in progress
        if (activeExchanges.containsKey(sessionId)) {
            Timber.d("$TAG: Exchange already in progress with $sessionId")
            return false
        }

        // Verify peer is still known
        if (!discoveredPeers.containsKey(sessionId)) {
            Timber.w("$TAG: Cannot start exchange - peer $sessionId not found")
            return false
        }

        // Determine if we should initiate based on publicId comparison
        val localId = localPublicId
        val peerId = peer.publicIdPrefix
        val shouldInitiate = when {
            localId == null || peerId == null -> true  // Can't compare, just initiate
            localId.take(8) > peerId -> true  // Higher ID initiates
            else -> false  // Wait for peer to initiate
        }

        if (!shouldInitiate) {
            Timber.d("$TAG: Waiting for peer $sessionId to initiate (their ID is higher)")
            return false
        }

        Timber.i("$TAG: Starting exchange with $sessionId as initiator")
        val exchange = createExchange(peer, isInitiator = true)
        activeExchanges[sessionId] = exchange
        // Track publicId -> sessionId for routing incoming messages
        peer.publicIdPrefix?.let { publicIdToSessionId[it] = sessionId }
        exchange.start(exchangeScope)

        trackTelemetry("exchange_started", mapOf(
            "peer_id" to (peerId ?: "unknown"),
            "is_initiator" to "true"
        ))

        return true
    }

    /**
     * Create a new exchange instance for a peer.
     */
    private fun createExchange(peer: DiscoveredWifiAwarePeer, isInitiator: Boolean): WifiAwareExchange {
        val sessionId = peer.sessionId
        val localId = localPublicId ?: ""

        return WifiAwareExchange(
            peerId = sessionId,
            peerHandle = peer.peerHandle,
            localPublicId = localId,
            isInitiator = isInitiator,
            sendMessage = { handle, data ->
                sendMessageAsync(handle, data)
            },
            getMessagesToSend = {
                getMessagesToSend?.invoke() ?: emptyList()
            },
            onMessageReceived = { messageData ->
                onMessageReceived?.invoke(sessionId, messageData)
            },
            onExchangeComplete = { result ->
                handleExchangeComplete(sessionId, result)
            }
        )
    }

    /**
     * Handle exchange completion.
     */
    private fun handleExchangeComplete(sessionId: String, result: WifiAwareExchange.ExchangeResult) {
        activeExchanges.remove(sessionId)
        // Clean up publicId -> sessionId mapping and related handle mappings
        val publicIdToRemove = publicIdToSessionId.entries.find { it.value == sessionId }?.key
        publicIdToSessionId.entries.removeIf { it.value == sessionId }
        if (publicIdToRemove != null) {
            incomingHandleToPublicId.entries.removeIf { it.value == publicIdToRemove }
        }

        Timber.i("$TAG: Exchange with $sessionId completed: " +
                "success=${result.success}, sent=${result.messagesSent}, received=${result.messagesReceived}" +
                (result.errorReason?.let { ", error=$it" } ?: ""))

        trackTelemetry(if (result.success) "exchange_success" else "exchange_failed", mapOf(
            "peer_id" to sessionId,
            "messages_sent" to result.messagesSent.toString(),
            "messages_received" to result.messagesReceived.toString(),
            "error" to (result.errorReason ?: "none")
        ))

        onExchangeComplete?.invoke(sessionId, result)
    }

    /**
     * Check if an exchange is in progress with the given peer.
     */
    fun isExchangeInProgress(sessionId: String): Boolean {
        return activeExchanges.containsKey(sessionId)
    }

    /**
     * Get the number of active exchanges.
     */
    fun getActiveExchangeCount(): Int = activeExchanges.size

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

    /**
     * Get local public ID used for WiFi Aware discovery.
     */
    fun getLocalPublicId(): String? = localPublicId

    /**
     * Check if this device has responder capability (can accept incoming NDP).
     * Devices with only 1 NDP interface don't run responder to avoid blocking initiator.
     */
    fun hasResponderCapability(): Boolean {
        return maxNdiInterfaces >= MIN_INTERFACES_FOR_RESPONDER
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
