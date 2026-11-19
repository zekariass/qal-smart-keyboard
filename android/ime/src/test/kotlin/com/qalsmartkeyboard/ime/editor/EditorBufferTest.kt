package com.qalsmartkeyboard.ime.editor

import com.qalsmartkeyboard.ime.ComposerUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR 0038: the keyboard's own text fields edit at a cursor, by grapheme clusters, in logical order. */
class EditorBufferTest {
    private fun buf(text: String, cursor: Int = text.length) = EditorBuffer().also {
        it.setText(text)
        it.setSelection(cursor)
    }

    private fun EditorBuffer.type(s: String) = apply(ComposerUpdate(commit = s))
    private fun EditorBuffer.backspace(n: Int = 1) = apply(ComposerUpdate(deleteBefore = n))

    // ------------------------------------------------------------------ insert / delete / replace at the cursor

    @Test fun keysInsertAtTheCursorInTheMiddle() {
        val b = buf("Helo world", 3)
        b.type("l")
        assertEquals("Hello world", b.text)
        assertEquals(4, b.cursor)
        b.type(", dear")
        assertEquals("Hell, dearo world", b.text)
    }

    @Test fun backspaceDeletesBeforeTheCursorInTheMiddle() {
        val b = buf("Hello world", 5)
        b.backspace()
        assertEquals("Hell world", b.text)
        assertEquals(4, b.cursor)
        b.backspace(2)
        assertEquals("He world", b.text)
        assertEquals(2, b.cursor)
    }

    @Test fun backspaceAtTheStartDoesNothing() {
        val b = buf("abc", 0)
        b.backspace()
        assertEquals("abc", b.text)
        assertEquals(0, b.cursor)
    }

    @Test fun aCommitReplacesTheSelection() {
        val b = buf("I like tea")
        b.setSelection(7, 10)
        b.type("coffee")
        assertEquals("I like coffee", b.text)
        assertEquals(13, b.cursor)
        assertFalse(b.hasSelection)
    }

    @Test fun backspaceDeletesTheSelectionFirst() {
        val b = buf("one two three")
        b.setSelection(4, 8)
        b.backspace()
        assertEquals("one three", b.text)
        assertEquals(4, b.cursor)
    }

    @Test fun selectionIsNormalisedWhateverTheOrder() {
        val b = buf("abcdef")
        b.setSelection(5, 1)
        assertEquals(1, b.selectionStart)
        assertEquals(5, b.selectionEnd)
        assertEquals("bcde", b.selectedText)
    }

    @Test fun insertReplacesTheSelectionAndDeleteSelectionReturnsIt() {
        val b = buf("cat dog")
        b.setSelection(0, 3)
        b.insert("bird")
        assertEquals("bird dog", b.text)
        b.setSelection(5, 8)
        assertEquals("dog", b.deleteSelection())
        assertEquals("bird ", b.text)
    }

    // ------------------------------------------------------------------ grapheme clusters (Ethiopic, Arabic, emoji)

    @Test fun backspaceRemovesAnEthiopicLetterWithItsGeminationMark() {
        // ሰ + U+135F (combining gemination mark) is one cluster.
        val b = buf("ሰ፟ላም", 2)
        b.backspace()
        assertEquals("ላም", b.text)
        assertEquals(0, b.cursor)
    }

    @Test fun ethiopicSyllablesAreOneClusterEach() {
        val b = buf("ሰላም ዓለም")
        b.backspace()
        assertEquals("ሰላም ዓለ", b.text)
        b.setSelection(1)
        b.type("ል")
        assertEquals("ሰልላም ዓለ", b.text)
    }

    @Test fun backspaceRemovesAnArabicLetterWithItsHarakat() {
        // بِسْمِ: ب + kasra, س + sukun, م + kasra.
        val text = "بِسْمِ"
        val b = buf(text)
        b.backspace()
        assertEquals("بِسْ", b.text)
        b.backspace()
        assertEquals("بِ", b.text)
        b.backspace()
        assertEquals("", b.text)
    }

