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
}
