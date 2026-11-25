package com.qalsmartkeyboard.ime.voice

import com.qalsmartkeyboard.ime.FieldKind
import com.qalsmartkeyboard.ime.InputProfile
import com.qalsmartkeyboard.ime.ai.AiActions
import com.qalsmartkeyboard.ime.ai.AiBlock
import com.qalsmartkeyboard.ime.ai.AiCall
import com.qalsmartkeyboard.ime.ai.AiDestination
import com.qalsmartkeyboard.ime.ai.AiFailure
import com.qalsmartkeyboard.ime.ai.AiLanguages
import com.qalsmartkeyboard.ime.ai.AiOutcome
import com.qalsmartkeyboard.ime.ai.AiRequest
import com.qalsmartkeyboard.ime.ai.AiTask
import com.qalsmartkeyboard.ime.ai.AiTextTarget
import java.util.Locale
import java.util.UUID

/** Where the voice panel is. [VoiceController.isOpen] is true in every state but [Idle]. */
sealed interface VoiceState {
    /** The keys are shown. */
    data object Idle : VoiceState

    /** The panel is open, the recogniser is being chosen or started. */
    data object Preparing : VoiceState

    /** The recogniser is listening; [partial] is the preview of what it heard (not in the field yet). */
    data class Listening(val partial: String) : VoiceState

    /** The user stopped (or paused): the recogniser is working out the final text. */
    data class Processing(val partial: String) : VoiceState

    /** The panel is open but not listening (after the language picker was closed): the big button starts. */
    data object Ready : VoiceState

    /** The session failed; the panel explains [kind] for [tag] and offers Try again. */
    data class Failed(val kind: VoiceErrorKind, val tag: String) : VoiceState
}

/** What the voice controller needs from the keyboard. Main thread. */
interface VoiceHost {
    /** The keyboard language (am, ti, om, en): the default recognition language. */
    val keyboardLanguage: String

    /** The app holds RECORD_AUDIO. */
    fun hasPermission(): Boolean

    /** Opens the app's microphone-permission screen (an IME cannot show the system dialog itself). */
    fun requestPermission()

    /** Before the panel opens: commit the open composition as typed, close the emoji panel and the AI actions. */
    fun beforeVoice()

    /** A few characters before the cursor (for spacing), or null when the editor does not answer. */
    fun textBeforeCursor(): CharSequence?

    /** The field's auto-capitalisation would capitalise a letter typed at the cursor now. */
    fun wantsCapital(): Boolean

    /** Commits recognised text to the field. Never observed by the word learner (ADR 0029). */
    fun commitVoiceText(text: String)

    /**
     * T8.12 (ADR 0049 D3): replaces [expected], the text just before the cursor (trailing whitespace kept), with
     * [replacement] if it still reads so; false when it does not (the field changed) or the editor does not answer.
     */
    fun replaceDictated(expected: String, replacement: String): Boolean = false

    /**
     * T8.12: dictation goes into the app's field (true), not into one of the keyboard's own fields, which have their own
     * AI controls: only then is a follow-up strip offered.
     */
    val insertsIntoAppField: Boolean get() = true
}

/** The voice-typing settings the controller reads and writes (device-local, `KeyboardSettings`). */
interface VoicePrefs {
    /** The "Voice typing" setting: the microphone is offered at all. */
    val enabled: Boolean

    /** The recognition tag the user last chose for [keyboardLanguage], or null for the default. */
    fun chosen(keyboardLanguage: String): String?

    fun choose(keyboardLanguage: String, tag: String)

    /** The one-time privacy note was shown. */
    val noticeShown: Boolean

    fun noticeSeen()

    /** T8.12: offer "Fix grammar" / "Translate" after a dictation (default on). */
    val followUps: Boolean get() = true

    /** T8.12: translate dictation into this language before inserting it (null = as spoken). */
    val insertLanguage: String? get() = null

    fun chooseInsertLanguage(language: String?) {}

