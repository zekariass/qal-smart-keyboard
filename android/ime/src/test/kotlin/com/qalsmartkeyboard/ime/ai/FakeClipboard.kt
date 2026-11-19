package com.qalsmartkeyboard.ime.ai

/** A clipboard whose description and content the test sets; counts every content read. */
class FakeClipboard(var state: ClipAvailability = ClipAvailability.NONE, var content: String? = null) : AiClipboard {
    var descriptionReads = 0
    var contentReads = 0
    val copies = ArrayList<String>()
    var copyWorks = true

    override fun availability(): ClipAvailability {
        descriptionReads++
        return state
    }

    override fun readText(): String? {
        contentReads++
        return if (state == ClipAvailability.TEXT) content else null
    }

    override fun copy(text: String): Boolean {
        if (copyWorks) copies += text
        return copyWorks
    }
}

/** An [AiEditor] with a selection and a whole-field text, recording what was applied. */
class FakeAiEditor(var selection: String? = null, var fieldText: String = "") : AiEditor {
    val inserted = ArrayList<String>()
    val replaced = ArrayList<Pair<String, String>>()
    var allTextReads = 0
    var commits = 0

    override fun commitComposition() { commits++ }
    override fun selectedText(): String? = selection
    override fun textBeforeCursor(n: Int): String? = fieldText.takeLast(n)
    override fun replaceSelection(expected: String, replacement: String): Boolean {
        if (selection != expected) return false
        replaced += expected to replacement
        selection = null
        return true
    }
    override fun replaceBeforeCursor(expected: String, replacement: String) = false
    override fun insertAtCursor(text: String): Boolean {
        inserted += text
        return true
    }
    override fun allText(max: Int): String? {
        allTextReads++
        return fieldText.take(max)
    }
    override fun replaceAll(expected: String, replacement: String): Boolean {
        if (fieldText != expected) return false
        replaced += expected to replacement
        fieldText = replacement
        return true
    }
}
