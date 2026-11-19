package com.qalsmartkeyboard.ime.voice

import com.qalsmartkeyboard.ime.Composer
import com.qalsmartkeyboard.ime.ComposerKey
import com.qalsmartkeyboard.ime.ComposerUpdate
import com.qalsmartkeyboard.ime.FakeEditor
import com.qalsmartkeyboard.ime.FidelGrid
import com.qalsmartkeyboard.ime.InputConnectionAdapter
import com.qalsmartkeyboard.ime.InputProfile
import com.qalsmartkeyboard.ime.Key
import com.qalsmartkeyboard.ime.KeyKind
import com.qalsmartkeyboard.ime.KeyboardController
import com.qalsmartkeyboard.ime.InMemoryModePreferences
import com.qalsmartkeyboard.ime.TypingMode
import com.qalsmartkeyboard.ime.userdict.WordLearningSink
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Dictated text reaching the field through the real keyboard controller and InputConnection adapter: the open
 * composition is finished first, spacing follows the text before the cursor, and nothing is learned (ADR 0029).
 */
class VoiceInsertionTest {
    /** Letters compose; Space commits the word and a space; Commit commits it as it is. */
    private class WordComposer : Composer {
        private var buf = ""
        override fun onKey(key: ComposerKey): ComposerUpdate = when (key) {
            is ComposerKey.Text -> { buf += key.text; ComposerUpdate(composing = buf) }
            ComposerKey.Space -> ComposerUpdate(commit = buf + " ").also { buf = "" }
            ComposerKey.Commit -> ComposerUpdate(commit = buf).also { buf = "" }
            ComposerKey.Enter -> ComposerUpdate(commit = buf + "\n").also { buf = "" }
            ComposerKey.Backspace -> if (buf.isEmpty()) ComposerUpdate(deleteBefore = 1) else { buf = buf.dropLast(1); ComposerUpdate(composing = buf) }
            is ComposerKey.FidelConsonant, is ComposerKey.FidelVowel -> ComposerUpdate(composing = buf)
        }
        override fun selectCandidate(index: Int) = ComposerUpdate(composing = buf)
        override fun reset() { buf = "" }
        override fun setMode(mode: TypingMode) = true.also { buf = "" }
        override fun supports(mode: TypingMode) = true
        override fun isDraft(mode: TypingMode) = false
        override fun fidelGrid(language: String): FidelGrid? = null
    }

    private class H(text: String = "") {
        val ed = FakeEditor(text)
        val adapter = InputConnectionAdapter { ed }.also { it.startSession(false, text.length, text.length) }
        val learned = ArrayList<String>()
        val c = KeyboardController(
            adapter, { listOf("en") }, InMemoryModePreferences(),
            WordLearningSink { lang, word, previous -> learned += "$lang:$word<$previous" },
        ) {}.also {
            it.composer = WordComposer()
            it.startField(InputProfile.DEFAULT)
        }
        val host = object : VoiceHost {
            override val keyboardLanguage = "en"
            override fun hasPermission() = true
            override fun requestPermission() = Unit
            override fun beforeVoice() = c.commitComposition()
            override fun textBeforeCursor(): CharSequence? = adapter.editorOps()?.getTextBeforeCursor(4)
            override fun wantsCapital() = c.wantsCapitalAtCursor()
            override fun commitVoiceText(text: String) = c.commitVoiceText(text)
        }
        val provider = FakeVoiceProvider(languages = mutableMapOf("default" to FakeVoiceProvider.online("en-US")))
        val voice = VoiceController(host, provider, VoicePrefs.InMemory(), deviceLocale = { Locale.US }) {}

        fun type(s: String) {
            for (ch in s) c.onKey(if (ch == ' ') Key(KeyKind.SPACE, "") else Key(KeyKind.CHAR, ch.toString(), ch.toString()))
            ed.deliver(adapter)
        }

        fun dictate(text: String) {
            voice.onMicTapped()
            provider.last.listener!!.onReady()
            provider.last.listener!!.onFinal(text)
            ed.deliver(adapter)
        }
    }

    @Test fun theOpenCompositionIsFinishedBeforeTheTextIsInserted() {
        val h = H()
        h.type("hel")
        assertEquals("hel", h.ed.composing)
        h.dictate("good morning")
        assertEquals("hel good morning", h.ed.text.toString())
        assertEquals("", h.ed.composing)
    }

    @Test fun dictatedWordsAreNeverLearned() {
        val h = H()
        h.type("hello ")
        assertEquals(listOf("en:hello<null"), h.learned)
        h.dictate("morning everyone")
        h.type(" next ")
        // "morning" and "everyone" never reach the dictionary, and "next" is not linked to a dictated word.
        assertEquals(listOf("en:hello<null", "en:next<null"), h.learned)
        assertEquals("hello morning everyone next ", h.ed.text.toString())
    }

    @Test fun noCapitalWhereTheFieldDoesNotAskForOne() {
        val h = H("Hi")
        h.dictate("there")
        assertEquals("Hi there", h.ed.text.toString()) // plain text field without caps flags: as recognised
    }
}
