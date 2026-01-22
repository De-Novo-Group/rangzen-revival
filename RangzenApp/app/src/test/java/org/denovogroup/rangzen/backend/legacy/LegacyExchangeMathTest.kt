/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * Unit tests for LegacyExchangeMath trust computation.
 */
package org.denovogroup.rangzen.backend.legacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the trust computation math in LegacyExchangeMath.
 *
 * The trust model uses a sigmoid function on the fraction of shared friends.
 * These tests verify the mathematical properties of the trust computation.
 */
class LegacyExchangeMathTest {

    companion object {
        // Mirror the constant from LegacyExchangeMath for test assertions.
        private const val EPSILON_TRUST = 0.001
        private const val SIGMOID_CUTOFF = 0.3
        private const val SIGMOID_RATE = 13.0
        private const val DELTA = 0.0001 // Tolerance for double comparisons
    }

    // ========================================================================
    // Sigmoid function tests
    // ========================================================================

    @Test
    fun `sigmoid at cutoff returns 0_5`() {
        // At the cutoff point, sigmoid should return exactly 0.5
        // sigmoid(0.3, 0.3, 13) = 1 / (1 + e^0) = 0.5
        val result = LegacyExchangeMath.sigmoid(0.3, SIGMOID_CUTOFF, SIGMOID_RATE)
        assertEquals(0.5, result, DELTA)
    }

    @Test
    fun `sigmoid at zero is low`() {
        // sigmoid(0, 0.3, 13) = 1 / (1 + e^3.9) ≈ 0.02
        val result = LegacyExchangeMath.sigmoid(0.0, SIGMOID_CUTOFF, SIGMOID_RATE)
        assertTrue("Sigmoid at 0 should be low (< 0.05), was $result", result < 0.05)
        assertTrue("Sigmoid at 0 should be positive, was $result", result > 0.0)
    }

    @Test
    fun `sigmoid at one is high`() {
        // sigmoid(1, 0.3, 13) = 1 / (1 + e^-9.1) ≈ 0.9999
        val result = LegacyExchangeMath.sigmoid(1.0, SIGMOID_CUTOFF, SIGMOID_RATE)
        assertTrue("Sigmoid at 1 should be high (> 0.99), was $result", result > 0.99)
        assertTrue("Sigmoid at 1 should be <= 1, was $result", result <= 1.0)
    }

    @Test
    fun `sigmoid is monotonically increasing`() {
        // Verify sigmoid increases as input increases
        val values = listOf(0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0)
        var previous = -1.0
        for (input in values) {
            val result = LegacyExchangeMath.sigmoid(input, SIGMOID_CUTOFF, SIGMOID_RATE)
            assertTrue(
                "Sigmoid should be monotonically increasing: f($input)=$result should be > $previous",
                result > previous
            )
            previous = result
        }
    }

    @Test
    fun `sigmoid output is bounded between 0 and 1`() {
        // Test various inputs to ensure output is always in [0, 1]
        val testInputs = listOf(-1.0, -0.5, 0.0, 0.25, 0.3, 0.5, 0.75, 1.0, 1.5, 2.0)
        for (input in testInputs) {
            val result = LegacyExchangeMath.sigmoid(input, SIGMOID_CUTOFF, SIGMOID_RATE)
            assertTrue("Sigmoid($input) should be >= 0, was $result", result >= 0.0)
            assertTrue("Sigmoid($input) should be <= 1, was $result", result <= 1.0)
        }
    }

    @Test
    fun `sigmoid with different cutoff shifts transition point`() {
        // Changing cutoff should shift where the 0.5 point is
        val cutoff = 0.5
        val result = LegacyExchangeMath.sigmoid(cutoff, cutoff, SIGMOID_RATE)
        assertEquals(0.5, result, DELTA)
    }

    @Test
    fun `sigmoid with different rate changes steepness`() {
        // Higher rate = steeper transition
        val lowRate = LegacyExchangeMath.sigmoid(0.35, SIGMOID_CUTOFF, 5.0)
        val highRate = LegacyExchangeMath.sigmoid(0.35, SIGMOID_CUTOFF, 20.0)
        // Both should be > 0.5 (since 0.35 > cutoff), but high rate should be closer to 1
        assertTrue("Higher rate should produce steeper curve", highRate > lowRate)
    }

    // ========================================================================
    // Zero shared friends tests (EPSILON_TRUST)
    // ========================================================================

