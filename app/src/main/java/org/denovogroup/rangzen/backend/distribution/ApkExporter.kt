/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * ApkExporter - Extracts the current app's APK for sharing.
 * 
 * SAFETY NOTES:
 * - Exports to app's private cache directory (not world-readable)
 * - Calculates SHA-256 hash for integrity verification
 * - Extracts signing certificate fingerprint for verification
 * - Does NOT log any identifying information
 */
package org.denovogroup.rangzen.backend.distribution

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Utility for exporting the current app's APK for sharing.
 */
object ApkExporter {
    
    private const val TAG = "ApkExporter"
    
    // Canonical name for exported APK (no version info to avoid fingerprinting)
    private const val EXPORTED_APK_NAME = "murmur.apk"
    
    /**
     * Result of APK export operation.
     */
    data class ExportResult(
        val success: Boolean,
        val apkFile: File? = null,
        val apkInfo: ApkInfo? = null,
        val error: String? = null
    )
    
    /**
     * Export the current app's APK to cache directory.
     * 
     * @param context Android context
     * @return ExportResult with file and metadata, or error
     */
    fun exportApk(context: Context): ExportResult {
        return try {
            Timber.d("$TAG: Starting APK export")
            
            // Get the source APK path
            val appInfo = context.applicationInfo
            val sourceApkPath = appInfo.sourceDir
            val sourceFile = File(sourceApkPath)
            
            if (!sourceFile.exists()) {
                return ExportResult(
                    success = false,
                    error = "Source APK not found"
                )
            }
            
            // Create output directory in cache
            val outputDir = File(context.cacheDir, "distribution")
            if (!outputDir.exists()) {
                outputDir.mkdirs()
            }
            
            // Copy APK to cache with canonical name
            val outputFile = File(outputDir, EXPORTED_APK_NAME)
            copyFile(sourceFile, outputFile)
            
            // Calculate SHA-256 hash
            val sha256Hash = calculateSha256(outputFile)
            
            // Get package info
            val packageInfo = getPackageInfo(context)
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
            val versionName = packageInfo.versionName ?: "unknown"
            
            // Get signing certificate fingerprint
            val signatureFingerprint = getSignatureFingerprint(context)
            
            val apkInfo = ApkInfo(
                versionCode = versionCode,
                versionName = versionName,
                sizeBytes = outputFile.length(),
                sha256Hash = sha256Hash,
                signatureFingerprint = signatureFingerprint
            )
            
            Timber.i("$TAG: APK exported successfully: v$versionName (code $versionCode), ${outputFile.length()} bytes")
            
            ExportResult(
                success = true,
                apkFile = outputFile,
                apkInfo = apkInfo
            )
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to export APK")
            ExportResult(
                success = false,
                error = "Export failed: ${e.message}"
            )
        }
    }
    
    /**
     * Get the exported APK file if it exists and is valid.
     */
    fun getExportedApk(context: Context): File? {
        val outputDir = File(context.cacheDir, "distribution")
        val apkFile = File(outputDir, EXPORTED_APK_NAME)
        return if (apkFile.exists() && apkFile.length() > 0) apkFile else null
    }
    
    /**
     * Clean up exported APK files.
     * Call this when share mode ends.
     */
    fun cleanup(context: Context) {
        try {
            val outputDir = File(context.cacheDir, "distribution")
            if (outputDir.exists()) {
                outputDir.listFiles()?.forEach { it.delete() }
            }
            Timber.d("$TAG: Cleaned up exported APK")
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Failed to cleanup")
        }
    }
    
    /**
     * Get the current app's version code.
     */
    fun getCurrentVersionCode(context: Context): Int {
        return try {
            val packageInfo = getPackageInfo(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to get version code")
            0
        }
    }
    
    /**
     * Get the current app's signing certificate fingerprint.
     */
    fun getSignatureFingerprint(context: Context): String {
        return try {
            val signatures = getSignatures(context)
            if (signatures.isNotEmpty()) {
                calculateSignatureFingerprint(signatures[0])
            } else {
                "unknown"
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to get signature fingerprint")
            "unknown"
        }
    }
    
    // ========================================================================
    // Private helpers
    // ========================================================================
    
    private fun getPackageInfo(context: Context): PackageInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
    }
    
    private fun getSignatures(context: Context): Array<Signature> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            ).signingInfo
            
            if (signingInfo == null) {
                emptyArray()
            } else if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners ?: emptyArray()
            } else {
                signingInfo.signingCertificateHistory ?: emptyArray()
            }
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES
            ).signatures ?: emptyArray()
        }
    }
    
    private fun calculateSignatureFingerprint(signature: Signature): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(signature.toByteArray())
        return digest.joinToString(":") { "%02X".format(it) }
    }
    
    private fun copyFile(source: File, dest: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
            }
        }
    }
    
    private fun calculateSha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                md.update(buffer, 0, bytesRead)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
