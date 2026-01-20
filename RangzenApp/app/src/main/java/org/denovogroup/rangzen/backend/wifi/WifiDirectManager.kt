/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * WiFi Direct Manager for Android 14+
 * Handles peer-to-peer WiFi connections for high-speed message exchange
 */
package org.denovogroup.rangzen.backend.wifi

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.denovogroup.rangzen.backend.ble.DiscoveredPeer
import timber.log.Timber
import java.net.InetAddress
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * WiFi Direct Manager for establishing peer-to-peer connections.
 * 
 * After discovering peers via BLE, WiFi Direct is used to establish
 * a high-bandwidth connection for transferring messages efficiently.
 */
class WifiDirectManager(private val context: Context) {

    /** WiFi P2P Manager */
    private var wifiP2pManager: WifiP2pManager? = null
    
    /** WiFi P2P Channel */
    private var channel: WifiP2pManager.Channel? = null

    /** Current connection state */
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** Connected peer info (group owner address) */
    private val _connectionInfo = MutableStateFlow<WifiP2pInfo?>(null)
    val connectionInfo: StateFlow<WifiP2pInfo?> = _connectionInfo.asStateFlow()

    /** Current WiFi Direct group */
    private val _groupInfo = MutableStateFlow<WifiP2pGroup?>(null)
    val groupInfo: StateFlow<WifiP2pGroup?> = _groupInfo.asStateFlow()

    /** Discovered WiFi Direct peers */
    private val _peers = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val peers: StateFlow<List<WifiP2pDevice>> = _peers.asStateFlow()

    /** Broadcast receiver for WiFi P2P events */
    private var broadcastReceiver: BroadcastReceiver? = null

    /** Whether manager is initialized */
    private var isInitialized = false

    /** Listener for connection events */
    var onConnectionEstablished: ((WifiP2pInfo) -> Unit)? = null
    var onConnectionLost: (() -> Unit)? = null
    var onPeersChanged: ((List<WifiP2pDevice>) -> Unit)? = null

    /**
     * Connection states
     */
    enum class ConnectionState {
        DISCONNECTED,
        DISCOVERING,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    /**
     * Initialize the WiFi Direct manager.
     */
    fun initialize() {
        if (isInitialized) return

        wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (wifiP2pManager == null) {
            Timber.e("WiFi P2P not supported on this device")
            return
        }

        channel = wifiP2pManager?.initialize(context, Looper.getMainLooper()) { 
            Timber.w("WiFi P2P channel disconnected")
            _connectionState.value = ConnectionState.DISCONNECTED
        }

        registerBroadcastReceiver()
        isInitialized = true
        Timber.i("WiFi Direct Manager initialized")
    }

    /**
     * Check if we have the required permissions for WiFi Direct.
     */
    fun hasPermissions(): Boolean {
        // WiFi Direct requires location permission on Android 10+
        val locationPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        // Android 13+ also needs NEARBY_WIFI_DEVICES
        val nearbyPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return locationPermission && nearbyPermission
    }

