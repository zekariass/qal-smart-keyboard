package com.qalsmartkeyboard.sync

import com.qalsmartkeyboard.api.ApiError
import com.qalsmartkeyboard.data.ConversionTrigger
import com.qalsmartkeyboard.data.InputMode
import com.qalsmartkeyboard.data.KeyboardSettings
import com.qalsmartkeyboard.data.LanguageRules
import com.qalsmartkeyboard.data.SettingsRepository
import com.qalsmartkeyboard.data.TypingLanguageSetting
import com.qalsmartkeyboard.data.UnknownTokenBehavior
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** One language as it travels (`TypingLanguage`), with the profile code already resolved (D21). */
internal data class WireLang(
    val code: String,
    val enabled: Boolean,
    val mode: InputMode,
    /** Backend profile code; set exactly when [mode] is phonetic. */
    val profile: String?,
    val trigger: ConversionTrigger,
    val showCandidates: Boolean,
    val unknown: UnknownTokenBehavior,
) {
    fun toJson(sortOrder: Int, primary: Boolean): JsonObject = JsonObject(
        buildMap {
            put("languageCode", JsonPrimitive(code))
            put("enabled", JsonPrimitive(enabled))
            put("isPrimary", JsonPrimitive(primary))
            put("sortOrder", JsonPrimitive(sortOrder))
            put("inputMode", JsonPrimitive(mode.name))
            if (mode == InputMode.PHONETIC_LATIN_TO_NATIVE && profile != null) put("transliterationProfileCode", JsonPrimitive(profile))
            put("conversionTrigger", JsonPrimitive(trigger.name))
            put("showTransliterationCandidates", JsonPrimitive(showCandidates))
            put("unknownTokenBehavior", JsonPrimitive(unknown.name))
        },
    )

    companion object {
        /** Null for an entry this app cannot represent (unknown language or enum value): kept raw by the caller. */
        fun fromJson(o: JsonObject): WireLang? {
            fun s(n: String) = (o[n] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
            val code = s("languageCode")?.takeIf { LanguageRules.isKnown(it) } ?: return null
            val mode = InputMode.entries.firstOrNull { it.name == s("inputMode") } ?: return null
            return WireLang(
                code = code,
                enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: return null,
                mode = mode,
                profile = s("transliterationProfileCode").takeIf { mode == InputMode.PHONETIC_LATIN_TO_NATIVE },
                trigger = ConversionTrigger.entries.firstOrNull { it.name == (s("conversionTrigger") ?: "LIVE_COMPOSING") } ?: return null,
                showCandidates = (o["showTransliterationCandidates"] as? JsonPrimitive)?.booleanOrNull ?: true,
                unknown = UnknownTokenBehavior.entries.firstOrNull { it.name == (s("unknownTokenBehavior") ?: "PRESERVE_LATIN") } ?: return null,
            )
        }
    }
}

/** A parsed typing-language document: the representable entries in order, plus raw entries kept as they are. */
internal class TypingDoc(val langs: List<WireLang>, val raw: List<JsonObject>) {
    companion object {
        fun parse(doc: JsonObject?): TypingDoc {
            val items = (doc?.get("languages") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val sorted = items.withIndex()
                .sortedWith(compareBy({ (it.value["sortOrder"] as? JsonPrimitive)?.intOrNull ?: 100 }, { it.index }))
                .map { it.value }
            val langs = ArrayList<WireLang>()
            val raw = ArrayList<JsonObject>()
            for (o in sorted) {
                val w = WireLang.fromJson(o)
                if (w == null) raw += o else if (langs.none { it.code == w.code }) langs += w
            }
            return TypingDoc(langs, raw)
        }
    }
}

/**
 * Sync of `/me/typing-languages` (B3.3): the ordered language list with, per language, enabled,
 * input mode, transliteration profile, conversion trigger, candidate toggle and unknown-token behaviour.
 *
 * **Profile codes (ADR 0007 D21).** The backend code is canonical. A local phonetic language sends
 * its stored code when it is an ACTIVE profile of that language (or already the one the server
 * holds, since a retired profile stays valid for users on it); a missing or unknown local code
 * (null = "newest active", or a legacy derived code) is replaced by the newest ACTIVE profile from
 * `GET /languages/{code}/transliteration-profiles`. PHONETIC is never sent without a code: if no
 * code can be resolved (profile list unreachable or empty) that language is **deferred**: the
 * server's entry for it is left as it is, nothing is applied locally for it, and the run reports
 * it in [TypingResult.deferred] so it is retried.
 *
 * **Merge rule (field level, three-way, per language).** Base = the language in the document last
 * synced (schema defaults before the first sync). For each field of a language (`enabled`; the pair
 * input mode + profile; trigger; candidates; unknown-token behaviour): changed locally since the
 * last sync -> the local value wins; otherwise the server value wins. Order: if the user reordered
 * on this device since the last sync the local order wins, otherwise the server order; languages
 * the server does not list yet are appended. Entries the app cannot represent are preserved.
 * `isPrimary` is the first enabled language, `sortOrder` 10, 20, ... as `KeyboardSettings.toSyncItems`.
 */
class TypingLanguagesSync internal constructor(
    private val repo: SettingsRepository,
    private val doc: DocSync,
    private val profiles: ProfileDirectory,
) {
    class TypingResult internal constructor(internal val result: DocResult, val deferred: Set<String>) {
        val unchanged: Boolean get() = result == DocResult.UNCHANGED && deferred.isEmpty()
    }

    internal suspend fun run(): TypingResult = try {
        runOnce()
    } catch (e: ApiError.Http) {
        if (e.status == 422 && e.code == "PROFILE_NOT_ACTIVE") {
            // Our cached profile list was stale: refetch and try once more.
            profiles.invalidate()
            runOnce()
        } else {
            throw e
        }
    }

    private suspend fun runOnce(): TypingResult {
        val start = repo.current()
        val lastDoc = doc.lastDoc()
        // Profile lists are needed for languages in phonetic mode, and (before the first sync) to build the default base.
        val active: Map<String, List<String>?> = LanguageRules.KNOWN
            .filter { code ->
                InputMode.PHONETIC_LATIN_TO_NATIVE in LanguageRules.allowedModes(code) &&
                    (lastDoc == null || start.language(code)?.inputMode == InputMode.PHONETIC_LATIN_TO_NATIVE)
            }
            .associateWith { profiles.active(it) }
        val deferredOut = LinkedHashSet<String>()
        val baseDoc = lastDoc?.let { TypingDoc.parse(it) }
        val baseBy = baseDoc?.langs?.associateBy { it.code }.orEmpty()
        val baseOrder = baseDoc?.langs?.map { it.code }?.let { it + (LanguageRules.KNOWN - it.toSet()) } ?: LanguageRules.KNOWN
        fun baseOf(code: String): WireLang = baseBy[code]
            ?: wireOf(TypingLanguageSetting(code), active[code], null)
            ?: WireLang(code, true, LanguageRules.defaultMode(code), null, ConversionTrigger.LIVE_COMPOSING, true, UnknownTokenBehavior.PRESERVE_LATIN)

        val result = doc.run { server ->
            val serverDoc = TypingDoc.parse(server)
            val serverBy = serverDoc.langs.associateBy { it.code }
            val local = HashMap<String, WireLang?>()
            for (l in start.languages) local[l.languageCode] = wireOf(l, active[l.languageCode], serverBy[l.languageCode]?.profile)
            val merged = mergeTyping(
                localOrder = start.languages.map { it.languageCode },
                local = local,
                baseOrder = baseOrder,
                base = ::baseOf,
                serverOrder = serverDoc.langs.map { it.code },
                server = serverBy,
            )
            deferredOut.clear()
            deferredOut += local.filterValues { it == null }.keys
            val needsPut = merged != serverDoc.langs
            val primary = merged.firstOrNull { it.enabled }?.code
            val items = merged.mapIndexed { i, w -> w.toJson((i + 1) * 10, w.code == primary) } + serverDoc.raw
            val body = JsonObject(server.filterKeys { it != "revision" } + ("languages" to JsonArray(items)))
            DocPlan(body, needsPut) { applyLocal(start, local, merged) }
        }
        return TypingResult(result, deferredOut.toSet())
    }

    private suspend fun applyLocal(start: KeyboardSettings, local: Map<String, WireLang?>, merged: List<WireLang>) {
        val byCode = merged.associateBy { it.code }
        repo.update { cur ->
            val startBy = start.languages.associateBy { it.languageCode }
            // Each field only if the user did not change it while the sync ran (it then stays local and wins next time).
            val updated = cur.languages.map { c ->
                val r = byCode[c.languageCode]
                val st = startBy[c.languageCode]
                val lw = local[c.languageCode]
                if (r == null || st == null || lw == null) return@map c // deferred or unknown: nothing to apply
                var n = c
                if (c.enabled == st.enabled) n = n.copy(enabled = r.enabled)
                if (c.inputMode == st.inputMode && c.transliterationProfileCode == st.transliterationProfileCode) {
                    n = n.copy(
                        inputMode = r.mode,
                        transliterationProfileCode = when {
                            r.mode != InputMode.PHONETIC_LATIN_TO_NATIVE -> null
                            lw.profile == r.profile && (c.transliterationProfileCode == null || c.transliterationProfileCode == r.profile) ->
                                c.transliterationProfileCode // same code: keep "newest active" (null) as it is; a legacy code is replaced
                            else -> r.profile
                        },
                    )
                }
                if (c.conversionTrigger == st.conversionTrigger) n = n.copy(conversionTrigger = r.trigger)
                if (c.showTransliterationCandidates == st.showTransliterationCandidates) n = n.copy(showTransliterationCandidates = r.showCandidates)
                if (c.unknownTokenBehavior == st.unknownTokenBehavior) n = n.copy(unknownTokenBehavior = r.unknown)
                n
            }
            val order = merged.map { it.code }
            val ordered = if (cur.languages.map { it.languageCode } == start.languages.map { it.languageCode }) {
                updated.sortedBy { order.indexOf(it.languageCode).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            } else {
                updated
            }
            cur.copy(languages = ordered)
        }
    }

    companion object {
        /** Local -> wire, resolving the profile code (D21). Null = phonetic with no resolvable code (deferred). */
        internal fun wireOf(l: TypingLanguageSetting, active: List<String>?, serverProfile: String?): WireLang? {
            var profile: String? = null
            if (l.inputMode == InputMode.PHONETIC_LATIN_TO_NATIVE) {
                val own = l.transliterationProfileCode
                profile = when {
                    own != null && (own == serverProfile || active?.contains(own) == true) -> own
                    !active.isNullOrEmpty() -> active.first()
                    else -> return null
                }
            }
            return WireLang(l.languageCode, l.enabled, l.inputMode, profile, l.conversionTrigger, l.showTransliterationCandidates, l.unknownTokenBehavior)
        }

        /** The three-way merge (see the class comment). [local] holds null for a deferred language. */
        internal fun mergeTyping(
            localOrder: List<String>,
            local: Map<String, WireLang?>,
            baseOrder: List<String>,
            base: (String) -> WireLang,
            serverOrder: List<String>,
            server: Map<String, WireLang>,
        ): List<WireLang> {
            val result = LinkedHashMap<String, WireLang>()
            for (code in localOrder) {
                val l = local[code]
                val s = server[code]
                val r: WireLang? = when {
                    l == null -> s
                    s == null -> l
                    else -> {
                        val b = base(code)
                        val pairLocal = l.mode != b.mode || l.profile != b.profile
                        WireLang(
                            code = code,
                            enabled = if (l.enabled != b.enabled) l.enabled else s.enabled,
                            mode = if (pairLocal) l.mode else s.mode,
                            profile = if (pairLocal) l.profile else s.profile,
                            trigger = if (l.trigger != b.trigger) l.trigger else s.trigger,
                            showCandidates = if (l.showCandidates != b.showCandidates) l.showCandidates else s.showCandidates,
                            unknown = if (l.unknown != b.unknown) l.unknown else s.unknown,
                        )
                    }
                }
                if (r != null) result[code] = r
            }
            val order = if (localOrder != baseOrder) localOrder else serverOrder + (localOrder - serverOrder.toSet())
            val out = order.mapNotNull { result[it] }.toMutableList()
            if (out.isNotEmpty() && out.none { it.enabled }) out[0] = out[0].copy(enabled = true)
            return out
        }
    }
}

/**
 * The transliteration profiles the backend lists per language (`GET /languages/{code}/transliteration-profiles`),
 * ACTIVE ones only, newest first. Cached in the sync state for [ttlMs] so a periodic sync that
 * changes nothing does not call it. A failed lookup is null (callers defer).
 */
class ProfileDirectory(
    private val http: SyncHttp,
    private val state: SyncStateStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 24L * 3600 * 1000,
) {
    suspend fun active(language: String): List<String>? {
        val cached = state.section(SECTION)[language] as? JsonObject
        val at = (cached?.get("at") as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        if (cached != null && at != null && now() - at in 0..ttlMs) {
            return (cached["codes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        }
        val list = try {
            http.get("/languages/$language/transliteration-profiles") as? JsonObject
        } catch (e: ApiError) {
            if (e is ApiError.SignedOut) throw e
            return null
        }
        val items = (list?.get("items") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { (it["status"] as? JsonPrimitive)?.contentOrNull == "ACTIVE" }
        val codes = items.sortedWith { a, b -> compareVersions(versionOf(b), versionOf(a)) }
            .mapNotNull { (it["code"] as? JsonPrimitive)?.contentOrNull }
        val entry = JsonObject(mapOf("at" to JsonPrimitive(now().toString()), "codes" to JsonArray(codes.map { JsonPrimitive(it) })))
        state.put(SECTION, JsonObject(state.section(SECTION) + (language to entry)))
        return codes
    }

    fun invalidate() = state.put(SECTION, null)

    private fun versionOf(o: JsonObject) = (o["version"] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.', '-')
        val pb = b.split('.', '-')
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i)
            val y = pb.getOrNull(i)
            val c = if (x?.toIntOrNull() != null && y?.toIntOrNull() != null) x.toInt().compareTo(y.toInt()) else (x ?: "").compareTo(y ?: "")
            if (c != 0) return c
        }
        return 0
    }

    private companion object {
        const val SECTION = "profiles"
    }
}
