/*
 * Telemetry client for Rangzen.
 * Handles batching, retries with exponential backoff, and async sending.
 */
package org.denovogroup.rangzen.backend.telemetry

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import com.google.gson.Gson
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.min
import kotlin.math.pow

/**
 * Telemetry client that batches events and sends them to the server.
 *
 * Features:
 * - Event queuing with size/time-based batching
 * - Exponential backoff on failures
 * - Opt-in control
 * - Fire-and-forget (never blocks exchanges)
 */
class TelemetryClient private constructor(
    private val context: Context,
    private val serverUrl: String,
    private val apiToken: String
) {
    companion object {
        private const val PREFS_NAME = "telemetry_prefs"
        private const val PREF_OPT_IN = "telemetry_opt_in"
        private const val PREF_DEVICE_ID_HASH = "device_id_hash"

        private const val MAX_QUEUE_SIZE = 500
        private const val BATCH_SIZE = 50
        private const val FLUSH_INTERVAL_MS = 60_000L // 1 minute
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 300_000L // 5 minutes
        private const val CONNECTION_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000

        @Volatile
        private var instance: TelemetryClient? = null

        /**
         * Initialize the telemetry client.
         * Should be called once at app startup.
         */
        fun init(context: Context, serverUrl: String, apiToken: String): TelemetryClient {
            return instance ?: synchronized(this) {
                instance ?: TelemetryClient(context.applicationContext, serverUrl, apiToken).also {
                    instance = it
                }
            }
        }

        /**
         * Get the initialized instance.
         * Returns null if not initialized.
         */
        fun getInstance(): TelemetryClient? = instance
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val eventQueue = ConcurrentLinkedQueue<TelemetryEvent>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var currentBackoffMs = INITIAL_BACKOFF_MS
    private var flushJob: Job? = null

    /** Cached device ID hash for this device */
    private val deviceIdHash: String by lazy {
        prefs.getString(PREF_DEVICE_ID_HASH, null) ?: run {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            val hash = sha256(androidId ?: "unknown")
            prefs.edit().putString(PREF_DEVICE_ID_HASH, hash).apply()
            hash
        }
    }

    init {
        startFlushTimer()
    }

    /**
     * Check if telemetry is enabled (user opted in).
     */
    fun isEnabled(): Boolean = prefs.getBoolean(PREF_OPT_IN, false)

    /**
     * Set telemetry opt-in status.
     */
    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_OPT_IN, enabled).apply()
        if (enabled) {
            Timber.i("Telemetry enabled")
            startFlushTimer()
        } else {
            Timber.i("Telemetry disabled")
            flushJob?.cancel()
            eventQueue.clear()
        }
    }

    /**
     * Enqueue a telemetry event.
     * This is fire-and-forget - never blocks.
     */
    fun track(eventType: String, peerIdHash: String? = null, transport: String? = null, payload: Map<String, Any>? = null) {
        if (!isEnabled()) return

        val event = TelemetryEvent(
            eventType = eventType,
            deviceIdHash = deviceIdHash,
            peerIdHash = peerIdHash,
            transport = transport,
            payload = payload
        )

        // Drop oldest events if queue is full
        while (eventQueue.size >= MAX_QUEUE_SIZE) {
            eventQueue.poll()
        }

        eventQueue.offer(event)

        // Flush immediately if we have a full batch
        if (eventQueue.size >= BATCH_SIZE) {
            flush()
        }
    }

    /**
     * Track an exchange start event.
     */
    fun trackExchangeStart(peerIdHash: String, transport: String) {
        track(TelemetryEvent.TYPE_EXCHANGE_START, peerIdHash, transport)
    }

    /**
     * Track an exchange success event.
     */
    fun trackExchangeSuccess(
        peerIdHash: String,
        transport: String,
        durationMs: Long,
        messagesSent: Int,
        messagesReceived: Int,
        mutualFriends: Int
    ) {
        track(
            TelemetryEvent.TYPE_EXCHANGE_SUCCESS,
            peerIdHash,
            transport,
            mapOf(
                "duration_ms" to durationMs,
                "messages_sent" to messagesSent,
                "messages_received" to messagesReceived,
                "mutual_friends" to mutualFriends
            )
        )
    }

    /**
     * Track an exchange failure event.
     */
    fun trackExchangeFailure(peerIdHash: String, transport: String, error: String, durationMs: Long) {
        track(
            TelemetryEvent.TYPE_EXCHANGE_FAILURE,
            peerIdHash,
            transport,
            mapOf(
                "error" to error.take(200), // Truncate long errors
                "duration_ms" to durationMs
            )
        )
    }

    /**
     * Track a peer discovery event.
     */
    fun trackPeerDiscovered(peerIdHash: String, transport: String, rssi: Int? = null) {
        val payload = mutableMapOf<String, Any>()
        rssi?.let { payload["rssi"] = it }
        track(TelemetryEvent.TYPE_PEER_DISCOVERED, peerIdHash, transport, payload.ifEmpty { null })
    }

    /**
     * Track a message sent during exchange.
     * Tracks message propagation details without PII.
     *
     * @param peerIdHash Hashed peer identifier
     * @param transport Transport type (BLE, WiFi, etc.)
     * @param messageIdHash Hashed message ID (SHA-256 of messageId)
     * @param hopCount Number of hops this message has traveled
     * @param trustScore Trust score of the message (0.0-1.0)
     * @param priority Message priority
     * @param ageMs Age of message in milliseconds
     */
    fun trackMessageSent(
        peerIdHash: String,
        transport: String,
        messageIdHash: String,
        hopCount: Int,
        trustScore: Double,
        priority: Int,
        ageMs: Long
    ) {
        track(
            TelemetryEvent.TYPE_MESSAGE_SENT,
            peerIdHash,
            transport,
            mapOf(
                "message_id_hash" to messageIdHash,
                "hop_count" to hopCount,
                "trust_score" to trustScore,
                "priority" to priority,
                "age_ms" to ageMs
            )
        )
    }

    /**
     * Track a message received during exchange.
     *
     * @param peerIdHash Hashed peer identifier
     * @param transport Transport type
     * @param messageIdHash Hashed message ID
     * @param hopCount Number of hops
     * @param trustScore Trust score
     * @param priority Message priority
     * @param isNew True if this is a new message, false if already known
     */
    fun trackMessageReceived(
        peerIdHash: String,
        transport: String,
        messageIdHash: String,
        hopCount: Int,
        trustScore: Double,
        priority: Int,
        isNew: Boolean
    ) {
        track(
            TelemetryEvent.TYPE_MESSAGE_RECEIVED,
            peerIdHash,
            transport,
            mapOf(
                "message_id_hash" to messageIdHash,
                "hop_count" to hopCount,
                "trust_score" to trustScore,
                "priority" to priority,
                "is_new" to isNew
            )
        )
    }

    /**
     * Track a locally composed message.
     *
     * @param messageIdHash Hashed message ID
     * @param textLength Length of message text
     */
    fun trackMessageComposed(messageIdHash: String, textLength: Int) {
        track(
            TelemetryEvent.TYPE_MESSAGE_COMPOSED,
            null,
            null,
            mapOf(
                "message_id_hash" to messageIdHash,
                "text_length" to textLength
            )
        )
    }

    /**
     * Trigger an immediate flush of queued events.
     */
    fun flush() {
        if (!isEnabled() || eventQueue.isEmpty()) return

        scope.launch {
            doFlush()
        }
    }

    /**
     * Start the periodic flush timer.
     */
    private fun startFlushTimer() {
        flushJob?.cancel()
        flushJob = scope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                if (isEnabled() && eventQueue.isNotEmpty()) {
                    doFlush()
                }
            }
        }
    }

    /**
     * Actually send events to the server.
     */
    private suspend fun doFlush() {
        if (eventQueue.isEmpty()) return

        // Collect a batch of events
        val batch = mutableListOf<TelemetryEvent>()
        while (batch.size < BATCH_SIZE && eventQueue.isNotEmpty()) {
            eventQueue.poll()?.let { batch.add(it) }
        }

        if (batch.isEmpty()) return

        try {
            sendBatch(batch)
            // Reset backoff on success
            currentBackoffMs = INITIAL_BACKOFF_MS
            Timber.d("Sent ${batch.size} telemetry events")
        } catch (e: Exception) {
            Timber.w("Failed to send telemetry: ${e.message}")
            // Re-queue events on failure (at front)
            batch.reversed().forEach { eventQueue.offer(it) }
            // Apply backoff
            delay(currentBackoffMs)
            currentBackoffMs = min(currentBackoffMs * 2, MAX_BACKOFF_MS)
        }
    }

    /**
     * Send a batch of events to the server.
     */
    private suspend fun sendBatch(events: List<TelemetryEvent>) = withContext(Dispatchers.IO) {
        val url = URL("$serverUrl/v1/events")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiToken")
            connection.connectTimeout = CONNECTION_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true

            val body = gson.toJson(TelemetryBatch(events))

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(body)
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw Exception("Server returned $responseCode")
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Compute SHA-256 hash of a string.
     */
    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Shutdown the telemetry client.
     */
    fun shutdown() {
        scope.cancel()
    }
}
