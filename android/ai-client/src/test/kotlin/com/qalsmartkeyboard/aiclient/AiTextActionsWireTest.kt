package com.qalsmartkeyboard.aiclient

import com.qalsmartkeyboard.ime.ai.AiDestination
import com.qalsmartkeyboard.ime.ai.AiDocumentType
import com.qalsmartkeyboard.ime.ai.AiEmailMode
import com.qalsmartkeyboard.ime.ai.AiEmailSpec
import com.qalsmartkeyboard.ime.ai.AiEmailTone
import com.qalsmartkeyboard.ime.ai.AiFormat
import com.qalsmartkeyboard.ime.ai.AiLanguages
import com.qalsmartkeyboard.ime.ai.AiLength
import com.qalsmartkeyboard.ime.ai.AiOutcome
import com.qalsmartkeyboard.ime.ai.AiReplyIntent
import com.qalsmartkeyboard.ime.ai.AiRequest
import com.qalsmartkeyboard.ime.ai.AiRewriteMode
import com.qalsmartkeyboard.ime.ai.AiTask
import com.qalsmartkeyboard.ime.ai.AiTaskKind
import com.qalsmartkeyboard.ime.ai.AiTone
import java.util.concurrent.Executor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The AI text actions and the e-mail writer on the wire (ADR 0027, 0031): request bodies against the contract shapes
 * (`TransformRequest`, `RepliesRequest`, `DraftRequest`), response parsing, per-operation client timeouts, the plan
 * hint behind the "Premium" marks and the app rule of the direction control.
 */
