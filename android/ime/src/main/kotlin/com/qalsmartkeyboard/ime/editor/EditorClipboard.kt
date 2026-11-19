package com.qalsmartkeyboard.ime.editor

/**
 * The clipboard as the keyboard's own text fields use it (ADR 0038, the rules of ADR 0031 §7 and ADR 0035 §6): [canPaste]
 * reads only the clip's description (text or not, marked sensitive or not), never its content; [paste] reads the content
 * only on the user's Paste tap and never returns a clip the copying app marked sensitive (a password, a one-time code);
 * [copy] puts the user's own selected text on the clipboard as a plain clip (they asked to paste it elsewhere).
 */
interface EditorClipboard {
    fun canPaste(): Boolean

    fun paste(): String?

    /** False when the system refused. */
    fun copy(text: String): Boolean

    /** No clipboard (tests, previews): nothing to paste, nothing copied. */
    object None : EditorClipboard {
        override fun canPaste() = false
        override fun paste(): String? = null
        override fun copy(text: String) = false
    }
}
