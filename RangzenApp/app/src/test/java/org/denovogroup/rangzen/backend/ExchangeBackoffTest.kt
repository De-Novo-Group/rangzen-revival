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

/**
 * Tests for the exponential backoff logic used in peer exchange.
 *
 * These tests call the production BackoffMath directly, ensuring test fidelity
 * with the actual app behavior. Config values from config.json:
 * - backoffAttemptMillis: 10000 (10 seconds)
 * - backoffMaxMillis: 320000 (320 seconds / ~5.3 minutes)
 */
class ExchangeBackoffTest {

    companion object {
        // Config values from config.json - must match actual config!
        private const val CONFIG_BASE_DELAY_MS = 10_000L  // 10 seconds
        private const val CONFIG_MAX_DELAY_MS = 320_000L  // 320 seconds
    }

    // Use production code directly
    private fun computeBackoffDelay(
        attempts: Int,
        baseDelay: Long = CONFIG_BASE_DELAY_MS,
        maxDelay: Long = CONFIG_MAX_DELAY_MS
    ): Long {
        return BackoffMath.computeBackoffDelay(attempts, baseDelay, maxDelay)
    }

    // ========================================================================
    // Basic backoff calculation tests (using config: 10s base, 320s max)
    // ========================================================================

    @Test
    fun `zero attempts results in base delay`() {
        val delay = computeBackoffDelay(0)
        // 2^0 * 10000 = 10000ms (10 seconds)
        assertEquals(10_000L, delay)
    }

    @Test
    fun `one attempt doubles delay`() {
        val delay = computeBackoffDelay(1)
        // 2^1 * 10000 = 20000ms (20 seconds)
        assertEquals(20_000L, delay)
    }

    @Test
    fun `two attempts quadruples delay`() {
        val delay = computeBackoffDelay(2)
        // 2^2 * 10000 = 40000ms (40 seconds)
        assertEquals(40_000L, delay)
    }

    @Test
    fun `three attempts octuples delay`() {
        val delay = computeBackoffDelay(3)
        // 2^3 * 10000 = 80000ms (80 seconds)
        assertEquals(80_000L, delay)
    }

    // ========================================================================
    // Max delay capping tests (config max: 320 seconds)
    // ========================================================================

    @Test
    fun `delay is capped at max`() {
        // 2^10 * 10000 = 10240000, but max is 320000
        val delay = computeBackoffDelay(10)
        assertEquals(CONFIG_MAX_DELAY_MS, delay)
    }

    @Test
    fun `delay reaches max at six attempts`() {
        // 2^6 * 10000 = 640000, which exceeds max of 320000
        val delay = computeBackoffDelay(6)
        assertEquals(CONFIG_MAX_DELAY_MS, delay)
    }

    @Test
    fun `five attempts is under max`() {
        // 2^5 * 10000 = 320000, exactly at max
        val delay = computeBackoffDelay(5)
        assertEquals(320_000L, delay)
    }
    
    @Test
    fun `four attempts is under max`() {
        // 2^4 * 10000 = 160000 (160 seconds), under max of 320000
        val delay = computeBackoffDelay(4)
        assertEquals(160_000L, delay)
    }

    // ========================================================================
    // Exponential growth verification
    // ========================================================================

    @Test
    fun `backoff grows exponentially`() {
        var previous = 0L
        for (attempts in 0..4) {  // Up to 4 to stay under max
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
        for (attempts in 1..4) {  // Up to 4 to stay under max
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
    // Custom config tests (verifies BackoffMath works with any values)
    // ========================================================================

    @Test
    fun `custom base delay changes scale`() {
        val delay = BackoffMath.computeBackoffDelay(2, 500L, 60_000L)
        // 2^2 * 500 = 2000ms
        assertEquals(2000L, delay)
    }

    @Test
    fun `custom max delay caps correctly`() {
        val delay = BackoffMath.computeBackoffDelay(10, 1000L, 10_000L)
        assertEquals(10_000L, delay)
    }

    @Test
    fun `very high attempt count stays at max`() {
        val delay = computeBackoffDelay(100)
        assertEquals(CONFIG_MAX_DELAY_MS, delay)
    }

    // ========================================================================
    // Edge cases
    // ========================================================================

    @Test
    fun `negative attempts returns base delay`() {
        // BackoffMath handles negative attempts by returning base delay
        val delay = computeBackoffDelay(-1)
        assertEquals(CONFIG_BASE_DELAY_MS, delay)
    }

    @Test
    fun `base delay of zero results in zero delay`() {
        val delay = BackoffMath.computeBackoffDelay(5, 0L, CONFIG_MAX_DELAY_MS)
        assertEquals(0L, delay)
    }

    // ========================================================================
    // isReadyForAttempt tests
    // ========================================================================

    @Test
    fun `isReadyForAttempt returns true when enough time passed`() {
        val lastExchange = System.currentTimeMillis() - 15_000L  // 15 seconds ago
        val isReady = BackoffMath.isReadyForAttempt(
            lastExchange, 0, CONFIG_BASE_DELAY_MS, CONFIG_MAX_DELAY_MS
        )
        // First attempt needs 10s backoff, 15s has passed
        assertTrue("Should be ready after 15s for first attempt (10s backoff)", isReady)
    }

    @Test
    fun `isReadyForAttempt returns false when not enough time passed`() {
        val lastExchange = System.currentTimeMillis() - 5_000L  // 5 seconds ago
        val isReady = BackoffMath.isReadyForAttempt(
            lastExchange, 0, CONFIG_BASE_DELAY_MS, CONFIG_MAX_DELAY_MS
        )
        // First attempt needs 10s backoff, only 5s has passed
        assertTrue("Should not be ready after 5s for first attempt (10s backoff)", !isReady)
    }
}
