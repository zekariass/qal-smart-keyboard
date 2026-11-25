package com.qalsmartkeyboard.ime.ai

import android.content.Context
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.editor.EditorStrip

/**
 * Shown in the bar's place while the keys type into an AI panel field ("Type it", ADR 0031 §4; like Word Art's strip):
 * which field and its count against the limit, the text as an editable field (ADR 0038: tap anywhere to move the cursor,
 * long press or double tap to select, Cut/Copy/Paste/Select all, the keys write at the cursor, the microphone dictates
 * at the cursor) and Done. Done, Enter on a one-line field, the emoji key and Back return to the panel. A fixed height
 * ([EditorStrip.HEIGHT_DP]), so typing never resizes the keyboard, and it sits above the keys, so it is never hidden by
 * them.
 */
class AiFieldBar(context: Context) : EditorStrip(context) {
    var workspace: AiWorkspace? = null

    /** Re-reads the field being typed into. Cheap: called on every key while typing (the editor redraws by itself). */
    fun render() {
        val w = workspace ?: return
        val f = w.typingField ?: return
        val name = fieldLabel(w)
        val e = field.editor
        e.buffer = f.buffer
        e.maxLines = if (w.typingMultiline) 4 else 1
        e.hint = context.getString(R.string.ai_typing_hint)
        e.describe = { t -> context.getString(R.string.ai_typing_field, name, t) }
        field.label = context.getString(R.string.ai_typing_label, name, AiMessages.count(f.length, f.limit))
    }

    private fun fieldLabel(w: AiWorkspace): String = context.getString(
        when (w.screen) {
            AiScreen.ASSIST -> when (w.assist.typing) {
                AiAssistField.NOTE -> R.string.ai_reply_note_label
                else -> R.string.ai_source_typed
            }
            AiScreen.EMAIL -> when (w.email.typing) {
                AiEmailField.INSTRUCTIONS -> R.string.ai_email_instructions_new
                AiEmailField.ORIGINAL -> R.string.ai_email_original_label
                AiEmailField.DRAFT -> R.string.ai_email_draft_label
                AiEmailField.RECIPIENT -> R.string.ai_email_recipient
                AiEmailField.SENDER -> R.string.ai_email_sender
                AiEmailField.SUBJECT -> R.string.ai_email_subject
                AiEmailField.BODY, null -> R.string.ai_email_body
            }
            AiScreen.CHAT -> R.string.chat_message_label
            AiScreen.CLOSED -> R.string.ai_source_typed
        },
    )

    internal val textView: com.qalsmartkeyboard.ime.editor.EditorView get() = this.field.editor
}
