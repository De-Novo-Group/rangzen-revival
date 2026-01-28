package org.denovogroup.rangzen.backend.telemetry

import android.content.Context
import java.util.UUID

/**
 * Tracks state throughout an exchange for rich telemetry.
 * Create at exchange start, update as you progress, then call buildSuccessPayload() or buildFailurePayload().
 */
class ExchangeContext(
    val transport: String,
    val peerIdHash: String,
    private val appContext: Context
) {
    /** Unique ID for this exchange */
    val exchangeId: String = UUID.randomUUID().toString()

    var currentStage: ExchangeStage = ExchangeStage.DISCOVERY
        private set

    val startTime: Long = System.currentTimeMillis()

    // Phase timing - recorded when stages are reached
    private var connectTime: Long? = null      // When CONNECTED reached
    private var transferStartTime: Long? = null // When SENDING_MESSAGES reached

    // Signal quality
    var rssi: Int? = null
    var mtu: Int? = null

    // Message stats
    var messagesSent: Int = 0
    var messagesReceived: Int = 0
    var bytesSent: Long = 0
    var bytesReceived: Long = 0

    // Trust computation
    var trustScore: Double? = null
    var mutualFriends: Int = 0

    // Retry tracking
    var retryCount: Int = 0

    // GATT diagnostics (populated after BLE exchange attempts)
    var gattDiagnostics: Map<String, Any>? = null

    // Identity contract: peer's device_id_hash for consistent cross-transport identity
    var peerDeviceIdHash: String? = null

    // Shared exchange_id: when the peer provides one, adopt it for pairing.
    // The initiator's exchangeId is authoritative; the responder adopts it.
    var sharedExchangeId: String? = null

    // Location (if permission granted)
    var location: LocationHelper.LocationData? = null

    // Local friend count at time of exchange
    var localFriendCount: Int = 0

    /**
     * Advance to the next stage and record timing for key phases.
     */
    fun advanceStage(stage: ExchangeStage) {
        currentStage = stage
        val now = System.currentTimeMillis()

        // Record timing for key phases
        when (stage) {
            ExchangeStage.CONNECTED -> connectTime = now
            ExchangeStage.SENDING_MESSAGES -> transferStartTime = now
            else -> { /* No timing for other stages */ }
        }
    }

    /**
     * Get duration in milliseconds since exchange started.
     */
    fun getDurationMs(): Long = System.currentTimeMillis() - startTime

    /**
     * Build the payload for a successful exchange.
     */
    fun buildSuccessPayload(): Map<String, Any> {
        val endTime = System.currentTimeMillis()
        // Use the shared exchange_id if the peer provided one (responder adopts initiator's ID)
        val effectiveExchangeId = sharedExchangeId ?: exchangeId
        val payload = mutableMapOf<String, Any>(
            "exchange_id" to effectiveExchangeId,
            "duration_total_ms" to (endTime - startTime),
            "final_stage" to currentStage.code,
            "messages_sent" to messagesSent,
            "messages_received" to messagesReceived,
            "mutual_friends" to mutualFriends
        )
        // Identity contract: include peer's device_id_hash for consistent identity
        peerDeviceIdHash?.let { payload["peer_device_id_hash"] = it }

        // Add phase timing breakdown if available
        connectTime?.let { ct ->
            payload["duration_discovery_ms"] = ct - startTime
            transferStartTime?.let { tst ->
                payload["duration_connect_ms"] = tst - ct
                payload["duration_transfer_ms"] = endTime - tst
            }
        }

        // Add bytes if tracked
        if (bytesSent > 0) payload["bytes_sent"] = bytesSent
        if (bytesReceived > 0) payload["bytes_received"] = bytesReceived

        // Add trust score if computed
        trustScore?.let { payload["trust_score"] = it }

        // Add local friend count
        if (localFriendCount > 0) payload["local_friend_count"] = localFriendCount

        // Add location if available (only when permission granted)
        location?.let {
            payload["location"] = it.toTelemetryMap()
            // Location age: how stale the GPS fix is
            payload["location_age_ms"] = it.ageMs
        }

        // Add signal quality
        val signalQuality = buildSignalQualityMap()
        if (signalQuality.isNotEmpty()) {
            payload["signal_quality"] = signalQuality
        }

        // Add GATT diagnostics if available
        gattDiagnostics?.let { payload["gatt_diagnostics"] = it }

        // Add user context
        payload["user_context"] = UserContextHelper.getContext(appContext)

        // Add device state
        payload["device_state"] = DeviceStateHelper.capture(appContext)

        return payload
    }

    /**
     * Build the payload for a failed exchange.
     */
    fun buildFailurePayload(error: Throwable): Map<String, Any> {
        val category = ErrorClassifier.categorize(error)
        return buildFailurePayload(category, error.message ?: "Unknown error")
    }

    /**
     * Build the payload for a failed exchange with explicit category.
     */
    fun buildFailurePayload(category: ErrorCategory, errorMessage: String): Map<String, Any> {
        val endTime = System.currentTimeMillis()
        val effectiveExchangeId = sharedExchangeId ?: exchangeId
        val payload = mutableMapOf<String, Any>(
            "exchange_id" to effectiveExchangeId,
            "duration_total_ms" to (endTime - startTime),
            "failed_stage" to currentStage.code,
            "error_category" to category.code,
            "error_message" to errorMessage.take(500)
        )
        peerDeviceIdHash?.let { payload["peer_device_id_hash"] = it }

        // Add phase timing breakdown if available
        connectTime?.let { ct ->
            payload["duration_discovery_ms"] = ct - startTime
            transferStartTime?.let { tst ->
                payload["duration_connect_ms"] = tst - ct
                payload["duration_transfer_ms"] = endTime - tst
            }
        }

        // Add retry count if retried
        if (retryCount > 0) payload["retry_count"] = retryCount

        // Add GATT diagnostics if available
        gattDiagnostics?.let { payload["gatt_diagnostics"] = it }

        // Add signal quality
        val signalQuality = buildSignalQualityMap()
        if (signalQuality.isNotEmpty()) {
            payload["signal_quality"] = signalQuality
        }

        // Add user context
        payload["user_context"] = UserContextHelper.getContext(appContext)

        // Add device state
        payload["device_state"] = DeviceStateHelper.capture(appContext)

        return payload
    }

    /**
     * Build signal quality map if we have any signal data.
     */
    private fun buildSignalQualityMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        rssi?.let { map["rssi"] = it }
        mtu?.let { map["mtu"] = it }
        return map
    }

    companion object {
        /**
         * Create an ExchangeContext for a new exchange.
         */
        fun create(transport: String, peerIdHash: String, context: Context): ExchangeContext {
            return ExchangeContext(transport, peerIdHash, context.applicationContext)
        }
    }
}
