/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * Modern BLE Scanner implementation for Android 14+
 * Uses BluetoothLeScanner API with proper permission handling
 */
package org.denovogroup.rangzen.backend.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.denovogroup.rangzen.backend.AppConfig
import timber.log.Timber
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * BLE Scanner for discovering nearby Rangzen peers.
 */
class BleScanner(private val context: Context) {

    companion object {
        val RANGZEN_SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        // Standard Client Characteristic Configuration Descriptor (CCCD).
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        // Tag for Android Log fallback in case Timber is filtered.
        private const val LOG_TAG = "BleScanner"
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var bleScanner: BluetoothLeScanner? = null
    private val discoveredPeers = ConcurrentHashMap<String, DiscoveredPeer>()

    private val _peers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val peers: StateFlow<List<DiscoveredPeer>> = _peers.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    var onPeerDiscovered: ((DiscoveredPeer) -> Unit)? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Log each scan result to trace discovery timing.
            Timber.d("Scan callback result type=$callbackType device=${result.device.address}")
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            results.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            // Map common scan failure codes for quicker diagnosis.
            val errorName = when (errorCode) {
                ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "SCAN_FAILED_ALREADY_STARTED"
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED"
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "SCAN_FAILED_INTERNAL_ERROR"
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "SCAN_FAILED_FEATURE_UNSUPPORTED"
                ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES"
                else -> "SCAN_FAILED_UNKNOWN"
            }
            // Emit both the numeric code and friendly name.
            Timber.e("BLE scan failed: code=$errorCode name=$errorName")
            _isScanning.value = false
        }
    }

    fun hasPermissions(): Boolean {
        val scanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else true

        val connectPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true
        
        val locationPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        return scanPermission && connectPermission && locationPermission
    }

    @SuppressLint("MissingPermission")
    fun startScanning() {
        if (!hasPermissions() || bluetoothAdapter?.isEnabled != true) {
            // Log why scanning cannot start for visibility in logs.
            Timber.w("Cannot start scanning, permissions or bluetooth state is invalid")
            return
        }

        bleScanner = bluetoothAdapter.bluetoothLeScanner
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(RANGZEN_SERVICE_UUID)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        
        // Start scanning with filter and settings.
        bleScanner?.startScan(filters, settings, scanCallback)
        _isScanning.value = true
        Timber.i("BLE scanning started")
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        if (!_isScanning.value) return
        bleScanner?.stopScan(scanCallback)
        _isScanning.value = false
        Timber.i("BLE scanning stopped")
    }

    @SuppressLint("MissingPermission")
    suspend fun exchange(peer: DiscoveredPeer, data: ByteArray): ByteArray? = suspendCancellableCoroutine { continuation ->
        var responded = false
        // Track if we already issued a read to avoid duplicates.
        var readTriggered = false
        // Handler for scheduling read fallback on the main thread.
        val handler = Handler(Looper.getMainLooper())
        // Placeholder for the characteristic used in this exchange.
        var exchangeCharacteristic: BluetoothGattCharacteristic? = null
        // Descriptor used for enabling notifications.
        var exchangeDescriptor: BluetoothGattDescriptor? = null
        // Keep the write payload available for later steps.
        val writePayload = data
        // Keep a reference to the active GATT for fallback reads.
        var activeGatt: BluetoothGatt? = null
        // Runnable for the read fallback when write callbacks are missing.
        val readFallbackRunnable = Runnable {
            // Only attempt fallback if we haven't responded yet.
            if (!responded && !readTriggered) {
                // Mark that we are triggering a fallback read.
                readTriggered = true
                // Log the fallback attempt for debugging.
                Timber.w("GATT write callback missing; forcing read fallback")
                Log.w(LOG_TAG, "GATT write callback missing; forcing read fallback")
                // Try to read the characteristic if available.
                val characteristic = exchangeCharacteristic
                // Try to read using the active GATT connection if present.
                val readStarted = if (characteristic != null && activeGatt != null) {
                    // readCharacteristic returns true if the read was initiated.
                    activeGatt?.readCharacteristic(characteristic) == true
                } else {
                    // If we are missing GATT or characteristic, report failure.
                    false
                }
                // Log that the fallback read was requested.
                Timber.i("GATT fallback read requested: started=$readStarted")
                Log.i(LOG_TAG, "GATT fallback read requested: started=$readStarted")
            }
        }
        
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                // Trace connection state transitions and status codes.
                Timber.i("GATT connection state change: status=$status newState=$newState device=${peer.address}")
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // Store active GATT connection for fallback reads.
                    activeGatt = gatt
                    // Request service discovery once connected.
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    // Cancel any pending fallback when disconnected.
                    handler.removeCallbacks(readFallbackRunnable)
                    // Clear active GATT reference on disconnect.
                    activeGatt = null
                    // Close and finish if we disconnect early.
                    gatt.close()
                    if (continuation.isActive && !responded) continuation.resume(null)
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                // Log service discovery status.
                Timber.i("GATT services discovered: status=$status device=${peer.address}")
                // Also log to Android Log to ensure visibility.
                Log.i(LOG_TAG, "GATT services discovered: status=$status device=${peer.address}")
                val service = gatt.getService(RANGZEN_SERVICE_UUID)
                val characteristic = service?.getCharacteristic(BleAdvertiser.RANGZEN_CHARACTERISTIC_UUID)
                if (characteristic != null) {
                    // Store the characteristic for fallback usage.
                    exchangeCharacteristic = characteristic
                    // Fetch the CCCD descriptor for notifications.
                    exchangeDescriptor = characteristic.getDescriptor(CCCD_UUID)
                    // Enable notifications locally so callbacks can fire.
                    val notifySet = gatt.setCharacteristicNotification(characteristic, true)
                    Timber.i("GATT notifications enabled local=$notifySet device=${peer.address}")
                    Log.i(LOG_TAG, "GATT notifications enabled local=$notifySet device=${peer.address}")
                    // If descriptor exists, write CCCD to enable notifications on remote.
                    exchangeDescriptor?.let { descriptor ->
                        // Set CCCD value for notifications.
                        val cccdValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        val cccdWriteResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            // Use new writeDescriptor API on Android 13+.
                            gatt.writeDescriptor(descriptor, cccdValue)
                        } else {
                            // Use legacy API for older devices.
                            descriptor.value = cccdValue
                            if (gatt.writeDescriptor(descriptor)) 0 else -1
                        }
                        Timber.i("GATT CCCD write started=$cccdWriteResult device=${peer.address}")
                        Log.i(LOG_TAG, "GATT CCCD write started=$cccdWriteResult device=${peer.address}")
                        // We will start the characteristic write in onDescriptorWrite.
                        return
                    }
                    // Ensure write type is explicit to avoid platform defaults.
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    // Write the request payload to the characteristic.
                    val writeResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        // Use the new API on Android 13+ for better reliability.
                        gatt.writeCharacteristic(
                            characteristic,
                            data,
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        )
                    } else {
                        // Fallback to legacy API on older devices.
                        characteristic.value = data
                        // Convert legacy boolean to an integer-style status code.
                        if (gatt.writeCharacteristic(characteristic)) 0 else -1
                    }
                    // Determine whether write was accepted for execution.
                    val writeAccepted = writeResult == 0
                    // Log write start result for debugging.
                    Timber.i("GATT write started=$writeResult device=${peer.address}")
                    // Mirror to Android Log for visibility.
                    Log.i(LOG_TAG, "GATT write started=$writeResult device=${peer.address}")
                    // Schedule a read fallback if the write was accepted.
                    if (writeAccepted) {
                        // Load the fallback delay from config (fail loud if missing).
                        val delayMs = AppConfig.gattReadFallbackDelayMs(context)
                        // Remove any existing scheduled fallback.
                        handler.removeCallbacks(readFallbackRunnable)
                        // Schedule a fallback read in case onCharacteristicWrite never fires.
                        handler.postDelayed(readFallbackRunnable, delayMs)
                        // Log the scheduled fallback delay.
                        Timber.i("Scheduled GATT read fallback after ${delayMs}ms")
                        Log.i(LOG_TAG, "Scheduled GATT read fallback after ${delayMs}ms")
                    } else {
                        // Log the failure and close the GATT connection.
                        Timber.e("GATT write could not be started; closing connection")
                        Log.e(LOG_TAG, "GATT write could not be started; closing connection")
                        gatt.close()
                    }
                } else {
                    // Log missing service/characteristic for troubleshooting.
                    Timber.w("GATT service/characteristic not found on device=${peer.address}")
                    // Mirror to Android Log for visibility.
                    Log.w(LOG_TAG, "GATT service/characteristic not found on device=${peer.address}")
                    gatt.close()
                }
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                // Log write result status.
                Timber.i("GATT characteristic write status=$status uuid=${characteristic.uuid}")
                // Mirror to Android Log for visibility.
                Log.i(LOG_TAG, "GATT characteristic write status=$status uuid=${characteristic.uuid}")
                // Cancel fallback once we receive the write callback.
                handler.removeCallbacks(readFallbackRunnable)
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    // Mark that we are explicitly triggering a read.
                    readTriggered = true
                    // Trigger read to fetch response.
                    gatt.readCharacteristic(characteristic)
                } else {
                    // Close on write failure to avoid dangling connections.
                    gatt.close()
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                // Log descriptor write status.
                Timber.i("GATT descriptor write status=$status uuid=${descriptor.uuid}")
                Log.i(LOG_TAG, "GATT descriptor write status=$status uuid=${descriptor.uuid}")
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    // Close on descriptor failure to avoid dangling connections.
                    gatt.close()
                    return
                }
                // Only proceed if this is the CCCD we wrote.
                if (descriptor.uuid == CCCD_UUID) {
                    // Grab the characteristic for writing the payload.
                    val characteristic = exchangeCharacteristic
                    if (characteristic == null) {
                        // Fail loudly if characteristic is missing.
                        Timber.e("GATT write aborted: characteristic missing after CCCD write")
                        Log.e(LOG_TAG, "GATT write aborted: characteristic missing after CCCD write")
                        gatt.close()
                        return
                    }
                    // Ensure write type is explicit before writing.
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    // Write the payload now that notifications are enabled.
                    val writeResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        // Use new API on Android 13+.
                        gatt.writeCharacteristic(
                            characteristic,
                            writePayload,
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        )
                    } else {
                        // Use legacy API on older devices.
                        characteristic.value = writePayload
                        if (gatt.writeCharacteristic(characteristic)) 0 else -1
                    }
                    // Interpret result code for logging.
                    Timber.i("GATT write after CCCD started=$writeResult device=${peer.address}")
                    Log.i(LOG_TAG, "GATT write after CCCD started=$writeResult device=${peer.address}")
                    // Schedule fallback if write was accepted.
                    if (writeResult == 0) {
                        val delayMs = AppConfig.gattReadFallbackDelayMs(context)
                        handler.removeCallbacks(readFallbackRunnable)
                        handler.postDelayed(readFallbackRunnable, delayMs)
                        Timber.i("Scheduled GATT read fallback after ${delayMs}ms")
                        Log.i(LOG_TAG, "Scheduled GATT read fallback after ${delayMs}ms")
                    } else {
                        // Close if write could not be started.
                        Timber.e("GATT write could not be started after CCCD; closing")
                        Log.e(LOG_TAG, "GATT write could not be started after CCCD; closing")
                        gatt.close()
                    }
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                // Log notification receipt for debugging.
                Timber.i("GATT notification received uuid=${characteristic.uuid}")
                Log.i(LOG_TAG, "GATT notification received uuid=${characteristic.uuid}")
                // Cancel any pending fallback since we got a response.
                handler.removeCallbacks(readFallbackRunnable)
                // Resume the coroutine with the notification payload.
                if (continuation.isActive) {
                    responded = true
                    continuation.resume(characteristic.value)
                }
                // Close the connection after handling the response.
                gatt.close()
            }

            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                // Log read result status.
                Timber.i("GATT characteristic read status=$status uuid=${characteristic.uuid}")
                // Mirror to Android Log for visibility.
                Log.i(LOG_TAG, "GATT characteristic read status=$status uuid=${characteristic.uuid}")
                // Cancel any pending fallback once read completes.
                handler.removeCallbacks(readFallbackRunnable)
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    if (continuation.isActive) {
                        responded = true
                        // Resume with the read value.
                        continuation.resume(characteristic.value)
                    }
                    // Close after successful read.
                    gatt.close()
                } else {
                    // Close on read failure.
                    gatt.close()
                }
            }
        }
        // Connect to the peer's GATT server.
        val gatt = peer.device.connectGatt(context, false, callback)
        // Ensure GATT is closed if the coroutine is cancelled.
        continuation.invokeOnCancellation {
            // Cancel any pending fallback before closing.
            handler.removeCallbacks(readFallbackRunnable)
            // Close the connection to avoid leaks.
            gatt.close()
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleScanResult(result: ScanResult) {
        if (result.scanRecord?.serviceUuids?.contains(ParcelUuid(RANGZEN_SERVICE_UUID)) == true) {
            Timber.d("Scan result from device: ${result.device.address}")
            val peer = DiscoveredPeer(
                address = result.device.address,
                name = result.device.name,
                rssi = result.rssi,
                lastSeen = System.currentTimeMillis(),
                device = result.device
            )
            if (!discoveredPeers.containsKey(peer.address)) {
                onPeerDiscovered?.invoke(peer)
            }
            discoveredPeers[peer.address] = peer
            _peers.value = discoveredPeers.values.sortedByDescending { it.rssi }
        }
    }

    fun clearPeers() {
        discoveredPeers.clear()
        _peers.value = emptyList()
    }
}
data class DiscoveredPeer(
    val address: String,
    val name: String?,
    val rssi: Int,
    val lastSeen: Long,
    val device: BluetoothDevice
)
