package com.qalsmartkeyboard.ime

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR 0039 end to end on the JVM: the real core with the bundled seed packs, the real controller and adapter, and a
 * fake editor (InputConnection). Replacing the current word, autocorrection and its one-tap undo, next-word predictions
 * after a space, spacing, the cursor context, the RTL strip, settings and sensitive fields. DRAFT data, synthetic text.
 */
class PredictionTypingTest {
    private val all = EnabledLanguages { listOf("am", "ti", "om", "en", "ar") }

    private class Prefs(
        val next: Boolean = true,
        val correct: Boolean = true,
        val learn: Boolean = true,
    ) : InMemoryModePreferences() {
        override val nextWordPredictions get() = next
        override fun autocorrect(language: String) = correct && language == "en"
        override val learnWords get() = learn
    }

    private fun session(
        prefs: ModePreferences = Prefs(),
        type: Int = InputType.TYPE_CLASS_TEXT,
        mode: TypingMode = TypingMode("en", LayoutKind.ENGLISH),
        dictionary: com.qalsmartkeyboard.ime.userdict.WordLearningSink = com.qalsmartkeyboard.ime.userdict.WordLearningSink.None,
        engine: uniffi.qal_keyboard_core.KeyboardEngine = CoreTestEnv.engine,
    ) = CoreTestEnv.Session(type, languages = all, prefs = prefs, engine = engine, dictionary = dictionary).also { it.mode(mode) }

    private fun CoreTestEnv.Session.typeRaw(s: String) {
        for (ch in s) {
            tap(if (ch == ' ') key(KeyKind.SPACE) else Key(KeyKind.CHAR, ch.toString(), ch.toString()))
        }
    }

    private fun CoreTestEnv.Session.pick(text: String) {
        val i = c.candidates.indexOfFirst { it.text == text }
        assertTrue("$text not offered: ${c.candidates}", i >= 0)
        c.selectCandidate(i)
        ed.deliver(adapter)
    }

    private fun CoreTestEnv.Session.backspace() = tap(key(KeyKind.BACKSPACE))

    /** An engine where the user has used "keyboard" often: a confident autocorrection target with seed data. */
    private fun engineKnowingKeyboard() = CoreTestEnv.freshEngine().also { e ->
        repeat(12) { assertTrue(e.learnUserWord("en", "keyboard", null)) }
    }

    @Test fun englishSuggestsAndAPickReplacesTheCurrentWordWithOneSpace() {
        val s = session()
        assertTrue(s.c.showCandidateStrip)
        s.typeRaw("hel")
        assertEquals("hel", s.ed.composing)
        assertEquals("hel", s.c.candidates.first().text)
        s.pick("hello")
        assertEquals("hello ", s.ed.text.toString())
        assertEquals("", s.ed.composing)
        // No double space, and punctuation attaches to the picked word.
        s.typeRaw(" ")
        assertEquals("hello ", s.ed.text.toString())
        s.typeRaw("how")
        s.pick("how")
        s.typeRaw("?")
        assertEquals("hello how?", s.ed.text.toString())
    }

    @Test fun nextWordAfterASpaceFromTheBigramPack() {
        val s = session()
        s.typeRaw("good ")
        assertEquals("morning", s.c.candidates.first().text)
        assertTrue(s.c.candidates.all { it.kind == SuggestionKind.PREDICTION })
        s.pick("morning")
        assertEquals("good morning ", s.ed.text.toString())
        // Off in the settings: no predictions.
        val off = session(prefs = Prefs(next = false))
        off.typeRaw("good ")
        assertTrue(off.c.candidates.isEmpty())
    }

    @Test fun autocorrectionOnSpaceIsMarkedAndBackspaceUndoesIt() {
        val s = session(engine = engineKnowingKeyboard())
        s.typeRaw("my keybaord")
        assertTrue(s.c.autocorrectIndex >= 0)
        assertEquals("keyboard", s.c.candidates[s.c.autocorrectIndex].text)
        s.typeRaw(" ")
        assertEquals("my keyboard ", s.ed.text.toString())
        s.backspace()
        assertEquals("my keybaord", s.ed.text.toString())
        assertEquals("keybaord", s.ed.composing)
        assertEquals(-1, s.c.autocorrectIndex)
        s.typeRaw(" ")
        assertEquals("my keybaord ", s.ed.text.toString())
        // Turned off in the settings: never corrected, still suggested.
        val off = session(prefs = Prefs(correct = false), engine = engineKnowingKeyboard())
        off.typeRaw("my keybaord")
        assertEquals(-1, off.c.autocorrectIndex)
        assertTrue(off.c.candidates.any { it.text == "keyboard" })
        off.typeRaw(" ")
        assertEquals("my keybaord ", off.ed.text.toString())
    }

