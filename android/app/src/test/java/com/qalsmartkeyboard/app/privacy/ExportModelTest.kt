package com.qalsmartkeyboard.app.privacy

import com.qalsmartkeyboard.app.privacy.PrivacyRig.Companion.EXPORT_ID
import com.qalsmartkeyboard.app.privacy.PrivacyRig.Companion.export
import com.qalsmartkeyboard.app.privacy.PrivacyRig.Companion.json
import com.qalsmartkeyboard.app.privacy.PrivacyRig.Companion.problem
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/** Export: request -> poll -> download into a fake document; 429; the one open export; expiry. */
class ExportModelTest {
    private val rig = PrivacyRig()
    private val memory = MemoryExportMemory()
    private val pauses = mutableListOf<Long>()
    private var clock = 1_000_000L
    private lateinit var model: ExportModel

    /** Unicode and a long tail: the archive is streamed, not read as text. */
    private val archive = ("""{"manifest":{"format":"qal-data-export"},"data":{"history":[{"outputText":"ሰላም"}]}}""" + " ".repeat(200_000)).toByteArray()

    @Before fun setUp() {
        rig.start()
        model = ExportModel(rig.api, memory, pause = { pauses += it }, now = { clock })
    }

    @After fun tearDown() { rig.stop() }

    private fun archiveResponse() =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
            .setHeader("Content-Disposition", "attachment; filename=\"export.json\"").setHeader("Cache-Control", "no-store")
            .setBody(Buffer().write(archive))

