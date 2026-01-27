/*
 * Telemetry event data class for Rangzen.
 */
package org.denovogroup.rangzen.backend.telemetry

import com.google.gson.annotations.SerializedName
import org.denovogroup.rangzen.BuildConfig

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

    @SerializedName("display_name")
    val displayName: String? = null,

    @SerializedName("peer_id_hash")
    val peerIdHash: String? = null,

    @SerializedName("transport")
    val transport: String? = null,

    @SerializedName("payload")
    val payload: Map<String, Any>? = null,

    @SerializedName("app_version")
    val appVersion: String = BuildConfig.VERSION_NAME
) {
    companion object {
        // Event types for exchange lifecycle
        const val TYPE_EXCHANGE_START = "exchange_start"
        const val TYPE_EXCHANGE_SUCCESS = "exchange_success"
        const val TYPE_EXCHANGE_FAILURE = "exchange_failure"

        // Event types for message propagation
        const val TYPE_MESSAGE_SENT = "message_sent"
        const val TYPE_MESSAGE_RECEIVED = "message_received"
        const val TYPE_MESSAGE_COMPOSED = "message_composed"

        // Event types for peer discovery
        const val TYPE_PEER_DISCOVERED = "peer_discovered"
        const val TYPE_PEER_CONNECTED = "peer_connected"
        const val TYPE_PEER_DISCONNECTED = "peer_disconnected"

        // Event types for transport stats
        const val TYPE_TRANSPORT_BLE = "transport_ble"
        const val TYPE_TRANSPORT_WIFI = "transport_wifi"

        // OTA update event types
        const val TYPE_OTA_CHECK = "ota_check"
        const val TYPE_OTA_DOWNLOAD_START = "ota_download_start"
        const val TYPE_OTA_DOWNLOAD_COMPLETE = "ota_download_complete"
        const val TYPE_OTA_DOWNLOAD_FAILED = "ota_download_failed"
        const val TYPE_OTA_INSTALL_START = "ota_install_start"
        const val TYPE_OTA_INSTALL_SUCCESS = "ota_install_success"
        const val TYPE_OTA_INSTALL_FAILED = "ota_install_failed"

        // App lifecycle event types
        const val TYPE_APP_START = "app_start"
        const val TYPE_APP_STOP = "app_stop"
        const val TYPE_APP_FOREGROUND = "app_foreground"
        const val TYPE_APP_BACKGROUND = "app_background"

        // Network state event types
        const val TYPE_PEER_SNAPSHOT = "peer_snapshot"
        const val TYPE_ERROR_BATCH = "error_batch"

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

/**
 * Bug report to submit to the server.
 */
data class BugReport(
    @SerializedName("device_id_hash")
    val deviceIdHash: String,

    @SerializedName("category")
    val category: String,

    @SerializedName("description")
    val description: String,

    @SerializedName("app_version")
    val appVersion: String? = null,

    @SerializedName("os_version")
    val osVersion: String? = null,

    @SerializedName("device_model")
    val deviceModel: String? = null,

    @SerializedName("transport_state")
    val transportState: String? = null,

    @SerializedName("last_exchange_id")
    val lastExchangeId: String? = null,

    @SerializedName("battery_level")
    val batteryLevel: Int? = null,

    @SerializedName("is_power_save")
    val isPowerSave: Boolean? = null,

    @SerializedName("display_name")
    val displayName: String? = null,

    @SerializedName("latitude")
    val latitude: Double? = null,

    @SerializedName("longitude")
    val longitude: Double? = null,

    @SerializedName("location_accuracy")
    val locationAccuracy: Float? = null,

    @SerializedName("recent_events")
    val recentEvents: List<TelemetryEvent>? = null
)

/**
 * Response after submitting a bug report.
 */
data class BugReportResponse(
    @SerializedName("id")
    val id: String,

    @SerializedName("status")
    val status: String
)

/**
 * A broadcast message from the server.
 */
data class Broadcast(
    @SerializedName("id")
    val id: String,

    @SerializedName("title")
    val title: String,

    @SerializedName("body")
    val body: String,

    @SerializedName("highlight_color")
    val highlightColor: String? = null,

    @SerializedName("created_at")
    val createdAt: String,

    @SerializedName("expires_at")
    val expiresAt: String? = null
)

/**
 * A device-specific message from the server (replies to bug reports).
 */
data class DeviceMessage(
    @SerializedName("id")
    val id: String,

    @SerializedName("message")
    val message: String,

    @SerializedName("report_id")
    val reportId: String? = null,

    @SerializedName("created_at")
    val createdAt: String
)

/**
 * Response from the sync endpoint.
 */
data class SyncResponse(
    @SerializedName("broadcasts")
    val broadcasts: List<Broadcast>? = null,

    @SerializedName("messages")
    val messages: List<DeviceMessage>? = null
)

/**
 * Response from GET /v1/bug-reports/{id} - full report with conversation.
 */
data class BugReportDetailResponse(
    @SerializedName("report")
    val report: BugReportDetail,

    @SerializedName("replies")
    val replies: List<BugReportReply>
)

/**
 * Bug report detail returned from server.
 */
data class BugReportDetail(
    @SerializedName("id")
    val id: String,

    @SerializedName("category")
    val category: String,

    @SerializedName("description")
    val description: String,

    @SerializedName("status")
    val status: String,

    @SerializedName("created_at")
    val createdAt: String,

    @SerializedName("updated_at")
    val updatedAt: String? = null
)

/**
 * A reply in a bug report conversation.
 */
data class BugReportReply(
    @SerializedName("id")
    val id: String,

    @SerializedName("message")
    val message: String,

    @SerializedName("from_dashboard")
    val fromDashboard: Boolean,

    @SerializedName("created_at")
    val createdAt: String
)

/**
 * Response from POST /v1/bug-reports/{id}/reply.
 */
data class ReplyResponse(
    @SerializedName("id")
    val id: String,

    @SerializedName("status")
    val status: String
)
