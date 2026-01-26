/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * BLE Mutual-Code Pairing Protocol
 *
 * Implements a secure pairing handshake using human-verifiable short codes.
 * Both users must see each other's codes and enter them to complete pairing.
 */
package org.denovogroup.rangzen.backend.ble

import android.util.Base64
import org.json.JSONObject
import timber.log.Timber
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Protocol for mutual-code BLE pairing.
 *
 * Flow:
 * 1. Both devices generate a 6-digit pairing code
 * 2. Devices discover each other via BLE and exchange codes
 * 3. Users visually verify codes match what they see on other device
 * 4. Users enter the code they see on the other device
 * 5. Devices exchange public IDs after mutual verification
 */
object BlePairingProtocol {

    private const val TAG = "BlePairingProtocol"

    // Protocol version for future compatibility
    private const val PROTOCOL_VERSION = 1

    // Message types
    const val MSG_PAIRING_ANNOUNCE = "pairing_announce"  // Broadcast code + short ID
    const val MSG_PAIRING_REQUEST = "pairing_request"    // Request to pair with a specific device
    const val MSG_PAIRING_VERIFY = "pairing_verify"      // Send entered code for verification
    const val MSG_PAIRING_CONFIRM = "pairing_confirm"    // Confirm pairing + exchange public ID
    const val MSG_PAIRING_REJECT = "pairing_reject"      // Reject pairing attempt

    // Code validity window (5 minutes)
    private const val CODE_VALIDITY_MS = 5 * 60 * 1000L

    /**
     * Data class representing a pairing session.
     */
    data class PairingSession(
        val myCode: String,                    // Our 6-digit code
        val myPublicId: String,                // Our Base64-encoded public ID
        val myShortId: String,                 // Short identifier for display (first 4 chars of hash)
        val createdAt: Long,                   // When this session was created
        var peerCode: String? = null,          // Code entered by user (from peer's display)
        var peerPublicId: String? = null,      // Peer's public ID after verification
        var peerShortId: String? = null,       // Peer's short identifier
        var verified: Boolean = false          // Whether mutual verification is complete
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - createdAt > CODE_VALIDITY_MS
    }

    /**
     * Data class for a discovered pairing peer.
     */
    data class PairingPeer(
        val address: String,                   // BLE device address
        val shortId: String,                   // Short identifier (4 chars)
        val code: String,                      // Their 6-digit pairing code
        val rssi: Int,                         // Signal strength
        val lastSeen: Long                     // Last time we heard from them
    )

    /**
     * Generate a new pairing session.
     */
    fun createSession(myPublicId: String): PairingSession {
        val code = generatePairingCode()
        val shortId = generateShortId(myPublicId)

        return PairingSession(
            myCode = code,
            myPublicId = myPublicId,
            myShortId = shortId,
            createdAt = System.currentTimeMillis()
        )
    }

    /**
     * Generate a 6-digit pairing code.
     */
    fun generatePairingCode(): String {
        val random = SecureRandom()
        val code = random.nextInt(1000000)
        return String.format("%06d", code)
    }

    /**
     * Generate a short identifier from a public ID (for display in device list).
     * Uses first 4 characters of SHA-256 hash (hex) for readability.
     */
    fun generateShortId(publicId: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(publicId.toByteArray())
            hash.take(2).joinToString("") { String.format("%02X", it) }
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate short ID")
            publicId.take(4).uppercase()
        }
    }

    /**
     * Create a pairing announcement message.
     * This is broadcast to let other devices know we're available for pairing.
     */
    fun createAnnounceMessage(session: PairingSession): ByteArray {
        val json = JSONObject().apply {
            put("v", PROTOCOL_VERSION)
            put("type", MSG_PAIRING_ANNOUNCE)
            put("code", session.myCode)
            put("short_id", session.myShortId)
            put("ts", System.currentTimeMillis())
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Parse a pairing announcement message.
     */
    fun parseAnnounceMessage(data: ByteArray): Triple<String, String, Long>? {
        return try {
            val json = JSONObject(String(data, Charsets.UTF_8))
            if (json.optString("type") != MSG_PAIRING_ANNOUNCE) return null

            val code = json.getString("code")
            val shortId = json.getString("short_id")
            val timestamp = json.getLong("ts")

            Triple(code, shortId, timestamp)
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse announce message")
            null
        }
    }

    /**
     * Create a pairing verification message.
     * Sent when user has entered the code from the other device.
     * Includes our public ID so the receiver can complete immediately upon sending CONFIRM.
     */
    fun createVerifyMessage(session: PairingSession, enteredCode: String): ByteArray {
        val json = JSONObject().apply {
            put("v", PROTOCOL_VERSION)
            put("type", MSG_PAIRING_VERIFY)
            put("my_code", session.myCode)
            put("my_short_id", session.myShortId)
            put("my_public_id", session.myPublicId)  // Include public ID for immediate completion
            put("entered_code", enteredCode)  // Code we entered (should match their myCode)
            put("ts", System.currentTimeMillis())
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Data class for parsed VERIFY message.
     */
    data class VerifyMessageData(
        val theirCode: String,
        val theirShortId: String,
        val theirPublicId: String?,  // May be null for older clients
        val enteredCode: String
    )

    /**
     * Parse a verification message and check if codes match.
     * Returns VerifyMessageData or null if invalid.
     */
    fun parseVerifyMessage(data: ByteArray): VerifyMessageData? {
        return try {
            val json = JSONObject(String(data, Charsets.UTF_8))
            if (json.optString("type") != MSG_PAIRING_VERIFY) return null

            val theirCode = json.getString("my_code")
            val theirShortId = json.getString("my_short_id")
            val theirPublicId = json.optString("my_public_id").ifEmpty { null }
            val enteredCode = json.getString("entered_code")

            VerifyMessageData(theirCode, theirShortId, theirPublicId, enteredCode)
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse verify message")
            null
        }
    }

    /**
     * Create a pairing confirmation message.
     * Sent after both codes have been verified to exchange public IDs.
     */
    fun createConfirmMessage(session: PairingSession): ByteArray {
        val json = JSONObject().apply {
            put("v", PROTOCOL_VERSION)
            put("type", MSG_PAIRING_CONFIRM)
            put("public_id", session.myPublicId)
            put("short_id", session.myShortId)
            put("ts", System.currentTimeMillis())
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Parse a confirmation message.
     * Returns (publicId, shortId) or null if invalid.
     */
    fun parseConfirmMessage(data: ByteArray): Pair<String, String>? {
        return try {
            val json = JSONObject(String(data, Charsets.UTF_8))
            if (json.optString("type") != MSG_PAIRING_CONFIRM) return null

            val publicId = json.getString("public_id")
            val shortId = json.getString("short_id")

            Pair(publicId, shortId)
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse confirm message")
            null
        }
    }

    /**
     * Create a rejection message.
     */
    fun createRejectMessage(reason: String): ByteArray {
        val json = JSONObject().apply {
            put("v", PROTOCOL_VERSION)
            put("type", MSG_PAIRING_REJECT)
            put("reason", reason)
            put("ts", System.currentTimeMillis())
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Check if a message is a rejection.
     */
    fun isRejectMessage(data: ByteArray): Boolean {
        return try {
            val json = JSONObject(String(data, Charsets.UTF_8))
            json.optString("type") == MSG_PAIRING_REJECT
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get the message type from raw data.
     */
    fun getMessageType(data: ByteArray): String? {
        return try {
            val json = JSONObject(String(data, Charsets.UTF_8))
            json.optString("type").ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }
}
