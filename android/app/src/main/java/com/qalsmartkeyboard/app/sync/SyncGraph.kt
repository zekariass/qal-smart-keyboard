package com.qalsmartkeyboard.app.sync

import android.content.Context
import com.qalsmartkeyboard.app.auth.AuthGraph
import com.qalsmartkeyboard.data.SettingsRepository
import com.qalsmartkeyboard.data.secure.EncryptedFileStore
import com.qalsmartkeyboard.data.secure.KeystoreKeys
import com.qalsmartkeyboard.ime.userdict.UserDictionary
import com.qalsmartkeyboard.sync.DestinationStore
import com.qalsmartkeyboard.sync.LocalDictionary
import com.qalsmartkeyboard.sync.SyncEngine
import com.qalsmartkeyboard.sync.SyncHttp
import com.qalsmartkeyboard.sync.SyncStateStore
import java.io.File

/**
 * Process-wide wiring of the sync engine (A2.4). Its state (revisions, cursors, the app-rule key,
 * the dictionary snapshot) and the destination profiles live in `noBackupFilesDir/sync`, encrypted
 * with their own Keystore key alias (`qal.sync.v1`, non-exportable), separate from the tokens and
 * the personal dictionary key.
 */
class SyncGraph private constructor(context: Context) {
    private val files = EncryptedFileStore(File(context.noBackupFilesDir, "sync"), KeystoreKeys.aesGcm(KEY_ALIAS))

    /** The local personal dictionary as sync sees it (same process-wide instance as the keyboard and the settings list). */
    val dictionary: UserDictionary = UserDictionary.get(context)

    val engine = SyncEngine(
        settings = SettingsRepository.get(context),
        http = SyncHttp(AuthGraph.get(context).clients),
        state = SyncStateStore(files),
        destinations = DestinationStore(files),
        dictionary = UserDictionaryPort(dictionary),
        log = { android.util.Log.i(TAG, it) },
    )

    companion object {
        const val KEY_ALIAS = "qal.sync.v1"
        private const val TAG = "QalSync"

        @Volatile private var instance: SyncGraph? = null

        fun get(context: Context): SyncGraph =
            instance ?: synchronized(this) { instance ?: SyncGraph(context.applicationContext).also { instance = it } }
    }
}

/** Adapts the keyboard's [UserDictionary] to the `:sync` port. Remote words are explicit adds (ADR 0007 D18: learned at once). */
class UserDictionaryPort(private val dictionary: UserDictionary) : LocalDictionary {
    override fun words(language: String): List<String> = dictionary.words(language)

    override fun add(language: String, word: String): Boolean = dictionary.add(language, word)

    override fun forget(language: String, word: String): Boolean = dictionary.forget(language, word)
}
