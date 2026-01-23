/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * WiFi Direct Manager for Android 14+
 * 
 * Following Casific's design: WiFi Direct is used as a DISCOVERY mechanism.
 * We broadcast our BLE/device identifier in the WiFi Direct device name (RSVP-by-name).
 * Peers that see us can extract our identifier and connect via BLE for message exchange.
 * 
 * This avoids the complexity of WiFi Direct socket transport while leveraging WiFi Direct's
 * superior discovery range compared to BLE advertising alone.
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
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.denovogroup.rangzen.backend.ble.DiscoveredPeer
import org.denovogroup.rangzen.backend.telemetry.TelemetryClient
import timber.log.Timber
import java.net.InetAddress
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * WiFi Direct Manager for peer discovery via RSVP-by-name.
 * 
 * Following Casific's proven design:
 * 1. We broadcast our device identifier in the WiFi Direct device name (e.g., "MURMUR-<id>")
 * 2. When we discover other Murmur peers, we extract their identifier from their device name
 * 3. Actual message exchange happens over BLE (not WiFi Direct sockets)
 * 
 * This approach leverages WiFi Direct's greater discovery range while keeping
 * the proven BLE exchange protocol intact.
 */
class WifiDirectManager(private val context: Context) {

    companion object {
        /** Prefix for RSVP device names - peers with this prefix are Murmur devices */
        const val RSVP_PREFIX = "MURMUR-"
        
        /** Dummy Bluetooth MAC that Android returns when address is unavailable */
        private const val DUMMY_BT_ADDRESS = "02:00:00:00:00:00"
        
        /** Reserved MAC addresses we should ignore */
        private val RESERVED_MAC_PREFIXES = listOf("00:00:00", "FF:FF:FF")
    }

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

    /** Discovered WiFi Direct peers (raw) */
    private val _peers = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val peers: StateFlow<List<WifiP2pDevice>> = _peers.asStateFlow()

    /** Discovered Murmur peers (extracted from WiFi Direct device names) */
    private val _murmurPeers = MutableStateFlow<List<WifiDirectPeer>>(emptyList())
    val murmurPeers: StateFlow<List<WifiDirectPeer>> = _murmurPeers.asStateFlow()

    /** Broadcast receiver for WiFi P2P events */
    private var broadcastReceiver: BroadcastReceiver? = null

    /** Whether manager is initialized */
    private var isInitialized = false
    
    /** Whether discovery is currently active */
    private var isDiscovering = false
    
    /** Whether we want discovery to be running */
    private var seekingDesired = false

    /** Listener for connection events */
    var onConnectionEstablished: ((WifiP2pInfo) -> Unit)? = null
    var onConnectionLost: (() -> Unit)? = null
    var onPeersChanged: ((List<WifiP2pDevice>) -> Unit)? = null
    
    /** Callback when a Murmur peer is discovered via WiFi Direct */
    var onMurmurPeerDiscovered: ((WifiDirectPeer) -> Unit)? = null
    
    /** Callback when we become connected as group owner (should start server) */
    var onBecameGroupOwner: ((groupOwnerAddress: java.net.InetAddress) -> Unit)? = null
    
    /** Callback when we become connected as client (should connect to group owner) */
    var onBecameClient: ((groupOwnerAddress: java.net.InetAddress) -> Unit)? = null

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
     * Represents a Murmur peer discovered via WiFi Direct RSVP.
     * Contains the identifier extracted from the WiFi Direct device name.
     */
    data class WifiDirectPeer(
        val deviceId: String,           // Extracted identifier (BLE address or Android ID)
        val wifiDirectAddress: String,  // WiFi P2P device address
        val deviceName: String,         // Full WiFi Direct device name
        val discoveredAt: Long = System.currentTimeMillis()
    )

    /**
     * Initialize the WiFi Direct manager.
     */
    fun initialize() {
        if (isInitialized) return

        wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (wifiP2pManager == null) {
            Timber.e("WiFi P2P not supported on this device")
            trackTelemetry("wifi_direct_init_failed", "reason" to "not_supported")
            return
        }

        channel = wifiP2pManager?.initialize(context, Looper.getMainLooper()) { 
            Timber.w("WiFi P2P channel disconnected")
            _connectionState.value = ConnectionState.DISCONNECTED
            trackTelemetry("wifi_direct_channel_disconnected")
        }

        registerBroadcastReceiver()
        isInitialized = true
        Timber.i("WiFi Direct Manager initialized")
        trackTelemetry("wifi_direct_initialized")
    }
    
