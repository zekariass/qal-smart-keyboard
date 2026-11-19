package com.qalsmartkeyboard.ime.voice

/**
 * The voice-typing port of the keyboard (ADR 0029). Pure Kotlin: no Android types, so the state machine
 * ([VoiceController]) is a JVM test and a second recogniser (a cloud one behind our gateway, plan V5.1/V5.2) can be
 * added later as another [VoiceProvider] without touching the controller or the panel.
 *
 * Rules every implementation keeps:
 * - It listens only between [VoiceInput.start] and the end of that session ([VoiceListener.onFinal],
 *   [VoiceListener.onError], [VoiceInput.cancel]); the keyboard starts it only on an explicit tap, while it is shown.
 * - Recognised text (partial or final) is user content (plan N2): it is handed to the listener and never logged,
 *   stored, put in crash reports or analytics.
 * - Every call and every callback happens on the main thread.
 */
interface VoiceInput {
    /** Starts listening in [languageTag] (BCP-47, e.g. `am-ET`). Results go to [listener]. */
    fun start(languageTag: String, listener: VoiceListener)

    /** Stops listening and asks for the final result of what was heard so far ([VoiceListener.onFinal] follows). */
    fun stop()

    /** Abandons the session: no further callbacks. Safe to call at any time and more than once. */
    fun cancel()
}

/** Callbacks of one [VoiceInput] session, on the main thread. */
interface VoiceListener {
    /** The recogniser is ready: the user can speak. */
    fun onReady()

    /** Input level, roughly 0 (silence) to 1 (loud), for the level animation. Frequent: keep it cheap. */
    fun onLevel(level: Float)

    /** Text recognised so far (not final: shown as a preview, never committed). */
    fun onPartial(text: String)

    /** The user stopped speaking: the recogniser is working out the final text. */
    fun onEndOfSpeech()

    /** The final text (may be empty when nothing was understood). Ends the session. */
    fun onFinal(text: String)

    /** The session failed. Ends the session. */
    fun onError(kind: VoiceErrorKind)
}

/** Why a session failed, independent of the recogniser behind it. */
enum class VoiceErrorKind {
    /** The recogniser says it does not support the language. */
    LANGUAGE_NOT_SUPPORTED,

    /** The recogniser supports the language but its data is not available (not downloaded, offline). */
    LANGUAGE_UNAVAILABLE,

    /**
     * A generic failure for a language the recogniser has never handled here (old Android versions cannot ask
     * beforehand): most likely the language is not supported, but that cannot be told for sure.
     */
    LANGUAGE_MAYBE_UNSUPPORTED,
    NETWORK,
    AUDIO,
    NO_MATCH,
    NO_SPEECH,
    BUSY,
    SERVER,
    CLIENT,
    PERMISSION,

    /** No recogniser exists on the device, or it could not be bound. */
    UNAVAILABLE,
}

/** One speech recogniser the keyboard can use. [id] is stable (a component name, or [DEFAULT_ID] for the system default). */
data class RecognizerRef(val id: String, val isDefault: Boolean) {
    companion object {
        const val DEFAULT_ID = "default"
        val DEFAULT = RecognizerRef(DEFAULT_ID, true)
    }
}

/**
 * The languages a recogniser declares, as normalised BCP-47 tags ([VoiceLanguages.normalize]). [online] needs a
 * network connection; [onDevice] works offline (installed).
 */
data class RecognizerLanguages(val online: Set<String>, val onDevice: Set<String>) {
    val all: Set<String> get() = online + onDevice
}

/**
 * Where recognisers come from: the platform's `SpeechRecognizer` services today ([PlatformVoiceProvider]); a cloud
 * recogniser later. Main thread.
 */
interface VoiceProvider {
    /** At least one recogniser is installed. Cheap (cached). */
    val available: Boolean

    /** The recognisers to try, in preference order: the system default first, then fallbacks (Google's service). */
    fun recognizers(): List<RecognizerRef>

    /**
     * Asks [recognizer] which languages it supports. [done] gets null when it cannot tell (Android before 13, or the
     * service does not answer), so the language has to be tried. Called at most once per call, on the main thread.
     */
    fun languages(recognizer: RecognizerRef, done: (RecognizerLanguages?) -> Unit)

    /** A new session object for [recognizer]. */
    fun input(recognizer: RecognizerRef): VoiceInput
}
