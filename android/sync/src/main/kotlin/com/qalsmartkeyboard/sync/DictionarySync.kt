package com.qalsmartkeyboard.sync

import com.qalsmartkeyboard.api.ApiError
import java.text.Normalizer
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The personal dictionary as sync sees it (a port, so `:sync` does not depend on `:ime` or the Rust
 * core; `:app` implements it over `UserDictionary`). Only user words count: words of the shipped
 * dictionaries are not here. [add] is an *explicit* add (A1.8 / ADR 0007 D18: immediate, counts as
 * learned, clears a pending sighting); typed-but-not-yet-learned sightings are never visible here and
 * are never synced. Bigrams (word pairs) are local only.
 */
interface LocalDictionary {
    /** The words of [language] (any order). Empty before the dictionary has loaded. */
    fun words(language: String): List<String>

    /** True if the word is now in the dictionary. */
    fun add(language: String, word: String): Boolean

    fun forget(language: String, word: String): Boolean
}

enum class DictionaryStatus {
    SYNCED,
    UNCHANGED,

    /** The user has not granted `DICTIONARY_SYNC` (here or on the server). */
    CONSENT_REQUIRED,

    /** Guests cannot sync a dictionary (`GUEST_NOT_ALLOWED`). */
    GUEST_NOT_ALLOWED,

    /** 403 for another reason, for example the plan has no `DICTIONARY_SYNC` entitlement. */
    NOT_ALLOWED,
}

/** Counts only; never words. */
data class DictionaryResult(val status: DictionaryStatus, val pushed: Int = 0, val addedLocally: Int = 0, val removedLocally: Int = 0, val fullResync: Boolean = false)

/**
 * Personal-dictionary sync (B3.6, ADR 0007 D18, D31).
 *
 * **Mapping.** Local word set per language <-> server `ADD`/`DELETE` changes. The app keeps a
 * *snapshot* (per language, the words known to be on the server after the last sync) in the
 * encrypted sync state. A change set is the difference between the local words and the snapshot:
 * words not in the snapshot are `ADD`, snapshot words no longer local are `DELETE` (the user
 * removed or cleared them). Each change gets a `clientChangeId` (UUID) that is stored with the
 * change until the server acknowledged it, so a retry after a lost response, a crash or a
 * network error sends the same ids and the server's idempotency makes it harmless. Words are
 * NFC; words the server would reject (empty, over 64 characters, whitespace or control characters)
 * are never sent. Rejections in an answer are acknowledged too (a rejected word is not retried).
 *
 * **Order and conflicts.** Push first, then pull. Last writer wins per word in server arrival
 * order, so a local change made since the last sync is the later write and the pull that follows
 * returns it after older remote changes. While applying a pull, a word that has a local change
 * still waiting (made during the run) is skipped: the local edit is newer.
 *
 * **Pull.** `GET /me/dictionary/changes?since=<cursor>` oldest first, paged; the cursor is the
 * opaque `syncToken` of the last page (the token of a push answer is ignored: it may be ahead of
 * what this device pulled). Remote `ADD` -> [LocalDictionary.add] (an explicit add: D18, the word is
 * learned at once, no second sighting needed); remote `DELETE` -> [LocalDictionary.forget].
 * A remote word that cannot be added locally is left out of the snapshot (never pushed back as a
 * deletion).
 *
 * **Resync (D31).** Tombstones are kept 180 days. When the server answers the cursor with
 * resync-required (HTTP 410, a code containing `RESYNC`, or 400 for a cursor that is no longer
 * known) the cursor is dropped and the dictionary is downloaded in full: remote words that are
 * missing locally are added, and words that were in the snapshot but are gone from the server are
 * removed locally (they were deleted elsewhere); local words never synced stay and are pushed on
 * the next run. The first sync is the same full download with an empty snapshot.
 *
 * Gates (server, in this order): guests -> [DictionaryStatus.GUEST_NOT_ALLOWED]; entitlement, then
 * the `DICTIONARY_SYNC` consent -> [DictionaryStatus.CONSENT_REQUIRED] (also clears the local
 * "enabled" flag, see [DictionaryConsent]).
 */
