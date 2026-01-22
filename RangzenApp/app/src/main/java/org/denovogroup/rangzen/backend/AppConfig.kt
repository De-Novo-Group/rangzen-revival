/*
 * AppConfig loader for Rangzen.
 *
 * Loads configuration from assets/config.json to avoid hardcoded constants.
 * Fails loudly when config is missing or invalid.
 */
package org.denovogroup.rangzen.backend

import android.content.Context
import android.util.Log
import org.json.JSONObject
import timber.log.Timber

/**
 * Central configuration loader.
 */
object AppConfig {

    // Tag for Android Log in case Timber filtering hides messages.
    private const val LOG_TAG = "AppConfig"
    // Config file name inside assets.
    private const val CONFIG_FILE_NAME = "config.json"
    // JSON key for GATT read fallback delay.
    private const val KEY_GATT_READ_FALLBACK_DELAY_MS = "gattReadFallbackDelayMs"
    // JSON key for initial write delay after CCCD enable.
    private const val KEY_INITIAL_WRITE_DELAY_MS = "initialWriteDelayMs"
    // JSON key for maximum GATT attribute length.
    private const val KEY_GATT_MAX_ATTRIBUTE_LENGTH = "gattMaxAttributeLength"
    // JSON key for maximum messages per exchange.
    private const val KEY_MAX_MESSAGES_PER_EXCHANGE = "maxMessagesPerExchange"
    // JSON key for exchange session timeout.
    private const val KEY_EXCHANGE_SESSION_TIMEOUT_MS = "exchangeSessionTimeoutMs"
    // JSON key for BLE MTU size.
    private const val KEY_BLE_MTU = "bleMtu"
    // JSON key for trust usage in PSI.
    private const val KEY_USE_TRUST = "useTrust"
    // JSON key for minimum shared contacts to allow exchange.
    private const val KEY_MIN_SHARED_CONTACTS = "minSharedContactsForExchange"
    // JSON key for optional pseudonym field.
    private const val KEY_INCLUDE_PSEUDONYM = "includePseudonym"
    // JSON key for optional location field.
    private const val KEY_SHARE_LOCATION = "shareLocation"
    // JSON key for trust noise variance.
    private const val KEY_TRUST_NOISE_VARIANCE = "trustNoiseVariance"
    // JSON key for auto-delete enable.
    private const val KEY_AUTODELETE_ENABLED = "autodeleteEnabled"
    // JSON key for auto-delete trust threshold.
    private const val KEY_AUTODELETE_TRUST_THRESHOLD = "autodeleteTrustThreshold"
    // JSON key for auto-delete age in days.
    private const val KEY_AUTODELETE_AGE_DAYS = "autodeleteAgeDays"
    // JSON key for exchange cooldown in seconds.
    private const val KEY_EXCHANGE_COOLDOWN_SECONDS = "exchangeCooldownSeconds"
    // JSON key for random exchange selection.
    private const val KEY_RANDOM_EXCHANGE = "randomExchange"
    // JSON key for backoff enable.
    private const val KEY_USE_BACKOFF = "useBackoff"
    // JSON key for backoff attempt base in ms.
    private const val KEY_BACKOFF_ATTEMPT_MILLIS = "backoffAttemptMillis"
    // JSON key for backoff max in ms.
    private const val KEY_BACKOFF_MAX_MILLIS = "backoffMaxMillis"
    // JSON key for timebound period in days.
    private const val KEY_TIMEBOUND_PERIOD_DAYS = "timeboundPeriodDays"
    // JSON key for inbound-session grace window in milliseconds.
    private const val KEY_INBOUND_SESSION_GRACE_MS = "inboundSessionGraceMs"
    // JSON key for telemetry enabled default.
    private const val KEY_TELEMETRY_ENABLED = "telemetryEnabled"
    // JSON key for telemetry server URL.
    private const val KEY_TELEMETRY_SERVER_URL = "telemetryServerUrl"
    // JSON key for telemetry batch size.
    private const val KEY_TELEMETRY_BATCH_SIZE = "telemetryBatchSize"
    // JSON key for telemetry flush interval.
    private const val KEY_TELEMETRY_FLUSH_INTERVAL_MS = "telemetryFlushIntervalMs"
    // JSON key for telemetry API token.
    private const val KEY_TELEMETRY_API_TOKEN = "telemetryApiToken"
    // JSON key for OTA enabled.
    private const val KEY_OTA_ENABLED = "otaEnabled"
    // JSON key for OTA check interval.
    private const val KEY_OTA_CHECK_INTERVAL_MS = "otaCheckIntervalMs"
    // JSON key for OTA WiFi-only mode.
    private const val KEY_OTA_WIFI_ONLY = "otaWifiOnly"
    // JSON key for OTA auto-download.
    private const val KEY_OTA_AUTO_DOWNLOAD = "otaAutoDownload"

