package com.qalsmartkeyboard.ime.toolbar

import android.content.Context
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.ai.AiMessages
import com.qalsmartkeyboard.ime.draw.DrawController
import com.qalsmartkeyboard.ime.editor.EditorStrip

/**
 * Shown in the bar's place while the keys type a text for the drawing (T8.15; like [StickerFieldBar]): the field's name and
 * its count against the limit, the text as an editable field (cursor, selection, Cut / Copy / Paste, dictation) and Done. A
 * fixed height, above the keys, so typing never resizes the keyboard or hides the field.
 */
class DrawFieldBar(context: Context) : EditorStrip(context) {
    var controller: DrawController? = null

    /** Re-reads the field being typed into. Cheap: called on every key while typing. */
    fun render() {
        val c = controller ?: return
        if (!c.typing) return
        val f = c.textField
        val name = context.getString(R.string.draw_typing_text)
        val e = field.editor
        e.buffer = f.buffer
        e.maxLines = 4
        e.hint = context.getString(R.string.ai_typing_hint)
        e.describe = { t -> context.getString(R.string.ai_typing_field, name, t) }
        field.label = context.getString(R.string.ai_typing_label, name, AiMessages.count(f.length, f.limit))
    }
}
