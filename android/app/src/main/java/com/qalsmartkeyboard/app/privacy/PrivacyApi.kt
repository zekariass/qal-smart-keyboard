package com.qalsmartkeyboard.app.privacy

import com.qalsmartkeyboard.api.ApiClients
import com.qalsmartkeyboard.api.ApiError
import com.qalsmartkeyboard.api.ApiErrors
import com.qalsmartkeyboard.api.apiCall
import com.qalsmartkeyboard.sync.SyncHttp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import java.io.OutputStream
import java.time.OffsetDateTime

/** One consent decision (`Consent` in the contract). */
data class ConsentRecord(val code: String, val version: String, val granted: Boolean, val recordedAtMs: Long?)

data class Page<T>(val items: List<T>, val nextCursor: String?)

/** `HistoryItem`. The texts are shown on screen only: never logged. */
data class HistoryEntry(
    val id: String,
    val featureCode: String,
    val sourceLanguage: String?,
    val destinationLanguage: String?,
    val inputText: String,
    val outputText: String,
    val favorite: Boolean,
    val createdAtMs: Long?,
    val expiresAtMs: Long?,
)

/** The history members of `/me/preferences`. */
data class HistoryPrefs(val enabled: Boolean, val retentionDays: Int)

/** What the plan allows: feature `HISTORY_SYNC` of `/me/entitlements`. */
data class PlanInfo(val planName: String?, val hasHistorySync: Boolean)

enum class ExportStatus { REQUESTED, PROCESSING, READY, EXPIRED, FAILED, UNKNOWN }

data class DataExportInfo(val id: String, val status: ExportStatus, val requestedAtMs: Long?, val readyAtMs: Long?, val downloadExpiresAtMs: Long?) {
    val inFlight: Boolean get() = status == ExportStatus.REQUESTED || status == ExportStatus.PROCESSING
}

data class DeletionInfo(val id: String, val status: String, val requestedAtMs: Long?, val scheduledPurgeAtMs: Long?, val activeStoreSubscriptions: Int) {
    /** After `PROCESSING` the purge has started and the request can no longer be cancelled. */
    val cancellable: Boolean get() = status == "SCHEDULED" || status == "REQUESTED"
}

/**
 * The calls of the privacy screens (A2.6) over the app's shared OkHttp stack (bearer, client
 * headers, refresh authenticator). JSON is read by hand like in `:sync`, so members this app does
 * not know survive a full-replace `PUT /me/preferences`. Failures are [ApiError]s.
 *
 * Nothing here logs: bodies carry history texts, consent details and export contents.
 */
class PrivacyApi(private val http: SyncHttp, private val clients: ApiClients) {

    // ---- consents (B3.5)

    suspend fun consents(): List<ConsentRecord> {
        val items = (http.get("/me/consents") as? JsonObject)?.get("items") as? JsonArray ?: throw ApiError.Unexpected(null)
        return items.mapNotNull { consent(it) }
    }

    /** Appends a decision. [surface] is where the user made it (`details.surface`). */
    suspend fun setConsent(code: String, granted: Boolean, surface: String): ConsentRecord {
        val answer = http.put(
            "/me/consents/$code",
            buildJsonObject {
                put("consentVersion", CONSENT_VERSION)
                put("isGranted", granted)
                putJsonObject("details") { put("surface", surface) }
            },
        )
        return consent(answer) ?: throw ApiError.Unexpected(null)
    }

    /** Oldest first (append-only history). */
    suspend fun consentHistory(cursor: String?, limit: Int = PAGE): Page<ConsentRecord> =
        page("/me/consents/history", cursor, limit) { consent(it) }

    // ---- preferences / entitlements (history gate)

