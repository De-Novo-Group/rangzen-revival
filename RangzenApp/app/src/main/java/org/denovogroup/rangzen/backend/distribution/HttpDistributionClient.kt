/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * HttpDistributionClient - HTTP client for downloading APK from sender.
 * 
 * SAFETY DESIGN:
 * - Session code sent in header (not URL - no logging)
 * - Verifies APK info before download
 * - Saves to app's private cache directory
 * - No device identifiers sent
 */
package org.denovogroup.rangzen.backend.distribution

import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONObject
import timber.log.Timber
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP client for downloading APK from distribution server.
 * 
 * Usage:
 * 1. Call [fetchApkInfo] to get metadata
 * 2. Verify metadata (version, signature)
 * 3. Call [downloadApk] to download
 * 4. Verify downloaded file
 */
class HttpDistributionClient(private val context: Context) {
    
    companion object {
        private const val TAG = "HttpDistributionClient"
        
        // Timeouts
        private const val CONNECT_TIMEOUT_MS = 10000
        private const val READ_TIMEOUT_MS = 60000
        
        // Buffer size
        private const val BUFFER_SIZE = 8192
    }
    
    @Volatile
    private var isCancelled = false
    
    // Callbacks
    var onProgress: ((bytesDownloaded: Long, totalBytes: Long) -> Unit)? = null
    var onComplete: ((file: File) -> Unit)? = null
    var onError: ((message: String) -> Unit)? = null
    
    /**
     * Fetch APK info from server.
     * 
     * @param serverUrl Base URL of server (e.g., "http://192.168.1.5:54321")
     * @param sessionCode Session code for authentication
     * @return ApkInfo or null on failure
     */
    suspend fun fetchApkInfo(serverUrl: String, sessionCode: String): ApkInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$serverUrl/info")
                val connection = url.openConnection() as HttpURLConnection
                
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.requestMethod = "GET"
                connection.setRequestProperty("X-Session-Code", sessionCode)
                
                val responseCode = connection.responseCode
                
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    Timber.w("$TAG: Info request failed: $responseCode")
                    return@withContext null
                }
                
                // Read response
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                
                // Parse JSON
                parseApkInfo(response)
                
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Failed to fetch APK info")
                null
            }
        }
    }
    
    /**
     * Download APK from server.
     * 
     * @param serverUrl Base URL of server
     * @param sessionCode Session code for authentication
     * @param expectedInfo Expected APK info (for size validation)
     * @return Downloaded file or null on failure
     */
    suspend fun downloadApk(
        serverUrl: String,
        sessionCode: String,
        expectedInfo: ApkInfo
    ): File? {
        isCancelled = false
        
        return withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            var outputFile: File? = null
            
            try {
                val url = URL("$serverUrl/download")
                connection = url.openConnection() as HttpURLConnection
                
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.requestMethod = "GET"
                connection.setRequestProperty("X-Session-Code", sessionCode)
                
                val responseCode = connection.responseCode
                
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    val errorMsg = when (responseCode) {
                        HttpURLConnection.HTTP_FORBIDDEN -> "Invalid session code"
                        HttpURLConnection.HTTP_NOT_FOUND -> "Server not ready"
                        else -> "Server error ($responseCode)"
                    }
                    Timber.w("$TAG: Download failed: $responseCode")
                    withContext(Dispatchers.Main) {
                        onError?.invoke(errorMsg)
                    }
                    return@withContext null
                }
                
                // Get content length
                val contentLength = connection.contentLengthLong
                val totalBytes = if (contentLength > 0) contentLength else expectedInfo.sizeBytes
                
                // Create output file in cache
                val downloadDir = File(context.cacheDir, "distribution")
                if (!downloadDir.exists()) {
                    downloadDir.mkdirs()
                }
                outputFile = File(downloadDir, "downloaded.apk")
                
                // Download with progress
                var bytesDownloaded = 0L
                val buffer = ByteArray(BUFFER_SIZE)
                
                connection.inputStream.use { input ->
                    FileOutputStream(outputFile).use { output ->
                        while (!isCancelled) {
                            val bytesRead = input.read(buffer)
                            if (bytesRead == -1) break
                            
                            output.write(buffer, 0, bytesRead)
                            bytesDownloaded += bytesRead
                            
                            // Report progress
                            if (bytesDownloaded % (BUFFER_SIZE * 10) == 0L || bytesDownloaded == totalBytes) {
                                withContext(Dispatchers.Main) {
                                    onProgress?.invoke(bytesDownloaded, totalBytes)
                                }
                            }
                        }
                    }
                }
                
                if (isCancelled) {
                    Timber.d("$TAG: Download cancelled")
                    outputFile.delete()
                    return@withContext null
                }
                
                // Verify size
                if (outputFile.length() != expectedInfo.sizeBytes) {
                    Timber.e("$TAG: Downloaded file size mismatch")
                    outputFile.delete()
                    withContext(Dispatchers.Main) {
                        onError?.invoke("Download incomplete")
                    }
                    return@withContext null
                }
                
                Timber.i("$TAG: Download complete: ${outputFile.length()} bytes")
                
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(outputFile)
                }
                
                outputFile
                
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Download failed")
                outputFile?.delete()
                
                val errorMsg = when {
                    e is java.net.ConnectException -> "Cannot connect to sender"
                    e is java.net.SocketTimeoutException -> "Connection timed out"
                    else -> "Download failed: ${e.message}"
                }
                
                withContext(Dispatchers.Main) {
                    onError?.invoke(errorMsg)
                }
                
                null
                
            } finally {
                connection?.disconnect()
            }
        }
    }
    
    /**
     * Cancel ongoing download.
     */
    fun cancel() {
        isCancelled = true
    }
    
    // ========================================================================
    // Utilities
    // ========================================================================
    
    private fun parseApkInfo(json: String): ApkInfo? {
        return try {
            val obj = JSONObject(json)
            ApkInfo(
                versionCode = obj.getInt("versionCode"),
                versionName = obj.getString("versionName"),
                sizeBytes = obj.getLong("sizeBytes"),
                sha256Hash = obj.getString("sha256Hash"),
                signatureFingerprint = obj.getString("signatureFingerprint")
            )
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to parse APK info")
            null
        }
    }
}
