/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * Centralized registry for discovered peers across all transports
 */
package org.denovogroup.rangzen.backend.discovery

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Central registry for discovered peers across all transport mechanisms.
 * 
 * This registry aggregates peer discoveries from:
 * - BLE scanning
 * - WiFi Direct discovery
 * - LAN/mDNS discovery (future)
 * 
 * Key design principles:
 * 1. Peers are initially identified by transport-specific address (BLE MAC, WiFi Direct address)
 * 2. App-layer handshake establishes a public ID for correlation
 * 3. Once handshake completes, peers with the same public ID are merged
 * 4. Transport selection prefers faster/higher-bandwidth transports
 * 
 * Thread-safe via ConcurrentHashMap.
 */
class DiscoveredPeerRegistry {
    
    companion object {
        private const val TAG = "PeerRegistry"
        
        /** Default threshold for considering a peer stale */
        const val DEFAULT_STALE_MS = 30_000L
        
        /** Prefix for temporary peer IDs before handshake */
        const val TEMP_ID_PREFIX = "temp:"
    }
    
    /**
     * All known peers, keyed by their public ID (or temporary ID before handshake).
     * 
     * Before handshake, temporary IDs are generated from the transport address:
     * - BLE: "temp:ble:AA:BB:CC:DD:EE:FF"
     * - WiFi Direct: "temp:wifi:XX:YY:ZZ..."
     * 
     * After handshake, peers are keyed by their actual public ID.
     */
    private val peers = ConcurrentHashMap<String, UnifiedPeer>()
    
    /**
     * Mapping from transport-specific addresses to peer IDs.
     * Allows quick lookup when a transport reports a peer.
     */
    private val addressToPeerId = ConcurrentHashMap<String, String>()
    
    /** StateFlow exposing the current list of peers */
    private val _peerList = MutableStateFlow<List<UnifiedPeer>>(emptyList())
    val peerList: StateFlow<List<UnifiedPeer>> = _peerList.asStateFlow()
    
    /** StateFlow exposing just the peer count */
    private val _peerCount = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = _peerCount.asStateFlow()
    
    /** Callback when a new peer is discovered */
    var onPeerDiscovered: ((UnifiedPeer) -> Unit)? = null
    
    /** Callback when a peer becomes reachable via a new transport */
    var onPeerTransportAdded: ((UnifiedPeer, TransportType) -> Unit)? = null
    
    /** Callback when peers are updated (for UI refresh) */
    var onPeersUpdated: ((List<UnifiedPeer>) -> Unit)? = null
    
    /**
     * Report a peer discovered via BLE.
     * 
     * @param bleAddress Bluetooth MAC address
     * @param device Bluetooth device object for GATT connection
     * @param rssi Signal strength
     * @param name Optional device name
     */
    fun reportBlePeer(
        bleAddress: String,
        device: BluetoothDevice,
        rssi: Int,
        name: String? = null
    ) {
        val transportKey = "ble:$bleAddress"
        val existingPeerId = addressToPeerId[transportKey]
        
        val transportInfo = PeerTransportInfo(
            transport = TransportType.BLE,
            lastSeen = System.currentTimeMillis(),
            signalStrength = rssi,
            bleDevice = device,
            bleAddress = bleAddress
        )
        
        if (existingPeerId != null) {
            // Update existing peer
            peers[existingPeerId]?.let { peer ->
                val isNewTransport = !peer.hasTransport(TransportType.BLE)
                peer.updateTransport(transportInfo)
                if (isNewTransport) {
                    onPeerTransportAdded?.invoke(peer, TransportType.BLE)
                }
            }
        } else {
            // Create new temporary peer
            val tempId = "${TEMP_ID_PREFIX}$transportKey"
            val peer = UnifiedPeer(
                publicId = tempId,
                transports = mutableMapOf(TransportType.BLE to transportInfo)
            )
            peers[tempId] = peer
            addressToPeerId[transportKey] = tempId
            
            Timber.i("$TAG: New BLE peer discovered: $bleAddress")
            onPeerDiscovered?.invoke(peer)
        }
        
        updatePeerList()
    }
    
