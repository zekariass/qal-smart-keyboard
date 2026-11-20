package com.qalsmartkeyboard.ime

import com.qalsmartkeyboard.data.InputMode
import com.qalsmartkeyboard.data.KeyboardSettings
import com.qalsmartkeyboard.data.TypingLanguageSetting
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ADR 0044: Amharic opens in Phonetic the first time it is typed (fresh install, before and after the core loads, enabled
 * later, first switch to it) and a layout picked deliberately afterwards (on the keyboard or in the app) persists. The
 * other languages keep their defaults. Real core and bundled packs; the settings store is simulated with its echo.
 */
class AmharicDefaultLayoutTest {
    private val am = TypingMode("am", LayoutKind.PHONETIC)

    /** A settings store whose writes land when [echo] is called (as the IME's snapshot does a moment later). */
    private class Store(var s: KeyboardSettings) {
        private val pending = ArrayList<(KeyboardSettings) -> KeyboardSettings>()
        val prefs = SettingsPreferences(
            { s },
            saveWritingMode = { l, m -> pending += { it.copy(writingModes = it.writingModes + (l to m)) } },
            saveSettings = { t -> pending += t },
        ) { l, m -> pending += { it.copy(lastModes = it.lastModes + (l to m)) } }

        /** Lands the next pending write (or all of them) and tells the controller, like the snapshot's onChange. */
        fun echo(c: KeyboardController, all: Boolean = true) {
            do {
                if (pending.isEmpty()) break
                s = pending.removeAt(0)(s)
                c.onPreferencesChanged()
            } while (all)
        }

        /** The app changed the settings (another process): the snapshot reloads and tells the controller. */
        fun app(c: KeyboardController, change: (KeyboardSettings) -> KeyboardSettings) {
            s = change(s)
            c.onPreferencesChanged()
        }
    }

    private fun session(store: Store) = CoreTestEnv.Session(languages = store.prefs, prefs = store.prefs)

    private fun order(vararg codes: String, disabled: Set<String> = emptySet()) =
        KeyboardSettings(languages = codes.map { TypingLanguageSetting(it, enabled = it !in disabled) }).sanitized()

    @Test fun freshInstallOpensAmharicPhoneticAndTransliterates() {
        val store = Store(KeyboardSettings.DEFAULT)
        val s = session(store)
        s.c.onPreferencesChanged() // the first settings load
        assertEquals(am, s.c.effectiveMode)
        s.type("selam ")
        assertEquals("ሰላም ", s.ed.text.toString())
    }

    @Test fun freshInstallWhileTheCoreLoadsStillOpensPhonetic() {
        // The service builds the controller before the core and the settings are loaded (ABC passthrough meanwhile).
        val store = Store(KeyboardSettings.DEFAULT)
        val ed = FakeEditor()
        val adapter = InputConnectionAdapter { ed }
        val c = KeyboardController(adapter, store.prefs, store.prefs) {}
        adapter.startSession(false, 0, 0)
        c.startField(InputProfile.DEFAULT)
        assertEquals(TypingMode.abc("am"), c.effectiveMode) // typing works, as ABC, until the core is there
        c.onPreferencesChanged()
        c.composer = CoreComposer(CoreTestEnv.engine)
        assertEquals(am, c.effectiveMode)
        assertEquals(emptyMap<String, String>(), store.s.writingModes) // nothing was remembered on the user's behalf
    }

    @Test fun enablingAmharicLaterOpensPhoneticOnTheFirstSwitch() {
        val store = Store(order("en", "om", "ti", "am", disabled = setOf("am")))
        val s = session(store)
        s.c.onPreferencesChanged()
        assertEquals(TypingMode("en", LayoutKind.ENGLISH), s.c.effectiveMode)
        store.app(s.c) { st -> st.copy(languages = st.languages.map { if (it.languageCode == "am") it.copy(enabled = true) else it }) }
        assertEquals("en", s.c.language) // enabling does not move the keyboard
        s.pick(s.key(KeyKind.SPACE).popup.first { it.language == "am" })
        assertEquals(am, s.c.effectiveMode)
        s.type("selam ")
        assertEquals("ሰላም ", s.ed.text.toString())
        // By the globe too: om -> ti -> am from a keyboard that never typed Amharic.
        val g = session(Store(order("en", "am", disabled = emptySet())))
        g.c.onPreferencesChanged()
        g.tap(g.key(KeyKind.GLOBE))
        assertEquals(am, g.c.effectiveMode)
    }