    // Cached JSON config to avoid repeated disk reads.
    @Volatile
    private var cachedConfig: JSONObject? = null
    // Lock for thread-safe initialization.
    private val lock = Any()

    /**
     * Load and cache the config JSON from assets.
     */
    private fun loadConfig(context: Context): JSONObject {
        // Return cached config if already loaded.
        cachedConfig?.let { return it }
        // Synchronize to avoid double loads.
        synchronized(lock) {
            // Double-check after acquiring the lock.
            cachedConfig?.let { return it }
            try {
                // Read the config file from assets.
                val jsonText = context.assets.open(CONFIG_FILE_NAME).bufferedReader().use { it.readText() }
                // Parse the JSON.
                val parsed = JSONObject(jsonText)
                // Cache it for future calls.
                cachedConfig = parsed
                // Log successful load.
                Timber.i("Loaded config from assets/$CONFIG_FILE_NAME")
                Log.i(LOG_TAG, "Loaded config from assets/$CONFIG_FILE_NAME")
                // Return the parsed object.
                return parsed
            } catch (e: Exception) {
                // Log the error and fail loudly.
                Timber.e(e, "Failed to load config from assets/$CONFIG_FILE_NAME")
                Log.e(LOG_TAG, "Failed to load config from assets/$CONFIG_FILE_NAME", e)
                throw IllegalStateException("Missing or invalid config.json in assets", e)
            }
        }
    }

    /**
     * Read the GATT read fallback delay from config.
     */
    fun gattReadFallbackDelayMs(context: Context): Long {
        // Load the config JSON.
        val config = loadConfig(context)
        // Ensure the key exists and is numeric.
        if (!config.has(KEY_GATT_READ_FALLBACK_DELAY_MS)) {
            // Fail loudly if missing.
            val message = "Config missing key: $KEY_GATT_READ_FALLBACK_DELAY_MS"
            Timber.e(message)
            Log.e(LOG_TAG, message)
            throw IllegalStateException(message)
        }
        // Return the configured delay.
        return config.getLong(KEY_GATT_READ_FALLBACK_DELAY_MS)
    }

    /**
     * Read the initial write delay after CCCD enable.
     */
    fun initialWriteDelayMs(context: Context): Long {
        // Load the config JSON.
        val config = loadConfig(context)
        // Ensure the key exists and is numeric.
        if (!config.has(KEY_INITIAL_WRITE_DELAY_MS)) {
            // Fail loudly if missing.
            val message = "Config missing key: $KEY_INITIAL_WRITE_DELAY_MS"
            Timber.e(message)
            Log.e(LOG_TAG, message)
            throw IllegalStateException(message)
        }
        // Return the configured delay.
        return config.getLong(KEY_INITIAL_WRITE_DELAY_MS)
    }

    /**
     * Read the maximum GATT attribute length.
     */
    fun gattMaxAttributeLength(context: Context): Int {
        // Load the config JSON.
        val config = loadConfig(context)
        // Ensure the key exists and is numeric.
        if (!config.has(KEY_GATT_MAX_ATTRIBUTE_LENGTH)) {
            // Fail loudly if missing.
            val message = "Config missing key: $KEY_GATT_MAX_ATTRIBUTE_LENGTH"
            Timber.e(message)
            Log.e(LOG_TAG, message)
            throw IllegalStateException(message)
        }
        // Return the configured max length.
        return config.getInt(KEY_GATT_MAX_ATTRIBUTE_LENGTH)
    }

