/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * ShareFragment - UI for offline APK distribution.
 * Allows users to share or receive the app over LAN/Hotspot.
 */
package org.denovogroup.rangzen.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import kotlinx.coroutines.*
import org.denovogroup.rangzen.backend.distribution.*
import org.denovogroup.rangzen.databinding.FragmentShareBinding
import timber.log.Timber
import java.io.File

/**
 * Fragment for sharing/receiving the Murmur app offline.
 * 
 * Flow:
 * 1. User chooses Send or Receive
 * 2. Send: Shows session code, starts HTTP server, waits for receiver
 * 3. Receive: User enters code, connects to sender, downloads APK
 * 4. Result: Shows success/failure, option to install (receiver)
 */
class ShareFragment : Fragment() {
    
    companion object {
        private const val TAG = "ShareFragment"
        
        fun newInstance(): ShareFragment = ShareFragment()
    }
    
    private var _binding: FragmentShareBinding? = null
    private val binding get() = _binding!!
    
    // State
    private enum class Mode { SELECTION, SENDER, RECEIVER, RESULT }
    private var currentMode = Mode.SELECTION
    
    // Distribution components
    private var httpServer: HttpDistributionServer? = null
    private var httpClient: HttpDistributionClient? = null
    
    // Session data
    private var sessionCode: String? = null
    private var serverUrl: String? = null
    private var downloadedApkFile: File? = null
    
