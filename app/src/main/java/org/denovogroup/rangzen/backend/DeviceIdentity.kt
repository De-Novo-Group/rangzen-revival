/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * Privacy-preserving device identity for Murmur.
 * 
 * IMPORTANT: Do NOT use Bluetooth MAC, Android ID, or any hardware identifiers
 * for device identity. These can be used to track and deanonymize users.
 * 
 * Instead, we derive a stable device ID from the app's cryptographic keypair:
 * - Unique per device (each device has unique keypair)
 * - Stable across app restarts (keypair persisted)
 * - Privacy-preserving (cannot be correlated to hardware)
 * - Cryptographically tied to identity (same key = same device)
 */
package org.denovogroup.rangzen.backend

import android.content.Context
import timber.log.Timber
import java.security.MessageDigest

/**
 * Provides privacy-preserving device identity.
 * 
 * The device ID is derived from the app's public key using SHA-256.
 * This ensures:
 * 1. Uniqueness: Each device has a unique keypair
 * 2. Stability: Same keypair = same ID across restarts
 * 3. Privacy: Cannot be traced to hardware identifiers
 * 4. Security: Tied to cryptographic identity
 */
object DeviceIdentity {
    
    // Cached device ID (computed once per app session)
    @Volatile
    private var cachedDeviceId: String? = null
    
    /**
     * Get the privacy-preserving device ID.
     * 
     * This ID is:
     * - Derived from SHA-256(public_key)
     * - Truncated to 16 hex characters for brevity
     * - Safe to share over network (no hardware identifiers)
     * 
     * @param context Android context for accessing crypto storage
     * @return 16-character hex device ID
     */
    fun getDeviceId(context: Context): String {
        cachedDeviceId?.let { return it }
        
        synchronized(this) {
            // Double-check after acquiring lock
            cachedDeviceId?.let { return it }
            
            val deviceId = computeDeviceId(context)
            cachedDeviceId = deviceId
            return deviceId
        }
    }
    
    /**
     * Compute the device ID from the cryptographic keypair.
     * 
     * Uses the public key from the user's identity keypair stored in FriendStore.
     */
    private fun computeDeviceId(context: Context): String {
        return try {
            // Get the identity keypair from FriendStore
            val friendStore = FriendStore.getInstance(context)
            val keypair = friendStore.getOrCreateIdentity()
            
            // Get the encoded public key
            val publicKey = Crypto.generatePublicID(keypair)
            
            if (publicKey != null) {
                // Hash the public key to get a stable, privacy-preserving ID
                val hash = sha256(publicKey)
                // Take first 16 hex chars (64 bits) for brevity
                val deviceId = hash.take(16)
                Timber.i("Device ID derived from public key: ${deviceId.take(8)}...")
                deviceId
            } else {
                // Fallback: Generate a random ID if key encoding fails
                Timber.w("Failed to encode public key, generating random device ID")
                generateRandomId()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to compute device ID, using random fallback")
            generateRandomId()
        }
    }
    
    /**
     * Generate a random device ID as fallback.
     * This is only used if the crypto system isn't initialized yet.
     */
    private fun generateRandomId(): String {
        val bytes = ByteArray(8)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Compute SHA-256 hash of byte array and return as hex string.
     */
    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Invalidate the cached device ID.
     * Call this if the keypair is regenerated (should be rare).
     */
    fun invalidateCache() {
        cachedDeviceId = null
        Timber.d("Device ID cache invalidated")
    }

    /**
     * Compute device ID prefix from a public key byte array.
     *
     * This allows matching a peer's deviceId with a friend's public key.
     * The prefix is the first 8 hex characters of SHA-256(publicKey).
     *
     * @param publicKey The public key bytes
     * @return 8-character hex device ID prefix
     */
    fun computeDeviceIdPrefix(publicKey: ByteArray): String {
        return sha256(publicKey).take(8)
    }
}
