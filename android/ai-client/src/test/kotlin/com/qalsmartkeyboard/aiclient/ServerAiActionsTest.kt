package com.qalsmartkeyboard.aiclient

import com.qalsmartkeyboard.ime.ai.AiBlock
import com.qalsmartkeyboard.ime.ai.AiDestination
import com.qalsmartkeyboard.ime.ai.AiFailure
import com.qalsmartkeyboard.ime.ai.AiOutcome
import com.qalsmartkeyboard.ime.ai.AiRequest
import com.qalsmartkeyboard.ime.ai.AiTask
import com.qalsmartkeyboard.ime.ai.AiTaskKind
import com.qalsmartkeyboard.ime.ai.AiTone
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The `AiActions` implementation over the real `:api` client against MockWebServer. */
class ServerAiActionsTest {
    private lateinit var server: MockWebServer
    private lateinit var rig: Rig
    private val conn = FakeConnectivity()
    private val session = FakeSession()
    private val direct = Executor { it.run() }
    private var profiles = listOf(profile("p1", "Mum", "am", serverId = "11111111-1111-4111-8111-111111111111"), profile("p2", "Boss", "en", sort = 50))
    private var rules = listOf(rule("com.chat", profileId = "p1"), rule("com.mail", lang = "ti"))
    private var updateRequired = false
    private var saveToHistory = false

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        rig = Rig(server)
    }

    @After fun tearDown() = server.shutdown()

    private fun actions(
        client: okhttp3.OkHttpClient = rig.clients.okHttpClient,
        callTimeouts: (AiTaskKind) -> Long = AiTimeouts::callTimeoutSeconds,
    ): ServerAiActions {
        val entitlements = EntitlementManager(client, rig.baseUrl, session, EntitlementTokenVerifier(PinnedKeys.NONE), EntitlementTokenStore(rig.files))
        return ServerAiActions(client, rig.baseUrl, session, conn, { profiles to rules }, entitlements, { updateRequired }, direct, saveToHistory = { saveToHistory }, callTimeouts = callTimeouts)
    }

    private fun json(code: Int, body: String, vararg headers: Pair<String, String>) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body).also { r -> headers.forEach { r.setHeader(it.first, it.second) } }

    private fun problem(code: Int, errorCode: String, extra: String = "") =
        json(code, """{"type":"about:blank","title":"t","status":$code,"code":"$errorCode","correlationId":"c-1"$extra}""")

    private val tr = AiTask.Translate(AiDestination("p1", "Mum", "am", true, serverProfileId = "11111111-1111-4111-8111-111111111111", formality = "HONORIFIC", addresseeGender = "FEMININE", style = "POLITE"))

    private fun run(a: ServerAiActions, task: AiTask, text: String = "How are you?", key: String = "7d0a3c7e-1b1a-4c55-8a0e-1d2a3b4c5d6e"): AiOutcome {
        val w = Waiter()
        a.submit(AiRequest(key, task, text), w.callback)
        return w.await()
    }

    private fun failure(o: AiOutcome) = (o as AiOutcome.Failure).failure

    // ------------------------------------------------------------ requests

    @Test fun translateSendsTheContractRequest() {
        server.enqueue(
            json(
                200,
                """{"requestId":"0193a5c2-6f1e-7c3a-9b2d-4e5f6a7b8c9d","destinationLanguage":"am","translatedText":"እንዴት ነህ?",
                   "alternatives":[{"label":"Addressing a woman","text":"እንዴት ነሽ?"}],"notes":[{"type":"AMBIGUITY","message":"Gender unknown"}],
                   "qualityTier":"BETA","replayed":false,"usage":{"featureCode":"TRANSLATE","unitsCharged":1,"unit":"REQUEST","usageSource":"PLAN_ALLOWANCE","remaining":9,"resetsAt":"2026-11-17T21:00:00Z"}}""",
            ),
        )
        val out = run(actions(), tr)
        val r = (out as AiOutcome.Success).result
        assertEquals("እንዴት ነህ?", r.text)
        assertEquals("እንዴት ነሽ?", r.alternatives.single().text)
        assertEquals("Addressing a woman", r.alternatives.single().label)
        assertEquals(listOf("Gender unknown"), r.notes)
        assertEquals(9, r.remaining)
        assertEquals(1_794_949_200_000L, r.resetsAtMs)
        assertFalse(r.replayed)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/ai/translate", req.path)
        assertEquals("7d0a3c7e-1b1a-4c55-8a0e-1d2a3b4c5d6e", req.getHeader("Idempotency-Key"))
        assertEquals("KEYBOARD", req.getHeader("X-Client-Surface"))
        assertEquals("ANDROID", req.getHeader("X-Client-Platform"))
        assertEquals("Bearer access-token", req.getHeader("Authorization"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("How are you?", body["text"]!!.jsonPrimitive.content)
        assertEquals("am", body["destinationLanguage"]!!.jsonPrimitive.content)
        assertEquals("11111111-1111-4111-8111-111111111111", body["destinationProfileId"]!!.jsonPrimitive.content)
        assertEquals("POLITE", body["style"]!!.jsonPrimitive.content)
        assertEquals("HONORIFIC", body["addressee"]!!.jsonObject["formality"]!!.jsonPrimitive.content)
        assertEquals("FEMININE", body["addressee"]!!.jsonObject["gender"]!!.jsonPrimitive.content)
        assertFalse("source language is detected by the server", "sourceLanguage" in body)
        assertFalse("history is not saved", "saveToHistory" in body)
    }

    @Test fun saveToHistoryIsSentOnlyWhileHistoryIsOn() {
        val translated = """{"requestId":"r","destinationLanguage":"en","translatedText":"Hi","qualityTier":"BETA","replayed":false}"""
        val transformed = """{"requestId":"r","resultText":"Hi","qualityTier":"BETA","replayed":false}"""
        server.enqueue(json(200, translated))
        server.enqueue(json(200, transformed))
        server.enqueue(json(200, translated))
        val a = actions()
        saveToHistory = true // read per request: it takes effect on the next call
        run(a, tr)
        run(a, AiTask.Grammar)
        saveToHistory = false
        run(a, tr)
        val bodies = (1..3).map { Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject }
        assertEquals("true", bodies[0]["saveToHistory"]!!.jsonPrimitive.content)
        assertEquals("true", bodies[1]["saveToHistory"]!!.jsonPrimitive.content)
        assertFalse("off again: the member is absent (contract default false)", "saveToHistory" in bodies[2])
    }

    @Test fun plainLanguageTranslateOmitsProfileAndAddressee() {
        server.enqueue(json(200, """{"requestId":"r","destinationLanguage":"en","translatedText":"Hi","qualityTier":"BETA","replayed":false}"""))
        run(actions(), AiTask.Translate(AiDestination("lang:en", "English", "en")), "ሰላም")
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(setOf("text", "destinationLanguage", "preserveNames"), body.keys)
    }

    @Test fun grammarRewriteAndToneUseTheTransformEndpoint() {
        val ok = """{"requestId":"r","task":"X","resultText":"Fixed","qualityTier":"BETA","replayed":false}"""
        repeat(3) { server.enqueue(json(200, ok)) }
        val a = actions()
        run(a, AiTask.Grammar, "teh cat")
        run(a, AiTask.Rewrite(null), "teh cat")
        run(a, AiTask.Rewrite(AiTone.POLITE), "teh cat")
        fun next() = server.takeRequest().let { it.path to Json.parseToJsonElement(it.body.readUtf8()).jsonObject }
        val (p1, b1) = next()
        val (_, b2) = next()
        val (p3, b3) = next()
        assertEquals("/api/v1/ai/transform", p1)
        assertEquals("/api/v1/ai/transform", p3)
        assertEquals("GRAMMAR_CORRECTION", b1["task"]!!.jsonPrimitive.content)
        assertEquals("GRAMMAR", b1["options"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        assertEquals("REWRITE", b2["task"]!!.jsonPrimitive.content)
        assertEquals("NATURAL", b2["options"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        assertEquals("TONE_ADJUST", b3["task"]!!.jsonPrimitive.content)
        assertEquals("POLITE", b3["options"]!!.jsonObject["tone"]!!.jsonPrimitive.content)
        assertEquals("teh cat", b3["text"]!!.jsonPrimitive.content)
    }

    @Test fun anAnswerUpdatesThePlanHint() {
        server.enqueue(json(200, """{"requestId":"r","task":"GRAMMAR_CORRECTION","resultText":"Ok","qualityTier":"BETA","replayed":false,"usage":{"featureCode":"GRAMMAR_CORRECTION","unitsCharged":1,"unit":"REQUEST","usageSource":"PLAN_ALLOWANCE","remaining":3}}"""))
        val a = actions()
        var changed = 0
        a.addAvailabilityListener { changed++ }
        run(a, AiTask.Grammar)
        assertEquals(3, a.availability.quota[AiTaskKind.GRAMMAR]!!.remaining)
        assertTrue(changed > 0)
    }

    // ------------------------------------------------------------ retry, replay, cancel, timeout

    @Test fun retryAfterANetworkErrorSendsTheSameKeyAndTheServerReplays() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(json(200, """{"requestId":"r","destinationLanguage":"am","translatedText":"ሰላም","qualityTier":"BETA","replayed":true}"""))
        val a = actions()
        val first = run(a, tr, key = "key-A")
        assertTrue(failure(first).retrySameKey)
        val second = run(a, tr, key = "key-A")
        assertTrue((second as AiOutcome.Success).result.replayed)
        assertEquals("key-A", server.takeRequest().getHeader("Idempotency-Key"))
        assertEquals("key-A", server.takeRequest().getHeader("Idempotency-Key"))
    }

    @Test fun cancelAbortsTheCallAndNoOutcomeIsDelivered() {
        server.enqueue(json(200, "{}").setHeadersDelay(3, TimeUnit.SECONDS))
        val a = actions()
        val w = Waiter()
        val call = a.submit(AiRequest("k", AiTask.Grammar, "text"), w.callback)
        server.takeRequest(2, TimeUnit.SECONDS)
        call.cancel()
        assertTrue("no outcome after cancel", w.none(1_000))
    }

    @Test fun aSlowServerEndsInTimeoutAtTheClientLimit() {
        server.enqueue(json(200, "{}").setHeadersDelay(3, TimeUnit.SECONDS))
        val out = run(actions(callTimeouts = { 1L }), AiTask.Grammar, key = "k")
        assertEquals(AiFailure.Timeout, failure(out))
    }

    @Test fun anUnreachableServerIsReportedAsOfflineWithTheSameKeyRetry() {
        server.shutdown()
        val out = run(actions(), AiTask.Grammar, key = "k")
        assertEquals(AiFailure.Offline, failure(out))
    }

    @Test fun offlineMakesNoRequestAtAll() {
        conn.online = false
        val a = actions()
        assertEquals(AiBlock.OFFLINE, a.availability.block)
        assertEquals(AiFailure.Offline, failure(run(a, AiTask.Grammar)))
        assertEquals(0, server.requestCount)
    }

    // ------------------------------------------------------------ every error state, through the real client

    @Test fun quotaExceededCarriesTheResetTimeAndUpgradeHint() {
        server.enqueue(problem(429, "QUOTA_EXCEEDED", ""","featureCode":"TRANSLATE","resetAt":"2026-11-17T21:00:00Z","upgradeOptions":[{"planCode":"PREMIUM"}]""").setHeader("Retry-After", "3600"))
        val f = failure(run(actions(), tr)) as AiFailure.QuotaExceeded
        assertEquals(1_794_949_200_000L, f.resetAtMs)
        assertTrue(f.canUpgrade)
        server.enqueue(problem(429, "QUOTA_EXCEEDED", ""","resetAt":"2026-11-17T21:00:00Z","upgradeOptions":[]"""))
        assertFalse((failure(run(actions(), tr)) as AiFailure.QuotaExceeded).canUpgrade)
    }

    @Test fun serverCodesMapToToolbarStates() {
        val cases = listOf(
            problem(429, "RATE_LIMITED").setHeader("Retry-After", "20") to AiFailure.RateLimited(20),
            problem(403, "FEATURE_NOT_ENTITLED") to AiFailure.NotEntitled,
            problem(403, "ON_DEVICE_ONLY_PREFERRED") to AiFailure.OnDeviceOnly,
            problem(403, "CONSENT_REQUIRED", ""","consentCode":"AI_PROCESSING"""") to AiFailure.ConsentRequired("AI_PROCESSING"),
            problem(422, "OUTPUT_GUARDRAIL_FAILED") to AiFailure.GuardrailFailed,
            problem(422, "CONTENT_REJECTED") to AiFailure.ContentRejected,
            problem(422, "LANGUAGE_PAIR_UNAVAILABLE") to AiFailure.LanguageUnsupported,
            problem(409, "REQUEST_IN_PROGRESS") to AiFailure.Busy,
            problem(403, "GUEST_NOT_ALLOWED") to AiFailure.SignInRequired,
            problem(403, "ATTESTATION_REQUIRED") to AiFailure.SignInRequired,
            problem(403, "ACCOUNT_SUSPENDED") to AiFailure.AccountBlocked,
            problem(503, "PROVIDER_UNAVAILABLE") to AiFailure.ServiceUnavailable,
            json(502, "<html>bad gateway</html>") to AiFailure.ServiceUnavailable,
            problem(400, "VALIDATION_FAILED") to AiFailure.Invalid(null),
            problem(400, "INPUT_TOO_LONG", ""","errors":[{"field":"originalEmail","code":"TOO_LONG","message":"m"}]""") to AiFailure.InputTooLong("originalEmail"),
            problem(426, "APP_VERSION_UNSUPPORTED", ""","minSupportedAppVersion":"2.1.0"""") to AiFailure.UpdateRequired,
        )
        val a = actions()
        for ((response, expected) in cases) {
            server.enqueue(response)
            assertEquals(expected, failure(run(a, AiTask.Grammar)))
        }
    }

    @Test fun anUnreadableSuccessBodyIsAnOtherFailure() {
        server.enqueue(json(200, "not json"))
        server.enqueue(json(200, """{"requestId":"r","translatedText":"   "}"""))
        val a = actions()
        assertEquals(AiFailure.Other, failure(run(a, tr)))
        assertEquals(AiFailure.Other, failure(run(a, tr)))
    }

    // ------------------------------------------------------------ availability and destinations

    @Test fun availabilityReflectsSessionUpdateAndNetwork() {
        val a = actions()
        assertEquals(AiBlock.NONE, a.availability.block)
        conn.set(false)
        assertEquals(AiBlock.OFFLINE, a.availability.block)
        conn.set(true)
        session.signedIn = false
        assertEquals(AiBlock.SIGNED_OUT, a.availability.block)
        updateRequired = true
        assertEquals(AiBlock.UPDATE_REQUIRED, a.availability.block)
    }

    @Test fun connectivityChangesNotifyListeners() {
        val a = actions()
        var n = 0
        val h = a.addAvailabilityListener { n++ }
        conn.set(false)
        conn.set(true)
        assertEquals(2, n)
        h.close()
        conn.set(false)
        assertEquals(2, n)
    }

    @Test fun destinationsListProfilesFirstThenPlainLanguages() {
        val d = actions().destinations()
        assertEquals(listOf("Boss", "Mum"), d.take(2).map { it.label }) // by sort order
        assertTrue(d.take(2).all { it.isProfile })
        assertEquals(listOf("en", "am", "ti", "om"), d.drop(2).map { it.language })
        assertTrue(d.drop(2).none { it.isProfile })
        val mum = d.first { it.label == "Mum" }
        assertEquals("11111111-1111-4111-8111-111111111111", mum.serverProfileId)
        assertEquals("HONORIFIC", mum.formality)
    }

    @Test fun defaultDestinationFollowsAppRulesThenTheFirstProfileThenEnglish() {
        val a = actions()
        assertEquals("Mum", a.defaultDestination("com.chat").label)
        assertEquals("ti", a.defaultDestination("com.mail").language)
        assertEquals("Boss", a.defaultDestination("com.unknown").label)
        assertEquals("Boss", a.defaultDestination(null).label)
        profiles = emptyList()
        rules = emptyList()
        assertEquals("en", actions().defaultDestination("com.chat").language)
    }
}
