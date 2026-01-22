/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * BLE client-side exchange aligned with the original Rangzen/Murmur protocol.
 */
package org.denovogroup.rangzen.backend.legacy

import android.content.Context
import kotlinx.coroutines.withTimeout
import org.denovogroup.rangzen.backend.AppConfig
import org.denovogroup.rangzen.backend.Crypto
import org.denovogroup.rangzen.backend.FriendStore
import org.denovogroup.rangzen.backend.MessageStore
import org.denovogroup.rangzen.backend.ble.BleScanner
import org.denovogroup.rangzen.backend.ble.DiscoveredPeer
import org.denovogroup.rangzen.backend.telemetry.TelemetryClient
import org.denovogroup.rangzen.backend.telemetry.TelemetryEvent
import org.denovogroup.rangzen.objects.RangzenMessage
import org.json.JSONObject
import timber.log.Timber
import java.security.MessageDigest

class LegacyExchangeClient(
    private val context: Context,
    private val friendStore: FriendStore,
    private val messageStore: MessageStore
) {

    suspend fun exchangeWithPeer(bleScanner: BleScanner, peer: DiscoveredPeer): LegacyExchangeResult? {
        val startTime = System.currentTimeMillis()
        val peerIdHash = sha256(peer.address)

        // Track exchange start
        TelemetryClient.getInstance()?.trackExchangeStart(peerIdHash, TelemetryEvent.TRANSPORT_BLE)

        return withTimeout(AppConfig.exchangeSessionTimeoutMs(context)) {
            try {
                // Decide whether to use the trust/PSI pipeline.
                val useTrust = AppConfig.useTrust(context)
                val localFriends = friendStore.getAllFriendIds()
                val clientPSI = Crypto.PrivateSetIntersection(localFriends)
                val serverPSI = Crypto.PrivateSetIntersection(localFriends)

                // Only send blinded friends when trust is enabled.
                val blindedFriends = if (useTrust) clientPSI.encodeBlindedItems() else emptyList()
                val friendsRequest = LegacyExchangeCodec.encodeClientMessage(emptyList(), blindedFriends)
                val friendsResponse = sendFrame(bleScanner, peer, friendsRequest) ?: return@withTimeout null
                // Only parse remote friends when trust is enabled.
                val remoteFriends = if (useTrust) {
                    LegacyExchangeCodec.decodeClientMessage(friendsResponse).blindedFriends
                } else {
                    emptyList()
                }

                // Reply to the remote blinded set (empty when trust is disabled).
                val serverReply = serverPSI.replyToBlindedItems(ArrayList(remoteFriends))
                val serverRequest = LegacyExchangeCodec.encodeServerMessage(
                    serverReply.doubleBlindedItems,
                    serverReply.hashedBlindedItems
                )
                val serverResponse = sendFrame(bleScanner, peer, serverRequest) ?: return@withTimeout null
                val remoteServerMessage = LegacyExchangeCodec.decodeServerMessage(serverResponse)
                // Compute cardinality only when trust is enabled.
                val commonFriends = if (useTrust) {
                    clientPSI.getCardinality(
                        Crypto.PrivateSetIntersection.ServerReplyTuple(
                            ArrayList(remoteServerMessage.doubleBlindedFriends),
                            ArrayList(remoteServerMessage.hashedBlindedFriends)
                        )
                    )
                } else {
                    0
                }

                // Enforce minimum shared contacts when trust is enabled.
                val minSharedContacts = AppConfig.minSharedContactsForExchange(context)
                if (useTrust && commonFriends < minSharedContacts) {
                    Timber.w(
                        "Exchange rejected: sharedContacts=$commonFriends " +
                            "minRequired=$minSharedContacts peer=${peer.address}"
                    )
                    return@withTimeout null
                }

                val maxMessages = AppConfig.maxMessagesPerExchange(context)
                val outboundMessages = messageStore.getMessagesForExchange(commonFriends, maxMessages)
                val countRequest = LegacyExchangeCodec.encodeExchangeInfo(outboundMessages.size)
                val countResponse = sendFrame(bleScanner, peer, countRequest) ?: return@withTimeout null
                val inboundCount = minOf(LegacyExchangeCodec.decodeExchangeInfo(countResponse), maxMessages)

                val rounds = maxOf(outboundMessages.size, inboundCount)
                val receivedMessages = ArrayList<RangzenMessage>()

                for (i in 0 until rounds) {
                    val outbound = if (i < outboundMessages.size) {
                        listOf(LegacyExchangeCodec.encodeMessage(context, outboundMessages[i]))
                    } else {
                        emptyList()
                    }
                    val messageRequest = LegacyExchangeCodec.encodeClientMessage(outbound, emptyList())
                    val messageResponse = sendFrame(bleScanner, peer, messageRequest) ?: continue
                    val remoteClient = LegacyExchangeCodec.decodeClientMessage(messageResponse)
                    for (json in remoteClient.messages) {
                        receivedMessages.add(LegacyExchangeCodec.decodeMessage(json))
                    }
                }

                mergeIncomingMessages(receivedMessages, commonFriends)

                // Track exchange success
                val durationMs = System.currentTimeMillis() - startTime
                TelemetryClient.getInstance()?.trackExchangeSuccess(
                    peerIdHash = peerIdHash,
                    transport = TelemetryEvent.TRANSPORT_BLE,
                    durationMs = durationMs,
                    messagesSent = outboundMessages.size,
                    messagesReceived = receivedMessages.size,
                    mutualFriends = commonFriends
                )

                LegacyExchangeResult(
                    commonFriends = commonFriends,
                    messagesSent = outboundMessages.size,
                    messagesReceived = receivedMessages.size
                )
            } catch (e: Exception) {
                Timber.e(e, "Legacy exchange failed with ${peer.address}")
                // Track exchange failure
                val durationMs = System.currentTimeMillis() - startTime
                TelemetryClient.getInstance()?.trackExchangeFailure(
                    peerIdHash = peerIdHash,
                    transport = TelemetryEvent.TRANSPORT_BLE,
                    error = e.message ?: "Unknown error",
                    durationMs = durationMs
                )
                null
            }
        }
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private suspend fun sendFrame(
        bleScanner: BleScanner,
        peer: DiscoveredPeer,
        message: JSONObject
    ): JSONObject? {
        val payload = LegacyExchangeCodec.encodeLengthValue(message)
        val response = bleScanner.exchange(peer, payload) ?: return null
        return LegacyExchangeCodec.decodeLengthValue(response)
    }

    private fun mergeIncomingMessages(messages: List<RangzenMessage>, commonFriends: Int) {
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

data class LegacyExchangeResult(
    val commonFriends: Int,
    val messagesSent: Int,
    val messagesReceived: Int
)

object LegacyExchangeMath {
    private const val EPSILON_TRUST = 0.001

    fun newPriority(remote: Double, stored: Double, commonFriends: Int, myFriends: Int): Double {
        return maxOf(fractionOfFriendsPriority(remote, commonFriends, myFriends), stored)
    }

    private fun fractionOfFriendsPriority(priority: Double, sharedFriends: Int, myFriends: Int): Double {
        val trustMultiplier = if (sharedFriends == 0 || myFriends == 0) {
            EPSILON_TRUST
        } else {
            sharedFriends / myFriends.toDouble()
        }
        return priority * trustMultiplier
    }
}
