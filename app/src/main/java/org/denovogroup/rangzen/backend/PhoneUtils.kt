/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * Phone number utilities for E.164 normalization.
 * Used by contact hashing (privacy-preserving friend discovery).
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

    /**
     * Check if an E.164 number belongs to the given country.
     * This is used to filter contacts to same-country only (for Iran use case).
     *
     * @param e164Number The E.164 formatted number (e.g., "+989121234567")
     * @param deviceCountryCode The device's country ISO code (e.g., "IR")
     * @return true if the number matches the device's country
     */
    fun isNumberFromCountry(e164Number: String, deviceCountryCode: String): Boolean {
        if (!e164Number.startsWith("+")) return false
        
        val callingCode = getCallingCodeForCountry(deviceCountryCode) ?: return false
        // Check if number starts with +[calling_code]
        return e164Number.startsWith("+$callingCode")
    }

    /**
     * Check if a phone number is likely a mobile number.
     * This uses country-specific prefix patterns.
     *
     * For contact hashing, we only want mobile numbers (not landlines, toll-free, etc.)
     * because Murmur is a mobile app - people won't be running it on landlines.
     *
     * NOTE: This is a heuristic - some numbers may be miscategorized.
     * The patterns are based on common mobile prefixes per country.
     *
     * @param e164Number The E.164 formatted number
     * @param countryCode The ISO country code for more accurate detection
     * @return true if the number appears to be a mobile number
     */
    fun isMobileNumber(e164Number: String, countryCode: String): Boolean {
        if (!e164Number.startsWith("+")) return false

        // Get the digits after the + sign
        val digitsOnly = e164Number.drop(1)

        return when (countryCode.uppercase(Locale.US)) {
            "IR" -> {
                // Iranian mobile numbers: +98 9XX XXX XXXX
                // Mobile prefixes: 901-939 (various carriers)
                // Landlines have different area code patterns (21 for Tehran, etc.)
                digitsOnly.startsWith("989") && digitsOnly.length == 12
            }
            "US", "CA" -> {
                // North America doesn't have clear mobile vs landline prefixes.
                // All 10-digit numbers can be mobile or landline.
                // For simplicity, we accept all valid NANP numbers.
                // This may include some landlines, which is acceptable.
                digitsOnly.startsWith("1") && digitsOnly.length == 11
            }
            "GB", "UK" -> {
                // UK mobile numbers: +44 7XXX XXXXXX
                // Landlines: +44 1XXX, +44 2XXX (area codes)
                digitsOnly.startsWith("447") && digitsOnly.length in 11..12
            }
            "DE" -> {
                // German mobile: +49 15XX, +49 16X, +49 17X
                // The pattern is +49 followed by mobile prefix
                digitsOnly.startsWith("4915") || 
                digitsOnly.startsWith("4916") || 
                digitsOnly.startsWith("4917")
            }
            "FR" -> {
                // French mobile: +33 6XX or +33 7XX
                (digitsOnly.startsWith("336") || digitsOnly.startsWith("337")) &&
                digitsOnly.length == 11
            }
            "IN" -> {
                // Indian mobile: +91 [6-9]XXX XXX XXX (10 digits after country code)
                // Mobile numbers start with 6, 7, 8, or 9
                digitsOnly.startsWith("91") && digitsOnly.length == 12 &&
                digitsOnly[2] in '6'..'9'
            }
            "PK" -> {
                // Pakistani mobile: +92 3XX XXXXXXX
                digitsOnly.startsWith("923") && digitsOnly.length == 12
            }
            "AF" -> {
                // Afghan mobile: +93 7X XXX XXXX
                digitsOnly.startsWith("937") && digitsOnly.length == 11
            }
            "TR" -> {
                // Turkish mobile: +90 5XX XXX XXXX
                digitsOnly.startsWith("905") && digitsOnly.length == 12
            }
            "EG" -> {
                // Egyptian mobile: +20 1X XXX XXXX
                digitsOnly.startsWith("201") && digitsOnly.length == 12
            }
            "SA" -> {
                // Saudi mobile: +966 5X XXX XXXX
                digitsOnly.startsWith("9665") && digitsOnly.length == 12
            }
            "AE" -> {
                // UAE mobile: +971 5X XXX XXXX
                digitsOnly.startsWith("9715") && digitsOnly.length == 12
            }
            else -> {
                // For unknown countries, accept all numbers (better false positives than false negatives)
                // This ensures we don't accidentally exclude valid mobile numbers.
                true
            }
        }
    }
}
