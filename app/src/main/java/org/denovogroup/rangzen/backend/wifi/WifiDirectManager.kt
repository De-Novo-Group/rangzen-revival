/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * WiFi Direct Manager for Android 14+
 * 
 * Updated Design (v2):
 * - Uses DNS-SD (Service Discovery) to broadcast device identity.
 * - Replaces the broken "RSVP-by-name" mechanism (setDeviceName).
 * - Allows "blind" discovery of Murmur peers over Wi-Fi Direct without connecting first.
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
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.denovogroup.rangzen.backend.AppConfig
import org.denovogroup.rangzen.backend.DeviceIdentity
import org.denovogroup.rangzen.backend.ble.DiscoveredPeer
import org.denovogroup.rangzen.backend.telemetry.TelemetryClient
import org.denovogroup.rangzen.backend.telemetry.TelemetryEvent
import timber.log.Timber
import java.net.InetAddress
import kotlin.coroutines.resume

/**
 * WiFi Direct Manager for peer discovery via DNS-SD.
 */
class WifiDirectManager(private val context: Context) {

    companion object {
        /** Dummy Bluetooth MAC that Android returns when address is unavailable */
        private const val DUMMY_BT_ADDRESS = "02:00:00:00:00:00"
        
        /** Reserved MAC addresses we should ignore */
        private val RESERVED_MAC_PREFIXES = listOf("00:00:00", "FF:FF:FF")

        // TXT Record Keys
        private const val TXT_KEY_ID = "id"
        private const val TXT_KEY_PORT = "port"
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

    /** Discovered Murmur peers (extracted from DNS-SD) */
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
    
    /** Callback when a Murmur peer is discovered via DNS-SD */
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
     * Represents a Murmur peer discovered via DNS-SD.
     */
    data class WifiDirectPeer(
        val deviceId: String,           // Extracted identifier from TXT record
        val wifiDirectAddress: String,  // WiFi P2P device address
        val deviceName: String,         // Full WiFi Direct device name
        val servicePort: Int,           // Port the peer is listening on
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
     * Register our local service (DNS-SD) to broadcast our identity.
     * 
     * This uses Wi-Fi Direct DNS-SD (WifiP2pManager), NOT regular NSD (NsdManager).
     * Wi-Fi Direct DNS-SD works without being connected to any network.
     * 
     * Other Murmur devices can discover us and see our ID without connecting.
     */
    @SuppressLint("MissingPermission")
    private fun registerLocalService() {
        if (!hasPermissions() || wifiP2pManager == null || channel == null) return

        val serviceType = AppConfig.wifiDirectServiceType(context)  // "_murmur._tcp"
        val servicePort = AppConfig.wifiDirectServicePort(context)
        val deviceId = getDeviceIdentifier()

        // Create the TXT record with our identity
        // This is visible to other devices during service discovery (before connection)
        val record = mapOf(
            TXT_KEY_ID to deviceId,
            TXT_KEY_PORT to servicePort.toString()
        )

        // Parameters:
        // 1. Instance name: unique identifier for this service instance (use device ID prefix)
        // 2. Service type: the protocol being advertised (e.g., "_murmur._tcp")
        // 3. TXT record: key-value metadata visible during discovery
        val instanceName = "murmur_${deviceId.take(8)}"
        
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
            instanceName,
            serviceType,  // "_murmur._tcp" from config
            record
        )

        wifiP2pManager?.addLocalService(channel, serviceInfo, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Timber.i("Wi-Fi Direct DNS-SD registered: $instanceName ($serviceType)")
                Timber.i("  -> ID: ${deviceId.take(8)}..., Port: $servicePort")
                trackTelemetry("dns_sd_registered", 
                    "id" to deviceId.take(8),
                    "service_type" to serviceType)
            }

            override fun onFailure(reason: Int) {
                val reasonStr = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "P2P unsupported"
                    WifiP2pManager.BUSY -> "System busy"
                    WifiP2pManager.ERROR -> "Internal error"
                    else -> "Unknown ($reason)"
                }
                Timber.e("Failed to register DNS-SD service: $reasonStr")
                trackTelemetry("dns_sd_registration_failed", "reason" to reasonStr)
            }
        })
    }
    
    /**
     * Get the device identifier to use for broadcasting.
     * 
     * IMPORTANT: Uses privacy-preserving ID derived from crypto keypair.
     * Do NOT use Bluetooth MAC or Android ID - they can be used to track users.
     */
    fun getDeviceIdentifier(): String {
        // Use privacy-preserving device ID (SHA256 of public key)
        return DeviceIdentity.getDeviceId(context)
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
            // Re-register local service when P2P is enabled
            registerLocalService()
        } else {
            Timber.w("WiFi P2P is disabled")
            _connectionState.value = ConnectionState.ERROR
            trackTelemetry("wifi_direct_state_disabled")
        }
    }
    
    /**
     * Handle WiFi P2P peers changed event.
     * We still request peers for standard discovery, but ID extraction happens via DNS-SD.
     */
    @SuppressLint("MissingPermission")
    private fun onWifiP2pPeersChanged(intent: Intent) {
        if (!hasPermissions()) return
        
        wifiP2pManager?.requestPeers(channel) { peerList ->
            val allPeers = peerList.deviceList.toList()
            _peers.value = allPeers
            onPeersChanged?.invoke(allPeers)
            
            Timber.v("WiFi Direct peers changed: ${allPeers.size} total")
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
                transport = TelemetryEvent.TRANSPORT_WIFI_DIRECT,
                payload = payload
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to track WiFi Direct telemetry")
        }
    }

    /**
     * Start discovering WiFi Direct peers via DNS-SD.
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

        // 1. Set up listeners for DNS-SD responses
        wifiP2pManager?.setDnsSdResponseListeners(channel,
            { instanceName, registrationType, srcDevice ->
                // Service Response Listener (not strictly needed if we use TXT record, but good for debugging)
                Timber.v("DNS-SD Service Found: $instanceName ($registrationType) from ${srcDevice.deviceName}")
            },
            { fullDomainName, txtRecordMap, srcDevice ->
                // TXT Record Listener - This is where we get the metadata!
                Timber.d("DNS-SD TXT Record: $fullDomainName from ${srcDevice.deviceName}")
                
                val deviceId = txtRecordMap[TXT_KEY_ID]
                val portStr = txtRecordMap[TXT_KEY_PORT]
                
                if (deviceId != null && !isReservedIdentifier(deviceId)) {
                    val port = portStr?.toIntOrNull() ?: 0
                    
                    val murmurPeer = WifiDirectPeer(
                        deviceId = deviceId,
                        wifiDirectAddress = srcDevice.deviceAddress,
                        deviceName = srcDevice.deviceName ?: "Unknown",
                        servicePort = port
                    )
                    
                    // Add to our list if new
                    val currentPeers = _murmurPeers.value.toMutableList()
                    val existingIndex = currentPeers.indexOfFirst { it.deviceId == deviceId }
                    
                    if (existingIndex >= 0) {
                        currentPeers[existingIndex] = murmurPeer // Update
                    } else {
                        currentPeers.add(murmurPeer) // Add
                        Timber.i("Discovered Murmur peer via DNS-SD: ${srcDevice.deviceName} -> $deviceId")
                        trackTelemetry("dns_sd_peer_found", "id" to deviceId)
                        onMurmurPeerDiscovered?.invoke(murmurPeer)
                    }
                    _murmurPeers.value = currentPeers
                }
            }
        )

        // 2. Add a service discovery request
        val serviceRequest = WifiP2pDnsSdServiceRequest.newInstance()
        wifiP2pManager?.addServiceRequest(channel, serviceRequest, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Timber.v("DNS-SD Service Request added")
            }
            override fun onFailure(reason: Int) {
                Timber.e("Failed to add DNS-SD request: $reason")
            }
        })

        // 3. Start discovery
        wifiP2pManager?.discoverServices(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Timber.i("WiFi Direct Service Discovery initiated")
                trackTelemetry("discovery_initiated")
                // Note: isDiscovering will be set to true in onWifiP2pDiscoveryChanged
            }

            override fun onFailure(reason: Int) {
                Timber.e("WiFi Direct discovery failed: $reason")
                _connectionState.value = ConnectionState.ERROR
                isDiscovering = false
                trackTelemetry("discovery_failed", "reason" to reason.toString())
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
        
        // Clear service requests
        wifiP2pManager?.clearServiceRequests(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Timber.v("Cleared service requests") }
            override fun onFailure(reason: Int) { Timber.w("Failed to clear service requests: $reason") }
        })

        // Stop peer discovery (covers both standard and service discovery)
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
        
        // Remove local service
        if (wifiP2pManager != null && channel != null) {
            wifiP2pManager?.clearLocalServices(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { Timber.v("Cleared local services") }
                override fun onFailure(reason: Int) { Timber.w("Failed to clear local services: $reason") }
            })
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
}
