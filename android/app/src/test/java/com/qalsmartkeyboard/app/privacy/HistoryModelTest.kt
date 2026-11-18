package com.qalsmartkeyboard.app.privacy

import com.qalsmartkeyboard.app.privacy.PrivacyRig.Companion.ITEM_1
import com.qalsmartkeyboard.app.privacy.PrivacyRig.Companion.ITEM_2
import com.qalsmartkeyboard.app.privacy.PrivacyRig.Companion.consent
import com.qalsmartkeyboard.app.privacy.PrivacyRig.Companion.item
import com.qalsmartkeyboard.app.privacy.PrivacyRig.Companion.json
import com.qalsmartkeyboard.app.privacy.PrivacyRig.Companion.problem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** History: enable/disable gating (preference + consent), the plan warning (D41), list, favourite, delete. */
class HistoryModelTest {
    private val rig = PrivacyRig()
    private val flag = MemoryHistoryFlag()
    private lateinit var model: HistoryModel

    /** A tiny server: consent state, preferences document with an unknown member, plan, items. */
    private var consentGranted: Boolean? = null
    private var prefsEnabled = false
    private var prefsRetention = 30
    private var revision = 7
    private var historyFeature = false
    private var entitlementsStatus = 200
    private var staleOnce = false
    private var items = mutableListOf(item(ITEM_1), item(ITEM_2))

    private fun prefsJson() =
        """{"revision":$revision,"historyEnabled":$prefsEnabled,"historyRetentionDays":$prefsRetention,"numeralStyle":"ETHIOPIC","aiProcessingPreference":"SERVER","updatedAt":"2026-11-18T08:00:00Z"}"""

