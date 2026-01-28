package org.denovogroup.rangzen.backend.wifiaware

import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WiFi Aware Message Exchange Protocol
 *
 * Uses WiFi Aware discovery messages (up to 255 bytes) for data exchange,
 * avoiding the complexity and fragility of NDP (NAN Data Path).
 *
 * Message Format:
 * | Type (1 byte) | Flags (1 byte) | Sequence (2 bytes) | Payload (up to 251 bytes) |
 *
 * This is simpler and more reliable than NDP because:
 * - No TCP connection establishment required
 * - No NDP interface contention issues
 * - Works on all WiFi Aware devices regardless of interface count
 * - Peer-to-peer messaging via discovery layer
 */
object WifiAwareMessageProtocol {
    private const val TAG = "WifiAwareProtocol"

    // Protocol version for compatibility checking
    const val PROTOCOL_VERSION: Byte = 1

    // Maximum WiFi Aware message size (conservative - spec allows 255)
    const val MAX_MESSAGE_SIZE = 250

    // Header size: type(1) + flags(1) + sequence(2) = 4 bytes
    const val HEADER_SIZE = 4

    // Maximum payload per message
    const val MAX_PAYLOAD_SIZE = MAX_MESSAGE_SIZE - HEADER_SIZE

    // Message types
    object MessageType {
        const val HELLO: Byte = 0x01        // Initiate exchange
        const val HELLO_ACK: Byte = 0x02    // Acknowledge hello
        const val MESSAGE: Byte = 0x03      // Actual message data
        const val MESSAGE_ACK: Byte = 0x04  // Acknowledge message receipt
        const val DONE: Byte = 0x05         // Exchange complete
        const val ERROR: Byte = 0x0F        // Error/abort
    }

    // Flags
    object Flags {
        const val NONE: Byte = 0x00
        const val MORE_COMING: Byte = 0x01  // More messages to send
        const val RETRY: Byte = 0x02        // This is a retry
    }

