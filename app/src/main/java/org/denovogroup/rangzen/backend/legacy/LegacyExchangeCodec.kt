/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * Legacy exchange codec aligned with the original Rangzen/Murmur Java protocol.
 */
package org.denovogroup.rangzen.backend.legacy

import android.content.Context
import android.util.Base64
import org.denovogroup.rangzen.backend.SecurityManager
import org.denovogroup.rangzen.objects.RangzenMessage
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder

object LegacyExchangeCodec {

    private const val LENGTH_PREFIX_BYTES = 4
    private const val MESSAGE_COUNT_KEY = "count"
    
    // Trust noise variance hard-locked to Casific's MurmurMessage.java design (VAR = 0.003).
    // Not configurable - this is a security constant aligned with the original app.
    private const val TRUST_NOISE_VARIANCE = 0.003

    fun encodeLengthValue(message: JSONObject): ByteArray {
        val payload = message.toString().toByteArray()
        val buffer = ByteBuffer.allocate(LENGTH_PREFIX_BYTES + payload.size)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(payload.size)
        buffer.put(payload)
        return buffer.array()
    }

    fun decodeLengthValue(payload: ByteArray): JSONObject? {
        if (payload.size < LENGTH_PREFIX_BYTES) {
            Timber.e("Legacy payload too small for length prefix: size=${payload.size}")
            return null
        }
        val buffer = ByteBuffer.wrap(payload)
        buffer.order(ByteOrder.BIG_ENDIAN)
        val expectedLength = buffer.int
        val remaining = payload.size - LENGTH_PREFIX_BYTES
        if (expectedLength != remaining) {
            Timber.e("Legacy length mismatch expected=$expectedLength actual=$remaining")
            return null
        }
        val jsonBytes = ByteArray(expectedLength)
        buffer.get(jsonBytes)
        return JSONObject(String(jsonBytes))
    }

    fun encodeExchangeInfo(count: Int): JSONObject {
        return JSONObject().put(MESSAGE_COUNT_KEY, count)
    }

    fun decodeExchangeInfo(json: JSONObject): Int {
        return json.optInt(MESSAGE_COUNT_KEY, 0)
    }

    fun encodeClientMessage(
        messages: List<JSONObject>,
        blindedFriends: List<ByteArray>,
        deviceIdHash: String? = null,
        exchangeId: String? = null,
        publicId: String? = null
    ): JSONObject {
        val messagesArray = JSONArray()
        for (message in messages) {
            messagesArray.put(message)
        }
        val friendsArray = JSONArray()
        for (friend in blindedFriends) {
            friendsArray.put(Base64.encodeToString(friend, Base64.NO_WRAP))
        }
        val json = JSONObject()
            .put("messages", messagesArray)
            .put("friends", friendsArray)
        // Identity contract: include device_id_hash and exchange_id when available.
        // These are optional for backwards compatibility with older peers.
        deviceIdHash?.let { json.put("device_id_hash", it) }
        exchangeId?.let { json.put("exchange_id", it) }
        publicId?.let { json.put("public_id", it) }
        return json
    }

    fun decodeClientMessage(json: JSONObject): LegacyClientMessage {
        val messagesArray = json.optJSONArray("messages") ?: JSONArray()
        val friendsArray = json.optJSONArray("friends") ?: JSONArray()
        val messages = ArrayList<JSONObject>()
        val friends = ArrayList<ByteArray>()
        for (i in 0 until messagesArray.length()) {
            val item = messagesArray.getJSONObject(i)
            messages.add(item)
        }
        for (i in 0 until friendsArray.length()) {
            val base64 = friendsArray.getString(i)
            friends.add(Base64.decode(base64, Base64.NO_WRAP))
        }
        // Identity contract: extract device_id_hash, exchange_id, and public_id if present.
        val deviceIdHash = json.optString("device_id_hash", null)
        val exchangeId = json.optString("exchange_id", null)
        val publicId = json.optString("public_id", null)
        return LegacyClientMessage(messages, friends, deviceIdHash, exchangeId, publicId)
    }

    fun encodeServerMessage(doubleBlinded: List<ByteArray>, hashedBlinded: List<ByteArray>): JSONObject {
        val dblindArray = JSONArray()
        val dhashArray = JSONArray()
        for (item in doubleBlinded) {
            dblindArray.put(Base64.encodeToString(item, Base64.NO_WRAP))
        }
        for (item in hashedBlinded) {
            dhashArray.put(Base64.encodeToString(item, Base64.NO_WRAP))
        }
        return JSONObject()
            .put("dblind", dblindArray)
            .put("dhash", dhashArray)
    }

    fun decodeServerMessage(json: JSONObject): LegacyServerMessage {
        val dblindArray = json.optJSONArray("dblind") ?: JSONArray()
        val dhashArray = json.optJSONArray("dhash") ?: JSONArray()
        val doubleBlinded = ArrayList<ByteArray>()
        val hashedBlinded = ArrayList<ByteArray>()
        for (i in 0 until dblindArray.length()) {
            val base64 = dblindArray.getString(i)
            doubleBlinded.add(Base64.decode(base64, Base64.NO_WRAP))
        }
        for (i in 0 until dhashArray.length()) {
            val base64 = dhashArray.getString(i)
            hashedBlinded.add(Base64.decode(base64, Base64.NO_WRAP))
        }
        return LegacyServerMessage(doubleBlinded, hashedBlinded)
    }

    /**
     * Encode a message for transmission to a specific peer.
     *
     * This matches Casific's approach where trust is recomputed per-peer using the
     * sigmoid+noise formula based on shared friend count.
     *
     * Security-related settings (useTrust, includePseudonym, shareLocation) come from
     * SecurityManager's current SecurityProfile (user-configurable).
     * Technical constants (trustNoiseVariance) are hard-locked to Casific's design (0.003).
     *
     * @param context Application context for config values.
     * @param message The message to encode.
     * @param sharedFriends Number of friends shared with the exchange peer.
     * @param myFriends Total number of friends we have.
     */
    fun encodeMessage(
        context: Context,
        message: RangzenMessage,
        sharedFriends: Int,
        myFriends: Int
    ): JSONObject {
        // User-configurable settings from SecurityManager (respects current SecurityProfile)
        val includeTrust = SecurityManager.useTrust(context)
        val includePseudonym = SecurityManager.includePseudonym(context)
        val shareLocation = SecurityManager.shareLocation(context)
        // Hard-locked to Casific's MurmurMessage.java value (VAR = 0.003).
        // This is intentionally not configurable to match Casific's design.
        val trustNoiseVariance = TRUST_NOISE_VARIANCE
        // Use the full toLegacyJson signature with per-peer trust recomputation.
        return message.toLegacyJson(
            includePseudonym,
            shareLocation,
            includeTrust,
            trustNoiseVariance,
            sharedFriends,
            myFriends
        )
    }

    fun decodeMessage(json: JSONObject): RangzenMessage {
        return RangzenMessage.fromLegacyJson(json)
    }
}

data class LegacyClientMessage(
    val messages: List<JSONObject>,
    val blindedFriends: List<ByteArray>,
    /** Peer's device_id_hash (null if peer is on older version). */
    val deviceIdHash: String? = null,
    /** Shared exchange_id for pairing events (null if peer is on older version). */
    val exchangeId: String? = null,
    /** Peer's public_id for cross-transport correlation (null if peer is on older version). */
    val publicId: String? = null
)

data class LegacyServerMessage(
    val doubleBlindedFriends: List<ByteArray>,
    val hashedBlindedFriends: List<ByteArray>
)
