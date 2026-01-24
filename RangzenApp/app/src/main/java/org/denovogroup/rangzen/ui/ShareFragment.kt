/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * ShareFragment - UI for offline APK distribution (SEND ONLY).
 * 
 * Two sharing methods:
 * 1. System share sheet (Quick Share / Bluetooth / USB) - primary
 * 2. Local HTTP server + QR code - fallback for same-network sharing
 * 
 * The receiver does NOT need Murmur installed - they use OS file acceptance
 * or browser download.
 */
package org.denovogroup.rangzen.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import kotlinx.coroutines.*
import org.denovogroup.rangzen.backend.distribution.*
import org.denovogroup.rangzen.databinding.FragmentShareBinding
import timber.log.Timber
import java.io.File

/**
 * Fragment for sharing the Murmur app offline (send-only).
 * 
 * Flow:
 * 1. User taps "Share via Quick Share / Bluetooth" -> system share sheet
 * 2. OR user taps "Share via QR code" -> starts HTTP server, shows QR
 * 3. Receiver downloads APK via their browser or file manager
 */
class ShareFragment : Fragment() {
    
    companion object {
        private const val TAG = "ShareFragment"
        
        fun newInstance(): ShareFragment = ShareFragment()
    }
    
    private var _binding: FragmentShareBinding? = null
    private val binding get() = _binding!!
    
    // State
    private enum class Mode { SELECTION, QR_SERVER, RESULT }
    private var currentMode = Mode.SELECTION
    
    // Distribution components
    private var httpServer: HttpDistributionServer? = null
    
    // Exported APK file (for system share)
    private var exportedApkFile: File? = null
    
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
        
        // Primary action: System share (Quick Share / Bluetooth / USB)
        binding.btnShareSystem.setOnClickListener {
            shareViaSystemSheet()
        }
        
        // Secondary action: QR code (local HTTP server)
        binding.btnShareQr.setOnClickListener {
            startQrServerMode()
        }
        
        // QR server controls
        binding.btnStopServer.setOnClickListener {
            stopQrServer()
        }
        
