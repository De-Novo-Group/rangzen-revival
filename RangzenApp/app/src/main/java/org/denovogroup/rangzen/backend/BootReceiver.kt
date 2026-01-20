/*
 * Copyright (c) 2026, De Novo Group
 * Receives BOOT_COMPLETED broadcast to start Rangzen service on device boot
 */
package org.denovogroup.rangzen.backend

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import timber.log.Timber

/**
 * Broadcast receiver that starts the Rangzen service when the device boots.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            
            Timber.i("Boot completed, checking if Rangzen should start")

            // Check if user has enabled auto-start
            val prefs = context.getSharedPreferences("rangzen_prefs", Context.MODE_PRIVATE)
            val isEnabled = prefs.getBoolean("service_enabled", false)

            if (isEnabled) {
                startRangzenService(context)
            }
        }
    }

    private fun startRangzenService(context: Context) {
        val serviceIntent = Intent(context, RangzenService::class.java).apply {
            action = RangzenService.ACTION_START
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Timber.i("Rangzen service started on boot")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start Rangzen service on boot")
        }
    }
}
