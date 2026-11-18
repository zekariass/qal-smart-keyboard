package com.qalsmartkeyboard.sync

import com.qalsmartkeyboard.data.secure.EncryptedFileStore
import com.qalsmartkeyboard.data.secure.StoreRead
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Everything sync remembers between runs, as one JSON object in an [EncryptedFileStore] blob
 * (AES-256-GCM, Keystore key in production): per-document last known server revision and server
 * document (the merge base), the per-account app-rule key, and the dictionary cursor, snapshot and
 * change ids. It holds words and key material, so it is never written in clear text and never logged.
 *
 * A missing or unreadable file reads as empty: the next run behaves like a first sync (every merge
 * is idempotent, so that is safe, only slower). A failed write is ignored for the same reason.
 * [reset] on sign-out or account switch, so one account's cursors and revisions never leak into
 * another's.
 */
class SyncStateStore(private val files: EncryptedFileStore, private val name: String = NAME) {
    private val lock = Any()
    private var cache: JsonObject? = null

    fun read(): JsonObject = synchronized(lock) { cache ?: load().also { cache = it } }

    fun section(key: String): JsonObject = read()[key] as? JsonObject ?: JsonObject(emptyMap())

    /** Replaces section [key] (null removes it) and saves. */
    fun put(key: String, value: JsonElement?) = synchronized(lock) {
        val m = LinkedHashMap(read())
        if (value == null) m.remove(key) else m[key] = value
        val next = JsonObject(m)
        cache = next
        try {
            files.write(name, Json.encodeToString(JsonObject.serializer(), next).toByteArray(Charsets.UTF_8))
        } catch (_: IOException) {
            // Kept in memory for this process; the next run redoes the work.
        } catch (_: GeneralSecurityException) {
        } catch (_: RuntimeException) {
        }
    }

    /** Forgets everything (sign-out, account switch). */
    fun reset() = synchronized(lock) {
        cache = JsonObject(emptyMap())
        files.delete(name)
    }

    private fun load(): JsonObject = when (val r = files.read(name)) {
        is StoreRead.Ok -> try {
            Json.parseToJsonElement(String(r.bytes, Charsets.UTF_8)) as? JsonObject ?: JsonObject(emptyMap())
        } catch (_: Exception) {
            JsonObject(emptyMap())
        }
        else -> JsonObject(emptyMap())
    }

    companion object {
        const val NAME = "sync-state"
    }
}
