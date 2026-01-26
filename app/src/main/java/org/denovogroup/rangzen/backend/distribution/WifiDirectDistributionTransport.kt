/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * WifiDirectDistributionTransport - WiFi Direct P2P transport for APK distribution.
 * 
 * This is a placeholder for Phase 2 implementation.
 * WiFi Direct allows direct device-to-device transfer without a router.
 * 
 * DESIGN NOTES:
 * - Uses WiFi Direct for connection (no router needed)
 * - Once connected, uses TCP socket for transfer (similar to HTTP)
 * - Session code used for authentication
 * - Group owner acts as server, client connects to group owner's IP
 * 
 * SAFETY:
 * - Group is removed after transfer
 * - No persistent WiFi Direct groups
 * - Session code prevents unauthorized connections
 */
package org.denovogroup.rangzen.backend.distribution

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.*
import android.os.Build
import android.os.Looper
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * WiFi Direct transport for APK distribution.
 * 
 * Usage:
 * 1. Sender: Call startAsSender() to create WiFi Direct group
 * 2. Receiver: Call startAsReceiver() to discover and connect
 * 3. Once connected, transfer happens over TCP socket
 * 
 * NOTE: This implementation requires both devices to have WiFi Direct enabled
 * and location services on (required by Android for WiFi Direct).
 */
class WifiDirectDistributionTransport(private val context: Context) : BaseDistributionTransport() {
    
    companion object {
        private const val TAG = "WifiDirectDistTransport"
        
        // Port for TCP transfer over WiFi Direct
        private const val TRANSFER_PORT = 41299
        
        // Timeouts
        private const val DISCOVERY_TIMEOUT_MS = 30000L
        private const val CONNECTION_TIMEOUT_MS = 30000L
        private const val TRANSFER_TIMEOUT_MS = 120000L
        
        // Buffer size
        private const val BUFFER_SIZE = 8192
    }
    
    // WiFi P2P manager
    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    
    // State
    @Volatile
    private var isGroupOwner = false
    @Volatile
    private var groupOwnerAddress: InetAddress? = null
    @Volatile
    private var isConnected = false
    
    // Transfer state
    private var serverSocket: ServerSocket? = null
    private var transferJob: Job? = null
    private var apkFile: File? = null
    private var sessionCode: String? = null
    
    // Callbacks
    var onDiscoveryStarted: (() -> Unit)? = null
    var onPeerFound: ((deviceName: String) -> Unit)? = null
    var onConnecting: (() -> Unit)? = null
    var onConnected: ((isGroupOwner: Boolean) -> Unit)? = null
    var onTransferProgress: ((bytesTransferred: Long, totalBytes: Long) -> Unit)? = null
    var onTransferComplete: ((success: Boolean, bytesTransferred: Long) -> Unit)? = null
    var onError: ((message: String) -> Unit)? = null
    
