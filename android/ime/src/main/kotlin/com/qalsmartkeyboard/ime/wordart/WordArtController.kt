package com.qalsmartkeyboard.ime.wordart

import com.qalsmartkeyboard.ime.TextSink
import com.qalsmartkeyboard.ime.editor.EditorBuffer

/** What the Word Art state needs from the keyboard. */
interface WordArtHost {
    /** Commits [text] to the host field; the editor replaces its selection, if any (an `InputConnection.commitText`). */
    fun commitToField(text: String)

    /** The text selected in the host field, or null. */
    fun selectedText(): String?

    /** Puts [text] on the clipboard (a plain-text clip, not marked sensitive); false when the system refused. */
    fun copyToClipboard(text: String): Boolean

    /** The keyboard language (am, ti, om, en, ...), for the greeting phrase and the placeholder. */
    val language: String
}

/** The short confirmation the card shows after an action. */
enum class WordArtNotice { NONE, COPIED, COPY_FAILED, INSERTED, NOTHING_TO_USE }

/**
 * The Word Art panel's state and logic; no View code. The user's text lives here, not in the host field: while the text
 * field is being edited the keyboard controller routes the composer's output to [sink] (the same mechanism as the emoji
 * search), so every layout (Fidel, phonetic, Qubee, English) can type it, and nothing typed here is learned, logged or stored.
 * The text is an [EditorBuffer] (ADR 0038): the keys write at its cursor, and the field can be tapped, selected and edited
 * in the middle.
 *
 * A tap on a style ([insert]) commits the complete styled Unicode text into the host field through its InputConnection
 * (the field's selection is replaced, like any commit) and the panel stays open with a short "Inserted", as Gboard does
 * for emoji; Word Art only opens where emoji are allowed (plain text fields, never passwords).
 */
class WordArtController(
    private val host: WordArtHost,
    private val onChanged: () -> Unit,
) {
    /** The text, its cursor and selection (the keys write at the cursor). */
    val buffer = EditorBuffer(MAX_LENGTH).also {
        it.onTextChanged = {
            notice = WordArtNotice.NONE
            onChanged()
        }
    }

    /** The text as typed (the committed text plus the composition in progress). */
    val text: String get() = buffer.text

    var category: WordArtCategory = WordArtCategory.POPULAR
        private set

    var greeting: Boolean = false
        private set

    /** The style whose large preview card is open, or null (the list). */
    var selected: WordArtStyle? = null
        private set

    var notice: WordArtNotice = WordArtNotice.NONE
        private set(value) {
            field = value
            if (value != WordArtNotice.NONE) noticeSerial++
        }

    /** Counts the notices given, so the panel shows (and announces) each one once. */
    var noticeSerial: Int = 0
        private set

    private fun language(): String = host.language.takeIf { it in Greetings.LANGUAGES } ?: "en"

    val options: WordArtOptions get() = WordArtOptions(language(), greeting)

    /** True while nothing is typed: the previews then show a sample name (and the greeting alone, when it is on). */
    val showingSample: Boolean get() = text.isBlank()

    /** Opens Word Art: the text starts as the host field's selection (if any), else empty. */
    fun begin() {
        category = WordArtCategory.POPULAR
        selected = null
        buffer.setText(host.selectedText()?.takeIf { it.isNotBlank() }.orEmpty())
        notice = WordArtNotice.NONE
    }

    /** Leaves Word Art: nothing is kept. */
    fun end() {
        selected = null
        buffer.clear()
        notice = WordArtNotice.NONE
    }

    /** The composer's output goes here while the text field is being typed into (see `KeyboardController`): at the cursor. */
    val sink: TextSink get() = buffer.sink

    fun selectCategory(c: WordArtCategory) {
        if (c == category) return
        category = c
        notice = WordArtNotice.NONE
        onChanged()
    }

    fun toggleGreeting() {
        greeting = !greeting
        notice = WordArtNotice.NONE
        onChanged()
    }

    /** The styles of the current category. */
    fun styles(): List<WordArtStyle> = WordArt.stylesIn(category)

    fun render(style: WordArtStyle): Rendered {
        val typed = text.trim()
        val use = if (WordArt.body(style, typed, options).isBlank()) WordArt.sample(language()) else typed
        return WordArt.render(style, use, options)
    }

    /** Opens the large preview card of [style] (false: the style cannot serve this text). */
    fun select(style: WordArtStyle?): Boolean {
        if (style != null && render(style).support == Support.NONE) return false
        selected = style
        notice = WordArtNotice.NONE
        onChanged()
        return true
    }

    /** The text to insert or copy for [style]: null when there is nothing real to use (no text typed, no greeting). */
    private fun output(style: WordArtStyle): String? {
        if (WordArt.body(style, text, options).isBlank()) return null
        val r = WordArt.render(style, text, options)
        return if (r.support == Support.NONE) null else r.text
    }

    /**
     * Inserts the complete styled text of [style] into the host field (replacing its selection): a tap on a style in the
     * list, or Insert on the large card (which closes). Combining marks and clusters are kept as rendered.
     */
    fun insert(style: WordArtStyle): Boolean {
        val out = output(style)
        if (out == null) {
            notice = WordArtNotice.NOTHING_TO_USE
        } else {
            host.commitToField(out)
            notice = WordArtNotice.INSERTED
            selected = null
        }
        onChanged()
        return out != null
    }

    /** Copies the styled text to the clipboard. */
    fun copy(style: WordArtStyle): Boolean {
        val out = output(style)
        notice = when {
            out == null -> WordArtNotice.NOTHING_TO_USE
            host.copyToClipboard(out) -> WordArtNotice.COPIED
            else -> WordArtNotice.COPY_FAILED
        }
        onChanged()
        return notice == WordArtNotice.COPIED
    }

    private companion object {
        const val MAX_LENGTH = 500
    }
}
