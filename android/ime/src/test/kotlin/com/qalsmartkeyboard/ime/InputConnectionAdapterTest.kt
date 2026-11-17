package com.qalsmartkeyboard.ime

import com.qalsmartkeyboard.ime.InputConnectionAdapter.Companion.KEYCODE_DEL
import com.qalsmartkeyboard.ime.InputConnectionAdapter.Companion.utf16LengthOfLastGraphemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Update application, grapheme deletion, the direct-commit fallback and selection handling. */
class InputConnectionAdapterTest {
    private class Setup(initial: String = "", typeNull: Boolean = false) {
        val ed = FakeEditor(initial)
        val a = InputConnectionAdapter { ed }
        var abandoned = 0

        init {
            a.onCompositionAbandoned = { abandoned++ }
            a.startSession(typeNull, initial.length, initial.length)
        }

        /** Applies [u] and delivers the editor's report right away (a responsive host). */
        fun apply(u: ComposerUpdate) {
            a.apply(u)
            ed.deliver(a)
        }
    }

    private fun comp(s: String) = ComposerUpdate(composing = s)
    private fun commit(s: String, composing: String = "") = ComposerUpdate(commit = s, composing = composing)
    private fun del(n: Int) = ComposerUpdate(deleteBefore = n)

    /** The updates the core sends for "selam" + Space in phonetic Amharic. */
    private val selam = listOf(comp("ስ"), comp("ሰ"), comp("ሰል"), comp("ሰላ"), comp("ሰላም"), commit("ሰላም "))

    // ------------------------------------------------------------------ composing path

    @Test fun composingThenCommitReplacesTheRegion() {
        val s = Setup("x ")
        for (u in selam.dropLast(1)) {
            s.apply(u)
            assertEquals(u.composing, s.ed.composing)
        }
        assertEquals("x ሰላም", s.ed.text.toString())
        s.apply(selam.last())
        assertEquals("x ሰላም ", s.ed.text.toString())
        assertEquals("", s.ed.composing)
        assertEquals(CompositionMode.COMPOSING, s.a.mode)
        assertEquals(0, s.abandoned)
    }

    @Test fun backspaceToEmptyCompositionRemovesIt() {
        val s = Setup("a")
        s.apply(comp("ስ"))
        s.apply(comp(""))
        assertEquals("a", s.ed.text.toString())
        assertEquals("", s.ed.composing)
    }

    @Test fun candidateSelectionCommitsOverTheComposition() {
        val s = Setup()
        s.apply(comp("ሰላ"))
        s.apply(commit("ሰላም"))
        assertEquals("ሰላም", s.ed.text.toString())
        assertEquals(-1, s.ed.compStart)
    }

    @Test fun eachUpdateIsOneBatchEditWithOneReport() {
        val s = Setup("ab")
        s.a.apply(ComposerUpdate(deleteBefore = 1, commit = "c", composing = "ስ"))
        assertEquals(1, s.ed.reports.size)
        assertEquals("acስ", s.ed.text.toString())
    }

    // ------------------------------------------------------------------ grapheme deletion

    @Test fun graphemeLengths() {
        assertEquals(1, utf16LengthOfLastGraphemes("ሰላም", 1))
        assertEquals(2, utf16LengthOfLastGraphemes("ሰላም", 2))
        assertEquals(3, utf16LengthOfLastGraphemes("ሰላም", 9))
        assertEquals(2, utf16LengthOfLastGraphemes("ሰ፟", 1)) // syllable + combining gemination mark
        assertEquals(2, utf16LengthOfLastGraphemes("é", 1))
        assertEquals(2, utf16LengthOfLastGraphemes("hi😀", 1)) // surrogate pair
        assertEquals(4, utf16LengthOfLastGraphemes("👍🏽", 1)) // emoji + skin tone
        assertEquals(8, utf16LengthOfLastGraphemes("🇪🇹🇪🇹", 2).let { it }) // two flags
        val family = "👨‍👩‍👧"
        assertEquals(family.length, utf16LengthOfLastGraphemes("ሰ$family", 1))
        assertEquals(0, utf16LengthOfLastGraphemes("", 1))
    }

    @Test fun deleteBeforeRemovesWholeClusters() {
        val family = "👨‍👩‍👧"
        for ((before, n, after) in listOf(
            Triple("ሰላም", 1, "ሰላ"),
            Triple("ሰላም", 2, "ሰ"),
            Triple("ok👍🏽", 1, "ok"),
            Triple("ሰ$family", 1, "ሰ"),
            Triple("ኢትዮጵያ🇪🇹", 1, "ኢትዮጵያ"),
            Triple("a፟", 1, ""),
        )) {
            val s = Setup(before)
            s.apply(del(n))
            assertEquals(before, after, s.ed.text.toString())
        }
    }

