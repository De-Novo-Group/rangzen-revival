/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * ApkVerifier - Verifies APK integrity and authenticity before installation.
 * 
 * SECURITY CHECKS:
 * 1. SHA-256 hash matches expected value
 * 2. Signing certificate matches pinned fingerprint
 * 3. Version code is not lower than current (downgrade protection)
 * 4. APK is a valid Android package
 * 
 * SAFETY NOTES:
 * - Fails loudly with clear error messages (no sensitive info leaked)
 * - Does NOT auto-install - returns result for user confirmation
 * - Logs verification results but NOT file paths or hashes
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
import java.security.MessageDigest

/**
 * Utility for verifying APK integrity and authenticity.
 */
object ApkVerifier {
    
    private const val TAG = "ApkVerifier"
    
    /**
     * Verification result.
     */
    sealed class VerificationResult {
        /** APK passed all verification checks */
        data class Success(
            val versionCode: Int,
            val versionName: String,
            val signatureFingerprint: String
        ) : VerificationResult()
        
        /** APK failed verification */
        data class Failure(
            val reason: FailureReason,
            val message: String
        ) : VerificationResult()
    }
    
    /**
     * Reasons for verification failure.
     * Keep messages generic to avoid information leakage.
     */
    enum class FailureReason {
        /** File doesn't exist or can't be read */
        FILE_NOT_FOUND,
        
        /** Not a valid APK file */
        INVALID_APK,
        
        /** SHA-256 hash doesn't match */
        HASH_MISMATCH,
        
        /** Signing certificate doesn't match */
        SIGNATURE_MISMATCH,
        
        /** Version is older than current (downgrade attempt) */
        DOWNGRADE_REJECTED,
        
        /** Package name doesn't match */
        PACKAGE_MISMATCH,
        
        /** Unknown error during verification */
        VERIFICATION_ERROR
    }
    
    /**
     * Verify an APK file.
     * 
     * @param context Android context
     * @param apkFile The APK file to verify
     * @param expectedInfo Expected APK info (from sender)
     * @return VerificationResult indicating success or failure reason
     */
    fun verify(
        context: Context,
        apkFile: File,
        expectedInfo: ApkInfo
    ): VerificationResult {
        Timber.d("$TAG: Starting APK verification")
        
        // Check 1: File exists and is readable
        if (!apkFile.exists() || !apkFile.canRead()) {
            Timber.e("$TAG: APK file not found or not readable")
            return VerificationResult.Failure(
                FailureReason.FILE_NOT_FOUND,
                "File not found"
            )
        }
        
        // Check 2: File size matches
        if (apkFile.length() != expectedInfo.sizeBytes) {
            Timber.e("$TAG: APK size mismatch")
            return VerificationResult.Failure(
                FailureReason.HASH_MISMATCH,
                "File size mismatch"
            )
        }
        
        // Check 3: SHA-256 hash matches
        val actualHash = calculateSha256(apkFile)
        if (actualHash != expectedInfo.sha256Hash) {
            Timber.e("$TAG: APK hash mismatch")
            return VerificationResult.Failure(
                FailureReason.HASH_MISMATCH,
                "File integrity check failed"
            )
        }
        
        // Check 4: Valid APK with correct package name
        val apkPackageInfo = getApkPackageInfo(context, apkFile)
        if (apkPackageInfo == null) {
            Timber.e("$TAG: Invalid APK file")
            return VerificationResult.Failure(
                FailureReason.INVALID_APK,
                "Invalid application package"
            )
        }
        
        // Check 5: Package name matches our app
        if (apkPackageInfo.packageName != context.packageName) {
            Timber.e("$TAG: Package name mismatch")
            return VerificationResult.Failure(
                FailureReason.PACKAGE_MISMATCH,
                "Package mismatch"
            )
        }
        
        // Check 6: Signing certificate matches
        val apkSignatureFingerprint = getApkSignatureFingerprint(context, apkFile)
        val currentFingerprint = ApkExporter.getSignatureFingerprint(context)
        
        if (apkSignatureFingerprint != currentFingerprint) {
            Timber.e("$TAG: Signature mismatch")
            return VerificationResult.Failure(
                FailureReason.SIGNATURE_MISMATCH,
                "Signature verification failed"
            )
        }
        
        // Check 7: Not a downgrade
        val apkVersionCode = getVersionCode(apkPackageInfo)
        val currentVersionCode = ApkExporter.getCurrentVersionCode(context)
        
        if (apkVersionCode < currentVersionCode) {
            Timber.e("$TAG: Downgrade rejected (APK: $apkVersionCode, current: $currentVersionCode)")
            return VerificationResult.Failure(
                FailureReason.DOWNGRADE_REJECTED,
                "Cannot install older version"
            )
        }
        
        // All checks passed
        val versionName = apkPackageInfo.versionName ?: "unknown"
        Timber.i("$TAG: APK verified successfully: v$versionName (code $apkVersionCode)")
        
        return VerificationResult.Success(
            versionCode = apkVersionCode,
            versionName = versionName,
            signatureFingerprint = apkSignatureFingerprint
        )
    }
    
    /**
     * Quick check if an APK is valid (without full verification).
     */
    fun isValidApk(context: Context, apkFile: File): Boolean {
        return getApkPackageInfo(context, apkFile) != null
    }
    
    /**
     * Get the pinned signing certificate fingerprint.
     * 
     * For now, this returns the current app's fingerprint.
     * In production, consider pinning multiple fingerprints for key rotation.
     */
    fun getPinnedFingerprint(context: Context): String {
        return ApkExporter.getSignatureFingerprint(context)
    }
    
    // ========================================================================
    // Private helpers
    // ========================================================================
    
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
    
    private fun getApkPackageInfo(context: Context, apkFile: File): PackageInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to parse APK")
            null
        }
    }
    
    private fun getApkSignatureFingerprint(context: Context, apkFile: File): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.GET_SIGNATURES
                )
            }
            
            if (packageInfo == null) return "unknown"
            
            val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = packageInfo.signingInfo
                if (signingInfo == null) {
                    emptyArray()
                } else if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners ?: emptyArray()
                } else {
                    signingInfo.signingCertificateHistory ?: emptyArray()
                }
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures ?: emptyArray()
            }
            
            if (signatures.isNotEmpty()) {
                calculateSignatureFingerprint(signatures[0])
            } else {
                "unknown"
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to get APK signature")
            "unknown"
        }
    }
    
    private fun calculateSignatureFingerprint(signature: Signature): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(signature.toByteArray())
        return digest.joinToString(":") { "%02X".format(it) }
    }
    
    private fun getVersionCode(packageInfo: PackageInfo): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode
        }
    }
}
