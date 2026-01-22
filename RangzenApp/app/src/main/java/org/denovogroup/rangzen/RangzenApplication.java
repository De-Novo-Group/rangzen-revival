package org.denovogroup.rangzen;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.SharedPreferences;
import android.os.Build;

import org.denovogroup.rangzen.BuildConfig;
import org.denovogroup.rangzen.backend.AppConfig;
import org.denovogroup.rangzen.backend.telemetry.TelemetryClient;
import org.denovogroup.rangzen.backend.update.UpdateClient;
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

        // Initialize telemetry client
        initializeTelemetry();

        // Initialize OTA update client
        initializeUpdateClient();

        Timber.i("Murmur Application initialized");
    }

    /**
     * Initializes the telemetry client for QA mode.
     */
    private void initializeTelemetry() {
        String serverUrl = AppConfig.INSTANCE.telemetryServerUrl(this);
        if (serverUrl == null || serverUrl.isEmpty()) {
            Timber.w("Telemetry server URL not configured, telemetry disabled");
            return;
        }

        String apiToken = AppConfig.INSTANCE.telemetryApiToken(this);
        if (apiToken == null || apiToken.isEmpty()) {
            Timber.w("Telemetry API token not configured, telemetry disabled");
            return;
        }

        TelemetryClient client = TelemetryClient.Companion.init(this, serverUrl, apiToken);

        // Restore QA mode state from preferences
        SharedPreferences prefs = getSharedPreferences("rangzen_prefs", MODE_PRIVATE);
        boolean qaMode = prefs.getBoolean("qa_mode", false);
        client.setEnabled(qaMode);

        Timber.d("Telemetry initialized, QA mode: %s", qaMode);
    }

    /**
     * Initializes the OTA update client.
     * Only starts periodic checks if QA mode is enabled.
     */
    private void initializeUpdateClient() {
        if (!AppConfig.INSTANCE.otaEnabled(this)) {
            Timber.d("OTA updates disabled in config");
            return;
        }

        String serverUrl = AppConfig.INSTANCE.telemetryServerUrl(this);
        String apiToken = AppConfig.INSTANCE.telemetryApiToken(this);

        if (serverUrl == null || serverUrl.isEmpty() || apiToken == null || apiToken.isEmpty()) {
            Timber.w("OTA server URL or API token not configured");
            return;
        }

        UpdateClient updateClient = UpdateClient.Companion.init(this, serverUrl, apiToken);

        // Check if QA mode is enabled and start periodic checks
        SharedPreferences prefs = getSharedPreferences("rangzen_prefs", MODE_PRIVATE);
        boolean qaMode = prefs.getBoolean("qa_mode", false);
        if (qaMode) {
            updateClient.startPeriodicChecks();
            Timber.d("OTA update checks started");
        }
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
