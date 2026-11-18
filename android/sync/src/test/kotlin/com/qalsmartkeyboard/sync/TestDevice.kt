package com.qalsmartkeyboard.sync

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import com.qalsmartkeyboard.api.ApiClients
import com.qalsmartkeyboard.api.ClientInfo
import com.qalsmartkeyboard.api.ClientSurface
import com.qalsmartkeyboard.api.InMemoryTokenStore
import com.qalsmartkeyboard.api.SessionManager
import com.qalsmartkeyboard.api.TokenPair
import com.qalsmartkeyboard.api.TransportConfig
import com.qalsmartkeyboard.data.SettingsRepository
import com.qalsmartkeyboard.data.secure.AesGcmCipher
import com.qalsmartkeyboard.data.secure.EncryptedFileStore
import java.io.File
import java.nio.file.Files
import java.text.Normalizer
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okio.FileSystem
import okio.Path.Companion.toPath

/** In-memory [LocalDictionary] that records how words arrived (explicit adds = D18 "learned"). */
class FakeDictionary : LocalDictionary {
    private val words = HashMap<String, MutableSet<String>>()
    val addCalls = ArrayList<String>()
    val forgetCalls = ArrayList<String>()

    @Synchronized fun typeLearned(language: String, word: String) {
        words.getOrPut(language) { HashSet() } += Normalizer.normalize(word, Normalizer.Form.NFC)
    }

    @Synchronized fun userDelete(language: String, word: String) {
        words[language]?.remove(word)
    }

    @Synchronized override fun words(language: String): List<String> = words[language].orEmpty().toList()

    @Synchronized override fun add(language: String, word: String): Boolean {
        addCalls += "$language|$word"
        words.getOrPut(language) { HashSet() }.add(word)
        return true
    }

    @Synchronized override fun forget(language: String, word: String): Boolean {
        forgetCalls += "$language|$word"
        return words[language]?.remove(word) ?: false
    }

    @Synchronized fun all(language: String): Set<String> = words[language].orEmpty().toSet()
}

/**
 * One phone: its own settings DataStore, encrypted sync state, destination store, dictionary and a
 * real [SyncEngine] over the real API client, talking to the shared [FakeBackend].
 */
class TestDevice(backend: FakeBackend, signedIn: Boolean = true, val isGuest: Boolean = false) {
    val dir: File = Files.createTempDirectory("qal-sync").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val files = EncryptedFileStore(File(dir, "enc"), AesGcmCipher { key })

    val settings = SettingsRepository(
        DataStoreFactory.create<Preferences>(
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { File(dir, "settings.preferences_pb").absolutePath.toPath() },
            scope = scope,
        ),
    )
    val dictionary = FakeDictionary()
    val state = SyncStateStore(files)
    val dest = DestinationStore(files)
    val logs = ArrayList<String>()
    val session = SessionManager(InMemoryTokenStore(if (signedIn) TokenPair("access-token", "refresh-token") else null))
    private val clients = ApiClients(TransportConfig(backend.baseUrl, ClientInfo("1.0", ClientSurface.COMPANION)), session)
    val http = SyncHttp(clients)
    val engine = SyncEngine(settings, http, state, dest, dictionary, log = { logs += it })

    suspend fun sync(): SyncReport = engine.runOnce(SyncRequest(isGuest))

    fun close() {
        scope.cancel()
        dir.deleteRecursively()
    }
}
