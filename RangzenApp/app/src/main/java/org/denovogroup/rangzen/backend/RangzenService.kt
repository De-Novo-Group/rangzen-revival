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
import org.denovogroup.rangzen.backend.legacy.LegacyExchangeClient
import org.denovogroup.rangzen.backend.legacy.LegacyExchangeServer
import org.denovogroup.rangzen.backend.wifi.WifiDirectManager
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
        private const val EXCHANGE_INTERVAL_MS = 15_000L // Exchange every 15 seconds
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var bleScanner: BleScanner
    private lateinit var bleAdvertiser: BleAdvertiser
    private lateinit var wifiDirectManager: WifiDirectManager
    private lateinit var friendStore: FriendStore
    private lateinit var messageStore: MessageStore
    private lateinit var legacyExchangeClient: LegacyExchangeClient
    private lateinit var legacyExchangeServer: LegacyExchangeServer
    private lateinit var exchangeHistory: ExchangeHistoryTracker
    private var cleanupJob: Job? = null
    private var wifiDirectTaskJob: Job? = null

    // SharedPreferences key for last exchange time.
    private val lastExchangePrefKey = "last_exchange_time"
    private var exchangeJob: Job? = null

    val peers: StateFlow<List<DiscoveredPeer>> get() = bleScanner.peers

    private val _status = MutableStateFlow(ServiceStatus.STOPPED)
    val status: StateFlow<ServiceStatus> = _status.asStateFlow()

    private val _peerCount = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = _peerCount.asStateFlow()

    enum class ServiceStatus { STOPPED, STARTING, DISCOVERING, EXCHANGING, IDLE }

    inner class LocalBinder : Binder() {
        fun getService(): RangzenService = this@RangzenService
    }

    override fun onCreate() {
        super.onCreate()
        bleScanner = BleScanner(this)
        bleAdvertiser = BleAdvertiser(this)
        friendStore = FriendStore.getInstance(this)
        messageStore = MessageStore.getInstance(this)
        legacyExchangeClient = LegacyExchangeClient(this, friendStore, messageStore)
        legacyExchangeServer = LegacyExchangeServer(this, friendStore, messageStore)
        exchangeHistory = ExchangeHistoryTracker.getInstance(this)

        bleAdvertiser.onDataReceived = { device, data ->
            Timber.i("Received legacy exchange request from ${device.address} (${data.size} bytes)")
            legacyExchangeServer.handleRequest(device, data)
        }

        // Set up callback for when peers are discovered
        bleScanner.onPeerDiscovered = { peer ->
            _peerCount.value = bleScanner.peers.value.size
            Timber.i("Peer discovered: ${peer.address} (RSSI: ${peer.rssi}) - Total peers: ${_peerCount.value}")
            updateNotification(getString(R.string.status_peers_found, _peerCount.value))
        }
        // Keep peer count in sync when the list changes (including stale removals).
        bleScanner.onPeersUpdated = { peers ->
            // Update the cached count to match the latest list.
            _peerCount.value = peers.size
            // Update the notification to avoid stale peer counts.
            updateNotification(getString(R.string.status_peers_found, _peerCount.value))
        }
        
        // Initialize WiFi Direct Manager for RSVP-by-name peer discovery
        // Following Casific's approach: WiFi Direct broadcasts our identifier,
        // and actual message exchange still happens over BLE.
        wifiDirectManager = WifiDirectManager(this)
        wifiDirectManager.initialize()
        
        // Set up callback for when Murmur peers are discovered via WiFi Direct
        wifiDirectManager.onMurmurPeerDiscovered = { wifiPeer ->
            Timber.i("Murmur peer discovered via WiFi Direct RSVP: ${wifiPeer.deviceId}")
            // Note: We log WiFi Direct discoveries but actual exchange still happens
            // when the peer is seen via BLE. WiFi Direct extends discovery range
            // but doesn't replace BLE for the exchange protocol.
        }
        
        // Set our WiFi Direct RSVP name to broadcast our device identifier
        val deviceId = wifiDirectManager.getDeviceIdentifier()
        wifiDirectManager.setRsvpName(deviceId)
        Timber.i("WiFi Direct RSVP initialized with identifier: $deviceId")
        
        Timber.i("RangzenService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundService()
            ACTION_STOP -> stopService()
            ACTION_FORCE_EXCHANGE -> triggerImmediateExchange(respectInbound = false)
            ACTION_SOFT_FORCE_EXCHANGE -> triggerImmediateExchange(respectInbound = true)
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        _status.value = ServiceStatus.STARTING
        val notification = createNotification("Starting...")
        startForeground(NOTIFICATION_ID, notification)
        
        startBleOperations()
        startExchangeLoop()
        startCleanupLoop()
        
        Timber.i("RangzenService started")
    }

    private fun stopService() {
        stopAllOperations()
        stopForeground(true)
        stopSelf()
    }

    private fun startBleOperations() {
        bleAdvertiser.startAdvertising()
        bleScanner.startScanning()
        
        // Start WiFi Direct discovery alongside BLE
        // This extends our discovery range (WiFi Direct can see peers further away)
        if (wifiDirectManager.hasPermissions()) {
            wifiDirectManager.setSeekingDesired(true)
            startWifiDirectTaskLoop()
            Timber.i("WiFi Direct discovery enabled")
        } else {
            Timber.w("WiFi Direct permissions not granted, skipping WiFi Direct discovery")
        }
        
        _status.value = ServiceStatus.DISCOVERING
        updateNotification(getString(R.string.status_discovering))
    }

    private fun stopBleOperations() {
        bleAdvertiser.stopAdvertising()
        bleScanner.stopScanning()
        
        // Stop WiFi Direct discovery
        wifiDirectManager.setSeekingDesired(false)
        wifiDirectTaskJob?.cancel()
        wifiDirectTaskJob = null
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

    private suspend fun performExchangeCycle(forceOutbound: Boolean, respectInbound: Boolean = true) {
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
                    // Treat failure as an attempt for backoff purposes.
                    updateExchangeHistory(peer.address, hasNewMessages = false)
                    Timber.w("Exchange failed or timed out with ${peer.address}")
                }
            } catch (e: Exception) {
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
        // Only initiate when we are the selected initiator.
        return initiator == localId
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
        // Run legacy auto-delete logic based on config.
        messageStore.deleteOutdatedOrIrrelevant(
            autodeleteEnabled = AppConfig.autodeleteEnabled(this),
            autodeleteTrustThreshold = AppConfig.autodeleteTrustThreshold(this),
            autodeleteAgeDays = AppConfig.autodeleteAgeDays(this)
        )
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
        stopAllOperations()
        serviceScope.cancel()
        super.onDestroy()
        Timber.i("RangzenService destroyed")
    }

    private fun stopAllOperations() {
        exchangeJob?.cancel()
        cleanupJob?.cancel()
        wifiDirectTaskJob?.cancel()
        stopBleOperations()
        wifiDirectManager.cleanup()
        _status.value = ServiceStatus.STOPPED
    }
}
