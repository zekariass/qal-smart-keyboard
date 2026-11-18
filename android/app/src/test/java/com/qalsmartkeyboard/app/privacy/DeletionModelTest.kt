package com.qalsmartkeyboard.app.privacy

import com.qalsmartkeyboard.app.privacy.PrivacyRig.Companion.json
import com.qalsmartkeyboard.app.privacy.PrivacyRig.Companion.problem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Account deletion: confirm word, request, pending state, cancel; what happens locally; guests. */
class DeletionModelTest {
    private val rig = PrivacyRig()
    private val wipes = mutableListOf<Boolean>()
    private val wipe = object : LocalWipe {
        override suspend fun afterDeletionRequested(signOut: Boolean) { wipes += signOut }
    }
    private lateinit var model: DeletionModel

    private fun deletion(status: String = "SCHEDULED", extra: String = "") =
        """{"id":"9a9a9a9a-1a79-4cfb-9a8e-7b8a1f4d2c10","status":"$status","requestedAt":"2026-11-18T08:00:00Z","scheduledPurgeAt":"2026-12-02T08:00:00Z"$extra}"""

    @Before fun setUp() {
        rig.start()
        model = DeletionModel(rig.api, wipe)
    }

    @After fun tearDown() { rig.stop() }

    @Test fun nothingPendingMeansTheConfirmationScreen() = runBlocking {
        rig.route = { problem(404, "NOT_FOUND") }
        model.load()
        assertEquals(DeletionPhase.IDLE, model.state.value.phase)
        assertNull(model.state.value.info)
        assertNull(model.state.value.message)
    }

    @Test fun aPendingRequestShowsTheScheduledDateAndStoreSubscriptions() = runBlocking {
        rig.route = { json(200, deletion(extra = ""","activeStoreSubscriptions":[{"id":"x","status":"ACTIVE"}]""")) }
        model.load()
        val s = model.state.value
        assertEquals(DeletionPhase.PENDING, s.phase)
        assertEquals(1_796_198_400_000L, s.info!!.scheduledPurgeAtMs) // 2026-12-02T08:00:00Z
        assertEquals(1, s.info!!.activeStoreSubscriptions)
        assertTrue(s.info!!.cancellable)
    }

    @Test fun theWordMustBeTypedAndNothingIsSentOtherwise() = runBlocking {
        rig.route = { json(202, deletion()) }
        for (typed in listOf("", "delet", "DELETE ME", "cancel")) {
            model.request(typed, "DELETE", isGuest = false)
            assertEquals(PrivacyMessage.WordMismatch, model.state.value.message)
        }
        assertTrue(rig.requests.isEmpty())
        assertTrue(wipes.isEmpty())
        assertEquals(DeletionPhase.UNKNOWN, model.state.value.phase)
    }

    @Test fun confirmedRequestOfARegisteredAccountSchedulesSignsOutAndWipes() = runBlocking {
        rig.route = { json(202, deletion(extra = ""","activeStoreSubscriptions":[]""")) }
        model.request("  delete ", "DELETE", isGuest = false)
        assertEquals(listOf("POST /me/deletion-request"), rig.calls())
        assertEquals("true", Json.parseToJsonElement(rig.bodyOf("POST", "/me/deletion-request")).jsonObject["confirm"]!!.jsonPrimitive.content)
        val s = model.state.value
        assertEquals(DeletionPhase.SCHEDULED_SIGNED_OUT, s.phase)
        assertEquals(1_796_198_400_000L, s.info!!.scheduledPurgeAtMs)
        assertEquals(listOf(true), wipes)
        // The signed-out screen stays until acknowledged (load does not overwrite it).
        model.load()
        assertEquals(DeletionPhase.SCHEDULED_SIGNED_OUT, model.state.value.phase)
        model.acknowledge()
        assertEquals(DeletionPhase.UNKNOWN, model.state.value.phase)
    }

    @Test fun aGuestStaysSignedInSoItCanCancelThenCancelWorks() = runBlocking {
        rig.route = { r -> if (r.method == "POST") json(202, deletion()) else MockResponse().setResponseCode(204) }
        model.request("DELETE", "DELETE", isGuest = true)
        assertEquals(DeletionPhase.PENDING, model.state.value.phase)
        assertEquals(listOf(false), wipes)
        model.cancel()
        assertEquals(DeletionPhase.IDLE, model.state.value.phase)
        assertNull(model.state.value.info)
        assertEquals(listOf("POST /me/deletion-request", "DELETE /me/deletion-request"), rig.calls())
    }

    @Test fun cancelAfterSigningInAgainDuringTheGracePeriod() = runBlocking {
        rig.route = { r -> if (r.method == "GET") json(200, deletion()) else MockResponse().setResponseCode(204) }
        model.load()
        assertEquals(DeletionPhase.PENDING, model.state.value.phase)
        model.cancel()
        assertEquals(DeletionPhase.IDLE, model.state.value.phase)
    }

    @Test fun cancelOnceThePurgeStartedIsRefusedAndShownAsStarted() = runBlocking {
        var processing = false
        rig.route = { r ->
            when (r.method) {
                "DELETE" -> { processing = true; problem(409, "DELETION_PENDING") }
                else -> json(200, deletion(if (processing) "PROCESSING" else "SCHEDULED"))
            }
        }
        model.load()
        model.cancel()
        val s = model.state.value
        assertEquals(DeletionPhase.PENDING, s.phase)
        assertEquals(PrivacyMessage.DeletionPending, s.message)
        assertFalse(s.info!!.cancellable)
    }

    @Test fun requestingWhileOneIsPendingJustShowsIt() = runBlocking {
        rig.route = { r -> if (r.method == "POST") problem(409, "DELETION_PENDING") else json(200, deletion()) }
        model.request("DELETE", "DELETE", isGuest = false)
        assertEquals(DeletionPhase.PENDING, model.state.value.phase)
        assertNotNull(model.state.value.info)
        assertTrue("no wipe: nothing new was requested", wipes.isEmpty())
    }

    @Test fun failuresLeaveTheAccountAndTheDeviceAsTheyWere() = runBlocking {
        rig.route = { problem(403, "FORBIDDEN") }
        model.request("DELETE", "DELETE", isGuest = false)
        assertEquals(DeletionPhase.IDLE, model.state.value.phase)
        assertEquals(PrivacyMessage.Forbidden, model.state.value.message)
        assertTrue(wipes.isEmpty())
        rig.route = { MockResponse().setResponseCode(500) }
        model.request("DELETE", "DELETE", isGuest = false)
        assertEquals(PrivacyMessage.Failed, model.state.value.message)
        assertTrue(wipes.isEmpty())
    }

    @Test fun cancelFailureKeepsThePendingState() = runBlocking {
        rig.route = { r -> if (r.method == "GET") json(200, deletion()) else MockResponse().setResponseCode(500) }
        model.load()
        model.cancel()
        assertEquals(DeletionPhase.PENDING, model.state.value.phase)
        assertEquals(PrivacyMessage.Failed, model.state.value.message)
    }
}
