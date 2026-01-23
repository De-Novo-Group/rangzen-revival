/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * Transport-specific information for a discovered peer
 */
package org.denovogroup.rangzen.backend.discovery

import android.bluetooth.BluetoothDevice

/**
 * Information about a peer as seen through a specific transport.
 * 
 * Each transport provides different metadata about a peer:
 * - BLE: Bluetooth address, device object, RSSI
 * - WiFi Direct: WiFi P2P address, device name
 * - LAN: IP address, port
 */
data class PeerTransportInfo(
    /** The transport type this info is for */
    val transport: TransportType,
    
    /** When this peer was last seen via this transport */
    val lastSeen: Long,
    
    /** Signal strength or quality indicator (-100 to 0 for RSSI, or similar) */
    val signalStrength: Int? = null,
    
    /** BLE-specific: Bluetooth device object for GATT connection */
    val bleDevice: BluetoothDevice? = null,
    
    /** BLE-specific: Bluetooth MAC address */
    val bleAddress: String? = null,
    
    /** WiFi Direct-specific: WiFi P2P device address */
    val wifiDirectAddress: String? = null,
    
    /** WiFi Direct-specific: Device name (may contain RSVP identifier) */
    val wifiDirectName: String? = null,
    
    /** LAN-specific: IP address */
    val lanAddress: String? = null,
    
    /** LAN-specific: Port number */
    val lanPort: Int? = null
) {
    /**
     * Check if this transport info is stale (older than the given threshold).
     */
    fun isStale(thresholdMs: Long): Boolean {
        return System.currentTimeMillis() - lastSeen > thresholdMs
    }
    
    /**
     * Get a connection identifier for this transport.
     * Used for establishing connections.
     */
    fun connectionId(): String? = when (transport) {
        TransportType.BLE -> bleAddress
        TransportType.WIFI_DIRECT -> wifiDirectAddress
        TransportType.LAN -> lanAddress?.let { "$it:$lanPort" }
    }
}
