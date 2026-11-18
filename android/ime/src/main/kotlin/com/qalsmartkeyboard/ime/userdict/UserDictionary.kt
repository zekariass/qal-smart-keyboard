package com.qalsmartkeyboard.ime.userdict

import android.content.Context
import android.util.Log
import com.qalsmartkeyboard.data.secure.EncryptedFileStore
import com.qalsmartkeyboard.data.secure.KeystoreKeys
import com.qalsmartkeyboard.data.secure.StoreRead
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import uniffi.qal_keyboard_core.KeyboardEngine
import uniffi.qal_keyboard_core.Lexicon
import uniffi.qal_keyboard_core.Ranker

/**
 * The user's personal dictionary: words added or learned per language, plus learned word pairs
 * (A1.8, SD 7.4). Local only: nothing here touches the network; sync is a later, consented
 * feature (`DICTIONARY_SYNC`).
 *
 * Design:
 * - The canonical copy is a user-overlay-only core `Lexicon` + `Ranker` per language, so word
 *   validation, NFC folding, caps and the byte formats are the core's, and the settings screen
 *   can list and edit words with no engine running.
 * - When the engine is [attach]ed (after the packs load) every change is also applied to it, so
 *   a learned word is suggested at once; the overlay is re-imported into the engine on attach.
 * - Persistence: one encrypted file per language ([EncryptedFileStore], AES-256-GCM, Keystore key).
 *   Changes are saved after [debounceMs] in the background, on [saveSoon] (field finished) and
 *   on [flush] (service destroyed). A missing file starts empty; an unreadable or undecryptable
 *   one also starts empty and only its reason code is logged (never any word).
 * - Until [load] has run (or if there is no [store]: Keystore unavailable) changes are kept in
 *   memory only; nothing is ever written unencrypted.
 *
 * Thread-safe. One instance per process ([get]): the keyboard service and the settings screen share it.
 */
