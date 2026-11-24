package com.qalsmartkeyboard.ime

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A1.4 on the JVM with the real core and bundled (DRAFT) packs: layouts built from core data,
 * typing a word in each of the four languages, switching, punctuation and numerals.
 */
class CoreLayoutsTest {
    /** The one-tap ABC switch: no layout has the key since ADR 0035, the controller still handles it (the pill's English). */
    private val abcToggle = Key(KeyKind.ABC_TOGGLE, "ABC")

    private val engine get() = CoreTestEnv.engine
    private fun session(type: Int = InputType.TYPE_CLASS_TEXT) = CoreTestEnv.Session(type, prefs = CoreTestEnv.FidelFirst())

    private val amFidel = TypingMode("am", LayoutKind.FIDEL)
    private val amPhonetic = TypingMode("am", LayoutKind.PHONETIC)
    private val tiFidel = TypingMode("ti", LayoutKind.FIDEL)
    private val tiPhonetic = TypingMode("ti", LayoutKind.PHONETIC)
    private val om = TypingMode("om", LayoutKind.QUBEE)
    private val en = TypingMode("en", LayoutKind.ENGLISH)

    // ------------------------------------------------------------------ layout model

    @Test fun fidelLayoutIsBuiltFromCoreData() {
        val core = CoreComposer(engine)
        val am = core.fidelGrid("am")!!
        val ti = core.fidelGrid("ti")!!
        assertEquals(34, am.rows.sumOf { it.size })
        assertEquals(35, ti.rows.sumOf { it.size })
        val s = session()
        assertEquals(amFidel, s.c.effectiveMode) // default: first enabled language, native grid
        val keys = s.c.layout.rows.flatten()
        assertEquals(am.rows.flatten().map { it.seriesId }, keys.filter { it.kind == KeyKind.FIDEL_CONSONANT }.map { it.text })
        assertEquals(am.vowelKeys.map { it.order }, keys.filter { it.kind == KeyKind.FIDEL_VOWEL }.map { it.order })
        val ge = keys.first { it.kind == KeyKind.FIDEL_CONSONANT && it.text == "G" }
        val coreGe = am.rows.flatten().first { it.seriesId == "G" }
        assertEquals(coreGe.forms.map { it.text }, ge.popup.map { it.text })
        assertTrue("labialised forms are in the popup", coreGe.forms.any { it.order > 10 })
        assertTrue(s.c.candidatesAreDraft) // the bundled layout is still DRAFT data (not verified)
        s.mode(tiFidel)
        assertTrue(s.c.layout.rows.flatten().any { it.kind == KeyKind.FIDEL_CONSONANT && it.text == "QH" })
        // every key has a spoken description
        assertTrue(s.c.layout.rows.flatten().filter { !it.isSpacer && it.kind != KeyKind.ENTER }.all { it.spoken.isNotEmpty() })
    }

    @Test fun indicatorAndKeysFollowSd7() {
        val s = session()
        assertEquals("አማ · ፊደል", s.c.indicator)
        s.mode(amPhonetic)
        assertEquals("አማ · Phonetic", s.c.indicator)
        s.tap(abcToggle)
        assertEquals("አማ · English", s.c.indicator) // am/ti: ABC is the English writing mode
        // ADR 0035: no ABC key in the bottom row (the bar's pill switches writing modes).
        assertFalse(s.c.layout.rows.flatten().any { it.kind == KeyKind.ABC_TOGGLE })
        s.mode(om)
        assertEquals("OM · Qubee", s.c.indicator)
        s.mode(en)
        assertEquals("EN · QWERTY", s.c.indicator)
        assertEquals(listOf("አማርኛ", "ትግርኛ", "Afaan Oromoo", "English"), s.key(KeyKind.SPACE).popup.map { it.label })
    }

    // ------------------------------------------------------------------ four languages typeable

    @Test fun amharicFidelTwoKeyEntry() {
        val s = session()
        s.consonant("S")
        assertEquals("ስ", s.ed.composing) // the default order: the sixth
        assertTrue(s.c.composing)
        s.vowel(1)
        assertEquals("ሰ", s.ed.text.toString())
        s.consonant("L"); s.vowel(4)
        s.consonant("M"); s.vowel(6)
        s.tap(s.key(KeyKind.SPACE))
        assertEquals("ሰላም ", s.ed.text.toString())
        assertEquals("", s.ed.composing)
        s.consonant("G"); s.vowel(8) // labialised order: ጓ
        assertEquals("ሰላም ጓ", s.ed.text.toString())
    }

    @Test fun amharicFidelLongPressAndBackspace() {
        val s = session()
        val sa = s.c.layout.rows.flatten().first { it.kind == KeyKind.FIDEL_CONSONANT && it.text == "S" }
        s.pick(sa.popup.first { it.text == "ሲ" })
        s.pick(sa.popup.first { it.text == "ሳ" })
        assertEquals("ሲሳ", s.ed.text.toString())
        s.tap(s.key(KeyKind.BACKSPACE))
        assertEquals("ሲ", s.ed.text.toString()) // one syllable (cluster), not a UTF-16 unit
        s.consonant("S")
        s.tap(s.key(KeyKind.BACKSPACE)) // cancels the pending consonant
        assertEquals("ሲ", s.ed.text.toString())
        assertEquals("", s.ed.composing)
    }

