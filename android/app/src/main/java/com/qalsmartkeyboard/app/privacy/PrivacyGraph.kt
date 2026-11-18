package com.qalsmartkeyboard.app.privacy

import android.content.Context
import com.qalsmartkeyboard.api.ApiError
import com.qalsmartkeyboard.app.auth.AuthGraph
import com.qalsmartkeyboard.app.sync.SyncGraph
import com.qalsmartkeyboard.sync.SyncHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Process-wide wiring of the privacy screens (A2.6): the API calls over the shared client, the two
 * device-local values (the history flag the keyboard toolbar reads, the id of the last export) and
 * the local wipe. The values are plain `SharedPreferences` (a boolean and an id: no secret, no
 * personal text); they are cleared on sign-out and on a deletion request.
 */
class PrivacyGraph private constructor(private val context: Context) {
    private val auth = AuthGraph.get(context)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val api = PrivacyApi(SyncHttp(auth.clients), auth.clients)

    val historyFlag: HistoryFlag = object : HistoryFlag {
        override var enabled: Boolean
            get() = prefs.getBoolean(KEY_HISTORY, false)
            set(value) = prefs.edit().putBoolean(KEY_HISTORY, value).apply()
    }

    val exportMemory: ExportMemory = object : ExportMemory {
        override var lastId: String?
            get() = prefs.getString(KEY_EXPORT, null)
            set(value) = prefs.edit().apply { if (value == null) remove(KEY_EXPORT) else putString(KEY_EXPORT, value) }.apply()
    }

    /** The local part of a consent withdrawal: stop the keyboard asking for history to be saved. */
    val effects: ConsentEffects = object : ConsentEffects {
        override suspend fun withdrawn(code: String) {
            if (code != ConsentCatalog.SAVE_AI_HISTORY) return
            historyFlag.enabled = false
            // Keep the server preference consistent (best effort: without the consent nothing is saved anyway).
            try {
                val prefs = api.historyPrefs()
                if (prefs.enabled) api.saveHistoryPrefs(false, prefs.retentionDays)
            } catch (_: ApiError) {
            }
        }
    }

    val wipe: LocalWipe = object : LocalWipe {
        override suspend fun afterDeletionRequested(signOut: Boolean) {
            historyFlag.enabled = false
            exportMemory.lastId = null
            if (signOut) auth.repository.signOut() // best-effort /auth/logout, then tokens and account kind are deleted
            withContext(Dispatchers.IO) { SyncGraph.get(context).engine.accountChanged() }
        }
    }

    /** Sign-out or an ended session: nothing account-specific stays in these two values. */
    fun onSignedOut() {
        historyFlag.enabled = false
        exportMemory.lastId = null
    }

    companion object {
        private const val PREFS = "qal_privacy"
        private const val KEY_HISTORY = "history_enabled"
        private const val KEY_EXPORT = "last_export_id"

        @Volatile private var instance: PrivacyGraph? = null

        fun get(context: Context): PrivacyGraph =
            instance ?: synchronized(this) { instance ?: PrivacyGraph(context.applicationContext).also { instance = it } }
    }
}
