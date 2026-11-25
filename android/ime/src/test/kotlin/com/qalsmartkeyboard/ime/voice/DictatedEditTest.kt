package com.qalsmartkeyboard.ime.voice

import com.qalsmartkeyboard.ime.FakeEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T8.12 (ADR 0049 D3): replacing just-dictated text through the input connection, and the own-edit window. */
class DictatedEditTest {
    @Test
    fun `replaces the text before the cursor when it reads as expected`() {
        val ed = FakeEditor("Say hello world")
        assertTrue(DictatedEdit.replace(ed, "hello world", "Hello, world."))
        assertEquals("Say Hello, world.", ed.text.toString())
        assertEquals(ed.text.length, ed.selStart)
    }

    @Test
    fun `does nothing when the text before the cursor differs`() {
        val ed = FakeEditor("Say hello there")
        assertFalse(DictatedEdit.replace(ed, "hello world", "Hello, world."))
        assertEquals("Say hello there", ed.text.toString())
        assertFalse(ed.calls.any { it.startsWith("deleteSurroundingText") || it == "commitText" })
    }

    @Test
    fun `does nothing when the cursor moved away from the dictated text`() {
        val ed = FakeEditor("hello world and more")
        ed.userMovesCursor(5)
        assertFalse(DictatedEdit.replace(ed, "hello world", "x"))
        assertEquals("hello world and more", ed.text.toString())
    }

    @Test
    fun `keeps trailing whitespace after the dictated text`() {
        val ed = FakeEditor("Say hello world \n")
        assertTrue(DictatedEdit.replace(ed, "hello world", "Hi"))
        assertEquals("Say Hi \n", ed.text.toString())
        assertEquals(ed.text.length, ed.selStart)
    }

    @Test
    fun `expected that itself ends in whitespace still matches and keeps the tail`() {
        val ed = FakeEditor("a hello world ")
        assertTrue(DictatedEdit.replace(ed, "hello world ", "Hi "))
        assertEquals("a Hi ", ed.text.toString())
    }

    @Test
    fun `an empty expected never replaces`() {
        val ed = FakeEditor("hello")
        assertFalse(DictatedEdit.replace(ed, "", "x"))
        assertEquals("hello", ed.text.toString())
        assertNull(DictatedEdit.keptTail("hello", ""))
    }

    @Test
    fun `an editor that does not answer replaces nothing`() {
        val ed = FakeEditor("hello")
        ed.answersTextQueries = false
        assertFalse(DictatedEdit.replace(ed, "hello", "x"))
        assertEquals("hello", ed.text.toString())
    }

    @Test
    fun `a selection is never replaced`() {
        val ed = FakeEditor("hello")
        ed.selStart = 0
        assertFalse(DictatedEdit.replace(ed, "hello", "x"))
    }

    @Test
    fun `undo restores the original`() {
        val ed = FakeEditor("fix this text ")
        assertTrue(DictatedEdit.replace(ed, "fix this text", "Fix this text."))
        assertTrue(DictatedEdit.replace(ed, "Fix this text.", "fix this text"))
        assertEquals("fix this text ", ed.text.toString())
    }

    @Test
    fun `the edit is one batch`() {
        val ed = FakeEditor("hello")
        assertTrue(DictatedEdit.replace(ed, "hello", "Hi"))
        assertEquals(1, ed.reports.size) // one report for delete + commit
    }

    @Test
    fun `keptTail finds the longest whitespace tail`() {
        assertEquals(0, DictatedEdit.keptTail("abc", "abc"))
        assertEquals(2, DictatedEdit.keptTail("abc  ", "abc"))
        assertNull(DictatedEdit.keptTail("abc  x", "abc"))
        assertNull(DictatedEdit.keptTail(null, "abc"))
    }

    @Test
    fun `own edit window covers the echo of our edit and then expires`() {
        var now = 1_000L
        val w = OwnEditWindow({ now }, windowMs = 500)
        assertFalse(w.active())
        w.mark()
        assertTrue(w.active())
        now += 499
        assertTrue(w.active())
        now += 2
        assertFalse(w.active())
    }
}
