/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * WiFi Direct socket transport for high-bandwidth message exchange
 */
package org.denovogroup.rangzen.backend.wifi

import kotlinx.coroutines.*
import org.denovogroup.rangzen.backend.discovery.PeerHandshake
import org.denovogroup.rangzen.backend.discovery.TransportType
import org.denovogroup.rangzen.backend.telemetry.ErrorCategory
import org.denovogroup.rangzen.backend.telemetry.ErrorClassifier
import org.denovogroup.rangzen.backend.telemetry.ExchangeContext
import org.denovogroup.rangzen.backend.telemetry.ExchangeStage
import org.denovogroup.rangzen.backend.telemetry.TelemetryClient
import org.denovogroup.rangzen.backend.telemetry.TelemetryEvent
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * WiFi Direct socket transport for message exchange.
 * 
 * This provides higher bandwidth than BLE for bulk data transfer.
 * Used when WiFi Direct connection is established between peers.
 * 
 * Protocol:
 * 1. Group owner starts server socket
 * 2. Client connects to group owner's IP
 * 3. Both sides exchange handshake (public ID, capabilities)
 * 4. Proceed with legacy exchange protocol over the socket
 */
class WifiDirectTransport {
    
    companion object {
        private const val TAG = "WifiDirectTransport"
        
        /** Port for WiFi Direct message exchange */
        const val TRANSPORT_PORT = 8988
        
        /** Socket connection timeout */
        private const val CONNECT_TIMEOUT_MS = 10_000
        
        /** Socket read timeout */
        private const val READ_TIMEOUT_MS = 30_000
        
        /** Max message size (16 MB should be plenty for text messages) */
        private const val MAX_MESSAGE_SIZE = 16 * 1024 * 1024
        
        /** Protocol version */
        private const val PROTOCOL_VERSION: Byte = 1
        
        /** Message types */
        private const val MSG_HANDSHAKE: Byte = 0x01
        private const val MSG_DATA: Byte = 0x02
        private const val MSG_ACK: Byte = 0x03
        private const val MSG_ERROR: Byte = 0x04
    }
    
    /** Currently active server socket (if we're group owner) */
    private var serverSocket: ServerSocket? = null
    
    /** Whether the server is running */
    @Volatile
    private var isServerRunning = false
    
    /** Server job for cancellation */
    private var serverJob: Job? = null
    
    /** Callback when data is received from a peer */
    var onDataReceived: ((peerPublicId: String, data: ByteArray) -> ByteArray?)? = null
    
    /** Callback when handshake is completed with a peer */
    var onHandshakeComplete: ((peerPublicId: String, socket: Socket) -> Unit)? = null
    
