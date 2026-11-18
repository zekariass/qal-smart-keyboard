package com.qalsmartkeyboard.sync

import com.qalsmartkeyboard.data.secure.EncryptedFileStore
import com.qalsmartkeyboard.data.secure.StoreRead
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * A destination profile (N3: "Mum, Amharic, honorific"): the user's named choice of how to write
 * for a destination. [localId] is this device's key (the server id once known, see [serverId]).
 * The enum-like members are the contract's strings (`Formality`, `AddresseeGender`, `Tone`,
 * `TranslationStyle`); they are carried, not interpreted, by sync.
 */
data class DestinationProfile(
    val localId: String,
    val serverId: String? = null,
    val label: String,
    val destinationLanguage: String,
    val formality: String? = null,
    val addresseeGender: String? = null,
    val defaultTone: String? = null,
    val defaultStyle: String? = null,
    val glossaryId: String? = null,
    val sortOrder: Int = 100,
    /** Changed on this device since the last push. */
    val dirty: Boolean = true,
    /** Deleted on this device, the delete not yet pushed (a tombstone). */
    val deleted: Boolean = false,
)

/**
 * A per-app rule (Android only): app -> a destination profile or a destination language.
 * [packageName] is known only on this device (stored encrypted, never sent): rules that arrived from
 * another device have only the [hmac]. [hmac] is `HMAC-SHA256(accountKey, packageName)` in hex,
 * computed at sync time once the per-account key is known.
 */
data class AppRule(
    val packageName: String?,
    val hmac: String?,
    /** [DestinationProfile.localId] of the target profile, or null when the target is a language. */
    val profileLocalId: String?,
    val language: String?,
    val enabled: Boolean = true,
)

/**
 * Local copy of destination profiles and app rules, encrypted at rest (labels and package names can
 * identify people and habits). Thread-safe; [profiles] and [rules] return snapshots. The editing
 * UI (a later step) and the sync both use it: edits mark a profile dirty, a delete leaves a
 * tombstone until the server delete is done.
 */
