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
import org.denovogroup.rangzen.backend.SecurityManager
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
                // Decide whether to use the trust/PSI pipeline (from SecurityManager profile).
                val useTrust = SecurityManager.useTrust(context)
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

                // Enforce minimum shared contacts when trust is enabled (from SecurityManager profile).
                val minSharedContacts = SecurityManager.minSharedContactsForExchange(context)
                if (useTrust && commonFriends < minSharedContacts) {
                    Timber.w(
                        "Exchange rejected: sharedContacts=$commonFriends " +
                            "minRequired=$minSharedContacts peer=${peer.address}"
                    )
                    return@withTimeout null
                }

                val maxMessages = SecurityManager.maxMessagesPerExchange(context)
                val outboundMessages = messageStore.getMessagesForExchange(commonFriends, maxMessages)
                val countRequest = LegacyExchangeCodec.encodeExchangeInfo(outboundMessages.size)
                val countResponse = sendFrame(bleScanner, peer, countRequest) ?: return@withTimeout null
                val inboundCount = minOf(LegacyExchangeCodec.decodeExchangeInfo(countResponse), maxMessages)

                val rounds = maxOf(outboundMessages.size, inboundCount)
                val receivedMessages = ArrayList<RangzenMessage>()
                // Track our friend count for per-peer trust recomputation.
                val myFriends = localFriends.size

                for (i in 0 until rounds) {
                    val outbound = if (i < outboundMessages.size) {
                        val msg = outboundMessages[i]
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
                        listOf(LegacyExchangeCodec.encodeMessage(context, msg, commonFriends, myFriends))
                    } else {
                        emptyList()
                    }
                    val messageRequest = LegacyExchangeCodec.encodeClientMessage(outbound, emptyList())
                    val messageResponse = sendFrame(bleScanner, peer, messageRequest) ?: continue
                    val remoteClient = LegacyExchangeCodec.decodeClientMessage(messageResponse)
                    for (json in remoteClient.messages) {
                        val msg = LegacyExchangeCodec.decodeMessage(json)
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
                        receivedMessages.add(msg)
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

/**
 * Math utilities for trust computation matching Casific's MurmurMessage.java.
 *
 * The trust model uses a sigmoid function on the fraction of shared friends,
 * plus Gaussian noise for privacy. This matches the original cryptographic
 * design for Sybil resistance.
 */
object LegacyExchangeMath {
    // Minimum trust for strangers (no shared friends).
    private const val EPSILON_TRUST = 0.001

    // Gaussian noise parameters from Casific (MurmurMessage.java app design).
    // MEAN = 0.0, VAR = 0.003 per Casific's production app.
    private const val NOISE_MEAN = 0.0
    private const val NOISE_VARIANCE = 0.003

    // Sigmoid parameters from Casific.
    private const val SIGMOID_CUTOFF = 0.3
    private const val SIGMOID_RATE = 13.0

    // Random source for Gaussian noise.
    private val random = java.util.Random()

    /**
     * Compute new priority for a message based on shared friends.
     * Takes the max of the remote-derived priority and the locally stored priority.
     */
    fun newPriority(remote: Double, stored: Double, commonFriends: Int, myFriends: Int): Double {
        return maxOf(computeNewPriority_sigmoidFractionOfFriends(remote, commonFriends, myFriends), stored)
    }

    /**
     * Compute the priority score normalized by friend fraction, passed through
     * a sigmoid function with Gaussian noise.
     *
     * This matches Casific's MurmurMessage.computeNewPriority_sigmoidFractionOfFriends().
     *
     * @param priority The priority of the message before computing trust.
     * @param sharedFriends Number of friends shared between this person and the message sender.
     * @param myFriends The number of friends this person has.
     * @return The adjusted priority score.
     */
    fun computeNewPriority_sigmoidFractionOfFriends(
        priority: Double,
        sharedFriends: Int,
        myFriends: Int
    ): Double {
        // Compute fraction of friends, guarding against division by zero.
        val fraction = if (myFriends > 0) sharedFriends / myFriends.toDouble() else 0.0

        // Pass through sigmoid to get base trust multiplier.
        var trustMultiplier = sigmoid(fraction, SIGMOID_CUTOFF, SIGMOID_RATE)

        // Add Gaussian noise for privacy.
        trustMultiplier += getGaussian(NOISE_MEAN, NOISE_VARIANCE)

        // Truncate range to [0, 1].
        trustMultiplier = trustMultiplier.coerceIn(0.0, 1.0)

        // Special case: no shared friends means minimal trust.
        if (sharedFriends == 0) {
            trustMultiplier = EPSILON_TRUST
        }

        return priority * trustMultiplier
    }

    /**
     * Sigmoid function matching Casific's implementation.
     *
     * @param input The input value (fraction of shared friends).
     * @param cutoff The transition point of the sigmoid (0.3 in Casific).
     * @param rate The rate at which the sigmoid grows (13.0 in Casific).
     * @return A value between 0 and 1.
     */
    internal fun sigmoid(input: Double, cutoff: Double, rate: Double): Double {
        return 1.0 / (1.0 + Math.pow(Math.E, -rate * (input - cutoff)))
    }

    /**
     * Generate Gaussian noise matching Casific's Utils.makeNoise().
     *
     * @param mean The mean of the Gaussian distribution.
     * @param variance The variance of the Gaussian distribution.
     * @return A random value drawn from the specified Gaussian.
     */
    private fun getGaussian(mean: Double, variance: Double): Double {
        return mean + random.nextGaussian() * Math.sqrt(variance)
    }
}
