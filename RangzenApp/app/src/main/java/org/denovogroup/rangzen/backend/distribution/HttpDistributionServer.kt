/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * HttpDistributionServer - Simple HTTP server for APK distribution.
 * 
 * SAFETY DESIGN:
 * - Random high port (not predictable)
 * - Session code required for download
 * - Single-use: stops after one successful transfer
 * - No logging of client IPs or device info
 * - Timeout automatically stops server
 */
package org.denovogroup.rangzen.backend.distribution

import android.content.Context
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * HTTP server for distributing APK over LAN/Hotspot.
 * 
 * Usage:
 * 1. Call [start] with APK file and session code
 * 2. Server returns URL for receiver to connect
 * 3. Receiver downloads APK with session code in header
 * 4. Server stops after transfer or timeout
 */
class HttpDistributionServer(private val context: Context) {
    
    companion object {
        private const val TAG = "HttpDistributionServer"
        
        // Port range for random selection (high ports, unlikely to conflict)
        private const val PORT_RANGE_START = 49152
        private const val PORT_RANGE_END = 65535
        
        // Buffer size for file transfer
        private const val BUFFER_SIZE = 8192
        
        // Request timeout
        private const val SOCKET_TIMEOUT_MS = 30000
    }
    
    // Server state
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var currentApkFile: File? = null
    private var currentSessionCode: String? = null
    private var currentApkInfo: ApkInfo? = null
    
    @Volatile
    private var isRunning = false
    
    // Callbacks
    var onClientConnected: (() -> Unit)? = null
    var onTransferProgress: ((bytesTransferred: Long, totalBytes: Long) -> Unit)? = null
    var onTransferComplete: ((bytesTransferred: Long) -> Unit)? = null
    var onError: ((message: String) -> Unit)? = null
    
    /**
     * Start the HTTP server.
     * 
     * @param apkFile The APK file to serve
     * @param apkInfo Metadata about the APK
     * @param sessionCode Session code for authentication
     * @return Server URL (http://ip:port) or null on failure
     */
    fun start(apkFile: File, apkInfo: ApkInfo, sessionCode: String): String? {
        if (isRunning) {
            Timber.w("$TAG: Server already running")
            return null
        }
        
        if (!apkFile.exists() || !apkFile.canRead()) {
            Timber.e("$TAG: APK file not readable: ${apkFile.absolutePath}")
            return null
        }
        
        // Find a random available port
        val port = findAvailablePort()
        if (port == null) {
            Timber.e("$TAG: Could not find available port")
            return null
        }
        
        // Get local IP address
        val localIp = getLocalIpAddress()
        if (localIp == null) {
            Timber.e("$TAG: Could not determine local IP address")
            return null
        }
        
        try {
            serverSocket = ServerSocket(port)
            serverSocket?.soTimeout = SOCKET_TIMEOUT_MS
            
            currentApkFile = apkFile
            currentSessionCode = sessionCode
            currentApkInfo = apkInfo
            isRunning = true
            
            // Start server in background
            serverJob = CoroutineScope(Dispatchers.IO).launch {
                runServer()
            }
            
            val serverUrl = "http://$localIp:$port"
            Timber.i("$TAG: Server started at $serverUrl")
            return serverUrl
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to start server")
            cleanup()
            return null
        }
    }
    
    /**
     * Stop the server.
     */
    fun stop() {
        Timber.d("$TAG: Stopping server")
        isRunning = false
        cleanup()
    }
    
    /**
     * Check if server is running.
     */
    fun isRunning(): Boolean = isRunning
    
    // ========================================================================
    // Server logic
    // ========================================================================
    
    private suspend fun runServer() {
        Timber.d("$TAG: Server loop started")
        
        try {
            while (isRunning && serverSocket != null && !serverSocket!!.isClosed) {
                try {
                    // Accept connection (blocks until timeout or connection)
                    val clientSocket = serverSocket?.accept()
                    
                    if (clientSocket != null) {
                        // Handle client in current coroutine (single client at a time)
                        handleClient(clientSocket)
                    }
                    
                } catch (e: java.net.SocketTimeoutException) {
                    // Timeout - check if still running and continue
                    continue
                } catch (e: SocketException) {
                    // Socket closed - exit loop
                    if (isRunning) {
                        Timber.w("$TAG: Socket exception: ${e.message}")
                    }
                    break
                }
            }
        } catch (e: Exception) {
            if (isRunning) {
                Timber.e(e, "$TAG: Server error")
                withContext(Dispatchers.Main) {
                    onError?.invoke("Server error: ${e.message}")
                }
            }
        } finally {
            Timber.d("$TAG: Server loop ended")
        }
    }
    
