package org.denovogroup.rangzen.backend.wifiaware

import android.net.wifi.aware.PeerHandle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import org.denovogroup.rangzen.backend.wifiaware.WifiAwareMessageProtocol.ProtocolMessage
import org.denovogroup.rangzen.backend.wifiaware.WifiAwareMessageProtocol.HelloPayload
import org.denovogroup.rangzen.backend.wifiaware.WifiAwareMessageProtocol.MessagePayload
import org.denovogroup.rangzen.backend.wifiaware.WifiAwareMessageProtocol.AckPayload
import org.denovogroup.rangzen.backend.wifiaware.WifiAwareMessageProtocol.MessageType
import org.denovogroup.rangzen.backend.wifiaware.WifiAwareMessageProtocol.PROTOCOL_VERSION
import org.denovogroup.rangzen.backend.wifiaware.WifiAwareMessageProtocol.createHello
import org.denovogroup.rangzen.backend.wifiaware.WifiAwareMessageProtocol.createHelloAck
import org.denovogroup.rangzen.backend.wifiaware.WifiAwareMessageProtocol.createMessage
import org.denovogroup.rangzen.backend.wifiaware.WifiAwareMessageProtocol.createMessageAck
import org.denovogroup.rangzen.backend.wifiaware.WifiAwareMessageProtocol.createDone
import org.denovogroup.rangzen.backend.wifiaware.WifiAwareMessageProtocol.createError
import org.denovogroup.rangzen.backend.wifiaware.WifiAwareMessageProtocol.messageTypeName

/**
 * Manages a single WiFi Aware message exchange with a peer.
 *
 * State machine:
 * IDLE -> HANDSHAKING -> EXCHANGING -> COMPLETING -> DONE/FAILED
 *
 * Flow:
 * 1. Initiator sends HELLO
 * 2. Responder sends HELLO_ACK
 * 3. Both sides exchange MESSAGE/MESSAGE_ACK
 * 4. Both sides send DONE when complete
 */
