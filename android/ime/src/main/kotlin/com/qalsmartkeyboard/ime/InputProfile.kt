package com.qalsmartkeyboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

/** Which key arrangement a host field wants (SD 6.2: email, URI and number fields get Latin/direct input). */
enum class FieldKind { TEXT, EMAIL, URI, NUMBER, PHONE, PASSWORD }

/** What the Enter key does in a host field. */
sealed interface EnterAction {
    /** Insert a line break. */
    data object Newline : EnterAction

    /** Send a plain ENTER key press (used when the field declares no IME action). */
    data object EnterKey : EnterAction

    /** Ask the editor to perform an IME action (`EditorInfo.IME_ACTION_*` or a custom action id). */
    data class Editor(val actionId: Int) : EnterAction
}

enum class EnterLabel { RETURN, GO, SEARCH, SEND, NEXT, PREVIOUS, DONE }

/**
 * What the keyboard needs to know about the focused field, derived only from `EditorInfo` flags
 * (never from text content). Pure and JVM-testable: it takes the raw ints.
 */
data class InputProfile(
    val kind: FieldKind,
    val multiline: Boolean,
    /** False in password fields and when the host sets IME_FLAG_NO_PERSONALIZED_LEARNING (SD 6.2). */
    val learningAllowed: Boolean,
    val enterAction: EnterAction,
    /** Auto-capitalisation request bits (`TYPE_TEXT_FLAG_CAP_*`); 0 when not applicable. */
    val capsFlags: Int,
) {
    /**
     * Words committed here may be learned (SD 7.4): plain text fields that did not ask for no
     * personalised learning (incognito). Password, email, URI, number and phone fields never learn.
     */
    val learnsWords: Boolean
        get() = learningAllowed && kind == FieldKind.TEXT

    /**
     * Phonetic transliteration may be used: plain text fields only. Password, email, URI,
     * number and phone fields stay Latin/direct (SD 6.2, 7: transliteration is never forced).
     */
    val allowsTransliteration: Boolean
        get() = kind == FieldKind.TEXT

    val enterLabel: EnterLabel
        get() = when (val a = enterAction) {
            EnterAction.Newline, EnterAction.EnterKey -> EnterLabel.RETURN
            is EnterAction.Editor -> when (a.actionId) {
                EditorInfo.IME_ACTION_GO -> EnterLabel.GO
                EditorInfo.IME_ACTION_SEARCH -> EnterLabel.SEARCH
                EditorInfo.IME_ACTION_SEND -> EnterLabel.SEND
                EditorInfo.IME_ACTION_NEXT -> EnterLabel.NEXT
                EditorInfo.IME_ACTION_PREVIOUS -> EnterLabel.PREVIOUS
                EditorInfo.IME_ACTION_DONE -> EnterLabel.DONE
                else -> EnterLabel.RETURN
            }
        }

    companion object {
        /** Fallback before any field has been seen. */
        val DEFAULT = from(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_UNSPECIFIED, false, 0)

        /**
         * @param hasCustomAction `EditorInfo.actionLabel != null`; then [customActionId] is honoured.
         */
        fun from(inputType: Int, imeOptions: Int, hasCustomAction: Boolean, customActionId: Int): InputProfile {
            val cls = inputType and InputType.TYPE_MASK_CLASS
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            val flags = inputType and InputType.TYPE_MASK_FLAGS

            val kind = when (cls) {
                InputType.TYPE_CLASS_NUMBER ->
                    if (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) FieldKind.PASSWORD else FieldKind.NUMBER
                InputType.TYPE_CLASS_PHONE -> FieldKind.PHONE
                InputType.TYPE_CLASS_DATETIME -> FieldKind.NUMBER
                InputType.TYPE_CLASS_TEXT -> when (variation) {
                    InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> FieldKind.PASSWORD
                    InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                    InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldKind.EMAIL
                    InputType.TYPE_TEXT_VARIATION_URI -> FieldKind.URI
                    else -> FieldKind.TEXT
                }
                else -> FieldKind.TEXT // TYPE_NULL or unknown: plain typing
            }

            val multiline = cls == InputType.TYPE_CLASS_TEXT &&
                (flags and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 &&
                kind == FieldKind.TEXT

            val learning = kind != FieldKind.PASSWORD &&
                (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0

            val action = imeOptions and EditorInfo.IME_MASK_ACTION
            val noEnterAction = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
            val enter = when {
                hasCustomAction && customActionId != 0 -> EnterAction.Editor(customActionId)
                noEnterAction -> EnterAction.Newline
                action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED ->
                    if (multiline) EnterAction.Newline else EnterAction.EnterKey
                else -> EnterAction.Editor(action)
            }

            val caps = if (cls == InputType.TYPE_CLASS_TEXT && kind == FieldKind.TEXT) {
                flags and (
                    InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                        InputType.TYPE_TEXT_FLAG_CAP_WORDS or
                        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    )
            } else {
                0
            }

            return InputProfile(kind, multiline, learning, enter, caps)
        }
    }
}
