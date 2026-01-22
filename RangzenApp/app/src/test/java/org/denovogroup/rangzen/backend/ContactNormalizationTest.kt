/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * Unit tests for E.164 phone number normalization.
 */
package org.denovogroup.rangzen.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for E.164 phone number normalization logic.
 *
 * These tests verify that phone numbers are correctly normalized to
 * E.164 format for consistent friend matching across devices.
 */
class ContactNormalizationTest {

    companion object {
        // Test helper that mimics the normalization logic from FriendsFragment
        fun normalizeToE164(phoneNumber: String, defaultCountryCode: String = "1"): String? {
            // Check for leading + before stripping
            val hasPlus = phoneNumber.trimStart().startsWith("+")
            // Remove all non-digit characters
            val digits = phoneNumber.replace(Regex("[^\\d]"), "")
            if (digits.isEmpty()) return null
            
            return when {
                // Had a leading + - use digits as country code + number
                hasPlus -> {
                    if (digits.length < 10 || digits.length > 15) null
                    else "+$digits"
                }
                // Has country code without +
                digits.length > 10 -> {
                    "+$digits"
                }
                // Local number - add default country code
                digits.length == 10 -> {
                    "+$defaultCountryCode$digits"
                }
                // Too short
                else -> null
            }
        }
    }

    // ========================================================================
    // Basic normalization tests
    // ========================================================================

    @Test
    fun `normalize US number with dashes`() {
        val result = normalizeToE164("555-123-4567")
        assertEquals("+15551234567", result)
    }

    @Test
    fun `normalize US number with parentheses`() {
        val result = normalizeToE164("(555) 123-4567")
        assertEquals("+15551234567", result)
    }

    @Test
    fun `normalize US number with spaces`() {
        val result = normalizeToE164("555 123 4567")
        assertEquals("+15551234567", result)
    }

    @Test
    fun `normalize number already in E164 format`() {
        val result = normalizeToE164("+15551234567")
        assertEquals("+15551234567", result)
    }

    @Test
    fun `normalize number with country code without plus`() {
        val result = normalizeToE164("15551234567")
        assertEquals("+15551234567", result)
    }

    // ========================================================================
    // International number tests
    // ========================================================================

    @Test
    fun `normalize UK number with country code`() {
        val result = normalizeToE164("+447911123456")
        assertEquals("+447911123456", result)
    }

    @Test
    fun `normalize German number with country code`() {
        val result = normalizeToE164("+4915123456789")
        assertEquals("+4915123456789", result)
    }

    @Test
    fun `normalize Iranian number with country code`() {
        val result = normalizeToE164("+989123456789")
        assertEquals("+989123456789", result)
    }

    // ========================================================================
    // Edge cases and invalid inputs
    // ========================================================================

    @Test
    fun `empty string returns null`() {
        val result = normalizeToE164("")
        assertNull(result)
    }

    @Test
    fun `only spaces returns null`() {
        val result = normalizeToE164("   ")
        assertNull(result)
    }

    @Test
    fun `too short number returns null`() {
        val result = normalizeToE164("12345")
        assertNull(result)
    }

    @Test
    fun `non-numeric characters are stripped`() {
        val result = normalizeToE164("abc555def123ghi4567")
        assertEquals("+15551234567", result)
    }

    @Test
    fun `multiple plus signs handled`() {
        // Multiple + signs - we detect leading + and strip all non-digits
        val result = normalizeToE164("++15551234567")
        // hasPlus = true (starts with +), digits = "15551234567" (11 chars, valid)
        // Result: "+15551234567"
        assertEquals("+15551234567", result)
    }

    // ========================================================================
    // Default country code tests
    // ========================================================================

    @Test
    fun `local number uses default country code`() {
        val result = normalizeToE164("5551234567", "44")
        assertEquals("+445551234567", result)
    }

    @Test
    fun `e164 format ignores default country code`() {
        val result = normalizeToE164("+15551234567", "44")
        assertEquals("+15551234567", result)
    }
}
