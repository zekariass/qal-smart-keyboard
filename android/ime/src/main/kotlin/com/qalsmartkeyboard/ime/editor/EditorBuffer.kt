package com.qalsmartkeyboard.ime.editor

import com.qalsmartkeyboard.ime.ComposerUpdate
import com.qalsmartkeyboard.ime.TextSink
import com.qalsmartkeyboard.ime.wordart.Graphemes

/**
 * The text model of the keyboard's own text fields (ADR 0038): the translate box, the AI panels' fields and Word Art's
 * text. An input method cannot type into its own Views through an InputConnection, so the keys write here through the
 * keyboard's redirect ([sink], `KeyboardController.fieldTyping` / `EmojiController.redirect`), and [EditorView] draws it.
 *
 * Pure Kotlin (JVM-testable). Positions are logical UTF-16 offsets into [text], independent of how bidi text is drawn
 * (a right-to-left Arabic word keeps its logical order here; [EditorView] maps taps and the caret through the layout).
 * Rules:
 * - **Selection:** [selectionStart] ≤ [selectionEnd]; collapsed = the cursor. Every position the user can reach lies on a
 *   grapheme-cluster boundary ([Graphemes]): an Ethiopic letter with its gemination mark, an Arabic letter with its
 *   harakat, an emoji sequence are never split by a tap, a cursor move or Backspace.
 * - **Keys at the cursor:** a [ComposerUpdate] (the same contract the host field gets through `InputConnectionAdapter`)
 *   deletes [ComposerUpdate.deleteBefore] clusters before the cursor (a selection counts as one: it is deleted), commits
 *   at the cursor (replacing the selection or the composition), then shows the composition at the cursor.
 * - **Composition:** part of [text] (like Android's composing span), between [composingStart] and [composingEnd].
 *   Moving the cursor keeps it as plain text ([finishComposing]); the owner commits the composer first.
 * - **Capacity:** at most [capacity] UTF-16 units; longer input is cut (never inside a surrogate pair).
 *
 * Holds user text in memory only; nothing here logs or stores it (N2). [toString] prints no content.
 */
class EditorBuffer(val capacity: Int = DEFAULT_CAPACITY) {
    /** A change the views redraw for: [textChanged] false = only the selection moved. */
    fun interface Observer {
        fun onEditorChanged(textChanged: Boolean)
    }

    private val sb = StringBuilder()
    private var cached: String? = ""

    /** The text, including the composition in progress. */
    val text: String get() = cached ?: sb.toString().also { cached = it }

    val length: Int get() = sb.length

    var selectionStart: Int = 0
        private set
    var selectionEnd: Int = 0
        private set

    /** The composition's range in [text], or -1 when none. */
    var composingStart: Int = -1
        private set
    var composingEnd: Int = -1
        private set

    /** Bumped on every change of [text] (views cache their layout on it). */
    var version: Int = 0
        private set

    /** The owner's callback: [text] changed (typing, paste, cut, a new text). */
    var onTextChanged: () -> Unit = {}

    private val observers = ArrayList<Observer>(2)

    fun addObserver(o: Observer) {
        if (o !in observers) observers += o
    }

    fun removeObserver(o: Observer) {
        observers -= o
    }

    val cursor: Int get() = selectionEnd
    val hasSelection: Boolean get() = selectionStart != selectionEnd
    val isComposing: Boolean get() = composingStart >= 0
    val composing: String get() = if (isComposing) text.substring(composingStart, composingEnd) else ""
    val isEmpty: Boolean get() = sb.isEmpty()
    val selectedText: String get() = if (hasSelection) text.substring(selectionStart, selectionEnd) else ""

    /**
     * Up to [n] UTF-16 units before the cursor, or before the selection that an insertion replaces (voice typing's
     * spacing; the composition counts as text).
     */
    fun textBeforeCursor(n: Int): String = text.substring(maxOf(0, selectionStart - n), selectionStart)

    // ------------------------------------------------------------------ whole text

    /** Replaces everything (a prefill, a paste into an empty field, a result to edit): the cursor goes to the end. */
    fun setText(value: String) {
        val v = fit(value, capacity)
        val changed = v != text
        sb.setLength(0)
        sb.append(v)
        cached = null
        if (changed) version++
        composingStart = -1
        composingEnd = -1
        selectionStart = sb.length
        selectionEnd = sb.length
        notify(changed)
    }

    fun clear() = setText("")

    // ------------------------------------------------------------------ the keys (the composer's output)

