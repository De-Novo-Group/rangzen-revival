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

    /** WiFi Direct - longer range, higher bandwidth, but requires user confirmation popup */
    WIFI_DIRECT,

    /** WiFi Aware (NAN) - WiFi speed without confirmation dialogs, API 26+ */
    WIFI_AWARE,

    /** Local Area Network - when devices share an access point */
    LAN;

    /**
     * Get the priority for this transport when selecting which to use for exchange.
     * Higher values = higher priority.
     *
     * Priority order:
     * 1. LAN (100) - fastest, most reliable when on same network
     * 2. WiFi Aware (90) - WiFi speed, no user confirmation needed
     * 3. WiFi Direct (80) - WiFi speed, but needs user confirmation popup
     * 4. BLE (50) - slowest but most universally compatible
     */
    fun priority(): Int = when (this) {
        LAN -> 100         // Highest - fastest, most reliable
        WIFI_AWARE -> 90   // WiFi speed, no dialogs
        WIFI_DIRECT -> 80  // WiFi speed, but requires confirmation
        BLE -> 50          // Lowest - slowest but most compatible
    }

    /**
     * String identifier for telemetry and logging.
     */
    fun identifier(): String = when (this) {
        BLE -> "ble"
        WIFI_DIRECT -> "wifi_direct"
        WIFI_AWARE -> "wifi_aware"
        LAN -> "lan"
    }
}
