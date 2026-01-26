/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * LAN Discovery Manager for peer discovery on shared WiFi networks.
 * 
 * Uses UDP broadcast to find other Murmur devices on the same subnet.
 * Works in coffee shops, home networks, phone hotspots - anywhere devices
 * share a local network, even without Internet access.
 * 
 * Discovery protocol:
 * 1. Periodically broadcast a "MURMUR_HELLO" packet to the subnet broadcast address
 * 2. Listen for broadcasts from other Murmur devices
 * 3. When we receive a hello, respond with our device info
 * 4. Register discovered peers in the unified peer registry
 */
package org.denovogroup.rangzen.backend.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.denovogroup.rangzen.backend.telemetry.TelemetryClient
import org.json.JSONObject
import timber.log.Timber
import java.net.*
import java.util.concurrent.ConcurrentHashMap

/**
 * LAN Discovery Manager for finding Murmur peers on local networks.
 * 
 * Supports:
 * - Home WiFi networks
 * - Coffee shop / public WiFi (if client isolation is disabled)
 * - Phone hotspot networks
 * - Any network where devices can reach each other via broadcast
 */
class LanDiscoveryManager(private val context: Context) {

    companion object {
        // Discovery port - chosen to avoid common ports
        // Using a high port that's unlikely to be filtered
        const val DISCOVERY_PORT = 41234
        
        // Protocol identifiers
        const val PROTOCOL_MAGIC = "MURMUR_LAN"
        const val MSG_HELLO = "HELLO"
        const val MSG_HELLO_RESPONSE = "HELLO_RESP"
        
        // Discovery intervals
        const val BROADCAST_INTERVAL_MS = 10_000L  // Broadcast hello every 10 seconds
        const val PEER_TIMEOUT_MS = 60_000L        // Remove peers not seen for 60 seconds
        const val RECEIVE_BUFFER_SIZE = 1024       // Max packet size
        
        // Packet format version for future compatibility
        const val PROTOCOL_VERSION = 1
    }
    
    /** Our device identifier for LAN discovery */
    private var localDeviceId: String = ""
    
    /** Discovered LAN peers */
    private val _discoveredPeers = MutableStateFlow<Map<String, LanPeer>>(emptyMap())
    val discoveredPeers: StateFlow<Map<String, LanPeer>> = _discoveredPeers.asStateFlow()
    
    /** Whether discovery is running */
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    
    /** Internal peer storage with timestamps */
    private val peerMap = ConcurrentHashMap<String, LanPeer>()
    
    /** UDP socket for discovery */
    private var socket: DatagramSocket? = null
    
    /** Discovery coroutine scope */
    private var discoveryScope: CoroutineScope? = null
    
    /** Callback when a new LAN peer is discovered */
    var onPeerDiscovered: ((LanPeer) -> Unit)? = null
    
    /** Callback when a peer is lost (timed out) */
    var onPeerLost: ((LanPeer) -> Unit)? = null
    
    /**
     * Data class representing a discovered LAN peer.
     */
    data class LanPeer(
        val deviceId: String,        // Unique device identifier
        val ipAddress: InetAddress,  // IP address on the LAN
        val port: Int,               // Port for TCP connections
        val lastSeen: Long = System.currentTimeMillis(),
        val protocolVersion: Int = PROTOCOL_VERSION
    ) {
        fun isExpired(): Boolean {
            return System.currentTimeMillis() - lastSeen > PEER_TIMEOUT_MS
        }
    }
    
    /**
     * Initialize the LAN discovery manager with our device identifier.
     */
    fun initialize(deviceId: String) {
        this.localDeviceId = deviceId
        Timber.i("LAN Discovery Manager initialized with device ID: ${deviceId.take(8)}...")
    }
    
    /**
     * Start LAN discovery.
     * This will broadcast our presence and listen for other peers.
     */
    fun startDiscovery() {
        if (_isRunning.value) {
            Timber.d("LAN discovery already running")
            return
        }
        
        // Check if we're on a WiFi network
        if (!isOnWifiNetwork()) {
            Timber.w("Not on WiFi network, LAN discovery not started")
            trackTelemetry("discovery_skipped", "reason" to "not_on_wifi")
            return
        }
        
        discoveryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        _isRunning.value = true
        
        // Start the listener
        discoveryScope?.launch {
            startListener()
        }
        
        // Start the broadcaster
        discoveryScope?.launch {
            startBroadcaster()
        }
        
        // Start cleanup task
        discoveryScope?.launch {
            startCleanupTask()
        }
        
        Timber.i("LAN discovery started on port $DISCOVERY_PORT")
        trackTelemetry("discovery_started")
    }
    
    /**
     * Stop LAN discovery.
     */
    fun stopDiscovery() {
        if (!_isRunning.value) return
        
        _isRunning.value = false
        discoveryScope?.cancel()
        discoveryScope = null
        
        socket?.close()
        socket = null
        
        Timber.i("LAN discovery stopped")
        trackTelemetry("discovery_stopped")
    }
    