    @Test fun requestPollAndDownloadWritesTheArchiveToTheChosenDocument() = runBlocking {
        var statusReads = 0
        rig.route = { r ->
            val path = r.path!!.removePrefix("/api/v1")
            when {
                r.method == "POST" && path == "/me/data-exports" -> json(202, export(EXPORT_ID, "REQUESTED"))
                path == "/me/data-exports/$EXPORT_ID" -> {
                    statusReads++
                    when (statusReads) {
                        1 -> json(200, export(EXPORT_ID, "PROCESSING"))
                        else -> json(200, export(EXPORT_ID, "READY", ""","readyAt":"2026-11-18T08:03:00Z","downloadUrl":"/api/v1/me/data-exports/$EXPORT_ID/archive","downloadExpiresAt":"2026-11-25T08:03:00Z""""))
                    }
                }
                path == "/me/data-exports/$EXPORT_ID/archive" -> archiveResponse()
                else -> MockResponse().setResponseCode(404)
            }
        }
        model.request()
        assertEquals(ExportPhase.IN_PROGRESS, model.state.value.phase)
        assertEquals(EXPORT_ID, memory.lastId)
        assertEquals("""{"format":"JSON"}""", rig.bodyOf("POST", "/me/data-exports"))

        model.pollUntilDone()
        assertEquals(ExportPhase.READY, model.state.value.phase)
        assertEquals(listOf(3_000L, 3_000L), pauses)
        assertNotNull(model.state.value.export!!.downloadExpiresAtMs)
        assertEquals(1_795_593_780_000L, model.state.value.export!!.downloadExpiresAtMs)

        val document = ByteArrayOutputStream()
        var closed = false
        model.download(open = { object : FilterStream(document) { override fun close() { closed = true; super.close() } } })
        assertEquals(ExportPhase.SAVED, model.state.value.phase)
        assertEquals(archive.size.toLong(), model.state.value.savedBytes)
        assertArrayEquals(archive, document.toByteArray())
        assertTrue("the document is closed", closed)
        val dl = rig.requests.last()
        assertEquals("/api/v1/me/data-exports/$EXPORT_ID/archive", dl.path)
        assertEquals("the authenticated endpoint, with the owner's token", "Bearer access-token", dl.getHeader("Authorization"))
    }

    @Test fun cancellingThePickerDoesNothing() = runBlocking {
        rig.route = { json(202, export(EXPORT_ID, "READY", ""","downloadExpiresAt":"2026-11-25T08:03:00Z"""")) }
        model.request()
        assertEquals(ExportPhase.READY, model.state.value.phase)
        val before = rig.requests.size
        model.download(open = { null })
        assertEquals(ExportPhase.READY, model.state.value.phase)
        assertEquals(before, rig.requests.size)
    }

    @Test fun rateLimitShowsWhenToTryAgainAndBlocksUntilThen() = runBlocking {
        rig.route = { problem(429, "RATE_LIMITED", "", "Retry-After" to "3600") }
        model.request()
        var s = model.state.value
        assertEquals(PrivacyMessage.RateLimited(3600), s.message)
        assertEquals(clock + 3_600_000, s.retryAtMs)
        assertEquals(ExportPhase.FAILED, s.phase)
        val calls = rig.requests.size
        clock += 1_000
        model.request()
        assertEquals("no new call while limited", calls, rig.requests.size)
        // After the wait a new request goes out again.
        clock += 3_600_000
        rig.route = { json(202, export(EXPORT_ID, "REQUESTED")) }
        model.request()
        s = model.state.value
        assertEquals(ExportPhase.IN_PROGRESS, s.phase)
        assertNull(s.retryAtMs)
        assertNull(s.message)
    }

    @Test fun rateLimitWithoutRetryAfterStillExplains() = runBlocking {
        rig.route = { problem(429, "RATE_LIMITED") }
        model.request()
        assertEquals(PrivacyMessage.RateLimited(null), model.state.value.message)
        assertNull(model.state.value.retryAtMs)
    }

    @Test fun askingAgainWhileOneIsRunningShowsTheSameExport() = runBlocking {
        memory.lastId = EXPORT_ID // started earlier (this or another visit)
        rig.route = { json(202, export(EXPORT_ID, "PROCESSING")) }
        model.request()
        assertEquals(ExportPhase.IN_PROGRESS, model.state.value.phase)
        assertTrue(model.state.value.alreadyRunning)
        assertEquals(EXPORT_ID, model.state.value.export!!.id)
    }

    @Test fun aFreshExportIsNotCalledAlreadyRunning() = runBlocking {
        rig.route = { json(202, export(EXPORT_ID, "REQUESTED")) }
        model.request()
        assertFalse(model.state.value.alreadyRunning)
    }

    @Test fun resumePicksUpTheRememberedExportAndForgetsAGoneOne() = runBlocking {
        memory.lastId = EXPORT_ID
        rig.route = { json(200, export(EXPORT_ID, "PROCESSING")) }
        model.resume()
        assertEquals(ExportPhase.IN_PROGRESS, model.state.value.phase)

        val other = ExportModel(rig.api, MemoryExportMemory(EXPORT_ID), pause = {}, now = { clock })
        rig.route = { problem(404, "NOT_FOUND") }
        other.resume()
        assertEquals(ExportPhase.IDLE, other.state.value.phase)
    }

    @Test fun anExpiredExportOffersANewRequest() = runBlocking {
        memory.lastId = EXPORT_ID
        rig.route = { json(200, export(EXPORT_ID, "EXPIRED")) }
        model.resume()
        assertEquals(ExportPhase.EXPIRED, model.state.value.phase)
        rig.route = { json(202, export("6c6c6c6c-1a79-4cfb-9a8e-7b8a1f4d2c10", "REQUESTED")) }
        model.request()
        assertEquals(ExportPhase.IN_PROGRESS, model.state.value.phase)
    }

    @Test fun anArchiveThatIsGoneByDownloadTimeBecomesExpired() = runBlocking {
        rig.route = { r ->
            val path = r.path!!.removePrefix("/api/v1")
            when {
                r.method == "POST" -> json(202, export(EXPORT_ID, "READY", ""","downloadExpiresAt":"2026-11-25T08:03:00Z""""))
                path.endsWith("/archive") -> problem(404, "NOT_FOUND")
                else -> json(200, export(EXPORT_ID, "EXPIRED"))
            }
        }
        model.request()
        var discarded = 0
        model.download(open = { ByteArrayOutputStream() }, discard = { discarded++ })
        assertEquals(ExportPhase.EXPIRED, model.state.value.phase)
        assertEquals(1, discarded)
    }

    @Test fun aFullOrRevokedDocumentIsReportedAsASaveFailureNotAsOffline() = runBlocking {
        rig.route = { r ->
            if (r.method == "POST") json(202, export(EXPORT_ID, "READY", ""","downloadExpiresAt":"2026-11-25T08:03:00Z""""))
            else archiveResponse()
        }
        model.request()
        var discarded = 0
        val broken = object : OutputStream() {
            override fun write(b: Int) = throw IOException("disk full")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("disk full")
        }
        model.download(open = { broken }, discard = { discarded++ })
        assertEquals(ExportPhase.READY, model.state.value.phase)
        assertEquals(PrivacyMessage.SaveFailed, model.state.value.message)
        assertEquals(1, discarded)
        // Choosing another place works.
        val document = ByteArrayOutputStream()
        model.download(open = { document })
        assertEquals(ExportPhase.SAVED, model.state.value.phase)
        assertEquals(archive.size, document.size())
    }

    @Test fun aNetworkFailureWhileDownloadingKeepsTheExportDownloadable() = runBlocking {
        rig.route = { r ->
            if (r.method == "POST") json(202, export(EXPORT_ID, "READY", ""","downloadExpiresAt":"2026-11-25T08:03:00Z""""))
            else MockResponse().setResponseCode(500)
        }
        model.request()
        model.download(open = { ByteArrayOutputStream() })
        assertEquals(ExportPhase.READY, model.state.value.phase)
        assertEquals(PrivacyMessage.Failed, model.state.value.message)
    }

    @Test fun aFailedExportIsReportedAndCanBeRetried() = runBlocking {
        rig.route = { json(202, export(EXPORT_ID, "FAILED")) }
        model.request()
        assertEquals(ExportPhase.FAILED, model.state.value.phase)
        assertEquals(PrivacyMessage.Failed, model.state.value.message)
        rig.route = { json(202, export("7d7d7d7d-1a79-4cfb-9a8e-7b8a1f4d2c10", "REQUESTED")) }
        model.request()
        assertEquals(ExportPhase.IN_PROGRESS, model.state.value.phase)
    }

    @Test fun pollingSlowsDownAndStopsAtItsLimit() = runBlocking {
        rig.route = { json(200, export(EXPORT_ID, "PROCESSING")) }
        memory.lastId = EXPORT_ID
        model.resume()
        model.pollUntilDone(maxPolls = 25)
        assertEquals(25, pauses.size)
        assertEquals(3_000L, pauses.first())
        assertEquals(10_000L, pauses.last())
        assertEquals(ExportPhase.IN_PROGRESS, model.state.value.phase)
    }

    @Test fun guestsAndPendingDeletionUseTheSameCallsAndGuestsGetNoSpecialPath() = runBlocking {
        // The server allows guests and DELETION_PENDING accounts (B6.1); the client sends the same request either way.
        rig.route = { json(202, export(EXPORT_ID, "REQUESTED")) }
        model.request()
        assertEquals(listOf("POST /me/data-exports"), rig.calls())
        assertEquals(ExportPhase.IN_PROGRESS, model.state.value.phase)
    }

    /** Forwards to [target]; subclassed to see the close. */
    private open class FilterStream(private val target: OutputStream) : OutputStream() {
        override fun write(b: Int) = target.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = target.write(b, off, len)
        override fun close() = target.close()
    }
}