        // Result controls
        binding.btnDone.setOnClickListener {
            activity?.onBackPressed()
        }
    }
    
    private fun handleBack() {
        when (currentMode) {
            Mode.SELECTION -> activity?.onBackPressed()
            Mode.QR_SERVER -> stopQrServer()
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
        binding.layoutQrServer.visibility = View.GONE
        binding.layoutResult.visibility = View.GONE
    }
    
    // ========================================================================
    // System Share (Quick Share / Bluetooth / USB)
    // ========================================================================
    
    /**
     * Share the APK via Android's system share sheet.
     * This allows Quick Share, Bluetooth, USB, or any other sharing app.
     */
    private fun shareViaSystemSheet() {
        scope.launch {
            // Show loading state
            binding.btnShareSystem.isEnabled = false
            binding.btnShareSystem.text = "Preparing..."
            
            try {
                // Export APK
                val exportResult = withContext(Dispatchers.IO) {
                    ApkExporter.exportApk(requireContext())
                }
                
                if (!exportResult.success || exportResult.apkFile == null) {
                    showError("Failed to prepare app: ${exportResult.error}")
                    return@launch
                }
                
                exportedApkFile = exportResult.apkFile
                
                // Create share intent with FileProvider URI
                val uri = FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    exportResult.apkFile
                )
                
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.android.package-archive"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                
                // Launch share sheet with chooser
                val chooserIntent = Intent.createChooser(shareIntent, "Share Murmur (offline)")
                startActivity(chooserIntent)
                
                Timber.i("$TAG: Launched system share sheet")
                
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Failed to share via system")
                showError("Failed to share: ${e.message}")
            } finally {
                // Reset button state
                binding.btnShareSystem.isEnabled = true
                binding.btnShareSystem.text = getString(org.denovogroup.rangzen.R.string.share_system_btn)
            }
        }
    }
    
    // ========================================================================
    // QR Server Mode (Local HTTP + QR Code)
    // ========================================================================
    
    /**
     * Start local HTTP server and show QR code for download URL.
     * Receiver scans QR -> browser opens -> downloads APK.
     */
    private fun startQrServerMode() {
        currentMode = Mode.QR_SERVER
        
        binding.layoutModeSelection.visibility = View.GONE
        binding.layoutQrServer.visibility = View.VISIBLE
        binding.layoutResult.visibility = View.GONE
        
        // Reset UI
        binding.imgQrCode.setImageBitmap(null)
        binding.textServerUrl.text = "Preparing..."
        binding.textServerStatus.text = ""
        
        // Start server
        scope.launch {
            startHttpServer()
        }
    }
    
    private suspend fun startHttpServer() {
        val context = requireContext()
        
        // Export APK
        binding.textServerStatus.text = "Exporting app..."
        
        val exportResult = withContext(Dispatchers.IO) {
            ApkExporter.exportApk(context)
        }
        
        if (!exportResult.success || exportResult.apkFile == null || exportResult.apkInfo == null) {
            showError("Failed to export app: ${exportResult.error}")
            return
        }
        
        exportedApkFile = exportResult.apkFile
        
        // Create and start HTTP server (no session code required)
        httpServer = HttpDistributionServer(context).apply {
            onClientConnected = {
                activity?.runOnUiThread {
                    binding.textServerStatus.text = "Someone is downloading..."
                }
            }
            
            onTransferProgress = { transferred, total ->
                activity?.runOnUiThread {
                    val percent = ((transferred.toFloat() / total) * 100).toInt()
                    binding.textServerStatus.text = "Downloading: $percent%"
                }
            }
            
            onTransferComplete = { bytes ->
                activity?.runOnUiThread {
                    showResult(true, "Successfully shared ${formatBytes(bytes)}")
                }
            }
            
            onError = { message ->
                activity?.runOnUiThread {
                    showError(message)
                }
            }
        }
        
        // Start server (no session code)
        val url = httpServer?.start(exportResult.apkFile, exportResult.apkInfo, null)
        
        if (url == null) {
            showError("Failed to start server.\n\nMake sure you're connected to WiFi or have a hotspot active.")
            return
        }
        
        // Generate download URL (direct link, no code needed)
        val downloadUrl = "$url/download"
        
        // Generate QR code for the download URL
        try {
            val qrBitmap = generateQrCode(downloadUrl, 400)
            binding.imgQrCode.setImageBitmap(qrBitmap)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to generate QR code")
        }
        
        // Update UI
        binding.textServerUrl.text = downloadUrl
        binding.textServerStatus.text = "Ready. Ask receiver to scan the QR code."
        
        Timber.i("$TAG: QR server started at $downloadUrl")
    }
    
    private fun stopQrServer() {
        cleanup()
        showModeSelection()
    }
    
    // ========================================================================
    // Result
    // ========================================================================
    
    private fun showResult(success: Boolean, message: String) {
        currentMode = Mode.RESULT
        
        binding.layoutModeSelection.visibility = View.GONE
        binding.layoutQrServer.visibility = View.GONE
        binding.layoutResult.visibility = View.VISIBLE
        
        if (success) {
            binding.imgResult.setImageResource(android.R.drawable.ic_dialog_info)
            binding.textResultTitle.text = "Transfer Complete"
        } else {
            binding.imgResult.setImageResource(android.R.drawable.ic_dialog_alert)
            binding.textResultTitle.text = "Error"
        }
        
        binding.textResultMessage.text = message
        
        cleanup()
    }
    
    private fun showError(message: String) {
        showResult(false, message)
    }
    
    // ========================================================================
    // Utilities
    // ========================================================================
    
    private fun cleanup() {
        httpServer?.stop()
        httpServer = null
        
        // Cleanup exported APK
        try {
            ApkExporter.cleanup(requireContext())
        } catch (e: Exception) {
            // Ignore - context may be unavailable
        }
        
        // Cleanup WiFi Direct groups
        try {
            WifiDirectGroupCleanup.forceRemoveGroup(requireContext())
        } catch (e: Exception) {
            // Ignore
        }
    }
    
    private fun generateQrCode(content: String, size: Int): Bitmap {
        val barcodeEncoder = BarcodeEncoder()
        return barcodeEncoder.encodeBitmap(content, BarcodeFormat.QR_CODE, size, size)
    }
    
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }
}