class DictionarySync(
    private val http: SyncHttp,
    private val state: SyncStateStore,
    private val local: LocalDictionary,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val languages: List<String> = LANGUAGES,
) {
    suspend fun run(): DictionaryResult {
        try {
            return runInternal()
        } catch (e: ApiError.Http) {
            if (e.status == 403) {
                return DictionaryResult(
                    when (e.code) {
                        "GUEST_NOT_ALLOWED" -> DictionaryStatus.GUEST_NOT_ALLOWED
                        "CONSENT_REQUIRED" -> DictionaryStatus.CONSENT_REQUIRED
                        else -> DictionaryStatus.NOT_ALLOWED
                    },
                )
            }
            throw e
        }
    }

    private suspend fun runInternal(): DictionaryResult {
        var s = DictState.read(state)
        // ---- push
        val pushedCount = push(s).also { s = DictState.read(state) }
        // ---- pull
        var added = 0
        var removed = 0
        var full = false
        var cursor = s.cursor
        var resync = false
        var gotAny = pushedCount > 0
        if (cursor == null) full = true
        val remote = HashMap<String, MutableSet<String>>() // full download only
        var pages = 0
        while (pages++ < MAX_PAGES) {
            val page = try {
                http.get("/me/dictionary/changes", buildMap { cursor?.let { put("since", it) }; put("limit", PAGE.toString()) }) as? JsonObject
                    ?: throw ApiError.Unexpected(null)
            } catch (e: ApiError.Http) {
                if (cursor != null && !resync && isResyncRequired(e)) {
                    resync = true
                    full = true
                    cursor = null
                    remote.clear()
                    continue
                }
                throw e
            }
            val changes = (page["changes"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseChange) }
            if (changes.isNotEmpty()) gotAny = true
            if (full) {
                for (c in changes) {
                    val set = remote.getOrPut(c.language) { HashSet() }
                    if (c.add) set += c.word else set -= c.word
                }
            } else {
                val (a, r) = applyIncremental(changes)
                added += a
                removed += r
            }
            cursor = (page["syncToken"] as? JsonPrimitive)?.contentOrNull ?: cursor
            val more = (page["hasMore"] as? JsonPrimitive)?.booleanOrNull ?: false
            if (!full) DictState.read(state).copy(cursor = cursor).write(state)
            if (!more) break
        }
        if (full) {
            val (a, r) = applyFull(remote)
            added += a
            removed += r
            DictState.read(state).copy(cursor = cursor).write(state)
        }
        val status = if (gotAny || added > 0 || removed > 0 || full) DictionaryStatus.SYNCED else DictionaryStatus.UNCHANGED
        return DictionaryResult(status, pushedCount, added, removed, resync)
    }

    // ------------------------------------------------------------------ push

    private suspend fun push(initial: DictState): Int {
        val localSets = languages.associateWith { l -> local.words(l).map(::nfc).filter(::valid).toSet() }
        val ops = ArrayList<Op>()
        for (l in languages) {
            val snap = initial.snapshot[l].orEmpty()
            val now = localSets.getValue(l)
            for (w in (now - snap).sorted()) ops += Op(true, l, w)
            for (w in (snap - now).sorted()) ops += Op(false, l, w)
        }
        if (ops.isEmpty()) {
            if (initial.ids.isNotEmpty()) initial.copy(ids = emptyMap()).write(state)
            return 0
        }
        // Stable ids: the same pending change keeps its id across retries.
        val ids = ops.associate { it.key to (initial.ids[it.key] ?: newId()) }
        var s = initial.copy(ids = ids).also { it.write(state) }
        var pushed = 0
        for (batch in ops.chunked(BATCH)) {
            val body = JsonObject(
                mapOf(
                    "changes" to JsonArray(
                        batch.map {
                            JsonObject(
                                mapOf(
                                    "op" to JsonPrimitive(if (it.add) "ADD" else "DELETE"), "languageCode" to JsonPrimitive(it.language),
                                    "word" to JsonPrimitive(it.word), "clientChangeId" to JsonPrimitive(ids.getValue(it.key)),
                                ),
                            )
                        },
                    ),
                ),
            )
            http.post("/me/dictionary/changes", body)
            // Acknowledged (applied or rejected): the server state now covers these words.
            val snap = s.snapshot.mapValues { it.value.toMutableSet() }.toMutableMap()
            for (o in batch) {
                val set = snap.getOrPut(o.language) { HashSet() }
                if (o.add) set += o.word else set -= o.word
            }
            s = s.copy(snapshot = snap, ids = s.ids - batch.map { it.key }.toSet())
            s.write(state)
            pushed += batch.size
        }
        return pushed
    }

    // ------------------------------------------------------------------ pull

    private class Change(val add: Boolean, val language: String, val word: String)

    private fun parseChange(o: JsonObject): Change? {
        val op = (o["op"] as? JsonPrimitive)?.contentOrNull ?: return null
        val lang = (o["languageCode"] as? JsonPrimitive)?.contentOrNull?.takeIf { it in languages } ?: return null
        val word = (o["word"] as? JsonPrimitive)?.contentOrNull?.let(::nfc)?.takeIf { it.isNotEmpty() } ?: return null
        return Change(op == "ADD", lang, word)
    }

    /** Words with a local change that the snapshot does not cover yet: they must not be overwritten by a pull. */
    private fun pendingLocal(snapshot: Map<String, Set<String>>): Set<String> {
        val out = HashSet<String>()
        for (l in languages) {
            val now = local.words(l).map(::nfc).toSet()
            val snap = snapshot[l].orEmpty()
            for (w in now - snap) out += "$l|$w"
            for (w in snap - now) out += "$l|$w"
        }
        return out
    }

    private fun applyIncremental(changes: List<Change>): Pair<Int, Int> {
        if (changes.isEmpty()) return 0 to 0
        var s = DictState.read(state)
        val skip = pendingLocal(s.snapshot)
        val snap = s.snapshot.mapValues { it.value.toMutableSet() }.toMutableMap()
        var added = 0
        var removed = 0
        for (c in changes) {
            if ("${c.language}|${c.word}" in skip) continue
            val set = snap.getOrPut(c.language) { HashSet() }
            if (c.add) {
                if (c.word in set) continue // already known on both sides (the echo of our own push)
                if (local.add(c.language, c.word)) {
                    set += c.word
                    added++
                }
            } else {
                if (local.forget(c.language, c.word)) removed++
                set -= c.word
            }
        }
        s = s.copy(snapshot = snap)
        s.write(state)
        return added to removed
    }

    private fun applyFull(remote: Map<String, Set<String>>): Pair<Int, Int> {
        val s = DictState.read(state)
        val skip = pendingLocal(s.snapshot)
        val snap = HashMap<String, MutableSet<String>>()
        var added = 0
        var removed = 0
        for (l in languages) {
            val localNow = local.words(l).map(::nfc).toSet()
            val onServer = remote[l].orEmpty()
            val previous = s.snapshot[l].orEmpty()
            val next = HashSet<String>()
            for (w in onServer) {
                if ("$l|$w" in skip) { if (w in previous) next += w; continue }
                if (w in localNow || local.add(l, w)) {
                    if (w !in localNow) added++
                    next += w
                }
            }
            for (w in previous - onServer) {
                // Synced before, gone from the server: deleted elsewhere (unless changed here meanwhile).
                if ("$l|$w" in skip) continue
                if (local.forget(l, w)) removed++
            }
            snap[l] = next
        }
        s.copy(snapshot = snap).write(state)
        return added to removed
    }

    private fun isResyncRequired(e: ApiError.Http): Boolean =
        e.status == 410 || e.code.contains("RESYNC", ignoreCase = true) || e.status == 400

    // ------------------------------------------------------------------ helpers

    private class Op(val add: Boolean, val language: String, val word: String) {
        val key: String get() = "${if (add) "A" else "D"}|$language|$word"
    }

    private fun nfc(w: String) = Normalizer.normalize(w, Normalizer.Form.NFC)

    private fun valid(w: String): Boolean =
        w.isNotEmpty() && w.codePointCount(0, w.length) <= MAX_WORD && w.none { it.isWhitespace() || it.isISOControl() }

    companion object {
        val LANGUAGES = listOf("am", "ti", "om", "en")
        const val BATCH = 500
        const val PAGE = 500
        const val MAX_WORD = 64
        private const val MAX_PAGES = 400
    }
}

