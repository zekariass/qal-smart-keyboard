package com.qalsmartkeyboard.aiclient

import com.qalsmartkeyboard.data.secure.EncryptedFileStore
import com.qalsmartkeyboard.data.secure.StoreRead
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The cached entitlement token and the newest time seen, in an [EncryptedFileStore] (Keystore
 * key). The token holds no personal data (ADR 0013 D2) but is still kept encrypted and never
 * logged. [newestSeenMs] is the largest of every token `iat` and server `Date` accepted so far:
 * [monotonicNow] never goes back with the device clock (D4.5).
 */
class EntitlementTokenStore(private val files: EncryptedFileStore, private val name: String = NAME) {
    private val lock = Any()
    private var loaded = false
    private var jws: String? = null
    private var newestSeen = 0L

    fun token(): String? = synchronized(lock) { load(); jws }

    fun newestSeenMs(): Long = synchronized(lock) { load(); newestSeen }

    /** "Now" for token checks: the later of the device clock and the newest time seen. */
    fun monotonicNow(deviceClockMs: Long): Long = maxOf(deviceClockMs, newestSeenMs())

    /** Stores a verified token. [serverTimeMs] is the response's `Date`, when it had one. */
    fun save(token: String, issuedAtMs: Long, serverTimeMs: Long?) = synchronized(lock) {
        load()
        jws = token
        newestSeen = maxOf(newestSeen, issuedAtMs, serverTimeMs ?: 0L)
        persist()
    }

    /** Records a server time without changing the token. */
    fun seen(serverTimeMs: Long) = synchronized(lock) {
        load()
        if (serverTimeMs > newestSeen) {
            newestSeen = serverTimeMs
            persist()
        }
    }

    /** Drops the token (a 401 or 403 from the token endpoint, sign-out) but keeps the time floor. */
    fun dropToken() = synchronized(lock) {
        load()
        jws = null
        persist()
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val r = files.read(name) as? StoreRead.Ok ?: return
        try {
            val o = Json.parseToJsonElement(String(r.bytes, Charsets.UTF_8)) as? JsonObject ?: return
            jws = (o["jws"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            newestSeen = (o["seen"] as? JsonPrimitive)?.longOrNull ?: 0L
        } catch (_: Exception) {
            jws = null
        }
    }

    private fun persist() {
        val o = JsonObject(buildMap {
            jws?.let { put("jws", JsonPrimitive(it)) }
            put("seen", JsonPrimitive(newestSeen))
        })
        try {
            files.write(name, Json.encodeToString(JsonObject.serializer(), o).toByteArray(Charsets.UTF_8))
        } catch (_: IOException) {
        } catch (_: GeneralSecurityException) {
        } catch (_: RuntimeException) {
        }
    }

    companion object {
        const val NAME = "ai-ent-token"
    }
}