class WifiAwareExchange(
    val peerId: String,
    val peerHandle: PeerHandle,
    private val localPublicId: String,
    private val isInitiator: Boolean,
    private val sendMessage: suspend (PeerHandle, ByteArray) -> Boolean,
    private val getMessagesToSend: () -> List<MessageToSend>,
    private val onMessageReceived: (ByteArray) -> Unit,
    private val onExchangeComplete: (ExchangeResult) -> Unit
) {
    companion object {
        private const val TAG = "WifiAwareExchange"

        // Timeouts
        const val HANDSHAKE_TIMEOUT_MS = 10_000L
        const val MESSAGE_TIMEOUT_MS = 5_000L
        const val EXCHANGE_TIMEOUT_MS = 60_000L

        // Retries
        const val MAX_RETRIES = 3
        const val MAX_BATCH_SIZE = 10
    }

    enum class State {
        IDLE,
        HANDSHAKING,
        EXCHANGING,
        COMPLETING,
        DONE,
        FAILED
    }

    data class MessageToSend(
        val hash: ByteArray,
        val data: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MessageToSend) return false
            return hash.contentEquals(other.hash) && data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            return 31 * hash.contentHashCode() + data.contentHashCode()
        }
    }

    data class ExchangeResult(
        val success: Boolean,
        val messagesSent: Int,
        val messagesReceived: Int,
        val errorReason: String? = null
    )

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    private var scope: CoroutineScope? = null
    private var exchangeJob: Job? = null
    private var timeoutJob: Job? = null

    // Exchange state
    private var peerMessageCount = 0
    private var peerMaxBatch = MAX_BATCH_SIZE
    private var currentSequence = 1
    private var retryCount = 0

    // Messages to send/receive
    private var outgoingMessages = listOf<MessageToSend>()
    private var outgoingIndex = 0
    private val receivedHashes = mutableSetOf<String>()
    private var messagesSent = 0
    private var messagesReceived = 0

    // Pending ACKs
    private val pendingAcks = ConcurrentHashMap<Int, Job>()

    /**
     * Start the exchange
     */
    fun start(scope: CoroutineScope) {
        if (_state.value != State.IDLE) {
            Timber.w("$TAG: Cannot start - already in state ${_state.value}")
            return
        }

        this.scope = scope
        outgoingMessages = getMessagesToSend()

        Timber.i("$TAG: Starting exchange with $peerId, isInitiator=$isInitiator, " +
                "messagesToSend=${outgoingMessages.size}")

        // Start overall exchange timeout
        startExchangeTimeout()

        if (isInitiator) {
            // Send HELLO
            sendHello()
        } else {
            // Wait for HELLO
            _state.value = State.HANDSHAKING
            startHandshakeTimeout()
        }
    }

    /**
     * Handle incoming message from peer
     */
    fun onMessage(data: ByteArray) {
        val message = ProtocolMessage.deserialize(data)
        if (message == null) {
            Timber.w("$TAG: Failed to deserialize message from $peerId")
            return
        }

        Timber.d("$TAG: Received ${messageTypeName(message.type)} seq=${message.sequence} " +
                "from $peerId in state ${_state.value}")

        when (message.type) {
            MessageType.HELLO -> handleHello(message)
            MessageType.HELLO_ACK -> handleHelloAck(message)
            MessageType.MESSAGE -> handleMessage(message)
            MessageType.MESSAGE_ACK -> handleMessageAck(message)
            MessageType.DONE -> handleDone(message)
            MessageType.ERROR -> handleError(message)
            else -> Timber.w("$TAG: Unknown message type: ${message.type}")
        }
    }

    /**
     * Cancel the exchange
     */
    fun cancel(reason: String = "Cancelled") {
        Timber.i("$TAG: Cancelling exchange with $peerId: $reason")
        completeExchange(false, reason)
    }

    // === Message Handlers ===

    private fun handleHello(message: ProtocolMessage) {
        if (_state.value != State.HANDSHAKING && _state.value != State.IDLE) {
            Timber.w("$TAG: Received HELLO in wrong state: ${_state.value}")
            return
        }

        val hello = HelloPayload.deserialize(message.payload)
        if (hello == null) {
            Timber.w("$TAG: Failed to parse HELLO payload")
            sendError()
            return
        }

        if (hello.protocolVersion != PROTOCOL_VERSION) {
            Timber.w("$TAG: Protocol version mismatch: ${hello.protocolVersion} vs $PROTOCOL_VERSION")
            sendError()
            return
        }

        peerMessageCount = hello.messageCount
        peerMaxBatch = hello.maxBatchSize.coerceAtMost(MAX_BATCH_SIZE)

        Timber.i("$TAG: Peer $peerId has ${hello.messageCount} messages, maxBatch=${hello.maxBatchSize}")

        // Send HELLO_ACK
        cancelHandshakeTimeout()
        sendHelloAck()
    }

    private fun handleHelloAck(message: ProtocolMessage) {
        if (_state.value != State.HANDSHAKING) {
            Timber.w("$TAG: Received HELLO_ACK in wrong state: ${_state.value}")
            return
        }

        val helloAck = HelloPayload.deserialize(message.payload)
        if (helloAck == null) {
            Timber.w("$TAG: Failed to parse HELLO_ACK payload")
            sendError()
            return
        }

        peerMessageCount = helloAck.messageCount
        peerMaxBatch = helloAck.maxBatchSize.coerceAtMost(MAX_BATCH_SIZE)

        Timber.i("$TAG: Handshake complete with $peerId, peer has ${helloAck.messageCount} messages")

        cancelHandshakeTimeout()
        _state.value = State.EXCHANGING

        // Start sending messages if we have any
        if (outgoingMessages.isNotEmpty()) {
            sendNextMessage()
        } else if (peerMessageCount == 0) {
            // Neither side has messages - complete
            sendDone()
        }
        // Otherwise wait for peer's messages
    }

    private fun handleMessage(message: ProtocolMessage) {
        if (_state.value != State.EXCHANGING && _state.value != State.COMPLETING) {
            Timber.w("$TAG: Received MESSAGE in wrong state: ${_state.value}")
            return
        }

        val msgPayload = MessagePayload.deserialize(message.payload)
        if (msgPayload == null) {
            Timber.w("$TAG: Failed to parse MESSAGE payload")
            return
        }

        val hashHex = msgPayload.messageHash.toHexString()
        Timber.d("$TAG: Received message ${msgPayload.messageIndex + 1}/${msgPayload.totalMessages} " +
                "hash=$hashHex from $peerId")

        // Check for duplicate
        if (!receivedHashes.contains(hashHex)) {
            receivedHashes.add(hashHex)
            messagesReceived++

            // Deliver the message
            try {
                onMessageReceived(msgPayload.messageData)
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Error delivering message")
            }
        } else {
            Timber.d("$TAG: Duplicate message ignored: $hashHex")
        }

        // Send ACK
        sendMessageAck(message.sequence)

        // Check if we've received all expected messages
        if (messagesReceived >= peerMessageCount && messagesSent >= outgoingMessages.size) {
            sendDone()
        }
    }

    private fun handleMessageAck(message: ProtocolMessage) {
        val ack = AckPayload.deserialize(message.payload)
        if (ack == null) {
            Timber.w("$TAG: Failed to parse MESSAGE_ACK payload")
            return
        }

        Timber.d("$TAG: Received ACK for seq=${ack.ackedSequence}, peer received ${ack.receivedCount}")

        // Cancel retry timer for this sequence
        pendingAcks.remove(ack.ackedSequence)?.cancel()

        // Send next message if we have more
        if (outgoingIndex < outgoingMessages.size) {
            sendNextMessage()
        } else if (messagesReceived >= peerMessageCount) {
            // All messages exchanged
            sendDone()
        }
    }

    private fun handleDone(message: ProtocolMessage) {
        Timber.i("$TAG: Peer $peerId completed exchange")

        if (_state.value == State.COMPLETING) {
            // We already sent DONE, exchange is complete
            completeExchange(true)
        } else if (_state.value == State.EXCHANGING) {
            // Peer is done, we should finish up
            if (outgoingIndex >= outgoingMessages.size) {
                sendDone()
            } else {
                // Still have messages to send - continue
                _state.value = State.COMPLETING
            }
        }
    }

    private fun handleError(message: ProtocolMessage) {
        val errorCode = if (message.payload.isNotEmpty()) message.payload[0] else 0
        Timber.e("$TAG: Peer $peerId sent error: $errorCode")
        completeExchange(false, "Peer error: $errorCode")
    }

    // === Message Senders ===

    private fun sendHello() {
        _state.value = State.HANDSHAKING

        val hello = createHello(localPublicId, outgoingMessages.size, MAX_BATCH_SIZE)
        scope?.launch {
            val success = sendMessage(peerHandle, hello.serialize())
            if (success) {
                Timber.d("$TAG: Sent HELLO to $peerId")
                startHandshakeTimeout()
            } else {
                Timber.e("$TAG: Failed to send HELLO to $peerId")
                retryOrFail("Failed to send HELLO")
            }
        }
    }

    private fun sendHelloAck() {
        val helloAck = createHelloAck(localPublicId, outgoingMessages.size, MAX_BATCH_SIZE)
        scope?.launch {
            val success = sendMessage(peerHandle, helloAck.serialize())
            if (success) {
                Timber.d("$TAG: Sent HELLO_ACK to $peerId")
                _state.value = State.EXCHANGING

                // Start sending messages if we have any
                if (outgoingMessages.isNotEmpty()) {
                    sendNextMessage()
                } else if (peerMessageCount == 0) {
                    sendDone()
                }
            } else {
                Timber.e("$TAG: Failed to send HELLO_ACK to $peerId")
                completeExchange(false, "Failed to send HELLO_ACK")
            }
        }
    }

    private fun sendNextMessage() {
        if (outgoingIndex >= outgoingMessages.size) return

        val msg = outgoingMessages[outgoingIndex]
        val seq = currentSequence++
        val moreComing = outgoingIndex < outgoingMessages.size - 1

        val protocolMsg = createMessage(
            sequence = seq,
            messageIndex = outgoingIndex,
            totalMessages = outgoingMessages.size,
            messageHash = msg.hash,
            messageData = msg.data,
            moreComing = moreComing
        )

        outgoingIndex++
        messagesSent++

        scope?.launch {
            val success = sendMessage(peerHandle, protocolMsg.serialize())
            if (success) {
                Timber.d("$TAG: Sent MESSAGE seq=$seq (${outgoingIndex}/${outgoingMessages.size}) to $peerId")
                startMessageAckTimeout(seq)
            } else {
                Timber.e("$TAG: Failed to send MESSAGE to $peerId")
                retryOrFail("Failed to send message")
            }
        }
    }

    private fun sendMessageAck(ackedSequence: Int) {
        val ack = createMessageAck(ackedSequence, messagesReceived)
        scope?.launch {
            sendMessage(peerHandle, ack.serialize())
            // Don't fail on ACK send failure - peer will retry
        }
    }

    private fun sendDone() {
        if (_state.value == State.COMPLETING || _state.value == State.DONE) return

        _state.value = State.COMPLETING
        val done = createDone(currentSequence++)

        scope?.launch {
            val success = sendMessage(peerHandle, done.serialize())
            if (success) {
                Timber.d("$TAG: Sent DONE to $peerId")
                // Give peer a moment to send their DONE, then complete
                delay(1000)
                if (_state.value == State.COMPLETING) {
                    completeExchange(true)
                }
            } else {
                // Still complete successfully - we got all messages
                completeExchange(true)
            }
        }
    }

    private fun sendError() {
        val error = createError(currentSequence++)
        scope?.launch {
            sendMessage(peerHandle, error.serialize())
            completeExchange(false, "Protocol error")
        }
    }

    // === Timeouts ===

    private fun startExchangeTimeout() {
        timeoutJob?.cancel()
        timeoutJob = scope?.launch {
            delay(EXCHANGE_TIMEOUT_MS)
            if (_state.value != State.DONE && _state.value != State.FAILED) {
                Timber.w("$TAG: Exchange timeout with $peerId")
                completeExchange(false, "Exchange timeout")
            }
        }
    }

    private fun startHandshakeTimeout() {
        scope?.launch {
            delay(HANDSHAKE_TIMEOUT_MS)
            if (_state.value == State.HANDSHAKING) {
                Timber.w("$TAG: Handshake timeout with $peerId")
                retryOrFail("Handshake timeout")
            }
        }
    }

    private fun cancelHandshakeTimeout() {
        // Handled by state change
    }

    private fun startMessageAckTimeout(sequence: Int) {
        val job = scope?.launch {
            delay(MESSAGE_TIMEOUT_MS)
            if (pendingAcks.containsKey(sequence)) {
                Timber.w("$TAG: ACK timeout for seq=$sequence to $peerId")
                // Retry the message
                pendingAcks.remove(sequence)
                outgoingIndex = (outgoingIndex - 1).coerceAtLeast(0)
                messagesSent = (messagesSent - 1).coerceAtLeast(0)
                retryOrFail("ACK timeout")
            }
        }
        if (job != null) {
            pendingAcks[sequence] = job
        }
    }

    // === Helpers ===

    private fun retryOrFail(reason: String) {
        retryCount++
        if (retryCount >= MAX_RETRIES) {
            completeExchange(false, "$reason after $MAX_RETRIES retries")
        } else {
            Timber.d("$TAG: Retrying ($retryCount/$MAX_RETRIES): $reason")
            when (_state.value) {
                State.HANDSHAKING -> if (isInitiator) sendHello()
                State.EXCHANGING -> sendNextMessage()
                else -> completeExchange(false, reason)
            }
        }
    }

    private fun completeExchange(success: Boolean, errorReason: String? = null) {
        if (_state.value == State.DONE || _state.value == State.FAILED) return

        _state.value = if (success) State.DONE else State.FAILED
        timeoutJob?.cancel()
        pendingAcks.values.forEach { it.cancel() }
        pendingAcks.clear()

        val result = ExchangeResult(
            success = success,
            messagesSent = messagesSent,
            messagesReceived = messagesReceived,
            errorReason = errorReason
        )

        Timber.i("$TAG: Exchange with $peerId ${if (success) "completed" else "failed"}: " +
                "sent=$messagesSent, received=$messagesReceived" +
                (errorReason?.let { ", error=$it" } ?: ""))

        onExchangeComplete(result)
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }
}