class UserDictionary(
    private val store: EncryptedFileStore?,
    private val scheduler: ScheduledExecutorService,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val log: (String) -> Unit = {},
) : WordLearningSink {
    private class Overlay(val lexicon: Lexicon = Lexicon.newEmpty()) {
        val ranker: Ranker = Ranker(lexicon)
    }

    private val lock = Any()
    private val overlays = HashMap<String, Overlay>()
    private val dirty = HashSet<String>()
    private var loaded = false
    private var engine: KeyboardEngine? = null
    private var pending: ScheduledFuture<*>? = null
    private val saveLock = Any()

    /** True once [load] has finished (the overlay then accepts changes). */
    val isLoaded: Boolean get() = synchronized(lock) { loaded }

    /** Reads the stored overlays. Call off the main thread; idempotent. */
    fun load() {
        synchronized(lock) {
            if (loaded) return
            for (lang in LANGUAGES) {
                val overlay = Overlay()
                overlays[lang] = overlay
                val s = store ?: continue
                when (val r = s.read(fileName(lang))) {
                    StoreRead.Missing -> {}
                    is StoreRead.Corrupt -> {
                        log("user dictionary $lang unusable: ${r.reason}; starting empty")
                        dirty += lang // overwrite the bad file with the (empty) state on the next save
                    }
                    is StoreRead.Ok -> if (!decodeInto(overlay, r.bytes)) {
                        log("user dictionary $lang unusable: INVALID_DATA; starting empty")
                        overlays[lang] = Overlay()
                        dirty += lang
                    }
                }
            }
            loaded = true
        }
    }

    /** Hands the overlays to the engine and keeps it updated. Call after [load] and the packs. */
    fun attach(engine: KeyboardEngine) {
        synchronized(lock) {
            this.engine = engine
            for (lang in LANGUAGES) {
                val o = overlays[lang] ?: continue
                try {
                    engine.importUserData(lang, o.lexicon.exportUserDictionary(), o.ranker.exportUserBigrams())
                } catch (e: Exception) {
                    log("user dictionary $lang not applied: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    /** The engine is going away (service destroyed). */
    fun detach(engine: KeyboardEngine) {
        synchronized(lock) { if (this.engine === engine) this.engine = null }
    }

    // ------------------------------------------------------------------ editing

    /** Learning (a committed word): counts one use and, with [previous], the word pair. */
    override fun learn(language: String, word: String, previous: String?) {
        mutate(language) { o, e ->
            if (!o.lexicon.learnWord(word)) return@mutate false
            if (previous != null) o.ranker.learnBigram(previous, word)
            live(e) { it.learnUserWord(language, word, previous) }
            true
        }
    }

    /** Explicit add. False if the word is not acceptable (empty, too long, whitespace) or the overlay is full. */
    override fun add(language: String, word: String): Boolean = mutate(language) { o, e ->
        val ok = o.lexicon.addUserWord(word)
        if (ok) live(e) { it.addUserWord(language, word) }
        ok
    }

    /** Deletes a word the user added or learned. Words of the shipped dictionary stay. */
    fun forget(language: String, word: String): Boolean = mutate(language) { o, e ->
        val ok = o.lexicon.forgetUserWord(word)
        if (ok) live(e) { it.forgetUserWord(language, word) }
        ok
    }

    /** "Clear all" for one language (words and learned pairs). */
    fun clear(language: String) {
        mutate(language) { o, e ->
            o.lexicon.clearUserDictionary()
            o.ranker.clearUserBigrams()
            live(e) { it.clearUserData(language) }
            true
        }
    }

    fun clearAll() = LANGUAGES.forEach { clear(it) }

    /** The words of [language], alphabetical (empty before [load]). */
    fun words(language: String): List<String> = synchronized(lock) { overlays[language]?.lexicon?.userWords() ?: emptyList() }

    /** Applies a change to the live engine; an engine that was closed meanwhile is dropped, the overlay stays canonical. */
    private inline fun live(e: KeyboardEngine?, change: (KeyboardEngine) -> Unit) {
        if (e == null) return
        try {
            change(e)
        } catch (_: Exception) {
            if (engine === e) engine = null
        }
    }

    private inline fun mutate(language: String, change: (Overlay, KeyboardEngine?) -> Boolean): Boolean {
        val changed = synchronized(lock) {
            if (!loaded) return false
            val o = overlays[language] ?: return false
            change(o, engine).also { if (it) dirty += language }
        }
        if (changed) scheduleSave(debounceMs)
        return changed
    }

    // ------------------------------------------------------------------ persistence

    /** Saves on the background thread now (field finished, app backgrounded). */
    fun saveSoon() = scheduleSave(0)

    /** Saves on the calling thread and waits (service destroyed). */
    fun flush() {
        synchronized(lock) { pending?.cancel(false); pending = null }
        persist()
    }

    private fun scheduleSave(delayMs: Long) {
        synchronized(lock) {
            if (dirty.isEmpty() || store == null) return
            pending?.cancel(false)
            pending = try {
                scheduler.schedule({ persist() }, delayMs, TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                null
            }
        }
    }

    private fun persist() {
        val s = store ?: return
        synchronized(saveLock) {
            val snapshot = synchronized(lock) {
                val langs = dirty.toList()
                dirty.clear()
                langs.map { it to encode(overlays.getValue(it)) }
            }
            for ((lang, bytes) in snapshot) {
                try {
                    s.write(fileName(lang), bytes)
                } catch (e: Exception) {
                    // IOException or a cipher/Keystore failure: keep the change and try again later.
                    log("user dictionary $lang not saved: ${e.javaClass.simpleName}")
                    synchronized(lock) { dirty += lang }
                }
            }
        }
    }

    // ------------------------------------------------------------------ file content

    /** `"QUF1" | int dictLength | dictionary | int bigramLength | bigrams` (core "QUD1" and "QBG1" payloads). */
    private fun encode(o: Overlay): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.write(MAGIC)
            val dict = o.lexicon.exportUserDictionary()
            d.writeInt(dict.size)
            d.write(dict)
            val big = o.ranker.exportUserBigrams()
            d.writeInt(big.size)
            d.write(big)
        }
        return out.toByteArray()
    }

    private fun decodeInto(o: Overlay, bytes: ByteArray): Boolean = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { d ->
            val magic = ByteArray(MAGIC.size).also { d.readFully(it) }
            if (!magic.contentEquals(MAGIC)) return false
            val dict = readBlock(d, bytes.size) ?: return false
            val big = readBlock(d, bytes.size) ?: return false
            if (d.read() != -1) return false
            o.lexicon.importUserDictionary(dict)
            o.ranker.importUserBigrams(big)
            true
        }
    } catch (_: IOException) {
        false
    } catch (_: Exception) {
        false // core rejected the payload (malformed)
    }

    private fun readBlock(d: DataInputStream, max: Int): ByteArray? {
        val n = d.readInt()
        if (n < 0 || n > max) return null
        return ByteArray(n).also { d.readFully(it) }
    }

    companion object {
        val LANGUAGES = listOf("am", "ti", "om", "en")
        const val DEFAULT_DEBOUNCE_MS = 5_000L
        private val MAGIC = byteArrayOf('Q'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte(), '1'.code.toByte())
        private const val KEY_ALIAS = "qal.userdict.v1"
        private const val TAG = "QalUserDict"

        fun fileName(language: String) = "userdict-$language"

        @Volatile
        private var instance: UserDictionary? = null

        /** The process-wide instance, stored under `noBackupFilesDir` (a backup could not restore the Keystore key anyway). */
        fun get(context: Context): UserDictionary = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private fun create(context: Context): UserDictionary {
            val store = try {
                EncryptedFileStore(File(context.noBackupFilesDir, "userdict"), KeystoreKeys.aesGcm(KEY_ALIAS))
            } catch (e: Exception) {
                Log.e(TAG, "no encrypted storage: ${e.javaClass.simpleName}")
                null
            }
            val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "qal-userdict").apply { isDaemon = true } }
            return UserDictionary(store, scheduler) { Log.w(TAG, it) }
        }
    }
}
