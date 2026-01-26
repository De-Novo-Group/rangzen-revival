/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * DistributionTransport - Abstract interface for APK transfer mechanisms.
 * 
 * Implementations:
 * - HttpDistributionTransport: LAN/Hotspot HTTP transfer
 * - WifiDirectDistributionTransport: WiFi Direct P2P transfer
 */
package org.denovogroup.rangzen.backend.distribution

import java.io.File

/**
 * Callback interface for transfer progress and completion.
 */
interface TransferCallback {
    /** Called periodically during transfer with bytes transferred and total */
    fun onProgress(bytesTransferred: Long, totalBytes: Long)
    
    /** Called when transfer completes successfully */
    fun onComplete(file: File)
    
    /** Called when transfer fails */
    fun onError(message: String)
}

/**
 * Information about an APK available for transfer.
 */
data class ApkInfo(
    val versionCode: Int,
    val versionName: String,
    val sizeBytes: Long,
    val sha256Hash: String,
    val signatureFingerprint: String
)

/**
 * Abstract transport for APK distribution.
 * 
 * Each implementation handles a different transfer mechanism
 * (HTTP, WiFi Direct, etc.) but exposes a common interface.
 */
interface DistributionTransport {
    
    /**
     * Start as sender (server).
     * 
     * @param apkFile The APK file to share
     * @param sessionCode The session code for authentication
     * @return Connection info (URL, address, etc.) for receiver
     */
    suspend fun startSender(apkFile: File, sessionCode: String): String?
    
    /**
     * Stop sender server.
     */
    fun stopSender()
    
    /**
     * Start as receiver (client).
     * 
     * @param connectionInfo Info from sender (URL, address, etc.)
     * @param sessionCode Session code for authentication
     * @param outputFile Where to save the downloaded APK
     * @param callback Progress and completion callbacks
     */
    suspend fun startReceiver(
        connectionInfo: String,
        sessionCode: String,
        outputFile: File,
        callback: TransferCallback
    )
    
    /**
     * Cancel ongoing transfer.
     */
    fun cancel()
    
    /**
     * Get info about the APK being shared (sender side).
     */
    fun getApkInfo(): ApkInfo?
    
    /**
     * Fetch APK info from sender (receiver side).
     * Allows receiver to verify before downloading.
     */
    suspend fun fetchApkInfo(connectionInfo: String, sessionCode: String): ApkInfo?
}

/**
 * Base implementation with common functionality.
 */
abstract class BaseDistributionTransport : DistributionTransport {
    
    @Volatile
    protected var isCancelled = false
    
    @Volatile
    protected var currentApkInfo: ApkInfo? = null
    
    override fun cancel() {
        isCancelled = true
    }
    
    override fun getApkInfo(): ApkInfo? = currentApkInfo
    
    /**
     * Check if transfer should continue (not cancelled).
     */
    protected fun shouldContinue(): Boolean = !isCancelled
    
    /**
     * Reset state for new transfer.
     */
    protected fun reset() {
        isCancelled = false
        currentApkInfo = null
    }
}
