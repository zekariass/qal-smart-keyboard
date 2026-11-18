package com.qalsmartkeyboard.ime.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeCatalogTest {
    private val freeNames = listOf("Classic Light", "Classic Dark", "Minimal White", "Midnight", "Forest Green", "Ocean Blue", "Lavender", "Soft Pink")
    private val premiumNames = listOf("Premium Purple", "AMOLED Black", "Emerald", "Sunset", "Aurora", "Elegant Gold", "Modern Gradient")

    @Test fun idsAreUniqueShortAndWellFormed() {
        val ids = ThemeCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (id in ids) {
            assertTrue(id, id.length in 1..ThemeCatalog.MAX_ID_LENGTH)
            assertTrue(id, Regex("[a-z][a-z0-9_]*").matches(id))
        }
        assertTrue(ThemeCatalog.SYSTEM_ID !in ids)
        assertEquals(ThemeCatalog.all.map { it.name }.toSet().size, ThemeCatalog.all.size)
    }

    @Test fun requiredThemesArePresentInTheirTier() {
        assertEquals(freeNames, ThemeCatalog.free.map { it.name })
        assertTrue(ThemeCatalog.free.all { it.tier == ThemeTier.FREE && !it.isPremium })
        val premium = ThemeCatalog.premium.map { it.name }
        assertTrue(premium.containsAll(premiumNames))
        assertTrue("3 to 5 more premium themes", premium.size - premiumNames.size in 3..5)
        assertTrue(ThemeCatalog.premium.all { it.isPremium })
        assertEquals(ThemeCatalog.all.size, ThemeCatalog.free.size + ThemeCatalog.premium.size)
    }

    @Test fun lookupAndClassicFallback() {
        assertNull(ThemeCatalog.find("system"))
        assertNull(ThemeCatalog.find("nope"))
        assertNull(ThemeCatalog.find(null))
        assertSame(ThemeCatalog.classicLight, ThemeCatalog.find("classic_light"))
        assertSame(ThemeCatalog.classicDark, ThemeCatalog.find("classic_dark"))
        assertSame(ThemeCatalog.classicLight, ThemeCatalog.classic(true))
        assertSame(ThemeCatalog.classicDark, ThemeCatalog.classic(false))
        assertTrue(ThemeCatalog.classicLight.isLight && !ThemeCatalog.classicDark.isLight)
        assertNotNull(ThemeCatalog.find("amoled_black"))
    }

    @Test fun classicThemesKeepTheKeyboardsOriginalColours() {
        val l = ThemeCatalog.classicLight
        assertEquals(0xFFE4E5EA.toInt(), l.bgTop)
        assertEquals(0xFFFFFFFF.toInt(), l.key)
        assertEquals(0xFFC9CBD3.toInt(), l.special)
        assertEquals(0xFF2E7D32.toInt(), l.accent)
        val d = ThemeCatalog.classicDark
        assertEquals(0xFF1B1B1F.toInt(), d.bgTop)
        assertEquals(0xFF3A3B40.toInt(), d.key)
        assertEquals(0xFF5A5C66.toInt(), d.pressed)
    }

    @Test fun shapesAreSane() {
        for (t in ThemeCatalog.all) {
            assertTrue(t.id, t.cornerRadiusDp in 4f..14f)
            assertEquals(t.id, t.hasBorder, t.borderWidthDp > 0f)
            assertEquals(t.id, t.hasShadow, t.shadowDyDp > 0f)
            assertEquals(t.id, 0xFF, t.bgTop ushr 24)
            assertEquals(t.id, 0xFF, t.key ushr 24)
        }
    }
}
