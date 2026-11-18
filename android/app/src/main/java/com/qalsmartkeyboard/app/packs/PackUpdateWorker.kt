package com.qalsmartkeyboard.app.packs

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.qalsmartkeyboard.app.BuildConfig
import com.qalsmartkeyboard.app.auth.AuthGraph
import com.qalsmartkeyboard.data.SettingsRepository
import com.qalsmartkeyboard.ime.packs.PackStorage
import com.qalsmartkeyboard.packsync.ApiPackDiscovery
import com.qalsmartkeyboard.packsync.HttpArtifactDownloader
import com.qalsmartkeyboard.packsync.PackUpdater
import com.qalsmartkeyboard.packsync.RunStatus
import com.qalsmartkeyboard.packsync.UpdateRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One language-pack check (A2.3): see [PackUpdater]. Runs for the languages enabled in the settings,
 * on the configured channel, for this app version. Only transient failures (network, 5xx) are
 * retried, with WorkManager's backoff and a small cap; the periodic request tries again later anyway.
 * Never logs more than file names, resource ids and reason codes.
 */
class PackUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val trust = PackStorage.trustedKeys(ctx) { Log.w(TAG, it) } ?: return@withContext Result.success()
        val settings = SettingsRepository.get(ctx).current()
        val updater = PackUpdater(
            root = PackStorage.root(ctx),
            installedDir = PackStorage.installedDir(ctx),
            trust = trust,
            discovery = ApiPackDiscovery(AuthGraph.get(ctx).clients),
            downloader = HttpArtifactDownloader(),
            log = { Log.i(TAG, it) },
        )
        val report = updater.runOnce(UpdateRequest(settings.enabledLanguages, settings.packChannel.name, BuildConfig.VERSION_NAME))
        Log.i(TAG, "pack check ${report.status}: ${report.installed.size} installed, ${report.failures.size} not installed")
        if (report.retryable && report.status != RunStatus.BUSY && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    private companion object {
        const val TAG = "QalPackSync"
        const val MAX_ATTEMPTS = 3
    }
}
