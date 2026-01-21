/*
 * Telemetry event data class for Rangzen.
 */
package org.denovogroup.rangzen.backend.telemetry

import com.google.gson.annotations.SerializedName

/**
 * A telemetry event to be sent to the telemetry server.
 * All fields are anonymized - no PII.
 */
data class TelemetryEvent(
    @SerializedName("event_type")
    val eventType: String,

    @SerializedName("ts")
    val timestamp: Long = System.currentTimeMillis(),

    @SerializedName("device_id_hash")
    val deviceIdHash: String,

    @SerializedName("peer_id_hash")
    val peerIdHash: String? = null,

    @SerializedName("transport")
    val transport: String? = null,

    @SerializedName("payload")
    val payload: Map<String, Any>? = null
) {
    companion object {
        // Event types for exchange lifecycle
        const val TYPE_EXCHANGE_START = "exchange_start"
        const val TYPE_EXCHANGE_SUCCESS = "exchange_success"
        const val TYPE_EXCHANGE_FAILURE = "exchange_failure"

        // Event types for peer discovery
        const val TYPE_PEER_DISCOVERED = "peer_discovered"
        const val TYPE_PEER_CONNECTED = "peer_connected"
        const val TYPE_PEER_DISCONNECTED = "peer_disconnected"

        // Event types for transport stats
        const val TYPE_TRANSPORT_BLE = "transport_ble"
        const val TYPE_TRANSPORT_WIFI = "transport_wifi"

        // Transport identifiers
        const val TRANSPORT_BLE = "ble"
        const val TRANSPORT_WIFI = "wifi"
    }
}

/**
 * Batch of events to send to the server.
 */
data class TelemetryBatch(
    val events: List<TelemetryEvent>
)

/**
 * Response from the telemetry server.
 */
data class TelemetryResponse(
    val accepted: Int
)
