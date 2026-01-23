/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * LAN Transport for high-bandwidth message exchange over TCP sockets.
 * 
 * When devices discover each other via LAN (UDP broadcast), they can
 * use this TCP transport for fast bulk message exchange.
 * 
 * Protocol:
 * 1. Client connects to peer's TCP port
 * 2. Send handshake with device ID and nonce
 * 3. Exchange messages in both directions
 * 4. Close connection
 */
package org.denovogroup.rangzen.backend.lan

import android.content.Context
import kotlinx.coroutines.*
import org.denovogroup.rangzen.backend.FriendStore
import org.denovogroup.rangzen.backend.MessageStore
import org.denovogroup.rangzen.backend.SecurityManager
import org.denovogroup.rangzen.backend.legacy.LegacyExchangeCodec
import org.denovogroup.rangzen.backend.legacy.LegacyExchangeMath
import org.denovogroup.rangzen.backend.telemetry.TelemetryClient
import org.denovogroup.rangzen.objects.RangzenMessage
import org.json.JSONObject
import timber.log.Timber
import java.io.*
import java.net.*
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LAN Transport for TCP-based message exchange.
 * 
 * Provides:
 * - Server socket for accepting incoming connections
 * - Client connection for initiating exchanges with discovered peers
 * - Simplified exchange protocol (no PSI) for fast bulk transfer
 */
class LanTransport {
    
    companion object {
        // TCP port for message exchange (different from UDP discovery port)
        const val EXCHANGE_PORT = 41235
        
        // Protocol constants
        const val PROTOCOL_VERSION = 1
        const val HANDSHAKE_TIMEOUT_MS = 5_000
        const val EXCHANGE_TIMEOUT_MS = 30_000
        const val MAX_MESSAGE_SIZE = 1024 * 1024  // 1 MB max per exchange
    }
    
    /** Server socket for incoming connections */
    private var serverSocket: ServerSocket? = null
    
    /** Whether server is running */
    private val serverRunning = AtomicBoolean(false)
    
    /** Whether an exchange is currently in progress */
    private val exchangeInProgress = AtomicBoolean(false)
    
    /** Coroutine scope for server operations */
    private var serverScope: CoroutineScope? = null
    
    /** Our device identifier */
    private var localDeviceId: String = ""
    
    /** Callback when exchange completes */
    var onExchangeComplete: ((success: Boolean, messagesSent: Int, messagesReceived: Int) -> Unit)? = null
    
