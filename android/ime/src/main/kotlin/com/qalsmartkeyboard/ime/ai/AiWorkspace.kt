package com.qalsmartkeyboard.ime.ai

import com.qalsmartkeyboard.ime.FieldTyping
import com.qalsmartkeyboard.ime.TextSink

/** Which AI panel replaces the key area (ADR 0031). */
enum class AiScreen { CLOSED, ASSIST, EMAIL }

/**
 * The AI panels that replace the key area (ADR 0031): AI Assist and Write Email. Holds one of them open at a time,
 * creates each lazily on first use, and exposes the field the keys type into ([fieldTyping], used by the keyboard
 * controller's redirect). Free of Views; the service renders it.
 */
class AiWorkspace(
    actions: AiActions,
    editor: AiEditor,
    clipboard: AiClipboard,
    prefs: AiLanguagePrefs,
    post: (() -> Unit) -> Unit,
    keyboardLanguage: () -> String,
    translateDefault: () -> String,
    newKey: () -> String = AiRunner::uuid,
    private val onChanged: () -> Unit = {},
) {
    var screen: AiScreen = AiScreen.CLOSED
        private set

    val assist: AiAssistController by lazy {
        AiAssistController(actions, editor, clipboard, post, keyboardLanguage, translateDefault, newKey) { onChanged() }
    }

    val email: AiEmailController by lazy {
        AiEmailController(actions, editor, clipboard, prefs, post, newKey) { onChanged() }
    }

    private var assistUsed = false
    private var emailUsed = false

    val isOpen: Boolean get() = screen != AiScreen.CLOSED

    fun openAssist() {
        if (screen == AiScreen.ASSIST) return
        closeOther()
        screen = AiScreen.ASSIST
        assistUsed = true
        assist.open()
        onChanged()
    }

    fun openEmail(prefill: AiEmailSpec? = null) {
        if (screen == AiScreen.EMAIL && prefill == null) return
        closeOther()
        screen = AiScreen.EMAIL
        emailUsed = true
        email.open(prefill)
        onChanged()
    }

    private fun closeOther() {
        if (screen == AiScreen.ASSIST) assist.close()
        if (screen == AiScreen.EMAIL) email.close()
    }

    /** Back to the keyboard: every running call is cancelled and every text is dropped. */
    fun close() {
        if (screen == AiScreen.CLOSED) return
        closeOther()
        screen = AiScreen.CLOSED
        onChanged()
    }

    /** Forgets everything without a render (the field changed or the service ends). */
    fun discard() {
        if (assistUsed) assist.close()
        if (emailUsed) email.close()
        screen = AiScreen.CLOSED
    }

    /** The field the keys type into right now, or null (the panel is shown, or no panel is open). */
    val typingField: AiTextField?
        get() = when (screen) {
            AiScreen.ASSIST -> assist.typingField
            AiScreen.EMAIL -> email.typingField
            AiScreen.CLOSED -> null
        }

    val typing: Boolean get() = typingField != null

    /** True while a panel is shown in place of the keys (open and not typing). */
    val showsPanel: Boolean get() = isOpen && !typing

    private val multiline: Boolean
        get() = when (screen) {
            AiScreen.ASSIST -> true
            AiScreen.EMAIL -> email.typing?.multiline ?: true
            AiScreen.CLOSED -> false
        }

    /** Ends typing: the panel is back. The caller commits the open composition first (it goes into the field). */
    fun endTyping() {
        when (screen) {
            AiScreen.ASSIST -> assist.endTyping()
            AiScreen.EMAIL -> email.endTyping()
            AiScreen.CLOSED -> Unit
        }
    }

    private val typingAdapter = object : FieldTyping {
        override val sink: TextSink get() = typingField?.sink ?: NoSink
        override val newlineOnEnter: Boolean get() = multiline
        override fun done() = endTyping()
    }

    /** For `KeyboardController.fieldTyping`: non-null while the keys type into a panel field. */
    fun fieldTyping(): FieldTyping? = if (typing) typingAdapter else null

    /** The system Back key: typing → panel, result → inputs, inputs → keyboard. False when no panel is open. */
    fun back(): Boolean {
        when (screen) {
            AiScreen.CLOSED -> return false
            AiScreen.ASSIST -> when {
                assist.typing != null -> assist.endTyping()
                assist.step is AiAssistStep.Working -> assist.cancel()
                assist.step !is AiAssistStep.Compose -> assist.backToEdit()
                else -> close()
            }
            AiScreen.EMAIL -> when {
                email.typing != null -> email.endTyping()
                email.step == AiEmailStep.Working -> email.cancel()
                email.step != AiEmailStep.Compose -> email.backToCompose()
                else -> close()
            }
        }
        return true
    }

    private object NoSink : TextSink {
        override fun apply(update: com.qalsmartkeyboard.ime.ComposerUpdate) {}
        override fun sendEnterKey() {}
        override fun performEditorAction(actionId: Int) {}
        override fun wantsAutoCapital(capsFlags: Int) = false
    }
}
