/*
 * OTA update client for Rangzen.
 * Handles checking for updates, downloading APKs, and triggering installation.
 */
package org.denovogroup.rangzen.backend.update

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.FileProvider
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.denovogroup.rangzen.BuildConfig
import org.denovogroup.rangzen.backend.AppConfig
import org.denovogroup.rangzen.backend.telemetry.TelemetryClient
import org.denovogroup.rangzen.backend.telemetry.TelemetryEvent
import timber.log.Timber
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Client for managing OTA updates.
 * Only active when QA mode (telemetry) is enabled.
 */
class UpdateClient private constructor(
    private val context: Context,
    private val serverUrl: String,
    private val apiToken: String
) {
    companion object {
        private const val PREFS_NAME = "update_prefs"
        private const val PREF_LAST_CHECK = "last_update_check"
        private const val PREF_PENDING_VERSION = "pending_version_code"
        private const val PREF_DOWNLOADED_APK = "downloaded_apk_path"

        private const val CONNECTION_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val DOWNLOAD_BUFFER_SIZE = 8192

        @Volatile
        private var instance: UpdateClient? = null

        fun init(context: Context, serverUrl: String, apiToken: String): UpdateClient {
            return instance ?: synchronized(this) {
                instance ?: UpdateClient(context.applicationContext, serverUrl, apiToken).also {
                    instance = it
                }
            }
        }

        fun getInstance(): UpdateClient? = instance
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gson = Gson()
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    private var checkJob: Job? = null

    /**
     * Start periodic update checks.
     * Only runs if QA mode is enabled.
     */
    fun startPeriodicChecks() {
        if (!isQaModeEnabled()) {
            Timber.d("QA mode disabled, skipping update checks")
            return
        }

        val checkInterval = AppConfig.otaCheckIntervalMs(context)
        Timber.d("Starting periodic update checks every ${checkInterval / 1000}s")

        checkJob?.cancel()
        checkJob = scope.launch {
            while (isActive) {
                try {
                    checkForUpdate()
                } catch (e: Exception) {
                    Timber.w(e, "Periodic update check failed")
                }
                delay(checkInterval)
            }
        }
    }

    fun stopPeriodicChecks() {
        checkJob?.cancel()
        checkJob = null
    }

    /**
     * Check for available update.
     * @return ReleaseInfo if update available, null otherwise.
     */
    suspend fun checkForUpdate(): ReleaseInfo? = withContext(Dispatchers.IO) {
        if (!isQaModeEnabled()) return@withContext null

        _state.value = UpdateState.Checking
        trackOtaEvent(TelemetryEvent.TYPE_OTA_CHECK)

        try {
            val currentVersionCode = BuildConfig.VERSION_CODE
            val url = URL("$serverUrl/v1/releases/latest?current_version_code=$currentVersionCode")
            val connection = url.openConnection() as HttpURLConnection

            try {
                connection.requestMethod = "GET"
                connection.setRequestProperty("Authorization", "Bearer $apiToken")
                connection.connectTimeout = CONNECTION_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    throw Exception("Server returned $responseCode")
                }

                val response = InputStreamReader(connection.inputStream).use { reader ->
                    gson.fromJson(reader, VersionCheckResponse::class.java)
                }

                prefs.edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply()

                if (response.updateAvailable && response.release != null) {
                    val release = response.release
                    // Check minimum SDK
                    if (release.minSdk <= Build.VERSION.SDK_INT) {
                        _state.value = UpdateState.UpdateAvailable(release)
                        Timber.i("Update available: ${release.versionName} (${release.versionCode})")

                        // Auto-download if configured
                        if (AppConfig.otaAutoDownload(context)) {
                            downloadUpdate(release)
                        }

                        return@withContext release
                    } else {
                        Timber.d("Update requires SDK ${release.minSdk}, device has ${Build.VERSION.SDK_INT}")
                    }
                }

                _state.value = UpdateState.UpToDate
                null
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Timber.w(e, "Update check failed")
            _state.value = UpdateState.Error(e.message ?: "Check failed")
            null
        }
    }

    /**
     * Download the update APK.
     * @param release The release to download.
     * @return true if download completed successfully.
     */
    suspend fun downloadUpdate(release: ReleaseInfo): Boolean = withContext(Dispatchers.IO) {
        // Check WiFi requirement
        val wifiOnly = AppConfig.otaWifiOnly(context)
        if (wifiOnly && !isOnWifi()) {
            Timber.d("Not on WiFi, deferring download")
            _state.value = UpdateState.WaitingForWifi(release)
            return@withContext false
        }

        _state.value = UpdateState.Downloading(release, 0f)
        trackOtaEvent(TelemetryEvent.TYPE_OTA_DOWNLOAD_START, mapOf("to_version" to release.versionCode))

        try {
            val downloadDir = File(context.cacheDir, "updates")
            if (!downloadDir.exists()) {
                val created = downloadDir.mkdirs()
                Timber.d("Created updates directory: $created, path: ${downloadDir.absolutePath}")
                if (!created && !downloadDir.exists()) {
                    throw Exception("Failed to create updates directory: ${downloadDir.absolutePath}")
                }
            }

            val apkFile = File(downloadDir, "update-${release.versionCode}.apk")
            val tempFile = File(downloadDir, "update-${release.versionCode}.apk.tmp")

            // Check for existing partial download
            val existingBytes = if (tempFile.exists()) tempFile.length() else 0L

            val downloadUrl = release.downloadUrl ?: "/v1/releases/${release.id}/download"
            val url = URL("$serverUrl$downloadUrl")
            val connection = url.openConnection() as HttpURLConnection

            try {
                connection.requestMethod = "GET"
                connection.setRequestProperty("Authorization", "Bearer $apiToken")
                connection.connectTimeout = CONNECTION_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS

                // Request resume if partial file exists
                if (existingBytes > 0) {
                    connection.setRequestProperty("Range", "bytes=$existingBytes-")
                    Timber.d("Resuming download from byte $existingBytes")
                }

                val responseCode = connection.responseCode

                val totalBytes: Long
                val startByte: Long

                when (responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        totalBytes = connection.contentLength.toLong()
                        startByte = 0
                        if (tempFile.exists()) tempFile.delete()
                        // Ensure file can be created
                        tempFile.parentFile?.mkdirs()
                        tempFile.createNewFile()
                    }
                    HttpURLConnection.HTTP_PARTIAL -> {
                        totalBytes = release.sizeBytes
                        startByte = existingBytes
                    }
                    else -> {
                        throw Exception("Server returned $responseCode")
                    }
                }

                // Download with progress
                RandomAccessFile(tempFile, "rw").use { raf ->
                    raf.seek(startByte)

                    connection.inputStream.use { input ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                        var bytesRead: Int
                        var downloadedBytes = startByte

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            raf.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead

                            val progress = if (totalBytes > 0) {
                                downloadedBytes.toFloat() / totalBytes
                            } else {
                                0f
                            }
                            _state.value = UpdateState.Downloading(release, progress)
                        }
                    }
                }

                // Verify SHA-256 hash
                if (!verifyHash(tempFile, release.sha256)) {
                    tempFile.delete()
                    trackOtaEvent(TelemetryEvent.TYPE_OTA_DOWNLOAD_FAILED, mapOf(
                        "to_version" to release.versionCode,
                        "error" to "Hash mismatch"
                    ))
                    _state.value = UpdateState.Error("Download verification failed")
                    return@withContext false
                }

                // Rename temp file to final
                tempFile.renameTo(apkFile)

                // Save state
                prefs.edit()
                    .putString(PREF_DOWNLOADED_APK, apkFile.absolutePath)
                    .putInt(PREF_PENDING_VERSION, release.versionCode)
                    .apply()

                trackOtaEvent(TelemetryEvent.TYPE_OTA_DOWNLOAD_COMPLETE, mapOf("to_version" to release.versionCode))
                _state.value = UpdateState.ReadyToInstall(release, apkFile)

                Timber.i("Download complete: ${apkFile.absolutePath}")
                true
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Timber.e(e, "Download failed")
            trackOtaEvent(TelemetryEvent.TYPE_OTA_DOWNLOAD_FAILED, mapOf(
                "to_version" to release.versionCode,
                "error" to (e.message ?: "Unknown error")
            ))
            _state.value = UpdateState.Error(e.message ?: "Download failed")
            false
        }
    }

    /**
     * Prompt the user to install the downloaded APK.
     */
    fun promptInstall(apkFile: File, release: ReleaseInfo) {
        trackOtaEvent(TelemetryEvent.TYPE_OTA_INSTALL_START, mapOf("to_version" to release.versionCode))

        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            context.startActivity(intent)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start install")
            trackOtaEvent(TelemetryEvent.TYPE_OTA_INSTALL_FAILED, mapOf(
                "to_version" to release.versionCode,
                "error" to (e.message ?: "Unknown error")
            ))
            _state.value = UpdateState.Error("Failed to start install: ${e.message}")
        }
    }

    /**
     * Check if there's a previously downloaded APK ready to install.
     */
    fun checkPendingInstall(): Pair<ReleaseInfo, File>? {
        val apkPath = prefs.getString(PREF_DOWNLOADED_APK, null) ?: return null
        val versionCode = prefs.getInt(PREF_PENDING_VERSION, 0)

        if (versionCode <= BuildConfig.VERSION_CODE) {
            // Already installed or older version
            clearPendingInstall()
            return null
        }

        val apkFile = File(apkPath)
        if (!apkFile.exists()) {
            clearPendingInstall()
            return null
        }

        // Create minimal ReleaseInfo for the pending install
        val release = ReleaseInfo(
            id = "",
            versionCode = versionCode,
            versionName = "v$versionCode",
            sha256 = "",
            sizeBytes = apkFile.length()
        )

        _state.value = UpdateState.ReadyToInstall(release, apkFile)
        return Pair(release, apkFile)
    }

    /**
     * Clear the pending install state.
     */
    fun clearPendingInstall() {
        val apkPath = prefs.getString(PREF_DOWNLOADED_APK, null)
        if (apkPath != null) {
            File(apkPath).delete()
        }
        prefs.edit()
            .remove(PREF_DOWNLOADED_APK)
            .remove(PREF_PENDING_VERSION)
            .apply()
        _state.value = UpdateState.Idle
    }

    /**
     * Report install success (called after app restarts with new version).
     */
    fun reportInstallSuccess(previousVersionCode: Int) {
        trackOtaEvent(TelemetryEvent.TYPE_OTA_INSTALL_SUCCESS, mapOf(
            "from_version" to previousVersionCode,
            "to_version" to BuildConfig.VERSION_CODE
        ))
        clearPendingInstall()
    }

    private fun isQaModeEnabled(): Boolean {
        val prefs = context.getSharedPreferences("rangzen_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("qa_mode", false)
    }

    private fun isOnWifi(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun verifyHash(file: File, expectedSha256: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        val matches = actualHash.equals(expectedSha256, ignoreCase = true)
        if (!matches) {
            Timber.w("Hash mismatch: expected $expectedSha256, got $actualHash")
        }
        return matches
    }

    private fun trackOtaEvent(eventType: String, payload: Map<String, Any>? = null) {
        val fullPayload = buildMap {
            put("from_version", BuildConfig.VERSION_CODE)
            payload?.forEach { (k, v) -> put(k, v) }
        }
        TelemetryClient.getInstance()?.track(eventType, payload = fullPayload)
    }
}
