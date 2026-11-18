package com.qalsmartkeyboard.ime.theme

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which theme is drawn for a stored choice and the entitlement: the gating rules. */
class ThemeResolverTest {
    @After fun reset() = ThemeAccessHolder.resetForTest()

    private fun resolve(id: String?, dark: Boolean = false, access: PremiumThemeAccess = PremiumThemeAccess.ALLOWED) =
        ThemeResolver.resolve(id, dark, access)

    @Test fun systemFollowsTheDeviceMode() {
        assertSame(ThemeCatalog.classicLight, resolve("system", dark = false).theme)
        assertSame(ThemeCatalog.classicDark, resolve("system", dark = true).theme)
        assertFalse(resolve("system").lockedFallback)
    }

    @Test fun unknownIdsFallBackToSystem() {
        assertSame(ThemeCatalog.classicDark, resolve("neon_future", dark = true).theme)
        assertSame(ThemeCatalog.classicLight, resolve(null, dark = false).theme)
        assertFalse(resolve("neon_future").lockedFallback)
    }

    @Test fun freeThemesNeverNeedTheEntitlement() {
        for (t in ThemeCatalog.free) {
            for (a in PremiumThemeAccess.entries) {
                val r = resolve(t.id, access = a)
                assertSame(t.id, t, r.theme)
                assertFalse(r.lockedFallback)
            }
        }
    }

    @Test fun entitledShowsThePremiumTheme() {
        for (t in ThemeCatalog.premium) {
            val r = resolve(t.id, access = PremiumThemeAccess.ALLOWED)
            assertSame(t, r.theme)
            assertFalse(r.lockedFallback)
        }
    }

    @Test fun notEntitledSignedOutOrExpiredRenderTheFreeFallbackByLightness() {
        for (a in listOf(PremiumThemeAccess.NOT_ON_PLAN, PremiumThemeAccess.SIGNED_OUT, PremiumThemeAccess.NEEDS_CHECK)) {
            for (t in ThemeCatalog.premium) {
                val r = resolve(t.id, dark = !t.isLight, access = a)
                assertSame("${t.id} / $a", ThemeCatalog.classic(t.isLight), r.theme)
                assertTrue(r.lockedFallback)
                assertEquals(t.id, r.requestedId) // the choice is kept, only the drawing falls back
                assertEquals(a, r.access)
            }
        }
        // Light premium -> Classic Light, dark premium -> Classic Dark, whatever the device mode is.
        assertSame(ThemeCatalog.classicLight, resolve("modern_gradient", dark = true, access = PremiumThemeAccess.NOT_ON_PLAN).theme)
        assertSame(ThemeCatalog.classicDark, resolve("amoled_black", dark = false, access = PremiumThemeAccess.SIGNED_OUT).theme)
    }

    @Test fun holderBuildsThePortOnceAndNeedsAFactory() {
        assertNull(ThemeAccessHolder.create())
        var built = 0
        val port = object : ThemeAccess {
            override val premium = PremiumThemeAccess.ALLOWED
            override fun addListener(listener: () -> Unit) = AutoCloseable { }
        }
        ThemeAccessHolder.factory = { built++; port }
        assertSame(port, ThemeAccessHolder.create())
        assertSame(port, ThemeAccessHolder.create())
        assertEquals(1, built)
    }
}
