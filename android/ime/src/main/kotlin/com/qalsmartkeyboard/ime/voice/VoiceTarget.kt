package com.qalsmartkeyboard.ime.voice

import com.qalsmartkeyboard.ime.editor.EditorBuffer

/**
 * One of the keyboard's own text fields that voice typing dictates into instead of the app's field (ADR 0038): the
 * translate box, an AI panel field, Word Art's text. The recognised text goes in at the field's cursor (replacing its
 * selection), never through the composer or the word learner, exactly like dictation into the app's field (ADR 0029).
 */
class VoiceTarget(val buffer: EditorBuffer) {
    fun textBeforeCursor(n: Int): CharSequence = buffer.textBeforeCursor(n)

    /** English dictation capitalises at the start of the field or after a sentence end. */
    fun wantsCapital(): Boolean {
        val before = buffer.textBeforeCursor(CONTEXT).trimEnd()
        return before.isEmpty() || before.last() in ".!?\n"
    }

    fun insert(text: String) = buffer.insert(text)

    private companion object {
        const val CONTEXT = 8
    }
}

/**
 * The [VoiceHost] of the keyboard with dictation routed to a [target] field while one is set (ADR 0038). The permission,
 * the language and the recogniser are the keyboard's own (the same [VoiceController], the same platform recogniser and
 * language checks of ADR 0029); only where the text goes, what "before the cursor" means and what the panel closes
 * before listening change. [beforeFieldVoice] runs instead of [VoiceHost.beforeVoice] for a target: it finishes the
 * composition into the field and leaves the field's panel open (the field must stay on screen to receive the text).
 */
class RoutedVoiceHost(
    private val appField: VoiceHost,
    private val beforeFieldVoice: () -> Unit,
) : VoiceHost {
    /** The field dictation goes into, or null for the app's field. */
    var target: VoiceTarget? = null

    override val keyboardLanguage: String get() = appField.keyboardLanguage
    override fun hasPermission(): Boolean = appField.hasPermission()
    override fun requestPermission() = appField.requestPermission()

    override fun beforeVoice() {
        if (target != null) beforeFieldVoice() else appField.beforeVoice()
    }

    override fun textBeforeCursor(): CharSequence? = target?.textBeforeCursor(VoiceContext.CHARS) ?: appField.textBeforeCursor()

    override fun wantsCapital(): Boolean = target?.wantsCapital() ?: appField.wantsCapital()

    override fun commitVoiceText(text: String) {
        val t = target
        if (t != null) t.insert(text) else appField.commitVoiceText(text)
    }
}

/** How much text before the cursor voice typing reads to space what it inserts (never stored). */
object VoiceContext {
    const val CHARS = 4
}
