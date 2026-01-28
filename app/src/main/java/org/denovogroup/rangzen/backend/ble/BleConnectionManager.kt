package org.denovogroup.rangzen.backend.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ktx.suspend
import timber.log.Timber
import java.util.UUID

/**
 * BLE Connection Manager using Nordic's Android-BLE-Library.
 *
 * This class maintains a single GATT connection for the entire exchange session,
 * following Android BLE best practices:
 * 1. Connect once
 * 2. Discover services once
 * 3. Enable notifications once
 * 4. Exchange all data over the same connection
 * 5. Disconnect when complete
 *
 * This replaces the old pattern of creating a new connection per frame.
 */
class BleConnectionManager(context: Context) : BleManager(context) {

    companion object {
        private const val LOG_TAG = "BleConnectionManager"

        // Service and Characteristic UUIDs (same as existing implementation)
        val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    // The characteristic used for read/write operations
    private var exchangeCharacteristic: BluetoothGattCharacteristic? = null

    // Channel to receive notification responses
    private val responseChannel = Channel<ByteArray>(Channel.CONFLATED)

    // Track if we're fully initialized (connected + services discovered + notifications enabled)
    private var isReady = false

    // Diagnostics for debugging
    var lastMtu: Int = 23
        private set

    override fun getGattCallback(): BleManagerGattCallback = GattCallback()

    /**
     * GATT callback implementation following Nordic's pattern.
     */
    private inner class GattCallback : BleManagerGattCallback() {

        /**
         * Called after services are discovered.
         * Verify that required service and characteristic exist.
         */
        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val service: BluetoothGattService? = gatt.getService(SERVICE_UUID)
            if (service == null) {
                Log.e(LOG_TAG, "Required service not found: $SERVICE_UUID")
                return false
            }

            exchangeCharacteristic = service.getCharacteristic(CHARACTERISTIC_UUID)
            if (exchangeCharacteristic == null) {
                Log.e(LOG_TAG, "Required characteristic not found: $CHARACTERISTIC_UUID")
                return false
            }

            // Verify characteristic has required properties
            val properties = exchangeCharacteristic!!.properties
            val hasWrite = (properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                    (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            val hasNotify = (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                    (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

            Log.i(LOG_TAG, "Service found. Characteristic properties: write=$hasWrite, notify=$hasNotify")

            return hasWrite && hasNotify
        }

        /**
         * Called after service discovery is complete and required services are supported.
         * Initialize notifications here.
         */
        override fun initialize() {
            Log.i(LOG_TAG, "Initializing BLE connection - enabling notifications")

            // Set up notification callback
            setNotificationCallback(exchangeCharacteristic).with { device, data ->
                val bytes = data.value
                if (bytes != null && bytes.isNotEmpty()) {
                    Log.d(LOG_TAG, "Notification received: ${bytes.size} bytes from ${device.address}")
                    responseChannel.trySend(bytes)
                }
            }

            // Enable notifications
            enableNotifications(exchangeCharacteristic)
                .done { device ->
                    Log.i(LOG_TAG, "Notifications enabled for ${device.address}")
                    isReady = true
                }
                .fail { device, status ->
                    Log.e(LOG_TAG, "Failed to enable notifications for ${device.address}, status=$status")
                    isReady = false
                }
                .enqueue()
        }

        /**
         * Called when device disconnects or services become invalid.
         */
        override fun onServicesInvalidated() {
            Log.i(LOG_TAG, "Services invalidated - connection lost")
            exchangeCharacteristic = null
            isReady = false
        }
    }

    /**
     * Connect to device and prepare for data exchange.
     *
     * @param device The BLE device to connect to
     * @param timeoutMs Connection timeout in milliseconds
     * @return true if connection was successful and ready for exchange
     */
    suspend fun connectAndPrepare(device: BluetoothDevice, timeoutMs: Long = 30000): Boolean {
        Log.i(LOG_TAG, "Connecting to ${device.address}...")

        return try {
            withTimeoutOrNull(timeoutMs) {
                // Connect with retry
                connect(device)
                    .retry(3, 1000)
                    .useAutoConnect(false)
                    .suspend()

                // Request large MTU for efficiency
                // The suspend() extension returns the negotiated MTU value directly
                try {
                    lastMtu = requestMtu(517).suspend()
                    Log.i(LOG_TAG, "MTU negotiated: $lastMtu")
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "MTU negotiation failed, using default: ${e.message}")
                    lastMtu = 23 // Default BLE MTU
                }

                // Wait for initialization to complete
                while (!isReady) {
                    kotlinx.coroutines.delay(50)
                }

                true
            } ?: run {
                Log.e(LOG_TAG, "Connection timeout to ${device.address}")
                false
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Connection failed to ${device.address}: ${e.message}")
            Timber.e(e, "BLE connection failed")
            false
        }
    }

    /**
     * Send request data and wait for response via notification.
     *
     * This is the core exchange operation - write request, wait for response.
     * Unlike the old implementation, this does NOT create a new connection.
     *
     * @param request The request data to send
     * @param timeoutMs Timeout for waiting for response
     * @return Response bytes, or null if timeout/error
     */
    suspend fun sendAndReceive(request: ByteArray, timeoutMs: Long = 10000): ByteArray? {
        if (!isReady || exchangeCharacteristic == null) {
            Log.e(LOG_TAG, "Connection not ready for exchange")
            return null
        }

        // Clear any pending responses
        while (responseChannel.tryReceive().isSuccess) {
            // Drain channel
        }

        Log.d(LOG_TAG, "Sending ${request.size} bytes...")

        return try {
            // Write request
            writeCharacteristic(
                exchangeCharacteristic,
                request,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ).suspend()

            // Wait for response via notification
            withTimeoutOrNull(timeoutMs) {
                val response = responseChannel.receive()
                Log.d(LOG_TAG, "Received response: ${response.size} bytes")
                response
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Send/receive failed: ${e.message}")
            Timber.e(e, "BLE send/receive failed")
            null
        }
    }

    /**
     * Disconnect from the device.
     */
    suspend fun disconnectAndClose() {
        Log.i(LOG_TAG, "Disconnecting...")
        try {
            disconnect().suspend()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Disconnect error (may already be disconnected): ${e.message}")
        }
        isReady = false
    }

    /**
     * Check if connection is ready for exchange.
     */
    fun isConnectionReady(): Boolean = isReady && exchangeCharacteristic != null
}
