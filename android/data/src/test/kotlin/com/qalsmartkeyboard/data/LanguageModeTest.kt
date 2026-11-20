package com.qalsmartkeyboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** ADR 0044: the mode a language opens in, and the app's pick replacing what the keyboard remembered. */
class LanguageModeTest {
    private val phonetic = InputMode.PHONETIC_LATIN_TO_NATIVE
    private val fidel = InputMode.DIRECT_SCRIPT_LAYOUT
    private val latin = InputMode.LATIN_PASSTHROUGH

    @Test fun freshInstallOpensAmharicAndTigrinyaInPhonetic() {
        val s = KeyboardSettings.DEFAULT
        assertEquals(phonetic, s.modeInForce("am"))
        assertEquals(phonetic, s.modeInForce("ti"))
        assertEquals(latin, s.modeInForce("om"))
        assertEquals(latin, s.modeInForce("en"))
        assertEquals(phonetic, LanguageRules.defaultMode("am"))
        assertNull(s.modeInForce("xx"))
    }

    @Test fun theKeyboardsMemoryIsTheModeInForceInTheIMEsOrder() {
        val s = KeyboardSettings.DEFAULT
        // The keyboard resolves writing mode, then last native layout, then the setting.
        assertEquals(fidel, s.copy(lastModes = mapOf("am" to "FIDEL")).modeInForce("am"))
        assertEquals(latin, s.copy(writingModes = mapOf("am" to "ABC"), lastModes = mapOf("am" to "FIDEL")).modeInForce("am"))
        assertEquals(phonetic, s.copy(writingModes = mapOf("am" to "PHONETIC"), lastModes = mapOf("am" to "FIDEL")).modeInForce("am"))
        // Junk or a layout the language does not have is ignored; ABC is never a remembered native layout.
        assertEquals(phonetic, s.copy(writingModes = mapOf("am" to "QUBEE"), lastModes = mapOf("am" to "ABC")).modeInForce("am"))
        // Single-layout languages always show their setting.
        assertEquals(latin, s.copy(writingModes = mapOf("om" to "FIDEL")).modeInForce("om"))
    }

    @Test fun aPickInTheAppReplacesTheKeyboardsMemory() {
        val used = KeyboardSettings.DEFAULT.copy(
            lastModes = mapOf("am" to "FIDEL", "ti" to "FIDEL"),
            writingModes = mapOf("am" to "FIDEL", "ti" to "ABC"),
        )
        assertEquals(fidel, used.modeInForce("am"))
        val back = used.withLanguageMode("am", phonetic)
        assertEquals(phonetic, back.modeInForce("am"))
        assertEquals(phonetic, back.language("am")!!.inputMode)
        assertNull(back.lastModes["am"])
        assertNull(back.writingModes["am"])
        // Other languages keep theirs.
        assertEquals("FIDEL", back.lastModes["ti"])
        assertEquals("ABC", back.writingModes["ti"])
        // English (Latin) in am/ti is the keyboard's ABC writing mode.
        val english = used.withLanguageMode("am", latin)
        assertEquals(latin, english.modeInForce("am"))
        assertEquals("ABC", english.writingModes["am"])
        assertEquals(fidel, used.withLanguageMode("am", fidel).modeInForce("am"))
        // A mode the language does not have changes nothing.
        assertSame(used, used.withLanguageMode("om", fidel))
        assertSame(used, used.withLanguageMode("xx", phonetic))
    }
}
