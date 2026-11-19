package com.qalsmartkeyboard.ime.wordart

import android.content.Context
import com.qalsmartkeyboard.ime.KeyboardController
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.editor.EditorStrip

/**
 * The strip shown in the bar's place while the Word Art text field is being typed into (ADR 0030, like the emoji search
 * strip): the keys are back, and this shows the text as an editable field (ADR 0038: tap to move the cursor, long press
 * to select, cut/copy/paste, the keys write at the cursor), a microphone that dictates into it, and Done. Done, the
 * Enter key, the emoji key and system Back return to the Word Art panel.
 */
class WordArtBar(context: Context) : EditorStrip(context) {
    var controller: KeyboardController? = null

    init {
        onDone = { controller?.emoji?.endWordArtTyping() }
        doneView.contentDescription = context.getString(R.string.wordart_typing_done)
    }

    /** Re-reads the text from the Word Art state. */
    fun render() {
        val w = controller?.emoji?.wordArt ?: return
        val e = field.editor
        e.buffer = w.buffer
        e.hint = context.getString(R.string.wordart_typing_hint)
        e.describe = { t -> context.getString(R.string.wordart_typing_field, t) }
        field.label = context.getString(R.string.wordart_typing_label)
        e.contentDescription = context.getString(R.string.wordart_typing_field, w.text)
    }

    internal val fieldView: com.qalsmartkeyboard.ime.editor.EditorView get() = this.field.editor
}
