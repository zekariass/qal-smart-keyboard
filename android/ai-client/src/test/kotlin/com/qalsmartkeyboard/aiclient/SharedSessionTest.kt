package com.qalsmartkeyboard.aiclient

import com.qalsmartkeyboard.api.SignedOutReason
import com.qalsmartkeyboard.api.TokenPair
import com.qalsmartkeyboard.ime.ai.AiBlock
import com.qalsmartkeyboard.ime.theme.PremiumThemeAccess
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ADR 0034: the keyboard and the companion app share one session. A sign-in, sign-out or account switch in either
 * reaches the keyboard's AI availability, plan state, entitlement token and premium themes at once (no restart, no
 * polling), and only a real account change fetches anything.
 */
class SharedSessionTest {
    private lateinit var server: MockWebServer
    private lateinit var rig: Rig
    private val conn = FakeConnectivity()
    private val direct = Executor { it.run() }
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var plan = "Free"

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.path) {
                    "/api/v1/me/entitlements" -> MockResponse().setHeader("Content-Type", "application/json").setBody(
                        """{"plan":{"planCode":"P","displayName":"$plan"},"features":[{"featureCode":"TRANSLATE","accessEnabled":true,"remaining":3,"includedUnits":10,"resetPeriod":"DAILY"}]}""",
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        rig = Rig(server)
        rig.session.signedOut(SignedOutReason.SIGNED_OUT) // the rig starts signed in; these tests start signed out
    }

    @After fun tearDown() = server.shutdown()

    /** An access token the client can read `sub` from (the server verifies it; the client never does). */
    private fun jwt(sub: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("""{"alg":"ES256"}""".toByteArray()) + "." +
            enc.encodeToString("""{"sub":"$sub","did":"device-1"}""".toByteArray()) + ".sig"
    }

    private class Built(val actions: ServerAiActions, val themes: ServerThemeAccess)

    private fun build(): Built {
        val session = SessionAccessInfo(rig.session)
        val entitlements = EntitlementManager(
            rig.clients.okHttpClient, rig.baseUrl, session, EntitlementTokenVerifier(PinnedKeys.NONE), EntitlementTokenStore(rig.files),
        )
        val actions = ServerAiActions(rig.clients.okHttpClient, rig.baseUrl, session, conn, { emptyList<com.qalsmartkeyboard.sync.DestinationProfile>() to emptyList() }, entitlements, { false }, direct)
        return Built(actions, ServerThemeAccess(entitlements, session, conn) { l -> actions.addAvailabilityListener(l) })
    }

    @Test fun startingTheKeyboardFetchesNothing() {
        rig.session.signedIn(TokenPair(jwt("user-a"), "r"))
        build()
        assertTrue(requests.isEmpty())
    }

    @Test fun signingInInTheAppUnblocksTheKeyboardAtOnceAndFetchesThePlan() {
        val b = build()
        var repaints = 0
        b.actions.addAvailabilityListener { repaints++ }
        var themeRepaints = 0
        b.themes.addListener { themeRepaints++ }
        assertEquals(AiBlock.SIGNED_OUT, b.actions.availability.block)
        assertEquals(PremiumThemeAccess.SIGNED_OUT, b.themes.premium)

        rig.session.signedIn(TokenPair(jwt("user-a"), "r")) // the companion app's sign-in

        val a = b.actions.availability
        assertEquals(AiBlock.NONE, a.block)
        assertEquals("Free", a.planLabel)
        assertEquals(3, a.quota.values.single().remaining)
        assertEquals(1L, a.accountGeneration)
        assertTrue(repaints > 0)
        assertTrue(themeRepaints > 0)
        assertEquals(PremiumThemeAccess.NEEDS_CHECK, b.themes.premium) // signed in; the token is still to come
        assertEquals(setOf("/api/v1/me/entitlements/token", "/api/v1/me/entitlements"), requests.map { it.path }.toSet())
        assertTrue(requests.all { it.getHeader("Authorization") == "Bearer ${jwt("user-a")}" })
    }

    @Test fun aTokenRotationRepaintsButFetchesNothing() {
        val b = build()
        rig.session.signedIn(TokenPair(jwt("user-a"), "r1"))
        requests.clear()
        var repaints = 0
        b.actions.addAvailabilityListener { repaints++ }
        rig.session.signedIn(TokenPair(jwt("user-a") + "x", "r2")) // same account, new pair (refresh)
        assertTrue(requests.isEmpty())
        assertEquals(1, repaints)
        assertEquals(1L, b.actions.availability.accountGeneration)
        assertEquals("Free", b.actions.availability.planLabel)
    }

    @Test fun anotherAccountDropsThePreviousPlanAndFetchesItsOwn() {
        val b = build()
        rig.session.signedIn(TokenPair(jwt("user-a"), "r"))
        assertEquals("Free", b.actions.availability.planLabel)
        plan = "Pro"
        requests.clear()
        rig.session.signedIn(TokenPair(jwt("user-b"), "r")) // guest -> account, or a different account
        assertEquals("Pro", b.actions.availability.planLabel)
        assertEquals(2L, b.actions.availability.accountGeneration)
        assertTrue(requests.all { it.getHeader("Authorization") == "Bearer ${jwt("user-b")}" })
    }

    @Test fun signingOutInTheAppSignsTheKeyboardOutAndForgetsThePlan() {
        val b = build()
        rig.session.signedIn(TokenPair(jwt("user-a"), "r"))
        requests.clear()
        var repaints = 0
        b.actions.addAvailabilityListener { repaints++ }
        rig.session.signedOut(SignedOutReason.SIGNED_OUT)
        val a = b.actions.availability
        assertEquals(AiBlock.SIGNED_OUT, a.block)
        assertNull(a.planLabel)
        assertTrue(a.quota.isEmpty())
        assertNull(a.entitled)
        assertEquals(2L, a.accountGeneration)
        assertTrue(repaints > 0)
        assertEquals(PremiumThemeAccess.SIGNED_OUT, b.themes.premium)
        assertTrue("signing out fetches nothing", requests.isEmpty())
    }

    @Test fun aSessionEndedByTheServerReachesTheKeyboardToo() {
        val b = build()
        rig.session.signedIn(TokenPair(jwt("user-a"), "r"))
        rig.session.signedOut(SignedOutReason.REFRESH_REJECTED) // e.g. the authenticator after a refused refresh
        assertEquals(AiBlock.SIGNED_OUT, b.actions.availability.block)
    }

    @Test fun closingStopsListening() {
        val b = build()
        b.actions.close()
        rig.session.signedIn(TokenPair(jwt("user-a"), "r"))
        assertTrue(requests.isEmpty())
        assertEquals(0L, b.actions.availability.accountGeneration)
    }
}
