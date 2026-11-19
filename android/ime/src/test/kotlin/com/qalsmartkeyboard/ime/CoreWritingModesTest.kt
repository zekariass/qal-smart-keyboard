package com.qalsmartkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Writing modes, phonetic key hints and Ge'ez punctuation on the real core with the bundled (DRAFT)
 * packs: the hints must be exactly what typing each key alone produces for that language's profile.
 */
class CoreWritingModesTest {
    private val engine get() = CoreTestEnv.engine
    private val hints: Map<String, PhoneticHints> by lazy { CoreComposer.deriveHints(CoreTestEnv.freshEngine()) }

    private fun session(lang: String, kind: LayoutKind): CoreTestEnv.Session =
        CoreTestEnv.Session().also {
            it.c.composer = CoreComposer(engine, hints)
            it.mode(TypingMode(lang, kind))
        }

    @Test fun hintsAreDerivedForBothEthiopicProfiles() {
        for (lang in listOf("am", "ti")) {
            val h = hints[lang]
            assertNotNull(lang, h)
            h!!
            assertTrue("$lang has hints", PhoneticHints.LETTERS.count { h.lower(it).isNotEmpty() } >= 10)
        }
        assertTrue(hints.keys.none { it == "om" || it == "en" })
    }

    @Test fun eachHintIsWhatTypingThatKeyAloneProduces() {
        for (lang in listOf("am", "ti")) {
            for (shifted in listOf(false, true)) {
                for (letter in PhoneticHints.LETTERS) {
                    val s = session(lang, LayoutKind.PHONETIC)
                    val key = s.c.layout.rows.flatten().first { it.kind == KeyKind.CHAR && it.text == letter.toString() }
                    if (shifted) s.tap(s.key(KeyKind.SHIFT))
                    s.tap(key)
                    val produced = s.ed.text.toString()
                    val expected = PhoneticHints.hintOf(produced).orEmpty()
                    val shown = if (shifted) key.shiftHint else key.hint
                    assertEquals("$lang ${if (shifted) letter.uppercaseChar() else letter} -> '$produced'", expected, shown)
                    assertEquals(letter.toString(), key.label) // the Latin letter stays the primary label
                }
            }
        }
    }

    @Test fun hintsAppearOnlyInPhoneticMode() {
        for (kind in listOf(LayoutKind.FIDEL, LayoutKind.ABC)) {
            val s = session("am", kind)
            assertTrue("$kind", s.c.layout.rows.flatten().filter { it.isLetter }.none { it.shiftHint != null })
        }
        val en = session("en", LayoutKind.ENGLISH)
        assertTrue(en.c.layout.rows.flatten().filter { it.isLetter }.all { it.hint.isEmpty() })
    }

    @Test fun englishWritingModeTypesLatinWithoutTransliteration() {
        val s = session("am", LayoutKind.PHONETIC)
        s.c.selectWritingMode(LayoutKind.ABC)
        s.type("selam ")
        assertEquals("selam ", s.ed.text.toString())
        assertEquals("", s.ed.composing)
        assertTrue(s.c.candidates.isEmpty())
        assertTrue(s.c.writingModeOptions.containsAll(listOf(LayoutKind.FIDEL, LayoutKind.PHONETIC, LayoutKind.ABC)))
    }

    // ---------------------------------------------------------------- Ge'ez punctuation on ?123

    @Test fun everyGeezMarkIsOnTheSymbolsPageInFidelAndPhoneticForBothLanguages() {
        for (lang in listOf("am", "ti")) {
            for (kind in listOf(LayoutKind.FIDEL, LayoutKind.PHONETIC)) {
                val s = session(lang, kind)
                s.tap(s.key(KeyKind.TO_SYMBOLS))
                val texts = s.c.layout.rows.flatten().filter { it.kind == KeyKind.CHAR }.map { it.text }
                for (m in Layouts.GEEZ_MARKS) assertTrue("$lang $kind lacks $m", m in texts)
                for (ascii in listOf(".", ",", "?", "!", "'", "\"", ":", ";", "-", "(", ")")) {
                    assertTrue("$lang $kind lacks $ascii", ascii in texts)
                }
                // typing a mark from the page
                s.tap(s.c.layout.rows.flatten().first { it.text == "፧" })
                assertTrue(s.ed.text.endsWith("፧"))
            }
        }
    }

    @Test fun lettersPageIsNotCrowdedByTheMarks() {
        val s = session("am", LayoutKind.PHONETIC)
        val letters = s.c.layout.rows.flatten().filter { it.kind == KeyKind.CHAR }.map { it.text }
        // Only ። and ፣ are keys (the period and comma keys, owner 2026-11-19); the other marks are not.
        assertTrue(Layouts.GEEZ_MARKS.filter { it != "።" && it != "፣" }.none { it in letters })
        // ... they are one long-press away on the period key, after the ASCII full stop
        val period = s.c.layout.rows.last().first { it.text == "።" }
        assertEquals(".", period.popup.first().text)
        assertTrue(Layouts.GEEZ_MARKS.filter { it != "።" }.all { m -> period.popup.any { it.text == m } })
    }

    @Test fun englishWritingModeHasNoGeezMarks() {
        val s = session("am", LayoutKind.ABC)
        s.tap(s.key(KeyKind.TO_SYMBOLS))
        assertTrue(s.c.layout.rows.flatten().none { it.text in Layouts.GEEZ_MARKS })
    }

    @Test fun marksMissingFromThePackAreFilledIn() {
        // A layout pack that ships only two marks (or none: no pack) still gets all seven on ?123.
        val grid = CoreComposer(engine).fidelGrid("am")!!.copy(punctuation = listOf("።", "፣"))
        for (g in listOf(grid, null)) {
            val ctx = LayoutContext(TypingMode("am", LayoutKind.PHONETIC), g)
            val texts = Layouts.forPage(Page.SYMBOLS, InputProfile.DEFAULT, ctx).rows.flatten().map { it.text }
            assertTrue(Layouts.GEEZ_MARKS.all { it in texts })
        }
    }
}
