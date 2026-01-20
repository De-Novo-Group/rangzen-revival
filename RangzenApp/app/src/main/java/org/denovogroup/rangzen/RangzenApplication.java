package org.denovogroup.rangzen;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

import org.denovogroup.rangzen.BuildConfig;
import timber.log.Timber;

/**
 * Application class for Rangzen.
 * Initializes logging and notification channels.
 */
public class RangzenApplication extends Application {

    /** Notification channel ID for the foreground service */
    public static final String CHANNEL_ID_SERVICE = "rangzen_service";
    
    /** Notification channel ID for new messages */
    public static final String CHANNEL_ID_MESSAGES = "rangzen_messages";

    @Override
    public void onCreate() {
        super.onCreate();
        
        // Initialize Timber logging
        if (BuildConfig.DEBUG) {
            Timber.plant(new Timber.DebugTree());
        }
        
        // Create notification channels (required for Android 8+)
        createNotificationChannels();
        
        Timber.i("Rangzen Application initialized");
    }

    /**
     * Creates the notification channels required for Android 8+ (API 26+).
     */
    private void createNotificationChannels() {
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager == null) return;

        // Service notification channel (low importance - silent)
        NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_ID_SERVICE,
                "Rangzen Service",
                NotificationManager.IMPORTANCE_LOW
        );
        serviceChannel.setDescription("Shows when Rangzen is running in background");
        serviceChannel.setShowBadge(false);
        notificationManager.createNotificationChannel(serviceChannel);

        // Message notification channel (default importance - sound/vibrate)
        NotificationChannel messageChannel = new NotificationChannel(
                CHANNEL_ID_MESSAGES,
                "New Messages",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        messageChannel.setDescription("Notifications for new messages received");
        notificationManager.createNotificationChannel(messageChannel);

        Timber.d("Notification channels created");
    }
}
