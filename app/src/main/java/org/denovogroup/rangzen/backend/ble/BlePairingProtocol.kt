/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * BLE Secure Pairing Protocol v2
 *
 * Two-phase protocol ensuring visual verification:
 * Phase 1: Discovery using color+animal identifiers (no codes broadcast)
 * Phase 2: Mutual selection, then local code generation for visual verification
 */
package org.denovogroup.rangzen.backend.ble

import android.content.Context
import org.json.JSONObject
import timber.log.Timber
import java.security.SecureRandom

/**
 * Protocol for secure two-phase BLE pairing.
 *
 * Flow:
 * 1. Both devices display a friendly identifier (e.g., "Blue Tiger")
 * 2. Users verbally share their identifiers and find each other in the list
 * 3. Both users tap to select each other (mutual selection via BLE)
 * 4. AFTER mutual selection: verification codes are generated (NEVER broadcast)
 * 5. Users must physically look at each other's screens to read and enter codes
 * 6. Codes verified → public IDs exchanged → friends added
 *
 * Security improvement over v1:
 * - Verification codes are NEVER broadcast over BLE
 * - Codes only exist on screen after mutual selection
 * - Visual verification of physical presence is actually required
 */
object BlePairingProtocol {

    private const val TAG = "BlePairingProtocol"

    // Protocol version - v2 is breaking change from v1
    private const val PROTOCOL_VERSION = 2

    // Message types
    const val MSG_PAIRING_ANNOUNCE = "pairing_announce"  // Broadcast word identifier (no code!)
    const val MSG_PAIRING_SELECT = "pairing_select"      // Mutual selection handshake
    const val MSG_PAIRING_VERIFY = "pairing_verify"      // Send entered code for verification
    const val MSG_PAIRING_CONFIRM = "pairing_confirm"    // Confirm pairing + exchange public ID
    const val MSG_PAIRING_REJECT = "pairing_reject"      // Reject pairing attempt

    // Code validity window (5 minutes)
    private const val CODE_VALIDITY_MS = 5 * 60 * 1000L

    // Wordlist sizes
    private const val NUM_COLORS = 8
    private const val NUM_ANIMALS = 32

    /**
     * Data class representing a pairing session.
     */
    data class PairingSession(
        val myWordId: String,                  // Our color+animal identifier (e.g., "Blue Tiger")
        val myPublicId: String,                // Our Base64-encoded public ID
        val myShortId: String,                 // Short hex identifier (for debugging)
        val createdAt: Long,                   // When this session was created
        var selectedPeerWordId: String? = null,  // Word ID of peer we selected
        var selectedPeerAddress: String? = null, // BLE address of peer we selected
        var peerSelectedUs: Boolean = false,     // Whether peer has selected us
        var verificationCode: String? = null,    // Generated AFTER mutual selection (never broadcast)
        var peerCode: String? = null,            // Code entered by user (from peer's display)
        var peerPublicId: String? = null,        // Peer's public ID after verification
        var verified: Boolean = false            // Whether mutual verification is complete
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - createdAt > CODE_VALIDITY_MS

        /** Check if mutual selection is complete (both selected each other) */
        fun isMutuallySelected(): Boolean = selectedPeerWordId != null && peerSelectedUs

        /** Generate verification code after mutual selection */
        fun generateVerificationCode() {
            if (verificationCode == null && isMutuallySelected()) {
                verificationCode = generatePairingCode()
                Timber.d("$TAG: Generated verification code after mutual selection")
            }
        }
    }

    /**
     * Data class for a discovered pairing peer.
     */
    data class PairingPeer(
        val address: String,                   // BLE device address
        val wordId: String,                    // Color+animal identifier (e.g., "Gold Lion")
        val shortId: String,                   // Short hex identifier
        val rssi: Int,                         // Signal strength
        val lastSeen: Long                     // Last time we heard from them
    )

    /**
     * Generate a new pairing session with random word identifier.
     * PRIVACY: Both wordId and shortId are randomized each session
     * to prevent device fingerprinting across pairing attempts.
     */
    fun createSession(myPublicId: String, context: Context): PairingSession {
        val wordId = generateWordIdentifier(context)
        val shortId = generateShortId()

        return PairingSession(
            myWordId = wordId,
            myPublicId = myPublicId,
            myShortId = shortId,
            createdAt = System.currentTimeMillis()
        )
    }

    /**
     * Generate a 6-digit pairing code.
     * Called ONLY after mutual selection, never broadcast.
     */
    fun generatePairingCode(): String {
        val random = SecureRandom()
        val code = random.nextInt(1000000)
        return String.format("%06d", code)
    }

    /**
     * Generate a random color+animal identifier for this pairing session.
     * PRIVACY: Randomized each session to prevent device fingerprinting.
     * Returns localized strings based on device locale.
     */
    fun generateWordIdentifier(context: Context): String {
        return try {
            val random = SecureRandom()

            // Randomly select color and animal
            val colorIndex = random.nextInt(NUM_COLORS)
            val animalIndex = random.nextInt(NUM_ANIMALS)

            // Get localized strings from resources
            val colors = context.resources.getStringArray(
                context.resources.getIdentifier("pairing_colors", "array", context.packageName)
            )
            val animals = context.resources.getStringArray(
                context.resources.getIdentifier("pairing_animals", "array", context.packageName)
            )

            "${colors[colorIndex]} ${animals[animalIndex]}"
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate word identifier, falling back to hex")
            generateShortId()
        }
    }

