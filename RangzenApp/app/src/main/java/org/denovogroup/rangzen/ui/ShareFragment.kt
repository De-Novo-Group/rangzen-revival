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
        val context = requireContext()
        
        // We need to discover the sender's IP
        // For now, prompt user for IP or try common addresses
        // In production, we would use mDNS/NSD or WiFi Direct discovery
        
        binding.textReceiverStatus.text = "Scanning network..."
        
        // Try common gateway addresses on the local network
        val possibleServers = discoverServers(code)
        
        if (possibleServers.isEmpty()) {
            // Fall back to manual IP entry
            withContext(Dispatchers.Main) {
                promptForServerAddress(code)
            }
            return
        }
        
        // Try the first working server
        for ((url, info) in possibleServers) {
            binding.textReceiverStatus.text = "Connecting to sender..."
            
            val file = httpClient?.downloadApk(url, code, info)
            if (file != null) {
                return  // Success handled in callback
            }
        }
        
        // All servers failed
        withContext(Dispatchers.Main) {
            promptForServerAddress(code)
        }
    }
    
    /**
     * Discover servers on the local network.
     * Scans common port range for HTTP servers with valid APK info.
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
        
        // Scan network (this is a simplified approach)
        // In production, use mDNS/NSD discovery
        withContext(Dispatchers.IO) {
            val client = HttpDistributionClient(context)
            
            // Try a few common addresses (gateway, .1-.10)
            val addressesToTry = listOf(
                "$prefix.1", "$prefix.2", "$prefix.3", "$prefix.4", "$prefix.5",
                "$prefix.100", "$prefix.101", "$prefix.102"
            )
            
            val ports = listOf(49152, 49153, 49154, 49155, 49200, 50000, 55000, 60000)
            
            for (address in addressesToTry) {
                for (port in ports) {
                    val url = "http://$address:$port"
                    try {
                        val info = client.fetchApkInfo(url, code)
                        if (info != null) {
                            Timber.i("$TAG: Found server at $url")
                            results.add(url to info)
                            return@withContext  // Found one, stop scanning
                        }
                    } catch (e: Exception) {
                        // Ignore connection failures
                    }
                }
            }
        }
        
        return results
    }
    
    private fun promptForServerAddress(code: String) {
        // Show dialog to enter server IP manually
        val editText = android.widget.EditText(requireContext()).apply {
            hint = "192.168.1.xxx:port"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        
        AlertDialog.Builder(requireContext())
            .setTitle("Enter Sender Address")
            .setMessage("Could not find sender automatically. Enter their IP address and port (shown on their screen).")
            .setView(editText)
            .setPositiveButton("Connect") { _, _ ->
                val address = editText.text.toString().trim()
                if (address.isNotEmpty()) {
                    val url = if (address.startsWith("http://")) address else "http://$address"
                    scope.launch {
                        downloadFromServer(url, code)
                    }
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                cancelReceiving()
            }
            .show()
    }
    
    private suspend fun downloadFromServer(url: String, code: String) {
        val context = requireContext()
        
        binding.textReceiverStatus.text = "Connecting to $url..."
        
        // Fetch APK info
        val info = httpClient?.fetchApkInfo(url, code)
        if (info == null) {
            withContext(Dispatchers.Main) {
                showError("Could not connect to sender. Check the address and code.")
            }
            return
        }
        
        // Verify signature matches
        val currentFingerprint = ApkExporter.getSignatureFingerprint(context)
        if (info.signatureFingerprint != currentFingerprint) {
            withContext(Dispatchers.Main) {
                showError("Security error: App signature mismatch")
            }
            return
        }
        
        binding.textReceiverStatus.text = "Downloading..."
        
        // Download
        httpClient?.downloadApk(url, code, info)
        // Result handled in callback
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
