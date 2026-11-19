package com.qalsmartkeyboard.app.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.qalsmartkeyboard.ime.voice.VoiceController
import com.qalsmartkeyboard.ime.voice.VoiceHost
import com.qalsmartkeyboard.ime.voice.VoicePrefs
import com.qalsmartkeyboard.ime.voice.VoiceProvider
import com.qalsmartkeyboard.ime.voice.VoiceState

/** A text field of the companion app that dictation writes into (ADR 0038). */
interface DictationTarget {
    /** A few characters before the cursor (for spacing), never stored. */
    fun textBeforeCursor(): CharSequence

    /** Puts [text] in at the cursor, replacing the selection. */
    fun insert(text: String)
}

/**
 * Dictation into the companion app's text fields (ADR 0038): Word Art's text and the e-mail writer's fields. It is the
 * keyboard's own voice-typing state machine ([VoiceController], ADR 0029) over the same platform recogniser
 * (`SpeechRecognizer`, [com.qalsmartkeyboard.ime.voice.PlatformVoiceProvider]) and the same language checks
 * ([com.qalsmartkeyboard.ime.voice.VoiceLanguages], only languages the installed service reports are offered as
 * supported), with one field at a time as the target:
 *
 * - [toggle] on a field's microphone starts listening into that field; again (or [stop]) stops and inserts what was
 *   heard; another field's microphone moves dictation there.
 * - Without the microphone permission, [permissionNeeded] turns true and the screen asks for it (the system dialog);
 *   [onPermissionResult] continues, or shows the denial ([permissionDenied]) with a way to allow it.
 * - Partial results are shown as progress ([state] `Listening(partial)`), never inserted; the final text goes in at the
 *   cursor with the keyboard's spacing rules. No speech, no match, network and recogniser errors end in
 *   [VoiceState.Failed] with Try again.
 *
 * Nothing is recorded or kept: the recogniser streams audio itself, and recognised text exists only in [state] until it
 * is inserted; it is never logged or uploaded by us (N2).
 */
class DictationModel(
    provider: VoiceProvider,
    prefs: VoicePrefs,
    private val hasPermission: () -> Boolean,
    /** The language to dictate in by default (a keyboard language: am, ti, om, en, ar). */
    private val language: () -> String,
) {
    /** The recogniser's state, mirrored for Compose. */
    var state: VoiceState by mutableStateOf<VoiceState>(VoiceState.Idle)
        private set

    /** The field dictation goes into (shown with its status line), or null. */
    var activeField: String? by mutableStateOf<String?>(null)
        private set

    /** The screen should ask for the microphone permission now (the system dialog). */
    var permissionNeeded: Boolean by mutableStateOf(false)
        private set

    /** The user refused the microphone: the field's status line explains it and offers to allow it. */
    var permissionDenied: Boolean by mutableStateOf(false)
        private set

    /** The language list of the status line is open. */
    var pickerOpen: Boolean by mutableStateOf(false)
        private set

    private var target: DictationTarget? = null
    private var pending: Pair<String, DictationTarget>? = null

    private val host = object : VoiceHost {
        override val keyboardLanguage: String get() = language()
        override fun hasPermission(): Boolean = this@DictationModel.hasPermission()
        override fun requestPermission() {
            permissionNeeded = true
        }
        override fun beforeVoice() {}
        override fun textBeforeCursor(): CharSequence? = target?.textBeforeCursor()
        override fun wantsCapital(): Boolean {
            val before = target?.textBeforeCursor()?.toString()?.trimEnd().orEmpty()
            return before.isEmpty() || before.last() in ".!?\n"
        }
        override fun commitVoiceText(text: String) {
            target?.insert(text)
        }
    }

    val voice = VoiceController(host, provider, prefs) { sync() }

    /** The installed recognisers were read (off the main thread); until then no microphone is shown. */
    var servicesKnown: Boolean by mutableStateOf(false)

    /** The microphone is offered (voice typing on in the settings, a recogniser installed). */
    val available: Boolean get() = servicesKnown && voice.micVisible

    private fun sync() {
        state = voice.state
        pickerOpen = voice.pickerOpen
        if (!voice.isOpen) {
            target = null
            if (!permissionDenied) activeField = null
        }
    }

    /** A field's microphone. */
    fun toggle(field: String, into: DictationTarget) {
        permissionDenied = false
        if (voice.isOpen && activeField == field) {
            voice.onMicTapped() // stop and insert, or try again after a failure
            return
        }
        if (voice.isOpen) voice.cancel()
        if (!hasPermission()) {
            pending = field to into
            activeField = field
            permissionNeeded = true
            return
        }
        start(field, into)
    }

    private fun start(field: String, into: DictationTarget) {
        target = into
        activeField = field
        voice.onMicTapped()
        sync()
    }

    /** The permission request was shown (the screen consumed [permissionNeeded]). */
    fun onPermissionAsked() {
        permissionNeeded = false
    }

    /** The answer of the system dialog. */
    fun onPermissionResult(granted: Boolean) {
        permissionNeeded = false
        val p = pending
        pending = null
        if (granted && p != null) {
            start(p.first, p.second)
        } else if (!granted) {
            permissionDenied = true
            activeField = p?.first ?: activeField
        }
    }

    /** Stop listening; what was heard goes in. */
    fun stop() = voice.stop()

    /** Close the status line: listening stops without inserting. */
    fun cancel() {
        permissionDenied = false
        pending = null
        voice.cancel()
        activeField = null
        sync()
    }

    fun retry() = voice.retry()
    fun openLanguages() = voice.openPicker()
    fun closeLanguages() {
        voice.closePicker()
        sync()
    }

    fun chooseLanguage(tag: String) = voice.selectLanguage(tag)
}

/** Inserting dictated text into a Compose text field's value at its cursor (pure, so it is a JVM test). */
object DictationText {
    /** [value] with [text] in place of its selection (or at its cursor); the cursor ends after the inserted text. */
    fun insert(value: TextFieldValue, text: String): TextFieldValue {
        val s = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
        val e = maxOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
        val out = value.text.substring(0, s) + text + value.text.substring(e)
        return TextFieldValue(out, TextRange(s + text.length))
    }

    /** Up to [n] characters before the cursor of [value]. */
    fun before(value: TextFieldValue, n: Int = 4): String {
        val c = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
        return value.text.substring(maxOf(0, c - n), c)
    }
}
