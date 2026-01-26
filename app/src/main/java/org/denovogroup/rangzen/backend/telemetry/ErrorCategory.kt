package org.denovogroup.rangzen.backend.telemetry

/**
 * Categorized error types for telemetry aggregation and dashboarding.
 * Each category maps to a short code for compact storage.
 */
enum class ErrorCategory(val code: String) {
    // Connection errors
    CONNECTION_TIMEOUT("conn_timeout"),
    CONNECTION_REFUSED("conn_refused"),
    CONNECTION_RESET("conn_reset"),
    SOCKET_CLOSED("socket_closed"),

    // BLE-specific errors
    BLE_GATT_ERROR("ble_gatt"),
    BLE_DISCONNECTED("ble_disconnect"),
    BLE_MTU_ERROR("ble_mtu"),
    BLE_CHARACTERISTIC_ERROR("ble_char"),
    BLE_SERVICE_NOT_FOUND("ble_no_service"),

    // Protocol errors
    PSI_HANDSHAKE_FAILED("psi_handshake"),
    PSI_TRUST_REJECTED("psi_trust"),
    PROTOCOL_VERSION_MISMATCH("proto_version"),
    MESSAGE_PARSE_ERROR("msg_parse"),
    UNEXPECTED_RESPONSE("unexpected_resp"),

    // Resource errors
    OUT_OF_MEMORY("oom"),
    STORAGE_FULL("storage_full"),

    // Network errors
    WIFI_DISABLED("wifi_off"),
    BLUETOOTH_DISABLED("bt_off"),
    NO_INTERNET("no_internet"),

    // Security errors
    ENCRYPTION_FAILED("encrypt_fail"),
    DECRYPTION_FAILED("decrypt_fail"),
    SIGNATURE_INVALID("sig_invalid"),

    // Other
    CANCELLED("cancelled"),
    TIMEOUT("timeout"),
    UNKNOWN("unknown")
}