    @Test fun tigrinyaFidelTypes() {
        val s = session()
        s.mode(tiFidel)
        s.consonant("QH"); s.vowel(1) // the series only Tigrinya has
        s.consonant("N"); s.vowel(6)
        assertEquals("ቐን", s.ed.text.toString())
    }

    @Test fun amharicPhoneticWithStrip() {
        val s = session()
        s.mode(amPhonetic)
        s.type("sela")
        assertEquals("ሰላ", s.ed.composing)
        assertTrue(s.c.showCandidateStrip)
        assertTrue(s.c.candidates.any { it.text == "ሰላም" })
        s.type("m ")
        assertEquals("ሰላም ", s.ed.text.toString())
    }

    @Test fun oromoQubeeTypesWithHudhaaOnTheMainPage() {
        val s = session()
        s.mode(om)
        assertEquals(Page.LETTERS, s.c.page)
        assertTrue(s.c.layout.rows.flatten().any { it.text == "'" })
        s.type("ba'e dhaabbata ")
        assertEquals("ba'e dhaabbata ", s.ed.text.toString()) // exactly as typed, never Ge'ez
        assertTrue(s.c.showCandidateStrip) // Afaan Oromoo word suggestions (om dictionary)
        // Latin punctuation only: no Ethiopic marks on the Qubee keys.
        val keys = s.c.layout.rows.flatten()
        assertTrue(keys.none { k -> k.text in Layouts.GEEZ_MARKS || k.popup.any { it.text in Layouts.GEEZ_MARKS } })
    }

    @Test fun englishTypesUnchanged() {
        val s = session()
        s.mode(en)
        s.type("hello, world. ")
        assertEquals("hello, world. ", s.ed.text.toString())
        assertFalse(s.c.layout.rows.flatten().any { it.kind == KeyKind.ABC_TOGGLE }) // no layout has the ABC key (ADR 0035)
    }

    @Test fun abcInAnEthiopicLanguageTypesLatin() {
        val s = session()
        s.tap(abcToggle)
        assertEquals(TypingMode.abc("am"), s.c.effectiveMode)
        s.type("hi ")
        assertEquals("hi ", s.ed.text.toString())
        s.tap(abcToggle)
        assertEquals(amFidel, s.c.effectiveMode)
    }

    // ------------------------------------------------------------------ punctuation and numerals

    @Test fun ethiopicPunctuationAndNumeralsAreReachable() {
        val s = session()
        // ADR 0007: on the Fidel grid a tap commits the Ethiopic mark; ASCII leads the popup
        val period = s.c.layout.rows.last().first { it.kind == KeyKind.CHAR && it.text == "።" }
        assertEquals(listOf(".", "፣", "፤", "፥", "፦", "፧", "፨", "፡"), period.popup.map { it.text })
        val comma = s.c.layout.rows.last().first { it.kind == KeyKind.CHAR && it.text == "፣" }
        // The comma's long-press opens the emoji panel; ፤ and the ASCII mark stay on the period and symbols keys.
        assertTrue(comma.popup.isEmpty())
        assertEquals(LongAction.EMOJI, comma.longAction)
        s.consonant("S"); s.vowel(1)
        s.tap(period)
        assertEquals("ሰ።", s.ed.text.toString())
        // the Fidel grid has no digit row: the digits are on the ?123 page (ADR 0043: Ethiopic numerals, the Western
        // digit on long-press), which also carries the marks as keys
        s.tap(s.key(KeyKind.TO_SYMBOLS))
        val sym = s.c.layout.rows.flatten()
        val one = sym.first { it.kind == KeyKind.CHAR && it.text == "፩" }
        assertEquals("1", one.popup.first().text)
        assertEquals("1", one.hint)
        s.tap(one)
        assertEquals("ሰ።፩", s.ed.text.toString())
        s.tap(sym.first { it.text == "፨" })
        assertEquals("ሰ።፩፨", s.ed.text.toString())
        s.tap(s.key(KeyKind.TO_LETTERS))
        // no Ethiopic extras in English or in ABC
        s.mode(en)
        assertTrue(s.c.layout.rows.flatten().all { it.popup.none { p -> p.text == "።" || p.text == "፩" } })
        s.mode(amFidel)
        s.tap(abcToggle)
        assertTrue(s.c.layout.rows.flatten().all { it.popup.none { p -> p.text == "።" || p.text == "፩" } })
        s.tap(s.key(KeyKind.TO_SYMBOLS))
        assertTrue(s.c.layout.rows.flatten().none { it.text == "፨" })
    }

