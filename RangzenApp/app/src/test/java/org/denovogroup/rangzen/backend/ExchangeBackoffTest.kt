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
 * These tests call the production BackoffMath directly with values from
 * TestConfig, which reads from config.json. This ensures tests stay coupled
 * to production configuration and don't silently break when config changes.
 */
class ExchangeBackoffTest {

    // Read config values from TestConfig (which reads config.json)
    private val baseDelayMs: Long get() = TestConfig.backoffAttemptMillis
    private val maxDelayMs: Long get() = TestConfig.backoffMaxMillis

    // Use production code with config values
    private fun computeBackoffDelay(
        attempts: Int,
        baseDelay: Long = baseDelayMs,
        maxDelay: Long = maxDelayMs
    ): Long {
        return BackoffMath.computeBackoffDelay(attempts, baseDelay, maxDelay)
    }
    
    // ========================================================================
    // Config sanity check
    // ========================================================================
    
    @Test
    fun `config values match expected defaults`() {
        // Verify TestConfig reads expected values from config.json
        // If this fails, config.json may have changed
        assertEquals("Base delay should be 10 seconds", 10_000L, baseDelayMs)
        assertEquals("Max delay should be 320 seconds", 320_000L, maxDelayMs)
    }

    // ========================================================================
    // Basic backoff calculation tests (using values from TestConfig)
    // ========================================================================

    @Test
    fun `zero attempts results in base delay`() {
        val delay = computeBackoffDelay(0)
        // 2^0 * baseDelay = baseDelay
        assertEquals(baseDelayMs, delay)
    }

    @Test
    fun `one attempt doubles delay`() {
        val delay = computeBackoffDelay(1)
        // 2^1 * baseDelay = 2 * baseDelay
        assertEquals(baseDelayMs * 2, delay)
    }

    @Test
    fun `two attempts quadruples delay`() {
        val delay = computeBackoffDelay(2)
        // 2^2 * baseDelay = 4 * baseDelay
        assertEquals(baseDelayMs * 4, delay)
    }

    @Test
    fun `three attempts octuples delay`() {
        val delay = computeBackoffDelay(3)
        // 2^3 * baseDelay = 8 * baseDelay
        assertEquals(baseDelayMs * 8, delay)
    }

    // ========================================================================
    // Max delay capping tests (using maxDelayMs from TestConfig)
    // ========================================================================

    @Test
    fun `delay is capped at max`() {
        // Very high attempt count should cap at max
        val delay = computeBackoffDelay(10)
        assertEquals(maxDelayMs, delay)
    }

    @Test
    fun `delay reaches max at six attempts`() {
        // 2^6 * baseDelay typically exceeds max
        val delay = computeBackoffDelay(6)
        assertEquals(maxDelayMs, delay)
    }

    @Test
    fun `five attempts equals max`() {
        // 2^5 * 10000 = 320000, exactly at current max
        val delay = computeBackoffDelay(5)
        // This should equal max since 32 * 10000 = 320000 = maxDelayMs
        assertEquals(baseDelayMs * 32, delay)
    }
    
    @Test
    fun `four attempts is under max`() {
        // 2^4 * baseDelay = 16 * baseDelay
        val delay = computeBackoffDelay(4)
        assertEquals(baseDelayMs * 16, delay)
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
        assertEquals(maxDelayMs, delay)
    }

    // ========================================================================
    // Edge cases
    // ========================================================================

    @Test
    fun `negative attempts returns base delay`() {
        // BackoffMath handles negative attempts by returning base delay
        val delay = computeBackoffDelay(-1)
        assertEquals(baseDelayMs, delay)
    }

    @Test
    fun `base delay of zero results in zero delay`() {
        val delay = BackoffMath.computeBackoffDelay(5, 0L, maxDelayMs)
        assertEquals(0L, delay)
    }

    // ========================================================================
    // isReadyForAttempt tests (using config values)
    // ========================================================================

    @Test
    fun `isReadyForAttempt returns true when enough time passed`() {
        // Wait longer than base delay
        val lastExchange = System.currentTimeMillis() - (baseDelayMs + 5_000L)
        val isReady = BackoffMath.isReadyForAttempt(
            lastExchange, 0, baseDelayMs, maxDelayMs
        )
        assertTrue("Should be ready after base delay + 5s", isReady)
    }

    @Test
    fun `isReadyForAttempt returns false when not enough time passed`() {
        // Wait less than base delay
        val lastExchange = System.currentTimeMillis() - (baseDelayMs / 2)
        val isReady = BackoffMath.isReadyForAttempt(
            lastExchange, 0, baseDelayMs, maxDelayMs
        )
        assertTrue("Should not be ready after only half of base delay", !isReady)
    }
}
