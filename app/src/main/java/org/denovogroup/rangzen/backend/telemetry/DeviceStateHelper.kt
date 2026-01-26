package org.denovogroup.rangzen.backend.telemetry

import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import timber.log.Timber

/**
 * Helper to capture device state for telemetry events.
 */
object DeviceStateHelper {

    /**
     * Capture current device state as a map for telemetry payload.
     */
    fun capture(context: Context): Map<String, Any> {
        val result = mutableMapOf<String, Any>()

        try {
            // Battery level
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryIntent?.let {
                val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    result["battery_pct"] = (level * 100 / scale)
                }

                // Charging status
                val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                result["is_charging"] = isCharging
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to get battery state")
        }

        try {
            // Power save mode
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.let {
                result["power_save"] = it.isPowerSaveMode
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to get power save mode")
        }

        try {
            // App foreground/background state
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            activityManager?.let {
                val processes = it.runningAppProcesses
                val isBackground = processes?.none { proc ->
                    proc.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                            proc.processName == context.packageName
                } ?: true
                result["is_background"] = isBackground
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to get app process state")
        }

        // Radio state
        result["radio_state"] = captureRadioState(context)

        return result
    }

    /**
     * Capture radio (WiFi/Bluetooth) state for telemetry.
     */
    private fun captureRadioState(context: Context): Map<String, Any> {
        val radioState = mutableMapOf<String, Any>()

        try {
            // WiFi state
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.let {
                radioState["wifi_enabled"] = it.isWifiEnabled
            }

            // WiFi connected
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            connectivityManager?.let { cm ->
                val network = cm.activeNetwork
                val capabilities = network?.let { cm.getNetworkCapabilities(it) }
                radioState["wifi_connected"] = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }

            // WiFi Direct supported
            val p2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            radioState["wifi_direct_supported"] = p2pManager != null
        } catch (e: Exception) {
            Timber.w(e, "Failed to get WiFi state")
        }

        try {
            // Bluetooth state
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val bluetoothAdapter = bluetoothManager?.adapter
            bluetoothAdapter?.let {
                radioState["bluetooth_enabled"] = it.isEnabled
                try {
                    radioState["bluetooth_paired_count"] = it.bondedDevices?.size ?: 0
                } catch (e: SecurityException) {
                    // No BLUETOOTH_CONNECT permission
                    Timber.v("No permission for bonded devices count")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to get Bluetooth state")
        }

        return radioState
    }
}
