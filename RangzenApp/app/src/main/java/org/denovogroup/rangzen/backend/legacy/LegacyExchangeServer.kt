/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * BLE server-side exchange aligned with the original Rangzen/Murmur protocol.
 */
package org.denovogroup.rangzen.backend.legacy

import android.bluetooth.BluetoothDevice
import android.content.Context
import org.denovogroup.rangzen.backend.AppConfig
import org.denovogroup.rangzen.backend.Crypto
import org.denovogroup.rangzen.backend.FriendStore
import org.denovogroup.rangzen.backend.SecurityManager
import org.denovogroup.rangzen.backend.MessageStore
import org.denovogroup.rangzen.backend.telemetry.TelemetryClient
import org.denovogroup.rangzen.backend.telemetry.TelemetryEvent
import org.denovogroup.rangzen.objects.RangzenMessage
import org.json.JSONObject
import timber.log.Timber
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class LegacyExchangeServer(
    private val context: Context,
    private val friendStore: FriendStore,
    private val messageStore: MessageStore
) {

    private val sessions = ConcurrentHashMap<String, LegacyExchangeSession>()

    fun handleRequest(device: BluetoothDevice, payload: ByteArray): ByteArray? {
        val json = LegacyExchangeCodec.decodeLengthValue(payload)
        if (json == null) {
            Timber.e("Failed to decode legacy payload from ${device.address}")
            return null
        }
        val session = getOrCreateSession(device.address)
        session.lastActivityMs = System.currentTimeMillis()
        return try {
            val response = session.handle(json)
            LegacyExchangeCodec.encodeLengthValue(response)
        } catch (e: Exception) {
            Timber.e(e, "Legacy exchange session error for ${device.address}")
            sessions.remove(device.address)
            null
        }
    }

    private fun getOrCreateSession(address: String): LegacyExchangeSession {
        cleanupExpiredSessions()
        return sessions.getOrPut(address) {
            LegacyExchangeSession(
                context = context,
                friendStore = friendStore,
                messageStore = messageStore,
                address = address
            )
        }
    }

    private fun cleanupExpiredSessions() {
        val timeout = AppConfig.exchangeSessionTimeoutMs(context)
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { now - it.value.lastActivityMs > timeout }
    }
}

private enum class LegacyExchangeStage {
    WAIT_CLIENT_FRIENDS,
    WAIT_SERVER_MESSAGE,
    WAIT_CLIENT_MESSAGE_COUNT,
    WAIT_CLIENT_MESSAGES
}