    /** For tests and previews: a plain in-memory store. */
    class InMemory(
        override var enabled: Boolean = true,
        override var noticeShown: Boolean = false,
        override var followUps: Boolean = true,
        override var insertLanguage: String? = null,
    ) : VoicePrefs {
        val choices = HashMap<String, String>()
        override fun chooseInsertLanguage(language: String?) {
            insertLanguage = language
        }
        override fun chosen(keyboardLanguage: String): String? = choices[keyboardLanguage]
        override fun choose(keyboardLanguage: String, tag: String) {
            choices[keyboardLanguage] = tag
        }
        override fun noticeSeen() {
            noticeShown = true
        }
    }
}

/** One row of the language picker. */
data class VoiceLanguageOption(val tag: String, val label: String, val support: SupportLevel, val selected: Boolean)

/**
 * Voice typing's state machine (ADR 0029); no View code, no Android types.
 *
 * Idle -> (mic tap) Preparing -> Listening -> Processing -> text inserted -> Idle; any of them -> Failed (Try again
 * goes back to Preparing); the language chip opens the picker (the session is cancelled, the panel waits in Ready);
 * Back to keyboard, a field change, the keyboard hiding or the setting turned off cancel (nothing inserted).
 * Tapping stop (or the mic again) while listening stops and inserts what was recognised: the final result, or the
 * last partial one when the recogniser returns nothing final.
 *
 * Without the microphone permission the mic opens the app's permission screen instead ([VoiceHost.requestPermission]).
 * Recognised text is only held in [state] until it is inserted; it is never logged or stored (N2).
 */
