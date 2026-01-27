/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * Location Helper for telemetry and debugging.
 * 
 * Uses standard Android LocationManager (no Google Play Services dependency).
 * This ensures the app works in regions without Play Services.
 * 
 * Provides:
 * - Current device location (for QA telemetry)
 * - Location Services status check
 */
package org.denovogroup.rangzen.backend.telemetry

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * Helper class for location-related functionality.
 * 
 * Uses standard Android LocationManager (no Google Play Services required).
 * 
 * Used for:
 * 1. QA telemetry - share location of each connection for debugging
 * 2. Checking if Location Services are enabled (required for WiFi Direct)
 */
class LocationHelper(private val context: Context) {

    companion object {
        // Location data keys for telemetry payload
        const val KEY_LATITUDE = "lat"
        const val KEY_LONGITUDE = "lon"
        const val KEY_ACCURACY = "accuracy_m"
        const val KEY_LOCATION_AGE_MS = "location_age_ms"
        const val KEY_LOCATION_SOURCE = "location_source"
        
        // Location sources
        const val SOURCE_GPS = "gps"
        const val SOURCE_NETWORK = "network"
        const val SOURCE_PASSIVE = "passive"
        const val SOURCE_UNKNOWN = "unknown"
        
        // Timeout for getting fresh location
        const val LOCATION_TIMEOUT_MS = 5_000L
        
        // Max age for "recent" location (5 minutes)
        const val MAX_RECENT_AGE_MS = 5 * 60 * 1000L
    }
    
    private val locationManager: LocationManager? by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }
    
    /**
     * Check if Location Services are enabled.
     * 
     * WiFi Direct discovery REQUIRES Location Services to be ON (not just permission granted).
     * This is a common failure mode where discovery silently returns no peers.
     */
    fun isLocationServicesEnabled(): Boolean {
        val lm = locationManager ?: return false
        
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lm.isLocationEnabled
            } else {
                @Suppress("DEPRECATION")
                val gpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                val networkEnabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                gpsEnabled || networkEnabled
            }
        } catch (e: Exception) {
            Timber.e(e, "Error checking location services status")
            false
        }
    }
    
    /**
     * Check if we have location permission.
     */
    fun hasLocationPermission(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        val coarseLocation = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        return fineLocation || coarseLocation
    }
    
    /**
     * Create an intent to open Location Settings.
     * Use this to prompt the user to enable Location Services.
     */
    fun getLocationSettingsIntent(): Intent {
        return Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
    }
    
    /**
     * Get the current location for telemetry purposes.
     * Returns null if location is unavailable or permission denied.
     * 
     * This is for QA/debugging only - we want to understand the physical
     * context of exchanges (indoor vs outdoor, urban vs rural, etc.)
     */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): LocationData? = withContext(Dispatchers.IO) {
        if (!hasLocationPermission()) {
            Timber.v("No location permission for telemetry")
            return@withContext null
        }
        
        if (!isLocationServicesEnabled()) {
            Timber.v("Location services disabled")
            return@withContext null
        }
        
        try {
            // Try to get last known location from any provider
            val lastLocation = getBestLastKnownLocationInternal()

            // If last location is recent enough (< 5 minutes), use it
            if (lastLocation != null && isLocationRecent(lastLocation)) {
                return@withContext lastLocation
            }

            // If no recent location, return whatever we have (or null)
            lastLocation
        } catch (e: Exception) {
            Timber.w(e, "Failed to get location for telemetry")
            null
        }
    }
    
    /**
     * Get the best last known location from all available providers.
     * This is a synchronous call that returns cached location data.
     * Safe to call from any thread.
     */
    @SuppressLint("MissingPermission")
    fun getLastKnownLocation(): LocationData? {
        if (!hasLocationPermission()) return null
        if (!isLocationServicesEnabled()) return null
        return getBestLastKnownLocationInternal()
    }

    /**
     * Internal: Get the best last known location from all available providers.
     */
    @SuppressLint("MissingPermission")
    private fun getBestLastKnownLocationInternal(): LocationData? {
        val lm = locationManager ?: return null
        
        var bestLocation: Location? = null
        var bestAccuracy = Float.MAX_VALUE
        var bestTime = 0L
        
        // Check all providers
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        
        for (provider in providers) {
            try {
                if (!lm.isProviderEnabled(provider)) continue
                
                val location = lm.getLastKnownLocation(provider) ?: continue
                
                // Prefer more recent and more accurate locations
                val isBetterTime = location.time > bestTime
                val isBetterAccuracy = location.accuracy < bestAccuracy
                
                if (bestLocation == null || isBetterTime || (isBetterAccuracy && location.time >= bestTime - 60_000)) {
                    bestLocation = location
                    bestAccuracy = location.accuracy
                    bestTime = location.time
                }
            } catch (e: SecurityException) {
                Timber.v("No permission for provider: $provider")
            } catch (e: Exception) {
                Timber.v("Error getting location from provider $provider: ${e.message}")
            }
        }
        
        return bestLocation?.let { locationToData(it) }
    }
    
    /**
     * Convert Android Location to our LocationData.
     */
    private fun locationToData(location: Location): LocationData {
        val source = when (location.provider) {
            LocationManager.GPS_PROVIDER -> SOURCE_GPS
            LocationManager.NETWORK_PROVIDER -> SOURCE_NETWORK
            LocationManager.PASSIVE_PROVIDER -> SOURCE_PASSIVE
            else -> SOURCE_UNKNOWN
        }
        
        return LocationData(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy,
            ageMs = System.currentTimeMillis() - location.time,
            source = source
        )
    }
    
    /**
     * Check if a location is recent enough.
     */
    private fun isLocationRecent(location: LocationData): Boolean {
        return location.ageMs < MAX_RECENT_AGE_MS
    }
    
    /**
     * Data class representing a location for telemetry.
     */
    data class LocationData(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float,
        val ageMs: Long,
        val source: String
    ) {
        /**
         * Heuristic for indoor detection based on accuracy.
         * Poor accuracy (>50m) often indicates indoor/obstructed GPS.
         */
        val isIndoor: Boolean get() = accuracyMeters > 50f

        /**
         * Convert to a map for telemetry payload.
         */
        fun toTelemetryMap(): Map<String, Any> {
            return mapOf(
                KEY_LATITUDE to latitude,
                KEY_LONGITUDE to longitude,
                KEY_ACCURACY to accuracyMeters,
                "is_indoor" to isIndoor
            )
        }
    }
}