    @Test fun amharicMadePrimaryLaterOpensPhonetic() {
        val store = Store(order("am", "en", "om", "ti"))
        val s = session(store)
        s.c.onPreferencesChanged()
        assertEquals(am, s.c.effectiveMode)
    }

    @Test fun aLayoutChosenOnTheKeyboardPersistsAndIsNotOverridden() {
        val store = Store(KeyboardSettings.DEFAULT)
        val s = session(store)
        s.c.onPreferencesChanged()
        s.c.selectWritingMode(LayoutKind.FIDEL)
        store.echo(s.c)
        assertEquals(TypingMode("am", LayoutKind.FIDEL), s.c.effectiveMode)
        store.app(s.c) { it.copy(hapticsEnabled = false) } // an unrelated settings change
        assertEquals(TypingMode("am", LayoutKind.FIDEL), s.c.effectiveMode)
        // A restart (new keyboard, same store) reopens Fidel.
        val again = session(store)
        again.c.onPreferencesChanged()
        assertEquals(TypingMode("am", LayoutKind.FIDEL), again.c.effectiveMode)
        // The English writing mode (the ABC toggle) is remembered the same way.
        again.c.toggleAbc()
        store.echo(again.c)
        assertEquals(TypingMode.abc("am"), again.c.effectiveMode)
        assertEquals(TypingMode.abc("am"), session(store).also { it.c.onPreferencesChanged() }.c.effectiveMode)
    }

    @Test fun theKeyboardsOwnSwitchesAreNotUndoneByLateEchoes() {
        val store = Store(KeyboardSettings.DEFAULT)
        val s = session(store)
        s.c.onPreferencesChanged()
        // ABC and straight back before the store answers: the two echoes arrive afterwards, one by one.
        s.c.toggleAbc()
        s.c.toggleAbc()
        assertEquals(am, s.c.effectiveMode)
        store.echo(s.c, all = false)
        assertEquals(am, s.c.effectiveMode)
        store.echo(s.c)
        assertEquals(am, s.c.effectiveMode)
    }

    /**
     * The path that kept Amharic on Fidel (or English): a layout picked once on the keyboard is remembered and wins over the
     * language's setting, and the app's language page only changed that setting, so picking Phonetic there changed nothing
     * (the page even showed Phonetic while the keyboard opened Fidel), and a running keyboard ignored it until a restart.
     */
    @Test fun phoneticPickedInTheAppWinsOverTheKeyboardsMemoryAtOnce() {
        for (remembered in listOf("FIDEL", "ABC")) {
            val store = Store(KeyboardSettings.DEFAULT.copy(writingModes = mapOf("am" to remembered), lastModes = mapOf("am" to "FIDEL")))
            val s = session(store)
            s.c.onPreferencesChanged()
            assertEquals(remembered, if (s.c.effectiveMode.isAbc) "ABC" else s.c.effectiveMode.kind.name)
            // What the app shows is what the keyboard does.
            assertEquals(remembered, store.s.modeInForce("am")!!.let { if (it == InputMode.LATIN_PASSTHROUGH) "ABC" else "FIDEL" })
            store.app(s.c) { it.withLanguageMode("am", InputMode.PHONETIC_LATIN_TO_NATIVE) }
            assertEquals(remembered, am, s.c.effectiveMode) // the running keyboard follows the app at once
            s.type("selam ")
            assertEquals(remembered, true, s.ed.text.toString().endsWith("ሰላም "))
            // And a restart opens Phonetic too.
            assertEquals(am, session(store).also { it.c.onPreferencesChanged() }.c.effectiveMode)
        }
    }

    @Test fun otherLanguagesKeepTheirDefaults() {
        val store = Store(order("ti", "om", "en", "ar", "am"))
        val s = session(store)
        s.c.onPreferencesChanged()
        assertEquals(TypingMode("ti", LayoutKind.PHONETIC), s.c.effectiveMode)
        s.mode(TypingMode("om", LayoutKind.QUBEE))
        s.mode(TypingMode("en", LayoutKind.ENGLISH))
        s.mode(TypingMode("ar", LayoutKind.ARABIC))
        s.mode(am)
    }
}
