/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * LAN Transport for high-bandwidth message exchange over TCP sockets.
 * 
 * IMPORTANT: This transport preserves the PSI (Private Set Intersection)
 * trust model. People's safety depends on the trust system - we CANNOT
 * bypass it even for speed.
 * 
 * Protocol (with PSI):
 * 1. Client connects to peer's TCP port
 * 2. Exchange blinded friend sets (PSI round 1)
 * 3. Exchange double-blinded sets (PSI round 2)
 * 4. Compute common friends count
 * 5. Exchange messages with trust scores based on common friends
 * 6. Close connection
 * 
 * All frames are length-prefixed to prevent unbounded reads:
 * [4 bytes: length][payload]
 */
package org.denovogroup.rangzen.backend.lan

import android.content.Context
import kotlinx.coroutines.*
import org.denovogroup.rangzen.backend.Crypto
import org.denovogroup.rangzen.backend.FriendStore
import org.denovogroup.rangzen.backend.MessageStore
import org.denovogroup.rangzen.backend.SecurityManager
import org.denovogroup.rangzen.backend.legacy.LegacyExchangeCodec
import org.denovogroup.rangzen.backend.legacy.LegacyExchangeMath
import org.denovogroup.rangzen.backend.telemetry.TelemetryClient
import org.denovogroup.rangzen.objects.RangzenMessage
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LAN Transport for TCP-based message exchange WITH PSI trust model.
 * 
 * Safety is paramount - the trust model protects users from malicious content.
 * This transport implements the full PSI handshake even though it adds latency.
 */
class LanTransport {
    
    companion object {
        // TCP port for message exchange (different from UDP discovery port)
        const val EXCHANGE_PORT = 41235
        
        // Protocol constants
        const val PROTOCOL_VERSION = 2  // Version 2 = with PSI
        const val HANDSHAKE_TIMEOUT_MS = 5_000
        const val EXCHANGE_TIMEOUT_MS = 60_000  // Longer timeout for PSI + messages
        
        // Security: Maximum frame size to prevent memory exhaustion
        // 1 MB should be plenty for friend sets and message batches
        const val MAX_FRAME_SIZE = 1024 * 1024
        
        // Frame type identifiers
        const val FRAME_HANDSHAKE = 1
        const val FRAME_CLIENT_FRIENDS = 2
        const val FRAME_SERVER_FRIENDS = 3
        const val FRAME_SERVER_REPLY = 4
        const val FRAME_CLIENT_REPLY = 5
        const val FRAME_MESSAGES = 6
        const val FRAME_DONE = 7
    }
    
    /** Server socket for incoming connections */
    private var serverSocket: ServerSocket? = null
    
    /** Whether server is running */
    private val serverRunning = AtomicBoolean(false)
    
    /** Whether an exchange is currently in progress */
    private val exchangeInProgress = AtomicBoolean(false)
    
    /** Coroutine scope for server operations */
    private var serverScope: CoroutineScope? = null
    
    /** Our device identifier (privacy-preserving, derived from crypto key) */
    private var localDeviceId: String = ""
    
    /** Callback when exchange completes */
    var onExchangeComplete: ((success: Boolean, messagesSent: Int, messagesReceived: Int) -> Unit)? = null
    
    /**
     * Initialize the transport with our device ID.
     */
    fun initialize(deviceId: String) {
        this.localDeviceId = deviceId
        Timber.i("LAN Transport initialized (PSI-enabled, v$PROTOCOL_VERSION)")
    }
    
    /**
     * Start the server socket to accept incoming connections.
     */
    fun startServer(
        context: Context,
        messageStore: MessageStore,
        friendStore: FriendStore
    ) {
        if (serverRunning.get()) {
            Timber.d("LAN server already running")
            return
        }
        
        serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        serverRunning.set(true)
        
        serverScope?.launch {
            runServer(context, messageStore, friendStore)
        }
        
        Timber.i("LAN Transport server started on port $EXCHANGE_PORT (PSI-enabled)")
        trackTelemetry("server_started", "port" to EXCHANGE_PORT.toString(), "psi" to "true")
    }
    
