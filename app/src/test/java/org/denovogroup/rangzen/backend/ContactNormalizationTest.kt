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
 * These tests call the production PhoneUtils.manualNormalizeToE164() directly,
 * ensuring test fidelity with the actual app behavior.
 */
class ContactNormalizationTest {

    // Use production code directly - no local helpers
    private fun normalizeToE164(phoneNumber: String, countryCode: String = "US"): String? {
        return PhoneUtils.manualNormalizeToE164(phoneNumber, countryCode)
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
    // Country-specific normalization tests
    // ========================================================================

    @Test
    fun `UK local number with country code GB`() {
        // UK 10-digit local number starting with 0
        val result = normalizeToE164("07911123456", "GB")
        assertEquals("+447911123456", result)
    }

    @Test
    fun `Iranian local number with country code IR`() {
        // Iranian local number
        val result = normalizeToE164("09123456789", "IR")
        assertEquals("+989123456789", result)
    }

    @Test
    fun `e164 format ignores country code parameter`() {
        val result = normalizeToE164("+15551234567", "GB")
        assertEquals("+15551234567", result)
    }

    // ========================================================================
    // PhoneUtils.getCallingCodeForCountry tests
    // ========================================================================

    @Test
    fun `getCallingCodeForCountry returns correct codes`() {
        assertEquals("1", PhoneUtils.getCallingCodeForCountry("US"))
        assertEquals("44", PhoneUtils.getCallingCodeForCountry("GB"))
        assertEquals("98", PhoneUtils.getCallingCodeForCountry("IR"))
        assertEquals("49", PhoneUtils.getCallingCodeForCountry("DE"))
        assertNull(PhoneUtils.getCallingCodeForCountry("XX"))
    }

    // ========================================================================
    // Full normalization flow tests (PhoneUtils.normalizePhoneNumber)
    // This tests the complete flow used in production
    // ========================================================================

    @Test
    fun `full flow prefers Android normalized number`() {
        // When Android provides a normalized number, use it
        val result = PhoneUtils.normalizePhoneNumber(
            rawNumber = "(555) 123-4567",
            androidNormalized = "+15551234567",
            countryCode = "US"
        )
        assertEquals("+15551234567", result)
    }

    @Test
    fun `full flow ignores invalid Android normalized`() {
        // Android normalized without + is invalid - fall back to manual
        val result = PhoneUtils.normalizePhoneNumber(
            rawNumber = "5551234567",
            androidNormalized = "15551234567",  // Missing +
            countryCode = "US"
        )
        assertEquals("+15551234567", result)
    }

    @Test
    fun `full flow uses manual when Android returns null`() {
        val result = PhoneUtils.normalizePhoneNumber(
            rawNumber = "5551234567",
            androidNormalized = null,
            countryCode = "US"
        )
        assertEquals("+15551234567", result)
    }

    @Test
    fun `full flow uses manual when Android returns empty`() {
        val result = PhoneUtils.normalizePhoneNumber(
            rawNumber = "5551234567",
            androidNormalized = "",
            countryCode = "US"
        )
        assertEquals("+15551234567", result)
    }

    @Test
    fun `full flow handles international with manual fallback`() {
        val result = PhoneUtils.normalizePhoneNumber(
            rawNumber = "07911123456",
            androidNormalized = null,
            countryCode = "GB"
        )
        assertEquals("+447911123456", result)
    }

    @Test
    fun `full flow returns null for invalid input`() {
        val result = PhoneUtils.normalizePhoneNumber(
            rawNumber = "abc",
            androidNormalized = null,
            countryCode = "US"
        )
        assertNull(result)
    }
}
