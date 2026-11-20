package com.qalsmartkeyboard.ime

import com.qalsmartkeyboard.data.InputMode
import com.qalsmartkeyboard.data.KeyboardSettings
import com.qalsmartkeyboard.data.NumeralStyle
import com.qalsmartkeyboard.data.TypingLanguageSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A1.5 in the IME: the settings adapter, first-run defaults and the decisions of ADR 0007. */
class SettingsPreferencesTest {
    private fun prefs(s: KeyboardSettings, saved: MutableList<Pair<String, String>> = ArrayList()) =
        SettingsPreferences({ s }) { l, m -> saved += l to m }

    private fun order(vararg codes: String, disabled: Set<String> = emptySet()) = KeyboardSettings(
        languages = codes.map { TypingLanguageSetting(it, enabled = it !in disabled) },
    ).sanitized()

    @Test fun enabledLanguagesFollowSettingsOrderAndFlags() {
        val s = order("en", "am", "om", "ti", disabled = setOf("om"))
        assertEquals(listOf("en", "am", "ti"), prefs(s).enabled())
    }

    @Test fun adapterMapsModesAndRememberedLayouts() {
        val base = KeyboardSettings.DEFAULT
        val s = base.copy(
            languages = base.languages.map { if (it.languageCode == "ti") it.copy(inputMode = InputMode.DIRECT_SCRIPT_LAYOUT) else it },
            lastModes = mapOf("am" to "FIDEL", "om" to "ABC", "ti" to "QUBEE"),
            numeralStyle = NumeralStyle.ETHIOPIC, hapticsEnabled = false, keyPreviewEnabled = false, keyHeightPercent = 120,
        )
        val p = prefs(s)
        assertEquals(LayoutKind.PHONETIC, p.preferredKind("am"))
        assertEquals(LayoutKind.FIDEL, p.preferredKind("ti"))
        assertNull(p.preferredKind("om")) // passthrough: native Latin layout
        assertEquals(LayoutKind.FIDEL, p.lastNative("am"))
        assertNull(p.lastNative("om")) // ABC is never a remembered native layout
        assertNull(p.lastNative("ti")) // a layout the language does not have is ignored
        assertFalse(p.ethiopicNumerals) // ADR 0044: a stored ETHIOPIC that was never chosen is not in force
        assertTrue(prefs(s.copy(numeralStyleChosen = true)).ethiopicNumerals)
        assertFalse(p.hapticsEnabled)
        assertFalse(p.keyPreviewEnabled)
        assertTrue(prefs(KeyboardSettings.DEFAULT).keyPreviewEnabled)
        assertEquals(1.2f, p.keyHeightScale, 0.001f)
    }

    @Test fun lastModeIsSavedOnlyWhenItChanges() {
        val saved = ArrayList<Pair<String, String>>()
        val p = prefs(KeyboardSettings.DEFAULT.copy(lastModes = mapOf("am" to "FIDEL")), saved)
        p.setLastNative("am", LayoutKind.FIDEL)
        p.setLastNative("am", LayoutKind.PHONETIC)
        assertEquals(listOf("am" to "PHONETIC"), saved)
    }

    @Test fun writingModesMapAndSaveOnlyForAmAndTi() {
        val saved = ArrayList<Pair<String, String>>()
        val s = KeyboardSettings.DEFAULT.copy(writingModes = mapOf("am" to "ABC", "ti" to "FIDEL", "om" to "ABC", "en" to "FIDEL"))
        val p = SettingsPreferences({ s }, saveWritingMode = { l, m -> saved += l to m })
        assertEquals(LayoutKind.ABC, p.writingMode("am")) // English
        assertEquals(LayoutKind.FIDEL, p.writingMode("ti"))
        assertNull(p.writingMode("om")) // single-mode languages have no writing mode
        assertNull(p.writingMode("en"))
        p.setWritingMode("am", LayoutKind.ABC) // unchanged: not saved again
        p.setWritingMode("am", LayoutKind.PHONETIC)
        p.setWritingMode("om", LayoutKind.ABC) // ignored
        assertEquals(listOf("am" to "PHONETIC"), saved)
    }