    /**
     * Protocol message container
     */
    data class ProtocolMessage(
        val type: Byte,
        val flags: Byte,
        val sequence: Int,  // 16-bit unsigned
        val payload: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ProtocolMessage) return false
            return type == other.type &&
                   flags == other.flags &&
                   sequence == other.sequence &&
                   payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = type.toInt()
            result = 31 * result + flags
            result = 31 * result + sequence
            result = 31 * result + payload.contentHashCode()
            return result
        }

        fun serialize(): ByteArray {
            val buffer = ByteBuffer.allocate(HEADER_SIZE + payload.size)
            buffer.order(ByteOrder.BIG_ENDIAN)
            buffer.put(type)
            buffer.put(flags)
            buffer.putShort(sequence.toShort())
            buffer.put(payload)
            return buffer.array()
        }

        companion object {
            fun deserialize(data: ByteArray): ProtocolMessage? {
                if (data.size < HEADER_SIZE) {
                    Timber.w("$TAG: Message too short: ${data.size} bytes")
                    return null
                }

                val buffer = ByteBuffer.wrap(data)
                buffer.order(ByteOrder.BIG_ENDIAN)

                val type = buffer.get()
                val flags = buffer.get()
                val sequence = buffer.short.toInt() and 0xFFFF  // Unsigned

                val payloadSize = data.size - HEADER_SIZE
                val payload = ByteArray(payloadSize)
                if (payloadSize > 0) {
                    buffer.get(payload)
                }

                return ProtocolMessage(type, flags, sequence, payload)
            }
        }
    }

    /**
     * HELLO message payload
     * Contains info needed to start exchange
     */
    data class HelloPayload(
        val protocolVersion: Byte,
        val publicIdPrefix: String,  // 8 chars
        val messageCount: Int,       // How many messages we have to send
        val maxBatchSize: Int        // Max messages per exchange (for flow control)
    ) {
        fun serialize(): ByteArray {
            val idBytes = publicIdPrefix.take(8).toByteArray(Charsets.UTF_8)
            val buffer = ByteBuffer.allocate(1 + 8 + 2 + 1)
            buffer.order(ByteOrder.BIG_ENDIAN)
            buffer.put(protocolVersion)
            buffer.put(idBytes.copyOf(8))  // Pad to 8 bytes
            buffer.putShort(messageCount.toShort())
            buffer.put(maxBatchSize.toByte())
            return buffer.array()
        }

        companion object {
            const val SIZE = 12  // 1 + 8 + 2 + 1

            fun deserialize(data: ByteArray): HelloPayload? {
                if (data.size < SIZE) {
                    Timber.w("$TAG: HelloPayload too short: ${data.size}")
                    return null
                }

                val buffer = ByteBuffer.wrap(data)
                buffer.order(ByteOrder.BIG_ENDIAN)

                val version = buffer.get()
                val idBytes = ByteArray(8)
                buffer.get(idBytes)
                val publicId = String(idBytes, Charsets.UTF_8).trim { it == '\u0000' }
                val messageCount = buffer.short.toInt() and 0xFFFF
                val maxBatch = buffer.get().toInt() and 0xFF

                return HelloPayload(version, publicId, messageCount, maxBatch)
            }
        }
    }

    /**
     * MESSAGE payload wrapper
     * Contains the actual Murmur message (140 bytes) plus metadata
     */
    data class MessagePayload(
        val messageIndex: Int,       // Which message in the batch (0-indexed)
        val totalMessages: Int,      // Total messages being sent
        val messageHash: ByteArray,  // First 8 bytes of message hash (for dedup)
        val messageData: ByteArray   // The actual message content (up to 140 bytes)
    ) {
        fun serialize(): ByteArray {
            val buffer = ByteBuffer.allocate(2 + 2 + 8 + messageData.size)
            buffer.order(ByteOrder.BIG_ENDIAN)
            buffer.putShort(messageIndex.toShort())
            buffer.putShort(totalMessages.toShort())
            buffer.put(messageHash.copyOf(8))
            buffer.put(messageData)
            return buffer.array()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MessagePayload) return false
            return messageIndex == other.messageIndex &&
                   totalMessages == other.totalMessages &&
                   messageHash.contentEquals(other.messageHash) &&
                   messageData.contentEquals(other.messageData)
        }

        override fun hashCode(): Int {
            var result = messageIndex
            result = 31 * result + totalMessages
            result = 31 * result + messageHash.contentHashCode()
            result = 31 * result + messageData.contentHashCode()
            return result
        }

        companion object {
            const val HEADER_SIZE = 12  // 2 + 2 + 8

            fun deserialize(data: ByteArray): MessagePayload? {
                if (data.size < HEADER_SIZE) {
                    Timber.w("$TAG: MessagePayload too short: ${data.size}")
                    return null
                }

                val buffer = ByteBuffer.wrap(data)
                buffer.order(ByteOrder.BIG_ENDIAN)

                val index = buffer.short.toInt() and 0xFFFF
                val total = buffer.short.toInt() and 0xFFFF
                val hash = ByteArray(8)
                buffer.get(hash)

                val messageSize = data.size - HEADER_SIZE
                val messageData = ByteArray(messageSize)
                if (messageSize > 0) {
                    buffer.get(messageData)
                }

                return MessagePayload(index, total, hash, messageData)
            }
        }
    }

    /**
     * MESSAGE_ACK payload
     */
    data class AckPayload(
        val ackedSequence: Int,  // Sequence number being acknowledged
        val receivedCount: Int   // Total messages received so far
    ) {
        fun serialize(): ByteArray {
            val buffer = ByteBuffer.allocate(4)
            buffer.order(ByteOrder.BIG_ENDIAN)
            buffer.putShort(ackedSequence.toShort())
            buffer.putShort(receivedCount.toShort())
            return buffer.array()
        }

        companion object {
            const val SIZE = 4

            fun deserialize(data: ByteArray): AckPayload? {
                if (data.size < SIZE) return null
                val buffer = ByteBuffer.wrap(data)
                buffer.order(ByteOrder.BIG_ENDIAN)
                val seq = buffer.short.toInt() and 0xFFFF
                val count = buffer.short.toInt() and 0xFFFF
                return AckPayload(seq, count)
            }
        }
    }

    // Helper functions for creating messages

    fun createHello(
        publicIdPrefix: String,
        messageCount: Int,
        maxBatchSize: Int = 10
    ): ProtocolMessage {
        val payload = HelloPayload(PROTOCOL_VERSION, publicIdPrefix, messageCount, maxBatchSize)
        return ProtocolMessage(
            type = MessageType.HELLO,
            flags = if (messageCount > 0) Flags.MORE_COMING else Flags.NONE,
            sequence = 0,
            payload = payload.serialize()
        )
    }

    fun createHelloAck(
        publicIdPrefix: String,
        messageCount: Int,
        maxBatchSize: Int = 10
    ): ProtocolMessage {
        val payload = HelloPayload(PROTOCOL_VERSION, publicIdPrefix, messageCount, maxBatchSize)
        return ProtocolMessage(
            type = MessageType.HELLO_ACK,
            flags = if (messageCount > 0) Flags.MORE_COMING else Flags.NONE,
            sequence = 0,
            payload = payload.serialize()
        )
    }

    fun createMessage(
        sequence: Int,
        messageIndex: Int,
        totalMessages: Int,
        messageHash: ByteArray,
        messageData: ByteArray,
        moreComing: Boolean
    ): ProtocolMessage {
        val payload = MessagePayload(messageIndex, totalMessages, messageHash, messageData)
        return ProtocolMessage(
            type = MessageType.MESSAGE,
            flags = if (moreComing) Flags.MORE_COMING else Flags.NONE,
            sequence = sequence,
            payload = payload.serialize()
        )
    }

    fun createMessageAck(ackedSequence: Int, receivedCount: Int): ProtocolMessage {
        val payload = AckPayload(ackedSequence, receivedCount)
        return ProtocolMessage(
            type = MessageType.MESSAGE_ACK,
            flags = Flags.NONE,
            sequence = ackedSequence,
            payload = payload.serialize()
        )
    }

    fun createDone(sequence: Int): ProtocolMessage {
        return ProtocolMessage(
            type = MessageType.DONE,
            flags = Flags.NONE,
            sequence = sequence,
            payload = ByteArray(0)
        )
    }

    fun createError(sequence: Int, errorCode: Byte = 0): ProtocolMessage {
        return ProtocolMessage(
            type = MessageType.ERROR,
            flags = Flags.NONE,
            sequence = sequence,
            payload = byteArrayOf(errorCode)
        )
    }

    fun messageTypeName(type: Byte): String = when (type) {
        MessageType.HELLO -> "HELLO"
        MessageType.HELLO_ACK -> "HELLO_ACK"
        MessageType.MESSAGE -> "MESSAGE"
        MessageType.MESSAGE_ACK -> "MESSAGE_ACK"
        MessageType.DONE -> "DONE"
        MessageType.ERROR -> "ERROR"
        else -> "UNKNOWN($type)"
    }
}
