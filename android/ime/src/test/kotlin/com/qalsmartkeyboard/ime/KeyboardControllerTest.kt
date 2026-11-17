package com.qalsmartkeyboard.ime

import android.text.InputType as T
import android.view.inputmethod.EditorInfo as E
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardControllerTest {
    /** Applies updates to a plain string (one char per deleted cluster: tests use BMP text). */
    private class Fake : TextSink {
        val out = StringBuilder()
        var composing = ""
        val events = ArrayList<String>()
        var autoCaps = false
        override fun apply(update: ComposerUpdate) {
            repeat(update.deleteBefore) { if (out.isNotEmpty()) out.setLength(out.length - 1) }
            out.append(update.commit)
            composing = update.composing
        }
        override fun sendEnterKey() { events += "ENTER" }
        override fun performEditorAction(actionId: Int) { events += "ACTION$actionId" }
        override fun wantsAutoCapital(capsFlags: Int) = autoCaps
    }

    /**
     * A stand-in for the core in phonetic mode: letters compose upper-cased, candidates are the
     * composition and the composition + "!", Space/Commit commit. Records mode switches.
     */
    private class FakeComposer(private val draft: Boolean = true) : Composer {
        val calls = ArrayList<String>()
        var mode = TypingMode.ABC
        private var buf = ""
        private val cands get() = if (buf.isEmpty()) emptyList() else listOf(
            Suggestion(buf.uppercase(), SuggestionKind.TRANSLITERATION),
            Suggestion(buf.uppercase() + "!", SuggestionKind.COMPLETION),
        )

        private fun view(commit: String = "") = ComposerUpdate(composing = buf.uppercase(), commit = commit, candidates = cands)
        private fun flush(): String = buf.uppercase().also { buf = "" }

        override fun onKey(key: ComposerKey): ComposerUpdate {
            if (mode == TypingMode.ABC) return PassthroughComposer().onKey(key)
            return when (key) {
                is ComposerKey.Text -> { buf += key.text; view() }
                ComposerKey.Space -> view(flush() + " ")
                ComposerKey.Enter -> view(flush() + "\n")
                ComposerKey.Commit -> view(flush())
                ComposerKey.Backspace -> if (buf.isEmpty()) ComposerUpdate(deleteBefore = 1) else { buf = buf.dropLast(1); view() }
            }
        }

        override fun selectCandidate(index: Int): ComposerUpdate {
            val c = cands.getOrNull(index) ?: return view()
            buf = ""
            return view(c.text)
        }

        override fun reset() { calls += "reset"; buf = "" }
        override fun setMode(mode: TypingMode): Boolean {
            calls += "setMode $mode"
            buf = ""
            this.mode = mode
            return mode in availableModes
        }
        override val availableModes = listOf(TypingMode.ABC, TypingMode.AM_PHONETIC)
        override fun isDraft(mode: TypingMode) = draft && mode.phonetic
    }

    private fun setup(type: Int = T.TYPE_CLASS_TEXT, opts: Int = 0, f: Fake = Fake()): Pair<KeyboardController, Fake> {
        val c = KeyboardController(f) {}
        c.startField(InputProfile.from(type, opts, false, 0))
        return c to f
    }

    private fun phonetic(type: Int = T.TYPE_CLASS_TEXT): Triple<KeyboardController, Fake, FakeComposer> {
        val (c, f) = setup(type)
        val fc = FakeComposer()
        c.composer = fc
        return Triple(c, f, fc)
    }

    private fun KeyboardController.tap(label: String) =
        onKey(layout.rows.flatten().first { !it.isSpacer && (it.label == label || it.text == label) })

    private fun KeyboardController.tapKind(kind: KeyKind) =
        onKey(layout.rows.flatten().first { it.kind == kind })

    private fun KeyboardController.hasKind(kind: KeyKind) = layout.rows.flatten().any { it.kind == kind }

    // ------------------------------------------------------------------ A1.2 behaviour (ABC)

    @Test fun typesLowercaseAscii() {
        val (c, f) = setup()
        "hello".forEach { c.tap(it.toString()) }
        c.tap(" ")
        assertEquals("hello ", f.out.toString())
    }

    @Test fun shiftOnceThenLockThenOff() {
        val (c, f) = setup()
        c.tapKind(KeyKind.SHIFT); c.tap("a"); c.tap("b")
        assertEquals("Ab", f.out.toString())
        c.tapKind(KeyKind.SHIFT); c.tapKind(KeyKind.SHIFT)
        assertEquals(ShiftState.LOCKED, c.shift)
        c.tap("c"); c.tap("d")
        assertEquals("AbCD", f.out.toString())
        c.tapKind(KeyKind.SHIFT)
        assertEquals(ShiftState.OFF, c.shift)
    }

    @Test fun autoCapitalAtSentenceStart() {
        val f = Fake().also { it.autoCaps = true }
        val (c, _) = setup(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_FLAG_CAP_SENTENCES, f = f)
        assertEquals(ShiftState.ONCE, c.shift)
        f.autoCaps = false
        c.tap("h")
        assertEquals("H", f.out.toString())
        assertEquals(ShiftState.OFF, c.shift)
    }

    @Test fun backspaceDeletes() {
        val (c, f) = setup()
        c.tap("a"); c.tap("b"); c.tapKind(KeyKind.BACKSPACE)
        assertEquals("a", f.out.toString())
    }

    @Test fun symbolPagesAndBack() {
        val (c, f) = setup()
        c.tap("?123")
        assertEquals(Page.SYMBOLS, c.page)
        c.tap("@"); c.tap("#")
        c.tap("=\\<")
        assertEquals(Page.SYMBOLS_2, c.page)
        c.tap("?123"); c.tap("ABC")
        assertEquals(Page.LETTERS, c.page)
        assertEquals("@#", f.out.toString())
    }

    @Test fun symbolsAreNotUppercased() {
        val (c, f) = setup()
        c.tapKind(KeyKind.SHIFT)
        c.tap("?123"); c.tap("-")
        assertEquals("-", f.out.toString())
    }

    @Test fun enterBehaviour() {
        val (c, f) = setup(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_FLAG_MULTI_LINE)
        c.tapKind(KeyKind.ENTER)
        assertEquals("\n", f.out.toString())
        val (c2, f2) = setup(T.TYPE_CLASS_TEXT, E.IME_ACTION_GO)
        c2.tapKind(KeyKind.ENTER)
        assertEquals(listOf("ACTION${E.IME_ACTION_GO}"), f2.events)
        val (c3, f3) = setup(T.TYPE_CLASS_TEXT)
        c3.tapKind(KeyKind.ENTER)
        assertEquals(listOf("ENTER"), f3.events)
    }

    @Test fun emailAndUriLayoutsHaveTheirKeys() {
        val (e, _) = setup(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        assertTrue(e.layout.rows.flatten().any { it.text == "@" })
        val (u, _) = setup(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_URI)
        assertTrue(u.layout.rows.flatten().any { it.text == "/" })
        assertTrue(u.layout.rows.flatten().any { it.text == ".com" })
    }

    @Test fun numberFieldStartsOnDigitPad() {
        val (c, f) = setup(T.TYPE_CLASS_NUMBER)
        assertEquals(Page.NUMBER, c.page)
        c.tap("7"); c.tap("5")
        assertEquals("75", f.out.toString())
        assertTrue(c.layout.rows.flatten().none { it.isLetter })
    }

    @Test fun passwordUsesLettersWithoutLearning() {
        val (c, f) = setup(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD)
        assertEquals(Page.LETTERS, c.page)
        assertFalse(c.profile.learningAllowed)
        c.tap("p")
        assertEquals("p", f.out.toString())
    }

    // ------------------------------------------------------------------ A1.3 composer + strip

    @Test fun modeKeyCyclesLoadedModesAndCommitsFirst() {
        val (c, f, fc) = phonetic()
        assertEquals(TypingMode.ABC, c.effectiveMode)
        c.tapKind(KeyKind.MODE)
        assertEquals(TypingMode.AM_PHONETIC, c.effectiveMode)
        c.tap("s"); c.tap("e")
        assertEquals("SE", f.composing)
        c.tapKind(KeyKind.MODE) // back to ABC: the composition is committed, not lost
        assertEquals(TypingMode.ABC, c.effectiveMode)
        assertEquals("SE", f.out.toString())
        assertEquals("", f.composing)
        assertEquals("setMode ABC", fc.calls.last())
    }

    @Test fun phoneticCompositionCommitsOnSpaceAndShowsCandidates() {
        val (c, f, _) = phonetic()
        c.tapKind(KeyKind.MODE)
        c.tap("a"); c.tap("b")
        assertTrue(c.showCandidateStrip)
        assertTrue(c.candidatesAreDraft)
        assertEquals(listOf("AB", "AB!"), c.candidates.map { it.text })
        c.tap(" ")
        assertEquals("AB ", f.out.toString())
        assertTrue(c.candidates.isEmpty())
    }

    @Test fun candidateTapCommitsIt() {
        val (c, f, _) = phonetic()
        c.tapKind(KeyKind.MODE)
        c.tap("a")
        c.selectCandidate(1)
        assertEquals("A!", f.out.toString())
        assertEquals("", f.composing)
        c.selectCandidate(5) // out of range: nothing happens
        assertEquals("A!", f.out.toString())
    }

    @Test fun noAutoCapitalInPhoneticMode() {
        val f = Fake().also { it.autoCaps = true }
        val (c, _) = setup(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_FLAG_CAP_SENTENCES, f = f)
        c.composer = FakeComposer()
        assertEquals(ShiftState.ONCE, c.shift)
        c.tapKind(KeyKind.MODE)
        assertEquals(ShiftState.OFF, c.shift)
    }

    @Test fun nonTextFieldsStayAbcAndKeepTheChoiceForTextFields() {
        val (c, _, fc) = phonetic()
        c.tapKind(KeyKind.MODE)
        for (type in listOf(
            T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD,
            T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_URI,
            T.TYPE_CLASS_NUMBER,
            T.TYPE_CLASS_PHONE,
        )) {
            c.startField(InputProfile.from(type, 0, false, 0))
            assertEquals(TypingMode.ABC, c.effectiveMode)
            assertEquals("setMode ABC", fc.calls.last())
            assertFalse(c.showCandidateStrip)
            assertFalse(c.hasKind(KeyKind.MODE))
        }
        c.startField(InputProfile.from(T.TYPE_CLASS_TEXT, 0, false, 0))
        assertEquals(TypingMode.AM_PHONETIC, c.effectiveMode)
        assertTrue(c.hasKind(KeyKind.MODE))
    }

    @Test fun passwordFieldHidesTheStripAndTypesLatin() {
        val (c, f, _) = phonetic()
        c.tapKind(KeyKind.MODE)
        c.startField(InputProfile.from(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD, 0, false, 0))
        c.tap("a")
        assertEquals("a", f.out.toString())
        assertFalse(c.showCandidateStrip)
        assertTrue(c.candidates.isEmpty())
    }

    @Test fun abandonedCompositionResetsTheComposer() {
        val (c, f, fc) = phonetic()
        c.tapKind(KeyKind.MODE)
        c.tap("a")
        c.onCompositionAbandoned()
        assertEquals("reset", fc.calls.last())
        assertTrue(c.candidates.isEmpty())
        c.tap("b"); c.tap(" ")
        assertEquals("B ", f.out.toString()) // "A" is not committed again
    }

    @Test fun editorActionCommitsTheCompositionFirst() {
        val (c, f) = setup(T.TYPE_CLASS_TEXT, E.IME_ACTION_SEND)
        c.composer = FakeComposer()
        c.tapKind(KeyKind.MODE)
        c.tap("h"); c.tap("i")
        c.tapKind(KeyKind.ENTER)
        assertEquals("HI", f.out.toString())
        assertEquals(listOf("ACTION${E.IME_ACTION_SEND}"), f.events)
    }

    @Test fun modeKeyDoesNothingUntilPhoneticModesAreLoaded() {
        val (c, _) = setup()
        c.tapKind(KeyKind.MODE)
        assertEquals(TypingMode.ABC, c.effectiveMode)
        assertFalse(c.showCandidateStrip)
    }
}