    /**
     * Generate a random short hex identifier for this pairing session.
     * PRIVACY: Randomized each session to prevent device fingerprinting.
     */
    fun generateShortId(): String {
        val random = SecureRandom()
        val bytes = ByteArray(2)
        random.nextBytes(bytes)
        return bytes.joinToString("") { String.format("%02X", it) }
    }

    /**
     * Create a pairing announcement message.
     * Broadcasts word identifier only - NO verification code!
     */
    fun createAnnounceMessage(session: PairingSession): ByteArray {
        val json = JSONObject().apply {
            put("v", PROTOCOL_VERSION)
            put("type", MSG_PAIRING_ANNOUNCE)
            put("word_id", session.myWordId)
            put("short_id", session.myShortId)
            put("ts", System.currentTimeMillis())
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Parse a pairing announcement message.
     * Returns (wordId, shortId, timestamp) or null if invalid.
     */
    fun parseAnnounceMessage(data: ByteArray): Triple<String, String, Long>? {
        return try {
            val json = JSONObject(String(data, Charsets.UTF_8))
            if (json.optString("type") != MSG_PAIRING_ANNOUNCE) return null

            val wordId = json.getString("word_id")
            val shortId = json.getString("short_id")
            val timestamp = json.getLong("ts")

            Triple(wordId, shortId, timestamp)
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse announce message")
            null
        }
    }

    /**
     * Create a selection message.
     * Sent when user taps a peer in the list to initiate mutual selection.
     */
    fun createSelectMessage(session: PairingSession, peerWordId: String): ByteArray {
        val json = JSONObject().apply {
            put("v", PROTOCOL_VERSION)
            put("type", MSG_PAIRING_SELECT)
            put("my_word_id", session.myWordId)
            put("my_short_id", session.myShortId)
            put("peer_word_id", peerWordId)  // Who we're selecting
            put("ts", System.currentTimeMillis())
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Data class for parsed SELECT message.
     */
    data class SelectMessageData(
        val theirWordId: String,
        val theirShortId: String,
        val selectedPeerWordId: String  // Who they selected (should be us if mutual)
    )

    /**
     * Parse a selection message.
     */
    fun parseSelectMessage(data: ByteArray): SelectMessageData? {
        return try {
            val json = JSONObject(String(data, Charsets.UTF_8))
            if (json.optString("type") != MSG_PAIRING_SELECT) return null

            val theirWordId = json.getString("my_word_id")
            val theirShortId = json.getString("my_short_id")
            val selectedPeerWordId = json.getString("peer_word_id")

            SelectMessageData(theirWordId, theirShortId, selectedPeerWordId)
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse select message")
            null
        }
    }

    /**
     * Create a pairing verification message.
     * Sent when user has entered the code from the other device's screen.
     * Includes our public ID so the receiver can complete immediately upon sending CONFIRM.
     */
    fun createVerifyMessage(session: PairingSession, enteredCode: String): ByteArray {
        val json = JSONObject().apply {
            put("v", PROTOCOL_VERSION)
            put("type", MSG_PAIRING_VERIFY)
            put("my_word_id", session.myWordId)
            put("my_short_id", session.myShortId)
            put("my_public_id", session.myPublicId)
            put("entered_code", enteredCode)  // Code we entered (should match their verification code)
            put("ts", System.currentTimeMillis())
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Data class for parsed VERIFY message.
     */
    data class VerifyMessageData(
        val theirWordId: String,
        val theirShortId: String,
        val theirPublicId: String?,  // May be null for older clients
        val enteredCode: String
    )

    /**
     * Parse a verification message.
     */
    fun parseVerifyMessage(data: ByteArray): VerifyMessageData? {
        return try {
            val json = JSONObject(String(data, Charsets.UTF_8))
            if (json.optString("type") != MSG_PAIRING_VERIFY) return null

            val theirWordId = json.getString("my_word_id")
            val theirShortId = json.getString("my_short_id")
            val theirPublicId = json.optString("my_public_id").ifEmpty { null }
            val enteredCode = json.getString("entered_code")

            VerifyMessageData(theirWordId, theirShortId, theirPublicId, enteredCode)
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse verify message")
            null
        }
    }

    /**
     * Create a pairing confirmation message.
     * Sent after code verification succeeds to exchange public IDs.
     */
    fun createConfirmMessage(session: PairingSession): ByteArray {
        val json = JSONObject().apply {
            put("v", PROTOCOL_VERSION)
            put("type", MSG_PAIRING_CONFIRM)
            put("public_id", session.myPublicId)
            put("word_id", session.myWordId)
            put("short_id", session.myShortId)
            put("ts", System.currentTimeMillis())
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Parse a confirmation message.
     * Returns (publicId, wordId, shortId) or null if invalid.
     */
    fun parseConfirmMessage(data: ByteArray): Triple<String, String, String>? {
        return try {
            val json = JSONObject(String(data, Charsets.UTF_8))
            if (json.optString("type") != MSG_PAIRING_CONFIRM) return null

            val publicId = json.getString("public_id")
            val wordId = json.getString("word_id")
            val shortId = json.getString("short_id")

            Triple(publicId, wordId, shortId)
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
