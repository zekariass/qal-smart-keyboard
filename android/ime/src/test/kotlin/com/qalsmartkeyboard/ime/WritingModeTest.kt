package com.qalsmartkeyboard.ime

import android.text.InputType as T
import com.qalsmartkeyboard.data.KeyboardSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * am/ti writing modes (Fidel, Phonetic, English): the selector's options, selection, persistence per
 * language across sessions, the ABC key as the English switch, and the phonetic key hints in the
 * layout. Uses a fake composer (no core) and the real settings adapter where persistence matters.
 */
class WritingModeTest {
    private class Sink : TextSink {
        val out = StringBuilder()
        var composing = ""
        override fun apply(update: ComposerUpdate) {
            repeat(update.deleteBefore) { if (out.isNotEmpty()) out.setLength(out.length - 1) }
            out.append(update.commit)
            composing = update.composing
        }
        override fun sendEnterKey() {}
        override fun performEditorAction(actionId: Int) {}
        override fun wantsAutoCapital(capsFlags: Int) = false
    }

    /** Supports every layout of the four languages; phonetic letters compose upper-cased; hints for am and ti. */
    private class Fake(private val hints: Map<String, PhoneticHints> = emptyMap(), private val fidel: Boolean = true) : Composer {
        var mode = TypingMode.abc("en")
        private var buf = ""
        override fun onKey(key: ComposerKey): ComposerUpdate {
            if (!mode.phonetic) return PassthroughComposer().onKey(key)
            return when (key) {
                is ComposerKey.Text -> { buf += key.text; ComposerUpdate(composing = buf.uppercase()) }
                ComposerKey.Commit, ComposerKey.Space -> ComposerUpdate(commit = buf.uppercase() + if (key == ComposerKey.Space) " " else "").also { buf = "" }
                else -> ComposerUpdate.NONE
            }
        }
        override fun selectCandidate(index: Int) = ComposerUpdate.NONE
        override fun reset() { buf = "" }
        override fun setMode(mode: TypingMode): Boolean { buf = ""; this.mode = mode; return supports(mode) }
        override fun supports(mode: TypingMode) = when (mode.kind) {
            LayoutKind.ABC -> true
            LayoutKind.FIDEL -> fidel && LanguageCatalog.isEthiopic(mode.language)
            LayoutKind.PHONETIC -> LanguageCatalog.isEthiopic(mode.language)
            LayoutKind.QUBEE -> mode.language == "om"
            LayoutKind.ENGLISH -> mode.language == "en"
        }
        override fun isDraft(mode: TypingMode) = false
        override fun fidelGrid(language: String): FidelGrid? = null
        override fun phoneticHints(language: String) = hints[language]
    }

    private fun controller(prefs: ModePreferences = InMemoryModePreferences(), sink: Sink = Sink(), composer: Composer = Fake(), type: Int = T.TYPE_CLASS_TEXT): KeyboardController {
        val languages = prefs as? EnabledLanguages ?: DefaultEnabledLanguages
        return KeyboardController(sink, languages, prefs) {}.also {
            it.startField(InputProfile.from(type, 0, false, 0))
            it.composer = composer
        }
    }

    private fun KeyboardController.key(kind: KeyKind) = layout.rows.flatten().first { it.kind == kind }

    // ---------------------------------------------------------------- selector

    @Test fun amAndTiOfferFidelPhoneticAndEnglishInThatOrder() {
        val c = controller()
        assertEquals("am", c.language)
        assertEquals(listOf(LayoutKind.FIDEL, LayoutKind.PHONETIC, LayoutKind.ABC), c.writingModeOptions)
        assertEquals(LayoutKind.PHONETIC, c.writingMode) // ADR 0007 U3 default
        c.switchLanguage("ti")
        assertEquals(listOf(LayoutKind.FIDEL, LayoutKind.PHONETIC, LayoutKind.ABC), c.writingModeOptions)
        assertEquals(listOf("ፊደል", "Phonetic", "English"), c.writingModeOptions.map { LanguageCatalog.writingModeLabel(it) })
    }

    @Test fun omAndEnHaveNoSelector() {
        val c = controller()
        c.switchLanguage("om")
        assertTrue(c.writingModeOptions.isEmpty())
        c.switchLanguage("en")
        assertTrue(c.writingModeOptions.isEmpty())
    }

