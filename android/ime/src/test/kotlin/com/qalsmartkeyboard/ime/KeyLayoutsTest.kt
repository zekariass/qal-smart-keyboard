package com.qalsmartkeyboard.ime

import android.text.InputType
import com.qalsmartkeyboard.data.KeyboardSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR 0043: the Latin key arrangements, the number-row switch and the long-press Fidel grid. */
class KeyLayoutsTest {
    private val text = InputProfile.from(InputType.TYPE_CLASS_TEXT, 0, false, 0)

    private fun letters(mode: TypingMode, layout: LatinLayout = LatinLayout.QWERTY, numberRow: Boolean = true, hints: PhoneticHints? = null) =
        Layouts.forPage(Page.LETTERS, text, LayoutContext(mode, latinLayout = layout, numberRow = numberRow, hints = hints))

    private fun List<Key>.texts() = filter { !it.isSpacer && it.kind == KeyKind.CHAR }.joinToString("") { it.text }

    private fun List<Key>.units() = sumOf { it.weight.toDouble() }.toFloat()

    private val en = TypingMode("en", LayoutKind.ENGLISH)
    private val om = TypingMode("om", LayoutKind.QUBEE)

    @Test fun everyArrangementHasTheTwentySixLettersOnce() {
        for (l in LatinLayout.entries) {
            val letters = l.rows.joinToString("").filter { it.isLetter() }
            assertEquals(l.name, ('a'..'z').joinToString(""), letters.toList().sorted().joinToString(""))
            assertEquals(3, l.rows.size)
        }
        assertTrue(LatinLayout.DVORAK.hasApostrophe)
        assertFalse(LatinLayout.QWERTY.hasApostrophe)
    }

    @Test fun qwertyIsExactlyTheLayoutItWasBefore() {
        val rows = letters(en).rows
        assertEquals(5, rows.size)
        assertEquals("1234567890", rows[0].texts())
        assertEquals("qwertyuiop", rows[1].texts())
        assertEquals(listOf(0.5f) + List(9) { 1f } + 0.5f, rows[2].map { it.weight })
        assertEquals("asdfghjkl", rows[2].texts())
        assertEquals(listOf(KeyKind.SHIFT) + List(7) { KeyKind.CHAR } + KeyKind.BACKSPACE, rows[3].map { it.kind })
        assertEquals(listOf(1.5f) + List(7) { 1f } + 1.5f, rows[3].map { it.weight })
        // Qubee: the hudhaa after M, shift and delete 1.25 wide (SD 7.3).
        val q = letters(om).rows[3]
        assertEquals("zxcvbnm'", q.texts())
        assertEquals(listOf(1.25f) + List(8) { 1f } + 1.25f, q.map { it.weight })
    }

    @Test fun eachArrangementBuildsItsRows() {
        for (l in LatinLayout.entries) {
            val rows = letters(en, l).rows
            assertEquals(l.name, l.rows[0], rows[1].texts())
            assertEquals(l.name, l.rows[1], rows[2].texts())
            assertEquals(l.name, l.rows[2], rows[3].texts())
            assertEquals(KeyKind.SHIFT, rows[3].first().kind)
            assertEquals(KeyKind.BACKSPACE, rows[3].last().kind)
            assertTrue("${l.name}: shift and delete never narrower than 1.25", rows[3].first().weight >= 1.25f)
            // Letter rows fill the width of ten keys (the Dvorak bottom row is a little wider: narrower keys).
            for (r in 1..3) assertTrue("${l.name} row $r", rows[r].units() >= 10f)
            // Every key can be spoken.
            assertTrue(rows.flatten().filter { !it.isSpacer && it.kind != KeyKind.ENTER }.all { it.spoken.isNotEmpty() })
        }
        assertEquals("Apostrophe, hudhaa", letters(en, LatinLayout.DVORAK).rows[1].first().spoken)
    }

    @Test fun qubeeHasOneHudhaaKeyOnEveryArrangement() {
        for (l in LatinLayout.entries) {
            val keys = letters(om, l).rows.flatten()
            assertEquals(l.name, 1, keys.count { it.kind == KeyKind.CHAR && it.text == "'" })
        }
        assertEquals("wxcvbn'", letters(om, LatinLayout.AZERTY).rows[3].texts())
        assertEquals("qjkxbmwvz", letters(om, LatinLayout.DVORAK).rows[3].texts()) // the hudhaa is Dvorak's own top-row key
    }

