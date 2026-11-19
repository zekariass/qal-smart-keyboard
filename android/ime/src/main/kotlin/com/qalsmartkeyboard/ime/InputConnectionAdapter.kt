package com.qalsmartkeyboard.ime

import java.text.BreakIterator

/**
 * The InputConnection operations the keyboard uses. The service implements it over
 * `currentInputConnection`; tests use a fake editor. Mirrors the InputConnection methods.
 */
interface EditorOps {
    fun beginBatchEdit()
    fun endBatchEdit()

    /** Up to [n] UTF-16 units before the cursor; null when the editor does not answer. */
    fun getTextBeforeCursor(n: Int): CharSequence?
    fun deleteSurroundingText(beforeLength: Int): Boolean
    fun commitText(text: String): Boolean
    fun setComposingText(text: String): Boolean
    fun finishComposingText(): Boolean

    /** Sends a key down/up pair (`KeyEvent.KEYCODE_*`). */
    fun sendKey(keyCode: Int)
    fun performEditorAction(actionId: Int)

    /** `InputConnection.getCursorCapsMode`. */
    fun getCursorCapsMode(reqModes: Int): Int

    /** The selected text, or null when there is none or the editor does not answer (AI toolbar, A2.5). */
    fun getSelectedText(): CharSequence? = null

    /** `InputConnection.setSelection` (absolute offsets); false when not supported. */
    fun setSelection(start: Int, end: Int): Boolean = false
}

/** Editor-side operations the [KeyboardController] needs. */
interface TextSink {
    /** Applies a composer update to the editor. */
    fun apply(update: ComposerUpdate)
    fun sendEnterKey()
    fun performEditorAction(actionId: Int)

    /** True when the host wants the next typed letter capitalised (cursor caps mode). */
    fun wantsAutoCapital(capsFlags: Int): Boolean

    /** The text selected in the host field (Word Art starts from it), or null when none or unknown. */
    fun selectedText(): String? = null
}

/** How composing text reaches the editor in the current editor session. */
enum class CompositionMode {
    /** `setComposingText` / `commitText` (the normal path). */
    COMPOSING,

    /** Fallback: the composition is committed as plain text and replaced (delete + recommit). */
    DIRECT_COMMIT,
}

/** Why an editor session uses [CompositionMode.DIRECT_COMMIT]. */
enum class FallbackReason { TYPE_NULL, SET_COMPOSING_REJECTED, NO_COMPOSING_SPAN }

/**
 * Applies [ComposerUpdate]s through the InputConnection and handles host quirks
 * (SD 6.2 `InputConnectionAdapter`, ADR 0004).
 *
 * Every update is one batch edit: delete grapheme clusters (converted to UTF-16 with
 * [BreakIterator] over `getTextBeforeCursor`), commit, then set or clear the composing text.
 *
 * Apps that reject composing text are detected (ADR 0004) and the session switches to
 * [CompositionMode.DIRECT_COMMIT]:
 * 1. `inputType == TYPE_NULL` (no text model: terminals, custom views) from the start;
 * 2. `setComposingText` returns false;
 * 3. the editor reports the cursor exactly where one of our edits that left composing text put
 *    it, but with no composing span (`candidatesStart == -1`), and no other pending edit of ours
 *    had that cursor position (so it cannot be a late report of a commit).
 *
 * Own edits are told apart from user cursor moves by the cursor position each edit should
 * produce, matched in order against `onUpdateSelection` reports (which can arrive late, after
 * further edits). Any other report is a cursor or selection change not caused by us: the
 * composing text is left in the editor as plain text and [onCompositionAbandoned] tells the
 * keyboard to reset its composer, so text is neither lost nor committed twice. After edits whose
 * effect cannot be predicted (key events, editor actions) the next report consistent with the
 * current composition re-synchronises the position; no decision is taken until then.
 *
 * Never logs or stores typed text (N2); [shown] lives only in memory for the current session.
 */
class InputConnectionAdapter(private val editor: () -> EditorOps?) : TextSink {
    /** Called when the composition was given up because of a change we did not make. */
    var onCompositionAbandoned: () -> Unit = {}

