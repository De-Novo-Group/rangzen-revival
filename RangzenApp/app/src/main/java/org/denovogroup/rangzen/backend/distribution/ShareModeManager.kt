/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * ShareModeManager - Manages the state machine for offline APK distribution.
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
import java.security.SecureRandom

/**
 * Singleton manager for Share Mode - the offline APK distribution feature.
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
    
    // Session code length: 6 digits for easy verbal sharing
    private const val SESSION_CODE_LENGTH = 6
    
    /**
     * Share Mode states.
     * 
     * State transitions:
     * IDLE -> SENDER_PREPARING -> SENDER_WAITING -> SENDER_TRANSFERRING -> IDLE
     * IDLE -> RECEIVER_CONNECTING -> RECEIVER_DOWNLOADING -> RECEIVER_VERIFYING -> IDLE
     */
    enum class State {
        /** No share session active */
        IDLE,
        
        // Sender states
        /** Sender is preparing APK and session code */
        SENDER_PREPARING,
        /** Sender is waiting for receiver to connect */
        SENDER_WAITING,
        /** Transfer in progress (sender side) */
        SENDER_TRANSFERRING,
        
        // Receiver states
        /** Receiver is connecting to sender */
        RECEIVER_CONNECTING,
        /** Receiver is downloading APK */
        RECEIVER_DOWNLOADING,
        /** Receiver is verifying APK integrity */
        RECEIVER_VERIFYING,
        
        /** Session completed (success or failure) */
        COMPLETED,
        
        /** Session cancelled by user or timeout */
        CANCELLED
    }
    
    /**
     * Transfer method being used.
     */
    enum class TransferMethod {
        /** HTTP transfer over LAN/Hotspot */
        HTTP,
        /** WiFi Direct P2P transfer */
        WIFI_DIRECT,
        /** System share intent (third-party apps) */
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
        val sessionCode: String,
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
    private var currentSessionCode: String? = null
    private var sessionStartTime: Long = 0
    private val secureRandom = SecureRandom()
    
    // Callbacks for transfer implementations
    var onSessionStarted: ((sessionCode: String, method: TransferMethod) -> Unit)? = null
    var onSessionEnded: ((result: SessionResult) -> Unit)? = null
    
    /**
     * Start a sender session.
     * 
     * @param context Android context
     * @param method Transfer method to use
     * @return Session code for receiver to enter, or null on failure
     */
    fun startSenderSession(context: Context, method: TransferMethod): String? {
        if (_state.value != State.IDLE) {
            Timber.w("$TAG: Cannot start sender session - already in state ${_state.value}")
            return null
        }
        
        Timber.i("$TAG: Starting sender session with method $method")
        
        // Generate session code
        val sessionCode = generateSessionCode()
        currentSessionCode = sessionCode
        sessionStartTime = System.currentTimeMillis()
        
        // Update state
        _state.value = State.SENDER_PREPARING
        _sessionInfo.value = SessionInfo(
            sessionCode = sessionCode,
            method = method,
            startTime = sessionStartTime,
            expiresAt = sessionStartTime + SESSION_TIMEOUT_MS
        )
        
        // Start timeout
        startTimeoutTimer()
        
        // Notify listeners
        onSessionStarted?.invoke(sessionCode, method)
        
        // Move to waiting state (actual server start happens in transport layer)
        _state.value = State.SENDER_WAITING
        
        Timber.i("$TAG: Sender session started with code $sessionCode")
        return sessionCode
    }
    
    /**
     * Start a receiver session.
     * 
     * @param context Android context
     * @param sessionCode Code from sender
     * @param method Transfer method to use
     * @return true if session started
     */
    fun startReceiverSession(context: Context, sessionCode: String, method: TransferMethod): Boolean {
        if (_state.value != State.IDLE) {
            Timber.w("$TAG: Cannot start receiver session - already in state ${_state.value}")
            return false
        }
        
        // Validate session code format
        if (!isValidSessionCode(sessionCode)) {
            Timber.w("$TAG: Invalid session code format")
            return false
        }
        
        Timber.i("$TAG: Starting receiver session with code $sessionCode")
        
        currentSessionCode = sessionCode
        sessionStartTime = System.currentTimeMillis()
        
        _state.value = State.RECEIVER_CONNECTING
        _sessionInfo.value = SessionInfo(
            sessionCode = sessionCode,
            method = method,
            startTime = sessionStartTime,
            expiresAt = sessionStartTime + SESSION_TIMEOUT_MS
        )
        
        startTimeoutTimer()
        
        return true
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
        if (_state.value == State.SENDER_WAITING) {
            _state.value = State.SENDER_TRANSFERRING
        } else if (_state.value == State.RECEIVER_CONNECTING) {
            _state.value = State.RECEIVER_DOWNLOADING
        }
    }
    
    /**
     * Mark receiver as verifying APK.
     */
    fun setVerifying() {
        if (_state.value == State.RECEIVER_DOWNLOADING) {
            _state.value = State.RECEIVER_VERIFYING
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
     * Get the current session code (for verification).
     */
    fun getCurrentSessionCode(): String? = currentSessionCode
    
    /**
     * Verify a session code matches the current session.
     */
    fun verifySessionCode(code: String): Boolean {
        return currentSessionCode != null && currentSessionCode == code
    }
    
    /**
     * Clean up any stale state on app startup.
     * Call this from Application.onCreate() or Service.onCreate().
     */
    fun cleanupOnStartup() {
        Timber.d("$TAG: Cleaning up on startup")
        
        // Reset state
        _state.value = State.IDLE
        _sessionInfo.value = null
        currentSessionCode = null
        timeoutJob?.cancel()
        timeoutJob = null
        
        // Note: WiFi Direct group cleanup is handled by WifiDirectGroupCleanup
    }
    
    // ========================================================================
    // Private helpers
    // ========================================================================
    
    private fun generateSessionCode(): String {
        // Generate 6-digit numeric code
        val code = StringBuilder()
        for (i in 0 until SESSION_CODE_LENGTH) {
            code.append(secureRandom.nextInt(10))
        }
        return code.toString()
    }
    
    private fun isValidSessionCode(code: String): Boolean {
        return code.length == SESSION_CODE_LENGTH && code.all { it.isDigit() }
    }
    
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
            currentSessionCode = null
        }
    }
}
