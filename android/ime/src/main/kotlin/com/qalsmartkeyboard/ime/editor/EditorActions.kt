package com.qalsmartkeyboard.ime.editor

/** The keyboard text field's action row (ADR 0038): Gboard's select/cut/copy/paste/select-all toolbar, in the field. */
enum class EditorAction { CUT, COPY, PASTE, SELECT_ALL }

/** What the action row offers and does; pure, so it is a JVM test. */
object EditorActions {
    /**
     * The actions that make sense now: Cut and Copy with a selection, Paste when the clipboard's description says it holds
     * text that is not marked sensitive (its content is not read), Select all when not everything is selected.
     */
    fun available(b: EditorBuffer, clipboard: EditorClipboard): List<EditorAction> = buildList {
        if (b.hasSelection) {
            add(EditorAction.CUT)
            add(EditorAction.COPY)
        }
        if (clipboard.canPaste()) add(EditorAction.PASTE)
        if (!b.isEmpty && !(b.selectionStart == 0 && b.selectionEnd == b.length)) add(EditorAction.SELECT_ALL)
    }

    /** Runs [a]; false when nothing happened (no selection, the system refused, nothing pasteable). */
    fun perform(a: EditorAction, b: EditorBuffer, clipboard: EditorClipboard): Boolean = when (a) {
        EditorAction.COPY -> b.hasSelection && clipboard.copy(b.selectedText)
        EditorAction.CUT -> b.hasSelection && clipboard.copy(b.selectedText).also { if (it) b.deleteSelection() }
        EditorAction.PASTE -> {
            // The content is read here, on the user's tap, and only now; a sensitive clip reads as null.
            val clip = clipboard.paste()
            if (clip.isNullOrEmpty()) false else {
                b.insert(clip)
                true
            }
        }
        EditorAction.SELECT_ALL -> {
            if (b.isEmpty) false else {
                b.selectAll()
                true
            }
        }
    }
}
