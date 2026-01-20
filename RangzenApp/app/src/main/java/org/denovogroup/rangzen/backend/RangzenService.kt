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
import org.denovogroup.rangzen.ui.MainActivity
import timber.log.Timber
import java.util.*

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

        bleAdvertiser.onDataReceived = { data ->
            val message = String(data)
            Timber.i("Received data on advertiser: $message")
            "Echo: $message".toByteArray()
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

    private suspend fun performExchangeCycle() {
        val currentPeers = bleScanner.peers.value
        if (currentPeers.isEmpty()) {
            _status.value = ServiceStatus.IDLE
            updateNotification(getString(R.string.status_idle))
            return
        }
        
        updateNotification(getString(R.string.status_peers_found, currentPeers.size))
        
        _status.value = ServiceStatus.EXCHANGING
        val closestPeer = currentPeers.maxByOrNull { it.rssi }
        closestPeer?.let {
            try {
                val message = "Hello from ${UUID.randomUUID()}".toByteArray()
                Timber.i("Attempting to exchange data with ${it.address}")
                val response = bleScanner.exchange(it, message)
                if (response != null) {
                    val responseStr = String(response)
                    Timber.i("Received response: $responseStr")
                } else {
                    Timber.w("No response from peer ${it.address}")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error during BLE exchange with ${it.address}")
            }
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
        stopAllOperations()
        serviceScope.cancel()
        super.onDestroy()
        Timber.i("RangzenService destroyed")
    }

    private fun stopAllOperations() {
        exchangeJob?.cancel()
        stopBleOperations()
        _status.value = ServiceStatus.STOPPED
    }
}