    @Test fun backspaceWithASelectionSendsDel() {
        val s = Setup("ሰላም ነው")
        s.ed.selStart = 0; s.ed.selEnd = 3
        s.a.onSelectionUpdate(0, 3, -1, -1)
        assertEquals(1, s.abandoned) // a selection we did not make
        s.apply(del(1))
        assertEquals(listOf(KEYCODE_DEL), s.ed.keys)
        assertEquals(" ነው", s.ed.text.toString())
    }

    @Test fun hostsWithoutTextQueriesGetDelKeys() {
        val s = Setup("ሰላም")
        s.ed.answersTextQueries = false
        s.apply(del(2))
        assertEquals(listOf(KEYCODE_DEL, KEYCODE_DEL), s.ed.keys)
        assertEquals("ሰ", s.ed.text.toString())
    }

    @Test fun backspaceInAnEmptyFieldStillReachesTheApp() {
        val s = Setup("")
        s.apply(del(1))
        assertEquals(listOf(KEYCODE_DEL), s.ed.keys) // e.g. deletes a recipient chip
    }

    // ------------------------------------------------------------------ direct-commit fallback

    @Test fun typeNullStartsInDirectCommitAndUsesKeyEvents() {
        val s = Setup("", typeNull = true)
        s.ed.answersTextQueries = false
        s.ed.honoursDeleteSurrounding = false
        assertEquals(CompositionMode.DIRECT_COMMIT, s.a.mode)
        assertEquals(FallbackReason.TYPE_NULL, s.a.fallbackReason)
        selam.forEach { s.apply(it) }
        assertEquals("ሰላም ", s.ed.text.toString())
        assertTrue(s.ed.calls.none { it == "setComposingText" })
        assertTrue(s.ed.keys.all { it == KEYCODE_DEL } && s.ed.keys.isNotEmpty())
    }

    @Test fun setComposingTextFailureSwitchesToDirectCommit() {
        val s = Setup("x")
        s.ed.setComposingResult = false
        selam.forEach { s.apply(it) }
        assertEquals("xሰላም ", s.ed.text.toString())
        assertEquals(CompositionMode.DIRECT_COMMIT, s.a.mode)
        assertEquals(FallbackReason.SET_COMPOSING_REJECTED, s.a.fallbackReason)
        assertEquals(0, s.abandoned)
    }

    @Test fun missingComposingSpanIsDetectedAndRepairedInSync() {
        // The host inserts composing text as plain text and reports no span.
        val s = Setup("x")
        s.ed.keepsComposingSpan = false
        selam.forEach { s.apply(it) }
        assertEquals("xሰላም ", s.ed.text.toString())
        assertEquals(CompositionMode.DIRECT_COMMIT, s.a.mode)
        assertEquals(FallbackReason.NO_COMPOSING_SPAN, s.a.fallbackReason)
    }

    @Test fun directCommitReplacesOnlyWhatChanged() {
        val s = Setup("", typeNull = false)
        s.ed.setComposingResult = false
        s.apply(comp("ሰላ"))
        s.ed.calls.clear()
        s.apply(comp("ሰላም"))
        assertEquals(listOf("commitText"), s.ed.calls) // appended "ም", nothing deleted
        s.apply(comp("ሰላ")) // Backspace inside the composition
        assertEquals("ሰላ", s.ed.text.toString())
        s.apply(commit("ሰላም ")) // candidate + space
        assertEquals("ሰላም ", s.ed.text.toString())
        s.apply(del(1))
        assertEquals("ሰላም", s.ed.text.toString())
    }

    // ------------------------------------------------------------------ selection changes

    @Test fun userCursorMoveFinishesTheCompositionWithoutLosingText() {
        val s = Setup("ab")
        s.apply(comp("ሰላ"))
        s.ed.userMovesCursor(1)
        s.ed.deliver(s.a)
        assertEquals(1, s.abandoned)
        assertEquals("abሰላ", s.ed.text.toString())
        assertEquals("", s.ed.composing)
        // The keyboard reset its composer; the next word starts at the new cursor.
        s.apply(comp("ነ"))
        s.apply(commit("ነው"))
        assertEquals("aነውbሰላ", s.ed.text.toString())
    }