    /**
     * Read the maximum number of messages allowed per exchange.
     */
    fun maxMessagesPerExchange(context: Context): Int {
        val config = loadConfig(context)
        if (!config.has(KEY_MAX_MESSAGES_PER_EXCHANGE)) {
            val message = "Config missing key: $KEY_MAX_MESSAGES_PER_EXCHANGE"
            Timber.e(message)
            Log.e(LOG_TAG, message)
            throw IllegalStateException(message)
        }
        return config.getInt(KEY_MAX_MESSAGES_PER_EXCHANGE)
    }

    /**
     * Read the exchange session timeout.
     */
    fun exchangeSessionTimeoutMs(context: Context): Long {
        val config = loadConfig(context)
        if (!config.has(KEY_EXCHANGE_SESSION_TIMEOUT_MS)) {
            val message = "Config missing key: $KEY_EXCHANGE_SESSION_TIMEOUT_MS"
            Timber.e(message)
            Log.e(LOG_TAG, message)
            throw IllegalStateException(message)
        }
        return config.getLong(KEY_EXCHANGE_SESSION_TIMEOUT_MS)
    }

    /**
     * Read the BLE MTU size to request on connections.
     */
    fun bleMtu(context: Context): Int {
        val config = loadConfig(context)
        if (!config.has(KEY_BLE_MTU)) {
            val message = "Config missing key: $KEY_BLE_MTU"
            Timber.e(message)
            Log.e(LOG_TAG, message)
            throw IllegalStateException(message)
        }
        return config.getInt(KEY_BLE_MTU)
    }

    /**
     * Read whether trust/PSI is enabled.
     */
    fun useTrust(context: Context): Boolean {
        val config = loadConfig(context)
        if (!config.has(KEY_USE_TRUST)) {
            val message = "Config missing key: $KEY_USE_TRUST"
            Timber.e(message)
            Log.e(LOG_TAG, message)
            throw IllegalStateException(message)
        }
        return config.getBoolean(KEY_USE_TRUST)
    }

    /**
     * Read minimum shared contacts required to allow an exchange.
     */
    fun minSharedContactsForExchange(context: Context): Int {
        val config = loadConfig(context)
        if (!config.has(KEY_MIN_SHARED_CONTACTS)) {
            val message = "Config missing key: $KEY_MIN_SHARED_CONTACTS"
            Timber.e(message)
            Log.e(LOG_TAG, message)
            throw IllegalStateException(message)
        }
        return config.getInt(KEY_MIN_SHARED_CONTACTS)
    }

    /**
     * Read whether pseudonyms are shared in legacy messages.
     */
    fun includePseudonym(context: Context): Boolean {
        val config = loadConfig(context)
        if (!config.has(KEY_INCLUDE_PSEUDONYM)) {
            val message = "Config missing key: $KEY_INCLUDE_PSEUDONYM"
            Timber.e(message)
            Log.e(LOG_TAG, message)
            throw IllegalStateException(message)
        }
        return config.getBoolean(KEY_INCLUDE_PSEUDONYM)
    }

    /**
     * Read whether location is shared in legacy messages.
     */
    fun shareLocation(context: Context): Boolean {
        val config = loadConfig(context)
        if (!config.has(KEY_SHARE_LOCATION)) {
            val message = "Config missing key: $KEY_SHARE_LOCATION"
            Timber.e(message)
            Log.e(LOG_TAG, message)
            throw IllegalStateException(message)
        }
        return config.getBoolean(KEY_SHARE_LOCATION)
    }

