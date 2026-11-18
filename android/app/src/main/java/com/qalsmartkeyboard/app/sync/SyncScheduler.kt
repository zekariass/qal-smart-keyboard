package com.qalsmartkeyboard.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules [SyncWorker] (A2.4). Triggers: app start and sign-in ([requestSoon]), a settings change
 * ([requestSoon] after the debounce in the activity, and a short initial delay that merges rapid
 * requests: REPLACE), and a periodic run every [PERIOD_HOURS] hours on an unmetered network by
 * default. The payloads are small (a few hundred bytes to a few kilobytes), so the event-driven run
 * accepts any connected network; only the background periodic run waits for Wi-Fi.
 */
object SyncScheduler {
    const val PERIODIC_NAME = "qal-sync"
    const val SOON_NAME = "qal-sync-soon"
    const val PERIOD_HOURS = 12L
    const val DEBOUNCE_SECONDS = 5L

    private fun constraints(network: NetworkType) = Constraints.Builder().setRequiredNetworkType(network).build()

    fun schedulePeriodic(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints(NetworkType.UNMETERED))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build(),
        )
    }

    /** One run soon; further requests within [delaySeconds] replace it (debounce). */
    fun requestSoon(context: Context, delaySeconds: Long = DEBOUNCE_SECONDS) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            SOON_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setConstraints(constraints(NetworkType.CONNECTED))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }
}
