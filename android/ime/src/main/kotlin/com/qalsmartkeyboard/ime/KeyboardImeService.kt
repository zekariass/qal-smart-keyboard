package com.qalsmartkeyboard.ime

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo

/**
 * IME lifecycle and EditorInfo inspection (SD 6.2 `KeyboardImeService`). Typed text is only ever
 * passed to the InputConnection; it is never logged, stored or sent anywhere (rule N2). Nothing is
 * learned from any field in this step; [InputProfile.learningAllowed] is exposed for A1.3+.
 */
class KeyboardImeService : InputMethodService() {
    private var surface: KeyboardSurface? = null
    private var editorInfo: EditorInfo? = null

    private val sink = object : TextSink {
        override fun commitText(text: String) {
            currentInputConnection?.commitText(text, 1)
        }

        override fun deleteBackward() = sendKey(KeyEvent.KEYCODE_DEL)

        override fun sendEnterKey() = sendKey(KeyEvent.KEYCODE_ENTER)

        override fun performEditorAction(actionId: Int) {
            currentInputConnection?.performEditorAction(actionId)
        }

        override fun wantsAutoCapital(capsFlags: Int): Boolean {
            val info = editorInfo ?: return false
            return (currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0) != 0
        }

        // DEL/ENTER key events also delete a selection and work in hosts that ignore deleteSurroundingText.
        private fun sendKey(code: Int) {
            val ic = currentInputConnection ?: return
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
    }

    private val controller = KeyboardController(sink) { surface?.onLayoutChanged() }

    // The keyboard is small; never take over the screen with the extract UI.
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View =
        KeyboardSurface(this).also {
            it.controller = controller
            surface = it
        }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        editorInfo = attribute
        if (attribute != null) {
            controller.startField(
                InputProfile.from(
                    attribute.inputType,
                    attribute.imeOptions,
                    hasCustomAction = attribute.actionLabel != null,
                    customActionId = attribute.actionId,
                ),
            )
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        controller.refreshAutoCapital()
        surface?.onLayoutChanged()
    }

    override fun onFinishInput() {
        editorInfo = null
        super.onFinishInput()
    }
}
