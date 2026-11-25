package com.qalsmartkeyboard.aiclient

import com.qalsmartkeyboard.ime.ai.AiFailure
import com.qalsmartkeyboard.ime.ai.AiLink
import com.qalsmartkeyboard.ime.ai.AiOutcome
import com.qalsmartkeyboard.ime.ai.AiRequest
import com.qalsmartkeyboard.ime.ai.AiTask
import com.qalsmartkeyboard.ime.ai.AiTaskKind
import com.qalsmartkeyboard.ime.ai.ChatRole
import com.qalsmartkeyboard.ime.ai.ChatTurn
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Ask AI chat on the wire (plan T8.5, ADR 0048): `POST /ai/chat` against the contract's `ChatRequest` /
 * `ChatResponse`, the idempotent retry, the 35 s call timeout, `finishReason: LENGTH`, the plan hint of `AI_CHAT` and the
 * chat's error states (plan lock, quota with plan and credit-pack options, too long, guardrail, provider).
 */
class AiChatWireTest {
    private lateinit var server: MockWebServer
    private lateinit var rig: Rig
    private val direct = Executor { it.run() }

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        rig = Rig(server)
    }

    @After fun tearDown() = server.shutdown()

    private fun actions(saveToHistory: Boolean = false): ServerAiActions {
        val entitlements = EntitlementManager(rig.clients.okHttpClient, rig.baseUrl, FakeSession(), EntitlementTokenVerifier(PinnedKeys.NONE), EntitlementTokenStore(rig.files))
        return ServerAiActions(
            rig.clients.okHttpClient, rig.baseUrl, FakeSession(), FakeConnectivity(), { emptyList<com.qalsmartkeyboard.sync.DestinationProfile>() to emptyList() },
            entitlements, { false }, direct, saveToHistory = { saveToHistory },
        )
    }

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun problem(code: Int, errorCode: String, extra: String = "") =
        json(code, """{"type":"about:blank","title":"t","status":$code,"code":"$errorCode","correlationId":"c-1"$extra}""")

    private val turns = listOf(
        ChatTurn(ChatRole.USER, "What does \"kind regards\" mean in an Amharic letter?"),
        ChatTurn(ChatRole.ASSISTANT, "It is the polite closing before your name."),
        ChatTurn(ChatRole.USER, "Write that closing for my boss, formal."),
    )

    private fun run(a: ServerAiActions, task: AiTask, key: String = "7d0a3c7e-1b1a-4c55-8a0e-1d2a3b4c5d6e"): AiOutcome {
        val w = Waiter()
        a.submit(AiRequest(key, task, "Write that closing for my boss, formal."), w.callback)
        return w.await()
    }

    private fun failure(o: AiOutcome) = (o as AiOutcome.Failure).failure

    private val reply = """{"requestId":"0193a5c2-6f1e-7c3a-9b2d-4e5f6a7b8c9d","message":{"role":"ASSISTANT","text":"ከታላቅ አክብሮት ጋር፣"},"language":"am",
        "finishReason":"STOP","notes":[{"type":"QUALITY","message":"Beta quality: please review the result before sending."}],"qualityTier":"BETA","replayed":false,
        "usage":{"featureCode":"AI_CHAT","unitsCharged":1,"unit":"REQUEST","usageSource":"PLAN_ALLOWANCE","remaining":99,"resetsAt":"2026-11-17T21:00:00Z"}}"""

    @Test fun chatSendsTheContractRequestAndReadsTheReply() {
        server.enqueue(json(200, reply))
        val out = run(actions(), AiTask.Chat(turns, "am", "ሰላም አቶ ከበደ፣ ሪፖርቱን ነገ እልካለሁ።"))
        val r = (out as AiOutcome.Success).result
        assertEquals("ከታላቅ አክብሮት ጋር፣", r.text)
        assertEquals("am", r.language)
        assertEquals(99, r.remaining)
        assertEquals(1_794_949_200_000L, r.resetsAtMs)
        assertEquals(listOf("Beta quality: please review the result before sending."), r.notes)
        assertFalse(r.replayed)
        assertFalse("STOP is a complete reply", r.truncated)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/ai/chat", req.path)
        assertEquals("7d0a3c7e-1b1a-4c55-8a0e-1d2a3b4c5d6e", req.getHeader("Idempotency-Key"))
        assertEquals("KEYBOARD", req.getHeader("X-Client-Surface"))
        assertEquals("Bearer access-token", req.getHeader("Authorization"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        val sent = body["messages"]!!.jsonArray
        assertEquals(3, sent.size)
        assertEquals("USER", sent[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("ASSISTANT", sent[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("Write that closing for my boss, formal.", sent[2].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("am", body["language"]!!.jsonPrimitive.content)
        assertEquals("ሰላም አቶ ከበደ፣ ሪፖርቱን ነገ እልካለሁ።", body["context"]!!.jsonObject["fieldText"]!!.jsonPrimitive.content)
        assertEquals(false, body["saveToHistory"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(setOf("messages", "language", "context", "saveToHistory"), body.keys)
    }

    @Test fun languageAndFieldTextAreLeftOutWhenNotChosen() {
        server.enqueue(json(200, reply))
        run(actions(), AiTask.Chat(turns))
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(setOf("messages", "saveToHistory"), body.keys)
        server.enqueue(json(200, reply))
        run(actions(), AiTask.Chat(turns, null, "   "))
        assertFalse("a blank field text is not sent", "context" in Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject)
    }

    @Test fun chatIsNeverSavedEvenWhenHistoryIsOn() {
        server.enqueue(json(200, reply))
        run(actions(saveToHistory = true), AiTask.Chat(turns))
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("the server refuses true", false, body["saveToHistory"]!!.jsonPrimitive.booleanOrNull)
    }

    @Test fun aReplyCutOffAtTheLimitIsMarkedTruncated() {
        server.enqueue(json(200, reply.replace("\"STOP\"", "\"LENGTH\"")))
        val r = (run(actions(), AiTask.Chat(turns)) as AiOutcome.Success).result
        assertTrue(r.truncated)
        assertEquals("ከታላቅ አክብሮት ጋር፣", r.text)
    }

    @Test fun anEmptyOrUnreadableReplyIsAnOtherFailure() {
        server.enqueue(json(200, """{"requestId":"r","message":{"role":"ASSISTANT","text":"  "},"finishReason":"STOP","replayed":false}"""))
        server.enqueue(json(200, """{"requestId":"r","finishReason":"STOP","replayed":false}"""))
        server.enqueue(json(200, "not json"))
        val a = actions()
        repeat(3) { assertEquals(AiFailure.Other, failure(run(a, AiTask.Chat(turns)))) }
    }

    @Test fun aRetryWithTheSameKeyIsReplayedByTheServer() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(json(200, reply.replace("\"replayed\":false", "\"replayed\":true")))
        val a = actions()
        val task = AiTask.Chat(turns)
        assertTrue(failure(run(a, task, key = "key-A")).retrySameKey)
        assertTrue((run(a, task, key = "key-A") as AiOutcome.Success).result.replayed)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("key-A", first.getHeader("Idempotency-Key"))
        assertEquals("key-A", second.getHeader("Idempotency-Key"))
        assertEquals("the same body is resent with the same key", first.body.readUtf8(), second.body.readUtf8())
    }

    @Test fun chatWaitsAboutThirtyFiveSecondsAndNoOtherTaskDoes() {
        val a = actions()
        assertEquals(35, AiTimeouts.callTimeoutSeconds(AiTaskKind.CHAT))
        assertEquals(35_000, a.clientFor(AiTaskKind.CHAT).callTimeoutMillis)
        assertTrue("longer than the server's 30 s budget", a.clientFor(AiTaskKind.CHAT).callTimeoutMillis > 30_000)
        assertEquals(25_000, a.clientFor(AiTaskKind.EXPLAIN).callTimeoutMillis)
        assertEquals(12_000, a.clientFor(AiTaskKind.GRAMMAR).callTimeoutMillis)
        assertTrue("one connection pool", a.clientFor(AiTaskKind.CHAT).connectionPool === rig.clients.okHttpClient.connectionPool)
    }

    @Test fun aSlowServerEndsInTimeoutAtTheChatLimit() {
        server.enqueue(json(200, reply).setHeadersDelay(3, TimeUnit.SECONDS))
        val entitlements = EntitlementManager(rig.clients.okHttpClient, rig.baseUrl, FakeSession(), EntitlementTokenVerifier(PinnedKeys.NONE), EntitlementTokenStore(rig.files))
        val a = ServerAiActions(
            rig.clients.okHttpClient, rig.baseUrl, FakeSession(), FakeConnectivity(), { emptyList<com.qalsmartkeyboard.sync.DestinationProfile>() to emptyList() },
            entitlements, { false }, direct, callTimeouts = { 1L },
        )
        assertEquals(AiFailure.Timeout, failure(run(a, AiTask.Chat(turns))))
    }

    @Test fun errorsMapToTheChatStates() {
        val cases = listOf(
            problem(403, "FEATURE_NOT_ENTITLED") to AiFailure.NotEntitled,
            problem(400, "INPUT_TOO_LONG", ""","errors":[{"field":"messages[2].text","code":"TOO_LONG","message":"m"}]""") to AiFailure.InputTooLong("messages[2].text"),
            problem(400, "VALIDATION_FAILED", ""","errors":[{"field":"messages[0].role","code":"INVALID","message":"m"}]""") to AiFailure.Invalid("messages[0].role"),
            problem(409, "REQUEST_IN_PROGRESS") to AiFailure.Busy,
            problem(422, "OUTPUT_GUARDRAIL_FAILED") to AiFailure.GuardrailFailed,
            problem(422, "CONTENT_REJECTED") to AiFailure.ContentRejected,
            problem(503, "PROVIDER_UNAVAILABLE") to AiFailure.ServiceUnavailable,
            problem(401, "UNAUTHENTICATED") to AiFailure.SignInRequired,
        )
        val a = actions()
        for ((response, expected) in cases) {
            server.enqueue(response)
            assertEquals(expected, failure(run(a, AiTask.Chat(turns))))
        }
    }

    @Test fun quotaExceededCarriesPlanAndCreditPackOptions() {
        fun quota(options: String): AiFailure.QuotaExceeded {
            server.enqueue(problem(429, "QUOTA_EXCEEDED", ""","featureCode":"AI_CHAT","resetAt":"2026-11-17T21:00:00Z","creditBalance":1,"upgradeOptions":$options"""))
            return failure(run(actions(), AiTask.Chat(turns))) as AiFailure.QuotaExceeded
        }
        val both = quota("""[{"type":"PLAN","productCode":"PLAN_PREMIUM"},{"type":"CREDIT_PACK","productCode":"CREDITS_100"}]""")
        assertTrue(both.canUpgrade)
        assertTrue(both.canBuyCredits)
        assertEquals(AiLink.UPGRADE, both.link)
        assertEquals(AiLink.BUY_CREDITS, both.extraLink)
        assertEquals(1_794_949_200_000L, both.resetAtMs)
        val credits = quota("""[{"type":"CREDIT_PACK","productCode":"CREDITS_100"}]""")
        assertFalse(credits.canUpgrade)
        assertEquals(AiLink.BUY_CREDITS, credits.link)
        assertNull(quota("[]").link)
    }

    @Test fun thePlanHintKnowsAiChat() {
        assertEquals("AI_CHAT", AiWire.featureCode(AiTaskKind.CHAT))
        server.enqueue(MockResponse().setResponseCode(404)) // the token endpoint: no token, nothing changes
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"plan":{"planCode":"FREE","displayName":"Free","planRank":0,"source":"DEFAULT"},
                "features":[{"featureCode":"TRANSLATE","accessEnabled":true,"unit":"REQUEST","resetPeriod":"DAILY","remaining":3},
                            {"featureCode":"AI_CHAT","accessEnabled":false,"unit":"REQUEST","resetPeriod":"DAILY"}],
                "entitlementToken":{"jws":"x","keyId":"k","expiresAt":"2027-01-01T00:00:00Z","offlineGraceUntil":"2027-01-04T00:00:00Z"}}""",
            ),
        )
        val m = EntitlementManager(rig.clients.okHttpClient, rig.baseUrl, FakeSession(), EntitlementTokenVerifier(PinnedKeys.NONE), EntitlementTokenStore(rig.files))
        m.refresh()
        assertEquals(setOf(AiTaskKind.TRANSLATE), m.entitled)
        assertFalse(AiTaskKind.CHAT in m.entitled!!)
    }

    @Test fun anAnswerUpdatesTheChatAllowance() {
        server.enqueue(json(200, reply))
        val a = actions()
        run(a, AiTask.Chat(turns))
        assertEquals(99, a.availability.quota[AiTaskKind.CHAT]?.remaining)
    }

    @Test fun theTaskNeverPrintsUserText() {
        val t = AiTask.Chat(turns, "am", "secret field text")
        assertFalse(t.toString().contains("secret"))
        assertFalse(t.toString().contains("boss"))
        assertFalse(turns.first().toString().contains("kind regards"))
    }
}