    /** The keyboard's redirect writes here: what the host field would get through its InputConnection. */
    val sink: TextSink = object : TextSink {
        override fun apply(update: ComposerUpdate) = this@EditorBuffer.apply(update)
        override fun sendEnterKey() {}
        override fun performEditorAction(actionId: Int) {}
        override fun wantsAutoCapital(capsFlags: Int) = false
    }

    /** Applies one composer update at the cursor (see the class rules). */
    fun apply(u: ComposerUpdate) {
        if (u.deleteBefore <= 0 && u.commit.isEmpty() && u.composing == composing) return
        val before = version
        if (u.deleteBefore > 0) {
            if (isComposing) replace(composingStart, composingEnd, "")
            var n = u.deleteBefore
            if (hasSelection) {
                replace(selectionStart, selectionEnd, "")
                n--
            }
            repeat(n) {
                val c = cursor
                if (c > 0) replace(previousBoundary(c), c, "")
            }
        }
        if (u.commit.isNotEmpty()) {
            val (s, e) = activeRange()
            replace(s, e, u.commit)
        }
        if (u.composing.isNotEmpty()) {
            val (s, e) = activeRange()
            val end = replace(s, e, u.composing)
            composingStart = s
            composingEnd = end
        } else if (isComposing) {
            replace(composingStart, composingEnd, "")
        }
        notify(version != before)
    }

    /** The range a commit or a composition replaces: the composition, else the selection (the cursor when collapsed). */
    private fun activeRange(): Pair<Int, Int> = if (isComposing) composingStart to composingEnd else selectionStart to selectionEnd

    // ------------------------------------------------------------------ user edits (taps, the action row, voice)

    /** Inserts [value] at the cursor, replacing the selection (paste, dictated text). */
    fun insert(value: String) {
        if (value.isEmpty() && !hasSelection) return
        finishComposingQuietly()
        val before = version
        replace(selectionStart, selectionEnd, value)
        notify(version != before)
    }

    /** Deletes the selection; returns what was deleted (Cut). */
    fun deleteSelection(): String {
        if (!hasSelection) return ""
        finishComposingQuietly()
        val gone = selectedText
        replace(selectionStart, selectionEnd, "")
        notify(true)
        return gone
    }

    /** Backspace outside the composer: [n] clusters before the cursor, or the selection. */
    fun deleteBackward(n: Int = 1) = apply(ComposerUpdate(deleteBefore = n))

    /** The composition stays as plain text (the cursor moved away from it). */
    fun finishComposing() {
        if (!isComposing) return
        finishComposingQuietly()
        notify(false)
    }

    private fun finishComposingQuietly() {
        composingStart = -1
        composingEnd = -1
    }

    // ------------------------------------------------------------------ selection

    /**
     * Moves the cursor ([start] == [end]) or selects (in either order). Clamped to the text and snapped to cluster
     * boundaries; an open composition is kept as plain text.
     */
    fun setSelection(start: Int, end: Int = start) {
        val a = snap(start.coerceIn(0, length))
        val b = snap(end.coerceIn(0, length))
        val s = minOf(a, b)
        val e = maxOf(a, b)
        val composed = isComposing
        if (s == selectionStart && e == selectionEnd && !composed) return
        finishComposingQuietly()
        selectionStart = s
        selectionEnd = e
        notify(false)
    }

    /** Moves the cursor [clusters] grapheme clusters forward (positive) or back (logical order). Collapses a selection. */
    fun moveCursor(clusters: Int) {
        if (hasSelection) {
            // The first move collapses the selection to its side, like a text field's arrow keys.
            setSelection(if (clusters < 0) selectionStart else selectionEnd)
            return
        }
        var c = cursor
        repeat(kotlin.math.abs(clusters)) { c = if (clusters < 0) previousBoundary(c) else nextBoundary(c) }
        setSelection(c)
    }

    fun selectAll() = setSelection(0, length)

    /** Selects the word at or just before [offset] (long press, double tap). False when there is none (a space, empty). */
    fun selectWordAt(offset: Int): Boolean {
        val r = wordRange(text, offset.coerceIn(0, length)) ?: return false
        setSelection(r.first, r.last)
        return true
    }

    // ------------------------------------------------------------------ internals

    /** Replaces [s, e) with [value] (cut to the capacity); the cursor goes after it, the composition is cleared. */
    private fun replace(s: Int, e: Int, value: String): Int {
        val v = fit(value, capacity - (sb.length - (e - s)))
        if (s != e || v.isNotEmpty()) {
            sb.replace(s, e, v)
            cached = null
            version++
        }
        composingStart = -1
        composingEnd = -1
        selectionStart = s + v.length
        selectionEnd = s + v.length
        return s + v.length
    }