    /**
     * Read trust noise variance for legacy message serialization.
     */
    fun trustNoiseVariance(context: Context): Double {
        val config = loadConfig(context)
        if (!config.has(KEY_TRUST_NOISE_VARIANCE)) {
            val message = "Config missing key: $KEY_TRUST_NOISE_VARIANCE"
            Timber.e(message)
            Log.e(LOG_TAG, message)
            throw IllegalStateException(message)
        }
        return config.getDouble(KEY_TRUST_NOISE_VARIANCE)
    }

    /**
     * Read whether auto-delete is enabled.
     */
    fun autodeleteEnabled(context: Context): Boolean {
        val config = loadConfig(context)
        if (!config.has(KEY_AUTODELETE_ENABLED)) {
            val message = "Config missing key: $KEY_AUTODELETE_ENABLED"
            Timber.e(message)
            Log.e(LOG_TAG, message)
            throw IllegalStateException(message)
        }
        return config.getBoolean(KEY_AUTODELETE_ENABLED)
    }

    /**
     * Read auto-delete trust threshold.
     */
    fun autodeleteTrustThreshold(context: Context): Double {
        val config = loadConfig(context)
        if (!config.has(KEY_AUTODELETE_TRUST_THRESHOLD)) {
            val message = "Config missing key: $KEY_AUTODELETE_TRUST_THRESHOLD"
            Timber.e(message)
            Log.e(LOG_TAG, message)
            throw IllegalStateException(message)
        }
        return config.getDouble(KEY_AUTODELETE_TRUST_THRESHOLD)
    }

    /**
     * Read auto-delete age in days.
     */
    fun autodeleteAgeDays(context: Context): Int {
        val config = loadConfig(context)
        if (!config.has(KEY_AUTODELETE_AGE_DAYS)) {
            val message = "Config missing key: $KEY_AUTODELETE_AGE_DAYS"
            Timber.e(message)
            Log.e(LOG_TAG, message)
            throw IllegalStateException(message)
        }
        return config.getInt(KEY_AUTODELETE_AGE_DAYS)
    }

    /**
     * Read exchange cooldown in seconds.
     */
    fun exchangeCooldownSeconds(context: Context): Int {
        val config = loadConfig(context)
        if (!config.has(KEY_EXCHANGE_COOLDOWN_SECONDS)) {
            val message = "Config missing key: $KEY_EXCHANGE_COOLDOWN_SECONDS"
            Timber.e(message)
            Log.e(LOG_TAG, message)
            throw IllegalStateException(message)
        }
        return config.getInt(KEY_EXCHANGE_COOLDOWN_SECONDS)
    }

    /**
     * Read whether random exchange selection is enabled.
     */
    fun randomExchange(context: Context): Boolean {
        val config = loadConfig(context)
        if (!config.has(KEY_RANDOM_EXCHANGE)) {
            val message = "Config missing key: $KEY_RANDOM_EXCHANGE"
            Timber.e(message)
            Log.e(LOG_TAG, message)
            throw IllegalStateException(message)
        }
        return config.getBoolean(KEY_RANDOM_EXCHANGE)
    }

    /**
     * Read whether backoff logic is enabled.
     */
    fun useBackoff(context: Context): Boolean {
        val config = loadConfig(context)
        if (!config.has(KEY_USE_BACKOFF)) {
            val message = "Config missing key: $KEY_USE_BACKOFF"
            Timber.e(message)
            Log.e(LOG_TAG, message)
            throw IllegalStateException(message)
        }
        return config.getBoolean(KEY_USE_BACKOFF)
    }

    /**
     * Read backoff attempt base in milliseconds.
     */
    fun backoffAttemptMillis(context: Context): Long {
        val config = loadConfig(context)
        if (!config.has(KEY_BACKOFF_ATTEMPT_MILLIS)) {
            val message = "Config missing key: $KEY_BACKOFF_ATTEMPT_MILLIS"
            Timber.e(message)
            Log.e(LOG_TAG, message)
            throw IllegalStateException(message)
        }
        return config.getLong(KEY_BACKOFF_ATTEMPT_MILLIS)
    }

