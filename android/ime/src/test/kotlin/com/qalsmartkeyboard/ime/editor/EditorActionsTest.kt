package com.qalsmartkeyboard.ime.editor

import com.qalsmartkeyboard.ime.ai.ClipAvailability
import com.qalsmartkeyboard.ime.ai.FakeClipboard
import com.qalsmartkeyboard.ime.ai.forEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR 0038: the field's action row follows the host field's clipboard rules (ADR 0031 §7, ADR 0035 §6). */
class EditorActionsTest {
    private fun buf(text: String, s: Int, e: Int = s) = EditorBuffer().also {
        it.setText(text)
        it.setSelection(s, e)
    }

    @Test fun cutAndCopyOnlyWithASelection() {
        val clip = FakeClipboard()
        val b = buf("hello world", 5)
        assertEquals(listOf(EditorAction.SELECT_ALL), EditorActions.available(b, clip.forEditor()))
        b.setSelection(6, 11)
        assertEquals(listOf(EditorAction.CUT, EditorAction.COPY, EditorAction.SELECT_ALL), EditorActions.available(b, clip.forEditor()))
        assertTrue(EditorActions.perform(EditorAction.COPY, b, clip.forEditor()))
        assertEquals(listOf("world"), clip.copies)
        assertEquals("hello world", b.text)
        assertTrue(EditorActions.perform(EditorAction.CUT, b, clip.forEditor()))
        assertEquals("hello ", b.text)
        assertEquals(listOf("world", "world"), clip.copies)
    }

    @Test fun aRefusedCopyDoesNotCut() {
        val clip = FakeClipboard().also { it.copyWorks = false }
        val b = buf("abc", 0, 3)
        assertFalse(EditorActions.perform(EditorAction.CUT, b, clip.forEditor()))
        assertEquals("abc", b.text)
    }

    @Test fun pasteInsertsAtTheCursorAndReadsTheClipOnlyOnTheTap() {
        val clip = FakeClipboard(ClipAvailability.TEXT, "ሰላም ")
        val b = buf("Hello world", 6)
        val ed = clip.forEditor()
        assertTrue(EditorAction.PASTE in EditorActions.available(b, ed))
        assertEquals(0, clip.contentReads) // offering Paste reads the description only
        assertTrue(EditorActions.perform(EditorAction.PASTE, b, ed))
        assertEquals("Hello ሰላም world", b.text)
        assertEquals(1, clip.contentReads)
    }

    @Test fun pasteReplacesTheSelection() {
        val clip = FakeClipboard(ClipAvailability.TEXT, "tea")
        val b = buf("I like coffee", 7, 13)
        EditorActions.perform(EditorAction.PASTE, b, clip.forEditor())
        assertEquals("I like tea", b.text)
    }

    @Test fun aSensitiveClipIsNeverOfferedOrRead() {
        val clip = FakeClipboard(ClipAvailability.SENSITIVE, "123456")
        val b = buf("code: ", 6)
        val ed = clip.forEditor()
        assertFalse(EditorAction.PASTE in EditorActions.available(b, ed))
        assertFalse(EditorActions.perform(EditorAction.PASTE, b, ed))
        assertEquals("code: ", b.text)
        assertEquals(0, clip.contentReads)
    }

    @Test fun selectAllOnlyWhenNotEverythingIsSelected() {
        val b = buf("ሰላም", 0, 3)
        assertFalse(EditorAction.SELECT_ALL in EditorActions.available(b, EditorClipboard.None))
        b.setSelection(1)
        assertTrue(EditorActions.perform(EditorAction.SELECT_ALL, b, EditorClipboard.None))
        assertEquals("ሰላም", b.selectedText)
        assertEquals(emptyList<EditorAction>(), EditorActions.available(EditorBuffer(), EditorClipboard.None))
    }
}