    private fun notify(textChanged: Boolean) {
        if (textChanged) onTextChanged()
        for (o in observers.toTypedArray()) o.onEditorChanged(textChanged)
    }

    fun previousBoundary(offset: Int): Int = previousBoundary(text, offset)

    fun nextBoundary(offset: Int): Int = nextBoundary(text, offset)

    fun snap(offset: Int): Int = snap(text, offset)

    override fun toString() = "EditorBuffer(length=$length, selection=$selectionStart..$selectionEnd, composing=$isComposing)"

    companion object {
        const val DEFAULT_CAPACITY = 20_000

        private fun fit(value: String, room: Int): String {
            if (room <= 0) return ""
            if (value.length <= room) return value
            var cut = room
            if (cut > 0 && Character.isHighSurrogate(value[cut - 1])) cut--
            return value.substring(0, cut)
        }

        /** The start of the line holding [offset]: a line break is always a cluster boundary, so scanning starts there. */
        private fun lineStart(text: CharSequence, offset: Int): Int {
            var i = offset - 1
            while (i >= 0 && text[i] != '\n') i--
            return i + 1
        }

        /** The largest cluster boundary ≤ [offset]. */
        fun snap(text: CharSequence, offset: Int): Int {
            if (offset <= 0) return 0
            if (offset >= text.length) return text.length
            var b = lineStart(text, offset)
            while (true) {
                val e = Graphemes.clusterEnd(text, b)
                if (e > offset) return b
                b = e
            }
        }

        /** The cluster boundary before [offset] (the start of the cluster that ends at or contains it). */
        fun previousBoundary(text: CharSequence, offset: Int): Int {
            if (offset <= 0) return 0
            val o = minOf(offset, text.length)
            val ls = lineStart(text, o)
            if (ls == o) return if (o >= 2 && text[o - 2] == '\r' && text[o - 1] == '\n') o - 2 else o - 1
            var b = ls
            while (true) {
                val e = Graphemes.clusterEnd(text, b)
                if (e >= o) return b
                b = e
            }
        }

        /** The cluster boundary after [offset]. */
        fun nextBoundary(text: CharSequence, offset: Int): Int {
            if (offset >= text.length) return text.length
            return Graphemes.clusterEnd(text, snap(text, maxOf(0, offset)))
        }

        private fun isWordChar(cp: Int): Boolean = Character.isLetterOrDigit(cp) || when (Character.getType(cp).toByte()) {
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK, Character.CONNECTOR_PUNCTUATION -> true
            else -> cp == 0x200C || cp == 0x200D // ZWNJ/ZWJ inside Arabic and Indic words
        }

        /** An apostrophe inside a word (Afaan Oromoo's hudhaa: "ba'aa"; English "don't") belongs to the word. */
        private fun isApostrophe(c: Char) = c == '\'' || c == '’' || c == 'ʼ'

        /** The word at or just before [offset] as `start..end` (end exclusive), or null. */
        fun wordRange(text: String, offset: Int): IntRange? {
            if (text.isEmpty()) return null
            fun wordAt(i: Int): Boolean = i in text.indices && isWordChar(text.codePointAt(i))
            val p = when {
                wordAt(offset) -> offset
                offset > 0 && wordAt(offset - 1) -> offset - 1
                offset > 1 && Character.isLowSurrogate(text[offset - 1]) && wordAt(offset - 2) -> offset - 2
                else -> return null
            }
            var s = p
            while (s > 0) {
                val cp = text.codePointBefore(s)
                val w = Character.charCount(cp)
                if (isWordChar(cp)) s -= w
                else if (isApostrophe(text[s - 1]) && s - 1 > 0 && isWordChar(text.codePointBefore(s - 1))) s -= 1
                else break
            }
            var e = p
            while (e < text.length) {
                val cp = text.codePointAt(e)
                if (isWordChar(cp)) e += Character.charCount(cp)
                else if (isApostrophe(text[e]) && e + 1 < text.length && isWordChar(text.codePointAt(e + 1))) e += 1
                else break
            }
            return snap(text, s)..nextBoundaryOrSelf(text, e)
        }

        private fun nextBoundaryOrSelf(text: String, e: Int): Int {
            val s = snap(text, e)
            return if (s == e) e else Graphemes.clusterEnd(text, s)
        }
    }
}
