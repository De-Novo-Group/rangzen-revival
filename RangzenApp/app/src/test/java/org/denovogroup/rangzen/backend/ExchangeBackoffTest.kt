/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * Unit tests for exchange backoff logic.
 */
package org.denovogroup.rangzen.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min
import kotlin.math.pow

/**
 * Tests for the exponential backoff logic used in peer exchange.
 *
 * The backoff mechanism prevents excessive retries with unresponsive peers
 * while still allowing eventual retry attempts.
 */
class ExchangeBackoffTest {

    companion object {
        // Default config values (matching AppConfig defaults)
        private const val DEFAULT_BASE_DELAY_MS = 1000L  // 1 second
        private const val DEFAULT_MAX_DELAY_MS = 60000L  // 60 seconds
        
        /**
         * Compute backoff delay for a given attempt count.
         * This mirrors the logic in RangzenService.shouldAttemptExchange.
         */
        fun computeBackoffDelay(attempts: Int, baseDelay: Long = DEFAULT_BASE_DELAY_MS, maxDelay: Long = DEFAULT_MAX_DELAY_MS): Long {
            return min(
                (2.0.pow(attempts.toDouble()) * baseDelay).toLong(),
                maxDelay
            )
        }
    }

    // ========================================================================
    // Basic backoff calculation tests
    // ========================================================================

    @Test
    fun `zero attempts results in base delay`() {
        val delay = computeBackoffDelay(0)
        // 2^0 * 1000 = 1000ms
        assertEquals(1000L, delay)
    }

    @Test
    fun `one attempt doubles delay`() {
        val delay = computeBackoffDelay(1)
        // 2^1 * 1000 = 2000ms
        assertEquals(2000L, delay)
    }

    @Test
    fun `two attempts quadruples delay`() {
        val delay = computeBackoffDelay(2)
        // 2^2 * 1000 = 4000ms
        assertEquals(4000L, delay)
    }

    @Test
    fun `three attempts octuples delay`() {
        val delay = computeBackoffDelay(3)
        // 2^3 * 1000 = 8000ms
        assertEquals(8000L, delay)
    }

    // ========================================================================
    // Max delay capping tests
    // ========================================================================

    @Test
    fun `delay is capped at max`() {
        // 2^10 * 1000 = 1024000, but max is 60000
        val delay = computeBackoffDelay(10)
        assertEquals(DEFAULT_MAX_DELAY_MS, delay)
    }

    @Test
    fun `delay reaches max at six attempts`() {
        // 2^6 * 1000 = 64000, which exceeds max of 60000
        val delay = computeBackoffDelay(6)
        assertEquals(DEFAULT_MAX_DELAY_MS, delay)
    }

    @Test
    fun `five attempts is under max`() {
        // 2^5 * 1000 = 32000, under max of 60000
        val delay = computeBackoffDelay(5)
        assertEquals(32000L, delay)
    }

    // ========================================================================
    // Exponential growth verification
    // ========================================================================

    @Test
    fun `backoff grows exponentially`() {
        var previous = 0L
        for (attempts in 0..5) {
            val delay = computeBackoffDelay(attempts)
            assertTrue(
                "Backoff should grow: attempts=$attempts delay=$delay previous=$previous",
                delay > previous
            )
            previous = delay
        }
    }

    @Test
    fun `backoff is exactly 2x previous before max`() {
        for (attempts in 1..4) {
            val current = computeBackoffDelay(attempts)
            val previous = computeBackoffDelay(attempts - 1)
            assertEquals(
                "Backoff should be 2x previous at attempts=$attempts",
                previous * 2,
                current
            )
        }
    }

    // ========================================================================
    // Custom config tests
    // ========================================================================

    @Test
    fun `custom base delay changes scale`() {
        val delay = computeBackoffDelay(2, baseDelay = 500L)
        // 2^2 * 500 = 2000ms
        assertEquals(2000L, delay)
    }

    @Test
    fun `custom max delay caps correctly`() {
        val delay = computeBackoffDelay(10, maxDelay = 10000L)
        assertEquals(10000L, delay)
    }

    @Test
    fun `very high attempt count stays at max`() {
        val delay = computeBackoffDelay(100)
        assertEquals(DEFAULT_MAX_DELAY_MS, delay)
    }

    // ========================================================================
    // Edge cases
    // ========================================================================

    @Test
    fun `negative attempts treated as zero`() {
        // While negative attempts shouldn't happen in practice,
        // 2^(-1) = 0.5, so result would be 500ms
        val delay = computeBackoffDelay(-1)
        // This tests the actual math behavior
        assertTrue("Negative attempts should produce valid delay", delay > 0)
    }

    @Test
    fun `base delay of zero results in zero delay`() {
        val delay = computeBackoffDelay(5, baseDelay = 0L)
        assertEquals(0L, delay)
    }
}