    @Test fun phoneticAmharicTapsEthiopicPunctuationAndNumeralsWithWesternOnLongPress() {
        val s = session()
        s.mode(amPhonetic)
        // ADR 0043: the digit keys type Ethiopic numerals, the Western digit leads the popup.
        assertEquals("1", s.c.layout.rows.flatten().first { it.text == "፩" }.popup.first().text)
        // Product owner 2026-11-19: like the Fidel grid, a tap types ። / ፣ and the ASCII mark leads the popup.
        val period = s.c.layout.rows.last().first { it.kind == KeyKind.CHAR && it.text == "።" }
        val comma = s.c.layout.rows.last().first { it.kind == KeyKind.CHAR && it.text == "፣" }
        assertEquals(".", period.popup.first().text)
        // The comma's long-press opens the emoji panel; the ASCII comma lives on the symbols page.
        assertTrue(comma.popup.isEmpty())
        assertEquals(LongAction.EMOJI, comma.longAction)
        s.type("sela")
        s.tap(comma)
        s.type("selam")
        s.tap(period)
        assertEquals("ሰላ፣ሰላም።", s.ed.text.toString())
        assertEquals("", s.ed.composing)
        // The ASCII marks stay one long-press away on the period key, the comma on the symbols page.
        s.pick(period.popup.first())
        s.tap(s.key(KeyKind.TO_SYMBOLS))
        s.tap(s.c.layout.rows.flatten().first { it.kind == KeyKind.CHAR && it.text == "," })
        assertEquals("ሰላ፣ሰላም።.,", s.ed.text.toString())
    }

    @Test fun asciiPunctuationKeysInEveryNonGeezLayout() {
        val s = session()
        for (m in listOf(TypingMode("am", LayoutKind.ABC), TypingMode("ti", LayoutKind.ABC), TypingMode("om", LayoutKind.QUBEE), en)) {
            s.mode(m)
            val bottom = s.c.layout.rows.last()
            assertTrue("$m", bottom.any { it.kind == KeyKind.CHAR && it.text == "." })
            assertTrue("$m", bottom.any { it.kind == KeyKind.CHAR && it.text == "," })
            assertTrue("$m", bottom.none { it.text == "።" || it.text == "፣" })
        }
        s.mode(TypingMode("ti", LayoutKind.PHONETIC))
        assertTrue(s.c.layout.rows.last().any { it.kind == KeyKind.CHAR && it.text == "።" })
    }

    // ------------------------------------------------------------------ switching with the real core

    @Test fun switchingLosesAndDuplicatesNothing() {
        val s = session()
        s.consonant("S") // a pending Fidel consonant
        s.tap(s.key(KeyKind.GLOBE)) // -> ti
        assertEquals("ti", s.c.language)
        assertEquals("ስ", s.ed.text.toString())
        s.mode(tiPhonetic)
        s.type("sela")
        s.tap(s.key(KeyKind.GLOBE)) // -> om
        assertEquals("ስሰላ", s.ed.text.toString())
        s.type("ba")
        s.tap(s.key(KeyKind.GLOBE)) // -> en
        s.tap(s.key(KeyKind.GLOBE)) // wraps to am, on its last layout
        assertEquals(amFidel, s.c.effectiveMode)
        assertEquals("ስሰላba", s.ed.text.toString())
        assertEquals("", s.ed.composing)
    }

    @Test fun lastModeIsKeptPerLanguage() {
        val s = session()
        s.mode(amPhonetic)
        s.tap(s.key(KeyKind.GLOBE))
        assertEquals(tiFidel, s.c.effectiveMode)
        s.mode(tiPhonetic)
        s.mode(amPhonetic)
        s.c.switchLanguage("ti")
        assertEquals(tiPhonetic, s.c.effectiveMode)
        s.c.switchLanguage("am")
        assertEquals(amPhonetic, s.c.effectiveMode)
    }

    @Test fun globeLongPressCyclesLayoutsThroughAbc() {
        val s = session()
        val seen = ArrayList<TypingMode>()
        repeat(4) {
            s.c.onLongPress(s.key(KeyKind.GLOBE))
            seen += s.c.effectiveMode
        }
        assertEquals(listOf(TypingMode.abc("am"), amPhonetic, amFidel, TypingMode.abc("am")), seen)
    }

    @Test fun fieldRulesBeatLanguageChoice() {
        for (type in listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
        )) {
            val s = session(type)
            assertTrue(s.c.effectiveMode.isAbc)
            assertFalse(s.c.showCandidateStrip)
            assertTrue(s.c.layout.rows.flatten().none { it.kind == KeyKind.FIDEL_CONSONANT || it.kind == KeyKind.GLOBE })
            s.c.switchLanguage("om"); s.c.cycleLayout(); s.c.toggleAbc(); s.c.nextLanguage()
            assertEquals("am", s.c.language) // switching is refused in these fields
        }
        val s = session(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        s.type("pa1")
        assertEquals("pa1", s.ed.text.toString())
        assertNotNull(s.c.layout)
    }

    @Test fun backToATextFieldRestoresTheNativeLayout() {
        val s = session()
        s.c.startField(InputProfile.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0, false, 0))
        assertTrue(s.c.effectiveMode.isAbc)
        s.c.startField(InputProfile.from(InputType.TYPE_CLASS_TEXT, 0, false, 0))
        assertEquals(amFidel, s.c.effectiveMode)
    }
}
