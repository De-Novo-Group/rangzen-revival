/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * Transport framing for BLE to carry legacy Rangzen length/value messages.
 */
package org.denovogroup.rangzen.backend.ble

import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Simple transport frame for chunking large payloads over BLE.
 *
 * Frame layout:
 *  - 1 byte: op code
 *  - 4 bytes: total length (big-endian)
 *  - 4 bytes: offset (big-endian)
 *  - N bytes: payload
 */
data class TransportFrame(
    val op: Byte,
    val totalLength: Int,
    val offset: Int,
    val payload: ByteArray
)

/**
 * Shared transport framing helpers.
 */
object TransportProtocol {
    // Transport op codes.
    const val OP_DATA: Byte = 0x01
    const val OP_CONTINUE: Byte = 0x02
    const val OP_ACK: Byte = 0x03

    // Header sizes.
    private const val HEADER_SIZE = 1 + 4 + 4

    fun headerSize(): Int = HEADER_SIZE

    /**
     * Encode a transport frame into bytes.
     */
    fun encode(frame: TransportFrame): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE + frame.payload.size)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.put(frame.op)
        buffer.putInt(frame.totalLength)
        buffer.putInt(frame.offset)
        buffer.put(frame.payload)
        return buffer.array()
    }

    /**
     * Decode a transport frame from raw bytes.
     */
    fun decode(bytes: ByteArray): TransportFrame? {
        if (bytes.size < HEADER_SIZE) {
            Timber.e("Transport frame too small: size=${bytes.size}")
            return null
        }
        val buffer = ByteBuffer.wrap(bytes)
        buffer.order(ByteOrder.BIG_ENDIAN)
        val op = buffer.get()
        val totalLength = buffer.int
        val offset = buffer.int
        val payloadSize = bytes.size - HEADER_SIZE
        val payload = ByteArray(payloadSize)
        buffer.get(payload)
        return TransportFrame(op, totalLength, offset, payload)
    }
}
