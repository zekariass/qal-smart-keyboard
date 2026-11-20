package com.qalsmartkeyboard.ime

import android.text.InputType
import android.text.TextUtils
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Product owner 2026-11-19 (am 0.3.0, ti 0.4.0): in Phonetic mode capitals are their own letters (N ኝ, T ጥ, K ኽ, P ጵ;
 * Q ቐ in Tigrinya and, from am 0.4.0 / ADR 0044, in Amharic too), so the keyboard must never capitalise on its own there (a sentence-initial `n` stays ን), while a
 * manual Shift or caps lock gives the capital's letter and the shifted keys show it. Real core and bundled packs.
 */
class PhoneticCapitalsTest {
    private val all = EnabledLanguages { listOf("am", "ti", "om", "en") }
    private val sentences = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

    private fun session(mode: TypingMode) = CoreTestEnv.Session(sentences, languages = all).also {
        it.ed.capsMode = TextUtils.CAP_MODE_SENTENCES // the field asks for a capital at the cursor
        it.mode(mode)
        it.c.refreshAutoCapital()
    }

    private fun CoreTestEnv.Session.letter(ch: Char): Key = c.layout.rows.flatten().first { it.kind == KeyKind.CHAR && it.text == ch.toString() }

    @Test fun englishAutoCapitalisesButPhoneticNever() {
        val en = session(TypingMode("en", LayoutKind.ENGLISH))
        assertEquals(ShiftState.ONCE, en.c.shift)
        for (lang in listOf("am", "ti")) {
            val s = session(TypingMode(lang, LayoutKind.PHONETIC))
            assertEquals(lang, ShiftState.OFF, s.c.shift)
            s.type("na tena ")
            assertEquals(lang, "ና ተና ", s.ed.text.toString())
            assertEquals(lang, ShiftState.OFF, s.c.shift) // still none after a sentence start
        }
    }

    @Test fun manualShiftTypesTheCapitalsLetterAndTheKeysShowIt() {
        val s = session(TypingMode("am", LayoutKind.PHONETIC))
        val shift = s.key(KeyKind.SHIFT)
        val expected = mapOf('n' to "ኝ", 't' to "ጥ", 'k' to "ኽ", 'p' to "ጵ")
        // The keys' shifted hints are what the shifted key types (hints are derived from the engine).
        val hints = CoreComposer.deriveHints(CoreTestEnv.freshEngine())
        for ((c, out) in expected) assertEquals("$c", out, hints.getValue("am").upper(c))
        s.tap(shift)
        s.tap(s.letter('n'))
        s.type("a ")
        assertEquals("ኛ ", s.ed.text.toString())
        assertEquals(ShiftState.OFF, s.c.shift) // one-shot
        // Caps lock: every letter is the capital's.
        s.tap(shift); s.tap(shift)
        assertEquals(ShiftState.LOCKED, s.c.shift)
        s.tap(s.letter('t'))
        s.tap(s.letter('k'))
        s.tap(s.letter('p'))
        s.tap(shift)
        s.type(" ")
        assertEquals("ኛ ጥኽጵ ", s.ed.text.toString())
        // The capital's letter is the strip's first candidate (the composition), the plain letter an alternative.
        s.tap(shift)
        s.tap(s.letter('t'))
        s.type("ena")
        assertEquals("ጠና", s.c.candidates.first().text)
        s.type(" ")
    }

    @Test fun tigrinyaCapitalQIsQhInEveryOrder() {
        val s = shiftQInEveryOrder("ti")
        s.type("qhe ")
        assertEquals("ቐ ቑ ቒ ቓ ቔ ቕ ቖ ቐ ", s.ed.text.toString())
    }

    /** ADR 0044 (am 0.4.0): Amharic Phonetic gets the same capital Q as Tigrinya; lowercase q stays ቀ (k' too: core golden). */
    @Test fun amharicCapitalQIsQhInEveryOrderAndLowercaseQIsUnchanged() {
        val s = shiftQInEveryOrder("am")
        s.type("qe qa q ")
        assertEquals("ቐ ቑ ቒ ቓ ቔ ቕ ቖ ቀ ቃ ቅ ", s.ed.text.toString())
        // Labialised by the W convention.
        s.tap(s.key(KeyKind.SHIFT)); s.tap(s.letter('q'))
        s.tap(s.key(KeyKind.SHIFT)); s.tap(s.letter('w'))
        s.type("a ")
        assertEquals("ቐ ቑ ቒ ቓ ቔ ቕ ቖ ቀ ቃ ቅ ቛ ", s.ed.text.toString())
    }

    /** Shift + q then each vowel: ቐ…ቖ, and the shifted q key's hint is ቕ. */
    private fun shiftQInEveryOrder(lang: String): CoreTestEnv.Session {
        val s = session(TypingMode(lang, LayoutKind.PHONETIC))
        val shift = s.key(KeyKind.SHIFT)
        assertEquals(lang, "ቕ", CoreComposer.deriveHints(CoreTestEnv.freshEngine()).getValue(lang).upper('q'))
        for (v in listOf("e", "u", "i", "a", "ee", "", "o")) {
            s.tap(shift)
            s.tap(s.letter('q'))
            s.type("$v ")
        }
        assertEquals(lang, "ቐ ቑ ቒ ቓ ቔ ቕ ቖ ", s.ed.text.toString())
        return s
    }
}