    @Test fun aTapInsideAClusterSnapsToItsStart() {
        val b = buf("بِسْ")
        b.setSelection(1) // between ب and its kasra
        assertEquals(0, b.cursor)
        b.setSelection(3) // between س and its sukun
        assertEquals(2, b.cursor)
        val e = buf("ሰ፟ላ")
        e.setSelection(1)
        assertEquals(0, e.cursor)
    }

    @Test fun anEmojiSequenceIsDeletedWhole() {
        val b = buf("hi 👩‍💻") // woman technologist (ZWJ sequence)
        b.backspace()
        assertEquals("hi ", b.text)
        val flag = buf("🇪🇹") // the Ethiopian flag: two regional indicators
        flag.backspace()
        assertEquals("", flag.text)
    }

    @Test fun aSurrogatePairIsNeverSplit() {
        val b = buf("a😀b")
        b.setSelection(2)
        assertEquals(1, b.cursor)
    }

    @Test fun lineBreaksAreTheirOwnClusters() {
        val b = buf("ab\ńc") // a stray combining mark after a line break does not join it
        b.setSelection(3)
        b.backspace()
        assertEquals("ab́c", b.text)
        val crlf = buf("a\r\nb", 3)
        crlf.backspace()
        assertEquals("ab", crlf.text)
    }

    // ------------------------------------------------------------------ cursor moves, logical order

    @Test fun cursorMovesByClusters() {
        val b = buf("ሰ፟ላبِ", 0)
        b.moveCursor(1)
        assertEquals(2, b.cursor)
        b.moveCursor(1)
        assertEquals(3, b.cursor)
        b.moveCursor(1)
        assertEquals(5, b.cursor)
        b.moveCursor(1)
        assertEquals(5, b.cursor) // the end stays the end
        b.moveCursor(-2)
        assertEquals(2, b.cursor)
        b.moveCursor(-5)
        assertEquals(0, b.cursor)
    }

    @Test fun aMoveCollapsesTheSelectionToItsSide() {
        val b = buf("abcdef")
        b.setSelection(2, 4)
        b.moveCursor(-1)
        assertEquals(2, b.cursor)
        b.setSelection(2, 4)
        b.moveCursor(1)
        assertEquals(4, b.cursor)
    }

    @Test fun positionsAreLogicalWhateverTheDisplayOrder() {
        // Mixed English and Arabic: the Arabic run is drawn right to left, but offsets stay in typing order.
        val text = "Hi مرحبا you"
        val b = buf(text)
        b.setSelection(5) // after "م ر" in logical order
        b.type("X")
        assertEquals("Hi مرXحبا you", b.text)
        assertEquals(6, b.cursor)
    }

    // ------------------------------------------------------------------ composition (phonetic candidates mid-text)

    @Test fun aCompositionOpensAtTheCursorMidText() {
        val b = buf("abcd", 2)
        b.apply(ComposerUpdate(composing = "s"))
        assertEquals("absc" + "d", b.text)
        assertEquals(2, b.composingStart)
        assertEquals(3, b.composingEnd)
        assertEquals(3, b.cursor)
        b.apply(ComposerUpdate(composing = "ሰ"))
        assertEquals("abሰcd", b.text)
        b.apply(ComposerUpdate(commit = "ሰላም"))
        assertEquals("abሰላምcd", b.text)
        assertEquals(5, b.cursor)
        assertFalse(b.isComposing)
    }

    @Test fun aCompositionReplacesTheSelection() {
        val b = buf("one two")
        b.setSelection(4, 7)
        b.apply(ComposerUpdate(composing = "t"))
        assertEquals("one t", b.text)
        assertTrue(b.isComposing)
    }

