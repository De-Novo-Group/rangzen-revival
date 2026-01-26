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
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.ConcurrentHashMap

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

        // Global pairing callback - takes priority over instance callbacks when set
        @Volatile
        var pairingModeCallback: ((BluetoothDevice, ByteArray) -> ByteArray?)? = null

        // Flag to indicate pairing mode is active - service should pause BLE exchanges
        @Volatile
        var pairingModeActive: Boolean = false
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    var onDataReceived: ((BluetoothDevice, ByteArray) -> ByteArray?)? = null
    // Track per-device transport sessions for chunked requests.
    private val sessions = ConcurrentHashMap<String, TransportSession>()

    // Session state for assembling requests and streaming responses.
    private data class TransportSession(
        var expectedLength: Int = 0,
        var receivedLength: Int = 0,
        var requestBuffer: ByteArrayOutputStream = ByteArrayOutputStream(),
        var responseBytes: ByteArray? = null,
        var responseOffset: Int = 0,
        var maxChunkSize: Int = 0,
        var lastActivityMs: Long = System.currentTimeMillis()
    )

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _activeConnectionCount = MutableStateFlow(0)
    val activeConnectionCount: StateFlow<Int> = _activeConnectionCount.asStateFlow()
    private val activeConnections = mutableSetOf<String>()
    // Track the last inbound activity timestamp to avoid stuck sessions.
    private val _lastInboundActivityMs = MutableStateFlow(0L)
    // Expose inbound activity to the service for coordination.
    val lastInboundActivityMs: StateFlow<Long> = _lastInboundActivityMs.asStateFlow()

    private fun markInboundActivity() {
        // Record the current time for inbound activity.
        _lastInboundActivityMs.value = System.currentTimeMillis()
    }

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

    private fun getSession(device: BluetoothDevice): TransportSession {
        // Return existing session or create a new one for this device.
        return sessions.getOrPut(device.address) { TransportSession() }
    }

    private fun clearSession(device: BluetoothDevice) {
        // Remove the session to avoid stale state.
        sessions.remove(device.address)
    }

    private fun sendNotification(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray
    ) {
        // Update characteristic value before notifying.
        characteristic.value = payload
        // Notify the client with the payload, using API-specific methods.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gattServer?.notifyCharacteristicChanged(device, characteristic, false, payload)
        } else {
            gattServer?.notifyCharacteristicChanged(device, characteristic, false)
        }
    }

    private fun sendAck(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic
    ) {
        // Encode an ACK frame with no payload.
        val frame = TransportFrame(
            op = TransportProtocol.OP_ACK,
            totalLength = 0,
            offset = 0,
            payload = ByteArray(0)
        )
        val payload = TransportProtocol.encode(frame)
        // Notify the client with the ACK frame.
        sendNotification(device, characteristic, payload)
    }

    private fun sendResponseChunk(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        session: TransportSession
    ) {
        // Ensure we have response bytes to send.
        val response = session.responseBytes ?: return
        // Compute remaining bytes to send.
        val remaining = response.size - session.responseOffset
        // Determine chunk size based on the client-provided chunk size.
        val chunkSize = if (session.maxChunkSize > 0) {
            minOf(remaining, session.maxChunkSize)
        } else {
            remaining
        }
        // Slice the response for this chunk.
        val chunk = response.copyOfRange(
            session.responseOffset,
            session.responseOffset + chunkSize
        )
        // Build a DATA frame for this chunk.
        val frame = TransportFrame(
            op = TransportProtocol.OP_DATA,
            totalLength = response.size,
            offset = session.responseOffset,
            payload = chunk
        )
        // Advance response offset.
        session.responseOffset += chunkSize
        // Notify the client with the response frame.
        sendNotification(device, characteristic, TransportProtocol.encode(frame))
        // Clear session if we have sent the full response.
        if (session.responseOffset >= response.size) {
            clearSession(device)
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            // Log connection status transitions for the GATT server.
            Timber.i("GATT server connection change: status=$status newState=$newState device=${device.address}")
            // Mirror to Android Log for visibility.
            Log.i(LOG_TAG, "GATT server connection change: status=$status newState=$newState device=${device.address}")
            // Treat connection changes as inbound activity.
            markInboundActivity()
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Timber.i("GATT server connected to ${device.address}")
                // Track active connections to avoid initiating while serving.
                activeConnections.add(device.address)
                _activeConnectionCount.value = activeConnections.size
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Timber.i("GATT server disconnected from ${device.address}")
                // Clear any in-flight transport session for this device.
                clearSession(device)
                // Update active connection tracking.
                activeConnections.remove(device.address)
                _activeConnectionCount.value = activeConnections.size
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
            // Mark inbound activity for read requests.
            markInboundActivity()
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
            // Mark inbound activity for write requests.
            markInboundActivity()
            if (characteristic.uuid == RANGZEN_CHARACTERISTIC_UUID) {
                if (preparedWrite || offset > 0) {
                    Timber.e("Prepared/offset writes are not supported (offset=$offset prepared=$preparedWrite)")
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                    }
                    return
                }
                // Decode the transport frame.
                val frame = TransportProtocol.decode(value)
                if (frame == null) {
                    Timber.e("Failed to decode transport frame from ${device.address}")
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                    }
                    return
                }
                // Update session activity timestamp.
                val session = getSession(device)
                session.lastActivityMs = System.currentTimeMillis()
                when (frame.op) {
                    TransportProtocol.OP_DATA -> {
                        // Initialize request tracking if needed.
                        if (session.expectedLength == 0) {
                            session.expectedLength = frame.totalLength
                            session.receivedLength = 0
                            session.requestBuffer = ByteArrayOutputStream()
                            session.responseBytes = null
                            session.responseOffset = 0
                            session.maxChunkSize = frame.payload.size
                        }
                        // Enforce sequential offsets.
                        if (frame.offset != session.receivedLength) {
                            Timber.e("Request offset mismatch expected=${session.receivedLength} got=${frame.offset}")
                            clearSession(device)
                            if (responseNeeded) {
                                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                            }
                            return
                        }
                        // Append payload to the request buffer.
                        session.requestBuffer.write(frame.payload)
                        session.receivedLength += frame.payload.size
                        // Acknowledge the write immediately.
                        if (responseNeeded) {
                            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                        }
                        // If request is incomplete, send ACK and wait for more data.
                        if (session.receivedLength < session.expectedLength) {
                            sendAck(device, characteristic)
                            return
                        }
                        // Build the full request payload.
                        val requestPayload = session.requestBuffer.toByteArray()
                        // Process the request - pairing mode callback takes priority, but falls back to regular handler
                        Timber.i("Processing request from ${device.address}, size=${requestPayload.size}, pairingModeCallback=${pairingModeCallback != null}")
                        var responsePayload: ByteArray? = null
                        if (pairingModeCallback != null) {
                            Timber.i("Trying pairingModeCallback for ${device.address}")
                            responsePayload = pairingModeCallback?.invoke(device, requestPayload)
                            Timber.i("pairingModeCallback returned ${responsePayload?.size ?: "null"} bytes")
                        }
                        // Fall back to regular handler if pairing callback returns null or isn't set
                        if (responsePayload == null) {
                            Timber.i("Falling back to onDataReceived for ${device.address}")
                            responsePayload = onDataReceived?.invoke(device, requestPayload)
                        }
                        if (responsePayload == null) {
                            Timber.e("No response generated for ${device.address}; rejecting request")
                            clearSession(device)
                            return
                        }
                        // Store response bytes and send the first chunk.
                        session.responseBytes = responsePayload
                        session.responseOffset = 0
                        sendResponseChunk(device, characteristic, session)
                    }
                    TransportProtocol.OP_CONTINUE -> {
                        // Acknowledge the continue request.
                        if (responseNeeded) {
                            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                        }
                        // Send the next response chunk if available.
                        if (session.responseBytes == null) {
                            Timber.e("Continue requested without response data for ${device.address}")
                            clearSession(device)
                            return
                        }
                        sendResponseChunk(device, characteristic, session)
                    }
                    else -> {
                        Timber.e("Unknown transport op=${frame.op} from ${device.address}")
                        clearSession(device)
                        if (responseNeeded) {
                            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                        }
                    }
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
            // Mark inbound activity for CCCD updates.
            markInboundActivity()
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

    fun setExchangeCallback(callback: (BluetoothDevice, ByteArray) -> ByteArray?) {
        onDataReceived = callback
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising() {
        if (!hasPermissions() || bluetoothAdapter?.isEnabled != true) {
            // Log why advertising cannot start for visibility.
            Timber.w("Cannot start advertising, permissions or bluetooth state is invalid")
            return
        }

        // Initialize inbound activity timestamp for fresh sessions.
        markInboundActivity()
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
