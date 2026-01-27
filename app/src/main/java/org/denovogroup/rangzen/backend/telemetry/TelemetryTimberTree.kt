/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * Custom Timber.Tree that batches errors for telemetry.
 */
package org.denovogroup.rangzen.backend.telemetry

import android.util.Log
import kotlinx.coroutines.*
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Custom Timber.Tree that captures error and warning logs for telemetry.
 *
 * Features:
 * - Batches errors to avoid overwhelming the telemetry server
 * - Flushes on a timer or when batch is full
 * - Deduplicates similar errors within a batch window
 * - Truncates long messages to prevent huge payloads
 */
class TelemetryTimberTree : Timber.Tree() {

    companion object {
        private const val MAX_BATCH_SIZE = 20
        private const val FLUSH_INTERVAL_MS = 60_000L // 1 minute
        private const val MAX_MESSAGE_LENGTH = 500
        private const val DEDUP_WINDOW_MS = 5_000L // Deduplicate same errors within 5 seconds
    }

    private val errorQueue = ConcurrentLinkedQueue<ErrorEntry>()
    private val recentErrorHashes = mutableMapOf<Int, Long>() // hash -> timestamp
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var flushJob: Job? = null

    init {
        startFlushTimer()
    }

    override fun isLoggable(tag: String?, priority: Int): Boolean {
        // Only capture errors and warnings
        return priority >= Log.WARN
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        // Skip if telemetry is not enabled
        if (TelemetryClient.getInstance()?.isEnabled() != true) return

        // Create error entry
        val entry = ErrorEntry(
            message = message.take(MAX_MESSAGE_LENGTH),
            tag = tag,
            timestamp = System.currentTimeMillis(),
            level = priorityToLevel(priority),
            exceptionType = t?.javaClass?.simpleName,
            exceptionMessage = t?.message?.take(200)
        )

        // Deduplication: skip if we've seen this exact error recently
        val hash = entry.deduplicationHash()
        val now = System.currentTimeMillis()
        synchronized(recentErrorHashes) {
            val lastSeen = recentErrorHashes[hash]
            if (lastSeen != null && now - lastSeen < DEDUP_WINDOW_MS) {
                // Skip duplicate
                return
            }
            recentErrorHashes[hash] = now
            // Clean old entries
            recentErrorHashes.entries.removeIf { now - it.value > DEDUP_WINDOW_MS }
        }

        // Add to queue
        errorQueue.offer(entry)

        // Flush if batch is full
        if (errorQueue.size >= MAX_BATCH_SIZE) {
            flush()
        }
    }

    /**
     * Flush accumulated errors to telemetry.
     */
    fun flush() {
        scope.launch {
            doFlush()
        }
    }

    private fun startFlushTimer() {
        flushJob?.cancel()
        flushJob = scope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                doFlush()
            }
        }
    }

    private fun doFlush() {
        if (errorQueue.isEmpty()) return

        val batch = mutableListOf<ErrorEntry>()
        while (batch.size < MAX_BATCH_SIZE && errorQueue.isNotEmpty()) {
            errorQueue.poll()?.let { batch.add(it) }
        }

        if (batch.isEmpty()) return

        // Convert to telemetry payload format
        val errors = batch.map { entry ->
            mutableMapOf<String, Any>(
                "message" to entry.message,
                "timestamp" to entry.timestamp,
                "level" to entry.level
            ).apply {
                entry.tag?.let { put("tag", it) }
                entry.exceptionType?.let { put("exception_type", it) }
                entry.exceptionMessage?.let { put("exception_message", it) }
            }
        }

        TelemetryClient.getInstance()?.trackErrorBatch(errors)
    }

    private fun priorityToLevel(priority: Int): String {
        return when (priority) {
            Log.ERROR -> "error"
            Log.WARN -> "warn"
            Log.ASSERT -> "assert"
            else -> "unknown"
        }
    }

    /**
     * Shutdown the tree and flush remaining errors.
     */
    fun shutdown() {
        scope.launch {
            doFlush()
        }.invokeOnCompletion {
            scope.cancel()
        }
    }

    /**
     * Internal data class for error entries.
     */
    private data class ErrorEntry(
        val message: String,
        val tag: String?,
        val timestamp: Long,
        val level: String,
        val exceptionType: String? = null,
        val exceptionMessage: String? = null
    ) {
        /**
         * Hash for deduplication (ignores timestamp).
         */
        fun deduplicationHash(): Int {
            return (message + (tag ?: "") + level + (exceptionType ?: "")).hashCode()
        }
    }
}