    private val server: (RecordedRequest) -> MockResponse = { r ->
        val path = r.path!!.removePrefix("/api/v1").substringBefore('?')
        when {
            path == "/me/consents" && r.method == "GET" ->
                json(200, """{"items":[${consentGranted?.let { consent("SAVE_AI_HISTORY", it) } ?: ""}]}""")
            path == "/me/consents/SAVE_AI_HISTORY" && r.method == "PUT" -> {
                consentGranted = Json.parseToJsonElement(r.body.clone().readUtf8()).jsonObject["isGranted"]!!.jsonPrimitive.content.toBoolean()
                json(200, consent("SAVE_AI_HISTORY", consentGranted!!))
            }
            path == "/me/preferences" && r.method == "GET" -> json(200, prefsJson())
            path == "/me/preferences" && r.method == "PUT" -> {
                val body = Json.parseToJsonElement(r.body.clone().readUtf8()).jsonObject
                if (staleOnce) {
                    staleOnce = false
                    revision++
                    problem(409, "REVISION_CONFLICT", ""","currentRevision":$revision""")
                } else if (body["historyEnabled"]!!.jsonPrimitive.content == "true" && consentGranted != true) {
                    problem(403, "CONSENT_REQUIRED", ""","consentCode":"SAVE_AI_HISTORY"""")
                } else {
                    prefsEnabled = body["historyEnabled"]!!.jsonPrimitive.content.toBoolean()
                    prefsRetention = body["historyRetentionDays"]!!.jsonPrimitive.content.toInt()
                    revision++
                    json(200, prefsJson())
                }
            }
            path == "/me/entitlements" ->
                if (entitlementsStatus == 200) {
                    json(
                        200,
                        """{"plan":{"planCode":"FREE","displayName":"Free","planRank":0,"source":"DEFAULT"},"features":[{"featureCode":"TRANSLATE","accessEnabled":true},{"featureCode":"HISTORY_SYNC","accessEnabled":$historyFeature}],"entitlementToken":{"jws":"x"}}""",
                    )
                } else {
                    problem(403, "DELETION_PENDING")
                }
            path == "/me/history" && r.method == "GET" ->
                if (r.path!!.contains("cursor=n1")) json(200, """{"items":[${item("33333333-3333-4333-8333-333333333333")}]}""")
                else json(200, """{"items":[${items.joinToString(",")}],"nextCursor":"n1"}""")
            path == "/me/history" && r.method == "DELETE" -> { items.clear(); MockResponse().setResponseCode(204) }
            path.startsWith("/me/history/") && r.method == "PATCH" -> {
                val fav = Json.parseToJsonElement(r.body.clone().readUtf8()).jsonObject["favorite"]!!.jsonPrimitive.content.toBoolean()
                json(200, item(path.substringAfterLast('/'), fav))
            }
            path.startsWith("/me/history/") && r.method == "DELETE" -> MockResponse().setResponseCode(204)
            else -> MockResponse().setResponseCode(404)
        }
    }

    @Before fun setUp() {
        rig.start()
        rig.route = server
        model = HistoryModel(rig.api, flag)
    }

    @After fun tearDown() { rig.stop() }

    @Test fun loadShowsSettingsPlanAndItemsAndKeepsTheFlagOffByDefault() = runBlocking {
        model.load()
        val s = model.state.value
        assertTrue(s.loaded)
        assertFalse(s.enabled)
        assertEquals(30, s.retentionDays)
        assertEquals(false, s.plan!!.hasHistorySync)
        assertEquals("Free", s.plan!!.planName)
        assertEquals(2, s.items.size)
        assertEquals("hello", s.items[0].inputText)
        assertEquals("n1", s.nextCursor)
        assertFalse(flag.enabled)
    }

    @Test fun planWithoutHistorySyncIsWarnedAboutAndPlanWithItIsNot() = runBlocking {
        model.load()
        assertTrue(model.state.value.planLacksHistory)
        historyFeature = true
        model.load()
        assertFalse(model.state.value.planLacksHistory)
    }

    @Test fun unreadablePlanGivesNoWarning() = runBlocking {
        entitlementsStatus = 403 // DELETION_PENDING
        model.load()
        assertNull(model.state.value.plan)
        assertFalse(model.state.value.planLacksHistory)
        assertTrue(model.state.value.loaded)
        assertEquals(2, model.state.value.items.size)
    }

    @Test fun enablingGrantsTheConsentThenSavesThePreferenceAndTurnsTheFlagOn() = runBlocking {
        model.load()
        rig.requests.clear()
        model.enable(90)
        assertEquals(
            listOf("PUT /me/consents/SAVE_AI_HISTORY", "GET /me/preferences", "PUT /me/preferences"),
            rig.calls(),
        )
        val put = Json.parseToJsonElement(rig.bodyOf("PUT", "/me/preferences")).jsonObject
        assertEquals("true", put["historyEnabled"]!!.jsonPrimitive.content)
        assertEquals("90", put["historyRetentionDays"]!!.jsonPrimitive.content)
        assertEquals("7", put["revision"]!!.jsonPrimitive.content)
        // The PUT replaces the document: members this screen does not own are carried through, read-only ones are not sent.
        assertEquals("ETHIOPIC", put["numeralStyle"]!!.jsonPrimitive.content)
        assertEquals("SERVER", put["aiProcessingPreference"]!!.jsonPrimitive.content)
        assertFalse("updatedAt" in put)
        val s = model.state.value
        assertTrue(s.enabled)
        assertEquals(true, s.consentGranted)
        assertEquals(90, s.retentionDays)
        assertTrue(flag.enabled)
    }

    @Test fun enablingWithTheConsentAlreadyGivenSkipsTheConsentCall() = runBlocking {
        consentGranted = true
        model.load()
        rig.requests.clear()
        model.enable()
        assertEquals(listOf("GET /me/preferences", "PUT /me/preferences"), rig.calls())
        assertTrue(flag.enabled)
    }

    @Test fun enablingStillWorksOnAPlanWithoutHistoryButTheStateKeepsTheWarning() = runBlocking {
        model.load()
        model.enable(30)
        assertTrue(model.state.value.enabled)
        assertTrue(model.state.value.planLacksHistory)
    }

    @Test fun serverRefusalWithoutConsentLeavesHistoryOffAndTheFlagOff() = runBlocking {
        model.load()
        // The consent PUT is not answered the way a real server would: simulate a server that still says no.
        val inner = rig.route
        rig.route = { r -> if (r.method == "PUT" && r.path!!.contains("/consents/")) json(200, consent("SAVE_AI_HISTORY", true)) else inner(r) }
        model.enable()
        assertEquals(PrivacyMessage.ConsentRequired, model.state.value.message)
        assertFalse(model.state.value.enabled)
        assertFalse(flag.enabled)
        assertFalse(model.state.value.busy)
    }

    @Test fun staleRevisionIsReadAgainAndSent() = runBlocking {
        model.load()
        staleOnce = true
        model.enable(14)
        assertTrue(model.state.value.enabled)
        assertEquals(2, rig.requests.count { it.method == "PUT" && it.path!!.endsWith("/me/preferences") })
        assertEquals("8", Json.parseToJsonElement(rig.bodyOf("PUT", "/me/preferences")).jsonObject["revision"]!!.jsonPrimitive.content)
        assertTrue(flag.enabled)
    }

    @Test fun disablingStopsSavingFirstThenWithdrawsTheConsent() = runBlocking {
        consentGranted = true
        prefsEnabled = true
        model.load()
        assertTrue(flag.enabled)
        rig.requests.clear()
        model.disable()
        assertEquals(listOf("GET /me/preferences", "PUT /me/preferences", "PUT /me/consents/SAVE_AI_HISTORY"), rig.calls())
        assertFalse(model.state.value.enabled)
        assertEquals(false, model.state.value.consentGranted)
        assertFalse(flag.enabled)
        assertEquals("false", Json.parseToJsonElement(rig.bodyOf("PUT", "/me/consents/SAVE_AI_HISTORY")).jsonObject["isGranted"]!!.jsonPrimitive.content)
    }

    @Test fun theFlagNeedsBothThePreferenceAndTheConsent() = runBlocking {
        prefsEnabled = true
        consentGranted = false
        model.load()
        assertFalse(flag.enabled)
        consentGranted = true
        model.refreshFlag()
        assertTrue(flag.enabled)
        assertTrue(model.state.value.enabled)
        prefsEnabled = false
        model.refreshFlag()
        assertFalse(flag.enabled)
    }

    @Test fun retentionIsLimitedToOneTo365Days() = runBlocking {
        model.load()
        rig.requests.clear()
        for (bad in listOf(0, -3, 366, 1000)) {
            model.setRetention(bad)
            assertEquals(PrivacyMessage.RetentionInvalid, model.state.value.message)
        }
        model.enable(0)
        assertTrue("nothing was sent", rig.requests.isEmpty())
        assertFalse(model.state.value.enabled)
        model.setRetention(365)
        assertEquals(365, model.state.value.retentionDays)
        assertEquals("365", Json.parseToJsonElement(rig.bodyOf("PUT", "/me/preferences")).jsonObject["historyRetentionDays"]!!.jsonPrimitive.content)
        model.setRetention(1)
        assertEquals(1, model.state.value.retentionDays)
        assertFalse("history stays off while only the period changes", prefsEnabled)
    }

    @Test fun listsMoreFavouritesAndDeletes() = runBlocking {
        model.load()
        model.loadMore()
        assertEquals(3, model.state.value.items.size)
        assertNull(model.state.value.nextCursor)
        model.toggleFavorite(ITEM_1)
        assertTrue(model.state.value.items.first { it.id == ITEM_1 }.favorite)
        assertEquals("true", Json.parseToJsonElement(rig.bodyOf("PATCH", "/me/history/$ITEM_1")).jsonObject["favorite"]!!.jsonPrimitive.content)
        model.toggleFavorite(ITEM_1)
        assertFalse(model.state.value.items.first { it.id == ITEM_1 }.favorite)
        model.delete(ITEM_2)
        assertEquals(listOf(ITEM_1, "33333333-3333-4333-8333-333333333333"), model.state.value.items.map { it.id })
        assertTrue("DELETE /me/history/$ITEM_2" in rig.calls())
    }

    @Test fun deleteAllClearsTheListWithOneCall() = runBlocking {
        model.load()
        model.deleteAll()
        assertTrue(model.state.value.items.isEmpty())
        assertNull(model.state.value.nextCursor)
        assertEquals(1, rig.calls().count { it == "DELETE /me/history" })
        assertFalse(model.state.value.busy)
    }

    @Test fun anItemThatIsAlreadyGoneIsJustRemoved() = runBlocking {
        model.load()
        val inner = rig.route
        rig.route = { r -> if (r.method == "DELETE") problem(404, "NOT_FOUND") else inner(r) }
        model.delete(ITEM_1)
        assertEquals(listOf(ITEM_2), model.state.value.items.map { it.id })
        assertNull(model.state.value.message)
    }

    @Test fun deleteAllFailureKeepsTheList() = runBlocking {
        model.load()
        val inner = rig.route
        rig.route = { r -> if (r.method == "DELETE") MockResponse().setResponseCode(500) else inner(r) }
        model.deleteAll()
        assertEquals(2, model.state.value.items.size)
        assertEquals(PrivacyMessage.Failed, model.state.value.message)
        assertFalse(model.state.value.busy)
    }

    @Test fun anExistingFlagIsTheStartingGuess() {
        val on = HistoryModel(rig.api, MemoryHistoryFlag(true))
        assertTrue(on.state.value.enabled)
    }
}
