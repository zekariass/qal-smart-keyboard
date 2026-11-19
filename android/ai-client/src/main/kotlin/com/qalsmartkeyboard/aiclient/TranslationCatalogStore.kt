package com.qalsmartkeyboard.aiclient

import com.qalsmartkeyboard.ime.ai.TranslationCatalog
import com.qalsmartkeyboard.ime.ai.TranslationLanguage
import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The device's copy of the translation language catalogue (ADR 0040): `GET /ai/capabilities` `translationLanguages`,
 * kept in [file] with the answer's `ETag` so a refresh is a cheap `304`. Nothing in it is personal or secret (language
 * codes and names), so it is a plain file. Before the first successful fetch, offline, and when the server lists no
 * language (no provider active, or the plan has no translation), [catalog] is [TranslationCatalog.BUNDLED]: the
 * keyboard's five languages, exactly what it offered before.
 *
 * [refresh] is blocking (call it on a background thread) and throttled to [minIntervalMs] unless forced; it never sends
 * user text. Thread-safe: the fields are replaced whole.
 */
class TranslationCatalogStore(
    private val file: File?,
    private val now: () -> Long = System::currentTimeMillis,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
) {
    @Volatile var catalog: TranslationCatalog = TranslationCatalog.BUNDLED
        private set

    @Volatile var etag: String? = null
        private set

    @Volatile private var fetchedAt: Long = 0

    private var loaded = false

    /**
     * Reads the stored copy once (disk: call off the main thread). A missing or unreadable file keeps the bundled list.
     * [refresh] loads first, so a fetch is never overwritten by an older copy read later.
     */
    @Synchronized
    fun load() {
        if (loaded) return
        loaded = true
        val f = file ?: return
        val o = try {
            if (!f.isFile) return
            Json.parseToJsonElement(f.readText()) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return
        val languages = parseLanguages(o["languages"] as? JsonArray) ?: return
        catalog = TranslationCatalog.of(languages)
        etag = (o["etag"] as? JsonPrimitive)?.contentOrNull
        fetchedAt = (o["fetchedAt"] as? JsonPrimitive)?.longOrNull ?: 0
    }

    /**
     * Fetches the catalogue when the copy is older than [minIntervalMs] (or [force]): `If-None-Match` with the stored
     * tag; a `200` replaces the copy, a `304` only renews its age. Returns true when [catalog] changed. A failure keeps
     * the copy and returns false (the next refresh tries again).
     */
    fun refresh(http: OkHttpClient, baseUrl: String, force: Boolean = false): Boolean {
        load()
        val t = now()
        if (!force && fetchedAt != 0L && t - fetchedAt < minIntervalMs) return false
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/v1/ai/capabilities")
            .header("Accept", "application/json")
            .apply { if (!force) etag?.let { header("If-None-Match", it) } }
            .get()
            .build()
        return try {
            http.newCall(request).execute().use { r ->
                when {
                    r.code == 304 -> {
                        fetchedAt = t
                        save()
                        false
                    }
                    r.isSuccessful -> apply(r.header("ETag"), r.body?.string().orEmpty(), t)
                    else -> false
                }
            }
        } catch (e: IOException) {
            false
        } catch (e: RuntimeException) {
            false // a malformed answer never breaks the keyboard: the copy stays
        }
    }

    /** A `200` body: its `translationLanguages` become the catalogue (empty or absent: the bundled list). */
    @Synchronized
    internal fun apply(newEtag: String?, body: String, at: Long = now()): Boolean {
        loaded = true
        val o = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return false
        val languages = parseLanguages(o["translationLanguages"] as? JsonArray) ?: emptyList()
        val next = TranslationCatalog.of(languages)
        val changed = next != catalog
        catalog = next
        etag = newEtag
        fetchedAt = at
        save()
        return changed
    }

    /** The next [refresh] fetches again without `If-None-Match` (another account: its plan may differ). */
    fun invalidate() {
        fetchedAt = 0
        etag = null
    }

    private fun save() {
        val f = file ?: return
        val c = catalog
        val body = buildJsonObject {
            etag?.let { put("etag", it) }
            put("fetchedAt", fetchedAt)
            put("languages", if (c.fromServer) toJson(c.languages) else JsonArray(emptyList()))
        }
        try {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(body.toString())
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (e: IOException) {
            // the copy in memory still serves this process; the next fetch writes again
        } catch (e: SecurityException) {
        }
    }

    companion object {
        /** A refresh at most every few hours unless forced: the catalogue changes with provider rows, rarely. */
        const val DEFAULT_MIN_INTERVAL_MS = 6L * 60 * 60 * 1000

        private val CODE = Regex("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$")

        /** The contract's `TranslationLanguage` items; invalid ones are dropped. Null when [a] is absent. */
        fun parseLanguages(a: JsonArray?): List<TranslationLanguage>? = a?.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val code = AiWire.str(o, "code")?.takeIf { CODE.matches(it) } ?: return@mapNotNull null
            val english = AiWire.str(o, "englishName")?.takeIf { it.isNotBlank() } ?: code
            val asSource = (o["asSource"] as? JsonPrimitive)?.booleanOrNull ?: true
            val asDestination = (o["asDestination"] as? JsonPrimitive)?.booleanOrNull ?: true
            if (!asSource && !asDestination) return@mapNotNull null
            TranslationLanguage(
                code = code,
                englishName = english,
                nativeName = AiWire.str(o, "nativeName")?.takeIf { it.isNotBlank() } ?: english,
                rtl = AiWire.str(o, "writingDirection") == "RTL",
                asSource = asSource,
                asDestination = asDestination,
                autoDetect = (o["supportsAutoDetection"] as? JsonPrimitive)?.booleanOrNull ?: false,
                typing = (o["isTypingLanguage"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }?.distinctBy { it.code }

        private fun toJson(languages: List<TranslationLanguage>): JsonArray = buildJsonArray {
            for (l in languages) {
                add(
                    buildJsonObject {
                        put("code", l.code)
                        put("englishName", l.englishName)
                        put("nativeName", l.nativeName)
                        put("writingDirection", if (l.rtl) "RTL" else "LTR")
                        put("asSource", l.asSource)
                        put("asDestination", l.asDestination)
                        put("supportsAutoDetection", l.autoDetect)
                        put("isTypingLanguage", l.typing)
                    },
                )
            }
        }
    }
}