    /**
     * Set the WiFi Direct device name to broadcast our identifier (RSVP-by-name).
     * Uses reflection to access the hidden setDeviceName API.
     * 
     * @param identifier The device identifier to broadcast (BLE address or Android ID)
     */
    @SuppressLint("MissingPermission", "PrivateApi")
    fun setRsvpName(identifier: String) {
        if (wifiP2pManager == null || channel == null) {
            Timber.w("Cannot set RSVP name: WiFi Direct not initialized")
            return
        }
        
        val rsvpName = "$RSVP_PREFIX$identifier"
        
        try {
            // The setDeviceName method is hidden but accessible via reflection.
            // This is the same technique Casific used successfully.
            val method = wifiP2pManager!!.javaClass.getMethod(
                "setDeviceName",
                WifiP2pManager.Channel::class.java,
                String::class.java,
                WifiP2pManager.ActionListener::class.java
            )
            
            method.invoke(wifiP2pManager, channel, rsvpName, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Timber.i("WiFi Direct RSVP name set to: $rsvpName")
                    trackTelemetry("wifi_direct_rsvp_name_set", "name" to rsvpName)
                }
                
                override fun onFailure(reason: Int) {
                    val reasonStr = when (reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> "P2P unsupported"
                        WifiP2pManager.BUSY -> "Busy"
                        WifiP2pManager.ERROR -> "Internal error"
                        else -> "Unknown ($reason)"
                    }
                    Timber.e("Failed to set WiFi Direct RSVP name: $reasonStr")
                    trackTelemetry("wifi_direct_rsvp_name_failed", "reason" to reasonStr)
                }
            })
        } catch (e: NoSuchMethodException) {
            Timber.e(e, "setDeviceName method not found - may not be available on this Android version")
            trackTelemetry("wifi_direct_rsvp_reflection_failed", "error" to "NoSuchMethodException")
        } catch (e: Exception) {
            Timber.e(e, "Failed to set WiFi Direct device name via reflection")
            trackTelemetry("wifi_direct_rsvp_reflection_failed", "error" to e.javaClass.simpleName)
        }
    }
    
    /**
     * Get the device identifier to use for RSVP broadcasting.
     * Prefers Bluetooth address, falls back to Android ID.
     */
    fun getDeviceIdentifier(): String {
        // Try to get Bluetooth adapter address first
        val btAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        val btAddress = btAdapter?.address
        
        // Use BT address if it's valid (not the dummy address)
        if (!btAddress.isNullOrBlank() && btAddress != DUMMY_BT_ADDRESS) {
            return btAddress
        }
        
        // Fall back to Android ID
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return androidId ?: "UNKNOWN"
    }
    
    /**
     * Set seeking desired state. When true, discovery will be started/maintained.
     */
    fun setSeekingDesired(desired: Boolean) {
        seekingDesired = desired
        if (desired && !isDiscovering) {
            startDiscovery()
        } else if (!desired && isDiscovering) {
            stopDiscovery()
        }
    }
    
    /**
     * Run periodic tasks - call this from the service's background loop.
     * Maintains discovery state according to seekingDesired flag.
     */
    fun tasks() {
        if (seekingDesired && !isDiscovering) {
            startDiscovery()
        } else if (!seekingDesired && isDiscovering) {
            stopDiscovery()
        }
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
            addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
        }

        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        onWifiP2pStateChanged(intent)
                    }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        onWifiP2pPeersChanged(intent)
                    }

                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        onWifiP2pConnectionChanged(intent)
                    }

                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        onWifiP2pThisDeviceChanged(intent)
                    }
                    
                    WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                        onWifiP2pDiscoveryChanged(intent)
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
     * Handle WiFi P2P state changes (enabled/disabled).
     */
    private fun onWifiP2pStateChanged(intent: Intent) {
        val state = intent.getIntExtra(
            WifiP2pManager.EXTRA_WIFI_STATE,
            WifiP2pManager.WIFI_P2P_STATE_DISABLED
        )
        if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
            Timber.d("WiFi P2P is enabled")
            trackTelemetry("wifi_direct_state_enabled")
        } else {
            Timber.w("WiFi P2P is disabled")
            _connectionState.value = ConnectionState.ERROR
            trackTelemetry("wifi_direct_state_disabled")
        }
    }
    
    /**
     * Handle WiFi P2P peers changed event.
     * This is the core RSVP discovery logic - we look for peers with MURMUR- prefix
     * in their device name and extract their identifier.
     */
    @SuppressLint("MissingPermission")
    private fun onWifiP2pPeersChanged(intent: Intent) {
        if (!hasPermissions()) return
        
        wifiP2pManager?.requestPeers(channel) { peerList ->
            val allPeers = peerList.deviceList.toList()
            _peers.value = allPeers
            onPeersChanged?.invoke(allPeers)
            
            Timber.d("WiFi Direct peers changed: ${allPeers.size} total")
            
            // Extract Murmur peers from device names (RSVP-by-name pattern)
            val foundMurmurPeers = mutableListOf<WifiDirectPeer>()
            
            for (device in allPeers) {
                val deviceName = device.deviceName
                if (deviceName != null && deviceName.startsWith(RSVP_PREFIX)) {
                    // Extract the identifier from the device name
                    val identifier = deviceName.removePrefix(RSVP_PREFIX)
                    
                    // Validate the identifier looks reasonable
                    if (identifier.isNotBlank() && !isReservedIdentifier(identifier)) {
                        val murmurPeer = WifiDirectPeer(
                            deviceId = identifier,
                            wifiDirectAddress = device.deviceAddress,
                            deviceName = deviceName
                        )
                        foundMurmurPeers.add(murmurPeer)
                        
                        Timber.i("Discovered Murmur peer via WiFi Direct: $deviceName -> $identifier")
                        trackTelemetry(
                            "wifi_direct_peer_discovered",
                            "device_id_hash" to identifier.hashCode().toString(),
                            "wifi_address" to device.deviceAddress
                        )
                        
                        // Notify listener of new peer
                        onMurmurPeerDiscovered?.invoke(murmurPeer)
                    } else {
                        Timber.w("Ignoring Murmur peer with invalid/reserved identifier: $identifier")
                    }
                }
            }
            
            _murmurPeers.value = foundMurmurPeers
            
            if (foundMurmurPeers.isNotEmpty()) {
                Timber.i("Found ${foundMurmurPeers.size} Murmur peers via WiFi Direct RSVP")
            }
        }
    }
    
    /**
     * Handle WiFi P2P connection state changes.
     */
    private fun onWifiP2pConnectionChanged(intent: Intent) {
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
            wifiP2pManager?.requestConnectionInfo(channel) { info ->
                _connectionInfo.value = info
                _connectionState.value = ConnectionState.CONNECTED
                Timber.i("WiFi Direct connected: ${info.groupOwnerAddress}, isGroupOwner=${info.isGroupOwner}")
                trackTelemetry("wifi_direct_connected", "is_group_owner" to info.isGroupOwner.toString())
                onConnectionEstablished?.invoke(info)
                
                // Notify transport layer about connection state
                val goAddress = info.groupOwnerAddress
                if (goAddress != null) {
                    if (info.isGroupOwner) {
                        // We're the group owner - start server socket
                        onBecameGroupOwner?.invoke(goAddress)
                    } else {
                        // We're a client - connect to group owner
                        onBecameClient?.invoke(goAddress)
                    }
                }
            }
        } else {
            _connectionInfo.value = null
            _connectionState.value = ConnectionState.DISCONNECTED
            onConnectionLost?.invoke()
            Timber.i("WiFi Direct disconnected")
            trackTelemetry("wifi_direct_disconnected")
        }
    }
    
    /**
     * Handle WiFi P2P this device changed event.
     */
    private fun onWifiP2pThisDeviceChanged(intent: Intent) {
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(
                WifiP2pManager.EXTRA_WIFI_P2P_DEVICE,
                WifiP2pDevice::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
        }
        Timber.d("This device WiFi Direct status: ${device?.status}, name: ${device?.deviceName}")
    }
    
    /**
     * Handle WiFi P2P discovery state changes.
     */
    private fun onWifiP2pDiscoveryChanged(intent: Intent) {
        val discoveryState = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1)
        if (discoveryState == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED) {
            Timber.d("WiFi Direct discovery started")
            isDiscovering = true
            _connectionState.value = ConnectionState.DISCOVERING
            trackTelemetry("wifi_direct_discovery_started")
        } else if (discoveryState == WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED) {
            Timber.d("WiFi Direct discovery stopped")
            isDiscovering = false
            if (_connectionState.value == ConnectionState.DISCOVERING) {
                _connectionState.value = ConnectionState.DISCONNECTED
            }
            trackTelemetry("wifi_direct_discovery_stopped")
        }
    }
    
    /**
     * Check if an identifier is reserved and should be ignored.
     */
    private fun isReservedIdentifier(identifier: String): Boolean {
        // Check for reserved MAC address prefixes
        for (prefix in RESERVED_MAC_PREFIXES) {
            if (identifier.uppercase().startsWith(prefix)) {
                return true
            }
        }
        // Check for dummy Bluetooth address
        if (identifier == DUMMY_BT_ADDRESS) {
            return true
        }
        return false
    }
    
    /**
     * Track a telemetry event for WiFi Direct operations.
     */
    private fun trackTelemetry(eventType: String, vararg params: Pair<String, String>) {
        try {
            val payload = mutableMapOf<String, Any>("event" to eventType)
            for ((key, value) in params) {
                payload[key] = value
            }
            TelemetryClient.getInstance()?.track(
                eventType = "wifi_direct_$eventType",
                transport = "wifi_direct",
                payload = payload
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to track WiFi Direct telemetry")
        }
    }

    /**
     * Start discovering WiFi Direct peers.
     * 
     * Note: On Android 10+, Location Services must be ENABLED (not just permission granted)
     * for WiFi P2P discovery to work.
     */
    @SuppressLint("MissingPermission")
    fun startDiscovery(): Boolean {
        if (!hasPermissions()) {
            Timber.e("Missing WiFi Direct permissions")
            trackTelemetry("discovery_failed", "reason" to "missing_permissions")
            return false
        }

        if (wifiP2pManager == null || channel == null) {
            Timber.e("WiFi Direct not initialized")
            trackTelemetry("discovery_failed", "reason" to "not_initialized")
            return false
        }
        
        // Don't start if already discovering
        if (isDiscovering) {
            Timber.v("WiFi Direct discovery already in progress")
            return true
        }

        wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                // Note: isDiscovering will be set to true in onWifiP2pDiscoveryChanged
                Timber.i("WiFi Direct discovery initiated")
                trackTelemetry("discovery_initiated")
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
                isDiscovering = false
                trackTelemetry("discovery_failed", "reason" to reasonStr)
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
        if (!isDiscovering) return

        Timber.i("Stopping WiFi Direct discovery...")
        wifiP2pManager?.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Timber.d("WiFi Direct discovery stopped successfully")
                isDiscovering = false
            }

            override fun onFailure(reason: Int) {
                Timber.w("Failed to stop WiFi Direct discovery: $reason")
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
        // Stop discovery first
        if (isDiscovering) {
            stopDiscovery()
        }
        
        // Unregister broadcast receiver
        broadcastReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                Timber.w("Error unregistering WiFi Direct receiver: ${e.message}")
            }
        }
        broadcastReceiver = null
        
        // Reset state
        isInitialized = false
        isDiscovering = false
        seekingDesired = false
        _peers.value = emptyList()
        _murmurPeers.value = emptyList()
        _connectionState.value = ConnectionState.DISCONNECTED
        
        Timber.d("WiFi Direct Manager cleaned up")
        trackTelemetry("cleanup")
    }
    
    /**
     * Check if WiFi Direct is currently discovering.
     */
    fun isCurrentlyDiscovering(): Boolean = isDiscovering
    
    /**
     * Check if WiFi Direct manager is initialized.
     */
    fun isReady(): Boolean = isInitialized && wifiP2pManager != null && channel != null
}