    // Broadcast receiver for WiFi P2P events
    private val p2pReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        Timber.w("$TAG: WiFi P2P is not enabled")
                        onError?.invoke("WiFi Direct is not enabled")
                    }
                }
                
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    // Request updated peer list
                    wifiP2pManager?.requestPeers(channel) { peers ->
                        handlePeersDiscovered(peers)
                    }
                }
                
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                    }
                    handleConnectionChanged(networkInfo)
                }
                
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    // Device info changed - not critical for transfer
                }
            }
        }
    }
    
    /**
     * Initialize WiFi Direct.
     */
    @SuppressLint("MissingPermission")
    fun initialize(): Boolean {
        try {
            wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            if (wifiP2pManager == null) {
                Timber.e("$TAG: WiFi P2P not supported")
                return false
            }
            
            channel = wifiP2pManager?.initialize(context, Looper.getMainLooper()) { 
                Timber.w("$TAG: WiFi P2P channel disconnected")
            }
            
            if (channel == null) {
                Timber.e("$TAG: Failed to initialize WiFi P2P channel")
                return false
            }
            
            // Register receiver
            val intentFilter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            }
            
            context.registerReceiver(p2pReceiver, intentFilter)
            
            Timber.i("$TAG: WiFi Direct initialized")
            return true
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to initialize WiFi Direct")
            return false
        }
    }
    
    /**
     * Start as sender (creates WiFi Direct group).
     */
    @SuppressLint("MissingPermission")
    override suspend fun startSender(apkFile: File, sessionCode: String): String? {
        reset()
        this.apkFile = apkFile
        this.sessionCode = sessionCode
        
        if (wifiP2pManager == null && !initialize()) {
            return null
        }
        
        return withContext(Dispatchers.IO) {
            val deferred = CompletableDeferred<String?>()
            
            // Create group (this device becomes group owner)
            wifiP2pManager?.createGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Timber.i("$TAG: WiFi Direct group created")
                    isGroupOwner = true
                    
                    // Start TCP server for transfer
                    CoroutineScope(Dispatchers.IO).launch {
                        val success = startTransferServer()
                        if (success) {
                            // Return the session code - receiver will need it
                            // The actual IP will be shared after connection
                            deferred.complete("wifidirect:$sessionCode")
                        } else {
                            deferred.complete(null)
                        }
                    }
                }
                
                override fun onFailure(reason: Int) {
                    val reasonStr = getP2pFailureReason(reason)
                    Timber.e("$TAG: Failed to create group: $reasonStr")
                    onError?.invoke("Failed to create WiFi Direct group: $reasonStr")
                    deferred.complete(null)
                }
            })
            
            // Wait with timeout
            withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
                deferred.await()
            }
        }
    }
    
    /**
     * Stop sender.
     */
    @SuppressLint("MissingPermission")
    override fun stopSender() {
        cleanup()
    }
    
    /**
     * Start as receiver (discovers and connects to sender).
     */
    @SuppressLint("MissingPermission")
    override suspend fun startReceiver(
        connectionInfo: String,
        sessionCode: String,
        outputFile: File,
        callback: TransferCallback
    ) {
        reset()
        this.sessionCode = sessionCode
        
        if (wifiP2pManager == null && !initialize()) {
            callback.onError("WiFi Direct not available")
            return
        }
        
        withContext(Dispatchers.IO) {
            onDiscoveryStarted?.invoke()
            
            // Start peer discovery
            wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Timber.i("$TAG: Peer discovery started")
                }
                
                override fun onFailure(reason: Int) {
                    val reasonStr = getP2pFailureReason(reason)
                    Timber.e("$TAG: Peer discovery failed: $reasonStr")
                    callback.onError("Could not find nearby devices: $reasonStr")
                }
            })
            
            // Wait for connection and transfer
            var transferred = 0L
            val startTime = System.currentTimeMillis()
            
            while (!isCancelled && !isConnected) {
                delay(500)
                if (System.currentTimeMillis() - startTime > DISCOVERY_TIMEOUT_MS) {
                    callback.onError("Could not find sender. Make sure both devices have WiFi Direct enabled.")
                    return@withContext
                }
            }
            
            if (isConnected && groupOwnerAddress != null && !isGroupOwner) {
                // We're connected as client - download from group owner
                transferred = downloadFromGroupOwner(groupOwnerAddress!!, sessionCode, outputFile) { 
                    bytesReceived, total ->
                    callback.onProgress(bytesReceived, total)
                }
                
                if (transferred > 0) {
                    callback.onComplete(outputFile)
                } else {
                    callback.onError("Transfer failed")
                }
            }
        }
    }
    
    /**
     * Cancel ongoing operations.
     */
    override fun cancel() {
        super.cancel()
        cleanup()
    }
    
    /**
     * Fetch APK info (not supported for WiFi Direct - info comes after connection).
     */
    override suspend fun fetchApkInfo(connectionInfo: String, sessionCode: String): ApkInfo? {
        // WiFi Direct doesn't support pre-connection info fetch
        // Info is exchanged after connection
        return null
    }
    
    // ========================================================================
    // Private methods
    // ========================================================================
    
    private fun handlePeersDiscovered(peers: WifiP2pDeviceList) {
        val deviceList = peers.deviceList
        Timber.d("$TAG: Found ${deviceList.size} peers")
        
        for (device in deviceList) {
            Timber.d("$TAG: Peer: ${device.deviceName} (${device.deviceAddress})")
            onPeerFound?.invoke(device.deviceName)
            
            // Auto-connect to first peer found (in production, should show list)
            if (!isConnected) {
                connectToPeer(device)
            }
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun connectToPeer(device: WifiP2pDevice) {
        Timber.i("$TAG: Connecting to ${device.deviceName}")
        onConnecting?.invoke()
        
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            groupOwnerIntent = 0  // Prefer to be client (connect to existing group)
        }
        
        wifiP2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Timber.i("$TAG: Connection initiated")
            }
            
            override fun onFailure(reason: Int) {
                val reasonStr = getP2pFailureReason(reason)
                Timber.e("$TAG: Connection failed: $reasonStr")
                onError?.invoke("Connection failed: $reasonStr")
            }
        })
    }
    
    private fun handleConnectionChanged(info: WifiP2pInfo?) {
        if (info == null) return
        
        Timber.i("$TAG: Connection changed - groupFormed: ${info.groupFormed}, isGroupOwner: ${info.isGroupOwner}")
        
        if (info.groupFormed) {
            isConnected = true
            isGroupOwner = info.isGroupOwner
            groupOwnerAddress = info.groupOwnerAddress
            
            onConnected?.invoke(isGroupOwner)
            
            if (isGroupOwner) {
                Timber.i("$TAG: We are group owner, waiting for client transfer")
            } else {
                Timber.i("$TAG: Connected to group owner at ${info.groupOwnerAddress}")
            }
        } else {
            isConnected = false
            groupOwnerAddress = null
        }
    }
    
    private suspend fun startTransferServer(): Boolean {
        return try {
            serverSocket = ServerSocket(TRANSFER_PORT)
            Timber.i("$TAG: Transfer server started on port $TRANSFER_PORT")
            
            transferJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    while (!isCancelled && serverSocket != null) {
                        val socket = serverSocket?.accept() ?: break
                        handleClientConnection(socket)
                    }
                } catch (e: Exception) {
                    if (!isCancelled) {
                        Timber.e(e, "$TAG: Server error")
                    }
                }
            }
            
            true
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to start transfer server")
            false
        }
    }
    
    private suspend fun handleClientConnection(socket: Socket) {
        Timber.i("$TAG: Client connected")
        
        try {
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            
            // Read session code
            val receivedCode = input.readUTF()
            if (receivedCode != sessionCode) {
                Timber.w("$TAG: Invalid session code")
                output.writeUTF("ERROR:Invalid code")
                return
            }
            
            // Send APK info
            val file = apkFile
            if (file == null || !file.exists()) {
                output.writeUTF("ERROR:No APK")
                return
            }
            
            output.writeUTF("OK")
            output.writeLong(file.length())
            output.flush()
            
            // Stream APK
            var bytesSent = 0L
            val buffer = ByteArray(BUFFER_SIZE)
            
            FileInputStream(file).use { fileInput ->
                while (!isCancelled) {
                    val bytesRead = fileInput.read(buffer)
                    if (bytesRead == -1) break
                    
                    output.write(buffer, 0, bytesRead)
                    bytesSent += bytesRead
                    
                    withContext(Dispatchers.Main) {
                        onTransferProgress?.invoke(bytesSent, file.length())
                    }
                }
            }
            
            output.flush()
            
            Timber.i("$TAG: Transfer complete: $bytesSent bytes")
            
            withContext(Dispatchers.Main) {
                onTransferComplete?.invoke(true, bytesSent)
            }
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Transfer error")
            withContext(Dispatchers.Main) {
                onError?.invoke("Transfer failed: ${e.message}")
            }
        } finally {
            socket.close()
        }
    }
    
    private suspend fun downloadFromGroupOwner(
        address: InetAddress,
        code: String,
        outputFile: File,
        progressCallback: (Long, Long) -> Unit
    ): Long {
        return try {
            val socket = Socket(address, TRANSFER_PORT)
            socket.soTimeout = TRANSFER_TIMEOUT_MS.toInt()
            
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            
            // Send session code
            output.writeUTF(code)
            output.flush()
            
            // Read response
            val response = input.readUTF()
            if (!response.startsWith("OK")) {
                Timber.e("$TAG: Server error: $response")
                return 0
            }
            
            // Read file size
            val fileSize = input.readLong()
            
            // Download file
            var bytesReceived = 0L
            val buffer = ByteArray(BUFFER_SIZE)
            
            FileOutputStream(outputFile).use { fileOutput ->
                while (bytesReceived < fileSize && !isCancelled) {
                    val toRead = minOf(buffer.size.toLong(), fileSize - bytesReceived).toInt()
                    val bytesRead = input.read(buffer, 0, toRead)
                    if (bytesRead == -1) break
                    
                    fileOutput.write(buffer, 0, bytesRead)
                    bytesReceived += bytesRead
                    
                    progressCallback(bytesReceived, fileSize)
                }
            }
            
            socket.close()
            
            Timber.i("$TAG: Download complete: $bytesReceived bytes")
            bytesReceived
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Download error")
            0
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun cleanup() {
        transferJob?.cancel()
        transferJob = null
        
        try {
            serverSocket?.close()
        } catch (e: Exception) { }
        serverSocket = null
        
        // Remove WiFi Direct group
        wifiP2pManager?.removeGroup(channel, null)
        
        // Unregister receiver
        try {
            context.unregisterReceiver(p2pReceiver)
        } catch (e: Exception) { }
        
        isConnected = false
        isGroupOwner = false
        groupOwnerAddress = null
        apkFile = null
        sessionCode = null
    }
    
    private fun getP2pFailureReason(reason: Int): String {
        return when (reason) {
            WifiP2pManager.P2P_UNSUPPORTED -> "P2P not supported"
            WifiP2pManager.BUSY -> "System busy"
            WifiP2pManager.ERROR -> "Internal error"
            else -> "Unknown error ($reason)"
        }
    }
}
