package org.denovogroup.rangzen.backend.telemetry

import android.bluetooth.BluetoothGatt
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

/**
 * Classifies exceptions into ErrorCategory values for telemetry aggregation.
 */
object ErrorClassifier {

    /**
     * Categorize an exception into an ErrorCategory.
     */
    fun categorize(error: Throwable): ErrorCategory {
        val message = error.message?.lowercase() ?: ""

        return when {
            // Socket/connection errors
            error is SocketTimeoutException -> ErrorCategory.CONNECTION_TIMEOUT
            error is ConnectException -> ErrorCategory.CONNECTION_REFUSED
            error is SocketException && message.contains("reset") -> ErrorCategory.CONNECTION_RESET
            error is SocketException && message.contains("closed") -> ErrorCategory.SOCKET_CLOSED
            error is SocketException -> ErrorCategory.CONNECTION_RESET
            error is EOFException -> ErrorCategory.SOCKET_CLOSED

            // Timeout errors
            error is TimeoutException -> ErrorCategory.TIMEOUT
            message.contains("timeout") -> ErrorCategory.TIMEOUT

            // BLE-specific errors
            message.contains("gatt") -> ErrorCategory.BLE_GATT_ERROR
            message.contains("disconnected") && message.contains("ble") -> ErrorCategory.BLE_DISCONNECTED
            message.contains("mtu") -> ErrorCategory.BLE_MTU_ERROR
            message.contains("characteristic") -> ErrorCategory.BLE_CHARACTERISTIC_ERROR
            message.contains("service not found") -> ErrorCategory.BLE_SERVICE_NOT_FOUND

            // Protocol errors
            message.contains("psi") && message.contains("handshake") -> ErrorCategory.PSI_HANDSHAKE_FAILED
            message.contains("trust") && (message.contains("rejected") || message.contains("below")) -> ErrorCategory.PSI_TRUST_REJECTED
            message.contains("version") && message.contains("mismatch") -> ErrorCategory.PROTOCOL_VERSION_MISMATCH
            message.contains("parse") || message.contains("malformed") -> ErrorCategory.MESSAGE_PARSE_ERROR
            message.contains("unexpected") -> ErrorCategory.UNEXPECTED_RESPONSE

            // Resource errors
            error is OutOfMemoryError -> ErrorCategory.OUT_OF_MEMORY
            message.contains("out of memory") || message.contains("oom") -> ErrorCategory.OUT_OF_MEMORY
            message.contains("no space") || message.contains("storage full") -> ErrorCategory.STORAGE_FULL

            // Network state errors
            message.contains("wifi") && (message.contains("disabled") || message.contains("off")) -> ErrorCategory.WIFI_DISABLED
            message.contains("bluetooth") && (message.contains("disabled") || message.contains("off")) -> ErrorCategory.BLUETOOTH_DISABLED

            // Security errors
            message.contains("encrypt") && message.contains("fail") -> ErrorCategory.ENCRYPTION_FAILED
            message.contains("decrypt") && message.contains("fail") -> ErrorCategory.DECRYPTION_FAILED
            message.contains("signature") && message.contains("invalid") -> ErrorCategory.SIGNATURE_INVALID

            // Cancellation
            message.contains("cancel") -> ErrorCategory.CANCELLED
            error is InterruptedException -> ErrorCategory.CANCELLED

            // IO errors that look like connection issues
            error is IOException && message.contains("broken pipe") -> ErrorCategory.CONNECTION_RESET
            error is IOException && message.contains("connection") -> ErrorCategory.CONNECTION_REFUSED

            else -> ErrorCategory.UNKNOWN
        }
    }

    /**
     * Categorize a BLE GATT status code.
     */
    fun categorizeGattStatus(status: Int): ErrorCategory {
        return when (status) {
            BluetoothGatt.GATT_SUCCESS -> ErrorCategory.UNKNOWN // Not an error
            BluetoothGatt.GATT_CONNECTION_CONGESTED -> ErrorCategory.BLE_GATT_ERROR
            BluetoothGatt.GATT_FAILURE -> ErrorCategory.BLE_GATT_ERROR
            BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION -> ErrorCategory.BLE_GATT_ERROR
            BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION -> ErrorCategory.ENCRYPTION_FAILED
            BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> ErrorCategory.BLE_CHARACTERISTIC_ERROR
            BluetoothGatt.GATT_INVALID_OFFSET -> ErrorCategory.BLE_CHARACTERISTIC_ERROR
            BluetoothGatt.GATT_READ_NOT_PERMITTED -> ErrorCategory.BLE_CHARACTERISTIC_ERROR
            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED -> ErrorCategory.BLE_CHARACTERISTIC_ERROR
            BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> ErrorCategory.BLE_CHARACTERISTIC_ERROR
            8 -> ErrorCategory.CONNECTION_TIMEOUT // GATT_CONN_TIMEOUT
            19 -> ErrorCategory.BLE_DISCONNECTED // GATT_CONN_TERMINATE_PEER_USER
            22 -> ErrorCategory.BLE_DISCONNECTED // GATT_CONN_TERMINATE_LOCAL_HOST
            133 -> ErrorCategory.BLE_GATT_ERROR // GATT_ERROR (generic)
            else -> ErrorCategory.BLE_GATT_ERROR
        }
    }
}