class AiTextActionsWireTest {
    private lateinit var server: MockWebServer
    private lateinit var rig: Rig
    private val direct = Executor { it.run() }

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        rig = Rig(server)
    }

    @After fun tearDown() = server.shutdown()

    private fun actions(): ServerAiActions {
        val entitlements = EntitlementManager(rig.clients.okHttpClient, rig.baseUrl, FakeSession(), EntitlementTokenVerifier(PinnedKeys.NONE), EntitlementTokenStore(rig.files))
        return ServerAiActions(
            rig.clients.okHttpClient, rig.baseUrl, FakeSession(), FakeConnectivity(),
            { listOf(profile("p1", "Mum", "am")) to listOf(rule("com.chat", profileId = "p1"), rule("com.mail", lang = "ti")) },
            entitlements, { false }, direct,
        )
    }

    private fun json(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    private fun run(a: ServerAiActions, task: AiTask, text: String = "Some text", key: String = "11111111-2222-4333-8444-555555555555"): AiOutcome {
        val w = Waiter()
        a.submit(AiRequest(key, task, text), w.callback)
        return w.await()
    }

    private fun sent(): Pair<String, JsonObject> {
        val r = server.takeRequest()
        return r.path!! to Json.parseToJsonElement(r.body.readUtf8()).jsonObject
    }

    private fun JsonObject.s(name: String) = this[name]!!.jsonPrimitive.content

    private val transformed = """{"requestId":"r","task":"SUMMARIZE","language":"en","resultText":"Summary: ...","qualityTier":"BETA","replayed":false,
        "usage":{"featureCode":"SUMMARIZE","remaining":4}}"""

    @Test fun transformTasksCarryTheirOutputLanguageAndOptions() {
        val a = actions()
        repeat(6) { server.enqueue(json(transformed)) }
        run(a, AiTask.Explain("am"))
        run(a, AiTask.Summarize("en", AiLength.SHORT))
        run(a, AiTask.Simplify(null))
        run(a, AiTask.Rewrite(AiTone.PROFESSIONAL, "en"))
        run(a, AiTask.Rewrite(null, "om"))
        run(a, AiTask.Grammar)

        val (p1, explain) = sent()
        assertEquals("/api/v1/ai/transform", p1)
        assertEquals("EXPLAIN_TEXT", explain.s("task"))
        assertEquals("am", explain.s("outputLanguage"))
        assertEquals("Some text", explain.s("text"))

        val (_, summary) = sent()
        assertEquals("SUMMARIZE", summary.s("task"))
        assertEquals("en", summary.s("outputLanguage"))
        assertEquals("SHORT", summary["options"]!!.jsonObject.s("targetLength"))

        val (_, simplify) = sent()
        assertEquals("REWRITE", simplify.s("task"))
        assertEquals("SIMPLIFY", simplify["options"]!!.jsonObject.s("mode"))
        assertFalse("same as the text: omitted", "outputLanguage" in simplify)

        val (_, professional) = sent()
        assertEquals("TONE_ADJUST", professional.s("task"))
        assertEquals("PROFESSIONAL", professional["options"]!!.jsonObject.s("tone"))
        assertEquals("en", professional.s("outputLanguage"))

        val (_, rewrite) = sent()
        assertEquals("NATURAL", rewrite["options"]!!.jsonObject.s("mode"))
        assertEquals("om", rewrite.s("outputLanguage"))

        val (_, grammar) = sent()
        assertFalse("grammar keeps the text's language", "outputLanguage" in grammar)
        for (b in listOf(explain, summary, simplify, professional, rewrite, grammar)) assertFalse("variation 0 is the default", "variation" in b)
    }

    @Test fun formatAndTheRewriteModesSendTheirOptions() {
        val a = actions()
        repeat(4) { server.enqueue(json(transformed)) }
        run(a, AiTask.Format(AiFormat.BULLETS, "am"))
        run(a, AiTask.Rewrite(null, null, mode = AiRewriteMode.SHORTEN))
        run(a, AiTask.Rewrite(null, null, mode = AiRewriteMode.EXPAND))
        run(a, AiTask.Rewrite(null, null, mode = AiRewriteMode.CLARIFY))

        val (p1, format) = sent()
        assertEquals("/api/v1/ai/transform", p1)
        assertEquals("FORMAT_TEXT", format.s("task"))
        assertEquals("BULLETS", format["options"]!!.jsonObject.s("format"))
        assertEquals("am", format.s("outputLanguage"))
        assertEquals(AiTaskKind.FORMAT, AiTask.Format(AiFormat.BULLETS).kind)
        for (mode in listOf("SHORTEN", "EXPAND", "CLARIFY")) {
            val (_, body) = sent()
            assertEquals("REWRITE", body.s("task"))
            assertEquals(mode, body["options"]!!.jsonObject.s("mode"))
            assertFalse("no tone: a plain rewrite", "tone" in body["options"]!!.jsonObject)
        }
    }

    @Test fun aReplyIntentIsSentWhenPickedAndLeftOutOtherwise() {
        server.enqueue(json("""{"requestId":"r","suggestions":[{"text":"Sorry, I can't."}],"replayed":false}"""))
        run(actions(), AiTask.Reply("en", intent = AiReplyIntent.DECLINE_POLITELY), "Can you come?")
        assertEquals("DECLINE_POLITELY", sent().second.s("intent"))
    }

    @Test fun theEmailDocumentTypeIsSent() {
        server.enqueue(json("""{"requestId":"r","body":"Dear Sir,","replayed":false}"""))
        val spec = AiEmailSpec(AiEmailMode.NEW, "en", "applying for the post", documentType = AiDocumentType.JOB_APPLICATION)
        run(actions(), AiTask.Email(spec), spec.mainText)
        assertEquals("JOB_APPLICATION", sent().second.s("documentType"))
    }

    @Test fun regeneratedTransformTasksSendTheirVariation() {
        val a = actions()
        repeat(4) { server.enqueue(json(transformed)) }
        run(a, AiTask.Rewrite(null, null, variation = 2))
        run(a, AiTask.Rewrite(AiTone.PROFESSIONAL, "en", variation = 3))
        run(a, AiTask.Simplify(null, variation = 1))
        run(a, AiTask.Summarize("en", AiLength.SHORT, variation = 4))
        assertEquals(listOf("2", "3", "1", "4"), List(4) { sent().second.s("variation") })
    }

    @Test fun transformResultsReportTheirLanguage() {
        server.enqueue(json(transformed))
        val r = (run(actions(), AiTask.Summarize("en")) as AiOutcome.Success).result
        assertEquals("Summary: ...", r.text)
        assertEquals("en", r.language)
        assertEquals(4, r.remaining)
    }

    @Test fun anExplicitSourceLanguageIsSentAndTheDetectedOneRead() {
        server.enqueue(json("""{"requestId":"r","destinationLanguage":"en","detectedSourceLanguage":"ti","translatedText":"Hi","qualityTier":"BETA","replayed":false}"""))
        val r = (run(actions(), AiTask.Translate(AiLanguages.plain("en"), "am"), "ሰላም") as AiOutcome.Success).result
        val (path, body) = sent()
        assertEquals("/api/v1/ai/translate", path)
        assertEquals("am", body.s("sourceLanguage"))
        assertEquals("ti", r.sourceLanguage)
        assertEquals("en", r.language)
    }

    @Test fun repliesSendTheMessageNotesAndVariationAndReadEverySuggestion() {
        server.enqueue(
            json(
                """{"requestId":"r","language":"en","suggestions":[{"text":"Yes, Friday works."},{"text":"Could we do Monday?","tone":"POLITE"},{"text":" "}],
                "notes":[{"type":"INFO","message":"n"}],"qualityTier":"BETA","replayed":false,"usage":{"featureCode":"SMART_REPLY","remaining":2}}""",
            ),
        )
        val r = (run(actions(), AiTask.Reply("en", "say yes, 3pm", 3, 2), "Can we meet Friday?") as AiOutcome.Success).result
        val (path, body) = sent()
        assertEquals("/api/v1/ai/replies", path)
        assertEquals("Can we meet Friday?", body.s("contextText"))
        assertEquals("en", body.s("replyLanguage"))
        assertEquals("3", body.s("count"))
        assertEquals("say yes, 3pm", body.s("instructions"))
        assertEquals("2", body.s("variation"))
        assertEquals(listOf("Yes, Friday works.", "Could we do Monday?"), r.suggestions)
        assertEquals("Yes, Friday works.", r.text)
        assertEquals(listOf("n"), r.notes)
    }

    @Test fun aFirstReplyRequestLeavesTheOptionalMembersOut() {
        server.enqueue(json("""{"requestId":"r","suggestions":[{"text":"OK"}],"replayed":false}"""))
        run(actions(), AiTask.Reply("am"), "msg")
        val (_, body) = sent()
        assertEquals(setOf("contextText", "replyLanguage", "count"), body.keys)
    }

    @Test fun draftsSendOnlyWhatTheModeUses() {
        val a = actions()
        val draft = """{"requestId":"r","mode":"NEW","language":"en","subject":"Day off on [date]","body":"Dear [name],\n...","placeholders":["[date]","[name]"],
            "notes":[],"qualityTier":"BETA","replayed":false,"usage":{"featureCode":"DRAFT_DOCUMENT","remaining":1}}"""
        repeat(3) { server.enqueue(json(draft)) }
        val new = AiEmailSpec(AiEmailMode.NEW, "en", "ask for Friday off", originalEmail = "ignored", tone = AiEmailTone.POLITE, length = AiLength.SHORT, recipientName = "Almaz", senderName = "Abebe")
        val r = (run(a, AiTask.Email(new), new.mainText) as AiOutcome.Success).result
        run(a, AiTask.Email(AiEmailSpec(AiEmailMode.REPLY, "am", "yes", originalEmail = "Can you come?", variation = 3)), "Can you come?")
        run(a, AiTask.Email(AiEmailSpec(AiEmailMode.IMPROVE, "en", draft = "hi i want leave")), "hi i want leave")

        val (path, b1) = sent()
        assertEquals("/api/v1/ai/drafts", path)
        assertEquals("EMAIL", b1.s("documentType"))
        assertEquals("NEW", b1.s("mode"))
        assertEquals("en", b1.s("language"))
        assertEquals("ask for Friday off", b1.s("instructions"))
        assertEquals("POLITE", b1.s("tone"))
        assertEquals("SHORT", b1.s("length"))
        assertEquals("true", b1.s("includeSubject"))
        assertEquals("Almaz", b1["recipient"]!!.jsonObject.s("name"))
        assertEquals("Abebe", b1.s("senderName"))
        assertFalse("originalEmail outside REPLY is refused by the server", "originalEmail" in b1)
        assertFalse("draft" in b1)
        assertFalse("variation 0 is the default", "variation" in b1)

        val (_, b2) = sent()
        assertEquals("REPLY", b2.s("mode"))
        assertEquals("Can you come?", b2.s("originalEmail"))
        assertEquals("3", b2.s("variation"))

        val (_, b3) = sent()
        assertEquals("IMPROVE", b3.s("mode"))
        assertEquals("hi i want leave", b3.s("draft"))
        assertFalse("instructions are optional for IMPROVE", "instructions" in b3)

        assertEquals("Day off on [date]", r.subject)
        assertEquals("Dear [name],\n...", r.text)
        assertEquals(listOf("[date]", "[name]"), r.placeholders)
    }

    @Test fun aDraftWithoutSubjectOrBodyIsHandled() {
        server.enqueue(json("""{"requestId":"r","body":"Hello","replayed":false}"""))
        server.enqueue(json("""{"requestId":"r","subject":"S","body":"  ","replayed":false}"""))
        val a = actions()
        val r = (run(a, AiTask.Email(AiEmailSpec(AiEmailMode.NEW, "en", "x"))) as AiOutcome.Success).result
        assertNull(r.subject)
        assertTrue(r.placeholders.isEmpty())
        assertTrue("no body is not a usable answer", run(a, AiTask.Email(AiEmailSpec(AiEmailMode.NEW, "en", "x"))) is AiOutcome.Failure)
    }

    @Test fun clientTimeoutsFollowTheServerBudgets() {
        val a = actions()
        assertEquals(15, AiTimeouts.callTimeoutSeconds(AiTaskKind.TRANSLATE))
        assertEquals(15_000, a.clientFor(AiTaskKind.TRANSLATE).callTimeoutMillis)
        assertTrue("longer than the server's 12 s deadline", a.clientFor(AiTaskKind.TRANSLATE).callTimeoutMillis > 12_000)
        for (k in listOf(AiTaskKind.GRAMMAR, AiTaskKind.REWRITE, AiTaskKind.TONE)) {
            assertEquals(k.name, 12_000, a.clientFor(k).callTimeoutMillis)
            assertTrue("longer than the server's 10 s deadline", a.clientFor(k).callTimeoutMillis > 10_000)
        }
        for (k in listOf(AiTaskKind.EXPLAIN, AiTaskKind.SUMMARIZE, AiTaskKind.REPLY)) {
            assertEquals(k.name, 25_000, a.clientFor(k).callTimeoutMillis)
            assertTrue("longer than the server's 20 s deadline", a.clientFor(k).callTimeoutMillis > 20_000)
        }
        assertEquals(35_000, a.clientFor(AiTaskKind.EMAIL).callTimeoutMillis)
        assertTrue("one connection pool", a.clientFor(AiTaskKind.EMAIL).connectionPool === rig.clients.okHttpClient.connectionPool)
    }

    @Test fun everyKindHasItsFeatureCode() {
        assertEquals(
            listOf("TRANSLATE", "GRAMMAR_CORRECTION", "REWRITE", "TONE_ADJUST", "EXPLAIN_TEXT", "SUMMARIZE", "SMART_REPLY", "DRAFT_DOCUMENT", "FORMAT_TEXT"),
            AiTaskKind.entries.map(AiWire::featureCode),
        )
    }

    @Test fun thePlanHintListsTheIncludedFeatures() {
        server.enqueue(MockResponse().setResponseCode(404)) // the token endpoint: no token, nothing changes
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"plan":{"planCode":"FREE","displayName":"Free","planRank":0,"source":"DEFAULT"},
                "features":[{"featureCode":"TRANSLATE","accessEnabled":true,"unit":"REQUEST","resetPeriod":"DAILY","remaining":3},
                            {"featureCode":"EXPLAIN_TEXT","accessEnabled":true,"unit":"REQUEST","resetPeriod":"DAILY","remaining":3},
                            {"featureCode":"SMART_REPLY","accessEnabled":false,"unit":"REQUEST","resetPeriod":"DAILY"}],
                "entitlementToken":{"jws":"x","keyId":"k","expiresAt":"2027-01-01T00:00:00Z","offlineGraceUntil":"2027-01-04T00:00:00Z"}}""",
            ),
        )
        val m = EntitlementManager(rig.clients.okHttpClient, rig.baseUrl, FakeSession(), EntitlementTokenVerifier(PinnedKeys.NONE), EntitlementTokenStore(rig.files))
        assertNull("unknown before the first answer: nothing is locked", m.entitled)
        m.refresh()
        assertEquals(setOf(AiTaskKind.TRANSLATE, AiTaskKind.EXPLAIN), m.entitled)
        assertEquals(3, m.quota[AiTaskKind.EXPLAIN]?.remaining)
        m.clear()
        assertNull(m.entitled)
    }

    @Test fun theAppRuleIsOfferedSeparately() {
        val a = actions() // the direct executor loads the destination snapshot in init
        assertEquals("Mum", a.appRuleDestination("com.chat")?.label)
        assertEquals(AiDestination("lang:ti", "ትግርኛ", "ti"), a.appRuleDestination("com.mail"))
        assertNull(a.appRuleDestination("com.other"))
        assertNull(a.appRuleDestination(null))
    }
}