    suspend fun historyPrefs(): HistoryPrefs {
        val doc = preferences()
        return HistoryPrefs(
            enabled = (doc["historyEnabled"] as? JsonPrimitive)?.booleanOrNull ?: false,
            retentionDays = (doc["historyRetentionDays"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_RETENTION_DAYS,
        )
    }

    /**
     * Changes the history members of the preferences document. The PUT replaces the whole document,
     * so the other members are carried through; a stale revision (409 `REVISION_CONFLICT`) is
     * re-read and sent again, at most [attempts] times.
     */
    suspend fun saveHistoryPrefs(enabled: Boolean, retentionDays: Int, attempts: Int = 3): HistoryPrefs {
        require(retentionDays in RETENTION_RANGE) { "retention out of range" }
        var left = attempts
        while (true) {
            val doc = preferences()
            val body = JsonObject(
                doc.filterKeys { it != "updatedAt" } +
                    ("historyEnabled" to JsonPrimitive(enabled)) + ("historyRetentionDays" to JsonPrimitive(retentionDays)),
            )
            try {
                http.put("/me/preferences", body)
                return HistoryPrefs(enabled, retentionDays)
            } catch (e: ApiError.Http) {
                if (e.status == 409 && e.code == "REVISION_CONFLICT" && --left > 0) continue
                throw e
            }
        }
    }

    private suspend fun preferences(): JsonObject = http.get("/me/preferences") as? JsonObject ?: throw ApiError.Unexpected(null)

    suspend fun plan(): PlanInfo {
        val doc = http.get("/me/entitlements") as? JsonObject ?: throw ApiError.Unexpected(null)
        val features = (doc["features"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val history = features.firstOrNull { (it["featureCode"] as? JsonPrimitive)?.contentOrNull == "HISTORY_SYNC" }
        return PlanInfo(
            planName = ((doc["plan"] as? JsonObject)?.get("displayName") as? JsonPrimitive)?.contentOrNull,
            hasHistorySync = (history?.get("accessEnabled") as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }

    // ---- history (B5.7)

    suspend fun history(cursor: String?, limit: Int = PAGE): Page<HistoryEntry> = page("/me/history", cursor, limit) { entry(it) }

    suspend fun setFavorite(id: String, favorite: Boolean): HistoryEntry =
        entry(http.patch("/me/history/$id", buildJsonObject { put("favorite", favorite) })) ?: throw ApiError.Unexpected(null)

    suspend fun deleteHistoryItem(id: String) = http.delete("/me/history/$id")

    suspend fun clearHistory() = http.delete("/me/history")

    // ---- data export (B6.1)

    /** Queues an export, or returns the one already in flight (same 202 answer). */
    suspend fun requestExport(): DataExportInfo =
        export(http.post("/me/data-exports", buildJsonObject { put("format", "JSON") })) ?: throw ApiError.Unexpected(null)

    suspend fun export(id: String): DataExportInfo = export(http.get("/me/data-exports/$id")) ?: throw ApiError.Unexpected(null)

    /**
     * Streams the archive of a `READY` export (the authenticated endpoint, never a bucket URL) into
     * [sink] and returns the number of bytes. The caller closes [sink].
     */
    suspend fun downloadExport(id: String, sink: OutputStream): Long = apiCall {
        val request = Request.Builder()
            .url(clients.config.baseUrl.trimEnd('/') + "/api/v1/me/data-exports/$id/archive")
            .header("Accept", "application/json")
            .build()
        clients.okHttpClient.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw ApiErrors.fromHttp(r.code, r.body?.string(), r.headers.toMultimap())
            val body = r.body ?: throw ApiError.Unexpected(r.code)
            val bytes = body.byteStream().use { it.copyTo(sink) }
            sink.flush()
            bytes
        }
    }

    // ---- account deletion (B6.2)

    suspend fun requestDeletion(): DeletionInfo =
        deletion(http.post("/me/deletion-request", buildJsonObject { put("confirm", true) })) ?: throw ApiError.Unexpected(null)

    /** The pending request, or null when nothing is pending (404). */
    suspend fun pendingDeletion(): DeletionInfo? = try {
        deletion(http.get("/me/deletion-request"))
    } catch (e: ApiError.Http) {
        if (e.status == 404) null else throw e
    }

    suspend fun cancelDeletion() = http.delete("/me/deletion-request")

    // ---- parsing

    private suspend fun <T> page(path: String, cursor: String?, limit: Int, parse: (JsonElement?) -> T?): Page<T> {
        val query = buildMap {
            put("limit", limit.toString())
            cursor?.let { put("cursor", it) }
        }
        val doc = http.get(path, query) as? JsonObject ?: throw ApiError.Unexpected(null)
        val items = (doc["items"] as? JsonArray ?: throw ApiError.Unexpected(null)).mapNotNull { parse(it) }
        return Page(items, (doc["nextCursor"] as? JsonPrimitive)?.contentOrNull)
    }

    companion object {
        /** Version of the consent texts shown in this build (sent as `consentVersion`). */
        const val CONSENT_VERSION = "1"
        const val PAGE = 20
        const val DEFAULT_RETENTION_DAYS = 30
        val RETENTION_RANGE = 1..365

        private fun millis(o: JsonObject, name: String): Long? =
            (o[name] as? JsonPrimitive)?.contentOrNull?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

        private fun str(o: JsonObject, name: String) = (o[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        internal fun consent(e: JsonElement?): ConsentRecord? {
            val o = e as? JsonObject ?: return null
            return ConsentRecord(
                code = str(o, "consentCode") ?: return null,
                version = str(o, "consentVersion").orEmpty(),
                granted = (o["isGranted"] as? JsonPrimitive)?.booleanOrNull ?: return null,
                recordedAtMs = millis(o, "recordedAt"),
            )
        }

        internal fun entry(e: JsonElement?): HistoryEntry? {
            val o = e as? JsonObject ?: return null
            return HistoryEntry(
                id = str(o, "id") ?: return null,
                featureCode = str(o, "featureCode").orEmpty(),
                sourceLanguage = str(o, "sourceLanguage"),
                destinationLanguage = str(o, "destinationLanguage"),
                inputText = str(o, "inputText").orEmpty(),
                outputText = str(o, "outputText").orEmpty(),
                favorite = (o["favorite"] as? JsonPrimitive)?.booleanOrNull ?: false,
                createdAtMs = millis(o, "createdAt"),
                expiresAtMs = millis(o, "expiresAt"),
            )
        }

        internal fun export(e: JsonElement?): DataExportInfo? {
            val o = e as? JsonObject ?: return null
            return DataExportInfo(
                id = str(o, "id") ?: return null,
                status = ExportStatus.entries.firstOrNull { it.name == str(o, "status") } ?: ExportStatus.UNKNOWN,
                requestedAtMs = millis(o, "requestedAt"),
                readyAtMs = millis(o, "readyAt"),
                downloadExpiresAtMs = millis(o, "downloadExpiresAt"),
            )
        }

        internal fun deletion(e: JsonElement?): DeletionInfo? {
            val o = e as? JsonObject ?: return null
            return DeletionInfo(
                id = str(o, "id") ?: return null,
                status = str(o, "status").orEmpty(),
                requestedAtMs = millis(o, "requestedAt"),
                scheduledPurgeAtMs = millis(o, "scheduledPurgeAt"),
                activeStoreSubscriptions = (o["activeStoreSubscriptions"] as? JsonArray)?.size ?: 0,
            )
        }
    }
}