    /**
     * Start the server socket to accept incoming connections.
     * Call this when we become the WiFi Direct group owner.
     */
    fun startServer(scope: CoroutineScope, localPublicId: String) {
        if (isServerRunning) {
            Timber.w("$TAG: Server already running")
            return
        }
        
        serverJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(TRANSPORT_PORT).apply {
                    reuseAddress = true
                    soTimeout = 0 // No timeout - will be cancelled via close()
                }
                isServerRunning = true
                Timber.i("$TAG: Server started on port $TRANSPORT_PORT")
                trackTelemetry("server_started", "port" to TRANSPORT_PORT.toString())
                
                while (isActive && isServerRunning) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        Timber.i("$TAG: Client connected from ${clientSocket.inetAddress}")
                        
                        // Handle each client in a separate coroutine
                        launch {
                            handleClientConnection(clientSocket, localPublicId)
                        }
                    } catch (e: SocketTimeoutException) {
                        // Normal timeout, continue listening
                    } catch (e: IOException) {
                        if (isActive && isServerRunning) {
                            Timber.e(e, "$TAG: Error accepting connection")
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Server error")
                trackTelemetry("server_error", "error" to e.message.orEmpty())
            } finally {
                isServerRunning = false
                serverSocket?.close()
                serverSocket = null
                Timber.i("$TAG: Server stopped")
            }
        }
    }
    
    /**
     * Stop the server socket.
     */
    fun stopServer() {
        isServerRunning = false
        serverJob?.cancel()
        serverSocket?.close()
        serverSocket = null
        Timber.i("$TAG: Server stop requested")
    }
    
    /**
     * Connect to a peer's server as a client.
     * Call this when we're not the group owner and want to exchange with the GO.
     *
     * @param groupOwnerAddress IP address of the WiFi Direct group owner
     * @param localPublicId Our public identifier
     * @param data Data to send for exchange
     * @param context Android context for telemetry (optional)
     * @return Response data from the peer, or null on failure
     */
    suspend fun connectAndExchange(
        groupOwnerAddress: InetAddress,
        localPublicId: String,
        data: ByteArray,
        context: android.content.Context? = null
    ): ExchangeResult? = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        val peerIdHash = groupOwnerAddress.hostAddress?.take(16) ?: "unknown"
        val exchangeCtx = context?.let { ExchangeContext.create("wifi_direct", peerIdHash, it) }

        try {
            Timber.i("$TAG: Connecting to ${groupOwnerAddress.hostAddress}:$TRANSPORT_PORT")
            exchangeCtx?.advanceStage(ExchangeStage.CONNECTING)
            trackTelemetry("client_connecting", "address" to groupOwnerAddress.hostAddress.orEmpty())

            socket = Socket()
            socket.connect(
                InetSocketAddress(groupOwnerAddress, TRANSPORT_PORT),
                CONNECT_TIMEOUT_MS
            )
            socket.soTimeout = READ_TIMEOUT_MS
            exchangeCtx?.advanceStage(ExchangeStage.CONNECTED)

            Timber.i("$TAG: Connected, performing handshake")

            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

            exchangeCtx?.advanceStage(ExchangeStage.PROTOCOL_VERSION)

            // Send handshake
            val handshake = PeerHandshake.createHandshake(
                publicId = localPublicId,
                availableTransports = listOf(TransportType.WIFI_DIRECT, TransportType.BLE)
            )
            sendMessage(output, MSG_HANDSHAKE, PeerHandshake.encode(handshake))

            // Receive handshake response
            val (peerMsgType, peerHandshakeBytes) = receiveMessage(input)
            if (peerMsgType != MSG_HANDSHAKE) {
                Timber.e("$TAG: Expected handshake, got type $peerMsgType")
                exchangeCtx?.let {
                    TelemetryClient.getInstance()?.trackExchangeFailure(it, ErrorCategory.UNEXPECTED_RESPONSE, "Expected handshake, got type $peerMsgType")
                }
                return@withContext null
            }

            val peerHandshake = PeerHandshake.decode(peerHandshakeBytes)
            if (peerHandshake == null) {
                Timber.e("$TAG: Failed to decode peer handshake")
                exchangeCtx?.let {
                    TelemetryClient.getInstance()?.trackExchangeFailure(it, ErrorCategory.MESSAGE_PARSE_ERROR, "Failed to decode peer handshake")
                }
                return@withContext null
            }

            Timber.i("$TAG: Handshake complete with peer: ${peerHandshake.publicId}")
            trackTelemetry("handshake_complete", "peer_id" to peerHandshake.publicId)

            exchangeCtx?.advanceStage(ExchangeStage.SENDING_MESSAGES)
            exchangeCtx?.bytesSent = data.size.toLong()

            // Send data
            sendMessage(output, MSG_DATA, data)
            Timber.i("$TAG: Sent ${data.size} bytes")

            exchangeCtx?.advanceStage(ExchangeStage.RECEIVING_MESSAGES)

            // Receive response
            val (responseMsgType, responseData) = receiveMessage(input)
            if (responseMsgType == MSG_ERROR) {
                Timber.e("$TAG: Peer returned error: ${String(responseData)}")
                exchangeCtx?.let {
                    TelemetryClient.getInstance()?.trackExchangeFailure(it, ErrorCategory.UNEXPECTED_RESPONSE, "Peer error: ${String(responseData).take(100)}")
                }
                return@withContext null
            }
            if (responseMsgType != MSG_DATA) {
                Timber.e("$TAG: Expected data response, got type $responseMsgType")
                exchangeCtx?.let {
                    TelemetryClient.getInstance()?.trackExchangeFailure(it, ErrorCategory.UNEXPECTED_RESPONSE, "Expected data, got type $responseMsgType")
                }
                return@withContext null
            }

            exchangeCtx?.bytesReceived = responseData.size.toLong()
            exchangeCtx?.advanceStage(ExchangeStage.COMPLETE)

            Timber.i("$TAG: Received ${responseData.size} bytes response")

            // Track success with rich context
            exchangeCtx?.let { TelemetryClient.getInstance()?.trackExchangeSuccess(it) }

            ExchangeResult(
                peerPublicId = peerHandshake.publicId,
                responseData = responseData
            )

        } catch (e: SocketTimeoutException) {
            Timber.e(e, "$TAG: Connection timed out")
            exchangeCtx?.let { TelemetryClient.getInstance()?.trackExchangeFailure(it, e) }
                ?: trackTelemetry("client_timeout")
            null
        } catch (e: IOException) {
            Timber.e(e, "$TAG: Connection error")
            exchangeCtx?.let { TelemetryClient.getInstance()?.trackExchangeFailure(it, e) }
                ?: trackTelemetry("client_error", "error" to e.message.orEmpty())
            null
        } finally {
            socket?.close()
        }
    }
    
    /**
     * Handle an incoming client connection.
     */
    private suspend fun handleClientConnection(socket: Socket, localPublicId: String) {
        try {
            socket.soTimeout = READ_TIMEOUT_MS
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            
            // Receive client handshake
            val (msgType, handshakeBytes) = receiveMessage(input)
            if (msgType != MSG_HANDSHAKE) {
                Timber.e("$TAG: Expected handshake, got type $msgType")
                sendMessage(output, MSG_ERROR, "Expected handshake".toByteArray())
                return
            }
            
            val peerHandshake = PeerHandshake.decode(handshakeBytes)
            if (peerHandshake == null) {
                Timber.e("$TAG: Failed to decode client handshake")
                sendMessage(output, MSG_ERROR, "Invalid handshake".toByteArray())
                return
            }
            
            // Send our handshake
            val ourHandshake = PeerHandshake.createHandshake(
                publicId = localPublicId,
                availableTransports = listOf(TransportType.WIFI_DIRECT, TransportType.BLE)
            )
            sendMessage(output, MSG_HANDSHAKE, PeerHandshake.encode(ourHandshake))
            
            Timber.i("$TAG: Server handshake complete with peer: ${peerHandshake.publicId}")
            trackTelemetry("server_handshake_complete", "peer_id" to peerHandshake.publicId)
            
            onHandshakeComplete?.invoke(peerHandshake.publicId, socket)
            
            // Receive data
            val (dataMsgType, data) = receiveMessage(input)
            if (dataMsgType != MSG_DATA) {
                Timber.e("$TAG: Expected data, got type $dataMsgType")
                sendMessage(output, MSG_ERROR, "Expected data".toByteArray())
                return
            }
            
            Timber.i("$TAG: Received ${data.size} bytes from ${peerHandshake.publicId}")
            
            // Process data and get response
            val response = onDataReceived?.invoke(peerHandshake.publicId, data)
            
            if (response != null) {
                sendMessage(output, MSG_DATA, response)
                Timber.i("$TAG: Sent ${response.size} bytes response")
                trackTelemetry(
                    "server_exchange_complete",
                    "peer_id" to peerHandshake.publicId,
                    "received_bytes" to data.size.toString(),
                    "sent_bytes" to response.size.toString()
                )
            } else {
                sendMessage(output, MSG_ERROR, "Processing failed".toByteArray())
            }
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error handling client")
            trackTelemetry("server_client_error", "error" to e.message.orEmpty())
        } finally {
            socket.close()
        }
    }
    
    /**
     * Send a framed message.
     */
    private fun sendMessage(output: DataOutputStream, type: Byte, data: ByteArray) {
        output.writeByte(PROTOCOL_VERSION.toInt())
        output.writeByte(type.toInt())
        output.writeInt(data.size)
        output.write(data)
        output.flush()
    }
    
    /**
     * Receive a framed message.
     * @return Pair of (message type, message data)
     */
    private fun receiveMessage(input: DataInputStream): Pair<Byte, ByteArray> {
        val version = input.readByte()
        if (version != PROTOCOL_VERSION) {
            throw IOException("Unknown protocol version: $version")
        }
        
        val type = input.readByte()
        val length = input.readInt()
        
        if (length < 0 || length > MAX_MESSAGE_SIZE) {
            throw IOException("Invalid message length: $length")
        }
        
        val data = ByteArray(length)
        input.readFully(data)
        
        return type to data
    }
    
    /**
     * Track telemetry for WiFi Direct transport events.
     */
    private fun trackTelemetry(event: String, vararg params: Pair<String, String>) {
        try {
            val payload = mutableMapOf<String, Any>("event" to event)
            params.forEach { (key, value) -> payload[key] = value }
            TelemetryClient.getInstance()?.track(
                eventType = "wifi_direct_transport_$event",
                transport = "wifi_direct",
                payload = payload
            )
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Failed to track telemetry")
        }
    }
    
    /**
     * Result of a WiFi Direct exchange.
     */
    data class ExchangeResult(
        val peerPublicId: String,
        val responseData: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ExchangeResult
            return peerPublicId == other.peerPublicId && responseData.contentEquals(other.responseData)
        }
        
        override fun hashCode(): Int {
            var result = peerPublicId.hashCode()
            result = 31 * result + responseData.contentHashCode()
            return result
        }
    }
}
