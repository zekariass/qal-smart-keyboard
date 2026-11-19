package com.qalsmartkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Repeated-key bug (owner report: typing ግግር needs ግ twice, but the second press "disappeared"). End to end over the
 * real core and the bundled packs, in every layout: the same key pressed several times in a row, fast, with the
 * editor's `onUpdateSelection` reports arriving late (lagging 0, 1 or 3 reports behind, or only at the end), as they do
 * on a busy phone. Every press must reach the text exactly once, and no late report may abandon the composition.
 */
class RepeatedKeysTest {
    private val all = EnabledLanguages { listOf("am", "ti", "om", "en", "ar") }
    private val lags = listOf(0, 1, 3, Int.MAX_VALUE)

    private fun char(text: String) = Key(KeyKind.CHAR, text, text)

    private fun CoreTestEnv.Session.keysFor(s: String): List<Key> =
        s.map { ch -> if (ch == ' ') key(KeyKind.SPACE) else char(ch.toString()) }

    /** Presses [keys] with the editor's reports delivered [lag] reports late, then delivers the rest. */
    private fun CoreTestEnv.Session.pressFast(keys: List<Key>, lag: Int) {
        for (k in keys) {
            c.onKey(k)
            while (ed.reports.size > lag) ed.deliverOne(adapter)
        }
        ed.deliver(adapter)
    }

    private fun check(mode: TypingMode, typed: String, want: String) {
        for (lag in lags) {
            val s = CoreTestEnv.Session(languages = all)
            s.mode(mode)
            s.pressFast(s.keysFor(typed), lag)
            assertEquals("$mode lag $lag", want, s.ed.text.toString())
            assertEquals("$mode lag $lag: nothing left composing", "", s.ed.composing)
        }
    }

    @Test fun amharicPhonetic() {
        val am = TypingMode("am", LayoutKind.PHONETIC)
        check(am, "ggr ", "ግግር ")
        check(am, "selam tegt'ami ", "ሰላም ተግጣሚ ")
        check(am, "lele llb gg ", "ለለ ልልብ ግግ ")
        check(am, "messeret ", "መሰረት ") // a doubled consonant before a vowel is still gemination
    }

    @Test fun tigrinyaPhonetic() {
        val ti = TypingMode("ti", LayoutKind.PHONETIC)
        check(ti, "ggr selam ", "ግግር ሰላም ")
        check(ti, "lele ", "ለለ ")
    }

    @Test fun afaanOromooQubeeKeepsDoubledLettersAndLongVowels() {
        check(TypingMode("om", LayoutKind.QUBEE), "dhaabbata gaarii ", "dhaabbata gaarii ")
    }

    @Test fun englishKeepsDoubledLetters() {
        check(TypingMode("en", LayoutKind.ENGLISH), "coffee bookkeeper ", "coffee bookkeeper ")
    }

    @Test fun arabicKeepsDoubledLetters() {
        check(TypingMode("ar", LayoutKind.ARABIC), "الله ممم ", "الله ممم ")
    }

    @Test fun fidelTwoKeyConsonantPressedTwice() {
        for (lang in listOf("am", "ti")) {
            for (lag in lags) {
                val s = CoreTestEnv.Session(languages = all)
                s.mode(TypingMode(lang, LayoutKind.FIDEL))
                fun consonant(series: String) = s.c.layout.rows.flatten().first { it.kind == KeyKind.FIDEL_CONSONANT && it.text == series }
                s.pressFast(listOf(consonant("G"), consonant("G"), consonant("R"), s.key(KeyKind.SPACE)), lag)
                assertEquals("$lang lag $lag", "ግግር ", s.ed.text.toString())
            }
        }
    }

    @Test fun fidelPopupFormsPickedTwice() {
        val s = CoreTestEnv.Session(languages = all)
        s.mode(TypingMode("am", LayoutKind.FIDEL))
        fun form(series: String, text: String) =
            s.c.layout.rows.flatten().first { it.kind == KeyKind.FIDEL_CONSONANT && it.text == series }.popup.first { it.text == text }
        for (item in listOf(form("L", "ለ"), form("L", "ለ"), form("M", "ም"), form("M", "ም"))) {
            s.c.onPopupSelected(item) // reports are left undelivered on purpose until the end
        }
        s.ed.deliver(s.adapter)
        assertEquals("ለለምም", s.ed.text.toString())
    }
}
