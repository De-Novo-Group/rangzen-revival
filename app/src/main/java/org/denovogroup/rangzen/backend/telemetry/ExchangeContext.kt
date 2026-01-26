package org.denovogroup.rangzen.backend.telemetry

import android.content.Context

/**
 * Tracks state throughout an exchange for rich telemetry.
 * Create at exchange start, update as you progress, then call buildSuccessPayload() or buildFailurePayload().
 */
class ExchangeContext(
    val transport: String,
    val peerIdHash: String,
    private val appContext: Context
) {
    var currentStage: ExchangeStage = ExchangeStage.DISCOVERY
        private set

    val startTime: Long = System.currentTimeMillis()

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

    /**
     * Advance to the next stage.
     */
    fun advanceStage(stage: ExchangeStage) {
        currentStage = stage
    }

    /**
     * Get duration in milliseconds since exchange started.
     */
    fun getDurationMs(): Long = System.currentTimeMillis() - startTime

    /**
     * Build the payload for a successful exchange.
     */
    fun buildSuccessPayload(): Map<String, Any> {
        val payload = mutableMapOf<String, Any>(
            "duration_ms" to getDurationMs(),
            "final_stage" to currentStage.code,
            "messages_sent" to messagesSent,
            "messages_received" to messagesReceived,
            "mutual_friends" to mutualFriends
        )

        // Add bytes if tracked
        if (bytesSent > 0) payload["bytes_sent"] = bytesSent
        if (bytesReceived > 0) payload["bytes_received"] = bytesReceived

        // Add trust score if computed
        trustScore?.let { payload["trust_score"] = it }

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
        val payload = mutableMapOf<String, Any>(
            "duration_ms" to getDurationMs(),
            "failed_stage" to currentStage.code,
            "error_category" to category.code,
            "error_message" to errorMessage.take(500) // Allow longer messages than before
        )

        // Add retry count if retried
        if (retryCount > 0) payload["retry_count"] = retryCount

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
