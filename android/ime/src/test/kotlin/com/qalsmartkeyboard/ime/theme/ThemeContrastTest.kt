package com.qalsmartkeyboard.ime.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every theme of the catalogue meets the contrast rules (WCAG 2.x); gradients are checked at every stop. */
class ThemeContrastTest {
    private val failures = ArrayList<String>()

    private fun check(theme: KeyboardTheme, what: String, fg: Int, bg: Int, min: Double) {
        val ratio = Wcag.contrast(fg, bg)
        if (ratio < min) failures += "${theme.id}: $what %.2f < $min".format(ratio)
    }

    @Test fun wcagMath() {
        assertEquals(21.0, Wcag.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.001)
        assertEquals(1.0, Wcag.contrast(0xFF777777.toInt(), 0xFF777777.toInt()), 0.001)
        assertEquals(4.48, Wcag.contrast(0xFF777777.toInt(), 0xFFFFFFFF.toInt()), 0.01) // the well-known #777 on white
    }

    @Test fun everyThemeMeetsTheContrastRules() {
        for (t in ThemeCatalog.all) {
            // Key labels, function-key labels, Enter text: 4.5:1 (also on the pressed fill, which shows the same label).
            check(t, "key label / key", t.keyText, t.key, 4.5)
            check(t, "key label / pressed", t.keyText, t.pressed, 4.5)
            check(t, "special label / special", t.specialText, t.special, 4.5)
            check(t, "special label / pressed", t.specialText, t.pressed, 4.5)
            check(t, "enter text / accent", t.onAccent, t.accent, 4.5)
            // Hint labels and icons: 3:1 on every fill they can sit on.
            check(t, "hint / key", t.hintText, t.key, 3.0)
            check(t, "hint / special", t.hintText, t.special, 3.0)
            check(t, "hint / pressed", t.hintText, t.pressed, 3.0)
            check(t, "accent label / key", t.accentText, t.key, 3.0)
            check(t, "accent label / popup", t.accentText, t.popupBg, 3.0)
            // Candidate strip, popup, toolbar.
            check(t, "candidate text / strip", t.stripText, t.stripBg, 4.5)
            check(t, "warn / strip", t.warn, t.stripBg, 4.5)
            check(t, "popup text / popup", t.popupText, t.popupBg, 4.5)
            check(t, "popup accent item", t.onAccent, t.accent, 4.5)
            // Anything drawn straight on the background: every gradient stop.
            for (stop in t.backgroundStops) {
                val hex = Integer.toHexString(stop)
                check(t, "candidate text / background $hex", t.stripText, stop, 4.5)
                check(t, "hint / background $hex", t.hintText, stop, 3.0)
                check(t, "warn / background $hex", t.warn, stop, 4.5)
            }
        }
        assertTrue("contrast failures:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun theStripFollowsTheBackgroundSoThereIsNoSeam() {
        for (t in ThemeCatalog.all) assertEquals(t.id, t.bgTop, t.stripBg)
    }

    @Test fun gradientsExistAndSolidThemesAreSolid() {
        val gradients = ThemeCatalog.all.filter { it.isGradient }.map { it.id }
        assertTrue(gradients.containsAll(listOf("premium_purple", "modern_gradient", "aurora", "sunset", "emerald")))
        assertTrue(!ThemeCatalog.classicLight.isGradient && !ThemeCatalog.classicDark.isGradient)
        for (t in ThemeCatalog.all.filter { it.isGradient }) assertEquals(2, t.backgroundStops.size)
    }
}
