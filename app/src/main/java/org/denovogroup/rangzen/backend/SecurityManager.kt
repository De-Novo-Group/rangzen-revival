/*
 * Copyright (c) 2016, De Novo Group
 * All rights reserved.
 *
 * Ported to Kotlin for the Rangzen Revival Project (2026)
 *
 * Controller class managing security profiles and user settings.
 * Stores settings in SharedPreferences (Settings_secure file).
 */
package org.denovogroup.rangzen.backend

import android.content.Context
import android.content.SharedPreferences

/**
 * Singleton managing security profiles and user settings.
 *
 * Settings are stored in a dedicated SharedPreferences file (Settings_secure)
 * to isolate security-sensitive data from other app preferences.
 */
object SecurityManager {

    /** SharedPreferences file for security settings */
    const val SETTINGS_FILE = "Settings_secure"

    /** Key for user pseudonym */
    const val PSEUDONYM_KEY = "pseudonym"

    /** Key for stored MAC address (for Bluetooth identity) */
    const val MAC_KEY = "mac"

    // Profile setting keys
    private const val PROFILE_NAME_KEY = "name"
    private const val PROFILE_TIMESTAMP_KEY = "useTimestamp"
    private const val PROFILE_PSEUDONYM_KEY = "usePseudonym"
    private const val PROFILE_FEED_SIZE_KEY = "maxFeedSize"
    private const val PROFILE_FRIEND_VIA_BOOK_KEY = "addFromBook"
    private const val PROFILE_FRIEND_VIA_PAIRING_KEY = "addFromPairing"
    private const val PROFILE_AUTO_DELETE_KEY = "useAutoDecay"
    private const val PROFILE_AUTO_DELETE_TRUST_KEY = "AutoDecayTrust"
    private const val PROFILE_AUTO_DELETE_AGE_KEY = "AutoDecayAge"
    private const val PROFILE_SHARE_LOCATIONS_KEY = "shareLocations"
    private const val PROFILE_MIN_SHARED_CONTACTS_KEY = "minSharedContacts"
    private const val PROFILE_MAX_MESSAGES_KEY = "maxMessagesPerExchange"
    private const val PROFILE_COOLDOWN_KEY = "exchangeCooldown"
    private const val PROFILE_TIMEBOUND_KEY = "timebound"
    private const val PROFILE_ENFORCE_LOCK_KEY = "enforceLock"
    private const val PROFILE_USE_TRUST_KEY = "useTrust"
    private const val PROFILE_RANDOM_EXCHANGE_KEY = "randomExchange"
    private const val PROFILE_MIN_CONTACTS_FOR_HOP_KEY = "minContactsForHop"

    /** Key to track if initial seeding from config.json has been done */
    private const val PROFILE_SEEDED_KEY = "profile_seeded_from_config"

    /** Default pseudonym if none is stored */
    const val DEFAULT_PSEUDONYM = ""

    /** Available preset profiles */
    private val presetProfiles: Map<String, SecurityProfile> by lazy {
        mapOf(
            SecurityProfile.PROFILE_FLEXIBLE to SecurityProfile.flexible(),
            SecurityProfile.PROFILE_STRICT to SecurityProfile.strict()
        )
    }

