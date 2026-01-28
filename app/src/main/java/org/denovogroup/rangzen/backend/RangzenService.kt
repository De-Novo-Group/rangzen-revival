/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * Rangzen Foreground Service for Android 14+
 * Manages BLE discovery and message exchange
 */
package org.denovogroup.rangzen.backend

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.denovogroup.rangzen.R
import org.denovogroup.rangzen.RangzenApplication
import org.denovogroup.rangzen.backend.ble.BleAdvertiser
import org.denovogroup.rangzen.backend.ble.BleScanner
import org.denovogroup.rangzen.backend.ble.DiscoveredPeer
import org.denovogroup.rangzen.backend.discovery.DiscoveredPeerRegistry
import org.denovogroup.rangzen.backend.discovery.TransportType
import org.denovogroup.rangzen.backend.legacy.LegacyExchangeClient
import org.denovogroup.rangzen.backend.legacy.LegacyExchangeServer
import org.denovogroup.rangzen.backend.lan.LanDiscoveryManager
import org.denovogroup.rangzen.backend.lan.LanTransport
import org.denovogroup.rangzen.backend.lan.NsdDiscoveryManager
import org.denovogroup.rangzen.backend.telemetry.LocationHelper
import org.denovogroup.rangzen.backend.telemetry.TelemetryClient
import org.denovogroup.rangzen.backend.telemetry.TelemetryEvent
import org.denovogroup.rangzen.backend.wifi.WifiDirectManager
import org.denovogroup.rangzen.backend.wifi.WifiDirectTransport
import org.denovogroup.rangzen.backend.wifiaware.WifiAwareManager
import org.denovogroup.rangzen.backend.wifiaware.WifiAwareExchange
import org.denovogroup.rangzen.backend.legacy.LegacyExchangeCodec
import org.denovogroup.rangzen.backend.discovery.TransportCapabilities
import org.denovogroup.rangzen.ui.MainActivity
import timber.log.Timber
import java.util.*
import java.security.MessageDigest

/**
 * Foreground Service that manages the Rangzen peer-to-peer network.
 */