    @Test fun firstRunStartsOnAmharicPhoneticQwertyAndFidelIsOneGlobeLongPressAway() {
        val s = CoreTestEnv.Session(languages = prefs(KeyboardSettings.DEFAULT), prefs = prefs(KeyboardSettings.DEFAULT))
        assertEquals(TypingMode("am", LayoutKind.PHONETIC), s.c.effectiveMode)
        s.c.onLongPress(s.key(KeyKind.GLOBE))
        s.ed.deliver(s.adapter)
        assertEquals(TypingMode("am", LayoutKind.FIDEL), s.c.effectiveMode)
    }

    @Test fun inMemoryDefaultsAlsoStartPhonetic() {
        assertEquals(TypingMode("am", LayoutKind.PHONETIC), CoreTestEnv.Session().c.effectiveMode)
    }

    @Test fun controllerFallsBackToAbcWhileTheCoreIsNotLoaded() {
        val c = KeyboardController(PassthroughSink()) {}
        assertTrue(c.mode.isAbc)
    }

    @Test fun startsOnTheFirstEnabledLanguageAndRemembersLayout() {
        var s = order("om", "am", "ti", "en")
        s = s.copy(lastModes = mapOf("am" to "FIDEL"))
        val sess = CoreTestEnv.Session(languages = prefs(s), prefs = prefs(s))
        assertEquals("om", sess.c.language)
        sess.pick(sess.key(KeyKind.SPACE).popup.first { it.language == "am" })
        assertEquals(TypingMode("am", LayoutKind.FIDEL), sess.c.effectiveMode) // remembered layout wins over the setting
    }

    @Test fun disablingTheCurrentLanguageMovesAway() {
        var s = KeyboardSettings.DEFAULT
        val p = SettingsPreferences({ s })
        val sess = CoreTestEnv.Session(languages = p, prefs = p)
        sess.c.onPreferencesChanged() // first load: already on am
        s = order("am", "ti", "om", "en", disabled = setOf("am"))
        sess.c.onPreferencesChanged()
        assertEquals("ti", sess.c.language)
    }

    @Test fun numeralStyleEthiopicPutsEthiopicDigitsOnTheKeysWesternIsTheDefault() {
        fun firstDigit(st: KeyboardSettings): Key {
            val sess = CoreTestEnv.Session(languages = prefs(st), prefs = prefs(st))
            return sess.c.layout.rows.first().first()
        }
        // ADR 0044: Western first by default (nothing chosen), when Western is chosen, and for an ETHIOPIC never chosen.
        for (st in listOf(
            KeyboardSettings.DEFAULT,
            KeyboardSettings.DEFAULT.copy(numeralStyle = NumeralStyle.WESTERN, numeralStyleChosen = true),
            KeyboardSettings.DEFAULT.copy(numeralStyle = NumeralStyle.ETHIOPIC, numeralStyleChosen = false),
        )) {
            val k = firstDigit(st)
            assertEquals("1", k.text)
            assertEquals("፩", k.popup.first().text)
            assertEquals("፩", k.hint)
        }
        // Ethiopic first only when the user chose it.
        val ethiopic = firstDigit(KeyboardSettings.DEFAULT.copy(numeralStyle = NumeralStyle.ETHIOPIC, numeralStyleChosen = true))
        assertEquals("፩", ethiopic.text)
        assertEquals("1", ethiopic.popup.first().text)
        assertFalse(prefs(KeyboardSettings.DEFAULT).ethiopicNumerals)
        assertFalse(prefs(KeyboardSettings.DEFAULT.copy(numeralStyleChosen = true)).ethiopicNumerals)
        assertTrue(prefs(KeyboardSettings.DEFAULT.copy(numeralStyle = NumeralStyle.ETHIOPIC, numeralStyleChosen = true)).ethiopicNumerals)
    }