    /**
     * Report a peer discovered via WiFi Direct.
     * 
     * @param wifiAddress WiFi P2P device address
     * @param deviceName WiFi Direct device name (may contain RSVP identifier)
     * @param extractedId Optional identifier extracted from device name (RSVP) or DNS-SD
     */
    fun reportWifiDirectPeer(
        wifiAddress: String,
        deviceName: String,
        extractedId: String? = null
    ) {
        val transportKey = "wifi:$wifiAddress"
        var existingPeerId = addressToPeerId[transportKey]
        
        // If we have an ID (from DNS-SD), check if we already know a peer with this ID
        // This handles the case where BLE discovered the peer first, or we discovered it via DNS-SD before
        if (existingPeerId == null && extractedId != null) {
             // Check if we have a mapping for this ID (treated as BLE address)
             val potentialBleKey = "ble:$extractedId"
             existingPeerId = addressToPeerId[potentialBleKey]
             
             // If still null, maybe we have a peer keyed by this ID directly?
             if (existingPeerId == null && peers.containsKey(extractedId)) {
                 existingPeerId = extractedId
             }
        }
        
        val transportInfo = PeerTransportInfo(
            transport = TransportType.WIFI_DIRECT,
            lastSeen = System.currentTimeMillis(),
            wifiDirectAddress = wifiAddress,
            wifiDirectName = deviceName
        )
        
        if (existingPeerId != null) {
            // Update existing peer
            peers[existingPeerId]?.let { peer ->
                val isNewTransport = !peer.hasTransport(TransportType.WIFI_DIRECT)
                peer.updateTransport(transportInfo)
                
                // If we now have an ID but didn't before, update the peer ID
                if (extractedId != null && !peer.handshakeCompleted && peer.publicId.startsWith(TEMP_ID_PREFIX)) {
                    updatePeerIdAfterHandshake(transportKey, extractedId)
                }
                
                if (isNewTransport) {
                    onPeerTransportAdded?.invoke(peer, TransportType.WIFI_DIRECT)
                }
            }
        } else {
            // Create new peer
            // If we have an extracted ID, use it as the public ID (it's the BLE MAC)
            val peerId = extractedId ?: "${TEMP_ID_PREFIX}$transportKey"
            val peer = UnifiedPeer(
                publicId = peerId,
                transports = mutableMapOf(TransportType.WIFI_DIRECT to transportInfo),
                handshakeCompleted = extractedId != null
            )
            peers[peerId] = peer
            addressToPeerId[transportKey] = peerId
            
            // If we have an ID (BLE MAC), also map the BLE address to this peer
            // This ensures that when BLE scanning finds this device later, it maps to this same peer
            if (extractedId != null) {
                val bleKey = "ble:$extractedId"
                if (!addressToPeerId.containsKey(bleKey)) {
                    addressToPeerId[bleKey] = peerId
                    Timber.d("$TAG: Pre-mapped BLE address $bleKey to WiFi peer $peerId")
                }
            }
            
            Timber.i("$TAG: New WiFi Direct peer discovered: $wifiAddress ($deviceName) -> ID: $extractedId")
            onPeerDiscovered?.invoke(peer)
        }
        
        updatePeerList()
    }
    
    /**
     * Report a peer discovered via LAN (mDNS/UDP broadcast).
     * 
     * @param ipAddress IP address
     * @param port Port number for connection
     * @param publicId Public ID advertised via mDNS TXT record or broadcast
     */
    fun reportLanPeer(
        ipAddress: String,
        port: Int,
        publicId: String? = null
    ) {
        val transportKey = "lan:$ipAddress:$port"
        val existingPeerId = addressToPeerId[transportKey]
        
        val transportInfo = PeerTransportInfo(
            transport = TransportType.LAN,
            lastSeen = System.currentTimeMillis(),
            lanAddress = ipAddress,
            lanPort = port
        )
        
        if (existingPeerId != null) {
            // Update existing peer
            peers[existingPeerId]?.let { peer ->
                val isNewTransport = !peer.hasTransport(TransportType.LAN)
                peer.updateTransport(transportInfo)
                if (isNewTransport) {
                    onPeerTransportAdded?.invoke(peer, TransportType.LAN)
                }
            }
        } else {
            // Create peer (with real ID if provided, otherwise temp)
            val peerId = publicId ?: "${TEMP_ID_PREFIX}$transportKey"
            val peer = UnifiedPeer(
                publicId = peerId,
                transports = mutableMapOf(TransportType.LAN to transportInfo),
                handshakeCompleted = publicId != null
            )
            peers[peerId] = peer
            addressToPeerId[transportKey] = peerId
            
            Timber.i("$TAG: New LAN peer discovered: $ipAddress:$port")
            onPeerDiscovered?.invoke(peer)
        }
        
        updatePeerList()
    }
    