class DestinationStore(
    private val files: EncryptedFileStore,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val name: String = NAME,
) {
    private val lock = Any()
    private var profiles: List<DestinationProfile>? = null
    private var rules: List<AppRule>? = null

    fun profiles(): List<DestinationProfile> = synchronized(lock) { load(); profiles!!.filter { !it.deleted } }

    fun rules(): List<AppRule> = synchronized(lock) { load(); rules!! }

    /** Creates a profile; returns its local id. Capped at [MAX_PROFILES] (the server's cap). */
    fun addProfile(p: DestinationProfile): String? = synchronized(lock) {
        load()
        if (profiles!!.count { !it.deleted } >= MAX_PROFILES) return null
        val id = newId()
        profiles = profiles!! + p.copy(localId = id, serverId = null, dirty = true, deleted = false)
        save()
        id
    }

    fun updateProfile(localId: String, change: (DestinationProfile) -> DestinationProfile) = synchronized(lock) {
        load()
        profiles = profiles!!.map { if (it.localId == localId && !it.deleted) change(it).copy(localId = it.localId, serverId = it.serverId, dirty = true) else it }
        save()
    }

    /** Deletes a profile; rules that point at it go too (as on the server). */
    fun deleteProfile(localId: String) = synchronized(lock) {
        load()
        profiles = profiles!!.mapNotNull { p ->
            when {
                p.localId != localId -> p
                p.serverId == null -> null // never synced: nothing to tell the server
                else -> p.copy(deleted = true, dirty = false)
            }
        }
        rules = rules!!.filter { it.profileLocalId != localId }
        save()
    }

    /** Sets (replaces) the rule of [packageName]. */
    fun setRule(packageName: String, profileLocalId: String?, language: String?, enabled: Boolean = true) = synchronized(lock) {
        require((profileLocalId == null) != (language == null)) { "exactly one target" }
        load()
        val kept = rules!!.filter { it.packageName != packageName }
        rules = kept + AppRule(packageName, null, profileLocalId, language, enabled)
        save()
    }

    fun removeRule(packageName: String) = synchronized(lock) {
        load()
        rules = rules!!.filter { it.packageName != packageName }
        save()
    }

    // ---- used by the sync (same package)

    internal fun replaceProfiles(list: List<DestinationProfile>) = synchronized(lock) {
        load()
        profiles = list
        save()
    }

    /** Atomic read-modify-write of the whole profile list (including tombstones). */
    internal fun <T> editProfiles(block: (List<DestinationProfile>) -> Pair<List<DestinationProfile>, T>): T = synchronized(lock) {
        load()
        val (next, out) = block(profiles!!)
        profiles = next
        save()
        out
    }

    internal fun allProfiles(): List<DestinationProfile> = synchronized(lock) { load(); profiles!! }

    internal fun editRules(block: (List<AppRule>) -> List<AppRule>) = synchronized(lock) {
        load()
        rules = block(rules!!)
        save()
    }

    /** Forgets everything (sign-out of an account: its profiles are not ours to keep for the next one). */
    fun clear() = synchronized(lock) {
        profiles = emptyList()
        rules = emptyList()
        files.delete(name)
    }

    // ---- persistence

    private fun load() {
        if (profiles != null) return
        profiles = emptyList()
        rules = emptyList()
        val r = files.read(name) as? StoreRead.Ok ?: return
        try {
            val o = Json.parseToJsonElement(String(r.bytes, Charsets.UTF_8)) as? JsonObject ?: return
            profiles = (o["profiles"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::profileOf) }
            rules = (o["rules"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::ruleOf) }
        } catch (_: Exception) {
            profiles = emptyList()
            rules = emptyList()
        }
    }

    private fun save() {
        val o = JsonObject(
            mapOf(
                "profiles" to JsonArray(profiles!!.map(::profileJson)),
                "rules" to JsonArray(rules!!.map(::ruleJson)),
            ),
        )
        try {
            files.write(name, Json.encodeToString(JsonObject.serializer(), o).toByteArray(Charsets.UTF_8))
        } catch (_: IOException) {
        } catch (_: GeneralSecurityException) {
        } catch (_: RuntimeException) {
        }
    }

    private fun str(v: String?): JsonElement = if (v == null) JsonNull else JsonPrimitive(v)

    private fun profileJson(p: DestinationProfile) = JsonObject(
        mapOf(
            "localId" to JsonPrimitive(p.localId), "serverId" to str(p.serverId), "label" to JsonPrimitive(p.label),
            "lang" to JsonPrimitive(p.destinationLanguage), "formality" to str(p.formality), "gender" to str(p.addresseeGender),
            "tone" to str(p.defaultTone), "style" to str(p.defaultStyle), "glossary" to str(p.glossaryId),
            "sort" to JsonPrimitive(p.sortOrder), "dirty" to JsonPrimitive(p.dirty), "deleted" to JsonPrimitive(p.deleted),
        ),
    )

    private fun profileOf(o: JsonObject): DestinationProfile? {
        fun s(n: String) = (o[n] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        return DestinationProfile(
            localId = s("localId") ?: return null, serverId = s("serverId"), label = s("label") ?: return null,
            destinationLanguage = s("lang") ?: return null, formality = s("formality"), addresseeGender = s("gender"),
            defaultTone = s("tone"), defaultStyle = s("style"), glossaryId = s("glossary"),
            sortOrder = (o["sort"] as? JsonPrimitive)?.intOrNull ?: 100,
            dirty = (o["dirty"] as? JsonPrimitive)?.booleanOrNull ?: true, deleted = (o["deleted"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }

    private fun ruleJson(r: AppRule) = JsonObject(
        mapOf(
            "pkg" to str(r.packageName), "hmac" to str(r.hmac), "profile" to str(r.profileLocalId),
            "lang" to str(r.language), "enabled" to JsonPrimitive(r.enabled),
        ),
    )

    private fun ruleOf(o: JsonObject): AppRule? {
        fun s(n: String) = (o[n] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        val pkg = s("pkg")
        val hmac = s("hmac")
        if (pkg == null && hmac == null) return null
        return AppRule(pkg, hmac, s("profile"), s("lang"), (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true)
    }

    companion object {
        const val NAME = "sync-dest"
        const val MAX_PROFILES = 100
    }
}
