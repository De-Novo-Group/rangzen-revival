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
        private const val EXCHANGE_INTERVAL_MS = 15_000L // Exchange every 15 seconds
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var bleScanner: BleScanner
    private lateinit var bleAdvertiser: BleAdvertiser
    private lateinit var friendStore: FriendStore
    private lateinit var messageStore: MessageStore
    private lateinit var legacyExchangeClient: LegacyExchangeClient
    private lateinit var legacyExchangeServer: LegacyExchangeServer
    private lateinit var exchangeHistory: ExchangeHistoryTracker
    private var cleanupJob: Job? = null

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
        
        Timber.i("RangzenService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundService()
            ACTION_STOP -> stopService()
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
        _status.value = ServiceStatus.DISCOVERING
        updateNotification(getString(R.string.status_discovering))
    }

    private fun stopBleOperations() {
        bleAdvertiser.stopAdvertising()
        bleScanner.stopScanning()
    }
    
    private fun startExchangeLoop() {
        exchangeJob?.cancel()
        exchangeJob = serviceScope.launch {
            while (isActive) {
                delay(EXCHANGE_INTERVAL_MS)
                performExchangeCycle()
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

    private suspend fun performExchangeCycle() {
        // Check cooldown timing before attempting exchanges.
        if (!readyToConnect()) {
            return
        }
        // Avoid initiating while we are serving an inbound exchange.
        if (bleAdvertiser.activeConnectionCount.value > 0) {
            // Skip initiating to reduce BLE contention.
            Timber.i("Skipping exchange initiation; inbound session active")
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
        // Determine peer selection strategy.
        val randomExchange = AppConfig.randomExchange(this)
        val peersToCheck = if (randomExchange) {
            // Pick the best peer based on last-picked time.
            pickBestPeer(currentPeers)?.let { listOf(it) } ?: emptyList()
        } else {
            // Use all peers when random exchange is disabled.
            currentPeers
        }
        // Run exchanges for selected peers.
        for (peer in peersToCheck) {
            // Only initiate if allowed by protocol rules.
            if (!shouldInitiateExchange(peer)) {
                continue
            }
            // Respect backoff rules before starting an exchange.
            if (!shouldAttemptExchange(peer)) {
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

    private fun setLastExchangeTime() {
        // Persist the last exchange time for cooldown enforcement.
        val prefs = getSharedPreferences("exchange_state", MODE_PRIVATE)
        prefs.edit().putLong(lastExchangePrefKey, System.currentTimeMillis()).apply()
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
        // Compute backoff delay for the current attempt count.
        val baseDelay = AppConfig.backoffAttemptMillis(this)
        val maxDelay = AppConfig.backoffMaxMillis(this)
        val backoffDelay = kotlin.math.min(
            Math.pow(2.0, history.attempts.toDouble()) * baseDelay,
            maxDelay.toDouble()
        )
        // Allow exchange if sufficient time has passed.
        val readyAt = history.lastExchangeTime + backoffDelay.toLong()
        return System.currentTimeMillis() > readyAt
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
        stopBleOperations()
        _status.value = ServiceStatus.STOPPED
    }
}
