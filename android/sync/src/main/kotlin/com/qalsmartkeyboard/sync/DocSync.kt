package com.qalsmartkeyboard.sync

import com.qalsmartkeyboard.api.ApiError
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** What a merge decided for one run of a revisioned document (preferences, typing languages, app rules). */
internal class DocPlan(
    /** The full document to PUT, without `revision` (members this app does not own are the server's). */
    val body: JsonObject,
    /** False when the merged document equals what the server has: nothing to send. */
    val needsPut: Boolean,
    /** Applies the merge result to the local store (field-guarded against edits made meanwhile). */
    val apply: suspend () -> Unit,
)

internal enum class DocResult { UNCHANGED, SYNCED }

/** Raised when every bounded retry hit `REVISION_CONFLICT`. */
class RevisionConflictExhausted : RuntimeException("revision conflict persisted")

/**
 * The revision protocol shared by the three revisioned documents (B3.3, B3.4): every PUT is a full
 * replace carrying `revision`; a stale revision is 409 `REVISION_CONFLICT`.
 *
 * Run: with a remembered server document, PUT optimistically with its revision when the merge says
 * there is something to send, else GET to see whether the server moved. On 409 (or without a
 * remembered document) GET the server document, run the merge again on it (the merge base stays
 * the last synced document, so "what did I change since the last sync" is stable across retries)
 * and PUT with the revision just read. At most [maxAttempts] PUTs, then [RevisionConflictExhausted].
 * After a success the answer (or the unchanged server document) is remembered as the next base.
 */
internal class DocSync(
    private val http: SyncHttp,
    private val state: SyncStateStore,
    private val path: String,
    private val section: String,
    private val maxAttempts: Int = MAX_ATTEMPTS,
) {
    /** The remembered revision and server document, or null before the first sync. */
    fun lastDoc(): JsonObject? = state.section(section)["doc"] as? JsonObject

    private fun lastRevision(): Long? = (state.section(section)["revision"] as? JsonPrimitive)?.longOrNull

    suspend fun run(plan: (server: JsonObject) -> DocPlan): DocResult {
        val remembered = lastDoc()
        val rememberedRevision = lastRevision()
        var server: JsonObject
        var revision: Long
        if (remembered != null && rememberedRevision != null) {
            val p = plan(remembered)
            if (!p.needsPut) {
                server = fetch()
                revision = revisionOf(server)
                if (revision == rememberedRevision) return DocResult.UNCHANGED
            } else {
                server = remembered
                revision = rememberedRevision
            }
        } else {
            server = fetch()
            revision = revisionOf(server)
        }
        var attempt = 0
        while (true) {
            val p = plan(server)
            if (!p.needsPut) {
                remember(revision, server)
                p.apply()
                return DocResult.SYNCED
            }
            attempt++
            val answer = try {
                http.put(path, JsonObject(p.body + ("revision" to JsonPrimitive(revision)))) as? JsonObject
            } catch (e: ApiError.Http) {
                if (e.status == 409 && e.code == "REVISION_CONFLICT") {
                    if (attempt >= maxAttempts) throw RevisionConflictExhausted()
                    server = fetch()
                    revision = revisionOf(server)
                    continue
                }
                throw e
            }
            val saved = answer ?: JsonObject(p.body + ("revision" to JsonPrimitive(revision + 1)))
            remember(revisionOf(saved), saved)
            p.apply()
            return DocResult.SYNCED
        }
    }

    private suspend fun fetch(): JsonObject = http.get(path) as? JsonObject ?: throw ApiError.Unexpected(null)

    private fun remember(revision: Long, doc: JsonObject) {
        state.put(section, JsonObject(mapOf("revision" to JsonPrimitive(revision), "doc" to doc)))
    }

    private fun revisionOf(doc: JsonElement): Long = ((doc as? JsonObject)?.get("revision") as? JsonPrimitive)?.longOrNull ?: 0L

    companion object {
        const val MAX_ATTEMPTS = 4
    }
}
