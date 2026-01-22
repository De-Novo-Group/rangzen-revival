/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * Exchange protocol implementation for Rangzen
 * Handles the PSI-Ca cryptographic exchange and message transfer
 */
package org.denovogroup.rangzen.backend

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.denovogroup.rangzen.backend.telemetry.TelemetryClient
import org.denovogroup.rangzen.backend.telemetry.TelemetryEvent
import org.denovogroup.rangzen.objects.RangzenMessage
import timber.log.Timber
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.NoSuchAlgorithmException

/**
 * Performs a secure exchange with another Rangzen peer.
 * 
 * The exchange protocol:
 * 1. Both peers compute PSI (Private Set Intersection) on their friend lists
 * 2. PSI reveals the COUNT of mutual friends, not WHO they are
 * 3. Mutual friend count determines trust score for message prioritization
 * 4. Messages are exchanged, prioritized by sender's trust score
 * 
 * This protocol ensures:
 * - Privacy: Friend lists are never revealed
 * - Sybil resistance: More mutual friends = higher trust
 * - Efficiency: High-priority messages sent first
 *
 * @deprecated This class is not used. Use [LegacyExchangeClient] and [LegacyExchangeServer] instead
 * which implement the actual wire-compatible Casific/Murmur protocol.
 */
@Deprecated("Unused - use LegacyExchangeClient/LegacyExchangeServer instead")
class Exchange(
    private val inputStream: InputStream,
    private val outputStream: OutputStream,
    private val friendStore: FriendStore,
    private val messageStore: MessageStore,
    private val peerIdHash: String = "unknown",
    private val transport: String = TelemetryEvent.TRANSPORT_BLE
) {
    private val gson = Gson()
    private val reader = BufferedReader(InputStreamReader(inputStream))
    private val writer = BufferedWriter(OutputStreamWriter(outputStream))

    /** PSI computation for our side of the exchange */
    private var localPSI: Crypto.PrivateSetIntersection? = null

    /** Number of mutual friends computed via PSI */
    private var mutualFriendCount = 0

    /** Messages received from the peer */
    private val receivedMessages = mutableListOf<RangzenMessage>()

    /** Status of the exchange */
    var status: ExchangeStatus = ExchangeStatus.PENDING
        private set

    /** Error message if exchange failed */
    var errorMessage: String? = null
        private set

    enum class ExchangeStatus {
        PENDING,
        IN_PROGRESS,
        SUCCESS,
        FAILED
    }

    /**
     * Perform the complete exchange protocol.
     *
     * @return Number of messages exchanged, or -1 on failure
     */
    suspend fun performExchange(): Int = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        // Track exchange start
        TelemetryClient.getInstance()?.trackExchangeStart(peerIdHash, transport)

        try {
            withTimeout(EXCHANGE_TIMEOUT_MS) {
                status = ExchangeStatus.IN_PROGRESS

                // Step 1: Initialize PSI with our friends list
                initializePSI()

                // Step 2: Exchange protocol version for compatibility
                exchangeVersion()

                // Step 3: Exchange blinded friend lists
                exchangeFriendsPSI()

                // Step 4: Compute mutual friend count
                computeMutualFriends()

                Timber.i("Mutual friend count: $mutualFriendCount")

                // Step 5: Exchange messages based on trust
                val messagesExchanged = exchangeMessages()

                status = ExchangeStatus.SUCCESS
                Timber.i("Exchange completed successfully. Messages exchanged: $messagesExchanged")

                // Track exchange success
                val durationMs = System.currentTimeMillis() - startTime
                TelemetryClient.getInstance()?.trackExchangeSuccess(
                    peerIdHash = peerIdHash,
                    transport = transport,
                    durationMs = durationMs,
                    messagesSent = messageStore.getMessagesForExchange(mutualFriendCount, limit = 50).size,
                    messagesReceived = messagesExchanged,
                    mutualFriends = mutualFriendCount
                )

                messagesExchanged
            }
        } catch (e: Exception) {
            Timber.e(e, "Exchange failed")
            status = ExchangeStatus.FAILED
            errorMessage = e.message

            // Track exchange failure
            val durationMs = System.currentTimeMillis() - startTime
            TelemetryClient.getInstance()?.trackExchangeFailure(
                peerIdHash = peerIdHash,
                transport = transport,
                error = e.message ?: "Unknown error",
                durationMs = durationMs
            )

            -1
        }
    }

    /**
     * Initialize PSI with our friend list.
     */
    private fun initializePSI() {
        val friendIds = friendStore.getAllFriendIds()
        // Include our own ID so peers with us as a friend get counted
        friendStore.getMyPublicId()?.let { friendIds.add(it) }
        
        localPSI = Crypto.PrivateSetIntersection(friendIds)
        Timber.d("PSI initialized with ${friendIds.size} entries")
    }

    /**
     * Exchange protocol versions to ensure compatibility.
     */
    private fun exchangeVersion() {
        // Send our version
        sendLine(PROTOCOL_VERSION.toString())
        
        // Receive peer's version
        val peerVersion = readLine()?.toIntOrNull() ?: 0
        
        if (peerVersion < 1) {
            throw IOException("Incompatible protocol version: $peerVersion")
        }
        
        Timber.d("Protocol versions - local: $PROTOCOL_VERSION, peer: $peerVersion")
    }

    /**
     * Exchange blinded friend lists using PSI protocol.
     */
    private fun exchangeFriendsPSI() {
        val psi = localPSI ?: throw IllegalStateException("PSI not initialized")

        // Send our blinded items
        val blindedItems = psi.encodeBlindedItems()
        sendJsonLine(blindedItems.map { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) })
        
        // Receive peer's blinded items
        val peerBlindedJson = readLine() ?: throw IOException("No response from peer")
        val type = object : TypeToken<List<String>>() {}.type
        val peerBlindedBase64: List<String> = gson.fromJson(peerBlindedJson, type)
        val peerBlinded = ArrayList(peerBlindedBase64.map { 
            android.util.Base64.decode(it, android.util.Base64.NO_WRAP) 
        })

        // Generate and send server reply (double-blinded items + hashed items)
        val serverReply = psi.replyToBlindedItems(peerBlinded)
        
        val replyData = mapOf(
            "doubleBlinded" to serverReply.doubleBlindedItems.map { 
                android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) 
            },
            "hashedBlinded" to serverReply.hashedBlindedItems.map { 
                android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) 
            }
        )
        sendJsonLine(replyData)

        // Receive peer's server reply
        val peerReplyJson = readLine() ?: throw IOException("No server reply from peer")
        val peerReplyMap: Map<String, List<String>> = gson.fromJson(peerReplyJson, 
            object : TypeToken<Map<String, List<String>>>() {}.type)
        
        // Store peer reply for cardinality computation
        val peerDoubleBlinded = ArrayList(peerReplyMap["doubleBlinded"]?.map { 
            android.util.Base64.decode(it, android.util.Base64.NO_WRAP) 
        } ?: emptyList())
        
        val peerHashedBlinded = ArrayList(peerReplyMap["hashedBlinded"]?.map { 
            android.util.Base64.decode(it, android.util.Base64.NO_WRAP) 
        } ?: emptyList())

        // Compute cardinality
        val peerServerReply = Crypto.PrivateSetIntersection.ServerReplyTuple(
            peerDoubleBlinded, peerHashedBlinded
        )
        mutualFriendCount = psi.getCardinality(peerServerReply)
    }

    /**
     * Compute mutual friends from PSI exchange.
     */
    private fun computeMutualFriends() {
        // Already computed in exchangeFriendsPSI
        Timber.d("Computed $mutualFriendCount mutual friends via PSI")
    }

    /**
     * Exchange messages with the peer.
     * Trust score is based on mutual friend count.
     */
    private fun exchangeMessages(): Int {
        // Calculate trust score from mutual friends (0.0 to 1.0)
        // More mutual friends = higher trust
        val trustScore = calculateTrustScore(mutualFriendCount)
        
        // Get messages to send, filtered by minimum contact requirement
        val messagesToSend = messageStore.getMessagesForExchange(mutualFriendCount, limit = 50)
        
        // Send our message count
        sendLine(messagesToSend.size.toString())
        
        // Receive peer's message count
        val peerMessageCount = readLine()?.toIntOrNull() ?: 0
        Timber.d("Will exchange ${messagesToSend.size} for $peerMessageCount messages")

        // Send our messages
        for (message in messagesToSend) {
            val messageJson = gson.toJson(message)
            sendLine(messageJson)
        }

        // Receive peer's messages
        var messagesReceived = 0
        for (i in 0 until peerMessageCount) {
            val messageJson = readLine() ?: break
            try {
                val message = gson.fromJson(messageJson, RangzenMessage::class.java)
                // Apply trust score from this exchange
                message.trustScore = trustScore
                message.incrementHopCount()
                
                if (messageStore.addMessage(message)) {
                    messagesReceived++
                    receivedMessages.add(message)
                }
            } catch (e: Exception) {
                Timber.w("Failed to parse message: ${e.message}")
            }
        }

        Timber.i("Sent ${messagesToSend.size}, received $messagesReceived messages")
        return messagesReceived
    }

    /**
     * Calculate trust score from mutual friend count.
     *
     * Uses Casific's sigmoid function: sigmoid(sharedFriends / myFriends, 0.3, 13.0)
     * plus Gaussian noise for privacy protection.
     *
     * Note: For actual exchanges, use LegacyExchangeMath.computeNewPriority_sigmoidFractionOfFriends()
     * which provides the full Casific-compatible implementation.
     */
    private fun calculateTrustScore(mutualFriends: Int): Double {
        // Get total friend count for fraction calculation.
        val myFriends = friendStore.getAllFriendIds().size
        if (myFriends == 0) {
            return EPSILON_TRUST
        }

        // Compute fraction of friends.
        val fraction = mutualFriends / myFriends.toDouble()

        // Sigmoid function matching Casific parameters.
        var trustMultiplier = 1.0 / (1.0 + Math.pow(Math.E, -SIGMOID_RATE * (fraction - SIGMOID_CUTOFF)))

        // Add Gaussian noise for privacy.
        trustMultiplier += NOISE_MEAN + trustRandom.nextGaussian() * Math.sqrt(NOISE_VARIANCE)

        // Truncate range to [0, 1].
        trustMultiplier = trustMultiplier.coerceIn(0.0, 1.0)

        // Special case: no shared friends means minimal trust.
        if (mutualFriends == 0) {
            return EPSILON_TRUST
        }

        return trustMultiplier
    }

    companion object {
        private const val EXCHANGE_TIMEOUT_MS = 60_000L
        private const val PROTOCOL_VERSION = 2

        // Casific trust model constants (MurmurMessage.java app design).
        // MEAN = 0.0, VAR = 0.003 per Casific's production app.
        private const val EPSILON_TRUST = 0.001
        private const val NOISE_MEAN = 0.0
        private const val NOISE_VARIANCE = 0.003
        private const val SIGMOID_CUTOFF = 0.3
        private const val SIGMOID_RATE = 13.0

        // Random source for trust noise.
        private val trustRandom = java.util.Random()
    }

    /**
     * Get messages received during this exchange.
     */
    fun getReceivedMessages(): List<RangzenMessage> = receivedMessages.toList()

    /**
     * Get the mutual friend count from this exchange.
     */
    fun getMutualFriendCount(): Int = mutualFriendCount

    /**
     * Send a line of text to the peer.
     */
    private fun sendLine(text: String) {
        writer.write(text)
        writer.newLine()
        writer.flush()
    }

    /**
     * Send an object as JSON.
     */
    private fun sendJsonLine(obj: Any) {
        sendLine(gson.toJson(obj))
    }

    /**
     * Read a line of text from the peer.
     */
    private fun readLine(): String? {
        return reader.readLine()
    }

    /**
     * Close the exchange streams.
     */
    fun close() {
        try {
            reader.close()
            writer.close()
        } catch (e: Exception) {
            Timber.w("Error closing exchange streams: ${e.message}")
        }
    }
}
