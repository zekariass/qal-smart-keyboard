package com.qalsmartkeyboard.app.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.qalsmartkeyboard.app.auth.AccountStatus
import com.qalsmartkeyboard.app.auth.AuthGraph
import com.qalsmartkeyboard.sync.RunStatus
import com.qalsmartkeyboard.sync.SyncRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One sync run (A2.4): see `SyncEngine`. Retries (WorkManager backoff, at most [MAX_ATTEMPTS]) only for
 * transient failures; the periodic request tries again later anyway. Logs counts and codes only.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val graph = SyncGraph.get(ctx)
        // The personal dictionary must be loaded (off the main thread) before sync reads or edits it.
        graph.dictionary.load()
        val guest = AuthGraph.get(ctx).repository.currentStatus() == AccountStatus.Guest
        val report = graph.engine.runOnce(SyncRequest(isGuest = guest))
        Log.i(TAG, "sync ${report.status}")
        if (report.status == RunStatus.DONE && report.retryable && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    private companion object {
        const val TAG = "QalSync"
        const val MAX_ATTEMPTS = 3
    }
}