/** Dictionary part of the sync state: cursor, snapshot of server-known words, ids of unacknowledged changes. */
internal data class DictState(
    val cursor: String?,
    val snapshot: Map<String, Set<String>>,
    val ids: Map<String, String>,
) {
    fun write(state: SyncStateStore) {
        val consent = state.section(SECTION)["consent"]
        val o = buildMap<String, kotlinx.serialization.json.JsonElement> {
            cursor?.let { put("cursor", JsonPrimitive(it)) }
            put("snapshot", JsonObject(snapshot.mapValues { (_, v) -> JsonArray(v.sorted().map { JsonPrimitive(it) }) }))
            put("ids", JsonObject(ids.mapValues { JsonPrimitive(it.value) }))
            if (consent != null) put("consent", consent)
        }
        state.put(SECTION, JsonObject(o))
    }

    companion object {
        const val SECTION = "dictionary"

        fun read(state: SyncStateStore): DictState {
            val o = state.section(SECTION)
            val snap = (o["snapshot"] as? JsonObject).orEmpty().mapValues { (_, v) ->
                (v as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
            }
            val ids = (o["ids"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
            return DictState((o["cursor"] as? JsonPrimitive)?.contentOrNull, snap, ids)
        }
    }
}

/**
 * The `DICTIONARY_SYNC` consent as the settings switch sees it. [set] records the decision with
 * `PUT /me/consents/DICTIONARY_SYNC` (`details.surface = settings`) and remembers it locally; the
 * dictionary sync runs only while the flag is on. Switching off keeps the cursor and snapshot, so
 * switching it on later continues instead of resurrecting words deleted elsewhere in the meantime.
 * [refresh] reads the server's latest decision (a new device whose account already granted it).
 * Guests may record consents but cannot sync dictionaries; the UI should not offer the switch to them.
 */
class DictionaryConsent(private val http: SyncHttp, private val state: SyncStateStore) {
    val enabled: Boolean get() = (state.section(DictState.SECTION)["consent"] as? JsonPrimitive)?.booleanOrNull ?: false

    suspend fun set(granted: Boolean) {
        http.put(
            "/me/consents/DICTIONARY_SYNC",
            JsonObject(
                mapOf(
                    "consentVersion" to JsonPrimitive(CONSENT_VERSION),
                    "isGranted" to JsonPrimitive(granted),
                    "details" to JsonObject(mapOf("surface" to JsonPrimitive("settings"))),
                ),
            ),
        )
        remember(granted)
    }

    /** Adopts the server's latest `DICTIONARY_SYNC` decision. */
    suspend fun refresh(): Boolean {
        val items = (http.get("/me/consents") as? JsonObject)?.get("items") as? JsonArray ?: throw ApiError.Unexpected(null)
        val latest = items.mapNotNull { it as? JsonObject }.filter { (it["consentCode"] as? JsonPrimitive)?.contentOrNull == "DICTIONARY_SYNC" }
            .maxByOrNull { (it["recordedAt"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
        val granted = (latest?.get("isGranted") as? JsonPrimitive)?.booleanOrNull ?: false
        remember(granted)
        return granted
    }

    internal fun remember(granted: Boolean) {
        val cur = state.section(DictState.SECTION)
        state.put(DictState.SECTION, JsonObject(cur + ("consent" to JsonPrimitive(granted))))
    }

    companion object {
        /** Version of the consent text shown in settings. */
        const val CONSENT_VERSION = "1"
    }
}
