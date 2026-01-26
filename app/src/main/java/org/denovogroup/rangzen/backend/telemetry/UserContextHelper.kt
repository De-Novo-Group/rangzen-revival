package org.denovogroup.rangzen.backend.telemetry

import android.content.Context

/**
 * Helper to get user context for telemetry events.
 */
object UserContextHelper {

    private const val RANGZEN_PREFS = "org.denovogroup.rangzen"
    private const val PREF_DISPLAY_NAME = "default_pseudonym"

    /**
     * Get the user's display name for telemetry context.
     * Returns "Anonymous" if no name is set.
     */
    fun getDisplayName(context: Context): String {
        return try {
            context.getSharedPreferences(RANGZEN_PREFS, Context.MODE_PRIVATE)
                .getString(PREF_DISPLAY_NAME, null) ?: "Anonymous"
        } catch (e: Exception) {
            "Anonymous"
        }
    }

    /**
     * Get user context as a map for telemetry payload.
     */
    fun getContext(context: Context): Map<String, Any> {
        return mapOf("display_name" to getDisplayName(context))
    }
}
