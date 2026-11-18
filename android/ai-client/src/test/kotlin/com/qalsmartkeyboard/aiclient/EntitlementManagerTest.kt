package com.qalsmartkeyboard.aiclient

import com.qalsmartkeyboard.ime.ai.AiPeriod
import com.qalsmartkeyboard.ime.ai.AiTaskKind
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Token fetch, on-device verification, caching and the offline gates, against MockWebServer. */
class EntitlementManagerTest {
    private lateinit var server: MockWebServer
    private lateinit var rig: Rig
    private val key = TestKey("ent-2026-11")
    private val session = FakeSession()
    private var now = 1_800_000_000_000L
    private val iat get() = now / 1000 - 3600

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        rig = Rig(server)
    }

    @After fun tearDown() = server.shutdown()

    private fun store() = EntitlementTokenStore(rig.files)

    private fun manager(pinned: PinnedKeys = key.pinned(), store: EntitlementTokenStore = store()) =
        EntitlementManager(rig.clients.okHttpClient, rig.baseUrl, session, EntitlementTokenVerifier(pinned), store, { now })

    private fun tokenResponse(jws: String, date: String? = null) =
        MockResponse().setHeader("Content-Type", "application/json").setHeader("Cache-Control", "no-store")
            .setBody("""{"jws":"$jws","keyId":"ent-2026-11","expiresAt":"2027-01-01T00:00:00Z","offlineGraceUntil":"2027-01-04T00:00:00Z"}""")
            .also { r -> date?.let { r.setHeader("Date", it) } }

    private val entitlementsBody = """{"plan":{"planCode":"FREE","displayName":"Free","planRank":0,"source":"DEFAULT"},
        "features":[{"featureCode":"TRANSLATE","accessEnabled":true,"includedUnits":10,"unit":"REQUEST","resetPeriod":"DAILY","used":7,"remaining":3,"resetsAt":"2026-11-17T21:00:00Z"},
                    {"featureCode":"GRAMMAR_CORRECTION","accessEnabled":false,"unit":"REQUEST","resetPeriod":"DAILY"},
                    {"featureCode":"ON_DEVICE_AI_MODELS","accessEnabled":true,"unit":"NONE","resetPeriod":"NONE"}],
        "entitlementToken":{"jws":"x","keyId":"k","expiresAt":"2027-01-01T00:00:00Z","offlineGraceUntil":"2027-01-04T00:00:00Z"}}"""

    private fun mint(iat: Long = this.iat, features: String = """{"ON_DEVICE_AI_MODELS":true}""") =
        TestTokens.mint(key, TestTokens.claims(iat, features = features))

    @Test fun aVerifiedTokenIsStoredAndOpensTheOfflineGate() {
        server.enqueue(tokenResponse(mint()))
        server.enqueue(MockResponse().setBody(entitlementsBody))
        val m = manager()
        m.refresh()
        assertTrue(m.gates(online = true).onDeviceModels)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/v1/me/entitlements/token", req.path)
        assertEquals("Bearer access-token", req.getHeader("Authorization"))
        // A new process: the cached token is verified again from disk.
        val again = manager()
        again.loadCached()
        assertTrue(again.gates(online = false).onDeviceModels)
    }

    @Test fun aTamperedOrWrongKeyTokenIsNeverStoredOrUsed() {
        val parts = mint().split('.')
        val forged = parts[0] + "." + b64(TestTokens.claims(iat, features = """{"ON_DEVICE_AI_MODELS":true,"X":true}""").toByteArray()) + "." + parts[2]
        server.enqueue(tokenResponse(forged))
        server.enqueue(MockResponse().setBody(entitlementsBody))
        val s = store()
        val m = manager(store = s)
        m.refresh()
        assertFalse(m.gates(online = false).onDeviceModels)
        assertNull(s.token())
        // A token signed by a key this build does not pin.
        now += 10 * 60_000
        server.enqueue(tokenResponse(TestTokens.mint(TestKey("other"), TestTokens.claims(iat))))
        m.refresh()
        assertFalse(m.gates(online = false).onDeviceModels)
        assertNull(s.token())
    }

    @Test fun anEmptyPinnedSetOpensNoGate() {
        server.enqueue(tokenResponse(mint()))
        server.enqueue(MockResponse().setBody(entitlementsBody))
        val m = manager(PinnedKeys.NONE)
        m.refresh()
        assertFalse(m.gates(online = false).onDeviceModels)
    }

    @Test fun aTokenForAnotherUserOrDeviceIsRejected() {
        server.enqueue(tokenResponse(mint()))
        server.enqueue(MockResponse().setBody(entitlementsBody))
        session.sub = "another-user"
        val m = manager()
        m.refresh()
        assertFalse(m.gates(online = true).onDeviceModels)
    }

    @Test fun expiredInGraceOpensTheGateOnlyOffline() {
        val m = manager()
        server.enqueue(tokenResponse(mint()))
        server.enqueue(MockResponse().setBody(entitlementsBody))
        m.refresh()
        now += 25 * 3600_000 // 25 h later: past exp (24 h), inside the 72 h grace
        assertTrue(m.gates(online = false).onDeviceModels)
        assertFalse(m.gates(online = true).onDeviceModels)
        now += 80 * 3600_000 // past exp + 72 h
        assertFalse(m.gates(online = false).onDeviceModels)
    }

    @Test fun aStaleTokenIsRefreshedWhenOnline() {
        val m = manager()
        server.enqueue(tokenResponse(mint()))
        server.enqueue(MockResponse().setBody(entitlementsBody))
        m.refresh()
        now += 20 * 3600_000 // less than 6 h of validity left
        server.enqueue(tokenResponse(mint(iat = now / 1000 - 60)))
        server.enqueue(MockResponse().setBody(entitlementsBody))
        m.refresh()
        assertEquals(4, server.requestCount)
        assertTrue(m.gates(online = true).onDeviceModels)
    }

    @Test fun aFreshTokenIsNotFetchedAgain() {
        val m = manager()
        server.enqueue(tokenResponse(mint()))
        server.enqueue(MockResponse().setBody(entitlementsBody))
        m.refresh()
        now += 60_000
        m.refresh()
        m.refresh(force = false)
        assertEquals(2, server.requestCount) // one token call, one entitlements call
    }

    /** Answers by path, so the order of the manager's calls does not matter. */
    private fun answer(vararg routes: Pair<String, MockResponse>) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                routes.firstOrNull { request.path!!.endsWith(it.first) }?.second ?: MockResponse().setResponseCode(500)
        }
    }

    private fun problemResponse(code: Int, error: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json")
        .setBody("""{"status":$code,"code":"$error","title":"t","type":"about:blank","correlationId":"c"}""")

    @Test fun a403DropsTheCachedToken() {
        val s = store()
        s.save(mint(), iat * 1000, null)
        val m = manager(store = s)
        m.loadCached()
        assertTrue(m.gates(online = false).onDeviceModels)
        answer("/me/entitlements/token" to problemResponse(403, "ACCOUNT_SUSPENDED"))
        m.refresh(force = true)
        assertFalse(m.gates(online = false).onDeviceModels)
        assertNull(s.token())
    }

    @Test fun a401WhoseRefreshIsRefusedDropsTheCachedToken() {
        val s = store()
        s.save(mint(), iat * 1000, null)
        val m = manager(store = s)
        m.loadCached()
        answer(
            "/me/entitlements/token" to problemResponse(401, "TOKEN_EXPIRED"),
            "/auth/refresh" to problemResponse(401, "UNAUTHENTICATED"),
        )
        m.refresh(force = true)
        assertFalse(m.gates(online = false).onDeviceModels)
        assertNull(s.token())
    }

    @Test fun offlineAnd429KeepTheCachedToken() {
        val s = store()
        s.save(mint(), iat * 1000, null)
        val m = manager(store = s)
        m.loadCached()
        answer("/me/entitlements/token" to problemResponse(429, "RATE_LIMITED").setHeader("Retry-After", "60"))
        m.refresh(force = true)
        assertTrue(m.gates(online = false).onDeviceModels)
        server.shutdown()
        now += 10 * 60_000
        m.refresh(force = true) // unreachable: no exception, token kept
        assertNotNull(s.token())
        assertTrue(m.gates(online = false).onDeviceModels)
    }

    @Test fun theNewestServerTimeSetsAFloorSoTheClockCannotBeSetBack() {
        val s = store()
        val serverNow = "Tue, 02 Jan 2029 10:00:00 GMT" // far after the token's grace
        server.enqueue(tokenResponse(mint(), date = serverNow))
        server.enqueue(MockResponse().setBody(entitlementsBody))
        val m = manager(store = s)
        m.refresh()
        now -= 30 * 24 * 3600_000L // the user turns the clock back
        // The floor is the server's Date: the token (iat ~2027) is long void in that time.
        val again = manager(store = s)
        again.loadCached()
        assertFalse(again.gates(online = false).onDeviceModels)
    }

    @Test fun signedOutClearsTheTokenAndHints() {
        val s = store()
        s.save(mint(), iat * 1000, null)
        val m = manager(store = s)
        m.loadCached()
        session.signedIn = false
        m.refresh()
        assertFalse(m.gates(online = false).onDeviceModels)
        assertNull(s.token())
        assertEquals(0, server.requestCount)
    }

    @Test fun planHintsComeFromEntitlements() {
        server.enqueue(tokenResponse(mint()))
        server.enqueue(MockResponse().setBody(entitlementsBody))
        val m = manager()
        m.refresh()
        val q = m.quota[AiTaskKind.TRANSLATE]!!
        assertEquals(3, q.remaining)
        assertEquals(10, q.limit)
        assertEquals(AiPeriod.DAILY, q.period)
        assertEquals(1_794_949_200_000L, q.resetsAtMs)
        assertEquals("Free", m.planLabel)
        assertNull("a feature the plan does not include has no hint", m.quota[AiTaskKind.GRAMMAR])
    }

    @Test fun theTokenStoreKeepsTheTimeFloorAcrossDropsAndIsEncrypted() {
        val s = store()
        s.save("a.b.c", 1_000, 5_000)
        s.dropToken()
        assertNull(s.token())
        assertEquals(5_000L, s.newestSeenMs())
        assertEquals(9_000L, s.monotonicNow(9_000))
        assertEquals(5_000L, s.monotonicNow(1_000))
        s.save("a.b.c", 1_000, null)
        val raw = rig.dir.walkTopDown().filter { it.isFile }.map { it.readBytes().decodeToString() }.joinToString()
        assertFalse(raw.contains("a.b.c"))
    }
}
