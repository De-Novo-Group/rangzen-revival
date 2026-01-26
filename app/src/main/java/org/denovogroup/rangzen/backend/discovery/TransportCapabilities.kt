/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * Device capability detection for transport selection
 */
package org.denovogroup.rangzen.backend.discovery

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import timber.log.Timber

/**
 * Helper object to detect which transports are available on the current device.
 *
 * Used for:
 * 1. Automatic transport selection (prefer WiFi Aware over WiFi Direct)
 * 2. QA mode display (show which transports the device supports)
 * 3. Graceful degradation on older/limited devices
 */
object TransportCapabilities {

    private const val TAG = "TransportCapabilities"

    /**
     * Get the set of transports supported by this device's hardware.
     * This checks for hardware features, not runtime availability.
     */
    fun getSupportedTransports(context: Context): Set<TransportType> {
        val supported = mutableSetOf<TransportType>()
        val pm = context.packageManager

        // BLE - almost universally available on modern Android
        if (pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            supported.add(TransportType.BLE)
        }

        // WiFi Direct - widely available
        if (pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            supported.add(TransportType.WIFI_DIRECT)
        }

        // WiFi Aware (NAN) - API 26+ and hardware support
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
            supported.add(TransportType.WIFI_AWARE)
        }

        // LAN - always potentially available (depends on network state)
        supported.add(TransportType.LAN)

        Timber.d("$TAG: Device supports transports: $supported")
        return supported
    }

    /**
     * Get transports that are currently available for use.
     * This checks both hardware support AND current runtime conditions.
     */
    fun getAvailableTransports(context: Context): Set<TransportType> {
        val available = mutableSetOf<TransportType>()
        val pm = context.packageManager

        // BLE - check if Bluetooth is enabled
        if (pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            // We assume BLE is available if hardware supports it
            // Permission/state checks happen in the actual BLE code
            available.add(TransportType.BLE)
        }

        // WiFi Direct - check if WiFi is enabled
        if (pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager?.isWifiEnabled == true) {
                available.add(TransportType.WIFI_DIRECT)
            }
        }

        // WiFi Aware - check if hardware supports and WiFi Aware is currently available
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
            val wifiAwareManager = context.getSystemService(Context.WIFI_AWARE_SERVICE)
                as? android.net.wifi.aware.WifiAwareManager
            if (wifiAwareManager?.isAvailable == true) {
                available.add(TransportType.WIFI_AWARE)
            }
        }

        // LAN - check if connected to WiFi network
        if (isConnectedToWifi(context)) {
            available.add(TransportType.LAN)
        }

        return available
    }

    /**
     * Check if WiFi Aware is supported on this device.
     */
    fun isWifiAwareSupported(context: Context): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
    }

    /**
     * Check if WiFi Aware is currently available (hardware + enabled).
     */
    fun isWifiAwareAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false

        val wifiAwareManager = context.getSystemService(Context.WIFI_AWARE_SERVICE)
            as? android.net.wifi.aware.WifiAwareManager
        return wifiAwareManager?.isAvailable == true
    }

    /**
     * Check if WiFi Direct is supported.
     */
    fun isWifiDirectSupported(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
    }

    /**
     * Check if BLE is supported.
     */
    fun isBleSupported(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    }

    /**
     * Check if device is connected to a WiFi network (for LAN transport).
     */
    fun isConnectedToWifi(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false

        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Get a human-readable summary of supported transports.
     */
    fun getSupportedTransportsSummary(context: Context): String {
        val supported = getSupportedTransports(context)
        val names = supported.map { transport ->
            when (transport) {
                TransportType.BLE -> "BLE"
                TransportType.WIFI_DIRECT -> "WiFi Direct"
                TransportType.WIFI_AWARE -> "WiFi Aware"
                TransportType.LAN -> "LAN"
            }
        }
        return names.joinToString(", ")
    }
}
