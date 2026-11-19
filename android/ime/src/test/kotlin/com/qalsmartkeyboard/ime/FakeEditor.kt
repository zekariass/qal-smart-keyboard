package com.qalsmartkeyboard.ime

import java.text.BreakIterator

/**
 * A text editor behind an InputConnection, modelled on `BaseInputConnection` semantics: a text
 * buffer, a selection, an optional composing region, batch edits, and the `onUpdateSelection`
 * reports the framework would send (queued in [reports], delivered by the test, possibly late).
 *
 * Quirk switches model hosts that reject composing text.
 */
class FakeEditor(initial: String = "") : EditorOps {
    val text = StringBuilder(initial)
    var selStart = initial.length
    var selEnd = initial.length
    var compStart = -1
    var compEnd = -1

    /** False: `setComposingText` inserts plain text and keeps no span (a rejecting host). */
    var keepsComposingSpan = true

    /** Value `setComposingText` returns (false: the host refuses it, changing nothing). */
    var setComposingResult = true

    /** False: `getTextBeforeCursor` returns null (hosts without a text model). */
    var answersTextQueries = true

    /** False: `deleteSurroundingText` is ignored (TYPE_NULL hosts only see key events). */
    var honoursDeleteSurrounding = true

    val keys = ArrayList<Int>()
    val actions = ArrayList<Int>()
    val calls = ArrayList<String>()

    data class Report(val selStart: Int, val selEnd: Int, val candStart: Int, val candEnd: Int)

    val reports = ArrayDeque<Report>()
    private var batch = 0
    private var lastReported = Report(selStart, selEnd, -1, -1)

    val composing: String get() = if (compStart >= 0) text.substring(compStart, compEnd) else ""

    override fun beginBatchEdit() { batch++ }

    override fun endBatchEdit() {
        batch--
        if (batch == 0) report()
    }

    private fun report() {
        if (batch > 0) return
        val r = Report(selStart, selEnd, compStart, compEnd)
        if (r != lastReported) {
            reports.addLast(r)
            lastReported = r
        }
    }

    /** Delivers all queued reports to [a]. */
    fun deliver(a: InputConnectionAdapter) {
        while (reports.isNotEmpty()) {
            val r = reports.removeFirst()
            a.onSelectionUpdate(r.selStart, r.selEnd, r.candStart, r.candEnd)
        }
    }

    /** Delivers only the oldest queued report to [a]. */
    fun deliverOne(a: InputConnectionAdapter) {
        val r = reports.removeFirstOrNull() ?: return
        a.onSelectionUpdate(r.selStart, r.selEnd, r.candStart, r.candEnd)
    }

    /** The user taps at [pos] (a cursor move we did not make). */
    fun userMovesCursor(pos: Int) {
        selStart = pos
        selEnd = pos
        report()
    }

    private fun replace(start: Int, end: Int, s: String) {
        text.replace(start, end, s)
        selStart = start + s.length
        selEnd = selStart
    }

    override fun getTextBeforeCursor(n: Int): CharSequence? {
        calls += "getTextBeforeCursor"
        if (!answersTextQueries) return null
        return text.substring(maxOf(0, selStart - n), selStart)
    }

    override fun getTextAfterCursor(n: Int): CharSequence? {
        calls += "getTextAfterCursor"
        if (!answersTextQueries) return null
        return text.substring(selEnd, minOf(text.length, selEnd + n))
    }

    override fun deleteSurroundingText(beforeLength: Int): Boolean {
        calls += "deleteSurroundingText($beforeLength)"
        if (!honoursDeleteSurrounding) return true
        val a = selStart
        val start = maxOf(0, a - beforeLength)
        text.delete(start, a)
        val removed = a - start
        if (compStart >= 0) {
            if (compStart >= a) { compStart -= removed; compEnd -= removed } else if (compEnd > start) { compStart = -1; compEnd = -1 }
        }
        selStart -= removed
        selEnd -= removed
        report()
        return true
    }

    override fun commitText(text: String): Boolean {
        calls += "commitText"
        if (compStart >= 0) replace(compStart, compEnd, text) else replace(selStart, selEnd, text)
        compStart = -1
        compEnd = -1
        report()
        return true
    }

    override fun setComposingText(text: String): Boolean {
        calls += "setComposingText"
        if (!setComposingResult) return false
        val (s, e) = if (compStart >= 0) compStart to compEnd else selStart to selEnd
        if (!keepsComposingSpan) {
            // Plain insertion at the cursor, nothing replaced, no span.
            replace(selStart, selEnd, text)
            report()
            return true
        }
        replace(s, e, text)
        if (text.isEmpty()) { compStart = -1; compEnd = -1 } else { compStart = s; compEnd = s + text.length }
        report()
        return true
    }

    override fun finishComposingText(): Boolean {
        calls += "finishComposingText"
        compStart = -1
        compEnd = -1
        report()
        return true
    }

    override fun sendKey(keyCode: Int) {
        keys += keyCode
        if (keyCode == InputConnectionAdapter.KEYCODE_DEL) {
            if (selStart != selEnd) {
                replace(selStart, selEnd, "")
            } else if (selStart > 0) {
                val it = BreakIterator.getCharacterInstance()
                it.setText(text.toString())
                val p = it.preceding(selStart)
                replace(p, selStart, "")
            }
            compStart = -1
            compEnd = -1
            report()
        }
    }

    override fun performEditorAction(actionId: Int) { actions += actionId }

    override fun getSelectedText(): CharSequence? {
        calls += "getSelectedText"
        return if (selStart != selEnd) text.substring(minOf(selStart, selEnd), maxOf(selStart, selEnd)) else null
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        calls += "setSelection"
        selStart = start
        selEnd = end
        report()
        return true
    }

    /** What `getCursorCapsMode` answers (0: never capitalise; e.g. `TextUtils.CAP_MODE_SENTENCES`). */
    var capsMode = 0

    override fun getCursorCapsMode(reqModes: Int): Int = capsMode and reqModes
}
