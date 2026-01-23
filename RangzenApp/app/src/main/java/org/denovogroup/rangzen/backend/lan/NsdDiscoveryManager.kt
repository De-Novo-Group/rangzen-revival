/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * NSD (Network Service Discovery) Manager for mDNS-based peer discovery.
 * 
 * Uses Android's NsdManager to advertise and discover Murmur services on the local network.
 * This is more reliable than UDP broadcast on many networks, and works alongside
 * the UDP broadcast discovery for redundancy.
 * 
 * Service type: _murmur._tcp
 * Service name: murmur-<device-id>
 */
package org.denovogroup.rangzen.backend.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.denovogroup.rangzen.backend.telemetry.TelemetryClient
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * NSD Discovery Manager for mDNS-based peer discovery.
 * 
 * Advantages over UDP broadcast:
 * - Standard protocol, better router compatibility
 * - Automatic conflict resolution for service names
 * - Cross-platform compatible (iOS, macOS, Linux)
 * 
 * Limitations (same as UDP broadcast):
 * - Still blocked by client isolation on public WiFi
 * - Requires devices to be on same network segment
 */
class NsdDiscoveryManager(private val context: Context) {

    companion object {
        // Service type for Murmur discovery
        // Format: _<service>._<protocol>
        const val SERVICE_TYPE = "_murmur._tcp"
        
        // Service name prefix
        const val SERVICE_NAME_PREFIX = "murmur-"
        
        // TCP port for message exchange (same as LanTransport)
        const val EXCHANGE_PORT = 41235
    }
    
    /** Our device identifier */
    private var localDeviceId: String = ""
    
    /** NsdManager instance */
    private var nsdManager: NsdManager? = null
    
    /** Whether registration is active */
    private var isRegistered = false
    
    /** Whether discovery is active */
    private var isDiscovering = false
    
    /** Our registered service name (may be modified by NSD for uniqueness) */
    private var registeredServiceName: String? = null
    
    /** Discovered NSD peers */
    private val _discoveredPeers = MutableStateFlow<Map<String, NsdPeer>>(emptyMap())
    val discoveredPeers: StateFlow<Map<String, NsdPeer>> = _discoveredPeers.asStateFlow()
    
    /** Internal peer storage */
    private val peerMap = ConcurrentHashMap<String, NsdPeer>()
    
    /** Callback when a new peer is discovered */
    var onPeerDiscovered: ((NsdPeer) -> Unit)? = null
    
    /** Callback when a peer is lost */
    var onPeerLost: ((NsdPeer) -> Unit)? = null
    
    /**
     * Data class representing a discovered NSD peer.
     */
    data class NsdPeer(
        val deviceId: String,       // Extracted from service name (murmur-<id>)
        val serviceName: String,    // Full NSD service name
        val host: String,           // Resolved IP address
        val port: Int,              // TCP port for connection
        val discoveredAt: Long = System.currentTimeMillis()
    )
    
