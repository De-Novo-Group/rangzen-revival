package org.denovogroup.rangzen.backend.ble

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Interface for BLE data exchange operations.
 *
 * This abstracts the BLE connection management from the exchange protocol,
 * allowing the LegacyExchangeClient to focus on the protocol logic while
 * this handles the connection lifecycle.
 */
interface BleExchange {

    /**
     * Perform a complete exchange session with a BLE peer.
     *
     * The exchange happens over a SINGLE connection:
     * 1. Connect to device
     * 2. Execute all send/receive operations via the handler
     * 3. Disconnect
     *
     * @param device The BLE device to exchange with
     * @param handler Lambda that performs the actual exchange protocol.
     *                The handler receives a suspend function to send requests
     *                and receive responses. Returns the exchange result.
     * @return Exchange result, or null if exchange failed
     */
    suspend fun <T> exchange(
        device: BluetoothDevice,
        handler: suspend (sendRequest: suspend (ByteArray) -> ByteArray?) -> T?
    ): T?
}

/**
 * Implementation of BleExchange using Nordic BLE Library.
 *
 * This maintains a single GATT connection for the entire exchange session,
 * following Android BLE best practices.
 */
class BleExchangeImpl(private val context: Context) : BleExchange {

    companion object {
        private const val LOG_TAG = "BleExchangeImpl"
        private const val CONNECTION_TIMEOUT_MS = 30_000L
        private const val REQUEST_TIMEOUT_MS = 10_000L
    }

    override suspend fun <T> exchange(
        device: BluetoothDevice,
        handler: suspend (sendRequest: suspend (ByteArray) -> ByteArray?) -> T?
    ): T? = withContext(Dispatchers.IO) {

        Log.i(LOG_TAG, "Starting BLE exchange with ${device.address}")
        val startTime = System.currentTimeMillis()

        val manager = BleConnectionManager(context)

        try {
            // Step 1: Connect (single connection for entire exchange)
            val connected = manager.connectAndPrepare(device, CONNECTION_TIMEOUT_MS)
            if (!connected) {
                Log.e(LOG_TAG, "Failed to connect to ${device.address}")
                return@withContext null
            }

            val connectTime = System.currentTimeMillis() - startTime
            Log.i(LOG_TAG, "Connected to ${device.address} in ${connectTime}ms, MTU=${manager.lastMtu}")

            // Step 2: Execute exchange protocol
            // The handler does all the send/receive operations over this connection
            val result = handler { request ->
                manager.sendAndReceive(request, REQUEST_TIMEOUT_MS)
            }

            val totalTime = System.currentTimeMillis() - startTime
            Log.i(LOG_TAG, "Exchange complete with ${device.address}: time=${totalTime}ms")

            result

        } catch (e: Exception) {
            Log.e(LOG_TAG, "Exchange failed with ${device.address}: ${e.message}")
            Timber.e(e, "BLE exchange failed")
            null
        } finally {
            // Step 3: Always disconnect
            try {
                manager.disconnectAndClose()
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Error during disconnect: ${e.message}")
            }
        }
    }
}
