package com.qalsmartkeyboard.ime.ai

import com.qalsmartkeyboard.ime.FieldTyping
import com.qalsmartkeyboard.ime.InputProfile
import com.qalsmartkeyboard.ime.TextSink

/** Where the text in the translate box came from (ADR 0037). */
enum class TranslateSource {
    /** Nothing yet: the box is empty. */
    NONE,

    /** Typed on the keys (or edited after it came from the field). */
    TYPED,

    /** The host field's selection when the panel opened: Insert replaces it (ADR 0031's Replace). */
    SELECTION,

    /** The sentence before the cursor ("Use last sentence", the old Translate chip's target): Insert replaces it. */
    SENTENCE,
}

/** The translate panel's two language lists (they take the key area while open). */
enum class TranslatePicker { NONE, SOURCE, DESTINATION }

/** The output part of the translate panel. */
sealed interface TranslateStep {
    /** Nothing sent (or the last result was reset by an edit). */
    data object Idle : TranslateStep

    /** A request is in flight; Cancel aborts it. */
    class Working(val task: AiTask.Translate) : TranslateStep

    /** The translation. [selected] is -1 for the main result, else the index of an alternative. */
    class Result(val task: AiTask.Translate, val result: AiResult, val selected: Int = -1) : TranslateStep {
        val shownText: String get() = if (selected in result.alternatives.indices) result.alternatives[selected].text else result.text

        /** The language of the shown text: as the server reported it (a catalogue language), else the destination's. */
        val language: String get() = result.language?.takeIf { it == task.destination.language || TranslationLanguages.current.isKnown(it) }
            ?: task.destination.language
    }

    /** [task] is null for a failure decided before a request was built (offline, signed out, nothing to translate). */
    class Failed(val task: AiTask.Translate?, val failure: AiFailure) : TranslateStep
}

/**
 * The translate panel's state machine (ADR 0037, Gboard's translate mode as the benchmark), free of Views so it runs on
 * the JVM. It owns the [direction] (ADR 0031's [TranslationDirection]: one source of truth, also read by AI Assist's
 * Translate). Rules:
 *
 * - **Input:** while the panel is open the keys type into [input] (the same redirect as the AI panels' text boxes,
 *   [fieldTyping]). On open, the host's selection (read on the tap that opened it) prefills the box and Insert then
 *   replaces it; "Use last sentence" ([useLastSentence]) does the same with the sentence before the cursor, the target
 *   the old Translate chip used. Nothing else in the host field is read.
 * - **Explicit send only:** [translate] (the Translate button, or Enter on the keys) and [retry] are the only calls
 *   that send. No translation per keystroke: every call is metered. One request at a time; [cancel] aborts it.
 * - **A new input or direction resets the output:** an edit, a new language or a swap drops the result or error (a
 *   running call is cancelled), so what is shown always belongs to what is in the box.
 * - **Retry:** after a network error, timeout, 503 or `REQUEST_IN_PROGRESS` the same `Idempotency-Key` and text; after
 *   anything else a new key. A local failure (blocked, nothing to translate) reads the box again.
 * - **Never auto-insert:** the result enters the field only through [insert], and nothing presses the host's Send.
 * - **Blocked / locked:** offline, signed out or update needed ([AiGate.block]) and a plan without translation
 *   ([AiAvailability.locks]) show as [blocker] before anything is sent; Translate then shows that reason, sends nothing.
 *
 * User text (the box, results) lives in memory only while the panel is open; nothing here logs or stores it.
 */
