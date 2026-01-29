/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * Unified peer representation aggregating multiple transports
 */
package org.denovogroup.rangzen.backend.discovery

import android.bluetooth.BluetoothDevice

/**
 * A unified representation of a discovered peer that may be seen via multiple transports.
 * 
 * The key insight is that the same physical device might be discovered via:
 * - BLE advertising
 * - WiFi Direct discovery
 * - LAN broadcast
 * 
 * We correlate these discoveries using app-layer handshake (public ID exchange),
 * not relying on device names or MAC addresses which aren't reliable on modern Android.
 * 
 * @property publicId The peer's unique identifier (established via handshake)
 * @property transports Map of transport types to their specific info
 * @property firstSeen When this peer was first discovered
 * @property lastActivity When any transport last saw this peer
 * @property handshakeCompleted Whether we've done an app-layer handshake with this peer
 */
data class UnifiedPeer(
    val publicId: String,
    val transports: MutableMap<TransportType, PeerTransportInfo> = mutableMapOf(),
    val firstSeen: Long = System.currentTimeMillis(),
    var lastActivity: Long = System.currentTimeMillis(),
    var handshakeCompleted: Boolean = false
) {
    /**
     * Check if this peer is currently reachable via any transport.
     */
    fun isReachable(staleThresholdMs: Long = 30_000L): Boolean {
        return transports.values.any { !it.isStale(staleThresholdMs) }
    }
    
    /**
     * Get the best transport to use for connection, based on priority and freshness.
     */
    fun bestTransport(staleThresholdMs: Long = 30_000L): TransportType? {
        return transports.entries
            .filter { !it.value.isStale(staleThresholdMs) }
            .maxByOrNull { it.key.priority() }
            ?.key
    }
    
    /**
     * Get the list of available (non-stale) transports, sorted by priority.
     */
    fun availableTransports(staleThresholdMs: Long = 30_000L): List<TransportType> {
        return transports.entries
            .filter { !it.value.isStale(staleThresholdMs) }
            .sortedByDescending { it.key.priority() }
            .map { it.key }
    }
    
    /**
     * Update transport info for this peer.
     */
    fun updateTransport(info: PeerTransportInfo) {
        transports[info.transport] = info
        lastActivity = maxOf(lastActivity, info.lastSeen)
    }

    /**
     * Remove a specific transport from this peer.
     * Used when an address is reassigned to a different device.
     */
    fun removeTransport(transport: TransportType) {
        transports.remove(transport)
    }

    /**
     * Remove stale transport entries.
     */
    fun pruneStaleTransports(staleThresholdMs: Long = 30_000L) {
        val staleTransports = transports.entries
            .filter { it.value.isStale(staleThresholdMs) }
            .map { it.key }
        staleTransports.forEach { transports.remove(it) }
    }
    
    /**
     * Get BLE device if available and fresh.
     */
    fun getBleDevice(staleThresholdMs: Long = 30_000L): BluetoothDevice? {
        val bleInfo = transports[TransportType.BLE] ?: return null
        if (bleInfo.isStale(staleThresholdMs)) return null
        return bleInfo.bleDevice
    }
    
    /**
     * Get BLE address if available.
     */
    fun getBleAddress(): String? {
        return transports[TransportType.BLE]?.bleAddress
    }
    
    /**
     * Get WiFi Direct address if available.
     */
    fun getWifiDirectAddress(): String? {
        return transports[TransportType.WIFI_DIRECT]?.wifiDirectAddress
    }
    
    /**
     * Get signal strength (best available across transports).
     */
    fun getSignalStrength(): Int? {
        return transports.values
            .mapNotNull { it.signalStrength }
            .maxOrNull()
    }
    
    /**
     * Check if this peer has been seen via the specified transport.
     */
    fun hasTransport(transport: TransportType): Boolean {
        return transports.containsKey(transport)
    }
}