class VoiceController(
    private val host: VoiceHost,
    private val provider: VoiceProvider,
    private val prefs: VoicePrefs,
    val support: VoiceSupport = VoiceSupport(provider),
    private val deviceLocale: () -> Locale = { Locale.getDefault() },
    /** T8.12 (ADR 0049): the AI actions, or null while the app registered none. */
    private val ai: () -> AiActions? = { null },
    private val packageName: () -> String? = { null },
    /** AI callbacks arrive on any thread; this runs a block on the main thread. */
    private val runOnMain: (() -> Unit) -> Unit = { it() },
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
    private val onChanged: () -> Unit,
) {
    var state: VoiceState = VoiceState.Idle
        private set

    /** Input level 0..1 of the current session (the panel animates it; [onLevel] fires on every change). */
    var level: Float = 0f
        private set

    var onLevel: (Float) -> Unit = {}

    /** The language picker is shown (over [VoiceState.Ready]). */
    var pickerOpen: Boolean = false
        private set

    /** The picker shows "More languages" (every other tag a recogniser declared). */
    var moreOpen: Boolean = false
        private set

    /** The picker is waiting for the recognisers' language lists. */
    var pickerLoading: Boolean = false
        private set

    /** The one-time privacy note is shown in this panel session. */
    var showNotice: Boolean = false
        private set

    /** The choice of the current (or last) session. */
    var choice: VoiceChoice? = null
        private set

    private var fieldAllows = true
    private var session: VoiceInput? = null
    private var sessionId = 0
    private var autoRetries = 0

    val isOpen: Boolean get() = state != VoiceState.Idle

    // ------------------------------------------------------------------ T8.12 voice + AI chain (ADR 0049)

    /**
     * The strip after a dictation into the app's field, or null. Its text is the dictation as committed, trimmed (the
     * space voice typing put before it stays in the field); after [FollowUpPhase.Done] it is the result. It is user text:
     * held here only until dismissed (N2).
     */
    var followUp: DictationFollowUp? = null
        private set

    private var followCall: AiCall? = null
    private var followToken = 0
    private var followOriginal: String? = null

    /** The final result is being translated before it is inserted ("Insert in" mode). */
    var insertTranslating: Boolean = false
        private set

    private var translateCall: AiCall? = null
    private var translateToken = 0

    /** The "Insert in" language in effect: the preference when AI is usable and the language is translatable, else null (as spoken). */
    val insertLanguage: String?
        get() {
            val a = usableAi() ?: return null
            return prefs.insertLanguage?.takeIf { a.translationCatalog().isDestination(it) }
        }

    /** The plain languages the "Insert in" picker offers (the translation catalogue). Empty while AI is unusable. */
    fun insertLanguageChoices(): List<AiDestination> {
        val a = usableAi() ?: return emptyList()
        return a.translationCatalog().destinations.map { AiLanguages.plain(it.code) }
    }

    fun chooseInsertLanguage(language: String?) {
        prefs.chooseInsertLanguage(language)
        onChanged()
    }

    /** Fix grammar of the dictated text (in its own language). Sends it to the server: only on the user's tap. */
    fun fixGrammar() = runFollowUp(FollowUpAction.GRAMMAR, null)

    /** Translate the dictated text into [destination] (default: the strip's destination). Only on the user's tap. */
    fun translateFollowUp(destination: AiDestination? = null) = runFollowUp(FollowUpAction.TRANSLATE, destination)

    /** Puts the original dictation back in place of the result, then dismisses the strip. */
    fun undoFollowUp() {
        val f = followUp ?: return
        val original = followOriginal
        if (f.phase is FollowUpPhase.Done && original != null) host.replaceDictated(f.text, original)
        dismissFollowUp()
    }

    /** The strip's cross (also used for every other way the strip ends): cancels a running request, forgets the text. */
    fun dismissFollowUp() {
        if (clearFollowUp()) onChanged()
    }

    /**
     * The 20 second timer of the strip: the host (which owns a Handler; the controller has no timers) calls this 20 s
     * after the strip appeared or changed. Dismisses an offered or finished ([FollowUpPhase.Done]) strip; a running or
     * failed one stays (the failure message goes with typing or the cross).
     */
    fun onFollowUpTimeout() {
        val phase = followUp?.phase
        if (phase == FollowUpPhase.Offer || phase is FollowUpPhase.Done) dismissFollowUp()
    }

    /** The host: the editor changed for a reason other than this controller's own commit (typing, cursor move, field change). */
    fun onEditorChanged() = dismissFollowUp()

    private fun usableAi(): AiActions? = ai()?.takeIf { it.availability.block == AiBlock.NONE }

    private fun blockFailure(block: AiBlock?): AiFailure = when (block) {
        AiBlock.OFFLINE -> AiFailure.Offline
        AiBlock.SIGNED_OUT -> AiFailure.SignInRequired
        AiBlock.UPDATE_REQUIRED -> AiFailure.UpdateRequired
        else -> AiFailure.Other
    }

    /** Forgets the strip and its request without telling the view. True when there was one. */
    private fun clearFollowUp(): Boolean {
        followToken++
        followCall?.cancel()
        followCall = null
        followOriginal = null
        val had = followUp != null
        followUp = null
        return had
    }

    private fun cancelTranslation() {
        translateToken++
        translateCall?.cancel()
        translateCall = null
        insertTranslating = false
    }

    private fun spokenLanguage(tag: String): String = VoiceLanguages.languageOf(tag)

    /** Where Translate goes by default: the app's destination as a plain language, never the spoken language itself. */
    private fun followDestination(a: AiActions, spoken: String): AiDestination {
        var d = a.defaultDestination(packageName())
        if (d.isProfile) d = AiLanguages.plain(d.language)
        if (VoiceLanguages.languageOf(d.language) == spoken) d = AiLanguages.plain(AiLanguages.defaultTarget(spoken))
        return d
    }

    /** After a dictation was committed to the app's field: offers the strip when the conditions of ADR 0049 D2 hold. */
    private fun offerFollowUp(committed: String, spoken: String) {
        clearFollowUp()
        if (!host.insertsIntoAppField || !prefs.followUps) return
        val a = usableAi() ?: return
        val text = committed.trim()
        if (text.isEmpty() || text.length > AiTextTarget.MAX_CHARS) return
        followUp = DictationFollowUp(text, spoken, followDestination(a, spoken))
    }

    private fun runFollowUp(action: FollowUpAction, chosen: AiDestination?) {
        val f = followUp ?: return
        if (f.phase !is FollowUpPhase.Offer && f.phase !is FollowUpPhase.Failed) return
        val dest = chosen ?: f.destination
        if (chosen != null && chosen != f.destination) ai()?.rememberDestination(packageName(), chosen)
        val text = f.text
        val a = ai()
        val block = a?.availability?.block
        val failure = when {
            a == null -> AiFailure.Other
            block != AiBlock.NONE -> blockFailure(block)
            text.isBlank() -> AiFailure.NothingToProcess
            text.length > AiTextTarget.MAX_CHARS -> AiFailure.TooLong
            else -> null
        }
        if (a == null || failure != null) {
            followUp = DictationFollowUp(text, f.spokenLanguage, dest, FollowUpPhase.Failed(failure, false, action))
            onChanged()
            return
        }
        followCall?.cancel()
        followCall = null
        val token = ++followToken
        followUp = DictationFollowUp(text, f.spokenLanguage, dest, FollowUpPhase.Working(action))
        onChanged()
        val task = if (action == FollowUpAction.GRAMMAR) AiTask.Grammar else AiTask.Translate(dest, sourceLanguage = f.spokenLanguage)
        val call = try {
            a.submit(AiRequest(newRequestId(), task, text)) { outcome ->
                runOnMain { onFollowUpOutcome(token, action, f.spokenLanguage, dest, text, outcome) }
            }
        } catch (e: RuntimeException) {
            if (token == followToken) {
                followUp = DictationFollowUp(text, f.spokenLanguage, dest, FollowUpPhase.Failed(AiFailure.Other, false, action))
                onChanged()
            }
            return
        }
        if (token == followToken && followUp?.phase is FollowUpPhase.Working) followCall = call
    }

    private fun onFollowUpOutcome(token: Int, action: FollowUpAction, spoken: String, dest: AiDestination, text: String, outcome: AiOutcome) {
        if (token != followToken || followUp == null) return // dismissed or replaced meanwhile
        followCall = null
        val phase = when (outcome) {
            is AiOutcome.Failure -> FollowUpPhase.Failed(outcome.failure, false, action)
            is AiOutcome.Success -> {
                val result = outcome.result.text.trim()
                when {
                    result.isEmpty() -> FollowUpPhase.Failed(AiFailure.Other, false, action)
                    host.replaceDictated(text, result) -> {
                        followOriginal = text
                        followUp = DictationFollowUp(result, spoken, dest, FollowUpPhase.Done(action))
                        onChanged()
                        return
                    }
                    else -> FollowUpPhase.Failed(null, true, action) // the field changed meanwhile: nothing was inserted
                }
            }
        }
        followUp = DictationFollowUp(text, spoken, dest, phase)
        onChanged()
    }

    /** A dictation result [heard] in [tag]: inserted as it is, or translated first in "Insert in" mode (ADR 0049 D4). */
    private fun finish(heard: String, tag: String) {
        val a = ai()
        val lang = prefs.insertLanguage?.let { VoiceLanguages.languageOf(it) }
            ?.takeIf { a != null && a.translationCatalog().isDestination(it) && it != VoiceLanguages.languageOf(tag) }
        if (a == null || lang == null) {
            val committed = insert(heard, VoiceText.isEnglish(tag))
            offerFollowUp(committed, spokenLanguage(tag))
            closeSession()
            return
        }
        val dest = AiLanguages.plain(lang)
        val text = heard.trim()
        val block = a.availability.block
        if (block != AiBlock.NONE) {
            commitOriginal(heard, tag, dest, blockFailure(block))
            return
        }
        if (text.length > AiTextTarget.MAX_CHARS) {
            commitOriginal(heard, tag, dest, AiFailure.TooLong)
            return
        }
        val token = ++translateToken
        insertTranslating = true
        session = null
        state = VoiceState.Processing(partialOf(state))
        level = 0f
        onChanged()
        val task = AiTask.Translate(dest, sourceLanguage = spokenLanguage(tag))
        val call = try {
            a.submit(AiRequest(newRequestId(), task, text)) { outcome ->
                runOnMain { onTranslated(token, heard, tag, lang, dest, outcome) }
            }
        } catch (e: RuntimeException) {
            if (token == translateToken && insertTranslating) {
                insertTranslating = false
                commitOriginal(heard, tag, dest, AiFailure.Other)
            }
            return
        }
        if (token == translateToken && insertTranslating) translateCall = call
    }

    private fun onTranslated(token: Int, heard: String, tag: String, lang: String, dest: AiDestination, outcome: AiOutcome) {
        if (token != translateToken || !insertTranslating) return // cancelled meanwhile: nothing is inserted
        insertTranslating = false
        translateCall = null
        val result = (outcome as? AiOutcome.Success)?.result?.text?.trim().orEmpty()
        if (result.isNotEmpty()) {
            insert(result, VoiceText.isEnglish(lang))
            closeSession()
        } else {
            commitOriginal(heard, tag, dest, (outcome as? AiOutcome.Failure)?.failure ?: AiFailure.Other)
        }
    }

    /** The translation could not be made: what the user said is inserted as spoken, with a Translate retry on the strip. */
    private fun commitOriginal(heard: String, tag: String, dest: AiDestination, failure: AiFailure) {
        val committed = insert(heard, VoiceText.isEnglish(tag))
        clearFollowUp()
        val text = committed.trim()
        if (host.insertsIntoAppField && text.isNotEmpty() && text.length <= AiTextTarget.MAX_CHARS) {
            followUp = DictationFollowUp(text, spokenLanguage(tag), dest, FollowUpPhase.Failed(failure, false, FollowUpAction.TRANSLATE))
        }
        closeSession()
    }

    /** The mic is offered: setting on, a recogniser installed, and the field allows it (not a password field). */
    val micVisible: Boolean get() = prefs.enabled && provider.available && fieldAllows

    /** The recognition tag the panel names: the session's, else the first one that will be tried. */
    val currentTag: String get() = choice?.tag ?: pendingTag ?: tags().first()

    private var pendingTag: String? = null

    /** The tags to try, most preferred first: the user's choice and its language's defaults, else the keyboard language's. */
    private fun tags(): List<String> {
        val kl = host.keyboardLanguage
        val chosen = prefs.chosen(kl)?.let { VoiceLanguages.normalize(it) }?.takeIf { it.isNotEmpty() }
            ?: return VoiceLanguages.defaults(kl, deviceLocale())
        val family = VoiceLanguages.keyboardLanguageOf(chosen)?.let { VoiceLanguages.defaults(it, deviceLocale()) }.orEmpty()
        return (listOf(chosen) + family).distinct()
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * A field started: [allowsVoice] false hides the mic ([fieldAllowsVoice]). A different field cancels anything
     * running; the same field restarting (some apps restart input on their own) keeps a session where voice is allowed.
     */
    fun onFieldStarted(allowsVoice: Boolean, sameField: Boolean = false) {
        if (!sameField || !allowsVoice) cancel()
        fieldAllows = allowsVoice
        if (sameField) onChanged()
    }

    /** The keyboard was hidden: never listen while it is not visible. */
    fun onKeyboardHidden() = cancel()

    /** Settings changed: turning voice typing off closes the panel. */
    fun onPreferencesChanged() {
        if (!prefs.enabled) cancel()
        if (!prefs.followUps) dismissFollowUp()
    }

    // ------------------------------------------------------------------ user actions

    /** The mic in the bar: start; while listening it stops (and inserts); after a failure it tries again. */
    fun onMicTapped() {
        when (state) {
            VoiceState.Idle -> begin()
            VoiceState.Preparing, is VoiceState.Listening -> stop()
            is VoiceState.Processing -> Unit
            VoiceState.Ready, is VoiceState.Failed -> retry()
        }
    }

    /** The big button of the panel: stop while listening, start when ready or failed. */
    fun onMainButton() = onMicTapped()

    private fun begin() {
        if (!micVisible) return
        if (!host.hasPermission()) {
            host.requestPermission()
            return
        }
        host.beforeVoice()
        showNotice = !prefs.noticeShown
        if (showNotice) prefs.noticeSeen()
        autoRetries = 0
        startSession()
    }

    /** Try again (failed or ready): a new session in the current language. */
    fun retry() {
        if (!isOpen) return
        if (!host.hasPermission()) {
            cancel()
            host.requestPermission()
            return
        }
        pickerOpen = false
        moreOpen = false
        autoRetries = 0
        startSession()
    }

    /** Stop listening and insert what was recognised so far (the final result follows). */
    fun stop() {
        when (val s = state) {
            is VoiceState.Listening -> {
                state = VoiceState.Processing(s.partial)
                session?.stop()
                onChanged()
            }
            VoiceState.Preparing -> {
                // Nothing heard yet: stopping before the recogniser is ready ends the session without text.
                val running = session
                if (running == null) cancel() else {
                    state = VoiceState.Processing("")
                    running.stop()
                    onChanged()
                }
            }
            else -> Unit
        }
    }

    /** Back to the keys; nothing is inserted. */
    fun backToKeyboard() = cancel()

    /** Ends everything without inserting (also the follow-up strip and a running translation). Safe in every state. */
    fun cancel() {
        val had = clearFollowUp()
        if (!closeSession() && had) onChanged()
    }

    /** Closes the panel and the session; the follow-up strip is left alone. True when it told the view. */
    private fun closeSession(): Boolean {
        sessionId++
        session?.cancel()
        session = null
        cancelTranslation()
        val wasOpen = isOpen || pickerOpen
        state = VoiceState.Idle
        pickerOpen = false
        moreOpen = false
        pickerLoading = false
        showNotice = false
        level = 0f
        if (wasOpen) onChanged()
        return wasOpen
    }

    // ------------------------------------------------------------------ language picker

    /** The language chip: stops listening and shows the picker (the recognisers are asked once a day). */
    fun openPicker() {
        if (!isOpen) return
        sessionId++
        session?.cancel()
        session = null
        cancelTranslation()
        level = 0f
        state = VoiceState.Ready
        pickerOpen = true
        moreOpen = false
        pickerLoading = true
        onChanged()
        val id = sessionId
        support.loadAll {
            if (id != sessionId || !pickerOpen) return@loadAll
            pickerLoading = false
            onChanged()
        }
    }

    fun closePicker() {
        if (!pickerOpen) return
        pickerOpen = false
        moreOpen = false
        onChanged()
    }

    fun showMore(show: Boolean) {
        if (!pickerOpen || moreOpen == show) return
        moreOpen = show
        onChanged()
    }

    /**
     * am, ti, om, en (and Arabic when a speech service can take it or it is the keyboard language): the best tag of each
     * and whether it works here.
     */
    fun languageOptions(): List<VoiceLanguageOption> {
        val current = currentTag
        return VoiceLanguages.KEYBOARD_LANGUAGES.mapNotNull { kl ->
            val c = support.choose(VoiceLanguages.defaults(kl, deviceLocale()))
            val listed = kl !in VoiceLanguages.LISTED_WHEN_AVAILABLE || c.support.usable || kl == host.keyboardLanguage
            if (!listed) return@mapNotNull null
            VoiceLanguageOption(c.tag, VoiceLanguages.displayName(c.tag), c.support, selected = c.tag == current)
        }
    }

    /** Every other language a recogniser declares, by name. Empty when no recogniser can list its languages. */
    fun moreOptions(): List<VoiceLanguageOption> {
        val main = languageOptions().map { it.tag }.toSet()
        val current = currentTag
        return support.declaredTags().filter { it !in main }.map { t ->
            val c = support.choose(listOf(t))
            VoiceLanguageOption(t, VoiceLanguages.displayName(t), c.support, selected = t == current)
        }.sortedBy { it.label }
    }

    /** The user picked [tag]: remembered for the keyboard language (when it can work), then listening starts in it. */
    fun selectLanguage(tag: String) {
        if (!isOpen) return
        val t = VoiceLanguages.normalize(tag)
        if (support.choose(listOf(t)).support.usable) prefs.choose(host.keyboardLanguage, t)
        pickerOpen = false
        moreOpen = false
        autoRetries = 0
        startSession(listOf(t) + (VoiceLanguages.keyboardLanguageOf(t)?.let { VoiceLanguages.defaults(it, deviceLocale()) }.orEmpty()))
    }

    // ------------------------------------------------------------------ sessions

    private fun startSession(tags: List<String> = tags()) {
        val wanted = tags.distinct()
        session?.cancel()
        session = null
        cancelTranslation()
        clearFollowUp() // a new dictation: the old strip goes
        val id = ++sessionId
        choice = null
        pendingTag = VoiceLanguages.normalize(wanted.first())
        state = VoiceState.Preparing
        level = 0f
        onChanged()
        support.resolve(wanted) { c ->
            if (id != sessionId) return@resolve
            choice = c
            if (!provider.available) {
                fail(VoiceErrorKind.UNAVAILABLE, c.tag)
                return@resolve
            }
            if (!c.support.usable) {
                fail(VoiceErrorKind.LANGUAGE_NOT_SUPPORTED, c.tag)
                return@resolve
            }
            val input = provider.input(c.recognizer)
            session = input
            input.start(c.tag, Listener(id, c, wanted))
        }
    }

    private fun fail(kind: VoiceErrorKind, tag: String) {
        session = null
        level = 0f
        state = VoiceState.Failed(kind, tag)
        onChanged()
    }

    /** Commits [text] to the field; returns what was committed ("" when nothing). */
    private fun insert(text: String, english: Boolean): String {
        val out = VoiceText.prepare(text, host.textBeforeCursor(), english, english && host.wantsCapital())
        if (out.isNotEmpty()) host.commitVoiceText(out)
        return out
    }

    private fun partialOf(s: VoiceState): String = when (s) {
        is VoiceState.Listening -> s.partial
        is VoiceState.Processing -> s.partial
        else -> ""
    }

    private inner class Listener(private val id: Int, private val c: VoiceChoice, private val tags: List<String>) : VoiceListener {
        private val live get() = id == sessionId && !insertTranslating

        override fun onReady() {
            if (!live || state != VoiceState.Preparing) return
            state = VoiceState.Listening("")
            onChanged()
        }

        override fun onLevel(level: Float) {
            if (!live) return
            val v = level.coerceIn(0f, 1f)
            this@VoiceController.level = v
            this@VoiceController.onLevel(v) // the controller's callback (not this listener method)
        }

        override fun onPartial(text: String) {
            if (!live) return
            state = when (state) {
                is VoiceState.Processing -> VoiceState.Processing(text)
                VoiceState.Preparing, is VoiceState.Listening -> VoiceState.Listening(text)
                else -> return
            }
            onChanged()
        }

        override fun onEndOfSpeech() {
            if (!live) return
            val s = state
            if (s is VoiceState.Listening || s == VoiceState.Preparing) {
                state = VoiceState.Processing(partialOf(s))
                level = 0f
                onChanged()
            }
        }

        override fun onFinal(text: String) {
            if (!live) return
            support.cache.confirm(c.recognizer, c.tag)
            val heard = text.ifBlank { partialOf(state) }
            session = null
            if (heard.isBlank()) {
                fail(VoiceErrorKind.NO_MATCH, c.tag)
                return
            }
            finish(heard, c.tag)
        }

        override fun onError(kind: VoiceErrorKind) {
            if (!live) return
            session = null
            val partial = partialOf(state)
            if ((kind == VoiceErrorKind.NO_MATCH || kind == VoiceErrorKind.NO_SPEECH) && partial.isNotBlank()) {
                finish(partial, c.tag) // stopped before a final result: keep what was recognised
                return
            }
            if (kind == VoiceErrorKind.LANGUAGE_NOT_SUPPORTED) {
                support.cache.reject(c.recognizer, c.tag)
                // Another tag of the language or another recogniser may still do it (asked or not yet tried).
                val next = support.choose(tags)
                if (next.support.usable && next != c && autoRetries < MAX_AUTO_RETRIES) {
                    autoRetries++
                    startSession(tags)
                    return
                }
            }
            val shown = if ((kind == VoiceErrorKind.CLIENT || kind == VoiceErrorKind.SERVER) && c.support == SupportLevel.UNKNOWN &&
                !VoiceText.isEnglish(c.tag) && !support.cache.isConfirmed(c.recognizer, c.tag)
            ) {
                VoiceErrorKind.LANGUAGE_MAYBE_UNSUPPORTED
            } else {
                kind
            }
            fail(shown, c.tag)
        }
    }

    companion object {
        private const val MAX_AUTO_RETRIES = 3

        /**
         * Voice typing is offered in a field: not in password fields (nothing spoken there should go to a speech
         * service), not where the host has no text model, and not where the app asked keyboards for no microphone
         * (`EditorInfo.privateImeOptions` `nm` or `...noMicrophoneKey`, the convention keyboards share).
         */
        fun fieldAllowsVoice(profile: InputProfile, privateImeOptions: String?): Boolean {
            if (profile.kind == FieldKind.PASSWORD || profile.noTextModel) return false
            val opts = privateImeOptions?.split(',')?.map { it.trim() }.orEmpty()
            return opts.none { it == "nm" || it.endsWith(".noMicrophoneKey") || it.endsWith(".noMicrophone") }
        }
    }
}
