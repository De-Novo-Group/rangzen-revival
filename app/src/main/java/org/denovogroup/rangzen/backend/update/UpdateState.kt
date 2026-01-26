/*
 * OTA update state and data classes for Rangzen.
 */
package org.denovogroup.rangzen.backend.update

import com.google.gson.annotations.SerializedName
import java.io.File

/**
 * Represents the current state of the OTA update system.
 */
sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    object UpToDate : UpdateState()
    data class UpdateAvailable(val release: ReleaseInfo) : UpdateState()
    data class WaitingForWifi(val release: ReleaseInfo) : UpdateState()
    data class Downloading(val release: ReleaseInfo, val progress: Float) : UpdateState()
    data class ReadyToInstall(val release: ReleaseInfo, val apkFile: File) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

/**
 * Information about an available release from the server.
 */
data class ReleaseInfo(
    @SerializedName("id")
    val id: String,

    @SerializedName("version_code")
    val versionCode: Int,

    @SerializedName("version_name")
    val versionName: String,

    @SerializedName("sha256")
    val sha256: String,

    @SerializedName("size_bytes")
    val sizeBytes: Long,

    @SerializedName("release_notes")
    val releaseNotes: String? = null,

    @SerializedName("min_sdk")
    val minSdk: Int = 26,

    @SerializedName("download_url")
    val downloadUrl: String? = null
)

/**
 * Response from the version check endpoint.
 */
data class VersionCheckResponse(
    @SerializedName("update_available")
    val updateAvailable: Boolean,

    @SerializedName("release")
    val release: ReleaseInfo? = null
)
