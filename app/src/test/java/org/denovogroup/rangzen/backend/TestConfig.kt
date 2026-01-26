/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * Shared test configuration that reads actual values from config.json.
 * This ensures tests stay coupled to production config.
 */
package org.denovogroup.rangzen.backend

import java.io.File

/**
 * Test configuration that reads actual values from config.json.
 *
 * This ensures tests use the same values as production code,
 * so config changes don't silently break test assumptions.
 *
 * Uses simple regex parsing instead of Android's JSONObject to work
 * in pure JUnit tests without Android mocking.
 */
object TestConfig {

    private val configContent: String by lazy {
        loadConfigJson()
    }

    /**
     * Load config.json from the assets directory.
     */
    private fun loadConfigJson(): String {
        // Try multiple possible paths for config.json
        val possiblePaths = listOf(
            "src/main/assets/config.json",
            "app/src/main/assets/config.json",
            "../app/src/main/assets/config.json"
        )

        for (path in possiblePaths) {
            val file = File(path)
            if (file.exists()) {
                return file.readText()
            }
        }

        // Fallback: return defaults that match config.json
        // Update these if config.json changes!
        return """
            {
                "backoffAttemptMillis": 10000,
                "backoffMaxMillis": 320000,
                "exchangeSessionTimeoutMs": 30000,
                "trustNoiseVariance": 0.003
            }
        """.trimIndent()
    }

    /**
     * Parse a long value from the config content using regex.
     */
    private fun parseLong(key: String, default: Long): Long {
        val regex = """"$key"\s*:\s*(\d+)""".toRegex()
        return regex.find(configContent)?.groupValues?.get(1)?.toLongOrNull() ?: default
    }

    /**
     * Parse a double value from the config content using regex.
     */
    private fun parseDouble(key: String, default: Double): Double {
        val regex = """"$key"\s*:\s*([\d.]+)""".toRegex()
        return regex.find(configContent)?.groupValues?.get(1)?.toDoubleOrNull() ?: default
    }

    // ========================================================================
    // Backoff configuration
    // ========================================================================

    /** Base delay for exponential backoff (milliseconds) */
    val backoffAttemptMillis: Long
        get() = parseLong("backoffAttemptMillis", 10_000L)

    /** Maximum backoff delay cap (milliseconds) */
    val backoffMaxMillis: Long
        get() = parseLong("backoffMaxMillis", 320_000L)

    // ========================================================================
    // Exchange configuration
    // ========================================================================

    /** Exchange session timeout (milliseconds) */
    val exchangeSessionTimeoutMs: Long
        get() = parseLong("exchangeSessionTimeoutMs", 30_000L)

    // ========================================================================
    // Trust configuration
    // ========================================================================

    /** Trust noise variance (Casific design: 0.003) */
    val trustNoiseVariance: Double
        get() = parseDouble("trustNoiseVariance", 0.003)
}
