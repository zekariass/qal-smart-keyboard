package com.qalsmartkeyboard.app.creative.backup

import com.qalsmartkeyboard.api.generated.apis.CreativeBackupApi
import com.qalsmartkeyboard.app.privacy.PrivacyRig
import com.qalsmartkeyboard.app.privacy.PrivacyRig.Companion.json
import com.qalsmartkeyboard.app.privacy.PrivacyRig.Companion.problem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The real generated client against a mock server: the wire format, paging, and every error the contract names. */
class ApiBackupRemoteTest {
    private val rig = PrivacyRig()
    private lateinit var remote: ApiBackupRemote

    @Before fun setUp() {
        rig.start()
        remote = ApiBackupRemote(rig.clients.api(::CreativeBackupApi))
    }

    @After fun tearDown() {
        rig.stop()
    }

    private fun meta(id: String, revision: Long, size: Long = 4, updated: String = "2026-11-20T10:00:00.123Z") =
        """{"itemId":"$id","kind":"DRAWING","revision":$revision,"sizeBytes":$size,"updatedAt":"$updated","createdAt":"2026-11-19T10:00:00Z","storedAt":"2026-11-20T10:00:01Z"}"""

    private fun failure(block: suspend () -> Unit): BackupError =
        try {
            runBlocking { block() }
            throw AssertionError("expected a failure")
        } catch (e: BackupException) {
            e.error
        }

    @Test fun theSummaryIsMappedWithTheCaps() {
        rig.route = {
            json(200, """{"consentGranted":true,"itemCount":3,"totalBytes":1500,"maxItemBytes":2097152,"maxItemsPerKind":100,"maxTotalBytes":33554432,"kinds":[{"kind":"DRAWING","itemCount":3,"totalBytes":1500}]}""")
        }
        assertEquals(RemoteSummary(true, 3, 1500, 2_097_152, 100, 33_554_432), runBlocking { remote.summary() })
        assertEquals(listOf("GET /me/creative-backup/summary"), rig.calls())
    }

    @Test fun listingFollowsTheCursorAndAsksForDrawingsOnly() {
        rig.route = { r ->
            if (r.requestUrl!!.queryParameter("cursor") == null) {
                json(200, """{"items":[${meta("aaaaaa", 1)}],"nextCursor":"c2"}""")
            } else {
                json(200, """{"items":[${meta("bbbbbb", 7, 9, "2026-11-21T10:00:00Z")}]}""")
            }
        }
        val items = runBlocking { remote.list() }
        assertEquals(listOf("aaaaaa", "bbbbbb"), items.map { it.id })
        assertEquals(RemoteItem("aaaaaa", 1, 4, 1_795_168_800_123L), items[0])
        assertEquals(7L, items[1].revision)
        assertEquals("DRAWING", rig.requests[0].requestUrl!!.queryParameter("kind"))
        assertEquals("c2", rig.requests[1].requestUrl!!.queryParameter("cursor"))
    }

    @Test fun downloadDecodesTheBase64BackToTheExactBytes() {
        val bytes = byteArrayOf(0, 1, 2, -1, -128, 127, 81, 68)
        val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
        rig.route = { json(200, """{"itemId":"aaaaaa","kind":"DRAWING","revision":3,"sizeBytes":8,"updatedAt":"2026-11-20T10:00:00Z","createdAt":"2026-11-19T10:00:00Z","storedAt":"2026-11-20T10:00:01Z","content":"$b64"}""") }
        val blob = runBlocking { remote.download("aaaaaa") }
        assertArrayEquals(bytes, blob.bytes)
        assertEquals(3L, blob.item.revision)
        assertEquals("GET /me/creative-backup/items/aaaaaa", rig.calls().single())
    }

    @Test fun badBase64IsAFailureNotACrash() {
        rig.route = { json(200, """{"itemId":"aaaaaa","kind":"DRAWING","revision":3,"sizeBytes":8,"updatedAt":"2026-11-20T10:00:00Z","createdAt":"2026-11-19T10:00:00Z","storedAt":"2026-11-20T10:00:01Z","content":"@@@not base64@@@"}""") }
        assertEquals(BackupError.Failed, failure { remote.download("aaaaaa") })
    }