    @Test fun backspaceWithACompositionDeletesTheComposition() {
        val b = buf("abcd", 2)
        b.apply(ComposerUpdate(composing = "x"))
        b.apply(ComposerUpdate(composing = ""))
        assertEquals("abcd", b.text)
        assertEquals(2, b.cursor)
        b.apply(ComposerUpdate(composing = "x"))
        b.apply(ComposerUpdate(deleteBefore = 1))
        assertEquals("acd", b.text) // the composition and one cluster before it, like the host field's adapter
    }

    @Test fun movingTheCursorKeepsTheCompositionAsText() {
        val b = buf("abcd", 2)
        b.apply(ComposerUpdate(composing = "xy"))
        b.setSelection(0)
        assertFalse(b.isComposing)
        assertEquals("abxycd", b.text)
        assertEquals(0, b.cursor)
    }

    @Test fun aRecommitBeforeTheCursorWorksMidText() {
        // A phonetic core may delete the previous syllable and commit a new one (deleteBefore + commit).
        val b = buf("ሰ end", 1)
        b.apply(ComposerUpdate(deleteBefore = 1, commit = "ሱ"))
        assertEquals("ሱ end", b.text)
        assertEquals(1, b.cursor)
    }

    // ------------------------------------------------------------------ words

    @Test fun selectsTheWordAtAnOffset() {
        val b = buf("hello world")
        assertTrue(b.selectWordAt(8))
        assertEquals("world", b.selectedText)
        assertTrue(b.selectWordAt(5)) // just after "hello"
        assertEquals("hello", b.selectedText)
    }

    @Test fun selectsWordsInEveryLaunchLanguage() {
        val am = buf("ሰላም ዓለም")
        assertTrue(am.selectWordAt(1))
        assertEquals("ሰላም", am.selectedText)
        val om = buf("Akkam ba'aa jirta")
        assertTrue(om.selectWordAt(8))
        assertEquals("ba'aa", om.selectedText) // the hudhaa apostrophe belongs to the word
        val ar = buf("مرحبا بِك")
        assertTrue(ar.selectWordAt(7))
        assertEquals("بِك", ar.selectedText) // harakat included
        val ti = buf("ከመይ ኣለኹም፧")
        assertTrue(ti.selectWordAt(5))
        assertEquals("ኣለኹም", ti.selectedText) // the Ethiopic question mark is not part of the word
    }

    @Test fun noWordOnSpaces() {
        val b = buf("a    b")
        assertFalse(b.selectWordAt(3))
        assertFalse(buf("").selectWordAt(0))
    }

    @Test fun selectAllSelectsEverything() {
        val b = buf("ሰላም")
        b.selectAll()
        assertEquals(0, b.selectionStart)
        assertEquals(3, b.selectionEnd)
    }

    // ------------------------------------------------------------------ capacity, notifications

    @Test fun theCapacityCutsLongInput() {
        val b = EditorBuffer(5)
        b.setText("abcdefgh")
        assertEquals("abcde", b.text)
        b.setSelection(2)
        b.type("XY")
        assertEquals("abcde", b.text) // full: nothing more goes in
        val e = EditorBuffer(3)
        e.setText("ab😀")
        assertEquals("ab", e.text) // never half a surrogate pair
    }

    @Test fun textChangesAndCursorMovesAreToldApart() {
        val b = EditorBuffer()
        var texts = 0
        val seen = ArrayList<Boolean>()
        b.onTextChanged = { texts++ }
        b.addObserver { seen += it }
        b.setText("abc")
        b.setSelection(1)
        b.type("x")
        b.setSelection(2) // where the cursor already is: no callback
        assertEquals(2, texts)
        assertEquals(listOf(true, false, true), seen)
    }

    @Test fun textBeforeTheCursorIsTheLogicalPrefix() {
        val b = buf("hello world", 5)
        assertEquals("ello", b.textBeforeCursor(4))
        assertEquals("hello", b.textBeforeCursor(50))
    }

    @Test fun toStringHasNoContent() {
        val b = buf("secret words")
        assertFalse(b.toString().contains("secret"))
    }
}