    @Test fun noAutocorrectionInAddressNameAndNoSuggestionFields() {
        for (type in listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
        )) {
            val s = CoreTestEnv.Session(type, languages = all, prefs = Prefs(), engine = engineKnowingKeyboard())
            s.c.switchLanguage("en")
            s.typeRaw("my keybaord ")
            assertEquals("type $type", "my keybaord ", s.ed.text.toString())
        }
    }

    @Test fun passwordFieldsGetNoStripAndNoContextIsRead() {
        val s = CoreTestEnv.Session(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, languages = all, prefs = Prefs())
        s.ed.calls.clear()
        s.typeRaw("good ")
        assertFalse(s.c.showCandidateStrip)
        assertTrue(s.c.candidates.isEmpty())
        assertFalse(s.ed.calls.contains("getTextAfterCursor"))
    }

    @Test fun afterACursorMoveTheStripPredictsAndAPickRespectsTheSpaceThere() {
        val s = session()
        s.typeRaw("good  day")
        s.ed.userMovesCursor(5) // right after "good "
        s.ed.deliver(s.adapter)
        assertEquals("morning", s.c.candidates.first().text)
        s.pick("morning")
        // A space already follows: none is added.
        assertEquals("good morning day", s.ed.text.toString())
    }

    @Test fun phoneticPredictsTheNextWordAfterASpace() {
        val s = session(mode = TypingMode("am", LayoutKind.PHONETIC))
        s.type("selam ")
        assertEquals("ነው", s.c.candidates.first().text)
        assertEquals(SuggestionKind.PREDICTION, s.c.candidates.first().kind)
        s.pick("ነው")
        assertEquals("ሰላም ነው ", s.ed.text.toString())
    }

    @Test fun fidelGridShowsCompletionsAndAPickReplacesTheTypedLetters() {
        val s = session(mode = TypingMode("am", LayoutKind.FIDEL))
        assertTrue(s.c.showCandidateStrip)
        fun form(series: String, text: String) =
            s.c.layout.rows.flatten().first { it.kind == KeyKind.FIDEL_CONSONANT && it.text == series }.popup.first { it.text == text }
        s.pick(form("S", "ሰ"))
        s.pick(form("L", "ላ"))
        assertEquals("ሰላ", s.ed.text.toString())
        s.pick("ሰላም")
        assertEquals("ሰላም ", s.ed.text.toString())
    }

    @Test fun arabicLearnsAndSuggestsTheUsersOwnWordsWithAMirroredStrip() {
        val engine = CoreTestEnv.freshEngine()
        assertTrue(engine.learnUserWord("ar", "كتاب", null))
        val s = session(mode = TypingMode("ar", LayoutKind.ARABIC), engine = engine)
        assertTrue(s.c.candidatesRtl && s.c.showCandidateStrip)
        assertFalse(s.c.candidatesAreDraft) // the user's own words, not DRAFT data
        s.typeRaw("كت")
        assertTrue(s.c.candidates.any { it.text == "كتاب" })
        assertEquals(-1, s.c.autocorrectIndex)
        s.pick("كتاب")
        assertEquals("كتاب ", s.ed.text.toString())
    }

    @Test fun learningCanBeSwitchedOff() {
        val learned = ArrayList<String>()
        val sink = com.qalsmartkeyboard.ime.userdict.WordLearningSink { _, w, _ -> learned += w }
        val on = session(dictionary = sink)
        on.typeRaw("zebra zebra ")
        assertEquals(listOf("zebra", "zebra"), learned)
        learned.clear()
        val off = session(prefs = Prefs(learn = false), dictionary = sink)
        off.typeRaw("zebra zebra ")
        assertTrue(learned.isEmpty())
    }
}
