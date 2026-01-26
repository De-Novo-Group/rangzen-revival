/*
 * Copyright (c) 2016, De Novo Group
 * All rights reserved.
 *
 * Ported to Kotlin for the Rangzen Revival Project (2026)
 *
 * A data class representing a security profile and its properties.
 * Profiles control privacy, trust, and exchange behavior.
 */
package org.denovogroup.rangzen.backend

/**
 * Security profile controlling app behavior for privacy and trust.
 *
 * Two preset profiles are provided:
 * - FLEXIBLE (strength=1): More open settings for casual use
 * - STRICT (strength=2): Restrictive settings for high-security scenarios
 *
 * Users can also create custom profiles by modifying individual settings.
 */
data class SecurityProfile(
    /** Profile security strength level (higher = more secure) */
    val strength: Int,

    /** Profile identifier name (resource ID in original, now string) */
    var name: String = PROFILE_CUSTOM,

    /** Allow display/storage of timestamps */
    var timestamp: Boolean = true,

    /** Allow display/storage of sender pseudonym */
    var pseudonyms: Boolean = true,

    /** Maximum messages in feed (0 = unlimited) */
    var feedSize: Int = 0,

    /** Can add friends from device phonebook */
    var friendsViaBook: Boolean = true,

    /** Can add friends via BLE pairing */
    var friendsViaPairing: Boolean = true,

    /** Enable auto-delete of low-trust/old messages */
    var autodelete: Boolean = false,

    /** Trust threshold for auto-delete (0.0-1.0) */
    var autodeleteTrust: Float = 0.05f,

    /** Age in days for auto-delete */
    var autodeleteAge: Int = 14,

    /** Allow sharing location with messages */
    var shareLocation: Boolean = true,

    /** Minimum shared contacts required for exchange */
    var minSharedContacts: Int = 0,

    /** Maximum messages per exchange */
    var maxMessages: Int = 1000,

    /** Cooldown between exchanges in seconds */
    var cooldown: Int = 5,

    /** Default timebound expiry period in days */
    var timeboundPeriod: Int = 3,

    /** Enforce device lock pattern */
    var enforceLock: Boolean = false,

    /** Calculate trust based on shared friends (PSI-Ca) */
    var useTrust: Boolean = false,

    /** Randomize exchange order vs round-robin */
    var randomExchange: Boolean = true,

    /** Minimum contacts required for restricted message hop */
    var minContactsForHop: Int = 3
) {
    companion object {
        // Profile name constants
        const val PROFILE_FLEXIBLE = "flexible"
        const val PROFILE_STRICT = "strict"
        const val PROFILE_CUSTOM = "custom"

        /**
         * Create the FLEXIBLE preset profile.
         * More open settings for casual use.
         */
        fun flexible(): SecurityProfile = SecurityProfile(
            strength = 1,
            name = PROFILE_FLEXIBLE,
            timestamp = true,
            pseudonyms = true,
            feedSize = 0,
            friendsViaBook = true,
            friendsViaPairing = true,
            autodelete = false,
            autodeleteTrust = 0.05f,
            autodeleteAge = 14,
            shareLocation = true,
            minSharedContacts = 0,
            maxMessages = 1000,
            cooldown = 5,
            timeboundPeriod = 3,
            enforceLock = false,
            useTrust = false,
            randomExchange = true,
            minContactsForHop = 3
        )

        /**
         * Create the STRICT preset profile.
         * Restrictive settings for high-security scenarios.
         */
        fun strict(): SecurityProfile = SecurityProfile(
            strength = 2,
            name = PROFILE_STRICT,
            timestamp = false,
            pseudonyms = false,
            feedSize = 0,
            friendsViaBook = false,
            friendsViaPairing = true,
            autodelete = false,
            autodeleteTrust = 0.05f,
            autodeleteAge = 14,
            shareLocation = false,
            minSharedContacts = 5,
            maxMessages = 250,
            cooldown = 30,
            timeboundPeriod = 3,
            enforceLock = true,
            useTrust = true,
            randomExchange = true,
            minContactsForHop = 5
        )
    }

    /**
     * Create a deep copy of this profile.
     */
    fun clone(): SecurityProfile = copy()
}