    var mode: CompositionMode = CompositionMode.COMPOSING
        private set
    var fallbackReason: FallbackReason? = null
        private set

    private var typeNull = false

    /** The composition currently in the editor: a composing span, or plain text in DIRECT_COMMIT. */
    private var shown = ""
    private var selStart = -1
    private var selEnd = -1

    private data class Expect(val cursor: Int, val composing: Boolean)

    /** Cursor after our latest edit; null when unknown. */
    private var cursor: Int? = null
    private var last: Expect? = null
    private val pending = ArrayDeque<Expect>()

    /** Starts an editor session (`onStartInput`). Selection values are `EditorInfo.initialSel*`. */
    fun startSession(isTypeNull: Boolean, initialSelStart: Int, initialSelEnd: Int) {
        typeNull = isTypeNull
        mode = if (isTypeNull) CompositionMode.DIRECT_COMMIT else CompositionMode.COMPOSING
        fallbackReason = if (isTypeNull) FallbackReason.TYPE_NULL else null
        shown = ""
        selStart = initialSelStart
        selEnd = initialSelEnd
        pending.clear()
        resync(if (initialSelStart >= 0 && initialSelStart == initialSelEnd) initialSelEnd else null)
    }

    /**
     * Leaves any composition in the editor as plain text (`onFinishInput`, keyboard hidden, mode
     * switch). The caller resets its composer.
     */
    fun finishComposition() {
        val span = shown.isNotEmpty() && mode == CompositionMode.COMPOSING
        shown = ""
        last = last?.copy(composing = false)
        if (span) finishSpan()
    }

    override fun apply(update: ComposerUpdate) {
        if (update.deleteBefore <= 0 && update.commit.isEmpty() && update.composing == shown) return
        val ed = editor() ?: return
        ed.beginBatchEdit()
        try {
            if (mode == CompositionMode.COMPOSING) applyComposing(ed, update) else applyDirect(ed, update)
        } finally {
            ed.endBatchEdit()
        }
        expect(shown.isNotEmpty() && mode == CompositionMode.COMPOSING)
    }

    override fun sendEnterKey() {
        editor()?.sendKey(KEYCODE_ENTER)
        unpredictable()
    }

    override fun performEditorAction(actionId: Int) {
        editor()?.performEditorAction(actionId)
        unpredictable()
    }

    override fun wantsAutoCapital(capsFlags: Int): Boolean =
        (editor()?.getCursorCapsMode(capsFlags) ?: 0) != 0

    override fun selectedText(): String? = editor()?.getSelectedText()?.toString()?.takeIf { it.isNotEmpty() }

    /** The editor, for edits the user asked for explicitly (the AI toolbar's Replace/Insert). */
    fun editorOps(): EditorOps? = editor()

    /** The selection end the editor last reported, or -1 when unknown. */
    val selectionEnd: Int get() = selEnd

    /**
     * An edit was made outside the composer (the AI toolbar replaced text): forget the expected
     * cursor so the next report re-synchronises instead of looking like a user cursor move.
     */
    fun editedExternally() = unpredictable()

    /** `InputMethodService.onUpdateSelection`. */
    fun onSelectionUpdate(newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        selStart = newSelStart
        selEnd = newSelEnd
        val collapsed = newSelStart == newSelEnd
        if (cursor == null) {
            // Waiting to re-synchronise: accept the first report that fits the current state.
            val fits = if (shown.isNotEmpty() && mode == CompositionMode.COMPOSING) {
                collapsed && candidatesStart >= 0 && newSelEnd == candidatesEnd &&
                    candidatesEnd - candidatesStart == shown.length
            } else {
                collapsed && (mode == CompositionMode.DIRECT_COMMIT || candidatesStart < 0)
            }
            if (fits) resync(newSelEnd)
            return
        }
        val i = if (collapsed) pending.indexOfFirst { it.cursor == newSelEnd } else -1
        val matched: Expect? = when {
            i >= 0 -> pending[i].also { repeat(i + 1) { pending.removeFirst() } }
            // The editor re-reported the current state (layout, focus): still ours.
            pending.isEmpty() && collapsed && last?.cursor == newSelEnd -> last
            else -> null
        }
        if (matched == null) {
            external(newSelStart, newSelEnd)
            return
        }
        val ambiguous = pending.any { it.cursor == newSelEnd }
        if (mode == CompositionMode.COMPOSING && matched.composing && candidatesStart < 0 && !ambiguous) {
            composingRejected(FallbackReason.NO_COMPOSING_SPAN, inSync = pending.isEmpty())
        }
    }

