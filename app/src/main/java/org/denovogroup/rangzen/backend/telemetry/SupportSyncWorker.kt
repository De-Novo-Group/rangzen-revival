/*
 * Copyright (c) 2026, De Novo Group
 * SupportSyncWorker - Background worker for polling support messages
 */
package org.denovogroup.rangzen.backend.telemetry

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.denovogroup.rangzen.backend.NotificationHelper
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that periodically polls the server for support messages.
 *
 * Schedule:
 * - Runs every 15 minutes (minimum WorkManager interval)
 * - Only runs when network is available
 * - Stores new messages in SupportStore
 * - Shows notification for new messages
 */
class SupportSyncWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val WORK_NAME = "support_sync"
        private const val SYNC_INTERVAL_MINUTES = 15L

        /**
         * Schedule the periodic sync worker.
         * Call this when QA mode is enabled.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<SupportSyncWorker>(
                SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // Don't replace existing work
                workRequest
            )

            Timber.i("SupportSyncWorker scheduled (every $SYNC_INTERVAL_MINUTES minutes)")
        }

        /**
         * Cancel the periodic sync worker.
         * Call this when QA mode is disabled.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Timber.i("SupportSyncWorker cancelled")
        }

        /**
         * Trigger an immediate sync (on top of periodic).
         * Call this when app comes to foreground.
         */
        fun syncNow(context: Context) {
            val telemetry = TelemetryClient.getInstance() ?: return
            val supportStore = SupportStore.getInstance(context)

            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                try {
                    doSync(context, telemetry, supportStore)
                } catch (e: Exception) {
                    Timber.e(e, "Immediate sync failed")
                }
            }
        }

        private suspend fun doSync(
            context: Context,
            telemetry: TelemetryClient,
            supportStore: SupportStore
        ) {
            val response = telemetry.sync() ?: return

            // Store new messages
            val messages = response.messages ?: emptyList()
            if (messages.isEmpty()) return

            var newCount = 0
            var firstMessage: String? = null

            messages.forEach { dm ->
                val supportMessage = SupportMessage(
                    id = dm.id,
                    message = dm.message,
                    reportId = dm.reportId,
                    readAt = null,
                    createdAt = parseTimestamp(dm.createdAt)
                )

                if (supportStore.addMessage(supportMessage)) {
                    newCount++
                    if (firstMessage == null) {
                        firstMessage = dm.message
                    }
                }
            }

            // Show notification if there are new messages
            if (newCount > 0) {
                Timber.i("Sync found $newCount new support messages")
                NotificationHelper.showSupportMessageNotification(
                    context,
                    messageCount = newCount,
                    messagePreview = firstMessage
                )
            }
        }

        private fun parseTimestamp(isoString: String): Long {
            return try {
                java.time.Instant.parse(isoString).toEpochMilli()
            } catch (e: Exception) {
                System.currentTimeMillis()
            }
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val telemetry = TelemetryClient.getInstance()
            if (telemetry == null) {
                Timber.w("SupportSyncWorker: Telemetry not initialized")
                return@withContext Result.retry()
            }

            if (!telemetry.isEnabled()) {
                Timber.d("SupportSyncWorker: Telemetry disabled, skipping sync")
                return@withContext Result.success()
            }

            val supportStore = SupportStore.getInstance(applicationContext)
            doSync(applicationContext, telemetry, supportStore)

            Timber.d("SupportSyncWorker: Sync completed")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "SupportSyncWorker: Sync failed")
            Result.retry()
        }
    }
}
