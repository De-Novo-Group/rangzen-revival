/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * Transport types for multi-transport discovery
 */
package org.denovogroup.rangzen.backend.discovery

/**
 * Enum representing the different transport mechanisms for peer discovery and exchange.
 */
enum class TransportType {
    /** Bluetooth Low Energy - short range, low power, proven exchange protocol */
    BLE,
    
    /** WiFi Direct - longer range, higher bandwidth potential */
    WIFI_DIRECT,
    
    /** Local Area Network - when devices share an access point */
    LAN;
    
    /**
     * Get the priority for this transport when selecting which to use for exchange.
     * Higher values = higher priority.
     */
    fun priority(): Int = when (this) {
        WIFI_DIRECT -> 3  // Prefer WiFi Direct for bandwidth
        LAN -> 2          // LAN is reliable when available
        BLE -> 1          // BLE is fallback (always works, but slower)
    }
    
    /**
     * String identifier for telemetry and logging.
     */
    fun identifier(): String = when (this) {
        BLE -> "ble"
        WIFI_DIRECT -> "wifi_direct"
        LAN -> "lan"
    }
}