    @Test fun phoneticHintsFollowTheLetters() {
        val hints = PhoneticHints(mapOf('s' to "ስ", 'q' to "ቅ", 'a' to "ኣ"), mapOf('s' to "ሥ"))
        val am = TypingMode("am", LayoutKind.PHONETIC)
        for (l in LatinLayout.entries) {
            val keys = letters(am, l, hints = hints).rows.flatten().filter { it.isLetter }
            assertEquals(26, keys.size)
            for (k in keys) {
                assertEquals("${l.name} ${k.text}", hints.lower(k.text[0]), k.hint)
                assertEquals("${l.name} ${k.text}", hints.upper(k.text[0]), k.shiftHint)
            }
        }
    }

    @Test fun numberRowOffPutsTheDigitsOnTheTopRow() {
        val rows = letters(en, numberRow = false).rows
        assertEquals(4, rows.size)
        val top = rows[0].filter { !it.isSpacer }
        assertEquals("qwertyuiop", top.texts())
        assertEquals("1234567890".map { it.toString() }, top.map { it.popup.first().text })
        assertEquals("1234567890".map { it.toString() }, top.map { it.hint })
        // A letter still types itself on tap.
        assertEquals("q", top.first().text)
        // Colemak's top row has nine letters: 1-9 there, 0 stays on ?123.
        val colemak = letters(en, LatinLayout.COLEMAK, numberRow = false).rows[0].filter { !it.isSpacer }
        assertEquals("123456789".map { it.toString() }, colemak.map { it.popup.first().text })
        // The symbols page keeps its digit row.
        val sym = Layouts.forPage(Page.SYMBOLS, text, LayoutContext(en, numberRow = false))
        assertEquals("1234567890", sym.rows[0].texts())
    }

    @Test fun numberRowOffWithEthiopicNumeralsAndPhoneticHints() {
        val hints = PhoneticHints(mapOf('q' to "ቅ"), emptyMap())
        val am = TypingMode("am", LayoutKind.PHONETIC)
        val ctx = LayoutContext(am, ethiopicNumerals = true, hints = hints, numberRow = false)
        val q = Layouts.forPage(Page.LETTERS, text, ctx).rows[0].first()
        assertEquals("q", q.text)
        assertEquals("ቅ", q.hint) // the phonetic hint stays
        assertEquals(listOf("፩", "1", "፲"), q.popup.map { it.text })
    }

    @Test fun arabicNumberRowOff() {
        val ar = TypingMode("ar", LayoutKind.ARABIC)
        val on = Layouts.forPage(Page.LETTERS, text, LayoutContext(ar))
        val off = Layouts.forPage(Page.LETTERS, text, LayoutContext(ar, numberRow = false))
        assertEquals(on.rows.size - 1, off.rows.size)
        val first = off.rows[0].first()
        assertEquals("ض", first.text)
        assertEquals(listOf("1", "١"), first.popup.map { it.text })
        // Letter alternatives stay after the digits (ه: ة).
        assertTrue(off.rows[0].first { it.text == "ه" }.popup.map { it.text }.containsAll(listOf("8", "ة")))
    }

    // ------------------------------------------------------------------ with the real core

    private fun session(s: KeyboardSettings): CoreTestEnv.Session {
        val p = SettingsPreferences({ s })
        return CoreTestEnv.Session(languages = p, prefs = p)
    }

    @Test fun transliterationWorksOnEveryArrangement() {
        val expected = session(KeyboardSettings.DEFAULT).also { it.type("selam ") }.ed.text.toString()
        assertTrue(expected.isNotBlank())
        for (l in LatinLayout.entries) {
            val s = session(KeyboardSettings.DEFAULT.copy(latinLayouts = mapOf("am" to l.name)))
            assertEquals(l.rows[0], s.c.layout.rows[1].texts().filter { it.isLetter() || it in "',." })
            s.type("selam ")
            assertEquals(l.name, expected, s.ed.text.toString())
        }
    }