    /**
     * Initialize the transport with our device ID.
     */
    fun initialize(deviceId: String) {
        this.localDeviceId = deviceId
        Timber.i("LAN Transport initialized")
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
        
        Timber.i("LAN Transport server started on port $EXCHANGE_PORT")
        trackTelemetry("server_started", "port" to EXCHANGE_PORT.toString())
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
     * Handle an incoming client connection.
     */
    private suspend fun handleClientConnection(
        socket: Socket,
        context: Context,
        messageStore: MessageStore,
        friendStore: FriendStore
    ) {
        val clientAddress = socket.remoteSocketAddress.toString()
        Timber.i("LAN client connected from $clientAddress")
        
        try {
            socket.soTimeout = EXCHANGE_TIMEOUT_MS
            
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
            
            // Read handshake
            val handshakeJson = input.readLine() ?: throw IOException("No handshake received")
            val handshake = JSONObject(handshakeJson)
            
            val peerDeviceId = handshake.optString("device_id")
            val peerNonce = handshake.optString("nonce")
            
            if (peerDeviceId.isBlank()) {
                throw IOException("Invalid handshake: missing device_id")
            }
            
            Timber.d("LAN handshake from peer: ${peerDeviceId.take(8)}...")
            
            // Send our handshake response
            val responseHandshake = JSONObject().apply {
                put("device_id", localDeviceId)
                put("nonce", peerNonce)  // Echo back their nonce
                put("version", PROTOCOL_VERSION)
            }
            output.write(responseHandshake.toString())
            output.newLine()
            output.flush()
            
            // Read their messages
            val incomingData = input.readLine() ?: ""
            val receivedMessages = if (incomingData.isNotBlank()) {
                processIncomingMessages(incomingData, messageStore, friendStore)
            } else {
                emptyList()
            }
            
            // Send our messages
            val outgoingData = prepareOutgoingMessages(context, messageStore, friendStore)
            if (outgoingData != null) {
                output.write(outgoingData)
            }
            output.newLine()
            output.flush()
            
            Timber.i("LAN exchange complete: sent=${outgoingData?.length ?: 0} bytes, received ${receivedMessages.size} messages")
            trackTelemetry(
                "exchange_complete_server",
                "peer" to peerDeviceId.take(8),
                "received" to receivedMessages.size.toString()
            )
            
            onExchangeComplete?.invoke(true, 0, receivedMessages.size)
            
        } catch (e: Exception) {
            Timber.e(e, "LAN exchange failed with client $clientAddress")
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
     * Initiate an exchange with a discovered LAN peer.
     * 
     * @param peer The LAN peer to connect to
     * @param context Android context
     * @param messageStore Message store for exchange
     * @param friendStore Friend store for trust computation
     * @return ExchangeResult with success status and message counts
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
     * Perform the actual exchange with a peer.
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
            Timber.i("Starting LAN exchange with ${peer.deviceId.take(8)}... at ${peer.ipAddress.hostAddress}")
            
            // Connect to peer
            socket = Socket()
            socket.connect(InetSocketAddress(peer.ipAddress, EXCHANGE_PORT), HANDSHAKE_TIMEOUT_MS)
            socket.soTimeout = EXCHANGE_TIMEOUT_MS
            
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
            
            // Send handshake
            val nonce = generateNonce()
            val handshake = JSONObject().apply {
                put("device_id", localDeviceId)
                put("nonce", nonce)
                put("version", PROTOCOL_VERSION as Any)
            }
            output.write(handshake.toString())
            output.newLine()
            output.flush()
            
            // Read handshake response
            val responseJson = input.readLine() ?: throw IOException("No handshake response")
            val response = JSONObject(responseJson)
            
            val peerDeviceId = response.optString("device_id")
            val echoedNonce = response.optString("nonce")
            
            // Verify nonce
            if (echoedNonce != nonce) {
                throw IOException("Nonce mismatch - possible MITM")
            }
            
            Timber.d("LAN handshake verified with peer: ${peerDeviceId.take(8)}...")
            
            // Send our messages
            val outgoingData = prepareOutgoingMessages(context, messageStore, friendStore)
            val messagesSent = if (outgoingData != null) {
                output.write(outgoingData)
                // Count messages in the JSON
                try {
                    val json = JSONObject(outgoingData)
                    json.optInt("message_count", 0)
                } catch (e: Exception) {
                    0
                }
            } else {
                0
            }
            output.newLine()
            output.flush()
            
            // Read their messages
            val incomingData = input.readLine() ?: ""
            val receivedMessages = if (incomingData.isNotBlank()) {
                processIncomingMessages(incomingData, messageStore, friendStore)
            } else {
                emptyList()
            }
            
            val duration = System.currentTimeMillis() - startTime
            Timber.i("LAN exchange complete in ${duration}ms: sent $messagesSent, received ${receivedMessages.size}")
            
            trackTelemetry(
                "exchange_complete_client",
                "peer" to peerDeviceId.take(8),
                "sent" to messagesSent.toString(),
                "received" to receivedMessages.size.toString(),
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
     * Prepare outgoing messages for exchange.
     * Simplified protocol - no PSI, just message encoding.
     */
    private fun prepareOutgoingMessages(
        context: Context,
        messageStore: MessageStore,
        friendStore: FriendStore
    ): String? {
        val maxMessages = SecurityManager.maxMessagesPerExchange(context)
        // Use 0 common friends for simplified exchange (no PSI)
        val messages = messageStore.getMessagesForExchange(0, maxMessages)
        if (messages.isEmpty()) return null
        
        val myFriends = friendStore.getAllFriendIds().size
        val json = JSONObject().apply {
            put("protocol", "simplified_v1")
            put("timestamp", System.currentTimeMillis())
            put("message_count", messages.size)
            val msgArray = org.json.JSONArray()
            for (msg in messages) {
                // Encode without PSI context (0 common friends)
                val encoded = LegacyExchangeCodec.encodeMessage(context, msg, 0, myFriends)
                msgArray.put(encoded)
            }
            put("messages", msgArray)
        }
        return json.toString()
    }
    
    /**
     * Process incoming messages from exchange.
     * Decodes messages and merges them into the local store.
     */
    private fun processIncomingMessages(
        data: String,
        messageStore: MessageStore,
        friendStore: FriendStore
    ): List<RangzenMessage> {
        return try {
            val json = JSONObject(data)
            val msgArray = json.optJSONArray("messages") ?: return emptyList()
            
            val received = mutableListOf<RangzenMessage>()
            val myFriendsCount = friendStore.getAllFriendIds().size
            
            for (i in 0 until msgArray.length()) {
                val msgJson = msgArray.getJSONObject(i)
                val msg = LegacyExchangeCodec.decodeMessage(msgJson)
                
                val existing = messageStore.getMessage(msg.messageId)
                if (existing != null) {
                    // Update trust if new value is higher
                    val newTrust = LegacyExchangeMath.newPriority(
                        msg.trustScore,
                        existing.trustScore,
                        0, // No PSI context in simplified exchange
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
