/*
 * Copyright (c) 2026, De Novo Group
 * NotificationHelper - handles message notifications following Android best practices
 */
package org.denovogroup.rangzen.backend

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.denovogroup.rangzen.R
import org.denovogroup.rangzen.RangzenApplication
import org.denovogroup.rangzen.ui.MainActivity
import timber.log.Timber

/**
 * Helper class for showing message notifications.
 * 
 * Follows Android notification best practices:
 * - Uses proper notification channel (CHANNEL_ID_MESSAGES)
 * - Respects notification permissions (Android 13+)
 * - Groups multiple messages into a single notification
 * - Clears notifications when user opens the app
 * - Supports heads-up display for new messages
 */
object NotificationHelper {
    
    // Notification ID for message notifications (use single ID to stack/update)
    // IMPORTANT: Must be different from RangzenService.NOTIFICATION_ID (1001)
    private const val NOTIFICATION_ID_MESSAGES = 2001
    
    // Track pending message count for grouped notification
    private var pendingMessageCount = 0
    
    // Last notification time to avoid spam
    private var lastNotificationTime = 0L
    private const val MIN_NOTIFICATION_INTERVAL_MS = 2000L  // 2 seconds minimum between notifications
    
    // Track if UI is visible (set by FeedFragment)
    // We use this instead of process importance because a foreground service
    // will always report IMPORTANCE_FOREGROUND even when UI is not visible.
    @Volatile
    var isUiVisible = false
    
    /**
     * Show notification for new message(s) received.
     * 
     * @param context Application context
     * @param messageCount Number of new messages (1 for single, >1 for batch)
     * @param messagePreview Preview text of the message (for single message)
     * @param senderPseudonym Pseudonym of sender (for single message)
     */
    fun showNewMessageNotification(
        context: Context,
        messageCount: Int = 1,
        messagePreview: String? = null,
        senderPseudonym: String? = null
    ) {
        // Check notification permission (required on Android 13+)
        if (!hasNotificationPermission(context)) {
            Timber.d("Notification permission not granted, skipping notification")
            return
        }
        
        // Rate limit notifications to avoid spam during bulk exchanges
        val now = System.currentTimeMillis()
        if (now - lastNotificationTime < MIN_NOTIFICATION_INTERVAL_MS) {
            // Accumulate count for next notification
            pendingMessageCount += messageCount
            Timber.d("Rate limiting notification, pending count: $pendingMessageCount")
            return
        }
        lastNotificationTime = now
        
        // Include any pending messages from rate limiting
        val totalNewMessages = pendingMessageCount + messageCount
        pendingMessageCount = 0
        
        // Don't notify if UI is visible (user is looking at the feed)
        // We track this manually because foreground service makes process
        // appear as IMPORTANCE_FOREGROUND even when UI is not shown.
        if (isUiVisible) {
            Timber.i("NotificationHelper: UI is visible, skipping notification for $totalNewMessages messages")
            return
        }
        
        Timber.i("NotificationHelper: Showing notification for $totalNewMessages new message(s)")
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) 
            as NotificationManager
        
        // Create intent to open app when notification is tapped
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // Navigate to feed tab
            putExtra("navigate_to", "feed")
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Build notification content based on message count
        val (title, content) = if (totalNewMessages == 1 && messagePreview != null) {
            // Single message - show preview
            val sender = senderPseudonym ?: context.getString(R.string.notification_unknown_sender)
            Pair(
                context.getString(R.string.notification_new_message),
                "$sender: ${messagePreview.take(100)}"
            )
        } else {
            // Multiple messages - show count
            Pair(
                context.getString(R.string.notification_new_messages_title),
                context.getString(R.string.notification_new_messages_body, totalNewMessages)
            )
        }
        
        // Build the notification with HIGH priority for heads-up display
        val notification = NotificationCompat.Builder(context, RangzenApplication.CHANNEL_ID_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)  // Dismiss when tapped
            .setPriority(NotificationCompat.PRIORITY_HIGH)  // HIGH = heads-up pop-up
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)  // Show content on lock screen
            .setDefaults(NotificationCompat.DEFAULT_ALL)  // Sound + vibration
            // Keep notification until user dismisses it
            .setOngoing(false)
            // Show timestamp
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .build()
        
        notificationManager.notify(NOTIFICATION_ID_MESSAGES, notification)
        Timber.i("Showed notification for $totalNewMessages new message(s)")
    }
    
    /**
     * Clear all message notifications.
     * Call this when user opens the feed to clear pending notifications.
     */
    fun clearMessageNotifications(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) 
            as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID_MESSAGES)
        pendingMessageCount = 0
        Timber.d("Cleared message notifications")
    }
    
    /**
     * Check if notification permission is granted.
     * Required for Android 13 (API 33) and above.
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Permission not required before Android 13
            true
        }
    }
    
}
