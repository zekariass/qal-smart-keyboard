package com.qalsmartkeyboard.aiclient

import com.qalsmartkeyboard.ime.theme.PremiumThemeAccess
import com.qalsmartkeyboard.ime.theme.ThemeCatalog
import com.qalsmartkeyboard.ime.theme.ThemeResolver
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * The keyboard's premium-theme port over the verified entitlement token: entitled, not entitled, offline grace,
 * grace expired and signed out, and what the keyboard then draws.
 */
class ServerThemeAccessTest {
    private lateinit var server: MockWebServer
    private lateinit var rig: Rig
    private val key = TestKey("ent-2026-11")
    private val session = FakeSession()
    private val connectivity = FakeConnectivity()
    private var now = 1_800_000_000_000L
    private val iat get() = now / 1000 - 3600
    private lateinit var store: EntitlementTokenStore
    private lateinit var manager: EntitlementManager
    private lateinit var access: ServerThemeAccess

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        rig = Rig(server)
        store = EntitlementTokenStore(rig.files)
        manager = EntitlementManager(rig.clients.okHttpClient, rig.baseUrl, session, EntitlementTokenVerifier(key.pinned()), store) { now }
        access = ServerThemeAccess(manager, session, connectivity) { listener -> connectivity.addListener(listener) }
    }

    @After fun tearDown() = server.shutdown()

    private fun cacheToken(themes: Boolean?) {
        val features = buildString {
            append("""{"ON_DEVICE_AI_MODELS":${themes == true}""")
            if (themes != null) append(""","PREMIUM_THEMES":$themes""")
            append("}")
        }
        store.save(TestTokens.mint(key, TestTokens.claims(iat, features = features)), iat * 1000, null)
        manager.loadCached()
    }

    @Test fun entitledPlanOpensPremiumThemes() {
        cacheToken(themes = true)
        assertEquals(PremiumThemeAccess.ALLOWED, access.premium)
        assertSame(ThemeCatalog.find("aurora"), ThemeResolver.resolve("aurora", false, access.premium).theme)
    }

    @Test fun aPlanWithoutPremiumThemesIsNotEntitled() {
        cacheToken(themes = false)
        assertEquals(PremiumThemeAccess.NOT_ON_PLAN, access.premium)
        val r = ThemeResolver.resolve("aurora", false, access.premium)
        assertSame(ThemeCatalog.classicDark, r.theme) // Aurora is dark
    }

    @Test fun anOlderTokenWithoutTheFeatureIsNotEntitled() {
        cacheToken(themes = null)
        assertEquals(PremiumThemeAccess.NOT_ON_PLAN, access.premium)
    }

    @Test fun noTokenYetNeedsACheck() {
        manager.loadCached()
        assertEquals(PremiumThemeAccess.NEEDS_CHECK, access.premium)
    }

    @Test fun offlineGraceKeepsThemesOnlyWhileOffline() {
        cacheToken(themes = true)
        now += 25 * 3600_000 // past exp (24 h), inside the 72 h grace
        connectivity.online = false
        assertEquals(PremiumThemeAccess.ALLOWED, access.premium)
        connectivity.online = true // online: a fresh token is due; until it arrives the gate is shut
        assertEquals(PremiumThemeAccess.NEEDS_CHECK, access.premium)
        assertSame(ThemeCatalog.classicLight, ThemeResolver.resolve("modern_gradient", false, access.premium).theme)
    }

    @Test fun graceExpiredFallsBack() {
        cacheToken(themes = true)
        now += 100 * 3600_000 // past exp + 72 h
        connectivity.online = false
        assertEquals(PremiumThemeAccess.NEEDS_CHECK, access.premium)
        assertSame(ThemeCatalog.classicDark, ThemeResolver.resolve("amoled_black", true, access.premium).theme)
    }

    @Test fun signedOutFallsBackWhateverTheCachedToken() {
        cacheToken(themes = true)
        session.signedIn = false
        assertEquals(PremiumThemeAccess.SIGNED_OUT, access.premium)
        assertSame(ThemeCatalog.classicDark, ThemeResolver.resolve("elegant_gold", true, access.premium).theme)
    }

    @Test fun listenersAreForwardedAndRemovable() {
        var calls = 0
        val handle = access.addListener { calls++ }
        connectivity.set(false)
        assertEquals(1, calls)
        handle.close()
        connectivity.set(true)
        assertEquals(1, calls)
    }
}
