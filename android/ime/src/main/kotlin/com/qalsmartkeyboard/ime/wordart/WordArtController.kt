package com.qalsmartkeyboard.ime.wordart

import com.qalsmartkeyboard.ime.ComposerUpdate
import com.qalsmartkeyboard.ime.TextSink

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
 */
class WordArtController(
    private val host: WordArtHost,
    private val onChanged: () -> Unit,
) {
    private val buffer = StringBuilder()
    private var composing = ""

    /** The text as typed (the committed text plus the composition in progress). */
    val text: String get() = buffer.toString() + composing

    var category: WordArtCategory = WordArtCategory.POPULAR
        private set

    var greeting: Boolean = false
        private set

    /** The style whose large preview card is open, or null (the list). */
    var selected: WordArtStyle? = null
        private set

    var notice: WordArtNotice = WordArtNotice.NONE
        private set

    private fun language(): String = host.language.takeIf { it in Greetings.LANGUAGES } ?: "en"

    val options: WordArtOptions get() = WordArtOptions(language(), greeting)

    /** True while nothing is typed: the previews then show a sample name (and the greeting alone, when it is on). */
    val showingSample: Boolean get() = text.isBlank()

    /** Opens Word Art: the text starts as the host field's selection (if any), else empty. */
    fun begin() {
        buffer.setLength(0)
        composing = ""
        category = WordArtCategory.POPULAR
        selected = null
        notice = WordArtNotice.NONE
        host.selectedText()?.takeIf { it.isNotBlank() }?.let { buffer.append(it.take(MAX_LENGTH)) }
    }

    /** Leaves Word Art: nothing is kept. */
    fun end() {
        buffer.setLength(0)
        composing = ""
        selected = null
        notice = WordArtNotice.NONE
    }

    /** The composer's output goes here while the text field is being typed into (see `KeyboardController`). */
    val sink: TextSink = object : TextSink {
        override fun apply(update: ComposerUpdate) {
            if (update.deleteBefore > 0) deleteGraphemes(update.deleteBefore)
            if (update.commit.isNotEmpty() && buffer.length + update.commit.length <= MAX_LENGTH) buffer.append(update.commit)
            composing = update.composing
            notice = WordArtNotice.NONE
            onChanged()
        }

        override fun sendEnterKey() {}
        override fun performEditorAction(actionId: Int) {}
        override fun wantsAutoCapital(capsFlags: Int) = false
    }

    private fun deleteGraphemes(n: Int) {
        repeat(n) {
            if (buffer.isEmpty()) return
            val parts = Graphemes.split(buffer.toString())
            buffer.setLength(buffer.length - parts.last().length)
        }
    }

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

    /** Inserts the styled text into the host field (replacing its selection) and closes the card. */
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