    /**
     * Registration listener for our service.
     */
    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            // Service name may have been changed due to conflict
            registeredServiceName = serviceInfo.serviceName
            isRegistered = true
            Timber.i("NSD service registered: ${serviceInfo.serviceName}")
            trackTelemetry("service_registered", "name" to serviceInfo.serviceName)
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            isRegistered = false
            Timber.e("NSD registration failed: error $errorCode")
            trackTelemetry("registration_failed", "error" to errorCode.toString())
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            isRegistered = false
            registeredServiceName = null
            Timber.i("NSD service unregistered: ${serviceInfo.serviceName}")
            trackTelemetry("service_unregistered")
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Timber.e("NSD unregistration failed: error $errorCode")
            trackTelemetry("unregistration_failed", "error" to errorCode.toString())
        }
    }
    
    /**
     * Discovery listener for finding other Murmur services.
     */
    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            isDiscovering = true
            Timber.i("NSD discovery started for $serviceType")
            trackTelemetry("discovery_started")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            isDiscovering = false
            Timber.i("NSD discovery stopped for $serviceType")
            trackTelemetry("discovery_stopped")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Timber.d("NSD service found: ${serviceInfo.serviceName}")
            
            // Don't resolve our own service
            if (serviceInfo.serviceName == registeredServiceName) {
                Timber.v("Ignoring our own NSD service")
                return
            }
            
            // Only process services with our prefix
            if (!serviceInfo.serviceName.startsWith(SERVICE_NAME_PREFIX)) {
                Timber.v("Ignoring non-Murmur service: ${serviceInfo.serviceName}")
                return
            }
            
            // Resolve to get IP and port
            resolveService(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Timber.d("NSD service lost: ${serviceInfo.serviceName}")
            
            // Extract device ID from service name
            val deviceId = extractDeviceId(serviceInfo.serviceName)
            if (deviceId != null) {
                val peer = peerMap.remove(deviceId)
                if (peer != null) {
                    _discoveredPeers.value = peerMap.toMap()
                    Timber.i("NSD peer lost: $deviceId")
                    trackTelemetry("peer_lost", "device_id_hash" to deviceId.hashCode().toString())
                    onPeerLost?.invoke(peer)
                }
            }
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            isDiscovering = false
            Timber.e("NSD discovery start failed: error $errorCode")
            trackTelemetry("discovery_start_failed", "error" to errorCode.toString())
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Timber.e("NSD discovery stop failed: error $errorCode")
            trackTelemetry("discovery_stop_failed", "error" to errorCode.toString())
        }
    }
    
    /**
     * Initialize the NSD manager with our device identifier.
     */
    fun initialize(deviceId: String) {
        this.localDeviceId = deviceId
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        
        if (nsdManager == null) {
            Timber.e("NSD service not available on this device")
            return
        }
        
        Timber.i("NSD Discovery Manager initialized with device ID: ${deviceId.take(8)}...")
    }
    
    /**
     * Start advertising our service and discovering others.
     */
    fun start() {
        if (nsdManager == null) {
            Timber.w("Cannot start NSD: service not available")
            return
        }
        
        registerService()
        startDiscovery()
    }
    
    /**
     * Stop advertising and discovery.
     */
    fun stop() {
        unregisterService()
        stopDiscovery()
    }
    
    /**
     * Register our service for other devices to discover.
     */
    private fun registerService() {
        if (isRegistered) {
            Timber.d("NSD service already registered")
            return
        }
        
        // FIX #13: Sanitize device ID for mDNS compatibility
        // mDNS names: alphanumeric + hyphens only, max 63 chars
        val sanitizedId = sanitizeForMdns(localDeviceId)
        
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "$SERVICE_NAME_PREFIX$sanitizedId"
            serviceType = SERVICE_TYPE
            port = EXCHANGE_PORT
        }
        
        try {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
            Timber.i("Registering NSD service: ${serviceInfo.serviceName}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to register NSD service")
            trackTelemetry("register_exception", "error" to e.message.toString())
        }
    }
    
    /**
     * Unregister our service.
     */
    private fun unregisterService() {
        if (!isRegistered) return
        
        try {
            nsdManager?.unregisterService(registrationListener)
        } catch (e: Exception) {
            Timber.w(e, "Error unregistering NSD service")
        }
    }
    
    /**
     * Start discovering other Murmur services.
     */
    private fun startDiscovery() {
        if (isDiscovering) {
            Timber.d("NSD discovery already running")
            return
        }
        
        try {
            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            Timber.i("Starting NSD discovery for $SERVICE_TYPE")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start NSD discovery")
            trackTelemetry("discover_exception", "error" to e.message.toString())
        }
    }
    
    /**
     * Stop discovery.
     */
    private fun stopDiscovery() {
        if (!isDiscovering) return
        
        try {
            nsdManager?.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {
            Timber.w(e, "Error stopping NSD discovery")
        }
    }
    
    /**
     * Resolve a discovered service to get its IP and port.
     */
    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                Timber.w("NSD resolve failed for ${si.serviceName}: error $errorCode")
                trackTelemetry("resolve_failed", "error" to errorCode.toString())
            }

            override fun onServiceResolved(si: NsdServiceInfo) {
                val host = si.host?.hostAddress
                val port = si.port
                
                if (host == null || port <= 0) {
                    Timber.w("NSD resolved service has invalid host/port: $host:$port")
                    return
                }
                
                val deviceId = extractDeviceId(si.serviceName) ?: return
                
                // Don't add ourselves
                if (deviceId == localDeviceId) {
                    return
                }
                
                val peer = NsdPeer(
                    deviceId = deviceId,
                    serviceName = si.serviceName,
                    host = host,
                    port = port
                )
                
                val isNew = !peerMap.containsKey(deviceId)
                peerMap[deviceId] = peer
                _discoveredPeers.value = peerMap.toMap()
                
                if (isNew) {
                    Timber.i("NSD peer discovered: ${deviceId.take(8)}... at $host:$port")
                    trackTelemetry(
                        "peer_discovered",
                        "device_id_hash" to deviceId.hashCode().toString(),
                        "host" to host
                    )
                    onPeerDiscovered?.invoke(peer)
                } else {
                    Timber.v("NSD peer refreshed: ${deviceId.take(8)}...")
                }
            }
        }
        
        try {
            nsdManager?.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Timber.w(e, "Exception resolving NSD service")
        }
    }
    
    /**
     * Sanitize a device ID for use in mDNS service names.
     * 
     * mDNS service names must:
     * - Contain only alphanumeric characters and hyphens
     * - Not start or end with hyphen
     * - Be max 63 characters (we leave room for prefix)
     * 
     * Device IDs may contain colons (Bluetooth MAC) or other chars.
     */
    private fun sanitizeForMdns(deviceId: String): String {
        // Replace colons and other invalid chars with empty string
        // Keep only alphanumeric and convert to lowercase
        val sanitized = deviceId
            .replace(Regex("[^a-zA-Z0-9]"), "")
            .lowercase()
            .take(50) // Leave room for "murmur-" prefix
        
        return if (sanitized.isEmpty()) {
            "unknown"
        } else {
            sanitized
        }
    }
    
    /**
     * Extract device ID from service name.
     * Service name format: murmur-<device-id>
     */
    private fun extractDeviceId(serviceName: String): String? {
        if (!serviceName.startsWith(SERVICE_NAME_PREFIX)) {
            return null
        }
        
        // Handle potential NSD conflict resolution suffix like "murmur-abc123 (2)"
        val withoutPrefix = serviceName.removePrefix(SERVICE_NAME_PREFIX)
        
        // Remove any conflict suffix
        val spaceIndex = withoutPrefix.indexOf(' ')
        return if (spaceIndex > 0) {
            withoutPrefix.substring(0, spaceIndex)
        } else {
            withoutPrefix
        }
    }
    
    /**
     * Get list of currently discovered peers.
     */
    fun getDiscoveredPeers(): List<NsdPeer> = peerMap.values.toList()
    
    /**
     * Clear all discovered peers.
     */
    fun clearPeers() {
        peerMap.clear()
        _discoveredPeers.value = emptyMap()
    }
    
    /**
     * Cleanup resources.
     */
    fun cleanup() {
        stop()
        clearPeers()
        nsdManager = null
        Timber.d("NSD Discovery Manager cleaned up")
    }
    
    /**
     * Track telemetry event.
     */
    private fun trackTelemetry(eventType: String, vararg params: Pair<String, String>) {
        try {
            val payload = mutableMapOf<String, Any>("event" to eventType)
            for ((key, value) in params) {
                payload[key] = value
            }
            TelemetryClient.getInstance()?.track(
                eventType = "nsd_discovery_$eventType",
                transport = "nsd",
                payload = payload
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to track NSD telemetry")
        }
    }
}