    /**
     * Check if device is connected to a WiFi network.
     */
    private fun isOnWifiNetwork(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
            
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
    
    /**
     * Get the broadcast address for the current WiFi network.
     * 
     * Uses the directed broadcast address (e.g., 192.168.1.255 for /24)
     * rather than the limited broadcast (255.255.255.255) for better
     * reliability across different network configurations.
     */
    private fun getBroadcastAddress(): InetAddress? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
            
            @Suppress("DEPRECATION")
            val dhcp = wifiManager.dhcpInfo ?: return null
            
            // DhcpInfo stores IP addresses as little-endian integers
            val ip = dhcp.ipAddress
            val netmask = dhcp.netmask
            
            // If netmask is 0 or invalid, assume /24 (255.255.255.0)
            // This is the most common subnet mask for home/small networks
            val effectiveNetmask = if (netmask == 0) {
                Timber.w("Netmask is 0, assuming /24 subnet")
                0x00FFFFFF  // 255.255.255.0 in little-endian
            } else {
                netmask
            }
            
            // Calculate broadcast: (IP & netmask) | (~netmask)
            // This gives us the directed broadcast for our subnet
            val broadcast = (ip and effectiveNetmask) or effectiveNetmask.inv()
            
            // Convert little-endian int to byte array
            val bytes = ByteArray(4)
            for (i in 0..3) {
                bytes[i] = (broadcast shr (i * 8) and 0xFF).toByte()
            }
            
            val address = InetAddress.getByAddress(bytes)
            Timber.d("Calculated broadcast address: ${address.hostAddress} (IP: ${ipToString(ip)}, netmask: ${ipToString(effectiveNetmask)})")
            return address
        } catch (e: Exception) {
            Timber.e(e, "Failed to get broadcast address")
            return null
        }
    }
    
    /**
     * Convert little-endian int to IP string for logging.
     */
    private fun ipToString(ip: Int): String {
        return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }
    
    /**
     * Get our local IP address on the WiFi network.
     */
    private fun getLocalIpAddress(): InetAddress? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
            
            @Suppress("DEPRECATION")
            val ip = wifiManager.connectionInfo?.ipAddress ?: return null
            
            if (ip == 0) return null
            
            val bytes = ByteArray(4)
            for (i in 0..3) {
                bytes[i] = (ip shr (i * 8) and 0xFF).toByte()
            }
            return InetAddress.getByAddress(bytes)
        } catch (e: Exception) {
            Timber.e(e, "Failed to get local IP address")
            return null
        }
    }
    
    /**
     * Start the UDP listener for incoming discovery packets.
     */
    private suspend fun startListener() {
        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(DISCOVERY_PORT))
            }
            
            val buffer = ByteArray(RECEIVE_BUFFER_SIZE)
            val packet = DatagramPacket(buffer, buffer.size)
            
            Timber.d("LAN discovery listener started on port $DISCOVERY_PORT")
            
            while (_isRunning.value) {
                try {
                    socket?.receive(packet)
                    
                    val data = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val senderAddress = packet.address
                    val senderPort = packet.port
                    
                    // Don't process our own packets
                    val localIp = getLocalIpAddress()
                    if (senderAddress == localIp) {
                        continue
                    }
                    
                    handleIncomingPacket(data, senderAddress, senderPort)
                    
                } catch (e: SocketTimeoutException) {
                    // Normal timeout, continue
                } catch (e: SocketException) {
                    if (_isRunning.value) {
                        Timber.e(e, "Socket exception in LAN listener")
                    }
                    break
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to start LAN listener")
            trackTelemetry("listener_failed", "error" to e.message.toString())
        }
    }
    
    /**
     * Start the UDP broadcaster for discovery hello packets.
     */
    private suspend fun startBroadcaster() {
        delay(1000) // Brief delay to let listener start first
        
        while (_isRunning.value) {
            try {
                sendHelloPacket()
            } catch (e: Exception) {
                Timber.w(e, "Failed to send LAN discovery broadcast")
            }
            delay(BROADCAST_INTERVAL_MS)
        }
    }
    
    /**
     * Send a hello broadcast packet to announce our presence.
     */
    private fun sendHelloPacket() {
        val broadcastAddress = getBroadcastAddress() ?: run {
            Timber.w("Cannot get broadcast address for LAN discovery")
            return
        }
        
        val localIp = getLocalIpAddress()?.hostAddress ?: "unknown"
        
        val json = JSONObject().apply {
            put("magic", PROTOCOL_MAGIC)
            put("type", MSG_HELLO)
            put("version", PROTOCOL_VERSION)
            put("device_id", localDeviceId)
            put("ip", localIp)
            // FIX #6: Advertise TCP EXCHANGE_PORT (41235), not UDP DISCOVERY_PORT (41234)
            // This is the port where we're listening for TCP message exchange connections
            put("port", LanTransport.EXCHANGE_PORT)
            put("timestamp", System.currentTimeMillis())
        }
        
        val data = json.toString().toByteArray(Charsets.UTF_8)
        val packet = DatagramPacket(data, data.size, broadcastAddress, DISCOVERY_PORT)
        
        try {
            socket?.send(packet)
            Timber.v("Sent LAN discovery hello to $broadcastAddress")
        } catch (e: Exception) {
            Timber.w(e, "Failed to send LAN hello packet")
        }
    }
    
    /**
     * Handle an incoming discovery packet.
     */
    private fun handleIncomingPacket(data: String, senderAddress: InetAddress, senderPort: Int) {
        try {
            val json = JSONObject(data)
            
            // Verify this is a Murmur packet
            val magic = json.optString("magic")
            if (magic != PROTOCOL_MAGIC) {
                return // Not our packet
            }
            
            val type = json.optString("type")
            val deviceId = json.optString("device_id")
            val version = json.optInt("version", 1)
            
            // Ignore our own packets
            if (deviceId == localDeviceId) {
                return
            }
            
            // FIX #6: Extract port from packet (should be TCP EXCHANGE_PORT)
            val peerPort = json.optInt("port", LanTransport.EXCHANGE_PORT)
            
            when (type) {
                MSG_HELLO -> {
                    // Another Murmur device is announcing itself
                    handleHelloPacket(deviceId, senderAddress, version, peerPort)
                    
                    // Send a response so they know about us
                    sendHelloResponse(senderAddress)
                }
                MSG_HELLO_RESPONSE -> {
                    // Response to our hello
                    handleHelloPacket(deviceId, senderAddress, version, peerPort)
                }
            }
            
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse LAN discovery packet")
        }
    }
    
    /**
     * Handle a hello packet from another Murmur device.
     * 
     * @param port The TCP port where the peer is listening for exchange connections
     */
    private fun handleHelloPacket(deviceId: String, address: InetAddress, version: Int, port: Int) {
        val peer = LanPeer(
            deviceId = deviceId,
            ipAddress = address,
            port = port,
            lastSeen = System.currentTimeMillis(),
            protocolVersion = version
        )
        
        val isNew = !peerMap.containsKey(deviceId)
        peerMap[deviceId] = peer
        _discoveredPeers.value = peerMap.toMap()
        
        if (isNew) {
            Timber.i("LAN peer discovered: ${deviceId.take(8)}... at ${address.hostAddress}")
            trackTelemetry(
                "peer_discovered",
                "device_id_hash" to deviceId.hashCode().toString(),
                "ip" to address.hostAddress.toString()
            )
        } else {
            Timber.v("LAN peer refreshed: ${deviceId.take(8)}... at ${address.hostAddress}")
        }
        // Always invoke callback to keep peerRegistry timestamps fresh
        onPeerDiscovered?.invoke(peer)
    }
    
    /**
     * Send a hello response to a specific address.
     */
    private fun sendHelloResponse(targetAddress: InetAddress) {
        val localIp = getLocalIpAddress()?.hostAddress ?: "unknown"
        
        val json = JSONObject().apply {
            put("magic", PROTOCOL_MAGIC)
            put("type", MSG_HELLO_RESPONSE)
            put("version", PROTOCOL_VERSION)
            put("device_id", localDeviceId)
            put("ip", localIp)
            // FIX #6: Advertise TCP EXCHANGE_PORT, not UDP DISCOVERY_PORT
            put("port", LanTransport.EXCHANGE_PORT)
            put("timestamp", System.currentTimeMillis())
        }
        
        val data = json.toString().toByteArray(Charsets.UTF_8)
        val packet = DatagramPacket(data, data.size, targetAddress, DISCOVERY_PORT)
        
        try {
            socket?.send(packet)
            Timber.v("Sent LAN hello response to $targetAddress")
        } catch (e: Exception) {
            Timber.w(e, "Failed to send LAN hello response")
        }
    }
    
    /**
     * Start periodic cleanup of stale peers.
     */
    private suspend fun startCleanupTask() {
        while (_isRunning.value) {
            delay(PEER_TIMEOUT_MS / 2)
            
            val now = System.currentTimeMillis()
            val expired = peerMap.filter { it.value.isExpired() }
            
            for ((deviceId, peer) in expired) {
                peerMap.remove(deviceId)
                Timber.i("LAN peer timed out: ${deviceId.take(8)}...")
                trackTelemetry("peer_timeout", "device_id_hash" to deviceId.hashCode().toString())
                onPeerLost?.invoke(peer)
            }
            
            if (expired.isNotEmpty()) {
                _discoveredPeers.value = peerMap.toMap()
            }
        }
    }
    
    /**
     * Get a list of currently discovered peers.
     */
    fun getDiscoveredPeers(): List<LanPeer> {
        return peerMap.values.filter { !it.isExpired() }
    }
    
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
        stopDiscovery()
        clearPeers()
        Timber.d("LAN Discovery Manager cleaned up")
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
                eventType = "lan_discovery_$eventType",
                transport = "lan",
                payload = payload
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to track LAN discovery telemetry")
        }
    }
}