    @Test
    fun `zero shared friends results in EPSILON_TRUST`() {
        // When sharedFriends = 0, the result should be priority * EPSILON_TRUST
        val priority = 1.0
        val result = LegacyExchangeMath.computeNewPriority_sigmoidFractionOfFriends(
            priority = priority,
            sharedFriends = 0,
            myFriends = 100
        )
        assertEquals(EPSILON_TRUST, result, DELTA)
    }

    @Test
    fun `zero shared friends with different priorities`() {
        // Test that EPSILON_TRUST is applied as multiplier
        val priorities = listOf(0.0, 0.5, 1.0)
        for (priority in priorities) {
            val result = LegacyExchangeMath.computeNewPriority_sigmoidFractionOfFriends(
                priority = priority,
                sharedFriends = 0,
                myFriends = 50
            )
            assertEquals(
                "With 0 shared friends, result should be priority * EPSILON_TRUST",
                priority * EPSILON_TRUST,
                result,
                DELTA
            )
        }
    }

    @Test
    fun `zero shared friends with zero myFriends`() {
        // Edge case: both are zero
        val result = LegacyExchangeMath.computeNewPriority_sigmoidFractionOfFriends(
            priority = 1.0,
            sharedFriends = 0,
            myFriends = 0
        )
        assertEquals(EPSILON_TRUST, result, DELTA)
    }

    // ========================================================================
    // Trust multiplier clamping tests
    // ========================================================================

    @Test
    fun `trust multiplier is clamped to max 1`() {
        // With high shared friends ratio, result should not exceed priority
        // Run multiple times due to noise, but max should still be clamped
        val priority = 1.0
        repeat(100) {
            val result = LegacyExchangeMath.computeNewPriority_sigmoidFractionOfFriends(
                priority = priority,
                sharedFriends = 100,
                myFriends = 100
            )
            assertTrue(
                "Trust multiplier should be clamped to 1, result was $result",
                result <= priority
            )
        }
    }

    @Test
    fun `trust multiplier is clamped to min 0`() {
        // Even with noise, result should not go negative
        val priority = 1.0
        repeat(100) {
            val result = LegacyExchangeMath.computeNewPriority_sigmoidFractionOfFriends(
                priority = priority,
                sharedFriends = 1,
                myFriends = 100
            )
            assertTrue(
                "Trust multiplier should be clamped to >= 0, result was $result",
                result >= 0.0
            )
        }
    }

    @Test
    fun `computeNewPriority scales with priority input`() {
        // Higher priority input should result in higher output (proportionally)
        // Note: Due to noise, we test with many samples
        val lowPriority = 0.2
        val highPriority = 0.8

        var lowSum = 0.0
        var highSum = 0.0
        val iterations = 100

        repeat(iterations) {
            lowSum += LegacyExchangeMath.computeNewPriority_sigmoidFractionOfFriends(
                priority = lowPriority,
                sharedFriends = 50,
                myFriends = 100
            )
            highSum += LegacyExchangeMath.computeNewPriority_sigmoidFractionOfFriends(
                priority = highPriority,
                sharedFriends = 50,
                myFriends = 100
            )
        }

        val lowAvg = lowSum / iterations
        val highAvg = highSum / iterations

        assertTrue(
            "Higher priority should result in higher average output: low=$lowAvg, high=$highAvg",
            highAvg > lowAvg
        )
    }

    // ========================================================================
    // newPriority tests (max of computed and stored)
    // ========================================================================

    @Test
    fun `newPriority returns stored when stored is higher`() {
        // If stored priority is 1.0 and computed would be lower, return stored
        val result = LegacyExchangeMath.newPriority(
            remote = 0.5,
            stored = 1.0,
            commonFriends = 0,  // This forces EPSILON_TRUST multiplier
            myFriends = 100
        )
        assertEquals(1.0, result, DELTA)
    }

    @Test
    fun `newPriority returns computed when computed is higher`() {
        // If computed is higher than stored, return computed
        val stored = 0.001  // Very low stored value
        val remote = 1.0

        // With many shared friends, computed should be high
        // Run once and check it's >= stored (due to noise, exact value varies)
        val result = LegacyExchangeMath.newPriority(
            remote = remote,
            stored = stored,
            commonFriends = 100,
            myFriends = 100
        )
        assertTrue(
            "newPriority should return max, which should be >= stored=$stored, was $result",
            result >= stored
        )
    }