    private suspend fun handleClient(socket: Socket) {
        Timber.d("$TAG: Client connected")
        
        socket.soTimeout = SOCKET_TIMEOUT_MS
        
        try {
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = socket.getOutputStream()
            
            // Read HTTP request
            val request = parseHttpRequest(input)
            
            if (request == null) {
                sendErrorResponse(output, 400, "Bad Request")
                return
            }
            
            // Handle different endpoints
            when {
                request.path == "/info" && request.method == "GET" -> {
                    handleInfoRequest(output, request)
                }
                request.path == "/download" && request.method == "GET" -> {
                    handleDownloadRequest(output, request)
                }
                else -> {
                    sendErrorResponse(output, 404, "Not Found")
                }
            }
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error handling client")
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                // Ignore close errors
            }
        }
    }
    
    /**
     * Handle /info request - returns APK metadata.
     */
    private fun handleInfoRequest(output: OutputStream, request: HttpRequest) {
        // Verify session code
        val providedCode = request.headers["x-session-code"]
        if (providedCode != currentSessionCode) {
            Timber.w("$TAG: Invalid session code on /info")
            sendErrorResponse(output, 403, "Forbidden")
            return
        }
        
        val info = currentApkInfo
        if (info == null) {
            sendErrorResponse(output, 500, "Internal Server Error")
            return
        }
        
        // Return JSON with APK info
        val json = """
            {
                "versionCode": ${info.versionCode},
                "versionName": "${info.versionName}",
                "sizeBytes": ${info.sizeBytes},
                "sha256Hash": "${info.sha256Hash}",
                "signatureFingerprint": "${info.signatureFingerprint}"
            }
        """.trimIndent()
        
        val responseBytes = json.toByteArray(Charsets.UTF_8)
        
        val response = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${responseBytes.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        
        output.write(response.toByteArray(Charsets.UTF_8))
        output.write(responseBytes)
        output.flush()
        
        Timber.d("$TAG: Sent APK info")
    }
    
    /**
     * Handle /download request - streams APK file.
     */
    private suspend fun handleDownloadRequest(output: OutputStream, request: HttpRequest) {
        // Verify session code
        val providedCode = request.headers["x-session-code"]
        if (providedCode != currentSessionCode) {
            Timber.w("$TAG: Invalid session code on /download")
            sendErrorResponse(output, 403, "Forbidden")
            return
        }
        
        val apkFile = currentApkFile
        if (apkFile == null || !apkFile.exists()) {
            sendErrorResponse(output, 500, "Internal Server Error")
            return
        }
        
        // Notify that client connected
        withContext(Dispatchers.Main) {
            onClientConnected?.invoke()
        }
        
        val fileSize = apkFile.length()
        
        // Send headers
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/vnd.android.package-archive\r\n")
            append("Content-Length: $fileSize\r\n")
            append("Content-Disposition: attachment; filename=\"murmur.apk\"\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        
        output.write(headers.toByteArray(Charsets.UTF_8))
        
        // Stream file with progress updates
        var bytesTransferred = 0L
        val buffer = ByteArray(BUFFER_SIZE)
        
        FileInputStream(apkFile).use { input ->
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                bytesTransferred += bytesRead
                
                // Report progress (throttled to avoid too many updates)
                if (bytesTransferred % (BUFFER_SIZE * 10) == 0L || bytesTransferred == fileSize) {
                    withContext(Dispatchers.Main) {
                        onTransferProgress?.invoke(bytesTransferred, fileSize)
                    }
                }
            }
        }
        
        output.flush()
        
        Timber.i("$TAG: Transfer complete: $bytesTransferred bytes")
        
        withContext(Dispatchers.Main) {
            onTransferComplete?.invoke(bytesTransferred)
        }
        
        // Stop server after successful transfer (single-use)
        isRunning = false
    }
    
    private fun sendErrorResponse(output: OutputStream, code: Int, message: String) {
        val response = "HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        output.write(response.toByteArray(Charsets.UTF_8))
        output.flush()
    }
    
    // ========================================================================
    // HTTP parsing
    // ========================================================================
    
    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>
    )
    
    private fun parseHttpRequest(reader: BufferedReader): HttpRequest? {
        try {
            // Read request line
            val requestLine = reader.readLine() ?: return null
            val parts = requestLine.split(" ")
            if (parts.size < 2) return null
            
            val method = parts[0]
            val path = parts[1].split("?")[0]  // Remove query string
            
            // Read headers
            val headers = mutableMapOf<String, String>()
            var line: String?
            while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                val colonIndex = line!!.indexOf(':')
                if (colonIndex > 0) {
                    val name = line!!.substring(0, colonIndex).trim().lowercase()
                    val value = line!!.substring(colonIndex + 1).trim()
                    headers[name] = value
                }
            }
            
            return HttpRequest(method, path, headers)
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to parse HTTP request")
            return null
        }
    }
    
    // ========================================================================
    // Utilities
    // ========================================================================
    
    private fun findAvailablePort(): Int? {
        val random = SecureRandom()
        
        // Try up to 10 random ports
        repeat(10) {
            val port = PORT_RANGE_START + random.nextInt(PORT_RANGE_END - PORT_RANGE_START)
            try {
                ServerSocket(port).use { 
                    return port
                }
            } catch (e: Exception) {
                // Port in use, try another
            }
        }
        
        return null
    }
    
    private fun getLocalIpAddress(): String? {
        try {
            // Get all network interfaces
            val interfaces = NetworkInterface.getNetworkInterfaces()
            
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                
                // Skip loopback and down interfaces
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                
                // Prefer WiFi interfaces
                val name = networkInterface.name.lowercase()
                val isWifi = name.startsWith("wlan") || name.startsWith("wifi") || name.startsWith("en")
                
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    
                    // Skip IPv6 and loopback
                    if (address.isLoopbackAddress) continue
                    if (address.hostAddress?.contains(":") == true) continue  // IPv6
                    
                    val ip = address.hostAddress
                    if (ip != null && isWifi) {
                        return ip
                    }
                }
            }
            
            // Fallback: return any non-loopback IPv4 address
            val interfaces2 = NetworkInterface.getNetworkInterfaces()
            while (interfaces2.hasMoreElements()) {
                val networkInterface = interfaces2.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address.isLoopbackAddress) continue
                    if (address.hostAddress?.contains(":") == true) continue
                    
                    return address.hostAddress
                }
            }
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to get local IP")
        }
        
        return null
    }
    
    private fun cleanup() {
        serverJob?.cancel()
        serverJob = null
        
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        serverSocket = null
        
        currentApkFile = null
        currentSessionCode = null
        currentApkInfo = null
        isRunning = false
    }
}