    /**
     * Update a peer's public ID after app-layer handshake.
     * This may cause peer merging if another transport already discovered this ID.
     * 
     * @param transportKey The transport-specific key (e.g., "ble:AA:BB:CC:DD:EE:FF")
     * @param newPublicId The peer's actual public ID from handshake
     */
    fun updatePeerIdAfterHandshake(transportKey: String, newPublicId: String) {
        val currentPeerId = addressToPeerId[transportKey] ?: return
        val currentPeer = peers[currentPeerId] ?: return
        
        // Check if a peer with this public ID already exists
        val existingPeer = peers[newPublicId]
        
        if (existingPeer != null && existingPeer != currentPeer) {
            // Merge: move all transports from current peer to existing peer
            currentPeer.transports.forEach { (transport, info) ->
                existingPeer.updateTransport(info)
            }
            
            // Update address mappings to point to merged peer
            currentPeer.transports.forEach { (transport, info) ->
                info.connectionId()?.let { connId ->
                    val key = "${transport.identifier()}:$connId"
                    addressToPeerId[key] = newPublicId
                }
            }
            
            // Remove the old temporary peer
            peers.remove(currentPeerId)
            
            Timber.i("$TAG: Merged peer $currentPeerId into $newPublicId")
        } else {
            // Rename: update the peer's ID
            peers.remove(currentPeerId)
            val updatedPeer = currentPeer.copy(
                publicId = newPublicId,
                handshakeCompleted = true
            )
            peers[newPublicId] = updatedPeer
            
            // Update all address mappings for this peer
            updatedPeer.transports.forEach { (transport, info) ->
                info.connectionId()?.let { connId ->
                    val key = "${transport.identifier()}:$connId"
                    addressToPeerId[key] = newPublicId
                }
            }
            
            // If the new ID looks like a BLE address, ensure it's mapped too
            // This handles the case where we get ID from WiFi/LAN but haven't seen BLE yet
            if (!addressToPeerId.containsKey("ble:$newPublicId")) {
                addressToPeerId["ble:$newPublicId"] = newPublicId
            }
            
            Timber.i("$TAG: Peer $currentPeerId renamed to $newPublicId after handshake")
        }
        
        updatePeerList()
    }
    
    /**
     * Get a peer by its public ID.
     */
    fun getPeer(publicId: String): UnifiedPeer? = peers[publicId]
    
    /**
     * Get a peer by transport-specific address.
     */
    fun getPeerByTransportAddress(transportKey: String): UnifiedPeer? {
        val peerId = addressToPeerId[transportKey] ?: return null
        return peers[peerId]
    }
    
    /**
     * Get all reachable peers (not stale on at least one transport).
     */
    fun getReachablePeers(staleThresholdMs: Long = DEFAULT_STALE_MS): List<UnifiedPeer> {
        return peers.values.filter { it.isReachable(staleThresholdMs) }
    }
    
    /**
     * Get peers reachable via a specific transport.
     */
    fun getPeersWithTransport(
        transport: TransportType,
        staleThresholdMs: Long = DEFAULT_STALE_MS
    ): List<UnifiedPeer> {
        return peers.values.filter { peer ->
            peer.transports[transport]?.let { !it.isStale(staleThresholdMs) } == true
        }
    }
    
    /**
     * Prune stale peers and transport entries.
     */
    fun pruneStale(staleThresholdMs: Long = DEFAULT_STALE_MS) {
        val toRemove = mutableListOf<String>()
        
        peers.forEach { (peerId, peer) ->
            peer.pruneStaleTransports(staleThresholdMs)
            if (peer.transports.isEmpty()) {
                toRemove.add(peerId)
            }
        }
        
        toRemove.forEach { peerId ->
            val peer = peers.remove(peerId)
            // Also remove address mappings
            peer?.transports?.forEach { (transport, info) ->
                info.connectionId()?.let { connId ->
                    val key = "${transport.identifier()}:$connId"
                    addressToPeerId.remove(key)
                }
            }
            Timber.d("$TAG: Pruned stale peer: $peerId")
        }
        
        if (toRemove.isNotEmpty()) {
            updatePeerList()
        }
    }
    
    /**
     * Clear all discovered peers.
     */
    fun clear() {
        peers.clear()
        addressToPeerId.clear()
        updatePeerList()
    }
    
    /**
     * Get total peer count.
     */
    fun count(): Int = peers.size
    
    /**
     * Update the exposed peer list StateFlow.
     */
    private fun updatePeerList() {
        val list = peers.values.toList()
            .filter { it.isReachable() }
            .sortedByDescending { it.getSignalStrength() ?: Int.MIN_VALUE }
        _peerList.value = list
        _peerCount.value = list.size
        onPeersUpdated?.invoke(list)
    }
}