    // ------------------------------------------------------------------ normal composing path

    private fun applyComposing(ed: EditorOps, u: ComposerUpdate) {
        var c = cursor
        if (u.deleteBefore > 0) {
            if (shown.isNotEmpty()) { // the core never deletes inside a composition; be safe
                ed.setComposingText("")
                c = c?.minus(shown.length)
                shown = ""
            }
            c = deleteGraphemes(ed, u.deleteBefore, c)
        }
        val regionStart = c?.minus(shown.length)
        if (u.commit.isNotEmpty()) {
            ed.commitText(u.commit) // replaces the composing region, if any
            c = regionStart?.plus(u.commit.length)
            shown = ""
        }
        if (u.composing.isNotEmpty()) {
            val start = if (shown.isNotEmpty()) regionStart else c
            if (ed.setComposingText(u.composing)) {
                shown = u.composing
                c = start?.plus(u.composing.length)
            } else {
                // Rejected: fall back for this editor session and show the text directly.
                mode = CompositionMode.DIRECT_COMMIT
                fallbackReason = FallbackReason.SET_COMPOSING_REJECTED
                if (shown.isNotEmpty()) ed.finishComposingText()
                cursor = c
                applyDirect(ed, ComposerUpdate(composing = u.composing))
                return
            }
        } else if (shown.isNotEmpty()) {
            ed.setComposingText("") // the composition was deleted (Backspace)
            c = regionStart
            shown = ""
        }
        cursor = c
    }

    // ------------------------------------------------------------------ direct-commit fallback

    private fun applyDirect(ed: EditorOps, u: ComposerUpdate) {
        var c = cursor
        if (u.deleteBefore > 0) {
            c = deleteOwn(ed, shown.length, c)
            shown = ""
            c = deleteGraphemes(ed, u.deleteBefore, c)
            val text = u.commit + u.composing
            if (text.isNotEmpty()) ed.commitText(text)
            c = c?.plus(text.length)
        } else {
            // Replace only what changed: the shown composition becomes commit + new composition.
            val target = u.commit + u.composing
            var p = shown.commonPrefixWith(target).length
            if (p > 0 && Character.isHighSurrogate(target[p - 1])) p--
            c = deleteOwn(ed, shown.length - p, c)
            val add = target.substring(p)
            if (add.isNotEmpty()) ed.commitText(add)
            c = c?.plus(add.length)
        }
        shown = u.composing
        cursor = c
    }

    /** Deletes the last [utf16] units of our own direct-commit text. */
    private fun deleteOwn(ed: EditorOps, utf16: Int, c: Int?): Int? {
        if (utf16 <= 0) return c
        if (typeNull) {
            // No text model: only key events reach the app (one DEL per code point).
            val own = shown.substring(shown.length - utf16)
            repeat(own.codePointCount(0, own.length)) { ed.sendKey(KEYCODE_DEL) }
            return null
        }
        ed.deleteSurroundingText(utf16)
        return c?.minus(utf16)
    }

    /**
     * Deletes [n] grapheme clusters before the cursor. A selection is deleted with a DEL key
     * (what the user expects from Backspace); editors that do not answer text queries get DEL
     * key events (they handle Backspace themselves, e.g. chips in recipient fields).
     */
    private fun deleteGraphemes(ed: EditorOps, n: Int, c: Int?): Int? {
        if (selStart >= 0 && selStart != selEnd && shown.isEmpty()) {
            ed.sendKey(KEYCODE_DEL)
            if (n > 1) return deleteGraphemesFromText(ed, n - 1, null, afterKey = true)
            return null
        }
        return deleteGraphemesFromText(ed, n, c, afterKey = false)
    }

