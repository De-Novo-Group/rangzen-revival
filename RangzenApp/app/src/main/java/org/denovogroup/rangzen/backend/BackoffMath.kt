/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * Centralized backoff computation for exchange timing.
 */
package org.denovogroup.rangzen.backend

import android.content.Context
import kotlin.math.min
import kotlin.math.pow

/**
 * Centralized exponential backoff math for peer exchange timing.
 *
 * The backoff mechanism prevents excessive retries with unresponsive peers
 * while still allowing eventual retry attempts.
 */
object BackoffMath {

    /**
     * Compute backoff delay for a given attempt count.
     *
     * Uses exponential backoff: delay = min(2^attempts * baseDelay, maxDelay)
     *
     * @param attempts Number of failed attempts (0 = first attempt)
     * @param baseDelayMs Base delay in milliseconds
     * @param maxDelayMs Maximum delay in milliseconds (cap)
     * @return Delay in milliseconds before next attempt should be made
     */
    fun computeBackoffDelay(attempts: Int, baseDelayMs: Long, maxDelayMs: Long): Long {
        // Handle edge cases
        if (attempts < 0) return baseDelayMs
        if (baseDelayMs <= 0) return 0L
        
        val exponentialDelay = (2.0.pow(attempts.toDouble()) * baseDelayMs).toLong()
        return min(exponentialDelay, maxDelayMs)
    }

    /**
     * Compute backoff delay using config values from AppConfig.
     *
     * @param context Application context for reading config
     * @param attempts Number of failed attempts
     * @return Delay in milliseconds
     */
    fun computeBackoffDelay(context: Context, attempts: Int): Long {
        val baseDelay = AppConfig.backoffAttemptMillis(context)
        val maxDelay = AppConfig.backoffMaxMillis(context)
        return computeBackoffDelay(attempts, baseDelay, maxDelay)
    }

    /**
     * Check if enough time has passed since last exchange for given backoff.
     *
     * @param lastExchangeTimeMs Timestamp of last exchange in milliseconds
     * @param attempts Number of failed attempts
     * @param baseDelayMs Base delay in milliseconds
     * @param maxDelayMs Maximum delay in milliseconds
     * @return True if ready for next attempt
     */
    fun isReadyForAttempt(
        lastExchangeTimeMs: Long,
        attempts: Int,
        baseDelayMs: Long,
        maxDelayMs: Long
    ): Boolean {
        val backoffDelay = computeBackoffDelay(attempts, baseDelayMs, maxDelayMs)
        val readyAt = lastExchangeTimeMs + backoffDelay
        return System.currentTimeMillis() >= readyAt
    }
}