class RangzenService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "org.denovogroup.rangzen.action.START"
        const val ACTION_STOP = "org.denovogroup.rangzen.action.STOP"
        const val ACTION_FORCE_EXCHANGE = "org.denovogroup.rangzen.action.FORCE_EXCHANGE"
        const val ACTION_SOFT_FORCE_EXCHANGE = "org.denovogroup.rangzen.action.SOFT_FORCE_EXCHANGE"
        const val ACTION_RADIO_CONFIG_CHANGED = "org.denovogroup.rangzen.action.RADIO_CONFIG_CHANGED"
        private const val EXCHANGE_INTERVAL_MS = 15_000L // Exchange every 15 seconds
        /** Swap initiator/responder roles after this many consecutive failures with a peer. */
        private const val ROLE_SWAP_THRESHOLD = 3

        @Volatile
        private var instance: RangzenService? = null

        /**
         * Get the running service instance, if any.
         */
        fun getServiceInstance(): RangzenService? = instance
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var bleScanner: BleScanner
    private lateinit var bleAdvertiser: BleAdvertiser
    private lateinit var wifiDirectManager: WifiDirectManager
    private val wifiDirectTransport = WifiDirectTransport()
    private var wifiAwareManager: WifiAwareManager? = null  // Nullable - not all devices support
    private lateinit var lanDiscoveryManager: LanDiscoveryManager
    private lateinit var nsdDiscoveryManager: NsdDiscoveryManager
    private val lanTransport = LanTransport()
    private lateinit var friendStore: FriendStore
    private lateinit var messageStore: MessageStore
    private lateinit var legacyExchangeClient: LegacyExchangeClient
    private lateinit var legacyExchangeServer: LegacyExchangeServer
    private lateinit var exchangeHistory: ExchangeHistoryTracker
    private lateinit var locationHelper: LocationHelper
    private var cleanupJob: Job? = null
    private var wifiDirectTaskJob: Job? = null
    private var wifiDirectAutoConnectJob: Job? = null
    private var lanDiscoveryJob: Job? = null
    private var parallelExchangeJob: Job? = null
    private var registryCleanupJob: Job? = null
    private var peerSnapshotJob: Job? = null

    // SharedPreferences key for last exchange time.
    private val lastExchangePrefKey = "last_exchange_time"
    private var exchangeJob: Job? = null
    
    /**
     * Centralized peer registry that aggregates discoveries from all transports.
     * This enables multi-transport discovery + app-layer correlation.
     */
    private val peerRegistry = DiscoveredPeerRegistry()

    val peers: StateFlow<List<DiscoveredPeer>> get() = bleScanner.peers
    
    /** Unified peer list from all transports */
    val unifiedPeers get() = peerRegistry.peerList

    private val _status = MutableStateFlow(ServiceStatus.STOPPED)
    val status: StateFlow<ServiceStatus> = _status.asStateFlow()

    private val _peerCount = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = _peerCount.asStateFlow()

    enum class ServiceStatus { STOPPED, STARTING, DISCOVERING, EXCHANGING, IDLE }

    /** Last exchange ID for bug reports */
    private var lastExchangeId: String? = null

    inner class LocalBinder : Binder() {
        fun getService(): RangzenService = this@RangzenService
    }

    /**
     * Get current transport state for bug reports.
     */
    fun getTransportState(): String {
        return when (_status.value) {
            ServiceStatus.STOPPED -> "stopped"
            ServiceStatus.STARTING -> "starting"
            ServiceStatus.DISCOVERING -> "discovering"
            ServiceStatus.EXCHANGING -> "exchanging"
            ServiceStatus.IDLE -> "idle"
        }
    }

    /**
     * Get the last exchange ID for bug reports.
     */
    fun getLastExchangeId(): String? = lastExchangeId

    override fun onCreate() {
        super.onCreate()
        instance = this
        bleScanner = BleScanner(this)
        bleAdvertiser = BleAdvertiser(this)
        friendStore = FriendStore.getInstance(this)
        messageStore = MessageStore.getInstance(this)
        legacyExchangeClient = LegacyExchangeClient(this, friendStore, messageStore)
        legacyExchangeServer = LegacyExchangeServer(this, friendStore, messageStore)
        exchangeHistory = ExchangeHistoryTracker.getInstance(this)
        locationHelper = LocationHelper(this)
        
        // Check Location Services status - critical for WiFi Direct
        checkLocationServicesStatus()

        bleAdvertiser.onDataReceived = { device, data ->
            Timber.i("Received legacy exchange request from ${device.address} (${data.size} bytes)")
            legacyExchangeServer.handleRequest(device, data)
        }

        // Set up callback for when peers are discovered via BLE
        bleScanner.onPeerDiscovered = { peer ->
            // Report to unified peer registry with publicId prefix from BLE ad
            peerRegistry.reportBlePeer(
                bleAddress = peer.address,
                device = peer.device,
                rssi = peer.rssi,
                name = peer.name,
                publicIdPrefix = peer.publicIdPrefix
            )
            _peerCount.value = bleScanner.peers.value.size
            Timber.i("BLE peer discovered: ${peer.address} (RSSI: ${peer.rssi}) - Total BLE peers: ${_peerCount.value}")
            updateNotification(getString(R.string.status_peers_found, _peerCount.value))
        }
        // Keep peer count in sync when the list changes (including stale removals).
        // Also refresh the unified peer registry with fresh timestamps.
        bleScanner.onPeersUpdated = { peers ->
            // Only re-report BLE peers that were scanned recently.
            // Stale BLE MACs (from MAC rotation) should NOT get their timestamps refreshed.
            val now = System.currentTimeMillis()
            val freshPeers = peers.filter { now - it.lastSeen < DiscoveredPeerRegistry.DEFAULT_STALE_MS }
            // Update the cached count to match fresh peers only.
            _peerCount.value = freshPeers.size
            // Update the notification to avoid stale peer counts.
            updateNotification(getString(R.string.status_peers_found, _peerCount.value))
            // Refresh the unified peer registry with fresh BLE peers only.
            freshPeers.forEach { peer ->
                peerRegistry.reportBlePeer(
                    bleAddress = peer.address,
                    device = peer.device,
                    rssi = peer.rssi,
                    name = peer.name,
                    publicIdPrefix = peer.publicIdPrefix
                )
            }
        }
        
        // Set up peer registry callbacks
        peerRegistry.onPeerDiscovered = { unifiedPeer ->
            val transports = unifiedPeer.transports.keys.joinToString(", ") { it.identifier() }
            Timber.i("Unified peer discovered: ${unifiedPeer.publicId} via [$transports]")
        }
        peerRegistry.onPeerTransportAdded = { peer, transport ->
            Timber.i("Peer ${peer.publicId} now reachable via ${transport.identifier()}")
        }
        
        // Initialize WiFi Direct Manager for extended discovery range
        // Multi-transport strategy: WiFi Direct discovery + BLE discovery + LAN discovery (future)
        // Each transport feeds into the unified peer registry
        wifiDirectManager = WifiDirectManager(this)
        wifiDirectManager.initialize()
        
        // Set up callback for when Murmur peers are discovered via DNS-SD
        // Transport v2: DNS-SD provides identity BEFORE connection (no blind popups)
        wifiDirectManager.onMurmurPeerDiscovered = { wifiPeer ->
            // Report to unified peer registry with full info from DNS-SD
            peerRegistry.reportWifiDirectPeer(
                wifiAddress = wifiPeer.wifiDirectAddress,
                deviceName = wifiPeer.deviceName,
                extractedId = wifiPeer.deviceId,      // Public ID from DNS-SD TXT record
                servicePort = wifiPeer.servicePort    // Exchange port from DNS-SD
            )
            Timber.i("DNS-SD: Murmur peer discovered: ${wifiPeer.deviceId.take(8)}... via ${wifiPeer.deviceName}")
        }
        
        // Also report raw WiFi Direct peers (those without DNS-SD service)
        // These will need app-layer handshake for identity verification
        wifiDirectManager.onPeersChanged = { wifiPeers ->
            wifiPeers.forEach { device ->
                // Only report if not already known via DNS-SD
                val knownViaDnsSd = wifiDirectManager.murmurPeers.value
                    .any { it.wifiDirectAddress == device.deviceAddress }
                if (!knownViaDnsSd) {
                    peerRegistry.reportWifiDirectPeer(
                        wifiAddress = device.deviceAddress,
                        deviceName = device.deviceName ?: "Unknown",
                        extractedId = null,  // Will need handshake for identity
                        servicePort = null
                    )
                }
            }
            Timber.d("WiFi Direct: ${wifiPeers.size} total peers, ${wifiDirectManager.murmurPeers.value.size} verified Murmur")
        }
        
        // Get privacy-preserving device ID (derived from crypto keypair, not hardware)
        // This ID is safe to share over network - cannot be traced to hardware identifiers
        val deviceId = DeviceIdentity.getDeviceId(this)

        Timber.i("Device ID (privacy-preserving): ${deviceId.take(8)}...")

        // Wire up WiFi Direct transport for high-bandwidth exchanges
        setupWifiDirectTransport(deviceId)

        // Initialize WiFi Aware (NAN) if supported - preferred over WiFi Direct (no dialogs!)
        // WiFi Aware provides WiFi-speed connections without user confirmation popups
        initializeWifiAware(deviceId)
        
        // Initialize LAN discovery for same-network peer finding
        // Works on shared WiFi (home, coffee shop, hotspot) even without Internet
        // Uses both UDP broadcast AND NSD/mDNS for maximum compatibility
        lanDiscoveryManager = LanDiscoveryManager(this)
        lanDiscoveryManager.initialize(deviceId)
        lanTransport.initialize(deviceId)
        setupLanDiscovery()
        
        // Initialize NSD (Network Service Discovery) for mDNS-based discovery
        // More reliable than UDP broadcast on many networks
        nsdDiscoveryManager = NsdDiscoveryManager(this)
        nsdDiscoveryManager.initialize(deviceId)
        setupNsdDiscovery()
        
        Timber.i("RangzenService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundService()
            ACTION_STOP -> stopService()
            ACTION_FORCE_EXCHANGE -> triggerImmediateExchange(respectInbound = false)
            ACTION_SOFT_FORCE_EXCHANGE -> triggerImmediateExchange(respectInbound = true)
            ACTION_RADIO_CONFIG_CHANGED -> handleRadioConfigChange()
        }
        return START_STICKY
    }

    /**
     * Handle radio configuration changes from Settings.
     * Restarts discovery with new radio settings.
     */
    private fun handleRadioConfigChange() {
        Timber.i("Radio configuration changed, restarting discovery")
        val prefs = getSharedPreferences("rangzen_prefs", 0)

        val bleEnabled = prefs.getBoolean("radio_ble_enabled", true)
        val wifiDirectEnabled = prefs.getBoolean("radio_wifi_direct_enabled", true)
        val wifiAwareEnabled = prefs.getBoolean("radio_wifi_aware_enabled", true)
        val lanEnabled = prefs.getBoolean("radio_lan_enabled", true)

        Timber.i("Radio config: BLE=$bleEnabled, WiFiDirect=$wifiDirectEnabled, WiFiAware=$wifiAwareEnabled, LAN=$lanEnabled")

        // Stop all discovery
        stopBleOperations()

        // Restart with new config
        if (bleEnabled) {
            startBleOperations()
        }

        // WiFi Direct is controlled by its own manager
        if (wifiDirectEnabled) {
            wifiDirectManager.startDiscovery()
        } else {
            wifiDirectManager.stopDiscovery()
        }

        // WiFi Aware
        if (wifiAwareEnabled && wifiAwareManager != null) {
            val deviceId = DeviceIdentity.getDeviceId(this)
            wifiAwareManager?.start(deviceId)
        } else {
            wifiAwareManager?.stop()
        }

        // LAN discovery
        if (lanEnabled) {
            lanDiscoveryManager.startDiscovery()
            nsdDiscoveryManager.start()
        } else {
            lanDiscoveryManager.stopDiscovery()
            nsdDiscoveryManager.stop()
        }
    }

    private fun startForegroundService() {
        _status.value = ServiceStatus.STARTING
        val notification = createNotification("Starting...")
        startForeground(NOTIFICATION_ID, notification)

        startBleOperations()
        startExchangeLoop()
        startCleanupLoop()
        startRegistryCleanupLoop()
        startPeerSnapshotLoop()

        Timber.i("RangzenService started")
    }

    private fun stopService() {
        stopAllOperations()
        stopForeground(true)
        stopSelf()
    }

    private fun startBleOperations() {
        // Set publicId on advertiser so BLE ads include identity for cross-transport correlation
        bleAdvertiser.localPublicId = DeviceIdentity.getDeviceId(this)
        bleAdvertiser.startAdvertising()
        bleScanner.startScanning()

        // Start WiFi Aware if supported (preferred - no user confirmation dialogs!)
        // WiFi Aware takes priority over WiFi Direct when both are available
        val deviceId = DeviceIdentity.getDeviceId(this)
        if (wifiAwareManager?.isSupported() == true) {
            wifiAwareManager?.start(deviceId)
            Timber.i("WiFi Aware discovery enabled (preferred over WiFi Direct)")
        }

        // Start WiFi Direct discovery alongside BLE
        // This extends our discovery range (WiFi Direct can see peers further away)
        // Note: If WiFi Aware is active, WiFi Direct still runs but may share radio resources
        if (wifiDirectManager.hasPermissions()) {
            wifiDirectManager.setSeekingDesired(true)
            startWifiDirectTaskLoop()
            startWifiDirectAutoConnectLoop()
            Timber.i("WiFi Direct discovery enabled")
        } else {
            Timber.w("WiFi Direct permissions not granted, skipping WiFi Direct discovery")
        }

        // Start LAN discovery for same-network peers (coffee shop, home WiFi, hotspot)
        // Uses both UDP broadcast AND NSD/mDNS for maximum compatibility
        lanDiscoveryManager.startDiscovery()
        nsdDiscoveryManager.start()
        lanTransport.startServer(this, messageStore, friendStore)
        startParallelExchangeLoop()  // Changed from sequential to parallel
        Timber.i("LAN + NSD discovery enabled")

        _status.value = ServiceStatus.DISCOVERING
        updateNotification(getString(R.string.status_discovering))
    }

    private fun stopBleOperations() {
        bleAdvertiser.stopAdvertising()
        bleScanner.stopScanning()

        // Stop WiFi Aware discovery
        wifiAwareManager?.stop()

        // Stop WiFi Direct discovery
        wifiDirectManager.setSeekingDesired(false)
        wifiDirectTaskJob?.cancel()
        wifiDirectTaskJob = null
        wifiDirectAutoConnectJob?.cancel()
        wifiDirectAutoConnectJob = null

        // Stop LAN discovery and NSD
        lanDiscoveryManager.stopDiscovery()
        nsdDiscoveryManager.stop()
        lanTransport.stopServer()
        lanDiscoveryJob?.cancel()
        lanDiscoveryJob = null
        parallelExchangeJob?.cancel()
        parallelExchangeJob = null
    }
    
    /**
     * Start the WiFi Direct task loop that maintains discovery state.
     * Following Casific's pattern of periodic tasks() calls.
     */
    private fun startWifiDirectTaskLoop() {
        wifiDirectTaskJob?.cancel()
        wifiDirectTaskJob = serviceScope.launch {
            while (isActive) {
                delay(60_000L) // Run WiFi Direct tasks every minute
                wifiDirectManager.tasks()
            }
        }
    }
    
    /**
     * Start auto-connection loop for WiFi Direct peers.
     * When we discover WiFi Direct peers, automatically attempt to connect
     * and exchange messages over the high-bandwidth socket transport.
     */
    private fun startWifiDirectAutoConnectLoop() {
        wifiDirectAutoConnectJob?.cancel()
        wifiDirectAutoConnectJob = serviceScope.launch {
            while (isActive) {
                delay(30_000L) // Check for WiFi Direct connection opportunities every 30 seconds
                attemptWifiDirectAutoConnect()
            }
        }
    }
    
    /**
     * Attempt to auto-connect to a discovered WiFi Direct peer.
     * This enables opportunistic high-bandwidth exchanges.
     */
    private suspend fun attemptWifiDirectAutoConnect() {
        // Only attempt if not already connected
        if (wifiDirectManager.connectionState.value == WifiDirectManager.ConnectionState.CONNECTED) {
            // Already connected, let the transport handle exchange
            return
        }
        
        // Only attempt if not currently connecting
        if (wifiDirectManager.connectionState.value == WifiDirectManager.ConnectionState.CONNECTING) {
            return
        }
        
        // Get discovered WiFi Direct peers
        val wifiPeers = wifiDirectManager.peers.value
        if (wifiPeers.isEmpty()) {
            return
        }
        
        // Pick a random peer to connect to
        val targetPeer = wifiPeers.random()
        
        Timber.i("Auto-connecting to WiFi Direct peer: ${targetPeer.deviceAddress}")
        
        try {
            val connected = wifiDirectManager.connect(targetPeer.deviceAddress)
            if (connected) {
                Timber.i("WiFi Direct auto-connect initiated to ${targetPeer.deviceAddress}")
            } else {
                Timber.w("WiFi Direct auto-connect failed to ${targetPeer.deviceAddress}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error during WiFi Direct auto-connect")
        }
    }
    
    /**
     * Start exchange loop for LAN peers.
     * When LAN peers are discovered, periodically attempt to exchange messages.
     */
    /**
     * Start the parallel exchange loop.
     * 
     * This is the key optimization: opportunistic encounters are our most
     * constrained resource. When we find peers, we use ALL available transports
     * simultaneously to maximize message exchange in the limited time available.
     * 
     * - BLE exchanges run independently
     * - LAN/NSD exchanges run in parallel with BLE
     * - Different peers can be exchanged with concurrently across transports
     */
    private fun startParallelExchangeLoop() {
        parallelExchangeJob?.cancel()
        parallelExchangeJob = serviceScope.launch {
            while (isActive) {
                delay(20_000L) // Check for exchange opportunities every 20 seconds
                peerRegistry.dumpPeers()
                attemptParallelExchanges()
            }
        }
    }
    
    /**
     * Attempt exchanges with all discovered peers across all transports IN PARALLEL.
     * 
     * Key principle: Time is precious. People passing on the street may only
     * be in range for seconds. We must do all we can in that brief window.
     */
    private suspend fun attemptParallelExchanges() {
        // =====================================================================
        // Transport v2: Multi-Transport Parallel Exchange with Arbitration
        // =====================================================================
        // Priority order (highest bandwidth first):
        // 1. WiFi Direct (if connected and verified via DNS-SD)
        // 2. LAN/NSD (if on same network)
        // 3. BLE (handled separately in performExchangeCycle)
        // =====================================================================
        
        // Collect peers from all transports
        val lanPeers = lanDiscoveryManager.getDiscoveredPeers()
        val nsdPeers = nsdDiscoveryManager.getDiscoveredPeers()
        val wifiDirectPeers = wifiDirectManager.murmurPeers.value  // DNS-SD verified peers
        
        // Merge LAN and NSD peers
        val lanPeerMap = lanPeers.associateBy { it.deviceId }
        val nsdOnlyPeers = nsdPeers
            .filter { nsdPeer -> !lanPeerMap.containsKey(nsdPeer.deviceId) }
            .map { nsdPeer ->
                LanDiscoveryManager.LanPeer(
                    deviceId = nsdPeer.deviceId,
                    ipAddress = java.net.InetAddress.getByName(nsdPeer.host),
                    port = nsdPeer.port,
                    lastSeen = nsdPeer.discoveredAt
                )
            }
        val allNetworkPeers = lanPeers + nsdOnlyPeers
        
        val wifiAwarePeerCount = wifiAwareManager?.getDiscoveredPeers()?.size ?: 0
        val totalPeers = allNetworkPeers.size + wifiDirectPeers.size + wifiAwarePeerCount
        if (totalPeers == 0) {
            return
        }

        Timber.i("PARALLEL EXCHANGE: Found $totalPeers peers " +
            "(${lanPeers.size} LAN, ${nsdOnlyPeers.size} NSD, ${wifiDirectPeers.size} WiFi Direct, $wifiAwarePeerCount WiFi Aware)")
        
        // Fetch location for QA telemetry (only if enabled)
        val location = if (TelemetryClient.getInstance()?.isEnabled() == true) {
            getLocationForTelemetry()
        } else {
            null
        }
        
        // Check concurrency guard before launching parallel jobs
        if (lanTransport.isExchangeInProgress()) {
            Timber.d("PARALLEL: Skipping LAN cycle - exchange already in progress")
        }
        
        val allJobs = mutableListOf<Job>()
        
        // =====================================================================
        // WiFi Direct Exchanges (Phase 3: Only with verified peers from DNS-SD)
        // =====================================================================
        // Only attempt WiFi Direct if:
        // 1. We have DNS-SD verified peers (handshakeCompleted = true)
        // 2. We're connected and not the group owner (clients initiate)
        // =====================================================================
        val wifiConnected = wifiDirectManager.connectionState.value == WifiDirectManager.ConnectionState.CONNECTED
        val isGroupOwner = wifiDirectManager.isGroupOwner()
        val groupOwnerAddress = wifiDirectManager.getGroupOwnerAddress()
        
        if (wifiConnected && !isGroupOwner && groupOwnerAddress != null && wifiDirectPeers.isNotEmpty()) {
            // We're a WiFi Direct client - initiate exchange with the group owner
            // The group owner should be one of our DNS-SD verified peers
            val localId = DeviceIdentity.getDeviceId(this)
            val exchangeData = legacyExchangeClient.prepareExchangeData()
            
            if (exchangeData != null) {
                val wifiJob = serviceScope.launch {
                    try {
                        Timber.i("PARALLEL: WiFi Direct exchange with group owner at ${groupOwnerAddress.hostAddress}")

                        val result = wifiDirectTransport.connectAndExchange(
                            groupOwnerAddress = groupOwnerAddress,
                            localPublicId = localId,
                            data = exchangeData,
                            context = this@RangzenService,
                            location = location
                        )

                        if (result != null) {
                            val received = legacyExchangeClient.processExchangeResponse(
                                result.responseData,
                                result.peerPublicId.hashCode().toString()
                            )
                            Timber.i("PARALLEL: WiFi Direct exchange complete with ${result.peerPublicId.take(8)}...")

                            if (received > 0) {
                                messageStore.refreshMessagesNow()
                            }
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "PARALLEL: WiFi Direct exchange failed")
                    }
                }
                allJobs.add(wifiJob)
            }
        }
        
        // =====================================================================
        // LAN/NSD Exchanges (parallel with WiFi Direct)
        // =====================================================================
        if (!lanTransport.isExchangeInProgress()) {
            for (lanPeer in allNetworkPeers) {
                val deviceId = lanPeer.deviceId
                val job = serviceScope.launch {
                    try {
                        val result = lanTransport.exchangeWithPeer(
                            peer = lanPeer,
                            context = this@RangzenService,
                            messageStore = messageStore,
                            friendStore = friendStore,
                            location = location
                        )

                        if (result.success) {
                            Timber.i("PARALLEL: LAN exchange complete with ${deviceId.take(8)}...: " +
                                "sent=${result.messagesSent}, received=${result.messagesReceived}")

                            if (result.messagesReceived > 0) {
                                messageStore.refreshMessagesNow()
                            }
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "PARALLEL: LAN exchange failed with ${deviceId.take(8)}...")
                    }
                }
                allJobs.add(job)
            }
        }
        
        // =====================================================================
        // WiFi Aware Exchanges (infrastructure-free, no user dialogs)
        // =====================================================================
        // Uses message-based exchange via discovery layer (not NDP) for reliability.
        // This works on all devices regardless of NDP interface count.
        // See WIFI_AWARE_MESSAGE_PLAN.md for details.
        // =====================================================================
        val lanDeviceIds = allNetworkPeers.map { it.deviceId }.toSet()
        val awarePeers = wifiAwareManager?.getDiscoveredPeers() ?: emptyList()

        if (awarePeers.isNotEmpty()) {
            for (awarePeer in awarePeers) {
                // Deduplicate: skip if we already have a LAN path to this peer
                val peerDeviceId = awarePeer.publicIdPrefix
                if (peerDeviceId != null && lanDeviceIds.any { it.startsWith(peerDeviceId) }) {
                    Timber.d("PARALLEL: Skipping WiFi Aware peer ${peerDeviceId} - already reachable via LAN")
                    continue
                }

                // Skip if exchange already in progress with this peer
                if (wifiAwareManager?.isExchangeInProgress(awarePeer.sessionId) == true) {
                    Timber.d("PARALLEL: WiFi Aware exchange already in progress with ${awarePeer.sessionId}")
                    continue
                }

                // Start message-based exchange (handles initiator coordination internally)
                // startExchange() returns false if peer has higher ID and should initiate
                val started = wifiAwareManager?.startExchange(awarePeer) ?: false
                if (started) {
                    Timber.i("PARALLEL: Started WiFi Aware message exchange with ${awarePeer.sessionId}")
                }
                // Exchange completion is handled asynchronously via onExchangeComplete callback
            }
        }

        // Note: BLE exchanges are handled by the separate exchange loop (performExchangeCycle)
        // which runs independently and concurrently with this loop.
        // This means BLE, LAN, WiFi Aware, and WiFi Direct exchanges can happen in TRUE parallel.
        
        // Wait for all exchanges to complete (or timeout)
        if (allJobs.isNotEmpty()) {
            try {
                withTimeout(45_000L) {  // Longer timeout to accommodate WiFi Direct
                    allJobs.forEach { it.join() }
                }
            } catch (e: TimeoutCancellationException) {
                Timber.w("PARALLEL: Some exchanges timed out")
            }
        }
        
        Timber.d("PARALLEL EXCHANGE: Completed cycle for $totalPeers peers")
    }
    
    /**
     * Attempt to exchange messages with discovered LAN peers.
     * @deprecated Use attemptParallelExchanges instead
     */
    @Deprecated("Use attemptParallelExchanges for concurrent multi-transport exchanges")
    private suspend fun attemptLanExchanges() {
        val lanPeers = lanDiscoveryManager.getDiscoveredPeers()
        if (lanPeers.isEmpty()) {
            return
        }
        
        Timber.d("Found ${lanPeers.size} LAN peers, attempting exchanges...")
        
        for (peer in lanPeers) {
            if (!lanTransport.isExchangeInProgress()) {
                try {
                    val result = lanTransport.exchangeWithPeer(
                        peer = peer,
                        context = this@RangzenService,
                        messageStore = messageStore,
                        friendStore = friendStore
                    )
                    
                    if (result.success) {
                        Timber.i("LAN exchange complete with ${peer.deviceId.take(8)}...: " +
                            "sent=${result.messagesSent}, received=${result.messagesReceived}")
                        
                        // Refresh feed if we received messages
                        if (result.messagesReceived > 0) {
                            messageStore.refreshMessagesNow()
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "LAN exchange failed with ${peer.ipAddress.hostAddress}")
                }
            }
        }
    }
    
    /**
     * Set up LAN discovery callbacks to feed into the unified peer registry.
     */
    private fun setupLanDiscovery() {
        lanDiscoveryManager.onPeerDiscovered = { lanPeer ->
            // Report to unified peer registry
            peerRegistry.reportLanPeer(
                ipAddress = lanPeer.ipAddress.hostAddress ?: "unknown",
                port = lanPeer.port,
                publicId = lanPeer.deviceId  // LAN discovery includes device ID
            )
            Timber.i("LAN peer discovered: ${lanPeer.deviceId.take(8)}... at ${lanPeer.ipAddress.hostAddress}")
        }
        
        lanDiscoveryManager.onPeerLost = { lanPeer ->
            Timber.i("LAN peer lost: ${lanPeer.deviceId.take(8)}...")
        }
        
        lanTransport.onExchangeComplete = { success, sent, received ->
            if (success) {
                Timber.i("LAN exchange callback: sent=$sent, received=$received")
            }
        }
    }
    
    /**
     * Set up NSD (mDNS) discovery callbacks to feed into the unified peer registry.
     * NSD is more reliable than UDP broadcast on many networks.
     */
    private fun setupNsdDiscovery() {
        nsdDiscoveryManager.onPeerDiscovered = { nsdPeer ->
            // Report to unified peer registry
            // NSD peers have same info as LAN peers since they use same port
            peerRegistry.reportLanPeer(
                ipAddress = nsdPeer.host,
                port = nsdPeer.port,
                publicId = nsdPeer.deviceId
            )
            Timber.i("NSD peer discovered: ${nsdPeer.deviceId.take(8)}... at ${nsdPeer.host}:${nsdPeer.port}")
        }
        
        nsdDiscoveryManager.onPeerLost = { nsdPeer ->
            Timber.i("NSD peer lost: ${nsdPeer.deviceId.take(8)}...")
        }
    }
    
    private fun startExchangeLoop() {
        exchangeJob?.cancel()
        exchangeJob = serviceScope.launch {
            while (isActive) {
                delay(EXCHANGE_INTERVAL_MS)
                performExchangeCycle(forceOutbound = false)
            }
        }
    }

    private fun startCleanupLoop() {
        cleanupJob?.cancel()
        cleanupJob = serviceScope.launch {
            while (isActive) {
                delay(60_000L)
                cleanupMessageStore()
            }
        }
    }
    
    /**
     * Start periodic cleanup of stale peers in the unified registry.
     */
    private fun startRegistryCleanupLoop() {
        registryCleanupJob?.cancel()
        registryCleanupJob = serviceScope.launch {
            while (isActive) {
                delay(15_000L) // Prune every 15 seconds
                peerRegistry.pruneStale(DiscoveredPeerRegistry.DEFAULT_STALE_MS)
            }
        }
    }

    /**
     * Start periodic peer snapshot reporting for network health monitoring.
     * Reports known peers every 5 minutes when telemetry is enabled.
     */
    private fun startPeerSnapshotLoop() {
        peerSnapshotJob?.cancel()
        peerSnapshotJob = serviceScope.launch {
            while (isActive) {
                delay(5 * 60 * 1000L) // Every 5 minutes
                sendPeerSnapshot()
            }
        }
    }

    /**
     * Send a peer_snapshot telemetry event with current peer information.
     */
    private fun sendPeerSnapshot() {
        val telemetry = TelemetryClient.getInstance() ?: return
        if (!telemetry.isEnabled()) return

        val peers = peerRegistry.peerList.value
        if (peers.isEmpty()) return

        // Build peer info list
        val knownPeers = peers.map { peer ->
            val bestTransportType = peer.bestTransport()
            val transportInfo = bestTransportType?.let { peer.transports[it] }
            mutableMapOf<String, Any>(
                "peer_id_hash" to peer.publicId.hashCode().toString(),
                "transport" to (bestTransportType?.identifier() ?: "unknown"),
                "last_seen_ms" to (System.currentTimeMillis() - (transportInfo?.lastSeen ?: System.currentTimeMillis())),
                "handshake_completed" to peer.handshakeCompleted,
                "transport_count" to peer.transports.size
            ).apply {
                transportInfo?.signalStrength?.let { put("rssi", it) }
            }
        }

        // Count by transport type
        var bleCount = 0
        var wifiDirectCount = 0
        var wifiAwareCount = 0
        var lanCount = 0

        peers.forEach { peer ->
            if (peer.hasTransport(TransportType.BLE)) bleCount++
            if (peer.hasTransport(TransportType.WIFI_DIRECT)) wifiDirectCount++
            if (peer.hasTransport(TransportType.WIFI_AWARE)) wifiAwareCount++
            if (peer.hasTransport(TransportType.LAN)) lanCount++
        }

        telemetry.trackPeerSnapshot(
            knownPeers = knownPeers,
            bleCount = bleCount,
            wifiDirectCount = wifiDirectCount,
            wifiAwareCount = wifiAwareCount,
            lanCount = lanCount
        )

        Timber.d("Peer snapshot sent: ${peers.size} peers (BLE=$bleCount, WiFiD=$wifiDirectCount, WiFiA=$wifiAwareCount, LAN=$lanCount)")

        // Also emit a node_profile snapshot
        sendNodeProfile(telemetry)
    }
    
    /**
     * Send a node_profile telemetry event with current device state.
     */
    private fun sendNodeProfile(telemetry: TelemetryClient) {
        try {
            val allMessages = messageStore.getAllMessages()
            val now = System.currentTimeMillis()
            val friendCount = friendStore.getAllFriendIds().size
            val heartedCount = allMessages.count { it.isLiked }
            val oldestAge = if (allMessages.isNotEmpty()) now - allMessages.minOf { it.timestamp } else 0L
            val newestAge = if (allMessages.isNotEmpty()) now - allMessages.maxOf { it.timestamp } else 0L

            telemetry.trackNodeProfile(
                messageCount = allMessages.size,
                friendCount = friendCount,
                heartedCount = heartedCount,
                oldestMessageAgeMs = oldestAge,
                newestMessageAgeMs = newestAge
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to send node profile")
        }
    }

    /**
     * Set up WiFi Direct transport for high-bandwidth message exchange.
     * 
     * When WiFi Direct connection is established:
     * - If we're the group owner, start server socket
     * - If we're a client, connect to group owner and exchange
     */
    private fun setupWifiDirectTransport(localPublicId: String) {
        // Handle incoming data when we're the server
        wifiDirectTransport.onDataReceived = { peerPublicId, data ->
            Timber.i("WiFi Direct transport received ${data.size} bytes from $peerPublicId")
            // Process the exchange data using the legacy exchange server logic
            // This maintains compatibility with the existing protocol
            try {
                legacyExchangeServer.processExchangeData(data)
            } catch (e: Exception) {
                Timber.e(e, "Error processing WiFi Direct exchange data")
                null
            }
        }

        // When we become group owner, start the transport server
        wifiDirectManager.onBecameGroupOwner = { groupOwnerAddress ->
            Timber.i("WiFi Direct: Became group owner at ${groupOwnerAddress.hostAddress}")
            wifiDirectTransport.startServer(serviceScope, localPublicId)
        }

        // When we become a client, we can optionally trigger an exchange
        // This will be called from the exchange cycle when WiFi Direct is available
        wifiDirectManager.onBecameClient = { groupOwnerAddress ->
            Timber.i("WiFi Direct: Became client, group owner at ${groupOwnerAddress.hostAddress}")
            // The exchange will be triggered by the normal exchange cycle
            // which will check for available WiFi Direct connection
        }

        // When WiFi Direct disconnects, stop the server
        wifiDirectManager.onConnectionLost = {
            Timber.i("WiFi Direct: Connection lost, stopping transport server")
            wifiDirectTransport.stopServer()
        }
    }

    /**
     * Initialize WiFi Aware (NAN) if supported on this device.
     *
     * WiFi Aware provides WiFi-speed peer connections WITHOUT user confirmation dialogs.
     * It's preferred over WiFi Direct when available.
     *
     * Uses message-based exchange (not NDP) for reliability across all devices.
     * See WIFI_AWARE_MESSAGE_PLAN.md for details.
     */
    @Suppress("NewApi")
    private fun initializeWifiAware(localPublicId: String) {
        if (!TransportCapabilities.isWifiAwareSupported(this)) {
            Timber.i("WiFi Aware not supported on this device, using WiFi Direct only")
            return
        }

        try {
            wifiAwareManager = WifiAwareManager(this, peerRegistry)

            // Set up callback for discovered peers
            wifiAwareManager?.onPeerDiscovered = { peer ->
                Timber.i("WiFi Aware: Peer discovered: ${peer.publicIdPrefix ?: peer.sessionId}")
            }

            wifiAwareManager?.onPeerLost = { sessionId ->
                Timber.d("WiFi Aware: Peer lost: $sessionId")
            }

            // Provider for messages to send during exchange
            wifiAwareManager?.getMessagesToSend = {
                prepareMessagesForWifiAwareExchange()
            }

            // Handle received messages from exchange
            wifiAwareManager?.onMessageReceived = { peerId, messageData ->
                handleWifiAwareMessageReceived(peerId, messageData)
            }

            // Handle exchange completion
            wifiAwareManager?.onExchangeComplete = { peerId, result ->
                handleWifiAwareExchangeComplete(peerId, result)
            }

            // Log capability info
            val summary = TransportCapabilities.getSupportedTransportsSummary(this)
            Timber.i("WiFi Aware initialized with message-based exchange. Device supports: $summary")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize WiFi Aware")
            wifiAwareManager = null
        }
    }

    /**
     * Prepare messages for WiFi Aware message-based exchange.
     * Converts RangzenMessages to the MessageToSend format.
     */
    private fun prepareMessagesForWifiAwareExchange(): List<WifiAwareExchange.MessageToSend> {
        val maxMessages = SecurityManager.maxMessagesPerExchange(this)
        val messages = messageStore.getMessagesForExchange(0, maxMessages)
        val myFriends = friendStore.getAllFriendIds().size

        return messages.map { msg ->
            // Encode message as JSON
            val encoded = LegacyExchangeCodec.encodeMessage(this, msg, 0, myFriends)
            val data = encoded.toString().toByteArray(Charsets.UTF_8)

            // Create hash from message ID for deduplication
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(msg.messageId.toByteArray(Charsets.UTF_8)).copyOf(8)

            WifiAwareExchange.MessageToSend(hash = hash, data = data)
        }
    }

    /**
     * Handle a message received during WiFi Aware exchange.
     */
    private fun handleWifiAwareMessageReceived(peerId: String, messageData: ByteArray) {
        try {
            val jsonStr = String(messageData, Charsets.UTF_8)
            val json = org.json.JSONObject(jsonStr)
            val msg = LegacyExchangeCodec.decodeMessage(json)

            if (msg.text.isNullOrEmpty()) {
                Timber.w("WiFi Aware: Received empty message from $peerId")
                return
            }

            // Add to store - returns false if duplicate or tombstoned
            if (messageStore.addMessage(msg)) {
                Timber.i("WiFi Aware: Stored new message from $peerId: ${msg.text?.take(30)}...")
            } else {
                Timber.d("WiFi Aware: Duplicate/rejected message from $peerId: ${msg.messageId}")
            }
        } catch (e: Exception) {
            Timber.e(e, "WiFi Aware: Failed to process message from $peerId")
        }
    }

    /**
     * Handle WiFi Aware exchange completion.
     */
    private fun handleWifiAwareExchangeComplete(peerId: String, result: WifiAwareExchange.ExchangeResult) {
        if (result.success) {
            Timber.i("WiFi Aware exchange complete with $peerId: sent=${result.messagesSent}, received=${result.messagesReceived}")
            if (result.messagesReceived > 0) {
                messageStore.refreshMessagesNow()
            }
        } else {
            Timber.w("WiFi Aware exchange failed with $peerId: ${result.errorReason}")
        }
    }
    
    /**
     * Attempt to exchange data over WiFi Direct if connected.
     * Falls back to BLE if WiFi Direct is not available.
     * 
     * @return true if exchange was attempted over WiFi Direct
     */
    private suspend fun attemptWifiDirectExchange(): Boolean {
        val connectionInfo = wifiDirectManager.connectionInfo.value ?: return false
        val groupOwnerAddress = connectionInfo.groupOwnerAddress ?: return false
        
        // Only attempt if we're the client (not group owner)
        if (connectionInfo.isGroupOwner) {
            // Group owner waits for incoming connections
            return false
        }
        
        val localId = wifiDirectManager.getDeviceIdentifier()
        
        // Prepare exchange data using the legacy client
        val exchangeData = legacyExchangeClient.prepareExchangeData()
        if (exchangeData == null) {
            Timber.w("WiFi Direct exchange: No data to send")
            return false
        }
        
        // Attempt the exchange
        val result = wifiDirectTransport.connectAndExchange(
            groupOwnerAddress = groupOwnerAddress,
            localPublicId = localId,
            data = exchangeData
        )
        
        if (result != null) {
            Timber.i("WiFi Direct exchange successful with ${result.peerPublicId}")
            // Process the response
            legacyExchangeClient.processExchangeResponse(result.responseData)
            return true
        }
        
        Timber.w("WiFi Direct exchange failed, will fall back to BLE")
        return false
    }

    private suspend fun performExchangeCycle(forceOutbound: Boolean, respectInbound: Boolean = true) {
        // Skip BLE exchanges when pairing mode is active to avoid resource contention.
        // The pairing UI needs exclusive access to BLE for verification.
        if (BleAdvertiser.pairingModeActive) {
            Timber.d("Skipping BLE exchange - pairing mode active")
            return
        }
        // Respect cooldown timing unless we are forcing an outbound attempt.
        if (!forceOutbound && !readyToConnect()) {
            return
        }
        // Avoid initiating while we are serving an inbound exchange.
        // When respectInbound=true (soft force), we still check inbound sessions.
        // When respectInbound=false (hard force), we skip this check entirely.
        if (respectInbound && shouldDeferForInboundSession()) {
            // Exit early to avoid BLE contention.
            Timber.d("Soft force exchange deferred - inbound session active")
            return
        }
        val currentPeers = bleScanner.peers.value
        // Clean exchange history to keep only active peers.
        exchangeHistory.cleanHistory(currentPeers.map { it.address })
        if (currentPeers.isEmpty()) {
            _status.value = ServiceStatus.IDLE
            updateNotification(getString(R.string.status_idle))
            return
        }
        
        updateNotification(getString(R.string.status_peers_found, currentPeers.size))
        
        _status.value = ServiceStatus.EXCHANGING
        // Determine peer selection strategy unless we are forcing outbound.
        val peersToCheck = if (forceOutbound) {
            // When forcing, try all peers to maximize delivery.
            currentPeers
        } else {
            // Follow the configured random-exchange strategy.
            val randomExchange = AppConfig.randomExchange(this)
            if (randomExchange) {
                // Pick the best peer based on last-picked time.
                pickBestPeer(currentPeers)?.let { listOf(it) } ?: emptyList()
            } else {
                // Use all peers when random exchange is disabled.
                currentPeers
            }
        }
        // Run exchanges for selected peers.
        for (peer in peersToCheck) {
            // Only initiate if allowed by protocol rules unless forced.
            if (!forceOutbound && !shouldInitiateExchange(peer)) {
                continue
            }
            // Respect backoff rules before starting an exchange unless forced.
            if (!forceOutbound && !shouldAttemptExchange(peer)) {
                continue
            }
            try {
                Timber.i("Attempting to exchange data with ${peer.address}")
                val result = legacyExchangeClient.exchangeWithPeer(bleScanner, peer)
                if (result != null) {
                    // Cross-transport correlation: merge BLE temp peer with WiFi Aware/LAN peer.
                    // LAN registers with full 16-char publicId, WiFi Aware with 8-char prefix.
                    // Try full ID first (LAN match), then 8-char prefix (WiFi Aware match).
                    result.peerPublicId?.let { pubId ->
                        peerRegistry.updatePeerIdAfterHandshake("ble:${peer.address}", pubId)
                    }
                    // Clear consecutive failure count on success.
                    exchangeHistory.resetFailures(peer.address)
                    // Update exchange history on success.
                    updateExchangeHistory(peer.address, result.messagesReceived > 0)
                    // Refresh messages when we receive data to update the UI feed.
                    if (result.messagesReceived > 0) {
                        // Trigger a store refresh to notify observers.
                        messageStore.refreshMessagesNow()
                    }
                    Timber.i(
                        "Exchange completed with ${peer.address} " +
                            "commonFriends=${result.commonFriends} " +
                            "sent=${result.messagesSent} received=${result.messagesReceived}"
                    )
                } else {
                    // Record consecutive failure for role-swap logic.
                    exchangeHistory.recordFailure(peer.address)
                    // Treat failure as an attempt for backoff purposes.
                    updateExchangeHistory(peer.address, hasNewMessages = false)
                    Timber.w("Exchange failed or timed out with ${peer.address}")
                }
            } catch (e: Exception) {
                // Record consecutive failure for role-swap logic.
                exchangeHistory.recordFailure(peer.address)
                // Record the attempt for backoff logic.
                updateExchangeHistory(peer.address, hasNewMessages = false)
                Timber.e(e, "Error during BLE exchange with ${peer.address}")
            } finally {
                // Update last exchange time regardless of outcome.
                setLastExchangeTime()
            }
        }
    }

    private fun updateExchangeHistory(address: String, hasNewMessages: Boolean) {
        // Update pick history to avoid repeatedly choosing the same peer.
        exchangeHistory.updatePickHistory(address)
        if (hasNewMessages) {
            // Reset attempts and store version when new data arrives.
            exchangeHistory.updateHistory(messageStore, address)
            exchangeHistory.incrementExchangeCount()
        } else {
            // Increment attempts for backoff handling.
            val existing = exchangeHistory.getHistoryItem(address)
            if (existing != null) {
                exchangeHistory.updateAttemptsHistory(address)
            } else {
                // Create history entry when first seen.
                exchangeHistory.updateHistory(messageStore, address)
            }
        }
    }

    private fun readyToConnect(): Boolean {
        // Read last exchange time from prefs.
        val prefs = getSharedPreferences("exchange_state", MODE_PRIVATE)
        val lastExchangeMillis = prefs.getLong(lastExchangePrefKey, -1)
        // Compute cooldown interval in millis.
        val cooldownMillis = AppConfig.exchangeCooldownSeconds(this) * 1000L
        // Allow immediately if no prior exchange.
        if (lastExchangeMillis == -1L) return true
        // Enforce cooldown interval.
        return System.currentTimeMillis() - lastExchangeMillis >= cooldownMillis
    }

    private fun shouldDeferForInboundSession(): Boolean {
        // Read the active inbound connection count.
        val activeInbound = bleAdvertiser.activeConnectionCount.value
        // Allow immediately when no inbound sessions are active.
        if (activeInbound <= 0) return false
        // Read the last inbound activity timestamp.
        val lastInboundActivity = bleAdvertiser.lastInboundActivityMs.value
        // Load the grace window from config.
        val graceMs = AppConfig.inboundSessionGraceMs(this)
        // Compute idle time since last inbound activity.
        val idleMs = System.currentTimeMillis() - lastInboundActivity
        // Defer when inbound activity is recent.
        if (idleMs < graceMs) {
            // Log the deferral for visibility.
            Timber.i(
                "Skipping exchange initiation; inbound session active " +
                    "(idleMs=$idleMs graceMs=$graceMs active=$activeInbound)"
            )
            return true
        }
        // Log when we override the defer to prevent stuck sessions.
        Timber.w(
            "Inbound session idle beyond grace; proceeding with outbound exchange " +
                "(idleMs=$idleMs graceMs=$graceMs active=$activeInbound)"
        )
        return false
    }

    private fun setLastExchangeTime() {
        // Persist the last exchange time for cooldown enforcement.
        val prefs = getSharedPreferences("exchange_state", MODE_PRIVATE)
        prefs.edit().putLong(lastExchangePrefKey, System.currentTimeMillis()).apply()
    }

    private fun triggerImmediateExchange(respectInbound: Boolean) {
        // Launch a one-off exchange attempt without cooldown/backoff gating.
        serviceScope.launch {
            // Run the exchange cycle with force enabled.
            // When respectInbound=true, we skip cooldown but still defer if inbound is active.
            performExchangeCycle(forceOutbound = true, respectInbound = respectInbound)
        }
    }

    private fun shouldAttemptExchange(peer: DiscoveredPeer): Boolean {
        // Allow exchange if backoff is disabled.
        if (!AppConfig.useBackoff(this)) return true
        // Look up history for this peer.
        val history = exchangeHistory.getHistoryItem(peer.address)
        // Allow when no history exists.
        if (history == null) return true
        // Allow when local store version has changed.
        val storeChanged = history.storeVersion != messageStore.getStoreVersion()
        if (storeChanged) return true
        // Use centralized BackoffMath for consistent computation.
        val baseDelay = AppConfig.backoffAttemptMillis(this)
        val maxDelay = AppConfig.backoffMaxMillis(this)
        return BackoffMath.isReadyForAttempt(
            history.lastExchangeTime,
            history.attempts,
            baseDelay,
            maxDelay
        )
    }

    private fun shouldInitiateExchange(peer: DiscoveredPeer): Boolean {
        // Fetch the local initiator identifier.
        val localId = deviceIdForInitiator()
        // If the ID is unavailable, default to initiating.
        if (localId.isNullOrBlank()) return true
        // Use deterministic initiator selection to avoid collisions.
        val initiator = whichInitiates(localId, peer.address)
        // If initiator is unknown, allow initiation to avoid deadlock.
        if (initiator.isNullOrBlank()) return true
        var shouldInitiate = (initiator == localId)

        // Role-swap: if we've failed with this peer multiple times in a row,
        // swap roles so the other device gets a chance as GATT client.
        val history = exchangeHistory.getHistoryItem(peer.address)
        if (history != null && history.consecutiveFailures >= ROLE_SWAP_THRESHOLD) {
            shouldInitiate = !shouldInitiate
            Timber.i(
                "Role-swap active for ${peer.address}: " +
                    "failures=${history.consecutiveFailures}, now ${if (shouldInitiate) "initiating" else "waiting"}"
            )
        }

        return shouldInitiate
    }

    private fun bluetoothAddressOrNull(): String? {
        // Read the adapter address; may return a dummy on modern Android.
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        val address = adapter?.address
        // Treat dummy address as unavailable.
        if (address == "02:00:00:00:00:00") return null
        return address
    }

    private fun deviceIdForInitiator(): String? {
        // Prefer Bluetooth address when it is usable.
        val bluetooth = bluetoothAddressOrNull()
        // Return Bluetooth address if available.
        if (!bluetooth.isNullOrBlank()) return bluetooth
        // Fall back to Android ID for deterministic selection.
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        // Return the Android ID if it is present.
        return androidId
    }

    private fun whichInitiates(a: String, b: String): String? {
        // Return null when inputs are missing.
        if (a.isBlank() || b.isBlank()) return null
        // Determine ordering for concatenation.
        val (first, second) = if (a < b) a to b else b to a
        // Compute the hash of the concatenated string.
        val hash = concatAndHash(first, second)
        // Decide based on the first bit of the hash.
        val startsWithOne = hash.firstOrNull()?.let { (it.toInt() and 0x80) != 0 } ?: false
        return if (startsWithOne) first else second
    }

    private fun concatAndHash(x: String, y: String): ByteArray {
        // Use SHA-256 for deterministic initiator selection.
        val md = MessageDigest.getInstance("SHA-256")
        // Hash the concatenated address string.
        return md.digest((x + y).toByteArray(Charsets.UTF_8))
    }

    private fun pickBestPeer(peers: List<DiscoveredPeer>): DiscoveredPeer? {
        // Track the best candidate by last-picked time.
        var bestPeer: DiscoveredPeer? = null
        var bestLastPicked: Long = Long.MAX_VALUE
        for (peer in peers) {
            val history = exchangeHistory.getHistoryItem(peer.address)
            // Prefer peers with no history.
            if (history == null) {
                bestPeer = peer
                break
            }
            // Choose the peer least recently picked.
            if (history.lastPicked < bestLastPicked) {
                bestPeer = peer
                bestLastPicked = history.lastPicked
            }
        }
        return bestPeer
    }

    private fun cleanupMessageStore() {
        // Heart-based expiration: 0 hearts=5d, 1 heart=7d, 2+ hearts=14d.
        messageStore.cleanupByHearts()
        // Run legacy auto-delete logic based on config.
        messageStore.deleteOutdatedOrIrrelevant(
            autodeleteEnabled = AppConfig.autodeleteEnabled(this),
            autodeleteTrustThreshold = AppConfig.autodeleteTrustThreshold(this),
            autodeleteAgeDays = AppConfig.autodeleteAgeDays(this)
        )
        // Prune old tombstones so the table doesn't grow unbounded.
        messageStore.pruneTombstones()
    }

    /**
     * Check if Location Services are enabled.
     * WiFi Direct discovery REQUIRES Location Services to be ON.
     * Without it, peer discovery silently fails.
     * 
     * This is called on service start and logs warnings/telemetry if disabled.
     */
    private fun checkLocationServicesStatus() {
        val isEnabled = locationHelper.isLocationServicesEnabled()
        val hasPermission = locationHelper.hasLocationPermission()
        
        Timber.i("Location Services check: enabled=$isEnabled, permission=$hasPermission")
        
        if (!isEnabled) {
            Timber.w("LOCATION SERVICES DISABLED - WiFi Direct discovery will NOT work!")
            Timber.w("User must enable Location Services in system settings for peer discovery")
            
            // Track this for telemetry
            TelemetryClient.getInstance()?.track(
                eventType = "location_services_disabled",
                payload = mapOf(
                    "has_permission" to hasPermission,
                    "impact" to "wifi_direct_discovery_blocked"
                )
            )
        }
        
        if (!hasPermission) {
            Timber.w("Location permission not granted - some discovery features may be limited")
        }
    }
    
    /**
     * Get current location for telemetry (fire-and-forget, async).
     * Returns location data for QA tracking of connections.
     */
    private suspend fun getLocationForTelemetry(): LocationHelper.LocationData? {
        return try {
            // Request fresh GPS fix (5s timeout), falling back to cached
            locationHelper.requestFreshLocation()
        } catch (e: Exception) {
            Timber.w(e, "Failed to get location for telemetry")
            null
        }
    }
    
    private fun updateNotification(statusText: String) {
        val notification = createNotification(statusText)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotification(statusText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE)
        
        return NotificationCompat.Builder(this, RangzenApplication.CHANNEL_ID_SERVICE)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .build()
    }
    
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        instance = null
        stopAllOperations()
        serviceScope.cancel()
        super.onDestroy()
        Timber.i("RangzenService destroyed")
    }

    private fun stopAllOperations() {
        exchangeJob?.cancel()
        cleanupJob?.cancel()
        wifiDirectTaskJob?.cancel()
        wifiDirectAutoConnectJob?.cancel()
        lanDiscoveryJob?.cancel()
        parallelExchangeJob?.cancel()
        registryCleanupJob?.cancel()
        peerSnapshotJob?.cancel()
        stopBleOperations()
        wifiAwareManager?.destroy()
        wifiDirectManager.cleanup()
        lanDiscoveryManager.cleanup()
        nsdDiscoveryManager.cleanup()
        lanTransport.cleanup()
        peerRegistry.clear()
        _status.value = ServiceStatus.STOPPED
    }
}