    @Test fun keyHeightScalesRowsAndCandidatesCanBeHidden() {
        val st = KeyboardSettings.DEFAULT.copy(
            keyHeightPercent = 130,
            languages = KeyboardSettings.DEFAULT.languages.map { if (it.languageCode == "am") it.copy(showTransliterationCandidates = false) else it },
        )
        val normal = CoreTestEnv.Session().c
        val tall = CoreTestEnv.Session(languages = prefs(st), prefs = prefs(st)).c
        assertEquals(normal.layout.rowHeightDp * 1.3f, tall.layout.rowHeightDp, 0.01f)
        assertTrue(normal.showCandidateStrip)
        assertFalse(tall.showCandidateStrip)
    }

    @Test fun afaanOromooSuggestionsFollowTheirOwnSwitch() {
        val off = KeyboardSettings.DEFAULT.copy(
            languages = KeyboardSettings.DEFAULT.languages.map { if (it.languageCode == "om") it.copy(showTransliterationCandidates = false) else it },
        )
        val om = TypingMode("om", LayoutKind.QUBEE)
        val shown = CoreTestEnv.Session().also { it.mode(om) }.c
        val hidden = CoreTestEnv.Session(languages = prefs(off), prefs = prefs(off)).also { it.mode(om) }.c
        assertTrue(shown.showCandidateStrip)
        assertFalse(hidden.showCandidateStrip)
    }

    // ---------------------------------------------------------------- ADR 0007: Fidel punctuation

    @Test fun fidelGridTapsEthiopicPunctuationAndPutsAsciiFirstInThePopup() {
        val s = CoreTestEnv.Session()
        s.mode(TypingMode("am", LayoutKind.FIDEL))
        val bottom = s.c.layout.rows.last()
        val period = bottom.first { it.kind == KeyKind.CHAR && it.text == "።" }
        val comma = bottom.first { it.kind == KeyKind.CHAR && it.text == "፣" }
        assertEquals(".", period.popup.first().text)
        assertEquals("፣", period.popup[1].text)
        assertFalse(period.popup.drop(1).any { it.text == "." || it.text == "።" })
        assertEquals(listOf(",", "፤"), comma.popup.map { it.text })
        s.tap(period)
        s.tap(comma)
        assertEquals("።፣", s.ed.text.toString())
        s.pick(period.popup.first())
        assertEquals("።፣.", s.ed.text.toString())
    }

    @Test fun otherLayoutsKeepAsciiPunctuationOnTap() {
        val s = CoreTestEnv.Session()
        // am/ti Phonetic now tap ። / ፣ like the Fidel grid (owner 2026-11-19); Latin layouts keep ASCII.
        for (m in listOf(TypingMode("om", LayoutKind.QUBEE), TypingMode("en", LayoutKind.ENGLISH))) {
            s.mode(m)
            val bottom = s.c.layout.rows.last()
            assertTrue("$m", bottom.any { it.kind == KeyKind.CHAR && it.text == "." })
            assertTrue("$m", bottom.any { it.kind == KeyKind.CHAR && it.text == "," })
        }
        s.mode(TypingMode("am", LayoutKind.PHONETIC))
        assertTrue(s.c.layout.rows.last().any { it.kind == KeyKind.CHAR && it.text == "።" })
        s.c.selectWritingMode(LayoutKind.ABC) // the pill's English (ADR 0035: no ABC key)
        s.ed.deliver(s.adapter)
        assertTrue(s.c.layout.rows.last().any { it.kind == KeyKind.CHAR && it.text == "." })
    }

    private class PassthroughSink : TextSink {
        override fun apply(update: ComposerUpdate) {}
        override fun sendEnterKey() {}
        override fun performEditorAction(actionId: Int) {}
        override fun wantsAutoCapital(capsFlags: Int) = false
    }
}