class TranslateController(
    private val actions: AiActions,
    private val editor: AiEditor,
    private val post: (() -> Unit) -> Unit,
    /** The Copy button (the service puts a clip marked sensitive, as the toolbar's Copy always did). */
    private val copyToClipboard: (String) -> Unit,
    /** The keyboard language (the Detect source's likely language). */
    private val keyboardLanguage: () -> String,
    prefs: AiLanguagePrefs = AiLanguagePrefs.InMemory(),
    /** Refreshes the cached plan state (throttled; ADR 0007 D40). Called when the panel opens. */
    private val refresh: () -> Unit = {},
    newKey: () -> String = AiRunner::uuid,
    /** A state change the panel must render (not called for plain typing: see [onInputChanged]). */
    private val onChanged: () -> Unit = {},
    /** The text in the box changed (a key): cheap to render. */
    private val onInputChanged: () -> Unit = {},
) {
    private val runner = AiRunner(actions, post, newKey)

    /** The translation language catalogue (ADR 0040): the server's when fetched, else the five keyboard languages. */
    val catalog: TranslationCatalog get() = actions.translationCatalog()

    /** The translation direction (ADR 0031): Detect or an explicit source, ⇄, the destination. */
    val direction = TranslationDirection(prefs, { actions.translationCatalog() }) { actions.destinations() }

    /**
     * The language lists' search (ADR 0040): while [searching], the keys type into it (the list shrinks above them) and
     * the lists show only the languages it matches. Kept while the list is open; cleared when it opens or closes.
     */
    val search = AiTextField(SEARCH_MAX) { onSearchTyped() }

    /** The keys type into [search] (a language list is open and its search field was tapped). */
    var searching: Boolean = false
        private set

    private var lastText = ""
    val input = AiTextField(MAX_TEXT) { onTyped() }

    /** AI is allowed in the focused field ([InputProfile.allowsAi]); otherwise the panel never opens. */
    var allowed: Boolean = false
        private set
    var isOpen: Boolean = false
        private set
    var source: TranslateSource = TranslateSource.NONE
        private set
    var picker: TranslatePicker = TranslatePicker.NONE
        private set
    var step: TranslateStep = TranslateStep.Idle
        private set
    var notice: AiNotice = AiNotice.NONE
        private set

    /** The server's detected source language from the last result while the source is Detect (cleared by an edit). */
    var detected: String? = null
        private set

    /** The sentence before the cursor can be used ("Use last sentence" is offered while the box is empty). */
    var sentenceAvailable: Boolean = false
        private set

    /** What [source] captured from the field (for Replace): the selection, or the sentence and its trailing whitespace. */
    private var captured: AiSource? = null
    private var lastRequest: AiRequest? = null

    val busy: Boolean get() = runner.busy
    val availability: AiAvailability get() = actions.availability

    /** The destination, with the Detect source following the keyboard language. */
    val destination: AiDestination get() = sync().destination

    private fun sync(): TranslationDirection = direction.also { it.keyboardLanguage = keyboardLanguage() }

    /** The source chip: "Detect · Amharic" (the detected or likely language) or the explicit language. */
    fun sourceLabel(detect: String): String = sync().sourceLabel(detect, detected)

    // ------------------------------------------------------------------ lifecycle

    /** A field was focused: the direction starts again (Detect, the app's rule) and any open panel closes. */
    fun onFieldStarted(profile: InputProfile, packageName: String?) {
        val was = isOpen
        reset()
        isOpen = false
        allowed = profile.allowsAi
        if (allowed) direction.start(keyboardLanguage(), actions.appRuleDestination(packageName))
        if (was) onChanged()
    }

    /** The keyboard language changed: the Detect source follows it (and the default destination with it). */
    fun onKeyboardLanguageChanged() {
        direction.keyboardLanguage = keyboardLanguage()
        if (isOpen) onChanged()
    }

    /**
     * Opens the panel (the Translate shortcut, tile or AI-row chip). The open composition is finished in the host field
     * first; the host's selection, if any, prefills the box. Sends nothing.
     */
    fun open() {
        if (!allowed || isOpen) return
        reset()
        refresh()
        editor.commitComposition() // before the redirect starts: the composition stays in the host field
        sync()
        val sel = editor.selectedText()
        if (!sel.isNullOrBlank()) {
            captured = AiSource.Selection(sel)
            setInput(sel)
            source = TranslateSource.SELECTION
        } else {
            sentenceAvailable = editor.textBeforeCursor(AiTextTarget.LOOKBACK)?.let(AiTextTarget::sentenceBeforeCursor) != null
        }
        isOpen = true
        onChanged()
    }

    /** Closes the panel: a running call is cancelled and the texts are dropped. The direction is kept for this field. */
    fun close() {
        if (!isOpen) return
        reset()
        isOpen = false
        onChanged()
    }

    private fun reset() {
        runner.cancel()
        setInput("")
        source = TranslateSource.NONE
        captured = null
        picker = TranslatePicker.NONE
        endSearch()
        step = TranslateStep.Idle
        notice = AiNotice.NONE
        detected = null
        sentenceAvailable = false
        lastRequest = null
    }

    /** Replaces the box's text without treating it as an edit. */
    private fun setInput(text: String) {
        lastText = text
        input.set(text)
    }

    // ------------------------------------------------------------------ the box

    /** Every key that reaches the box. Only a real change of the text counts as an edit. */
    private fun onTyped() {
        val text = input.text
        if (text == lastText) return
        lastText = text
        val c = captured
        if (c != null && text != c.text) {
            // Edited away from what came from the field: Insert no longer replaces it.
            captured = null
            source = TranslateSource.TYPED
        }
        if (source == TranslateSource.NONE && text.isNotEmpty()) source = TranslateSource.TYPED
        if (text.isEmpty() && captured == null) source = TranslateSource.NONE
        detected = null
        notice = AiNotice.NONE
        if (step !is TranslateStep.Idle) {
            outdated()
            onChanged()
        } else {
            onInputChanged()
        }
    }

    /** The shown output no longer matches the box or the direction: back to Idle (a running call is cancelled). */
    private fun outdated() {
        if (busy) runner.cancel()
        step = TranslateStep.Idle
        lastRequest = null
        notice = AiNotice.NONE
    }

    /** The × in the box: empties it and drops the output. */
    fun clearInput() {
        if (!isOpen) return
        runner.cancel()
        setInput("")
        source = TranslateSource.NONE
        captured = null
        step = TranslateStep.Idle
        lastRequest = null
        notice = AiNotice.NONE
        detected = null
        onChanged()
    }

    /** "Use last sentence": the sentence before the cursor goes into the box (read now, on the tap); Insert replaces it. */
    fun useLastSentence() {
        if (!isOpen) return
        val s = editor.textBeforeCursor(AiTextTarget.LOOKBACK)?.let(AiTextTarget::sentenceBeforeCursor)
        if (s == null) {
            sentenceAvailable = false
            step = TranslateStep.Failed(null, AiFailure.NothingToProcess)
            onChanged()
            return
        }
        outdated()
        captured = s
        setInput(s.text)
        source = TranslateSource.SENTENCE
        detected = null
        onChanged()
    }

    /** Insert replaces what came from the field (the selection or the sentence) while the box still holds it unchanged. */
    val replacesFieldText: Boolean get() = captured != null && input.text == captured?.text

    // ------------------------------------------------------------------ languages

    fun openPicker(p: TranslatePicker) {
        if (!isOpen) return
        refresh()
        // The keys give way to the list: an open composition is finished into the box (or the search) first.
        editor.commitComposition()
        picker = if (picker == p) TranslatePicker.NONE else p
        endSearch()
        onChanged()
    }

    fun closePicker() {
        if (picker == TranslatePicker.NONE) return
        editor.commitComposition()
        picker = TranslatePicker.NONE
        endSearch()
        onChanged()
    }

    /** The source list for the current search: null is Detect (shown only without a query). */
    fun sourceChoices(): List<String?> = sync().sourceChoices(search.text)

    /** The destination list for the current search. */
    fun destinationChoices(): List<AiDestination> = sync().destinationChoices(search.text)

    /** The list's search field was tapped: the keys type into it (the service shows them under a shorter list). */
    fun startSearch() {
        if (picker == TranslatePicker.NONE || searching) return
        searching = true
        onChanged()
    }

    /** Enter or the emoji key in the search: the keys give way to the (filtered) list again; the query stays. */
    fun stopSearchTyping() {
        if (!searching) return
        editor.commitComposition()
        searching = false
        onChanged()
    }

    /** The search field's ×: the query is emptied (typing goes on if it was on). */
    fun clearSearch() {
        if (search.text.isEmpty()) return
        search.clear()
    }

    private fun endSearch() {
        searching = false
        if (search.text.isNotEmpty()) search.set("")
    }

    private fun onSearchTyped() {
        if (picker != TranslatePicker.NONE) onChanged()
    }

    /** A language of the source list, or null for Detect. */
    fun chooseSource(language: String?) {
        editor.commitComposition()
        sync().chooseSource(language)
        picker = TranslatePicker.NONE
        endSearch()
        detected = null
        directionChanged()
    }

    /** A destination of the list (remembered for the source language, device-local). */
    fun chooseDestination(d: AiDestination) {
        editor.commitComposition()
        sync().chooseDestination(d)
        picker = TranslatePicker.NONE
        endSearch()
        directionChanged()
    }

    /** ⇄: the direction turns around (the detected source counts as the old source). */
    fun swap() {
        if (!isOpen) return
        sync().swap(detected)
        detected = null
        picker = TranslatePicker.NONE
        directionChanged()
    }

    private fun directionChanged() {
        if (step !is TranslateStep.Idle) outdated()
        onChanged()
    }

    // ------------------------------------------------------------------ sending (explicit taps)

    /** Why nothing can be sent right now (shown as one line with its link), or null. Reads cached state only. */
    val blocker: AiFailure?
        get() {
            val a = availability
            return AiGate.block(a) ?: if (a.locks(AiTaskKind.TRANSLATE)) AiFailure.NotEntitled else null
        }

    /** The Translate button is enabled: text in the box, within the limit, nothing running, not blocked (offline may retry). */
    val canTranslate: Boolean
        get() = isOpen && !busy && !input.isBlank && !input.overLimit && blocker.let { it == null || it == AiFailure.Offline }

    /** The Translate button (or Enter on the keys). */
    fun translate() {
        if (!isOpen || busy) return
        refresh()
        // The open composition is finished into the box first (the keys type into it while the panel is open).
        editor.commitComposition()
        picker = TranslatePicker.NONE
        notice = AiNotice.NONE
        blocker?.let {
            step = TranslateStep.Failed(null, it)
            lastRequest = null
            onChanged()
            return
        }
        when {
            input.isBlank -> fail(AiFailure.NothingToProcess)
            input.overLimit -> fail(AiFailure.TooLong)
            else -> send(AiRequest(runner.key(), AiTask.Translate(destination, direction.manualSource), input.text))
        }
    }

    private fun fail(f: AiFailure) {
        lastRequest = null
        step = TranslateStep.Failed(null, f)
        onChanged()
    }

    private fun send(request: AiRequest) {
        val task = request.task as AiTask.Translate
        lastRequest = request
        step = TranslateStep.Working(task)
        onChanged()
        runner.start(request) { outcome ->
            step = when (outcome) {
                is AiOutcome.Success -> {
                    if (direction.isAuto) detected = outcome.result.sourceLanguage?.takeIf { catalog.isKnown(it) }
                    TranslateStep.Result(task, outcome.result)
                }
                is AiOutcome.Failure -> TranslateStep.Failed(task, outcome.failure)
            }
            onChanged()
        }
    }

    /** Retry after a failure: the same key and text when the first attempt may have reached the server, else a new key. */
    fun retry() {
        val f = step as? TranslateStep.Failed ?: return
        if (busy) return
        val prev = lastRequest
        if (prev == null || f.task == null) {
            translate()
            return
        }
        blocker?.let {
            step = TranslateStep.Failed(prev.task as AiTask.Translate, it)
            onChanged()
            return
        }
        send(AiRequest(if (f.failure.retrySameKey) prev.requestId else runner.key(), prev.task, prev.text))
    }

    /** Cancel while working: the call is aborted; the box keeps its text for another try. */
    fun cancel() {
        if (!busy && step !is TranslateStep.Working) return
        runner.cancel()
        step = TranslateStep.Idle
        lastRequest = null
        onChanged()
    }

    /** Enter on the keys: translates; on a result, inserts it. Never reaches the host's Enter or Send. */
    fun onEnter() {
        when (step) {
            is TranslateStep.Result -> insert()
            is TranslateStep.Working -> Unit
            else -> if (!input.isBlank) translate()
        }
    }

    fun selectAlternative(index: Int) {
        val r = step as? TranslateStep.Result ?: return
        step = TranslateStep.Result(r.task, r.result, if (index in r.result.alternatives.indices) index else -1)
        notice = AiNotice.NONE
        onChanged()
    }

    // ------------------------------------------------------------------ applying

    /** Copies the shown translation. */
    fun copy() {
        val r = step as? TranslateStep.Result ?: return
        copyToClipboard(r.shownText)
        notice = AiNotice.COPIED
        onChanged()
    }

    /**
     * Puts the shown translation into the field: it replaces the selection or sentence it came from (if the field still
     * reads the same; else it is inserted at the cursor, [AiApplied.INSERTED_ORIGINAL_CHANGED]), or is inserted at the
     * cursor. Then the box and the output are cleared for the next translation; the panel stays open (translate mode).
     */
    fun insert(): AiApplied {
        val r = step as? TranslateStep.Result ?: return AiApplied.FAILED
        val text = r.shownText
        val src = captured?.takeIf { replacesFieldText }
        val replaced = when (src) {
            is AiSource.Selection -> editor.replaceSelection(src.text, text)
            is AiSource.Sentence -> editor.replaceBeforeCursor(src.text + src.trailing, text + src.trailing)
            null -> false
        }
        val applied = when {
            replaced -> AiApplied.REPLACED
            editor.insertAtCursor(text) -> if (src != null) AiApplied.INSERTED_ORIGINAL_CHANGED else AiApplied.INSERTED
            else -> AiApplied.FAILED
        }
        if (applied == AiApplied.FAILED) {
            notice = AiNotice.INSERT_FAILED
        } else {
            setInput("")
            source = TranslateSource.NONE
            captured = null
            step = TranslateStep.Idle
            lastRequest = null
            detected = null
            sentenceAvailable = false
            notice = AiNotice.NONE
        }
        onChanged()
        return applied
    }

    // ------------------------------------------------------------------ typing redirect

    /** Ends translate mode from the keys (the emoji key): the service closes the panel and opens the emoji panel. */
    var onLeaveByKey: () -> Unit = {}

    private val typingAdapter = object : FieldTyping {
        override val sink: TextSink get() = input.sink
        override val newlineOnEnter: Boolean get() = false
        override fun submit() = onEnter()
        override fun done() = onLeaveByKey()
    }

    private val searchAdapter = object : FieldTyping {
        override val sink: TextSink get() = search.sink
        override val newlineOnEnter: Boolean get() = false
        override fun submit() = stopSearchTyping()
        override fun done() = stopSearchTyping()
    }

    /**
     * For `KeyboardController.fieldTyping`: the box while the panel is open, the list's search while it is being typed
     * (ADR 0040); nothing while a language list is shown without its search.
     */
    fun fieldTyping(): FieldTyping? = when {
        !isOpen -> null
        picker == TranslatePicker.NONE -> typingAdapter
        searching -> searchAdapter
        else -> null
    }

    /** The system Back: a language list closes first, then the panel. False when it was not open. */
    fun back(): Boolean {
        if (!isOpen) return false
        if (picker != TranslatePicker.NONE) closePicker() else close()
        return true
    }

    companion object {
        /** `TranslateRequest.text` (characters). */
        const val MAX_TEXT = AiTextTarget.MAX_CHARS

        /** The language search's length (a language name is short). */
        const val SEARCH_MAX = 40
    }
}