    @Test fun lateReportOfOurFinishComposingTextIsNotAnotherCursorMove() {
        // Seen on the emulator: tap elsewhere mid-composition, then type at once. The report for
        // the finishComposingText we send on the tap arrives after the next key's edit.
        val s = Setup("ab")
        s.apply(comp("ሰላ"))
        s.ed.userMovesCursor(0)
        s.ed.deliverOne(s.a) // the tap: external, we finish the span (its report is queued)
        assertEquals(1, s.abandoned)
        s.a.apply(comp("ድ")) // next key before that report arrives
        s.a.apply(comp("ደ"))
        s.ed.deliver(s.a)
        assertEquals(1, s.abandoned)
        assertEquals("ደ", s.ed.composing)
        s.apply(commit("ደ "))
        assertEquals("ደ abሰላ", s.ed.text.toString())
    }

    @Test fun directCommitCursorMoveKeepsTheTextAndForgetsIt() {
        val s = Setup("ab")
        s.ed.setComposingResult = false
        s.apply(comp("ሰላ"))
        s.ed.userMovesCursor(0)
        s.ed.deliver(s.a)
        assertEquals(1, s.abandoned)
        s.apply(commit("ነ"))
        assertEquals("ነabሰላ", s.ed.text.toString()) // nothing deleted at the new cursor
    }

    @Test fun lateReportsOfOwnEditsAreNotCursorMoves() {
        val s = Setup("")
        for (u in selam + listOf(comp("ነ"), comp("ነው"))) s.a.apply(u) // reports pile up
        assertTrue(s.ed.reports.size > 3)
        s.ed.deliver(s.a)
        assertEquals(0, s.abandoned)
        assertEquals(CompositionMode.COMPOSING, s.a.mode)
        s.apply(commit("ነው "))
        assertEquals("ሰላም ነው ", s.ed.text.toString())
    }

    @Test fun lateReportOfACommitAtTheSameCursorIsNotARejection() {
        // commit "ሰላም" (cursor 3, no span), delete one (cursor 2), compose "ስ" (cursor 3, span):
        // the late report for the commit has the composition's cursor but no span.
        val s = Setup("")
        s.a.apply(comp("ሰላ"))
        s.a.apply(commit("ሰላም"))
        s.a.apply(del(1))
        s.a.apply(comp("ስ"))
        s.ed.deliver(s.a)
        assertEquals(CompositionMode.COMPOSING, s.a.mode)
        assertEquals(0, s.abandoned)
        assertEquals("ሰላስ", s.ed.text.toString())
    }

    @Test fun repeatedReportOfTheCurrentStateIsIgnored() {
        val s = Setup("")
        s.apply(comp("ሰ"))
        s.a.onSelectionUpdate(1, 1, 0, 1)
        s.a.onSelectionUpdate(1, 1, 0, 1)
        assertEquals(0, s.abandoned)
        assertEquals("ሰ", s.ed.composing)
    }

    @Test fun finishCompositionKeepsTextAsPlainText() {
        val s = Setup("")
        s.apply(comp("ሰላ"))
        s.a.finishComposition()
        assertEquals("ሰላ", s.ed.text.toString())
        assertEquals("", s.ed.composing)
        // A new session starts clean.
        s.a.startSession(false, 2, 2)
        s.ed.deliver(s.a)
        s.apply(comp("ም"))
        assertEquals("ሰላም", s.ed.text.toString())
    }

    @Test fun unknownStartPositionResynchronises() {
        val ed = FakeEditor("abc")
        val a = InputConnectionAdapter { ed }
        var abandoned = 0
        a.onCompositionAbandoned = { abandoned++ }
        a.startSession(false, -1, -1)
        a.apply(comp("ሰ"))
        ed.deliver(a)
        a.apply(comp("ሰላ"))
        ed.deliver(a)
        assertEquals(0, abandoned)
        ed.userMovesCursor(0)
        ed.deliver(a)
        assertEquals(1, abandoned)
    }

    @Test fun enterKeyAndEditorActionsMakeThePositionUnknownNotExternal() {
        val s = Setup("a")
        s.a.sendEnterKey()
        s.a.performEditorAction(6)
        s.ed.userMovesCursor(0) // whatever the app did, no composition is lost
        s.ed.deliver(s.a)
        assertEquals(0, s.abandoned)
        assertEquals(listOf(6), s.ed.actions)
    }

    @Test fun noEditorIsANoOp() {
        val a = InputConnectionAdapter { null }
        a.startSession(false, 0, 0)
        a.apply(comp("ሰ"))
        assertNull(a.fallbackReason)
    }
}
