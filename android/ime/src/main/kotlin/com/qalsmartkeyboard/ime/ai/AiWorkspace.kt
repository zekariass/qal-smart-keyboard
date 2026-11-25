package com.qalsmartkeyboard.ime.ai

import com.qalsmartkeyboard.ime.FieldTyping
import com.qalsmartkeyboard.ime.TextSink

/** Which AI panel replaces the key area (ADR 0031). */
enum class AiScreen { CLOSED, ASSIST, EMAIL, CHAT }

/**
 * The AI panels that replace the key area (ADR 0031): AI Assist, Write Email and the Ask AI chat (T8.5). Holds one of them open at a time,
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
    /** The Ask AI chat's hooks: AI is allowed in the focused field; the user turn that asks for the rest of a cut-off reply. */
    chatFieldAllowed: () -> Boolean = { true },
    chatContinueText: () -> String = { "Continue" },
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

    val chat: ChatController by lazy {
        ChatController(
            actions, editor, clipboard, post, keyboardLanguage, chatFieldAllowed, chatContinueText, newKey,
            refresh = { actions.refresh() },
        ) { onChanged() }
    }

    private var assistUsed = false
    private var emailUsed = false
    private var chatUsed = false

    val isOpen: Boolean get() = screen != AiScreen.CLOSED

    /**
     * Opens AI Assist; with [action] (an AI tools tile: Reply, Explain, Summarise, ADR 0041) on that action. Already open
     * without an action: nothing changes.
     */
    fun openAssist(action: AiAssistAction? = null) {
        if (screen == AiScreen.ASSIST && action == null) return
        if (screen != AiScreen.ASSIST) {
            closeOther()
            screen = AiScreen.ASSIST
            assistUsed = true
            assist.open()
        }
        if (action != null) assist.selectAction(action)
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

    /** Opens the Ask AI chat (T8.5) in place of the keys. Nothing is read or sent by opening it. */
    fun openChat() {
        if (screen == AiScreen.CHAT) return
        if (!chat.allowed) return
        closeOther()
        screen = AiScreen.CHAT
        chatUsed = true
        chat.open()
        onChanged()
    }

    private fun closeOther() {
        if (screen == AiScreen.ASSIST) assist.close()
        if (screen == AiScreen.EMAIL) email.close()
        if (screen == AiScreen.CHAT) chat.close()
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
        if (chatUsed) chat.close()
        screen = AiScreen.CLOSED
    }

    /** The field the keys type into right now, or null (the panel is shown, or no panel is open). */
    val typingField: AiTextField?
        get() = when (screen) {
            AiScreen.ASSIST -> assist.typingField
            AiScreen.EMAIL -> email.typingField
            AiScreen.CHAT -> chat.typingField
            AiScreen.CLOSED -> null
        }

    val typing: Boolean get() = typingField != null

    /** True while a panel is shown in place of the keys (open and not typing). */
    val showsPanel: Boolean get() = isOpen && !typing

    /** The field being typed into takes line breaks (Enter adds one); one-line fields: names, the subject. */
    val typingMultiline: Boolean
        get() = when (screen) {
            AiScreen.ASSIST -> true
            AiScreen.EMAIL -> email.typing?.multiline ?: true
            AiScreen.CHAT, AiScreen.CLOSED -> false
        }

    /** Ends typing: the panel is back. The caller commits the open composition first (it goes into the field). */
    fun endTyping() {
        when (screen) {
            AiScreen.ASSIST -> assist.endTyping()
            AiScreen.EMAIL -> email.endTyping()
            AiScreen.CHAT -> chat.endTyping()
            AiScreen.CLOSED -> Unit
        }
    }

    private val typingAdapter = object : FieldTyping {
        override val sink: TextSink get() = typingField?.sink ?: NoSink
        override val newlineOnEnter: Boolean get() = typingMultiline
        override fun done() = endTyping()

        // Enter in the chat's message sends it (the panel shows the answer); the other fields just end the typing.
        override fun submit() = if (screen == AiScreen.CHAT) chat.onEnter() else done()
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
            AiScreen.CHAT -> when {
                chat.typing -> chat.endTyping()
                chat.languagePickerOpen -> chat.toggleLanguagePicker()
                chat.busy -> chat.cancel()
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
