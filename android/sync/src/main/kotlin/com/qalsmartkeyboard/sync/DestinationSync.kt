package com.qalsmartkeyboard.sync

import com.qalsmartkeyboard.api.ApiError
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The per-account key behind `appPackageHmac = HMAC-SHA256(key, packageName)` (B3.4, ADR 0007 D24).
 * Fetched once (`GET /me/app-destination-rules/key`; one key per account, identical on every call,
 * never regenerated, so a device must never invent its own) and kept in the encrypted sync state.
 * Never logged. [reset] with the rest of the sync state on sign-out or account switch: a guest's key
 * moves to the account on sign-in, so the key is read again for the new session.
 */
class RuleKeyStore(private val http: SyncHttp, private val state: SyncStateStore) {
    suspend fun key(): ByteArray {
        stored()?.let { return it }
        val o = http.get("/me/app-destination-rules/key") as? JsonObject ?: throw ApiError.Unexpected(null)
        val b64 = (o["key"] as? JsonPrimitive)?.contentOrNull ?: throw ApiError.Unexpected(null)
        val bytes = try {
            Base64.getDecoder().decode(b64)
        } catch (_: IllegalArgumentException) {
            throw ApiError.Unexpected(null)
        }
        if (bytes.size != KEY_BYTES) throw ApiError.Unexpected(null)
        state.put(SECTION, JsonObject(mapOf("keyId" to (o["keyId"] ?: JsonNull), "key" to JsonPrimitive(b64))))
        return bytes
    }