    @Test fun putSendsTheContractBodyAndReturnsTheServerRevision() {
        rig.route = { json(201, meta("aaaaaa", 1)) }
        val bytes = byteArrayOf(10, 20, 30, 40, 50)
        val revision = runBlocking { remote.put("aaaaaa", 1_795_168_800_123L, bytes, 0) }
        assertEquals(1L, revision)
        val body = Json.parseToJsonElement(rig.bodyOf("PUT", "/me/creative-backup/items/aaaaaa")).jsonObject
        assertEquals("DRAWING", body["kind"]!!.jsonPrimitive.content)
        assertEquals(java.util.Base64.getEncoder().encodeToString(bytes), body["content"]!!.jsonPrimitive.content)
        assertEquals(0L, body["expectedRevision"]!!.jsonPrimitive.content.toLong())
        assertEquals(1_795_168_800_123L, java.time.OffsetDateTime.parse(body["updatedAt"]!!.jsonPrimitive.content).toInstant().toEpochMilli())
        assertEquals(setOf("kind", "updatedAt", "content", "expectedRevision"), body.keys)
    }

    @Test fun putWithoutAnExpectedRevisionOmitsIt() {
        rig.route = { json(200, meta("aaaaaa", 5)) }
        assertEquals(5L, runBlocking { remote.put("aaaaaa", 1, byteArrayOf(1, 2, 3), null) })
        val body = Json.parseToJsonElement(rig.bodyOf("PUT", "/me/creative-backup/items/aaaaaa")) as JsonObject
        assertTrue("no compare-and-set", body["expectedRevision"].let { it == null || it is JsonNull })
    }

    @Test fun clearCallsTheDeleteEndpoint() {
        rig.route = { MockResponse().setResponseCode(204) }
        runBlocking { remote.clear() }
        assertEquals(listOf("DELETE /me/creative-backup"), rig.calls())
    }

    // ---- errors -----------------------------------------------------------------------------------------------------

    @Test fun everyContractErrorIsMapped() {
        fun answer(response: MockResponse): BackupError {
            rig.route = { response }
            return failure { remote.put("aaaaaa", 1, byteArrayOf(1, 2, 3), 0) }
        }
        assertEquals(BackupError.ConsentRequired, answer(problem(403, "CONSENT_REQUIRED", ""","consentCode":"CREATIVE_BACKUP"""")))
        assertEquals(BackupError.GuestNotAllowed, answer(problem(403, "GUEST_NOT_ALLOWED")))
        assertEquals(BackupError.FeatureOff, answer(problem(404, "NOT_FOUND")))
        assertEquals(BackupError.RevisionConflict(7), answer(problem(409, "REVISION_CONFLICT", ""","currentRevision":7""")))
        assertEquals(BackupError.QuotaExceeded("ITEMS_PER_KIND", 100), answer(problem(409, "BACKUP_QUOTA_EXCEEDED", ""","quota":"ITEMS_PER_KIND","limit":100""")))
        assertEquals(BackupError.QuotaExceeded("TOTAL_BYTES", 33_554_432), answer(problem(409, "BACKUP_QUOTA_EXCEEDED", ""","quota":"TOTAL_BYTES","limit":33554432""")))
        assertEquals(BackupError.TooLarge(2_097_152), answer(problem(413, "BACKUP_ITEM_TOO_LARGE", ""","maxItemBytes":2097152""")))
        assertEquals(BackupError.RateLimited(90), answer(problem(429, "RATE_LIMITED", "", "Retry-After" to "90")))
        assertEquals(BackupError.RateLimited(null), answer(problem(429, "RATE_LIMITED")))
        assertEquals(BackupError.Failed, answer(MockResponse().setResponseCode(503)))
        assertEquals(BackupError.Failed, answer(json(200, "not json")))
    }

    @Test fun aMissingItemOnDownloadIsNotFoundButOnAnyOtherCallItMeansTheFeatureIsOff() {
        rig.route = { problem(404, "NOT_FOUND") }
        assertEquals(BackupError.NotFound, failure { remote.download("aaaaaa") })
        assertEquals(BackupError.FeatureOff, failure { remote.summary() })
        assertEquals(BackupError.FeatureOff, failure { remote.list() })
        assertEquals(BackupError.FeatureOff, failure { remote.clear() })
    }

    @Test fun aServerThatCannotBeReachedIsOffline() {
        rig.stop()
        assertEquals(BackupError.Offline, failure { remote.summary() })
    }

    @Test fun aNonSessionBodyIsNeverLoggedOrKeptInTheError() {
        rig.route = { problem(409, "REVISION_CONFLICT", ""","currentRevision":2""") }
        val e = try {
            runBlocking { remote.put("aaaaaa", 1, byteArrayOf(1, 2, 3), 0) }
            null
        } catch (e: BackupException) {
            e
        }
        assertTrue(e!!.message!!.length < 60)
        assertTrue("aaaaaa" !in e.message!!)
    }
}
