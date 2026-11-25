package com.qalsmartkeyboard.ime.voice

import com.qalsmartkeyboard.ime.EditorOps

/**
 * T8.12 (ADR 0049 D3): replaces just-dictated text in the app's field. Through the editor's input connection in one batch
 * edit, like the AI toolbar's Replace: never through the composer or the word learner (nothing is learned). Pure on
 * `getTextBeforeCursor`, so it runs on the JVM. The text is never logged or stored (N2).
 */
object DictatedEdit {
    /** How much whitespace after the dictated text is looked through (and kept). */
    const val MAX_TRAILING = 8

    /**
     * How many trailing characters of [before] (the text before the cursor) are kept after [expected]: [before] must read
     * [expected] followed by nothing but whitespace. Null when it does not (or [expected] is empty).
     */
    fun keptTail(before: CharSequence?, expected: String): Int? {
        if (before == null || expected.isEmpty()) return null
        var trailing = 0
        while (trailing < before.length && before[before.length - 1 - trailing].isWhitespace()) trailing++
        // Longest tail first (it also covers [expected] itself ending in whitespace).
        for (kept in trailing downTo 0) {
            val end = before.length - kept
            if (end < expected.length) continue
            if (before.subSequence(end - expected.length, end).toString() == expected) return kept
        }
        return null
    }

    /** Replaces [expected] before the cursor with [replacement], keeping the whitespace after it; false when it does not read so. */
    fun replace(ed: EditorOps, expected: String, replacement: String): Boolean {
        if (expected.isEmpty()) return false
        if (!ed.getSelectedText().isNullOrEmpty()) return false
        val before = ed.getTextBeforeCursor(expected.length + MAX_TRAILING)
        val kept = keptTail(before, expected) ?: return false
        val tail = before!!.substring(before.length - kept)
        ed.beginBatchEdit()
        try {
            ed.deleteSurroundingText(expected.length + kept)
            ed.commitText(replacement + tail)
        } finally {
            ed.endBatchEdit()
        }
        return true
    }
}

/**
 * The editor reports our own edits back (`onUpdateSelection`, possibly late). For a short [windowMs] after the host
 * edited the field itself (dictation commit, a follow-up's replace), reports are not "the user changed the editor".
 */
class OwnEditWindow(private val clock: () -> Long, private val windowMs: Long = DEFAULT_MS) {
    private var until = Long.MIN_VALUE

    /** The host edited the field now. */
    fun mark() {
        until = clock() + windowMs
    }

    /** Reports arriving now are (probably) echoes of our own edit. */
    fun active(): Boolean = clock() < until

    companion object {
        const val DEFAULT_MS = 1_500L
    }
}
