package com.qalsmartkeyboard.app.packs

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
 * Schedules [PackUpdateWorker] (A2.3; SD 7.6, 12: Wi-Fi only by default). Periodic every
 * [PERIOD_HOURS] hours plus one check at app start. Constraints: unmetered network (or any network
 * when the user allowed mobile data), battery not low, storage not low.
 */
object PackUpdateScheduler {
    const val PERIODIC_NAME = "qal-pack-updates"
    const val NOW_NAME = "qal-pack-updates-now"
    const val PERIOD_HOURS = 12L

    /** Unmetered unless the user opted in to mobile data (SD: downloads are Wi-Fi-only by default). */
    fun requiredNetwork(allowMobileData: Boolean): NetworkType = if (allowMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED

    private fun constraints(allowMobileData: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(requiredNetwork(allowMobileData))
        .setRequiresBatteryNotLow(true)
        .setRequiresStorageNotLow(true)
        .build()

    /**
     * (Re)schedules the periodic check with the current constraints and queues a check now.
     * [replaceNow]: replace a queued check (the network setting changed); false keeps one that is already queued.
     */
    fun schedule(context: Context, allowMobileData: Boolean, replaceNow: Boolean) {
        val wm = WorkManager.getInstance(context.applicationContext)
        val c = constraints(allowMobileData)
        wm.enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<PackUpdateWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(c)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build(),
        )
        wm.enqueueUniqueWork(
            NOW_NAME,
            if (replaceNow) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PackUpdateWorker>()
                .setConstraints(c)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build(),
        )
    }
}