    // Coroutine scope
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentShareBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupListeners()
        showModeSelection()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        cleanup()
        scope.cancel()
        _binding = null
    }
    
    // ========================================================================
    // Setup
    // ========================================================================
    
    private fun setupListeners() {
        // Back button
        binding.btnBack.setOnClickListener {
            handleBack()
        }
        
        // Mode selection
        binding.cardSend.setOnClickListener {
            startSenderMode()
        }
        
        binding.cardReceive.setOnClickListener {
            startReceiverMode()
        }
        
        // Sender controls
        binding.btnCancelSend.setOnClickListener {
            cancelSending()
        }
        
        // Receiver controls
        binding.editSessionCode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Enable connect button when 6 digits entered
                binding.btnConnect.isEnabled = s?.length == 6
            }
        })
        
        binding.btnConnect.setOnClickListener {
            val code = binding.editSessionCode.text?.toString() ?: ""
            if (code.length == 6) {
                startDownload(code)
            }
        }
        
        binding.btnCancelReceive.setOnClickListener {
            cancelReceiving()
        }
        
        // Result controls
        binding.btnInstall.setOnClickListener {
            downloadedApkFile?.let { installApk(it) }
        }
        
        binding.btnDone.setOnClickListener {
            // Go back to main activity
            activity?.onBackPressed()
        }
    }
    
    private fun handleBack() {
        when (currentMode) {
            Mode.SELECTION -> activity?.onBackPressed()
            Mode.SENDER -> cancelSending()
            Mode.RECEIVER -> cancelReceiving()
            Mode.RESULT -> showModeSelection()
        }
    }
    
    // ========================================================================
    // Mode Selection
    // ========================================================================
    
    private fun showModeSelection() {
        currentMode = Mode.SELECTION
        cleanup()
        
        binding.layoutModeSelection.visibility = View.VISIBLE
        binding.layoutSender.visibility = View.GONE
        binding.layoutReceiver.visibility = View.GONE
        binding.layoutResult.visibility = View.GONE
    }
    
    // ========================================================================
    // Sender Mode
    // ========================================================================
    
    private fun startSenderMode() {
        currentMode = Mode.SENDER
        
        binding.layoutModeSelection.visibility = View.GONE
        binding.layoutSender.visibility = View.VISIBLE
        binding.layoutReceiver.visibility = View.GONE
        binding.layoutResult.visibility = View.GONE
        
        // Reset UI
        binding.textSessionCode.text = "------"
        binding.textSenderStatus.text = "Preparing..."
        binding.progressSender.visibility = View.GONE
        binding.progressSender.progress = 0
        
        // Start server
        scope.launch {
            startHttpServer()
        }
    }
    
    private suspend fun startHttpServer() {
        val context = requireContext()
        
        // Export APK
        binding.textSenderStatus.text = "Exporting app..."
        
        val exportResult = withContext(Dispatchers.IO) {
            ApkExporter.exportApk(context)
        }
        
        if (!exportResult.success || exportResult.apkFile == null || exportResult.apkInfo == null) {
            showError("Failed to export app: ${exportResult.error}")
            return
        }
        
        // Start ShareMode session
        val code = ShareModeManager.startSenderSession(context, ShareModeManager.TransferMethod.HTTP)
        if (code == null) {
            showError("Failed to start share session")
            return
        }
        
        sessionCode = code
        
        // Create and start HTTP server
        httpServer = HttpDistributionServer(context).apply {
            onClientConnected = {
                activity?.runOnUiThread {
                    binding.textSenderStatus.text = "Receiver connected, transferring..."
                    binding.progressSender.visibility = View.VISIBLE
                }
            }
            
            onTransferProgress = { transferred, total ->
                activity?.runOnUiThread {
                    val progress = ((transferred.toFloat() / total) * 100).toInt()
                    binding.progressSender.progress = progress
                    ShareModeManager.updateProgress(transferred, total)
                }
            }
            
            onTransferComplete = { bytes ->
                activity?.runOnUiThread {
                    ShareModeManager.completeSession(bytes)
                    showSenderResult(true, bytes)
                }
            }
            
            onError = { message ->
                activity?.runOnUiThread {
                    ShareModeManager.failSession(message)
                    showError(message)
                }
            }
        }
        
        val url = httpServer?.start(exportResult.apkFile, exportResult.apkInfo, code)
        
        if (url == null) {
            ShareModeManager.failSession("Failed to start server")
            showError("Failed to start server")
            return
        }
        
        serverUrl = url
        
        // Update UI with session code
        binding.textSessionCode.text = formatSessionCode(code)
        binding.textSenderStatus.text = "Waiting for receiver..."
        binding.textNetworkInfo.text = "Server ready at $url\nMake sure both devices are on the same network"
        
        Timber.i("$TAG: Sender started with code $code at $url")
    }
    
    private fun cancelSending() {
        cleanup()
        ShareModeManager.cancelSession()
        showModeSelection()
    }
    
    private fun showSenderResult(success: Boolean, bytesTransferred: Long) {
        currentMode = Mode.RESULT
        
        binding.layoutSender.visibility = View.GONE
        binding.layoutResult.visibility = View.VISIBLE
        
        if (success) {
            binding.imgResult.setImageResource(android.R.drawable.ic_dialog_info)
            binding.textResultTitle.text = "Transfer Complete"
            binding.textResultMessage.text = "Successfully shared ${formatBytes(bytesTransferred)} with receiver."
        } else {
            binding.imgResult.setImageResource(android.R.drawable.ic_dialog_alert)
            binding.textResultTitle.text = "Transfer Failed"
            binding.textResultMessage.text = "Could not complete the transfer."
        }
        
        binding.btnInstall.visibility = View.GONE
        
        cleanup()
    }
    
    // ========================================================================
    // Receiver Mode
    // ========================================================================
    
    private fun startReceiverMode() {
        currentMode = Mode.RECEIVER
        
        binding.layoutModeSelection.visibility = View.GONE
        binding.layoutSender.visibility = View.GONE
        binding.layoutReceiver.visibility = View.VISIBLE
        binding.layoutResult.visibility = View.GONE
        
        // Reset UI
        binding.editSessionCode.text?.clear()
        binding.btnConnect.isEnabled = false
        binding.layoutCodeEntry.visibility = View.VISIBLE
        binding.layoutDownload.visibility = View.GONE
        binding.textReceiverStatus.text = ""
        binding.progressReceiver.progress = 0
    }
    
    private fun startDownload(code: String) {
        val context = requireContext()
        
        // Show download UI
        binding.layoutCodeEntry.visibility = View.GONE
        binding.layoutDownload.visibility = View.VISIBLE
        binding.textReceiverStatus.text = "Searching for sender..."
        
        // Start receiver session
        ShareModeManager.startReceiverSession(context, code, ShareModeManager.TransferMethod.HTTP)
        
        // Create client
        httpClient = HttpDistributionClient(context).apply {
            onProgress = { downloaded, total ->
                activity?.runOnUiThread {
                    val progress = ((downloaded.toFloat() / total) * 100).toInt()
                    binding.progressReceiver.progress = progress
                    binding.textProgressDetails.text = "${formatBytes(downloaded)} / ${formatBytes(total)}"
                    ShareModeManager.updateProgress(downloaded, total)
                }
            }
            
            onComplete = { file ->
                activity?.runOnUiThread {
                    downloadedApkFile = file
                    verifyAndShowResult(file, code)
                }
            }
            
            onError = { message ->
                activity?.runOnUiThread {
                    ShareModeManager.failSession(message)
                    showError(message)
                }
            }
        }
        
        // Try to connect and download
        scope.launch {
            connectAndDownload(code)
        }
    }
    
    private suspend fun connectAndDownload(code: String) {
        // Prompt user for sender's address immediately
        // The address is shown on sender's screen (e.g., http://192.168.219.212:55632)
        // Auto-discovery is unreliable, so we use manual entry for reliability
        withContext(Dispatchers.Main) {
            promptForServerAddress(code)
        }
    }
    
    /**
     * Discover servers on the local network.
     * 
     * Scans the local subnet for HTTP servers with valid APK info.
     * Uses a broader scan to find servers on any IP in the local network.
     */
    private suspend fun discoverServers(code: String): List<Pair<String, ApkInfo>> {
        val context = requireContext()
        val results = mutableListOf<Pair<String, ApkInfo>>()
        
        // Get local network prefix
        val localIp = getLocalIpAddress()
        if (localIp == null) {
            Timber.w("$TAG: Could not determine local IP")
            return results
        }
        
        val prefix = localIp.substringBeforeLast(".")
        val localLastOctet = localIp.substringAfterLast(".").toIntOrNull() ?: 0
        
        Timber.d("$TAG: Scanning network $prefix.* for sender (we are $localIp)")
        
        withContext(Dispatchers.IO) {
            val client = HttpDistributionClient(context)
            
            // Scan the entire local subnet (1-254), but prioritize:
            // 1. Addresses close to our own IP (likely same router assignment range)
            // 2. Common DHCP ranges (.100-.200, .2-.50)
            val addressesToTry = mutableListOf<String>()
            
            // First, try addresses near our own IP (±20 range)
            for (offset in -20..20) {
                val octet = localLastOctet + offset
                if (octet in 1..254 && octet != localLastOctet) {
                    addressesToTry.add("$prefix.$octet")
                }
            }
            
            // Then add common DHCP ranges
            for (octet in listOf(1, 2, 100, 101, 102, 150, 200, 201)) {
                val addr = "$prefix.$octet"
                if (addr !in addressesToTry) {
                    addressesToTry.add(addr)
                }
            }
            
            // High ephemeral ports where our server runs (49152-65535)
            // Try common and spread-out ports
            val ports = (49152..65535 step 100).toList() + 
                        listOf(49152, 50000, 55000, 60000, 61958, 62000, 63000, 64000, 65000)
            
            Timber.d("$TAG: Scanning ${addressesToTry.size} addresses, ${ports.size} ports")
            
            // Scan with parallelism for speed (but limited to avoid flooding)
            val jobs = mutableListOf<kotlinx.coroutines.Deferred<Pair<String, ApkInfo>?>>()
            
            for (address in addressesToTry.take(50)) {  // Limit to 50 addresses
                for (port in ports.take(20)) {  // Limit to 20 ports
                    jobs.add(async {
                        val url = "http://$address:$port"
                        try {
                            val info = client.fetchApkInfo(url, code)
                            if (info != null) {
                                Timber.i("$TAG: Found server at $url")
                                return@async url to info
                            }
                        } catch (e: Exception) {
                            // Ignore - connection failed
                        }
                        null
                    })
                }
            }
            
            // Wait for results, return first success
            for (job in jobs) {
                val result = job.await()
                if (result != null) {
                    results.add(result)
                    // Cancel remaining jobs
                    jobs.forEach { it.cancel() }
                    return@withContext
                }
            }
        }
        
        return results
    }
    
    private fun promptForServerAddress(code: String) {
        // Show dialog to enter server IP manually
        // The URL is shown on sender's screen like: http://192.168.1.5:54321
        val editText = android.widget.EditText(requireContext()).apply {
            hint = "192.168.x.x:port"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            // Pre-fill with network prefix if available
            getLocalIpAddress()?.let { ip ->
                val prefix = ip.substringBeforeLast(".")
                setText("$prefix.")
                setSelection(text.length)
            }
        }
        
        AlertDialog.Builder(requireContext())
            .setTitle("Enter Sender's Address")
            .setMessage("Look at the sender's screen for the address.\n\nIt looks like: http://192.168.x.x:12345\n\nEnter the IP and port below:")
            .setView(editText)
            .setPositiveButton("Connect") { _, _ ->
                val address = editText.text.toString().trim()
                if (address.isNotEmpty()) {
                    // Remove http:// prefix if user copied it
                    val cleanAddress = address
                        .removePrefix("http://")
                        .removePrefix("https://")
                    val url = "http://$cleanAddress"
                    
                    binding.textReceiverStatus.text = "Connecting to $cleanAddress..."
                    
                    scope.launch {
                        downloadFromServer(url, code)
                    }
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                cancelReceiving()
            }
            .setCancelable(false)
            .show()
    }
    
    private suspend fun downloadFromServer(url: String, code: String) {
        val context = requireContext()
        
        Timber.i("$TAG: Attempting download from $url with code ${code.take(3)}***")
        
        withContext(Dispatchers.Main) {
            binding.layoutCodeEntry.visibility = View.GONE
            binding.layoutDownload.visibility = View.VISIBLE
            binding.textReceiverStatus.text = "Connecting..."
            binding.progressReceiver.progress = 0
        }
        
        // Fetch APK info first
        Timber.d("$TAG: Fetching APK info from $url")
        val info = httpClient?.fetchApkInfo(url, code)
        
        if (info == null) {
            Timber.e("$TAG: Failed to fetch APK info from $url")
            withContext(Dispatchers.Main) {
                showError("Could not connect to sender.\n\nCheck:\n• Both devices on same network\n• Address matches sender's screen\n• Sender is still active")
            }
            return
        }
        
        Timber.i("$TAG: Got APK info: v${info.versionName}, ${info.sizeBytes} bytes")
        
        // Verify signature matches our app
        val currentFingerprint = ApkExporter.getSignatureFingerprint(context)
        if (info.signatureFingerprint != currentFingerprint) {
            Timber.e("$TAG: Signature mismatch! Expected: $currentFingerprint, got: ${info.signatureFingerprint}")
            withContext(Dispatchers.Main) {
                showError("Security error: App signature mismatch")
            }
            return
        }
        
        Timber.d("$TAG: Signature verified, starting download")
        
        withContext(Dispatchers.Main) {
            binding.textReceiverStatus.text = "Downloading..."
        }
        
        // Download APK
        val result = httpClient?.downloadApk(url, code, info)
        
        if (result == null) {
            Timber.e("$TAG: Download returned null")
            // Error already handled by callback
        } else {
            Timber.i("$TAG: Download completed: ${result.absolutePath}")
        }
    }
    
    private fun cancelReceiving() {
        httpClient?.cancel()
        cleanup()
        ShareModeManager.cancelSession()
        showModeSelection()
    }
    
    private fun verifyAndShowResult(file: File, code: String) {
        scope.launch {
            binding.textReceiverStatus.text = "Verifying..."
            ShareModeManager.setVerifying()
            
            // Get expected info from server (we should have cached this)
            val result = withContext(Dispatchers.IO) {
                val client = HttpDistributionClient(requireContext())
                // We need the URL we connected to - for now, trust the file
                // In production, we'd cache the ApkInfo from the initial fetch
                
                // Just verify it's a valid APK with matching signature
                ApkVerifier.isValidApk(requireContext(), file)
            }
            
            if (result) {
                ShareModeManager.completeSession(file.length())
                showReceiverResult(true, file)
            } else {
                ShareModeManager.failSession("APK verification failed")
                file.delete()
                showReceiverResult(false, null)
            }
        }
    }
    
    private fun showReceiverResult(success: Boolean, apkFile: File?) {
        currentMode = Mode.RESULT
        
        binding.layoutReceiver.visibility = View.GONE
        binding.layoutResult.visibility = View.VISIBLE
        
        if (success && apkFile != null) {
            downloadedApkFile = apkFile
            
            binding.imgResult.setImageResource(android.R.drawable.ic_dialog_info)
            binding.textResultTitle.text = "Download Complete"
            binding.textResultMessage.text = "The app is ready to install (${formatBytes(apkFile.length())})."
            binding.btnInstall.visibility = View.VISIBLE
        } else {
            binding.imgResult.setImageResource(android.R.drawable.ic_dialog_alert)
            binding.textResultTitle.text = "Download Failed"
            binding.textResultMessage.text = "Could not download or verify the app."
            binding.btnInstall.visibility = View.GONE
        }
        
        cleanup()
    }
    
    // ========================================================================
    // Install
    // ========================================================================
    
    private fun installApk(file: File) {
        try {
            val context = requireContext()
            
            // Get content URI using FileProvider
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            
            startActivity(intent)
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to install APK")
            AlertDialog.Builder(requireContext())
                .setTitle("Install Error")
                .setMessage("Could not start installation: ${e.message}")
                .setPositiveButton("OK", null)
                .show()
        }
    }
    
    // ========================================================================
    // Utilities
    // ========================================================================
    
    private fun showError(message: String) {
        currentMode = Mode.RESULT
        
        binding.layoutModeSelection.visibility = View.GONE
        binding.layoutSender.visibility = View.GONE
        binding.layoutReceiver.visibility = View.GONE
        binding.layoutResult.visibility = View.VISIBLE
        
        binding.imgResult.setImageResource(android.R.drawable.ic_dialog_alert)
        binding.textResultTitle.text = "Error"
        binding.textResultMessage.text = message
        binding.btnInstall.visibility = View.GONE
        
        cleanup()
    }
    
    private fun cleanup() {
        httpServer?.stop()
        httpServer = null
        httpClient?.cancel()
        httpClient = null
        sessionCode = null
        serverUrl = null
        
        // Cleanup exported APK
        ApkExporter.cleanup(requireContext())
        
        // Cleanup WiFi Direct groups
        WifiDirectGroupCleanup.forceRemoveGroup(requireContext())
    }
    
    private fun formatSessionCode(code: String): String {
        // Format as "XXX XXX" for readability
        return if (code.length == 6) {
            "${code.substring(0, 3)} ${code.substring(3)}"
        } else {
            code
        }
    }
    
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }
    
    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address.isLoopbackAddress) continue
                    if (address.hostAddress?.contains(":") == true) continue  // IPv6
                    
                    return address.hostAddress
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to get local IP")
        }
        return null
    }
}
