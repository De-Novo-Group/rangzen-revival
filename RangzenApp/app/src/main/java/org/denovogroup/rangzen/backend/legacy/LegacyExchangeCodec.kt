/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * Legacy exchange codec aligned with the original Rangzen/Murmur Java protocol.
 */
package org.denovogroup.rangzen.backend.legacy

import android.content.Context
import android.util.Base64
import org.denovogroup.rangzen.backend.AppConfig
import org.denovogroup.rangzen.objects.RangzenMessage
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder

object LegacyExchangeCodec {

    private const val LENGTH_PREFIX_BYTES = 4
    private const val MESSAGE_COUNT_KEY = "count"

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

    fun encodeClientMessage(messages: List<JSONObject>, blindedFriends: List<ByteArray>): JSONObject {
        val messagesArray = JSONArray()
        for (message in messages) {
            messagesArray.put(message)
        }
        val friendsArray = JSONArray()
        for (friend in blindedFriends) {
            friendsArray.put(Base64.encodeToString(friend, Base64.NO_WRAP))
        }
        return JSONObject()
            .put("messages", messagesArray)
            .put("friends", friendsArray)
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
        return LegacyClientMessage(messages, friends)
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

    fun encodeMessage(context: Context, message: RangzenMessage): JSONObject {
        val includeTrust = AppConfig.useTrust(context)
        val includePseudonym = AppConfig.includePseudonym(context)
        val shareLocation = AppConfig.shareLocation(context)
        val trustNoiseVariance = AppConfig.trustNoiseVariance(context)
        return message.toLegacyJson(includePseudonym, shareLocation, includeTrust, trustNoiseVariance)
    }

    fun decodeMessage(json: JSONObject): RangzenMessage {
        return RangzenMessage.fromLegacyJson(json)
    }
}

data class LegacyClientMessage(
    val messages: List<JSONObject>,
    val blindedFriends: List<ByteArray>
)

data class LegacyServerMessage(
    val doubleBlindedFriends: List<ByteArray>,
    val hashedBlindedFriends: List<ByteArray>
)