    /**
     * Register broadcast receiver for WiFi P2P events.
     */
    @SuppressLint("MissingPermission")
    private fun registerBroadcastReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(
                            WifiP2pManager.EXTRA_WIFI_STATE,
                            WifiP2pManager.WIFI_P2P_STATE_DISABLED
                        )
                        if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                            Timber.w("WiFi P2P is disabled")
                            _connectionState.value = ConnectionState.ERROR
                        }
                    }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        // Request updated peer list
                        if (hasPermissions()) {
                            wifiP2pManager?.requestPeers(channel) { peers ->
                                _peers.value = peers.deviceList.toList()
                                onPeersChanged?.invoke(_peers.value)
                                Timber.d("WiFi Direct peers updated: ${peers.deviceList.size}")
                            }
                        }
                    }

                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                WifiP2pManager.EXTRA_NETWORK_INFO,
                                NetworkInfo::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                        }

                        if (networkInfo?.isConnected == true) {
                            // We're connected, request connection info
                            wifiP2pManager?.requestConnectionInfo(channel) { info ->
                                _connectionInfo.value = info
                                _connectionState.value = ConnectionState.CONNECTED
                                Timber.i("WiFi Direct connected: ${info.groupOwnerAddress}")
                                onConnectionEstablished?.invoke(info)
                            }
                        } else {
                            _connectionInfo.value = null
                            _connectionState.value = ConnectionState.DISCONNECTED
                            onConnectionLost?.invoke()
                            Timber.i("WiFi Direct disconnected")
                        }
                    }

                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                WifiP2pManager.EXTRA_WIFI_P2P_DEVICE,
                                WifiP2pDevice::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                        }
                        Timber.d("This device status: ${device?.status}")
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                broadcastReceiver, 
                intentFilter, 
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            context.registerReceiver(broadcastReceiver, intentFilter)
        }
    }

    /**
     * Start discovering WiFi Direct peers.
     */
    @SuppressLint("MissingPermission")
    fun startDiscovery(): Boolean {
        if (!hasPermissions()) {
            Timber.e("Missing WiFi Direct permissions")
            return false
        }

        if (wifiP2pManager == null || channel == null) {
            Timber.e("WiFi Direct not initialized")
            return false
        }

        wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _connectionState.value = ConnectionState.DISCOVERING
                Timber.i("WiFi Direct discovery started")
            }

            override fun onFailure(reason: Int) {
                val reasonStr = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "P2P unsupported"
                    WifiP2pManager.BUSY -> "Busy"
                    WifiP2pManager.ERROR -> "Internal error"
                    else -> "Unknown ($reason)"
                }
                Timber.e("WiFi Direct discovery failed: $reasonStr")
                _connectionState.value = ConnectionState.ERROR
            }
        })
        return true
    }

    /**
     * Stop discovering peers.
     */
    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        if (!hasPermissions()) return

        wifiP2pManager?.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Timber.d("WiFi Direct discovery stopped")
            }

            override fun onFailure(reason: Int) {
                Timber.w("Failed to stop discovery: $reason")
            }
        })
    }

    /**
     * Connect to a WiFi Direct peer.
     * 
     * @param deviceAddress MAC address of the peer to connect to
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(deviceAddress: String): Boolean {
        if (!hasPermissions()) {
            Timber.e("Missing WiFi Direct permissions")
            return false
        }

        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
            wps.setup = WpsInfo.PBC // Push button configuration
            groupOwnerIntent = 15 // Make this device more likely to be group owner
        }

        _connectionState.value = ConnectionState.CONNECTING

        return suspendCancellableCoroutine { continuation ->
            wifiP2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Timber.i("WiFi Direct connection initiated to $deviceAddress")
                    // Connection success callback comes via broadcast
                    continuation.resume(true)
                }

                override fun onFailure(reason: Int) {
                    val reasonStr = when (reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> "P2P unsupported"
                        WifiP2pManager.BUSY -> "Busy"
                        WifiP2pManager.ERROR -> "Internal error"
                        else -> "Unknown ($reason)"
                    }
                    Timber.e("WiFi Direct connection failed: $reasonStr")
                    _connectionState.value = ConnectionState.ERROR
                    continuation.resume(false)
                }
            })
        }
    }


    /**
     * Connect to a WiFi Direct peer.
     *
     * @param peer The peer to connect to
     */
    @SuppressLint("MissingPermission")
    suspend fun connectToPeer(peer: DiscoveredPeer): Boolean {
        return connect(peer.address)
    }

    /**
     * Disconnect from current WiFi Direct group.
     */
    @SuppressLint("MissingPermission")
    suspend fun disconnect(): Boolean {
        if (!hasPermissions()) return false

        return suspendCancellableCoroutine { continuation ->
            wifiP2pManager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _connectionInfo.value = null
                    Timber.i("WiFi Direct disconnected")
                    continuation.resume(true)
                }

                override fun onFailure(reason: Int) {
                    Timber.w("Failed to disconnect: $reason")
                    continuation.resume(false)
                }
            })
        }
    }

    /**
     * Get the IP address of the group owner (for socket connection).
     */
    fun getGroupOwnerAddress(): InetAddress? {
        return _connectionInfo.value?.groupOwnerAddress
    }

    /**
     * Check if this device is the group owner.
     */
    fun isGroupOwner(): Boolean {
        return _connectionInfo.value?.isGroupOwner == true
    }

    /**
     * Cleanup resources.
     */
    fun cleanup() {
        broadcastReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                Timber.w("Error unregistering receiver: ${e.message}")
            }
        }
        broadcastReceiver = null
        isInitialized = false
        Timber.d("WiFi Direct Manager cleaned up")
    }
}