    /**
     * Get SharedPreferences for security settings.
     */
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
    }

    /**
     * Get a preset profile by name.
     *
     * @param name Profile name (PROFILE_FLEXIBLE, PROFILE_STRICT)
     * @return A clone of the preset profile, or null if not found
     */
    fun getPresetProfile(name: String): SecurityProfile? {
        return presetProfiles[name]?.clone()
    }

    /**
     * Get the currently active security profile.
     *
     * Reads from SharedPreferences. If a preset profile name is stored,
     * returns that preset. Otherwise, reads individual settings for a
     * custom profile.
     */
    fun getCurrentProfile(context: Context): SecurityProfile {
        val prefs = getPrefs(context)

        // On first run, seed from config.json to apply paper-aligned defaults.
        if (!prefs.getBoolean(PROFILE_SEEDED_KEY, false)) {
            seedFromConfig(context)
        }

        val defaults = SecurityProfile.flexible()

        val profileName = prefs.getString(PROFILE_NAME_KEY, defaults.name) ?: defaults.name

        // Check if using a preset profile
        presetProfiles[profileName]?.let { return it.clone() }

        // Custom profile - read all individual settings
        return SecurityProfile(
            strength = 0,
            name = SecurityProfile.PROFILE_CUSTOM,
            timestamp = prefs.getBoolean(PROFILE_TIMESTAMP_KEY, defaults.timestamp),
            pseudonyms = prefs.getBoolean(PROFILE_PSEUDONYM_KEY, defaults.pseudonyms),
            feedSize = prefs.getInt(PROFILE_FEED_SIZE_KEY, defaults.feedSize),
            friendsViaBook = prefs.getBoolean(PROFILE_FRIEND_VIA_BOOK_KEY, defaults.friendsViaBook),
            friendsViaPairing = prefs.getBoolean(PROFILE_FRIEND_VIA_PAIRING_KEY, defaults.friendsViaPairing),
            autodelete = prefs.getBoolean(PROFILE_AUTO_DELETE_KEY, defaults.autodelete),
            autodeleteTrust = prefs.getFloat(PROFILE_AUTO_DELETE_TRUST_KEY, defaults.autodeleteTrust),
            autodeleteAge = prefs.getInt(PROFILE_AUTO_DELETE_AGE_KEY, defaults.autodeleteAge),
            shareLocation = prefs.getBoolean(PROFILE_SHARE_LOCATIONS_KEY, defaults.shareLocation),
            minSharedContacts = prefs.getInt(PROFILE_MIN_SHARED_CONTACTS_KEY, defaults.minSharedContacts),
            maxMessages = prefs.getInt(PROFILE_MAX_MESSAGES_KEY, defaults.maxMessages),
            cooldown = prefs.getInt(PROFILE_COOLDOWN_KEY, defaults.cooldown),
            timeboundPeriod = prefs.getInt(PROFILE_TIMEBOUND_KEY, defaults.timeboundPeriod),
            enforceLock = prefs.getBoolean(PROFILE_ENFORCE_LOCK_KEY, defaults.enforceLock),
            useTrust = prefs.getBoolean(PROFILE_USE_TRUST_KEY, defaults.useTrust),
            randomExchange = prefs.getBoolean(PROFILE_RANDOM_EXCHANGE_KEY, defaults.randomExchange),
            minContactsForHop = prefs.getInt(PROFILE_MIN_CONTACTS_FOR_HOP_KEY, defaults.minContactsForHop)
        )
    }

    /**
     * Seed profile settings from config.json on first run.
     * This ensures paper-aligned defaults (useTrust=true) are applied
     * even though SecurityProfile.flexible() has useTrust=false.
     */
    private fun seedFromConfig(context: Context) {
        val prefs = getPrefs(context)
        val useTrustFromConfig = AppConfig.useTrust(context)
        val minSharedContactsFromConfig = AppConfig.minSharedContactsForExchange(context)
        val defaults = SecurityProfile.flexible()

        prefs.edit().apply {
            putString(PROFILE_NAME_KEY, SecurityProfile.PROFILE_CUSTOM)
            putBoolean(PROFILE_TIMESTAMP_KEY, defaults.timestamp)
            putBoolean(PROFILE_PSEUDONYM_KEY, defaults.pseudonyms)
            putInt(PROFILE_FEED_SIZE_KEY, defaults.feedSize)
            putBoolean(PROFILE_FRIEND_VIA_BOOK_KEY, defaults.friendsViaBook)
            putBoolean(PROFILE_FRIEND_VIA_PAIRING_KEY, defaults.friendsViaPairing)
            putBoolean(PROFILE_AUTO_DELETE_KEY, defaults.autodelete)
            putFloat(PROFILE_AUTO_DELETE_TRUST_KEY, defaults.autodeleteTrust)
            putInt(PROFILE_AUTO_DELETE_AGE_KEY, defaults.autodeleteAge)
            putBoolean(PROFILE_SHARE_LOCATIONS_KEY, defaults.shareLocation)
            putInt(PROFILE_MIN_SHARED_CONTACTS_KEY, minSharedContactsFromConfig)
            putInt(PROFILE_MAX_MESSAGES_KEY, defaults.maxMessages)
            putInt(PROFILE_COOLDOWN_KEY, defaults.cooldown)
            putInt(PROFILE_TIMEBOUND_KEY, defaults.timeboundPeriod)
            putBoolean(PROFILE_ENFORCE_LOCK_KEY, defaults.enforceLock)
            putBoolean(PROFILE_USE_TRUST_KEY, useTrustFromConfig)
            putBoolean(PROFILE_RANDOM_EXCHANGE_KEY, defaults.randomExchange)
            putInt(PROFILE_MIN_CONTACTS_FOR_HOP_KEY, defaults.minContactsForHop)
            putBoolean(PROFILE_SEEDED_KEY, true)
            apply()
        }
        timber.log.Timber.i("SecurityManager: Seeded profile from config.json (useTrust=$useTrustFromConfig, minSharedContacts=$minSharedContactsFromConfig)")
    }

    /**
     * Set the current profile to a preset by name.
     *
     * @param context Application context
     * @param profileName Name of the preset profile
     * @return true if the preset was found and saved, false otherwise
     */
    fun setCurrentProfile(context: Context, profileName: String): Boolean {
        val preset = presetProfiles[profileName] ?: return false
        setCurrentProfile(context, preset)
        return true
    }

    /**
     * Save a security profile to SharedPreferences.
     *
     * All profile properties are persisted individually so custom
     * profiles can be restored correctly.
     */
    fun setCurrentProfile(context: Context, profile: SecurityProfile) {
        getPrefs(context).edit().apply {
            putString(PROFILE_NAME_KEY, profile.name)
            putBoolean(PROFILE_TIMESTAMP_KEY, profile.timestamp)
            putBoolean(PROFILE_PSEUDONYM_KEY, profile.pseudonyms)
            putInt(PROFILE_FEED_SIZE_KEY, profile.feedSize)
            putBoolean(PROFILE_FRIEND_VIA_BOOK_KEY, profile.friendsViaBook)
            putBoolean(PROFILE_FRIEND_VIA_PAIRING_KEY, profile.friendsViaPairing)
            putBoolean(PROFILE_AUTO_DELETE_KEY, profile.autodelete)
            putFloat(PROFILE_AUTO_DELETE_TRUST_KEY, profile.autodeleteTrust)
            putInt(PROFILE_AUTO_DELETE_AGE_KEY, profile.autodeleteAge)
            putBoolean(PROFILE_SHARE_LOCATIONS_KEY, profile.shareLocation)
            putInt(PROFILE_MIN_SHARED_CONTACTS_KEY, profile.minSharedContacts)
            putInt(PROFILE_MAX_MESSAGES_KEY, profile.maxMessages)
            putInt(PROFILE_COOLDOWN_KEY, profile.cooldown)
            putInt(PROFILE_TIMEBOUND_KEY, profile.timeboundPeriod)
            putBoolean(PROFILE_ENFORCE_LOCK_KEY, profile.enforceLock)
            putBoolean(PROFILE_USE_TRUST_KEY, profile.useTrust)
            putBoolean(PROFILE_RANDOM_EXCHANGE_KEY, profile.randomExchange)
            putInt(PROFILE_MIN_CONTACTS_FOR_HOP_KEY, profile.minContactsForHop)
            apply()
        }
    }

    /**
     * Get the user's pseudonym.
     */
    fun getCurrentPseudonym(context: Context): String {
        val prefs = getPrefs(context)
        if (!prefs.contains(PSEUDONYM_KEY)) {
            setCurrentPseudonym(context, DEFAULT_PSEUDONYM)
        }
        return prefs.getString(PSEUDONYM_KEY, DEFAULT_PSEUDONYM) ?: DEFAULT_PSEUDONYM
    }

    /**
     * Set the user's pseudonym.
     */
    fun setCurrentPseudonym(context: Context, name: String) {
        getPrefs(context).edit().putString(PSEUDONYM_KEY, name).apply()
    }

    /**
     * Get the stored Bluetooth MAC address.
     */
    fun getStoredMAC(context: Context): String {
        return getPrefs(context).getString(MAC_KEY, "") ?: ""
    }

    /**
     * Store the Bluetooth MAC address.
     */
    fun setStoredMAC(context: Context, mac: String) {
        getPrefs(context).edit().putString(MAC_KEY, mac.uppercase()).apply()
    }

    /**
     * Clear all security profile data.
     * Use with caution - this resets all user security settings.
     */
    fun clearProfileData(context: Context) {
        getPrefs(context).edit().clear().apply()
    }

    /**
     * Compare two profiles and return the most secure one.
     *
     * @return The profile name with higher strength, or profileA if equal
     */
    fun getMostSecureProfile(profileA: String, profileB: String): String {
        val profA = presetProfiles[profileA]
        val profB = presetProfiles[profileB]
        return when {
            profA == null -> profileB
            profB == null -> profileA
            profA.strength > profB.strength -> profileA
            else -> profileB
        }
    }

    // Convenience accessors for common settings

    /**
     * Check if PSI-based trust is enabled in current profile.
     */
    fun useTrust(context: Context): Boolean = getCurrentProfile(context).useTrust

    /**
     * Check if pseudonyms should be included in messages.
     */
    fun includePseudonym(context: Context): Boolean = getCurrentProfile(context).pseudonyms

    /**
     * Check if location sharing is enabled.
     */
    fun shareLocation(context: Context): Boolean = getCurrentProfile(context).shareLocation

    /**
     * Get minimum shared contacts required for exchange.
     */
    fun minSharedContactsForExchange(context: Context): Int = getCurrentProfile(context).minSharedContacts

    /**
     * Get maximum messages per exchange.
     */
    fun maxMessagesPerExchange(context: Context): Int = getCurrentProfile(context).maxMessages

    /**
     * Get exchange cooldown in milliseconds.
     */
    fun exchangeCooldownMs(context: Context): Long = getCurrentProfile(context).cooldown * 1000L

    /**
     * Check if random exchange order is enabled.
     */
    fun randomExchange(context: Context): Boolean = getCurrentProfile(context).randomExchange

    /**
     * Get auto-delete trust threshold.
     */
    fun autodeleteTrustThreshold(context: Context): Float = getCurrentProfile(context).autodeleteTrust

    /**
     * Get minimum contacts required for restricted message hop.
     */
    fun minContactsForHop(context: Context): Int = getCurrentProfile(context).minContactsForHop
}