    @Test fun fieldsThatStayLatinAndAnUnloadedCoreHaveNoSelector() {
        assertTrue(controller(type = T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD).writingModeOptions.isEmpty())
        assertTrue(controller(type = T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).writingModeOptions.isEmpty())
        assertTrue(controller(composer = PassthroughComposer()).writingModeOptions.isEmpty())
        // Fidel data not loaded: Phonetic and English only.
        assertEquals(listOf(LayoutKind.PHONETIC, LayoutKind.ABC), controller(composer = Fake(fidel = false)).writingModeOptions)
    }

    @Test fun selectingAModeCommitsTheCompositionAndSwitchesTheLayout() {
        val sink = Sink()
        val c = controller(sink = sink)
        c.onKey(c.layout.rows.flatten().first { it.text == "s" })
        assertEquals("S", sink.composing)
        c.selectWritingMode(LayoutKind.ABC)
        assertEquals("S", sink.out.toString()) // committed, not lost
        assertEquals("", sink.composing)
        assertEquals(TypingMode.abc("am"), c.effectiveMode)
        assertEquals("አማ · English", c.indicator)
        c.selectWritingMode(LayoutKind.FIDEL)
        assertEquals(LayoutKind.FIDEL, c.writingMode)
        c.selectWritingMode(LayoutKind.QUBEE) // not offered: ignored
        assertEquals(LayoutKind.FIDEL, c.writingMode)
    }

    // ---------------------------------------------------------------- persistence

    @Test fun eachLanguageRemembersItsOwnModeAcrossLanguageSwitches() {
        val c = controller()
        c.selectWritingMode(LayoutKind.ABC) // am: English
        c.switchLanguage("ti")
        assertEquals(LayoutKind.PHONETIC, c.writingMode)
        c.selectWritingMode(LayoutKind.FIDEL) // ti: Fidel
        c.switchLanguage("en")
        c.switchLanguage("am")
        assertEquals(LayoutKind.ABC, c.writingMode)
        c.switchLanguage("ti")
        assertEquals(LayoutKind.FIDEL, c.writingMode)
    }

    @Test fun theModeSurvivesARestartThroughTheSettingsStore() {
        var stored = KeyboardSettings.DEFAULT
        val saved = ArrayList<Pair<String, String>>()
        fun prefs() = SettingsPreferences(
            { stored },
            saveLastMode = { l, m -> stored = stored.copy(lastModes = stored.lastModes + (l to m)) },
            saveWritingMode = { l, m -> saved += l to m; stored = stored.copy(writingModes = stored.writingModes + (l to m)) },
        )
        val first = controller(prefs())
        first.selectWritingMode(LayoutKind.ABC) // am: English
        first.switchLanguage("ti")
        first.selectWritingMode(LayoutKind.FIDEL)
        assertEquals(listOf("am" to "ABC", "ti" to "FIDEL"), saved)
        assertEquals(mapOf("ti" to "FIDEL"), stored.lastModes) // English is never a "last native" layout

        // "Restart": a new controller over the same stored settings.
        val second = controller(prefs())
        assertEquals("am", second.language)
        assertEquals(TypingMode.abc("am"), second.effectiveMode)
        second.switchLanguage("ti")
        assertEquals(TypingMode("ti", LayoutKind.FIDEL), second.effectiveMode)
    }

    @Test fun settingsSavedBeforeWritingModesExistedStillOpenTheLastNativeLayout() {
        val old = KeyboardSettings.DEFAULT.copy(lastModes = mapOf("am" to "FIDEL"))
        val p = SettingsPreferences({ old })
        assertNull(p.writingMode("am"))
        assertEquals(TypingMode("am", LayoutKind.FIDEL), controller(p).effectiveMode)
    }

    @Test fun theFirstSettingsLoadAppliesTheSavedModeToTheStartingLanguage() {
        // The controller was built from the defaults before the store was read (cold IME start).
        var stored = KeyboardSettings.DEFAULT
        val p = SettingsPreferences({ stored })
        val c = controller(p)
        assertEquals(LayoutKind.PHONETIC, c.writingMode)
        stored = stored.copy(writingModes = mapOf("am" to "ABC"))
        c.onPreferencesChanged()
        assertEquals(LayoutKind.ABC, c.writingMode)
        // Later loads do not override what the user picks in this session.
        c.selectWritingMode(LayoutKind.FIDEL)
        stored = stored.copy(writingModes = mapOf("am" to "PHONETIC"))
        c.onPreferencesChanged()
        assertEquals(LayoutKind.FIDEL, c.writingMode)
    }