    private fun deleteGraphemesFromText(ed: EditorOps, n: Int, c: Int?, afterKey: Boolean): Int? {
        val before = if (typeNull) null else ed.getTextBeforeCursor(n * CHARS_PER_CLUSTER + CHARS_PER_CLUSTER)
        if (before.isNullOrEmpty()) {
            repeat(n) { ed.sendKey(KEYCODE_DEL) }
            return null
        }
        val len = utf16LengthOfLastGraphemes(before, n)
        if (len > 0) ed.deleteSurroundingText(len)
        return if (afterKey) null else c?.minus(len)
    }

    // ------------------------------------------------------------------ tracking own edits

    private fun expect(composing: Boolean) {
        val c = cursor ?: return
        val e = Expect(c, composing)
        // An edit that moves neither the cursor nor the span produces no report: don't wait for one.
        val unchanged = if (pending.isEmpty()) last == e else pending.last() == e
        last = e
        if (unchanged) return
        pending.addLast(e)
        while (pending.size > MAX_PENDING) pending.removeFirst()
    }

    private fun unpredictable() {
        cursor = null
        pending.clear()
    }

    private fun resync(position: Int?) {
        cursor = position
        pending.clear()
        last = position?.let { Expect(it, shown.isNotEmpty() && mode == CompositionMode.COMPOSING) }
    }

    /** A cursor or selection change we did not make: keep the text, drop the composition. */
    private fun external(newSelStart: Int, newSelEnd: Int) {
        val span = shown.isNotEmpty() && mode == CompositionMode.COMPOSING
        shown = ""
        resync(if (newSelStart == newSelEnd) newSelEnd else null)
        if (span) finishSpan()
        onCompositionAbandoned()
    }

    /**
     * `finishComposingText` on the editor. Its report (span gone, cursor unchanged) may arrive
     * after the next key, so it is expected like any own edit; otherwise it would look like a
     * cursor move and abandon the new composition.
     */
    private fun finishSpan() {
        editor()?.finishComposingText()
        val c = cursor ?: return
        val e = Expect(c, false)
        last = e
        pending.addLast(e)
    }

    private fun composingRejected(reason: FallbackReason, inSync: Boolean) {
        mode = CompositionMode.DIRECT_COMMIT
        fallbackReason = reason
        // Whatever the editor shows becomes plain text. When the report is for our latest edit the
        // editor holds exactly the composition, which continues as direct-commit text; otherwise
        // later edits may already have changed it, so start over from the editor's content.
        if (inSync) {
            last = last?.copy(composing = false)
            finishSpan()
        } else {
            editor()?.finishComposingText()
            shown = ""
            unpredictable()
            onCompositionAbandoned()
        }
    }

    companion object {
        /** `KeyEvent.KEYCODE_DEL` / `KEYCODE_ENTER`. */
        const val KEYCODE_DEL = 67
        const val KEYCODE_ENTER = 66

        /** UTF-16 units read per cluster to delete (long emoji ZWJ sequences fit). */
        private const val CHARS_PER_CLUSTER = 32
        private const val MAX_PENDING = 64

        /**
         * UTF-16 length of the last [n] extended grapheme clusters of [text] (all of it if it has
         * fewer). One Fidel syllable, an emoji ZWJ sequence or a flag is one cluster.
         */
        fun utf16LengthOfLastGraphemes(text: CharSequence, n: Int): Int {
            if (n <= 0 || text.isEmpty()) return 0
            val s = text.toString()
            val it = BreakIterator.getCharacterInstance()
            it.setText(s)
            var pos = s.length
            var count = 0
            while (count < n) {
                val prev = it.preceding(pos)
                if (prev == BreakIterator.DONE) {
                    pos = 0
                    break
                }
                pos = prev
                count++
            }
            return s.length - pos
        }
    }
}
