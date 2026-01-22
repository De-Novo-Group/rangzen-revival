/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * Phone number utilities for E.164 normalization.
 */
package org.denovogroup.rangzen.backend

import java.util.Locale

/**
 * Utilities for phone number normalization to E.164 format.
 *
 * E.164 format is "+[country code][number]" with no spaces/dashes.
 * Examples: "+15551234567", "+442071234567"
 */
object PhoneUtils {

    /**
     * Full normalization flow - testable without Android dependencies.
     *
     * This mirrors the production flow in FriendsFragment.normalizePhoneNumberToE164:
     * 1. If Android provided a normalized E.164 number, use it
     * 2. Otherwise, fall back to manual normalization
     *
     * @param rawNumber The raw phone number in any format
     * @param androidNormalized Android's pre-normalized number (may be null)
     * @param countryCode ISO country code from device (e.g., "US", "GB")
     * @return E.164 formatted number, or null if invalid
     */
    fun normalizePhoneNumber(
        rawNumber: String,
        androidNormalized: String?,
        countryCode: String
    ): String? {
        // If Android already provided a normalized E.164 number, use it
        if (!androidNormalized.isNullOrEmpty() && androidNormalized.startsWith("+")) {
            return androidNormalized
        }

        // Fall back to manual normalization
        return manualNormalizeToE164(rawNumber, countryCode)
    }

    /**
     * Manual phone number normalization.
     * Strips all non-digit characters and attempts to add country code.
     *
     * @param rawNumber The raw phone number in any format
     * @param countryCode ISO country code (e.g., "US", "GB", "IR")
     * @return E.164 formatted number, or null if invalid
     */
    fun manualNormalizeToE164(rawNumber: String, countryCode: String = "US"): String? {
        // Check for leading + before stripping
        val hasPlus = rawNumber.trimStart().startsWith("+")
        // Strip all non-digit characters
        val digitsOnly = rawNumber.replace(Regex("[^0-9]"), "")

        if (digitsOnly.isEmpty()) {
            return null
        }

        // If already has country code (starts with +), just clean it up
        if (hasPlus) {
            // Validate length: E.164 allows 1-15 digits after country code
            // Most numbers are 10-15 total digits
            return if (digitsOnly.length in 7..15) "+$digitsOnly" else null
        }

        // Try to add country code based on number length and format
        return when (countryCode.uppercase(Locale.US)) {
            "US", "CA" -> {
                // North American Numbering Plan
                when {
                    digitsOnly.length == 10 -> "+1$digitsOnly"
                    digitsOnly.length == 11 && digitsOnly.startsWith("1") -> "+$digitsOnly"
                    digitsOnly.length > 11 -> "+$digitsOnly" // Already has country code
                    else -> null // Too short
                }
            }
            "GB", "UK" -> {
                // UK numbers
                when {
                    digitsOnly.length == 10 && digitsOnly.startsWith("0") -> "+44${digitsOnly.drop(1)}"
                    digitsOnly.length == 11 && digitsOnly.startsWith("0") -> "+44${digitsOnly.drop(1)}"
                    digitsOnly.length >= 10 -> "+44$digitsOnly"
                    else -> null
                }
            }
            "IR" -> {
                // Iranian numbers
                when {
                    digitsOnly.length == 10 && digitsOnly.startsWith("0") -> "+98${digitsOnly.drop(1)}"
                    digitsOnly.length == 11 && digitsOnly.startsWith("0") -> "+98${digitsOnly.drop(1)}"
                    digitsOnly.length == 10 -> "+98$digitsOnly"
                    else -> null
                }
            }
            "DE" -> {
                // German numbers
                when {
                    digitsOnly.startsWith("0") -> "+49${digitsOnly.drop(1)}"
                    digitsOnly.length >= 10 -> "+49$digitsOnly"
                    else -> null
                }
            }
            else -> {
                // Generic: try to get calling code, or just prepend +
                val callingCode = getCallingCodeForCountry(countryCode)
                when {
                    callingCode != null && digitsOnly.length >= 7 -> "+$callingCode$digitsOnly"
                    digitsOnly.length >= 10 -> "+$digitsOnly"
                    else -> null
                }
            }
        }
    }

    /**
     * Get the calling code for a country ISO code.
     *
     * @param countryCode ISO 3166-1 alpha-2 country code
     * @return Calling code without +, or null if unknown
     */
    fun getCallingCodeForCountry(countryCode: String): String? {
        return when (countryCode.uppercase(Locale.US)) {
            "US", "CA" -> "1"
            "GB", "UK" -> "44"
            "DE" -> "49"
            "FR" -> "33"
            "IT" -> "39"
            "ES" -> "34"
            "AU" -> "61"
            "JP" -> "81"
            "CN" -> "86"
            "IN" -> "91"
            "BR" -> "55"
            "RU" -> "7"
            "MX" -> "52"
            "IR" -> "98"
            "AF" -> "93"
            "PK" -> "92"
            "TR" -> "90"
            "EG" -> "20"
            "SA" -> "966"
            "AE" -> "971"
            "IL" -> "972"
            else -> null
        }
    }
}