    @Test fun omKeepsAbcTemporary() {
        val p = InMemoryModePreferences()
        val c = controller(p)
        c.switchLanguage("om")
        c.toggleAbc()
        assertTrue(c.effectiveMode.isAbc)
        assertNull(p.writingMode("om"))
        c.switchLanguage("en")
        c.switchLanguage("om")
        assertEquals(TypingMode("om", LayoutKind.QUBEE), c.effectiveMode)
    }

    // ---------------------------------------------------------------- ABC key = English mode

    @Test fun abcKeyGoesToEnglishAndBackToTheLastNativeMode() {
        val c = controller()
        c.selectWritingMode(LayoutKind.FIDEL)
        assertEquals("ABC", c.key(KeyKind.ABC_TOGGLE).label)
        assertEquals("Switch to English writing mode", c.key(KeyKind.ABC_TOGGLE).description)
        c.onKey(c.key(KeyKind.ABC_TOGGLE))
        assertEquals(LayoutKind.ABC, c.writingMode)
        assertEquals("አ", c.key(KeyKind.ABC_TOGGLE).label)
        c.onKey(c.key(KeyKind.ABC_TOGGLE))
        assertEquals(LayoutKind.FIDEL, c.writingMode) // one tap back to the last native mode
        c.selectWritingMode(LayoutKind.PHONETIC)
        c.onKey(c.key(KeyKind.ABC_TOGGLE))
        c.onKey(c.key(KeyKind.ABC_TOGGLE))
        assertEquals(LayoutKind.PHONETIC, c.writingMode)
    }

    @Test fun globeLongPressStillCyclesAllThreeModes() {
        val c = controller()
        val seen = ArrayList<LayoutKind>()
        repeat(3) { c.onLongPress(c.key(KeyKind.GLOBE)); seen += c.writingMode }
        assertEquals(listOf(LayoutKind.FIDEL, LayoutKind.ABC, LayoutKind.PHONETIC), seen)
    }

    // ---------------------------------------------------------------- phonetic key hints in the layout

    private val hints = mapOf(
        "am" to PhoneticHints(mapOf('s' to "ስ", 'a' to "አ"), mapOf('s' to "ሽ")),
        "ti" to PhoneticHints(mapOf('s' to "ስ", 'q' to "ቕ"), emptyMap()),
    )

    @Test fun phoneticLettersCarryTheEnginesHintsPerLanguageAndShiftState() {
        val c = controller(composer = Fake(hints))
        val keys = c.layout.rows.flatten()
        val s = keys.first { it.text == "s" }
        assertEquals("ስ", s.hint)
        assertEquals("ሽ", s.shiftHint)
        assertEquals("አ", keys.first { it.text == "a" }.hint)
        val x = keys.first { it.text == "x" }
        assertEquals("", x.hint) // no single-key output: no hint
        assertEquals("", x.shiftHint)
        assertEquals("s", s.label) // the Latin letter stays the primary label
        c.switchLanguage("ti")
        val ti = c.layout.rows.flatten()
        assertEquals("ቕ", ti.first { it.text == "q" }.hint)
        assertEquals("", ti.first { it.text == "q" }.shiftHint)
    }

    @Test fun otherModesShowNoLetterHints() {
        val c = controller(composer = Fake(hints))
        c.selectWritingMode(LayoutKind.ABC)
        assertTrue(c.layout.rows.flatten().filter { it.isLetter }.all { it.hint.isEmpty() && it.shiftHint == null })
        c.switchLanguage("en")
        assertTrue(c.layout.rows.flatten().filter { it.isLetter }.all { it.hint.isEmpty() && it.shiftHint == null })
    }

    @Test fun hintDerivationKeepsOnlyEthiopicSingleKeyOutput() {
        val h = PhoneticHints.derive { key ->
            when (key) {
                "s" -> "ስ"
                "S" -> "ሽ"
                "a" -> "አ"
                "x" -> "x" // unmapped: echoes the letter
                "q" -> null
                "c" -> " "
                else -> ""
            }
        }
        assertEquals("ስ", h.lower('s'))
        assertEquals("ሽ", h.upper('s'))
        assertEquals("አ", h.lower('a'))
        assertEquals("", h.upper('a'))
        assertEquals("", h.lower('x'))
        assertEquals("", h.lower('q'))
        assertEquals("", h.lower('c'))
        assertFalse(h.isEmpty)
    }
}
