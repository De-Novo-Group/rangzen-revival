/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * App-layer handshake protocol for peer correlation across transports
 */
package org.denovogroup.rangzen.backend.discovery

import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * App-layer handshake protocol for correlating peers across transports.
 * 
 * When any transport connects to a peer, we exchange a small handshake message:
 * - Our public ID (or hashed ID for privacy)
 * - A short nonce for freshness
 * - Recent discovery tokens from other transports
 * 
 * This allows correlation without relying on device names or MAC addresses.
 * 
 * The handshake is transport-agnostic and can be sent over:
 * - BLE GATT characteristic
 * - WiFi Direct socket
 * - LAN TCP connection
 */
object PeerHandshake {
    
    private const val TAG = "PeerHandshake"
    
    /** Protocol version for future compatibility */
    const val PROTOCOL_VERSION = 1
    
    /** JSON keys */
    private const val KEY_VERSION = "v"
    private const val KEY_PUBLIC_ID = "id"
    private const val KEY_NONCE = "n"
    private const val KEY_DISCOVERY_TOKENS = "dt"
    private const val KEY_TRANSPORTS = "tr"
    private const val KEY_TIMESTAMP = "ts"
    private const val KEY_DEVICE_ID_HASH = "dih"
    private const val KEY_EXCHANGE_ID = "xid"
    
    /** Nonce size in bytes */
    private const val NONCE_SIZE = 8
    
    private val secureRandom = SecureRandom()
    
    /**
     * Represents a handshake message to send or that was received.
     */
    data class HandshakeMessage(
        val version: Int,
        val publicId: String,
        val nonce: ByteArray,
        val discoveryTokens: List<String>,
        val availableTransports: List<String>,
        val timestamp: Long,
        /** Identity contract: device_id_hash for consistent cross-transport identity. */
        val deviceIdHash: String? = null,
        /** Exchange pairing: shared exchange_id so both peers emit the same ID. */
        val exchangeId: String? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as HandshakeMessage
            return version == other.version && 
                   publicId == other.publicId && 
                   nonce.contentEquals(other.nonce)
        }
        
        override fun hashCode(): Int {
            var result = version
            result = 31 * result + publicId.hashCode()
            result = 31 * result + nonce.contentHashCode()
            return result
        }
    }
    
    /**
     * Generate a discovery token for a specific transport address.
     * This token can be included in handshakes via other transports for correlation.
     * 
     * @param transportId The transport identifier (e.g., "ble", "wifi_direct")
     * @param address The transport-specific address
     * @param secret A device-specific secret for token generation
     * @return A short token (hex string) that proves we control this address
     */
    fun generateDiscoveryToken(transportId: String, address: String, secret: ByteArray): String {
        val data = "$transportId:$address"
        val md = MessageDigest.getInstance("SHA-256")
        md.update(secret)
        md.update(data.toByteArray(Charsets.UTF_8))
        val hash = md.digest()
        // Use first 8 bytes as token
        return hash.take(8).joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Verify a discovery token.
     */
    fun verifyDiscoveryToken(
        token: String,
        transportId: String,
        address: String,
        secret: ByteArray
    ): Boolean {
        val expected = generateDiscoveryToken(transportId, address, secret)
        return token == expected
    }
    
    /**
     * Create a handshake message.
     * 
     * @param publicId Our public identifier
     * @param discoveryTokens Tokens from other transports for correlation
     * @param availableTransports List of transports we're available on
     */
    fun createHandshake(
        publicId: String,
        discoveryTokens: List<String> = emptyList(),
        availableTransports: List<TransportType> = emptyList(),
        deviceIdHash: String? = null,
        exchangeId: String? = null
    ): HandshakeMessage {
        val nonce = ByteArray(NONCE_SIZE)
        secureRandom.nextBytes(nonce)

        return HandshakeMessage(
            version = PROTOCOL_VERSION,
            publicId = publicId,
            nonce = nonce,
            discoveryTokens = discoveryTokens,
            availableTransports = availableTransports.map { it.identifier() },
            timestamp = System.currentTimeMillis(),
            deviceIdHash = deviceIdHash,
            exchangeId = exchangeId
        )
    }
    
    /**
     * Encode a handshake message to bytes for transmission.
     */
    fun encode(message: HandshakeMessage): ByteArray {
        val json = JSONObject().apply {
            put(KEY_VERSION, message.version)
            put(KEY_PUBLIC_ID, message.publicId)
            put(KEY_NONCE, message.nonce.toHexString())
            put(KEY_DISCOVERY_TOKENS, JSONArray(message.discoveryTokens))
            put(KEY_TRANSPORTS, JSONArray(message.availableTransports))
            put(KEY_TIMESTAMP, message.timestamp)
            message.deviceIdHash?.let { put(KEY_DEVICE_ID_HASH, it) }
            message.exchangeId?.let { put(KEY_EXCHANGE_ID, it) }
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }
    
    /**
     * Decode a handshake message from received bytes.
     */
    fun decode(data: ByteArray): HandshakeMessage? {
        return try {
            val json = JSONObject(String(data, Charsets.UTF_8))
            
            val version = json.optInt(KEY_VERSION, 1)
            val publicId = json.getString(KEY_PUBLIC_ID)
            val nonceHex = json.getString(KEY_NONCE)
            val nonce = nonceHex.hexToByteArray()
            
            val tokensArray = json.optJSONArray(KEY_DISCOVERY_TOKENS)
            val tokens = mutableListOf<String>()
            if (tokensArray != null) {
                for (i in 0 until tokensArray.length()) {
                    tokens.add(tokensArray.getString(i))
                }
            }
            
            val transportsArray = json.optJSONArray(KEY_TRANSPORTS)
            val transports = mutableListOf<String>()
            if (transportsArray != null) {
                for (i in 0 until transportsArray.length()) {
                    transports.add(transportsArray.getString(i))
                }
            }
            
            val timestamp = json.optLong(KEY_TIMESTAMP, System.currentTimeMillis())
            
            val deviceIdHash = json.optString(KEY_DEVICE_ID_HASH, null)
            val exchangeId = json.optString(KEY_EXCHANGE_ID, null)

            HandshakeMessage(
                version = version,
                publicId = publicId,
                nonce = nonce,
                discoveryTokens = tokens,
                availableTransports = transports,
                timestamp = timestamp,
                deviceIdHash = deviceIdHash,
                exchangeId = exchangeId
            )
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to decode handshake message")
            null
        }
    }
    
    /**
     * Get a hash of a public ID for privacy-preserving exchange.
     * Peers can still correlate by comparing hashes.
     */
    fun hashPublicId(publicId: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(publicId.toByteArray(Charsets.UTF_8))
        return hash.take(16).joinToString("") { "%02x".format(it) }
    }
    
    // Extension functions for hex encoding/decoding
    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
    
    private fun String.hexToByteArray(): ByteArray {
        check(length % 2 == 0) { "Hex string must have even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
