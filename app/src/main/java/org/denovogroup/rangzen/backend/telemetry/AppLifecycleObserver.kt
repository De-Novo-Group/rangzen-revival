/*
 * Copyright (c) 2026, De Novo Group
 * All rights reserved.
 *
 * Observes app lifecycle for telemetry.
 */
package org.denovogroup.rangzen.backend.telemetry

import android.app.Activity
import android.app.Application
import android.os.Bundle
import timber.log.Timber

/**
 * Observes app lifecycle events and reports them to telemetry.
 *
 * Uses Application.ActivityLifecycleCallbacks to track:
 * - app_start: When the app process starts
 * - app_foreground: When an activity comes to foreground
 * - app_background: When all activities go to background
 */
object AppLifecycleObserver : Application.ActivityLifecycleCallbacks {

    private var sessionStartTime: Long = 0
    private var foregroundStartTime: Long = 0
    private var startedActivities = 0
    private var isInitialized = false
    private var isInForeground = false

    /**
     * Initialize the observer. Call once from Application.onCreate().
     */
    fun initialize(application: Application) {
        if (isInitialized) return
        isInitialized = true

        application.registerActivityLifecycleCallbacks(this)
        sessionStartTime = System.currentTimeMillis()

        // Track app_start
        TelemetryClient.getInstance()?.trackAppLifecycle(TelemetryEvent.TYPE_APP_START)
        Timber.i("App lifecycle observer initialized")
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities++
        if (startedActivities == 1 && !isInForeground) {
            // App moved to foreground
            isInForeground = true
            foregroundStartTime = System.currentTimeMillis()

            TelemetryClient.getInstance()?.trackAppLifecycle(TelemetryEvent.TYPE_APP_FOREGROUND)
            Timber.d("App moved to foreground")
        }
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities--
        if (startedActivities == 0 && isInForeground) {
            // App moved to background
            isInForeground = false

            val foregroundDurationMs = if (foregroundStartTime > 0) {
                System.currentTimeMillis() - foregroundStartTime
            } else {
                null
            }

            TelemetryClient.getInstance()?.trackAppLifecycle(
                TelemetryEvent.TYPE_APP_BACKGROUND,
                foregroundDurationMs
            )
            Timber.d("App moved to background (foreground duration: ${foregroundDurationMs}ms)")
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
