package com.qalsmartkeyboard.ime

/** The editor operations the keyboard needs; implemented over InputConnection by the service. */
interface TextSink {
    fun commitText(text: String)
    fun deleteBackward()
    fun sendEnterKey()
    fun performEditorAction(actionId: Int)

    /** True when the host wants the next typed letter capitalised (cursor caps mode). */
    fun wantsAutoCapital(capsFlags: Int): Boolean
}

enum class ShiftState { OFF, ONCE, LOCKED }

/**
 * Keyboard state machine: current page, shift, and key handling. Contains no View code and never
 * logs or stores typed text (rule N2); it only forwards it to [TextSink].
 */
class KeyboardController(private val sink: TextSink, private val onChanged: () -> Unit) {
    var profile: InputProfile = InputProfile.DEFAULT
        private set
    var page: Page = Page.LETTERS
        private set
    var shift: ShiftState = ShiftState.OFF
        private set

    val layout: KeyboardLayout get() = Layouts.forPage(page, profile)

    fun startField(newProfile: InputProfile) {
        profile = newProfile
        page = Layouts.startPage(newProfile)
        shift = ShiftState.OFF
        refreshAutoCapital()
        onChanged()
    }

    fun refreshAutoCapital() {
        if (shift != ShiftState.LOCKED && page == Page.LETTERS) {
            shift = if (profile.capsFlags != 0 && sink.wantsAutoCapital(profile.capsFlags)) ShiftState.ONCE else ShiftState.OFF
        }
    }

    fun onKey(key: Key) {
        when (key.kind) {
            KeyKind.CHAR -> {
                if (key.isSpacer) return
                val upper = shift != ShiftState.OFF && page == Page.LETTERS
                sink.commitText(if (upper) key.text.uppercase() else key.text)
                if (shift == ShiftState.ONCE) shift = ShiftState.OFF
                refreshAutoCapital()
            }
            KeyKind.SPACE -> { sink.commitText(" "); refreshAutoCapital() }
            KeyKind.BACKSPACE -> { sink.deleteBackward(); refreshAutoCapital() }
            KeyKind.ENTER -> {
                when (val a = profile.enterAction) {
                    EnterAction.Newline -> sink.commitText("\n")
                    EnterAction.EnterKey -> sink.sendEnterKey()
                    is EnterAction.Editor -> sink.performEditorAction(a.actionId)
                }
                refreshAutoCapital()
            }
            KeyKind.SHIFT -> shift = when (shift) {
                ShiftState.OFF -> ShiftState.ONCE
                ShiftState.ONCE -> ShiftState.LOCKED
                ShiftState.LOCKED -> ShiftState.OFF
            }
            KeyKind.TO_SYMBOLS -> page = Page.SYMBOLS
            KeyKind.TO_SYMBOLS_2 -> page = Page.SYMBOLS_2
            KeyKind.TO_LETTERS -> { page = Page.LETTERS; refreshAutoCapital() }
        }
        onChanged()
    }
}
