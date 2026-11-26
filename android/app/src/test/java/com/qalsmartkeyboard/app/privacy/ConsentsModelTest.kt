package com.qalsmartkeyboard.app.privacy

import com.qalsmartkeyboard.app.privacy.PrivacyRig.Companion.consent
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A2.6 consent flows over the real `:api` client and MockWebServer. */
class ConsentsModelTest {
    private val rig = PrivacyRig()
    private val withdrawn = mutableListOf<String>()
    private val granted = mutableListOf<String>()
    private val effects = object : ConsentEffects {
        override suspend fun withdrawn(code: String) { withdrawn += code }
        override suspend fun granted(code: String) { granted += code }
    }
    private lateinit var model: ConsentsModel

    @Before fun setUp() {
        rig.start()
        model = ConsentsModel(rig.api, effects)
    }

    @After fun tearDown() { rig.stop() }

    @Test fun loadKeepsTheLatestDecisionOfEachCode() = runBlocking {
        rig.route = {
            json(
                200,
                """{"items":[${consent("AI_PROCESSING", true, "2026-11-17T08:00:00Z")},${consent("AI_PROCESSING", false, "2026-11-18T08:00:00Z")},${consent("TERMS_ACCEPTANCE", true)}]}""",
            )
        }
        model.load()
        val s = model.state.value
        assertTrue(s.loaded)
        assertEquals(false, s.granted("AI_PROCESSING"))
        assertEquals(true, s.granted("TERMS_ACCEPTANCE"))
        assertNull(s.granted("MARKETING"))
        assertEquals(listOf("GET /me/consents"), rig.calls())
        assertEquals("Bearer access-token", rig.requests.single().getHeader("Authorization"))
        assertEquals("COMPANION", rig.requests.single().getHeader("X-Client-Surface"))
    }

    @Test fun grantSendsTheDecisionWithTheSurfaceAndTellsTheEffects() = runBlocking {
        rig.route = { json(200, consent("ANALYTICS", true)) }
        model.set("ANALYTICS", true)
        assertEquals(listOf("PUT /me/consents/ANALYTICS"), rig.calls())
        val body = Json.parseToJsonElement(rig.bodyOf("PUT", "/me/consents/ANALYTICS")).jsonObject
        assertEquals("true", body["isGranted"]!!.jsonPrimitive.content)
        assertEquals("1", body["consentVersion"]!!.jsonPrimitive.content)
        assertEquals("settings", body["details"]!!.jsonObject["surface"]!!.jsonPrimitive.content)
        assertEquals(true, model.state.value.granted("ANALYTICS"))
        assertNull(model.state.value.justWithdrawn)
        assertNull(model.state.value.busy)
        assertEquals(listOf("ANALYTICS"), granted)
    }

    @Test fun withdrawalRecordsItAndShowsWhatItDid() = runBlocking {
        rig.route = { json(200, consent("SAVE_AI_HISTORY", false)) }
        model.set("SAVE_AI_HISTORY", false)
        assertEquals(false, model.state.value.granted("SAVE_AI_HISTORY"))
        assertEquals("SAVE_AI_HISTORY", model.state.value.justWithdrawn)
        assertEquals(listOf("SAVE_AI_HISTORY"), withdrawn)
        assertEquals("false", Json.parseToJsonElement(rig.bodyOf("PUT", "/me/consents/SAVE_AI_HISTORY")).jsonObject["isGranted"]!!.jsonPrimitive.content)
        model.dismissWithdrawn()
        assertNull(model.state.value.justWithdrawn)
    }

    @Test fun termsAndPrivacyCannotBeWithdrawn422() = runBlocking {
        rig.route = {
            problem(422, "VALIDATION_FAILED", ""","errors":[{"field":"isGranted","code":"CONSENT_NOT_REVOCABLE","message":"x"}]""")
        }
        for (code in ConsentCatalog.notRevocable) {
            model.set(code, false)
            assertEquals(PrivacyMessage.NotRevocable, model.state.value.message)
            assertNull(model.state.value.granted(code))
            assertNull(model.state.value.busy)
        }
        assertTrue("no local effect for a refused withdrawal", withdrawn.isEmpty())
        // Other 422s are not mistaken for it.
        rig.route = { problem(422, "VALIDATION_FAILED") }
        model.set("ANALYTICS", false)
        assertEquals(PrivacyMessage.Failed, model.state.value.message)
    }

    @Test fun theCatalogCoversEveryContractCodeAndOnlyTermsAndPrivacyAreRequired() {
        val codes = ConsentCatalog.all.map { it.code }
        assertEquals(13, codes.size)
        assertEquals(codes.size, codes.toSet().size)
        assertEquals(setOf("TERMS_ACCEPTANCE", "PRIVACY_NOTICE"), ConsentCatalog.all.filter { it.kind == ConsentKind.REQUIRED }.map { it.code }.toSet())
        assertEquals(ConsentCatalog.notRevocable, ConsentCatalog.all.filter { it.kind == ConsentKind.REQUIRED }.map { it.code }.toSet())
        assertEquals("DICTIONARY_SYNC", ConsentCatalog.all.single { it.kind == ConsentKind.SYNC_CARD }.code)
        for (d in ConsentCatalog.all.filter { it.kind == ConsentKind.SWITCH }) assertTrue("${d.code} explains what withdrawing does", d.effect != null)
    }

    @Test fun failuresAreMappedAndNothingChanges() = runBlocking {
        rig.route = { problem(401, "UNAUTHENTICATED") }
        model.set("MARKETING", true)
        assertEquals(PrivacyMessage.SignedOut, model.state.value.message)
        assertTrue(granted.isEmpty())
        rig.route = { MockResponse().setResponseCode(503) }
        model.set("MARKETING", true)
        assertEquals(PrivacyMessage.Failed, model.state.value.message)
        rig.route = { problem(429, "RATE_LIMITED", "", "Retry-After" to "30") }
        model.load()
        assertEquals(PrivacyMessage.RateLimited(30), model.state.value.message)
        assertFalse(model.state.value.loaded)
    }

    @Test fun offlineIsReported() = runBlocking {
        rig.stop()
        model.load()
        assertTrue(model.state.value.message == PrivacyMessage.Offline || model.state.value.message == PrivacyMessage.Timeout)
    }

    @Test fun consentHistoryIsPagedOldestFirst() = runBlocking {
        rig.route = { r ->
            if (r.path!!.contains("cursor=c2")) json(200, """{"items":[${consent("MARKETING", false, "2026-11-18T08:00:00Z")}]}""")
            else json(200, """{"items":[${consent("MARKETING", true, "2026-11-17T08:00:00Z")}],"nextCursor":"c2"}""")
        }
        model.loadHistory()
        assertEquals(listOf(true), model.state.value.history.map { it.granted })
        assertEquals("c2", model.state.value.historyNext)
        model.loadMoreHistory()
        assertEquals(listOf(true, false), model.state.value.history.map { it.granted })
        assertNull(model.state.value.historyNext)
        assertTrue(rig.requests[0].path!!.contains("limit=20"))
        assertEquals("/api/v1/me/consents/history", rig.requests[0].path!!.substringBefore('?'))
        model.loadMoreHistory() // no cursor left: no call
        assertEquals(2, rig.requests.size)
    }
}