    @Test fun realHintsFollowTheLettersOnDvorak() {
        fun hintsOf(s: CoreTestEnv.Session) = s.c.layout.rows.flatten().filter { it.isLetter }.associate { it.text to it.hint }
        val qwerty = hintsOf(session(KeyboardSettings.DEFAULT))
        val dvorak = hintsOf(session(KeyboardSettings.DEFAULT.copy(latinLayouts = mapOf("am" to "DVORAK"))))
        assertEquals(26, qwerty.size)
        assertEquals(qwerty, dvorak)
    }

    @Test fun theArrangementIsPerLanguageAndCoversAbc() {
        val s = session(KeyboardSettings.DEFAULT.copy(latinLayouts = mapOf("am" to "AZERTY", "om" to "DVORAK")))
        assertEquals("azertyuiop", s.c.layout.rows[1].texts())
        s.mode(TypingMode.abc("am")) // the English writing mode of Amharic follows Amharic's choice
        assertEquals("azertyuiop", s.c.layout.rows[1].texts())
        s.mode(TypingMode("om", LayoutKind.QUBEE))
        assertEquals("',.pyfgcrl", s.c.layout.rows[1].texts())
        s.mode(TypingMode("en", LayoutKind.ENGLISH))
        assertEquals("qwertyuiop", s.c.layout.rows[1].texts())
        assertEquals("EN · QWERTY", s.c.indicator)
        // English names its arrangement on the space bar and to TalkBack.
        val dv = session(KeyboardSettings.DEFAULT.copy(latinLayouts = mapOf("en" to "DVORAK")))
        dv.mode(TypingMode("en", LayoutKind.ENGLISH))
        assertEquals("EN · Dvorak", dv.c.indicator)
        assertTrue(dv.key(KeyKind.SPACE).spoken.endsWith("Dvorak"))
        // A field that stays Latin (e-mail) uses the language's arrangement too.
        val mail = SettingsPreferences({ KeyboardSettings.DEFAULT.copy(latinLayouts = mapOf("am" to "QWERTZ")) })
        val m = CoreTestEnv.Session(type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, languages = mail, prefs = mail)
        assertEquals("qwertzuiop", m.c.layout.rows[1].texts())
    }

    @Test fun longPressFidelGridHasNoVowelRowAndTypesTheSixthOrder() {
        val s = session(KeyboardSettings.DEFAULT.copy(fidelLayouts = mapOf("am" to "LONG_PRESS")))
        s.mode(TypingMode("am", LayoutKind.FIDEL))
        val keys = s.c.layout.rows.flatten()
        assertTrue(keys.none { it.kind == KeyKind.FIDEL_CONSONANT || it.kind == KeyKind.FIDEL_VOWEL })
        val two = session(KeyboardSettings.DEFAULT).also { it.mode(TypingMode("am", LayoutKind.FIDEL)) }
        assertEquals(two.c.layout.rows.size - 1, s.c.layout.rows.size)
        val se = keys.first { it.description == "ሰ" }
        assertEquals("ስ", se.text)
        assertEquals("ስ", se.label)
        assertTrue(se.popup.map { it.text }.containsAll(listOf("ሰ", "ሱ", "ሲ", "ሳ", "ሴ", "ሶ")))
        s.tap(se)
        s.pick(keys.first { it.description == "ለ" }.popup.first { it.text == "ላ" })
        assertEquals("ስላ", s.ed.text.toString())
        assertFalse(s.c.composing)
        // The keyboard keeps about the same height: the rows grow by what the vowel row took.
        assertEquals(two.c.layout.rowHeightDp * two.c.layout.rows.size, s.c.layout.rowHeightDp * s.c.layout.rows.size, 0.5f)
    }

    @Test fun variantsNeedBothInteractionsInThePack() {
        val grid = CoreComposer(CoreTestEnv.engine).fidelGrid("am")
        assertNotNull(grid)
        assertEquals(FidelVariant.entries, KeyLayouts.fidelVariants(grid))
        assertTrue(KeyLayouts.apply(grid!!, FidelVariant.TWO_KEY).twoKey)
        assertFalse(KeyLayouts.apply(grid, FidelVariant.LONG_PRESS).twoKey)
        assertEquals(emptyList<FidelVariant>(), KeyLayouts.fidelVariants(grid.copy(twoKey = false)))
        assertTrue(KeyLayouts.apply(grid.copy(longPress = false), FidelVariant.LONG_PRESS).twoKey)
    }
}
