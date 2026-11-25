package com.qalsmartkeyboard.ime.toolbar

import android.content.Context
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.ai.AiMessages
import com.qalsmartkeyboard.ime.editor.EditorStrip
import com.qalsmartkeyboard.ime.sticker.StickerController
import com.qalsmartkeyboard.ime.sticker.StickerField

/**
 * Shown in the bar's place while the keys type into the sticker maker's text or caption (T8.7; like [com.qalsmartkeyboard.ime.ai.AiFieldBar]):
 * the field's name and its count against the limit, the text as an editable field (cursor, selection, Cut / Copy / Paste,
 * dictation) and Done. A fixed height, above the keys, so typing never resizes the keyboard or hides the field.
 */
class StickerFieldBar(context: Context) : EditorStrip(context) {
    var controller: StickerController? = null

    /** Re-reads the field being typed into. Cheap: called on every key while typing. */
    fun render() {
        val c = controller ?: return
        val f = c.typingField ?: return
        val name = context.getString(if (c.typing == StickerField.CAPTION) R.string.sticker_typing_caption else R.string.sticker_typing_text)
        val e = field.editor
        e.buffer = f.buffer
        e.maxLines = if (c.typing == StickerField.TEXT) 4 else 1
        e.hint = context.getString(R.string.ai_typing_hint)
        e.describe = { t -> context.getString(R.string.ai_typing_field, name, t) }
        field.label = context.getString(R.string.ai_typing_label, name, AiMessages.count(f.length, f.limit))
    }
}
