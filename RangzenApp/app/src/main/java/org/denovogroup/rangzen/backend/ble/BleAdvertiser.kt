/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * Modern BLE Advertiser implementation for Android 14+
 * Advertises the Rangzen service UUID so other peers can discover us
 */
package org.denovogroup.rangzen.backend.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.*

/**
 * BLE Advertiser for making this device discoverable to other Rangzen peers.
 *
 * Broadcasts the Rangzen service UUID so that BleScanner on other devices
 * can find us and initiate a connection for message exchange.
 */
class BleAdvertiser(private val context: Context) {

    companion object {
        val RANGZEN_CHARACTERISTIC_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        // Standard Client Characteristic Configuration Descriptor (CCCD).
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        // Tag for Android Log fallback in case Timber is filtered.
        private const val LOG_TAG = "BleAdvertiser"
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    var onDataReceived: ((ByteArray) -> ByteArray)? = null

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            _isAdvertising.value = true
            _error.value = null
            Timber.i("BLE advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            val errorMessage = "Advertisement failed with error code: $errorCode"
            _isAdvertising.value = false
            _error.value = errorMessage
            Timber.e(errorMessage)
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            // Log connection status transitions for the GATT server.
            Timber.i("GATT server connection change: status=$status newState=$newState device=${device.address}")
            // Mirror to Android Log for visibility.
            Log.i(LOG_TAG, "GATT server connection change: status=$status newState=$newState device=${device.address}")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Timber.i("GATT server connected to ${device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Timber.i("GATT server disconnected from ${device.address}")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            // Log read requests to verify the client is querying our characteristic.
            Timber.i("GATT read request: device=${device.address} requestId=$requestId offset=$offset uuid=${characteristic.uuid}")
            // Mirror to Android Log for visibility.
            Log.i(LOG_TAG, "GATT read request: device=${device.address} requestId=$requestId offset=$offset uuid=${characteristic.uuid}")
            if (characteristic.uuid == RANGZEN_CHARACTERISTIC_UUID) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, characteristic.value)
            } else {
                // Respond with failure if the UUID is unexpected.
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
            // Log incoming writes to confirm request payloads arrive.
            Timber.i("GATT write request: device=${device.address} requestId=$requestId offset=$offset uuid=${characteristic.uuid} size=${value.size}")
            // Mirror to Android Log for visibility.
            Log.i(LOG_TAG, "GATT write request: device=${device.address} requestId=$requestId offset=$offset uuid=${characteristic.uuid} size=${value.size}")
            if (characteristic.uuid == RANGZEN_CHARACTERISTIC_UUID) {
                val response = onDataReceived?.invoke(value) ?: "NOOP".toByteArray()
                characteristic.value = response
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
                // Send response via notification to avoid client-side read issues.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // Use new notify API with value payload.
                    gattServer?.notifyCharacteristicChanged(device, characteristic, false, response)
                } else {
                    // Fallback to legacy notify API.
                    gattServer?.notifyCharacteristicChanged(device, characteristic, false)
                }
            } else if (responseNeeded) {
                // Respond with failure for unknown characteristic UUIDs.
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)
            // Log CCCD writes to confirm notifications are enabled.
            Timber.i("GATT descriptor write: device=${device.address} requestId=$requestId uuid=${descriptor.uuid} size=${value.size}")
            Log.i(LOG_TAG, "GATT descriptor write: device=${device.address} requestId=$requestId uuid=${descriptor.uuid} size=${value.size}")
            if (responseNeeded) {
                // Acknowledge descriptor write to complete subscription.
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
    }

    fun hasPermissions(): Boolean {
        val advertisePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        } else true
        
        val connectPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true
        
        return advertisePermission && connectPermission
    }

    fun setExchangeCallback(callback: (ByteArray) -> ByteArray) {
        onDataReceived = callback
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising() {
        if (!hasPermissions() || bluetoothAdapter?.isEnabled != true) {
            // Log why advertising cannot start for visibility.
            Timber.w("Cannot start advertising, permissions or bluetooth state is invalid")
            return
        }

        bleAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
        gattServer = bluetoothManager?.openGattServer(context, gattServerCallback)

        val service = BluetoothGattService(BleScanner.RANGZEN_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            RANGZEN_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        // Add CCCD so clients can enable notifications.
        val cccd = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        // Attach CCCD to the characteristic.
        characteristic.addDescriptor(cccd)
        service.addCharacteristic(characteristic)
        // Register the GATT service and log the result.
        val serviceAdded = gattServer?.addService(service) ?: false
        Timber.i("GATT service added: $serviceAdded")

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BleScanner.RANGZEN_SERVICE_UUID))
            .build()

        // Start BLE advertising with the configured settings.
        bleAdvertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        bleAdvertiser?.stopAdvertising(advertiseCallback)
        gattServer?.close()
        _isAdvertising.value = false
        Timber.i("BLE advertising stopped")
    }
}
