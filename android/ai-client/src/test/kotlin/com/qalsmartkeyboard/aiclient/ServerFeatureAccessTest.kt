package com.qalsmartkeyboard.aiclient

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The generic boolean-feature gate (the card maker's `CARD_MAKER`): from the verified token when it carries the feature,
 * else from the plan hints of `/me/entitlements`; signed out, not on the plan and "connect once to check" are told apart.
 */
class ServerFeatureAccessTest {
    private lateinit var server: MockWebServer
    private lateinit var rig: Rig
    private val key = TestKey("ent-2026-11")
    private val session = FakeSession()
    private val connectivity = FakeConnectivity()
    private var now = 1_800_000_000_000L
    private val iat get() = now / 1000 - 3600
    private lateinit var store: EntitlementTokenStore
    private lateinit var manager: EntitlementManager
    private lateinit var access: ServerFeatureAccess

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        rig = Rig(server)
        store = EntitlementTokenStore(rig.files)
        manager = EntitlementManager(rig.clients.okHttpClient, rig.baseUrl, session, EntitlementTokenVerifier(key.pinned()), store) { now }
        access = ServerFeatureAccess(ServerFeatureAccess.CARD_MAKER, manager, session, connectivity) { l -> connectivity.addListener(l) }
    }

    @After fun tearDown() = server.shutdown()

    private fun cacheToken(features: String) {
        store.save(TestTokens.mint(key, TestTokens.claims(iat, features = features)), iat * 1000, null)
        manager.loadCached()
    }

    private fun planHint(vararg enabled: String) {
        val feats = enabled.joinToString(",") { """{"featureCode":"$it","accessEnabled":true,"unit":"NONE","resetPeriod":"NONE"}""" }
        server.enqueue(MockResponse().setResponseCode(404)) // /me/entitlements/token
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"plan":{"planCode":"X","displayName":"X","planRank":1,"source":"DEFAULT"},"features":[$feats],
                "entitlementToken":{"jws":"x","keyId":"k","expiresAt":"2027-01-01T00:00:00Z","offlineGraceUntil":"2027-01-04T00:00:00Z"}}""",
            ),
        )
        manager.refresh(force = true)
    }

    @Test fun beforeAnyAnswerItNeedsACheck() {
        assertEquals(FeatureAccess.NEEDS_CHECK, access.access)
        assertNull(manager.entitledFeatureCodes)
    }

    @Test fun signedOutIsReportedWhateverIsCached() {
        cacheToken("""{"ON_DEVICE_AI_MODELS":true,"CARD_MAKER":true}""")
        session.signedIn = false
        assertEquals(FeatureAccess.SIGNED_OUT, access.access)
    }

    @Test fun aTokenThatCarriesTheFeatureOpensIt() {
        cacheToken("""{"ON_DEVICE_AI_MODELS":true,"CARD_MAKER":true}""")
        assertEquals(FeatureAccess.ALLOWED, access.access)
    }

    @Test fun thePlanHintOpensItWhenTheTokenDoesNotCarryIt() {
        cacheToken("""{"ON_DEVICE_AI_MODELS":true,"PREMIUM_THEMES":true}""")
        assertEquals(FeatureAccess.NEEDS_CHECK, access.access) // no plan answer yet
        planHint("CARD_MAKER", "TRANSLATE")
        assertEquals(FeatureAccess.ALLOWED, access.access)
    }

    @Test fun aPlanWithoutTheFeatureIsNotOnPlan() {
        planHint("TRANSLATE")
        assertEquals(FeatureAccess.NOT_ON_PLAN, access.access)
    }

    @Test fun theGrantIsForgottenOnSignOut() {
        planHint("CARD_MAKER")
        assertEquals(FeatureAccess.ALLOWED, access.access)
        manager.clear()
        assertNull(manager.entitledFeatureCodes)
        assertEquals(FeatureAccess.NEEDS_CHECK, access.access)
    }

    @Test fun aTokenInOfflineGraceCountsOnlyOffline() {
        cacheToken("""{"ON_DEVICE_AI_MODELS":true,"CARD_MAKER":true}""")
        now += 25 * 3600_000
        connectivity.online = false
        assertEquals(FeatureAccess.ALLOWED, access.access)
        connectivity.online = true
        assertEquals(FeatureAccess.NEEDS_CHECK, access.access)
    }

    @Test fun listenersAreForwarded() {
        var calls = 0
        access.addListener { calls++ }.use { connectivity.set(false) }
        assertEquals(1, calls)
    }
}
