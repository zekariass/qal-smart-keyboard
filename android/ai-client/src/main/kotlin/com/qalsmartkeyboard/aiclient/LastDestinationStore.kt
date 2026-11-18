package com.qalsmartkeyboard.aiclient

import com.qalsmartkeyboard.data.secure.EncryptedFileStore
import com.qalsmartkeyboard.data.secure.StoreRead
import com.qalsmartkeyboard.sync.RuleKeyStore
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The last destination the user picked in each app (ADR 0007 D40), **device-local**: it is never
 * synced and never leaves the device.
 *
 * The app is keyed like A2.4's rules, as `hex(HMAC-SHA256(key, packageName))`
 * ([RuleKeyStore.hash]); a raw package name is never written. The key is a random 32-byte secret
 * made on first use and kept in the same encrypted file (not the account key of A2.4: that one
 * needs the network and an account, and this feature must work offline and for signed-out users).
 * The file is an [EncryptedFileStore] blob (Keystore key). The value is a destination id (a
 * profile's local id or `lang:xx`). At most [MAX_APPS] apps are kept, least recently set dropped
 * first.
 *
 * [load] reads the disk (call it off the main thread); [lastFor] is in-memory and instant.
 */
class LastDestinationStore(
    private val files: EncryptedFileStore,
    private val name: String = NAME,
    private val newKey: () -> ByteArray = { ByteArray(RuleKeyStore.KEY_BYTES).also { SecureRandom().nextBytes(it) } },
) {
    private val lock = Any()
    private var loaded = false
    private var key: ByteArray? = null
    private val byHash = LinkedHashMap<String, String>()

    fun load() = synchronized(lock) { loadLocked() }

    private fun loadLocked() {
        if (loaded) return
        loaded = true
        val r = files.read(name) as? StoreRead.Ok ?: return
        try {
            val o = Json.parseToJsonElement(String(r.bytes, Charsets.UTF_8)) as? JsonObject ?: return
            key = (o["key"] as? JsonPrimitive)?.contentOrNull?.let { Base64.getDecoder().decode(it) }?.takeIf { it.size == RuleKeyStore.KEY_BYTES }
            val apps = o["apps"] as? JsonObject ?: return
            if (key != null) for ((h, v) in apps) (v as? JsonPrimitive)?.takeIf { it.isString }?.let { byHash[h] = it.content }
        } catch (_: Exception) {
            byHash.clear()
            key = null
        }
    }

    /** The remembered destination id for [packageName], or null. In-memory only; null before [load]. */
    fun lastFor(packageName: String?): String? = synchronized(lock) {
        val k = key
        if (packageName == null || k == null) null else byHash[RuleKeyStore.hash(k, packageName)]
    }

    /** Remembers [destinationId] for [packageName] and writes the file (blocking disk I/O). */
    fun remember(packageName: String, destinationId: String) = synchronized(lock) {
        loadLocked()
        val k = key ?: newKey().also { key = it }
        val h = RuleKeyStore.hash(k, packageName)
        byHash.remove(h)
        byHash[h] = destinationId
        while (byHash.size > MAX_APPS) byHash.remove(byHash.keys.first())
        persist(k)
    }

    private fun persist(k: ByteArray) {
        val o = JsonObject(
            mapOf(
                "key" to JsonPrimitive(Base64.getEncoder().encodeToString(k)),
                "apps" to JsonObject(byHash.mapValues { JsonPrimitive(it.value) }),
            ),
        )
        try {
            files.write(name, Json.encodeToString(JsonObject.serializer(), o).toByteArray(Charsets.UTF_8))
        } catch (_: IOException) {
        } catch (_: GeneralSecurityException) {
        } catch (_: RuntimeException) {
        }
    }

    companion object {
        const val NAME = "ai-last-dest"
        const val MAX_APPS = 200
    }
}
