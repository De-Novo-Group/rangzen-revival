/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * ShareModeManager - Manages the state machine for offline APK distribution (SEND ONLY).
 * 
 * SAFETY PRINCIPLES:
 * - Share Mode is completely separate from message transport
 * - All sessions are user-initiated and time-limited
 * - No automatic discovery or connection attempts
 * - No persistent logging of transfer counterparts
 * - No device identifiers in transfer payloads
 * 
 * This manager does NOT interfere with BLE, LAN, or WiFi Direct message exchanges.
 */
package org.denovogroup.rangzen.backend.distribution

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Singleton manager for Share Mode - the offline APK distribution feature (send-only).
 * 
 * Share Mode operates independently of message transport:
 * - Uses separate sockets/ports from messaging
 * - Has its own state machine with strict timeouts
 * - Never modifies or locks message transport resources
 */
object ShareModeManager {
    
    private const val TAG = "ShareModeManager"
    
    // Session timeout: 5 minutes max
    // After this, session is automatically cancelled for safety
    const val SESSION_TIMEOUT_MS = 5 * 60 * 1000L
    
    /**
     * Share Mode states (send-only).
     * 
     * State transitions:
     * IDLE -> PREPARING -> SHARING -> IDLE
     */
    enum class State {
        /** No share session active */
        IDLE,
        
        /** Preparing APK for sharing */
        PREPARING,
        
        /** Actively sharing (HTTP server running or system share in progress) */
        SHARING,
        
        /** Session completed (success or failure) */
        COMPLETED,
        
        /** Session cancelled by user or timeout */
        CANCELLED
    }
    
    /**
     * Transfer method being used.
     */
    enum class TransferMethod {
        /** HTTP transfer over LAN/Hotspot (QR code) */
        HTTP,
        /** System share intent (Quick Share, Bluetooth, etc.) */
        SYSTEM_SHARE
    }
    
    /**
     * Result of a share session.
     */
    data class SessionResult(
        val success: Boolean,
        val errorMessage: String? = null,
        val bytesTransferred: Long = 0,
        val durationMs: Long = 0
    )
    
    /**
     * Information about the current session.
     * Exposed for UI display.
     */
    data class SessionInfo(
        val method: TransferMethod,
        val startTime: Long,
        val expiresAt: Long,
        val progress: Float = 0f,  // 0.0 to 1.0
        val bytesTransferred: Long = 0,
        val totalBytes: Long = 0
    )
    
    // State
    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()
    
    // Session info (null when IDLE)
    private val _sessionInfo = MutableStateFlow<SessionInfo?>(null)
    val sessionInfo: StateFlow<SessionInfo?> = _sessionInfo.asStateFlow()
    
    // Last result (for UI to display after completion)
    private val _lastResult = MutableStateFlow<SessionResult?>(null)
    val lastResult: StateFlow<SessionResult?> = _lastResult.asStateFlow()
    
    // Internal
    private var timeoutJob: Job? = null
    private var sessionStartTime: Long = 0
    
    // Callbacks for transfer implementations
    var onSessionStarted: ((method: TransferMethod) -> Unit)? = null
    var onSessionEnded: ((result: SessionResult) -> Unit)? = null
    
    /**
     * Start a sender session.
     * 
     * @param context Android context
     * @param method Transfer method to use
     * @return true if session started
     */
    fun startSession(context: Context, method: TransferMethod): Boolean {
        if (_state.value != State.IDLE) {
            Timber.w("$TAG: Cannot start session - already in state ${_state.value}")
            return false
        }
        
        Timber.i("$TAG: Starting session with method $method")
        
        sessionStartTime = System.currentTimeMillis()
        
        // Update state
        _state.value = State.PREPARING
        _sessionInfo.value = SessionInfo(
            method = method,
            startTime = sessionStartTime,
            expiresAt = sessionStartTime + SESSION_TIMEOUT_MS
        )
        
        // Start timeout
        startTimeoutTimer()
        
        // Notify listeners
        onSessionStarted?.invoke(method)
        
        return true
    }
    
    /**
     * Mark session as actively sharing.
     */
    fun setSharing() {
        if (_state.value == State.PREPARING) {
            _state.value = State.SHARING
        }
    }
    
    /**
     * Update progress during transfer.
     */
    fun updateProgress(bytesTransferred: Long, totalBytes: Long) {
        val progress = if (totalBytes > 0) bytesTransferred.toFloat() / totalBytes else 0f
        
        _sessionInfo.value = _sessionInfo.value?.copy(
            progress = progress,
            bytesTransferred = bytesTransferred,
            totalBytes = totalBytes
        )
        
        // Update state if needed
        if (_state.value == State.PREPARING) {
            _state.value = State.SHARING
        }
    }
    
    /**
     * Complete the session successfully.
     */
    fun completeSession(bytesTransferred: Long = 0) {
        val duration = System.currentTimeMillis() - sessionStartTime
        
        val result = SessionResult(
            success = true,
            bytesTransferred = bytesTransferred,
            durationMs = duration
        )
        
        Timber.i("$TAG: Session completed successfully in ${duration}ms, $bytesTransferred bytes")
        
        endSession(result)
    }
    
    /**
     * Fail the session with an error.
     */
    fun failSession(errorMessage: String) {
        val duration = System.currentTimeMillis() - sessionStartTime
        
        val result = SessionResult(
            success = false,
            errorMessage = errorMessage,
            durationMs = duration
        )
        
        Timber.e("$TAG: Session failed: $errorMessage")
        
        endSession(result)
    }
    
    /**
     * Cancel the current session.
     */
    fun cancelSession() {
        if (_state.value == State.IDLE) {
            return
        }
        
        Timber.i("$TAG: Session cancelled by user")
        
        val result = SessionResult(
            success = false,
            errorMessage = "Cancelled",
            durationMs = System.currentTimeMillis() - sessionStartTime
        )
        
        _state.value = State.CANCELLED
        endSession(result)
    }
    
    /**
     * Check if Share Mode is active (any state except IDLE).
     * 
     * Message transport can check this to log (but NOT block) during share.
     */
    fun isActive(): Boolean = _state.value != State.IDLE
    
    /**
     * Clean up any stale state on app startup.
     * Call this from Application.onCreate() or Service.onCreate().
     */
    fun cleanupOnStartup() {
        Timber.d("$TAG: Cleaning up on startup")
        
        // Reset state
        _state.value = State.IDLE
        _sessionInfo.value = null
        timeoutJob?.cancel()
        timeoutJob = null
        
        // Note: WiFi Direct group cleanup is handled by WifiDirectGroupCleanup
    }
    
    // ========================================================================
    // Private helpers
    // ========================================================================
    
    private fun startTimeoutTimer() {
        timeoutJob?.cancel()
        timeoutJob = CoroutineScope(Dispatchers.Main).launch {
            delay(SESSION_TIMEOUT_MS)
            if (_state.value != State.IDLE && _state.value != State.COMPLETED) {
                Timber.w("$TAG: Session timed out")
                failSession("Session timed out")
            }
        }
    }
    
    private fun endSession(result: SessionResult) {
        timeoutJob?.cancel()
        timeoutJob = null
        
        _lastResult.value = result
        _state.value = State.COMPLETED
        
        // Notify listeners
        onSessionEnded?.invoke(result)
        
        // Reset to IDLE after a short delay (for UI to show result)
        CoroutineScope(Dispatchers.Main).launch {
            delay(100)
            _state.value = State.IDLE
            _sessionInfo.value = null
        }
    }
}