    private fun stored(): ByteArray? {
        val b64 = (state.section(SECTION)["key"] as? JsonPrimitive)?.contentOrNull ?: return null
        return try {
            Base64.getDecoder().decode(b64).takeIf { it.size == KEY_BYTES }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    companion object {
        private const val SECTION = "ruleKey"
        const val KEY_BYTES = 32

        /** `hex(HMAC-SHA256(key, UTF-8 packageName))`, lowercase: the only form in which a package name leaves the device. */
        fun hash(key: ByteArray, packageName: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(packageName.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
}

/** An app rule as it travels (`AppDestinationRule`). */
internal data class WireRule(val hmac: String, val profileId: String?, val language: String?, val enabled: Boolean) {
    fun toJson(): JsonObject = JsonObject(
        buildMap {
            put("appPackageHmac", JsonPrimitive(hmac))
            if (profileId != null) put("destinationProfileId", JsonPrimitive(profileId)) else put("destinationLanguage", JsonPrimitive(language))
            put("enabled", JsonPrimitive(enabled))
        },
    )

    companion object {
        private val HEX64 = Regex("[0-9a-f]{64}")

        fun fromJson(o: JsonObject): WireRule? {
            fun s(n: String) = (o[n] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
            val h = s("appPackageHmac")?.takeIf { HEX64.matches(it) } ?: return null
            val p = s("destinationProfileId")
            val l = s("destinationLanguage")
            if ((p == null) == (l == null)) return null
            return WireRule(h, p, l, (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true)
        }

        fun parse(doc: JsonObject?): Map<String, WireRule> =
            (doc?.get("rules") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::fromJson) }.associateBy { it.hmac }
    }
}

/** What a destination-profile sync did; counts only. */
data class ProfilesResult(val pushed: Int, val deleted: Int, val failed: Int, val changedLocally: Boolean)

/**
 * Sync of destination profiles and per-app rules (B3.4).
 *
 * **Profiles: last writer wins, no revision (the contract has none).** A run pushes first, then
 * pulls: local deletes (`DELETE`, 404 counts as done), then local creates (`POST`) and edits
 * (`PUT`; a 404 means the profile was deleted elsewhere, and the local edit wins by re-creating
 * it). Then `GET` the list and reconcile: a profile that is clean locally takes the server's
 * version, a profile unknown locally is added, a clean synced profile the server no longer has was
 * deleted elsewhere and is removed locally. A profile still dirty (its push failed) is left alone.
 * So whichever device syncs last decides, per profile, as the contract says.
 *
 * **App rules: revision protocol with a keyed merge.** Rules are matched by `appPackageHmac`, computed
 * on the device as `HMAC-SHA256(accountKey, packageName)`; package names never leave the device (rules
 * that arrived from another device have only the hash, so the editing UI can show them as unknown
 * apps). Merge (three-way per hash, base = the rules last synced): changed locally since then
 * (added, edited or removed) -> the local state wins, else the server's. Rules whose target profile
 * has no server id yet are held back until the profile is synced (profiles sync first).
 */
class DestinationSync internal constructor(
    private val http: SyncHttp,
    private val store: DestinationStore,
    private val keys: RuleKeyStore,
    private val rulesDoc: DocSync,
) {
    suspend fun syncProfiles(): ProfilesResult {
        var pushed = 0
        var deleted = 0
        var failed = 0
        for (p in store.allProfiles().filter { it.deleted }) {
            try {
                p.serverId?.let { http.delete("$PROFILES/$it") }
                store.editProfiles { l -> l.filter { it.localId != p.localId } to Unit }
                deleted++
            } catch (e: ApiError.Http) {
                if (e.status == 404) {
                    store.editProfiles { l -> l.filter { it.localId != p.localId } to Unit }
                    deleted++
                } else if (perItem(e)) failed++ else throw e
            }
        }
        for (p in store.allProfiles().filter { it.dirty && !it.deleted }) {
            try {
                val body = inputJson(p)
                val answer = if (p.serverId == null) {
                    http.post(PROFILES, body)
                } else {
                    try {
                        http.put("$PROFILES/${p.serverId}", body)
                    } catch (e: ApiError.Http) {
                        if (e.status == 404) http.post(PROFILES, body) else throw e
                    }
                }
                val id = ((answer as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull ?: p.serverId
                store.editProfiles { l ->
                    l.map { cur ->
                        if (cur.localId != p.localId) cur
                        else if (cur.sameContent(p)) cur.copy(serverId = id, dirty = false)
                        else cur.copy(serverId = id) // edited again while pushing: stay dirty
                    } to Unit
                }
                pushed++
            } catch (e: ApiError.Http) {
                if (perItem(e)) failed++ else throw e
            }
        }
        val list = (http.get(PROFILES) as? JsonObject)?.get("items") as? JsonArray ?: throw ApiError.Unexpected(null)
        val server = list.mapNotNull { (it as? JsonObject)?.let(::fromServer) }.associateBy { it.serverId }
        val changed = store.editProfiles { cur ->
            val out = ArrayList<DestinationProfile>()
            val seen = HashSet<String>()
            var changedLocally = false
            for (p in cur) {
                val sid = p.serverId
                when {
                    sid == null || p.dirty -> out += p
                    p.deleted -> if (sid in server) out += p
                    else -> {
                        val s = server[sid]
                        if (s == null) {
                            changedLocally = true
                        } else {
                            val merged = s.copy(localId = p.localId)
                            if (!merged.sameContent(p)) changedLocally = true
                            out += merged
                        }
                    }
                }
                if (sid != null) seen += sid
            }
            for ((sid, s) in server) if (sid != null && sid !in seen) {
                out += s.copy(localId = sid)
                changedLocally = true
            }
            out to changedLocally
        }
        return ProfilesResult(pushed, deleted, failed, changed)
    }

    /** Syncs the per-app rules document. Call after [syncProfiles]. */
    internal suspend fun syncRules(): DocResult {
        val key = keys.key()
        val startRules = store.rules()
        val profiles = store.allProfiles().filter { !it.deleted && it.serverId != null }
        val toServer = profiles.associate { it.localId to it.serverId!! }
        val toLocal = profiles.associate { it.serverId!! to it.localId }
        fun hashOf(r: AppRule): String? = r.packageName?.let { RuleKeyStore.hash(key, it) } ?: r.hmac
        fun wireOf(r: AppRule): WireRule? {
            val h = hashOf(r) ?: return null
            return if (r.profileLocalId != null) {
                WireRule(h, toServer[r.profileLocalId] ?: return null, null, r.enabled)
            } else {
                WireRule(h, null, r.language ?: return null, r.enabled)
            }
        }
        val start = startRules.mapNotNull { r -> wireOf(r)?.let { it.hmac to r } }.toMap()
        val local = start.mapValues { wireOf(it.value)!! }
        val base = WireRule.parse(rulesDoc.lastDoc())
        return rulesDoc.run { serverDoc ->
            val server = WireRule.parse(serverDoc)
            val merged = mergeRules(local, base, server)
            val items = merged.values.sortedBy { it.hmac }.take(MAX_RULES)
            val needsPut = merged.values.toSet() != server.values.toSet()
            val body = JsonObject(serverDoc.filterKeys { it != "revision" } + ("rules" to JsonArray(items.map { it.toJson() })))
            DocPlan(body, needsPut) {
                store.editRules { cur ->
                    val out = ArrayList<AppRule>()
                    val seen = HashSet<String>()
                    for (r in cur) {
                        val h = hashOf(r)
                        if (h == null) {
                            out += r
                            continue
                        }
                        seen += h
                        val m = merged[h]
                        when {
                            start[h] != r -> out += r // new or changed here meanwhile: stays local, wins next time
                            m == null -> {} // removed on another device
                            else -> out += r.copy(profileLocalId = m.profileId?.let { toLocal[it] }, language = m.language, enabled = m.enabled)
                        }
                    }
                    for ((h, m) in merged) if (h !in seen) {
                        val target = m.profileId?.let { toLocal[it] }
                        if (m.profileId == null || target != null) out += AppRule(null, h, target, m.language, m.enabled)
                    }
                    out
                }
            }
        }
    }

    private fun perItem(e: ApiError.Http) = e.status in setOf(400, 409, 422)

    private fun inputJson(p: DestinationProfile) = JsonObject(
        buildMap {
            put("label", JsonPrimitive(p.label))
            put("destinationLanguage", JsonPrimitive(p.destinationLanguage))
            p.formality?.let { put("formality", JsonPrimitive(it)) }
            p.addresseeGender?.let { put("addresseeGender", JsonPrimitive(it)) }
            p.defaultTone?.let { put("defaultTone", JsonPrimitive(it)) }
            p.defaultStyle?.let { put("defaultStyle", JsonPrimitive(it)) }
            p.glossaryId?.let { put("glossaryId", JsonPrimitive(it)) }
            put("sortOrder", JsonPrimitive(p.sortOrder))
        },
    )

    private fun fromServer(o: JsonObject): DestinationProfile? {
        fun s(n: String) = (o[n] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        val id = s("id") ?: return null
        return DestinationProfile(
            localId = id, serverId = id, label = s("label") ?: return null, destinationLanguage = s("destinationLanguage") ?: return null,
            formality = s("formality"), addresseeGender = s("addresseeGender"), defaultTone = s("defaultTone"),
            defaultStyle = s("defaultStyle"), glossaryId = s("glossaryId"),
            sortOrder = (o["sortOrder"] as? JsonPrimitive)?.intOrNull ?: 100, dirty = false,
        )
    }

    private fun DestinationProfile.sameContent(o: DestinationProfile) =
        copy(localId = "", serverId = null, dirty = false, deleted = false) == o.copy(localId = "", serverId = null, dirty = false, deleted = false)

    companion object {
        private const val PROFILES = "/me/destination-profiles"
        const val MAX_RULES = 200

        fun create(http: SyncHttp, store: DestinationStore, state: SyncStateStore) =
            DestinationSync(http, store, RuleKeyStore(http, state), DocSync(http, state, "/me/app-destination-rules", "rules"))

        /** Three-way merge by hash (see the class comment). */
        internal fun mergeRules(local: Map<String, WireRule>, base: Map<String, WireRule>, server: Map<String, WireRule>): Map<String, WireRule> {
            val out = LinkedHashMap<String, WireRule>()
            for (k in (local.keys + base.keys + server.keys).sorted()) {
                val l = local[k]
                val r = if (l != base[k]) l else server[k]
                if (r != null) out[k] = r
            }
            return out
        }
    }
}