private class LegacyExchangeSession(
    private val context: Context,
    private val friendStore: FriendStore,
    private val messageStore: MessageStore,
    private val address: String
) {

    var lastActivityMs: Long = System.currentTimeMillis()
    private var stage: LegacyExchangeStage = LegacyExchangeStage.WAIT_CLIENT_FRIENDS
    private var clientPSI: Crypto.PrivateSetIntersection? = null
    private var serverPSI: Crypto.PrivateSetIntersection? = null
    private var remoteBlindedFriends: List<ByteArray> = emptyList()
    private var commonFriends: Int = 0
    private var expectedMessages: Int = 0
    private var receivedMessages: Int = 0
    private var outgoingMessages: List<RangzenMessage> = emptyList()
    private var outgoingIndex: Int = 0

    fun handle(json: JSONObject): JSONObject {
        return when (stage) {
            LegacyExchangeStage.WAIT_CLIENT_FRIENDS -> handleClientFriends(json)
            LegacyExchangeStage.WAIT_SERVER_MESSAGE -> handleServerMessage(json)
            LegacyExchangeStage.WAIT_CLIENT_MESSAGE_COUNT -> handleMessageCount(json)
            LegacyExchangeStage.WAIT_CLIENT_MESSAGES -> handleMessage(json)
        }
    }

    private fun handleClientFriends(json: JSONObject): JSONObject {
        // Honor trust settings from SecurityManager profile.
        val useTrust = SecurityManager.useTrust(context)
        val clientMessage = LegacyExchangeCodec.decodeClientMessage(json)
        remoteBlindedFriends = if (useTrust) clientMessage.blindedFriends else emptyList()

        val localFriends = friendStore.getAllFriendIds()
        clientPSI = Crypto.PrivateSetIntersection(localFriends)
        serverPSI = Crypto.PrivateSetIntersection(localFriends)

        val blinded = if (useTrust) {
            clientPSI?.encodeBlindedItems() ?: emptyList()
        } else {
            emptyList()
        }
        stage = LegacyExchangeStage.WAIT_SERVER_MESSAGE
        return LegacyExchangeCodec.encodeClientMessage(emptyList(), blinded)
    }

    private fun handleServerMessage(json: JSONObject): JSONObject {
        val server = serverPSI ?: throw IllegalStateException("Server PSI missing for $address")
        val client = clientPSI ?: throw IllegalStateException("Client PSI missing for $address")
        // Honor trust settings from SecurityManager profile.
        val useTrust = SecurityManager.useTrust(context)
        val remoteServer = LegacyExchangeCodec.decodeServerMessage(json)
        val serverReply = server.replyToBlindedItems(ArrayList(remoteBlindedFriends))
        commonFriends = if (useTrust) {
            client.getCardinality(
                Crypto.PrivateSetIntersection.ServerReplyTuple(
                    ArrayList(remoteServer.doubleBlindedFriends),
                    ArrayList(remoteServer.hashedBlindedFriends)
                )
            )
        } else {
            0
        }

        // Enforce minimum shared contacts when trust is enabled (from SecurityManager profile).
        val minSharedContacts = SecurityManager.minSharedContactsForExchange(context)
        if (useTrust && commonFriends < minSharedContacts) {
            throw IllegalStateException(
                "Exchange rejected: sharedContacts=$commonFriends minRequired=$minSharedContacts peer=$address"
            )
        }
        val maxMessages = SecurityManager.maxMessagesPerExchange(context)
        outgoingMessages = messageStore.getMessagesForExchange(commonFriends, maxMessages)
        outgoingIndex = 0
        stage = LegacyExchangeStage.WAIT_CLIENT_MESSAGE_COUNT
        return LegacyExchangeCodec.encodeServerMessage(
            serverReply.doubleBlindedItems,
            serverReply.hashedBlindedItems
        )
    }

    private fun handleMessageCount(json: JSONObject): JSONObject {
        val count = LegacyExchangeCodec.decodeExchangeInfo(json)
        val maxMessages = SecurityManager.maxMessagesPerExchange(context)
        expectedMessages = minOf(count, maxMessages)
        receivedMessages = 0
        stage = LegacyExchangeStage.WAIT_CLIENT_MESSAGES
        return LegacyExchangeCodec.encodeExchangeInfo(outgoingMessages.size)
    }

    private fun handleMessage(json: JSONObject): JSONObject {
        val peerIdHash = sha256(address)
        val remote = LegacyExchangeCodec.decodeClientMessage(json)
        if (remote.messages.isNotEmpty()) {
            val incoming = ArrayList<RangzenMessage>()
            for (item in remote.messages) {
                val msg = LegacyExchangeCodec.decodeMessage(item)
                val isNew = !messageStore.hasMessage(msg.messageId)
                // Track message received
                TelemetryClient.getInstance()?.trackMessageReceived(
                    peerIdHash = peerIdHash,
                    transport = TelemetryEvent.TRANSPORT_BLE,
                    messageIdHash = sha256(msg.messageId),
                    hopCount = msg.hopCount,
                    trustScore = msg.trustScore,
                    priority = msg.priority,
                    isNew = isNew
                )
                incoming.add(msg)
            }
            mergeIncomingMessages(incoming)
            receivedMessages += incoming.size
        }

        // Track our friend count for per-peer trust recomputation.
        val myFriends = friendStore.getAllFriendIds().size

        val nextOutbound = if (outgoingIndex < outgoingMessages.size) {
            val msg = outgoingMessages[outgoingIndex]
            // Track message sent
            TelemetryClient.getInstance()?.trackMessageSent(
                peerIdHash = peerIdHash,
                transport = TelemetryEvent.TRANSPORT_BLE,
                messageIdHash = sha256(msg.messageId),
                hopCount = msg.hopCount,
                trustScore = msg.trustScore,
                priority = msg.priority,
                ageMs = System.currentTimeMillis() - msg.timestamp
            )
            // Pass shared friend context for per-peer trust computation.
            val messageJson = LegacyExchangeCodec.encodeMessage(
                context,
                msg,
                commonFriends,
                myFriends
            )
            outgoingIndex += 1
            listOf(messageJson)
        } else {
            emptyList()
        }

        if (receivedMessages >= expectedMessages && outgoingIndex >= outgoingMessages.size) {
            stage = LegacyExchangeStage.WAIT_CLIENT_FRIENDS
        }

        return LegacyExchangeCodec.encodeClientMessage(nextOutbound, emptyList())
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun mergeIncomingMessages(messages: List<RangzenMessage>) {
        val myFriendsCount = friendStore.getAllFriendIds().size
        for (message in messages) {
            val existing = messageStore.getMessage(message.messageId)
            if (existing != null) {
                val newTrust = LegacyExchangeMath.newPriority(
                    message.trustScore,
                    existing.trustScore,
                    commonFriends,
                    myFriendsCount
                )
                if (newTrust > existing.trustScore) {
                    messageStore.updateTrustScore(message.messageId, newTrust)
                }
            } else if (message.text != null && message.text.isNotEmpty()) {
                messageStore.addMessage(message)
            }
        }
    }
}