    /**
     * Read backoff maximum delay in milliseconds.
     */
    fun backoffMaxMillis(context: Context): Long {
        val config = loadConfig(context)
        if (!config.has(KEY_BACKOFF_MAX_MILLIS)) {
            val message = "Config missing key: $KEY_BACKOFF_MAX_MILLIS"
            Timber.e(message)
            Log.e(LOG_TAG, message)
            throw IllegalStateException(message)
        }
        return config.getLong(KEY_BACKOFF_MAX_MILLIS)
    }

    /**
     * Read timebound period in days.
     */
    fun timeboundPeriodDays(context: Context): Int {
        val config = loadConfig(context)
        if (!config.has(KEY_TIMEBOUND_PERIOD_DAYS)) {
            val message = "Config missing key: $KEY_TIMEBOUND_PERIOD_DAYS"
            Timber.e(message)
            Log.e(LOG_TAG, message)
            throw IllegalStateException(message)
        }
        return config.getInt(KEY_TIMEBOUND_PERIOD_DAYS)
    }

    /**
     * Read inbound session grace window in milliseconds.
     */
    fun inboundSessionGraceMs(context: Context): Long {
        // Load the config JSON.
        val config = loadConfig(context)
        // Ensure the key exists to avoid silent defaults.
        if (!config.has(KEY_INBOUND_SESSION_GRACE_MS)) {
            // Build a clear error message.
            val message = "Config missing key: $KEY_INBOUND_SESSION_GRACE_MS"
            // Log loudly for visibility.
            Timber.e(message)
            Log.e(LOG_TAG, message)
            // Fail fast so we never silently ignore the setting.
            throw IllegalStateException(message)
        }
        // Return the configured grace window.
        return config.getLong(KEY_INBOUND_SESSION_GRACE_MS)
    }

    /**
     * Read whether telemetry is enabled by default.
     */
    fun telemetryEnabled(context: Context): Boolean {
        val config = loadConfig(context)
        // Default to false if not specified (privacy-first).
        return config.optBoolean(KEY_TELEMETRY_ENABLED, false)
    }

    /**
     * Read telemetry server URL.
     */
    fun telemetryServerUrl(context: Context): String {
        val config = loadConfig(context)
        return config.optString(KEY_TELEMETRY_SERVER_URL, "")
    }

    /**
     * Read telemetry batch size.
     */
    fun telemetryBatchSize(context: Context): Int {
        val config = loadConfig(context)
        return config.optInt(KEY_TELEMETRY_BATCH_SIZE, 50)
    }

    /**
     * Read telemetry flush interval in milliseconds.
     */
    fun telemetryFlushIntervalMs(context: Context): Long {
        val config = loadConfig(context)
        return config.optLong(KEY_TELEMETRY_FLUSH_INTERVAL_MS, 60000L)
    }

    /**
     * Read telemetry API token.
     */
    fun telemetryApiToken(context: Context): String {
        val config = loadConfig(context)
        return config.optString(KEY_TELEMETRY_API_TOKEN, "")
    }

    /**
     * Check if OTA updates are enabled.
     */
    fun otaEnabled(context: Context): Boolean {
        val config = loadConfig(context)
        return config.optBoolean(KEY_OTA_ENABLED, false)
    }

    /**
     * Read OTA check interval in milliseconds.
     * Default: 4 hours.
     */
    fun otaCheckIntervalMs(context: Context): Long {
        val config = loadConfig(context)
        return config.optLong(KEY_OTA_CHECK_INTERVAL_MS, 4 * 60 * 60 * 1000L)
    }

    /**
     * Check if OTA downloads should only occur on WiFi.
     */
    fun otaWifiOnly(context: Context): Boolean {
        val config = loadConfig(context)
        return config.optBoolean(KEY_OTA_WIFI_ONLY, true)
    }

    /**
     * Check if OTA updates should auto-download when available.
     */
    fun otaAutoDownload(context: Context): Boolean {
        val config = loadConfig(context)
        return config.optBoolean(KEY_OTA_AUTO_DOWNLOAD, true)
    }
}