    /**
     * Stop the server socket.
     */
    fun stopServer() {
        serverRunning.set(false)
        serverSocket?.close()
        serverSocket = null
        serverScope?.cancel()
        serverScope = null
        Timber.i("LAN Transport server stopped")
        trackTelemetry("server_stopped")
    }
    
    /**
     * Run the server loop.
     */
    private suspend fun runServer(
        context: Context,
        messageStore: MessageStore,
        friendStore: FriendStore
    ) {
        try {
            serverSocket = ServerSocket(EXCHANGE_PORT).apply {
                reuseAddress = true
                soTimeout = 5000  // Check for shutdown every 5 seconds
            }
            
            while (serverRunning.get()) {
                try {
                    val clientSocket = serverSocket?.accept() ?: break
                    
                    // Handle each connection in a new coroutine
                    serverScope?.launch {
                        handleClientConnection(clientSocket, context, messageStore, friendStore)
                    }
                    
                } catch (e: SocketTimeoutException) {
                    // Normal timeout, continue
                } catch (e: SocketException) {
                    if (serverRunning.get()) {
                        Timber.e(e, "Server socket exception")
                    }
                    break
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to start LAN server")
            trackTelemetry("server_error", "error" to e.message.toString())
        }
    }
    
    /**
     * Handle an incoming client connection with full PSI.
     */
    private suspend fun handleClientConnection(
        socket: Socket,
        context: Context,
        messageStore: MessageStore,
        friendStore: FriendStore
    ) {
        val clientAddress = socket.remoteSocketAddress.toString()
        Timber.i("LAN client connected from $clientAddress")
        val startTime = System.currentTimeMillis()
        
        try {
            socket.soTimeout = EXCHANGE_TIMEOUT_MS
            
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            
            // Step 1: Read client handshake
            val handshakeFrame = readFrame(input)
            val handshake = JSONObject(String(handshakeFrame, Charsets.UTF_8))
            val peerDeviceId = handshake.optString("device_id")
            val peerNonce = handshake.optString("nonce")
            val peerVersion = handshake.optInt("version", 1)
            
            if (peerDeviceId.isBlank()) {
                throw IOException("Invalid handshake: missing device_id")
            }
            
            Timber.d("LAN handshake from peer: ${peerDeviceId.take(8)}... (v$peerVersion)")
            
            // Step 2: Send our handshake response
            val responseHandshake = JSONObject().apply {
                put("device_id", localDeviceId)
                put("nonce", peerNonce)
                put("version", PROTOCOL_VERSION)
            }
            writeFrame(output, responseHandshake.toString().toByteArray(Charsets.UTF_8))
            
            // Step 3: PSI - Read client's blinded friends
            val clientFriendsFrame = readFrame(input)
            val clientFriendsJson = JSONObject(String(clientFriendsFrame, Charsets.UTF_8))
            val useTrust = SecurityManager.useTrust(context)
            
            val remoteBlindedFriends = if (useTrust) {
                decodeBlindedFriends(clientFriendsJson.optJSONArray("blinded_friends"))
            } else {
                emptyList()
            }
            
            // Step 4: Create our PSI objects and send our blinded friends
            val localFriends = friendStore.getAllFriendIds()
            val clientPSI = Crypto.PrivateSetIntersection(localFriends)
            val serverPSI = Crypto.PrivateSetIntersection(localFriends)
            
            val ourBlindedFriends = if (useTrust) {
                clientPSI.encodeBlindedItems()
            } else {
                emptyList()
            }
            
            val serverFriendsJson = JSONObject().apply {
                put("blinded_friends", encodeBlindedFriends(ourBlindedFriends))
            }
            writeFrame(output, serverFriendsJson.toString().toByteArray(Charsets.UTF_8))
            
            // Step 5: Compute and send server reply (double-blinded + hashed)
            val serverReply = serverPSI.replyToBlindedItems(ArrayList(remoteBlindedFriends))
            val serverReplyJson = JSONObject().apply {
                put("double_blinded", encodeBlindedFriends(serverReply.doubleBlindedItems))
                put("hashed_blinded", encodeBlindedFriends(serverReply.hashedBlindedItems))
            }
            writeFrame(output, serverReplyJson.toString().toByteArray(Charsets.UTF_8))
            
            // Step 6: Read client's reply
            val clientReplyFrame = readFrame(input)
            val clientReplyJson = JSONObject(String(clientReplyFrame, Charsets.UTF_8))
            val remoteDoubleBlinded = decodeBlindedFriends(clientReplyJson.optJSONArray("double_blinded"))
            val remoteHashedBlinded = decodeBlindedFriends(clientReplyJson.optJSONArray("hashed_blinded"))
            
            // Step 7: Compute common friends
            val commonFriends = if (useTrust) {
                clientPSI.getCardinality(
                    Crypto.PrivateSetIntersection.ServerReplyTuple(
                        ArrayList(remoteDoubleBlinded),
                        ArrayList(remoteHashedBlinded)
                    )
                )
            } else {
                0
            }
            
            Timber.d("LAN PSI complete: $commonFriends common friends with ${peerDeviceId.take(8)}...")
            
            // Step 8: Check minimum shared contacts
            val minSharedContacts = SecurityManager.minSharedContactsForExchange(context)
            if (useTrust && commonFriends < minSharedContacts) {
                Timber.w("LAN exchange rejected: sharedContacts=$commonFriends minRequired=$minSharedContacts")
                writeFrame(output, JSONObject().apply {
                    put("error", "insufficient_trust")
                    put("common_friends", commonFriends)
                    put("required", minSharedContacts)
                }.toString().toByteArray(Charsets.UTF_8))
                return
            }
            
            // Step 9: Read client's messages
            val clientMessagesFrame = readFrame(input)
            val receivedMessages = processIncomingMessages(
                String(clientMessagesFrame, Charsets.UTF_8),
                messageStore,
                friendStore,
                commonFriends
            )
            
            // Step 10: Send our messages
            val outgoingData = prepareOutgoingMessages(context, messageStore, friendStore, commonFriends)
            writeFrame(output, outgoingData.toByteArray(Charsets.UTF_8))
            
            val duration = System.currentTimeMillis() - startTime
            Timber.i("LAN exchange (server) complete in ${duration}ms: received ${receivedMessages.size} messages, commonFriends=$commonFriends")
            
            trackTelemetry(
                "exchange_complete_server",
                "peer" to peerDeviceId.take(8),
                "received" to receivedMessages.size.toString(),
                "common_friends" to commonFriends.toString(),
                "duration_ms" to duration.toString()
            )
            
            onExchangeComplete?.invoke(true, 0, receivedMessages.size)
            
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            Timber.e(e, "LAN exchange failed with client $clientAddress after ${duration}ms")
            trackTelemetry("exchange_error_server", "error" to e.message.toString())
            onExchangeComplete?.invoke(false, 0, 0)
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                // Ignore close errors
            }
        }
    }
    
    /**
     * Initiate an exchange with a discovered LAN peer (with full PSI).
     */
    suspend fun exchangeWithPeer(
        peer: LanDiscoveryManager.LanPeer,
        context: Context,
        messageStore: MessageStore,
        friendStore: FriendStore
    ): ExchangeResult {
        // Prevent concurrent exchanges
        if (!exchangeInProgress.compareAndSet(false, true)) {
            Timber.w("LAN exchange already in progress, skipping")
            return ExchangeResult(false, 0, 0, "Exchange in progress")
        }
        
        try {
            return doExchange(peer, context, messageStore, friendStore)
        } finally {
            exchangeInProgress.set(false)
        }
    }
    
    /**
     * Perform the actual exchange with PSI.
     */
    private suspend fun doExchange(
        peer: LanDiscoveryManager.LanPeer,
        context: Context,
        messageStore: MessageStore,
        friendStore: FriendStore
    ): ExchangeResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var socket: Socket? = null
        
        try {
            Timber.i("Starting LAN exchange (PSI) with ${peer.deviceId.take(8)}... at ${peer.ipAddress.hostAddress}")
            
            // Connect to peer
            socket = Socket()
            socket.connect(InetSocketAddress(peer.ipAddress, peer.port), HANDSHAKE_TIMEOUT_MS)
            socket.soTimeout = EXCHANGE_TIMEOUT_MS
            
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            
            // Step 1: Send handshake
            val nonce = generateNonce()
            val handshake = JSONObject().apply {
                put("device_id", localDeviceId)
                put("nonce", nonce)
                put("version", PROTOCOL_VERSION)
            }
            writeFrame(output, handshake.toString().toByteArray(Charsets.UTF_8))
            
            // Step 2: Read handshake response
            val responseFrame = readFrame(input)
            val response = JSONObject(String(responseFrame, Charsets.UTF_8))
            val peerDeviceId = response.optString("device_id")
            val echoedNonce = response.optString("nonce")
            
            if (echoedNonce != nonce) {
                throw IOException("Nonce mismatch - possible MITM")
            }
            
            Timber.d("LAN handshake verified with peer: ${peerDeviceId.take(8)}...")
            
            // Step 3: PSI - Send our blinded friends
            val useTrust = SecurityManager.useTrust(context)
            val localFriends = friendStore.getAllFriendIds()
            val clientPSI = Crypto.PrivateSetIntersection(localFriends)
            val serverPSI = Crypto.PrivateSetIntersection(localFriends)
            
            val ourBlindedFriends = if (useTrust) {
                clientPSI.encodeBlindedItems()
            } else {
                emptyList()
            }
            
            val clientFriendsJson = JSONObject().apply {
                put("blinded_friends", encodeBlindedFriends(ourBlindedFriends))
            }
            writeFrame(output, clientFriendsJson.toString().toByteArray(Charsets.UTF_8))
            
            // Step 4: Read server's blinded friends
            val serverFriendsFrame = readFrame(input)
            val serverFriendsJson = JSONObject(String(serverFriendsFrame, Charsets.UTF_8))
            val remoteBlindedFriends = if (useTrust) {
                decodeBlindedFriends(serverFriendsJson.optJSONArray("blinded_friends"))
            } else {
                emptyList()
            }
            
            // Step 5: Read server's reply
            val serverReplyFrame = readFrame(input)
            val serverReplyJson = JSONObject(String(serverReplyFrame, Charsets.UTF_8))
            val remoteDoubleBlinded = decodeBlindedFriends(serverReplyJson.optJSONArray("double_blinded"))
            val remoteHashedBlinded = decodeBlindedFriends(serverReplyJson.optJSONArray("hashed_blinded"))
            
            // Step 6: Compute and send our reply
            val clientReply = serverPSI.replyToBlindedItems(ArrayList(remoteBlindedFriends))
            val clientReplyJson = JSONObject().apply {
                put("double_blinded", encodeBlindedFriends(clientReply.doubleBlindedItems))
                put("hashed_blinded", encodeBlindedFriends(clientReply.hashedBlindedItems))
            }
            writeFrame(output, clientReplyJson.toString().toByteArray(Charsets.UTF_8))
            
            // Step 7: Compute common friends
            val commonFriends = if (useTrust) {
                clientPSI.getCardinality(
                    Crypto.PrivateSetIntersection.ServerReplyTuple(
                        ArrayList(remoteDoubleBlinded),
                        ArrayList(remoteHashedBlinded)
                    )
                )
            } else {
                0
            }
            
            Timber.d("LAN PSI complete: $commonFriends common friends with ${peerDeviceId.take(8)}...")
            
            // Step 8: Check minimum shared contacts
            val minSharedContacts = SecurityManager.minSharedContactsForExchange(context)
            if (useTrust && commonFriends < minSharedContacts) {
                Timber.w("LAN exchange rejected: sharedContacts=$commonFriends minRequired=$minSharedContacts")
                return@withContext ExchangeResult(
                    false, 0, 0,
                    "Insufficient trust: $commonFriends < $minSharedContacts shared contacts"
                )
            }
            
            // Step 9: Send our messages
            val outgoingData = prepareOutgoingMessages(context, messageStore, friendStore, commonFriends)
            val messagesSent = try {
                val json = JSONObject(outgoingData)
                json.optInt("message_count", 0)
            } catch (e: Exception) { 0 }
            
            writeFrame(output, outgoingData.toByteArray(Charsets.UTF_8))
            
            // Step 10: Read server's messages
            val serverMessagesFrame = readFrame(input)
            val receivedMessages = processIncomingMessages(
                String(serverMessagesFrame, Charsets.UTF_8),
                messageStore,
                friendStore,
                commonFriends
            )
            
            val duration = System.currentTimeMillis() - startTime
            Timber.i("LAN exchange (client) complete in ${duration}ms: sent $messagesSent, received ${receivedMessages.size}, commonFriends=$commonFriends")
            
            trackTelemetry(
                "exchange_complete_client",
                "peer" to peerDeviceId.take(8),
                "sent" to messagesSent.toString(),
                "received" to receivedMessages.size.toString(),
                "common_friends" to commonFriends.toString(),
                "duration_ms" to duration.toString()
            )
            
            onExchangeComplete?.invoke(true, messagesSent, receivedMessages.size)
            
            return@withContext ExchangeResult(true, messagesSent, receivedMessages.size, null)
            
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            Timber.e(e, "LAN exchange failed with ${peer.ipAddress.hostAddress} after ${duration}ms")
            trackTelemetry(
                "exchange_error_client",
                "error" to e.message.toString(),
                "duration_ms" to duration.toString()
            )
            onExchangeComplete?.invoke(false, 0, 0)
            return@withContext ExchangeResult(false, 0, 0, e.message)
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
    
    // ========================================================================
    // Frame I/O - Length-prefixed to prevent unbounded reads
    // ========================================================================
    
    /**
     * Write a length-prefixed frame.
     * Format: [4 bytes big-endian length][payload]
     */
    private fun writeFrame(output: DataOutputStream, data: ByteArray) {
        if (data.size > MAX_FRAME_SIZE) {
            throw IOException("Frame too large: ${data.size} > $MAX_FRAME_SIZE")
        }
        output.writeInt(data.size)
        output.write(data)
        output.flush()
    }
    
    /**
     * Read a length-prefixed frame.
     * Enforces MAX_FRAME_SIZE to prevent memory exhaustion.
     */
    private fun readFrame(input: DataInputStream): ByteArray {
        val length = input.readInt()
        
        if (length < 0 || length > MAX_FRAME_SIZE) {
            throw IOException("Invalid frame length: $length (max: $MAX_FRAME_SIZE)")
        }
        
        val buffer = ByteArray(length)
        input.readFully(buffer)
        return buffer
    }
    
    // ========================================================================
    // PSI Helpers
    // ========================================================================
    
    /**
     * Encode blinded friends as JSON array of base64 strings.
     */
    private fun encodeBlindedFriends(friends: List<ByteArray>): JSONArray {
        val array = JSONArray()
        for (friend in friends) {
            array.put(android.util.Base64.encodeToString(friend, android.util.Base64.NO_WRAP))
        }
        return array
    }
    
    /**
     * Decode blinded friends from JSON array.
     */
    private fun decodeBlindedFriends(array: JSONArray?): List<ByteArray> {
        if (array == null) return emptyList()
        val result = mutableListOf<ByteArray>()
        for (i in 0 until array.length()) {
            val encoded = array.optString(i)
            if (encoded.isNotBlank()) {
                result.add(android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP))
            }
        }
        return result
    }
    
    // ========================================================================
    // Message Encoding/Decoding
    // ========================================================================
    
    /**
     * Prepare outgoing messages for exchange.
     * Uses commonFriends for proper trust computation.
     */
    private fun prepareOutgoingMessages(
        context: Context,
        messageStore: MessageStore,
        friendStore: FriendStore,
        commonFriends: Int
    ): String {
        val maxMessages = SecurityManager.maxMessagesPerExchange(context)
        val messages = messageStore.getMessagesForExchange(commonFriends, maxMessages)
        
        val myFriends = friendStore.getAllFriendIds().size
        val json = JSONObject().apply {
            put("protocol", "psi_v2")
            put("timestamp", System.currentTimeMillis())
            put("message_count", messages.size)
            put("common_friends", commonFriends)
            val msgArray = JSONArray()
            for (msg in messages) {
                val encoded = LegacyExchangeCodec.encodeMessage(context, msg, commonFriends, myFriends)
                msgArray.put(encoded)
            }
            put("messages", msgArray)
        }
        return json.toString()
    }
    
    /**
     * Process incoming messages from exchange.
     * Uses commonFriends for proper trust computation.
     */
    private fun processIncomingMessages(
        data: String,
        messageStore: MessageStore,
        friendStore: FriendStore,
        commonFriends: Int
    ): List<RangzenMessage> {
        return try {
            val json = JSONObject(data)
            
            // Check for error response
            if (json.has("error")) {
                Timber.w("LAN peer rejected exchange: ${json.optString("error")}")
                return emptyList()
            }
            
            val msgArray = json.optJSONArray("messages") ?: return emptyList()
            
            val received = mutableListOf<RangzenMessage>()
            val myFriendsCount = friendStore.getAllFriendIds().size
            
            for (i in 0 until msgArray.length()) {
                val msgJson = msgArray.getJSONObject(i)
                val msg = LegacyExchangeCodec.decodeMessage(msgJson)
                
                val existing = messageStore.getMessage(msg.messageId)
                if (existing != null) {
                    // Update trust using proper commonFriends value
                    val newTrust = LegacyExchangeMath.newPriority(
                        msg.trustScore,
                        existing.trustScore,
                        commonFriends,
                        myFriendsCount
                    )
                    if (newTrust > existing.trustScore) {
                        messageStore.updateTrustScore(msg.messageId, newTrust)
                    }
                } else if (msg.text != null && msg.text.isNotEmpty()) {
                    messageStore.addMessage(msg)
                    received.add(msg)
                }
            }
            
            received
        } catch (e: Exception) {
            Timber.e(e, "Failed to process incoming LAN messages")
            emptyList()
        }
    }
    
    // ========================================================================
    // Utility
    // ========================================================================
    
    /**
     * Check if an exchange is currently in progress.
     */
    fun isExchangeInProgress(): Boolean = exchangeInProgress.get()
    
    /**
     * Cleanup resources.
     */
    fun cleanup() {
        stopServer()
        Timber.d("LAN Transport cleaned up")
    }
    
    /**
     * Result of an exchange attempt.
     */
    data class ExchangeResult(
        val success: Boolean,
        val messagesSent: Int,
        val messagesReceived: Int,
        val error: String?
    )
    
    /**
     * Generate a random nonce for handshake freshness.
     */
    private fun generateNonce(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Track telemetry event.
     */
    private fun trackTelemetry(eventType: String, vararg params: Pair<String, String>) {
        try {
            val payload = mutableMapOf<String, Any>("event" to eventType)
            for ((key, value) in params) {
                payload[key] = value
            }
            TelemetryClient.getInstance()?.track(
                eventType = "lan_transport_$eventType",
                transport = "lan",
                payload = payload
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to track LAN transport telemetry")
        }
    }
}