    @Test
    fun `newPriority with zero stored uses computed`() {
        val result = LegacyExchangeMath.newPriority(
            remote = 1.0,
            stored = 0.0,
            commonFriends = 0,
            myFriends = 100
        )
        // With 0 common friends, computed = 1.0 * EPSILON_TRUST = 0.001
        // max(0.001, 0.0) = 0.001
        assertEquals(EPSILON_TRUST, result, DELTA)
    }

    @Test
    fun `newPriority with both zero returns zero`() {
        val result = LegacyExchangeMath.newPriority(
            remote = 0.0,
            stored = 0.0,
            commonFriends = 50,
            myFriends = 100
        )
        // computed = 0.0 * trustMultiplier = 0.0, stored = 0.0
        // max(0.0, 0.0) = 0.0
        assertEquals(0.0, result, DELTA)
    }

    @Test
    fun `newPriority always returns non-negative`() {
        repeat(100) {
            val result = LegacyExchangeMath.newPriority(
                remote = Math.random(),
                stored = Math.random(),
                commonFriends = (Math.random() * 100).toInt(),
                myFriends = 100
            )
            assertTrue("newPriority should never be negative, was $result", result >= 0.0)
        }
    }

    // ========================================================================
    // Edge case tests
    // ========================================================================

    @Test
    fun `sharedFriends greater than myFriends`() {
        // This shouldn't happen in practice, but code should handle it gracefully
        // fraction would be > 1.0
        val result = LegacyExchangeMath.computeNewPriority_sigmoidFractionOfFriends(
            priority = 1.0,
            sharedFriends = 150,
            myFriends = 100
        )
        // Should still be clamped to [0, 1]
        assertTrue("Result should be clamped even with fraction > 1", result <= 1.0)
        assertTrue("Result should be non-negative", result >= 0.0)
    }

    @Test
    fun `myFriends is zero with non-zero sharedFriends`() {
        // Edge case: division by zero protection
        // Code should treat fraction as 0.0 when myFriends is 0
        val result = LegacyExchangeMath.computeNewPriority_sigmoidFractionOfFriends(
            priority = 1.0,
            sharedFriends = 10,
            myFriends = 0
        )
        // With fraction = 0.0 and sharedFriends != 0, we get sigmoid(0) + noise
        // Note: sharedFriends > 0 means EPSILON_TRUST override doesn't apply
        assertTrue("Result should be non-negative", result >= 0.0)
        assertTrue("Result should be <= 1.0", result <= 1.0)
    }

    @Test
    fun `negative priority is handled`() {
        // Negative priority shouldn't happen, but verify no crash
        // Result will be negative (negative * positive multiplier)
        // This is a degenerate case, just verify no exception
        LegacyExchangeMath.computeNewPriority_sigmoidFractionOfFriends(
            priority = -0.5,
            sharedFriends = 50,
            myFriends = 100
        )
        assertTrue("Should handle negative priority without exception", true)
    }

    // ========================================================================
    // Statistical tests for noise behavior
    // ========================================================================

    @Test
    fun `results vary due to gaussian noise`() {
        // Verify that repeated calls produce different results (due to noise)
        val results = mutableSetOf<Double>()
        repeat(20) {
            val result = LegacyExchangeMath.computeNewPriority_sigmoidFractionOfFriends(
                priority = 1.0,
                sharedFriends = 50,
                myFriends = 100
            )
            results.add(result)
        }
        // Should have multiple distinct values due to noise
        assertTrue(
            "Results should vary due to Gaussian noise, got ${results.size} unique values",
            results.size > 1
        )
    }

    @Test
    fun `results cluster around expected mean`() {
        // With 50% shared friends, sigmoid ≈ 0.93, plus noise (mean 0.0, variance 0.003)
        // So we expect results to cluster tightly around sigmoid value
        var sum = 0.0
        val iterations = 100

        repeat(iterations) {
            sum += LegacyExchangeMath.computeNewPriority_sigmoidFractionOfFriends(
                priority = 1.0,
                sharedFriends = 50,
                myFriends = 100
            )
        }

        val average = sum / iterations
        // With sigmoid(0.5) ≈ 0.93 and low noise (mean 0.0, var 0.003), values cluster around 0.93
        // With Casific's low variance (0.003), std dev ≈ 0.055, so average should be close to sigmoid
        assertTrue(
            "Average should be high (> 0.85) with 50% shared friends, was $average",
            average > 0.85
        )
    }
}
