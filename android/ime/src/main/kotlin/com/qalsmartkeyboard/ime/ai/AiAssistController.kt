package com.qalsmartkeyboard.ime.ai

/** How an Assist action picks its output language (ADR 0031). */
enum class AiOutputRule {
    /** Translate: the preferred destination; any language. */
    DESTINATION,

    /** Explain, Summarise, Reply: the keyboard language; any language. */
    KEYBOARD,

    /** Rewrite, Make professional, Simplify: the language of the text ("Same as text"); may be changed. */
    SAME_BY_DEFAULT,

    /** Fix grammar: always the language of the text (the server refuses another one). */
    FIXED_SAME,
}

/** The eight actions of the AI Assist panel, in chip order, with what they are metered as. */
enum class AiAssistAction(val kind: AiTaskKind, val output: AiOutputRule, val regenerable: Boolean) {
    TRANSLATE(AiTaskKind.TRANSLATE, AiOutputRule.DESTINATION, false),
    EXPLAIN(AiTaskKind.EXPLAIN, AiOutputRule.KEYBOARD, false),
    GRAMMAR(AiTaskKind.GRAMMAR, AiOutputRule.FIXED_SAME, false),
    REWRITE(AiTaskKind.REWRITE, AiOutputRule.SAME_BY_DEFAULT, true),
    PROFESSIONAL(AiTaskKind.TONE, AiOutputRule.SAME_BY_DEFAULT, true),
    SIMPLIFY(AiTaskKind.REWRITE, AiOutputRule.SAME_BY_DEFAULT, true),
    REPLY(AiTaskKind.REPLY, AiOutputRule.KEYBOARD, true),
    SUMMARIZE(AiTaskKind.SUMMARIZE, AiOutputRule.KEYBOARD, false),
    ;

    companion object {
        /**
         * The request of [action] on [text] with output language [output] (null: same as the text). Regenerate sends a
         * higher [variation] (the contract's `variation`) with a new `Idempotency-Key`, for transforms and replies.
         */
        fun task(action: AiAssistAction, output: String?, note: String = "", variation: Int = 0): AiTask = when (action) {
            TRANSLATE -> AiTask.Translate(AiLanguages.plain(output ?: "en"))
            EXPLAIN -> AiTask.Explain(output ?: "en", variation)
            GRAMMAR -> AiTask.Grammar
            REWRITE -> AiTask.Rewrite(null, output, variation)
            PROFESSIONAL -> AiTask.Rewrite(AiTone.PROFESSIONAL, output, variation)
            SIMPLIFY -> AiTask.Simplify(output, variation)
            REPLY -> AiTask.Reply(output ?: "en", note.trim(), REPLY_COUNT, variation)
            SUMMARIZE -> AiTask.Summarize(output ?: "en", AiLength.MEDIUM, variation)
        }

        const val REPLY_COUNT = 3
    }
}

/** Where the Assist panel's text came from. */
enum class AiSourceKind { NONE, SELECTION, CLIPBOARD, TYPED }

/** The Assist panel's text fields that the keys can type into. */
enum class AiAssistField { SOURCE, NOTE }

/** Why Run cannot send yet (shown under the button). */
enum class AiAssistProblem { NO_TEXT, TEXT_TOO_LONG, NOTE_TOO_LONG }

/** A short confirmation shown in the panel after an action. */
enum class AiNotice { NONE, COPIED, COPY_FAILED, CLIPBOARD_EMPTY, INSERT_FAILED }

/** The step of the Assist panel. */
sealed interface AiAssistStep {
    data object Compose : AiAssistStep

    class Working(val action: AiAssistAction, val output: String?) : AiAssistStep

    /** [selected]: the reply suggestion picked (replies only). */
    class Result(val action: AiAssistAction, val output: String?, val result: AiResult, val selected: Int = 0) : AiAssistStep {
        val shownText: String get() = result.suggestions.getOrNull(selected) ?: result.text
    }

    class Failed(val action: AiAssistAction?, val failure: AiFailure) : AiAssistStep
}

/**
 * The AI Assist panel's state machine (ADR 0031), free of Views. Rules:
 *
 * - **Source priority:** the host's selection when the panel opens (read on the user's tap that opened it); else the
 *   user pastes the clipboard ([pasteFromClipboard], offered only when the clip's description says it holds text and it
 *   is not marked sensitive, read only on that tap) or types into the panel's text box. Nothing reads the clipboard
 *   content before the tap.
 * - **Explicit send only:** [run], [regenerate] and [retry] are the only calls that send, each on a tap. One request at
 *   a time; [cancel] aborts it (the HTTP call is cancelled) and returns to editing with everything kept.
 * - **Output language** per action ([AiOutputRule]); a choice the user makes is kept for that action while the panel
 *   is open. Grammar's cannot be changed.
 * - **Plan locks:** an action the cached plan state does not include (or that the server refused with
 *   `FEATURE_NOT_ENTITLED` in this session) shows the upgrade link instead of sending.
 * - **Never auto-insert:** the result enters the host field only through [insert].
 *
 * User text (the source, the note, results) lives only in memory while the panel is open; nothing is logged.
 */
class AiAssistController(
    private val actions: AiActions,
    private val editor: AiEditor,
    private val clipboard: AiClipboard,
    private val post: (() -> Unit) -> Unit,
    /** The keyboard language (the default output of Explain, Summarise and Reply). */
    private val keyboardLanguage: () -> String,
    /** The translation destination language (the direction control's destination). */
    private val translateDefault: () -> String,
    newKey: () -> String = AiRunner::uuid,
    private val onChanged: () -> Unit = {},
) {
    private val runner = AiRunner(actions, post, newKey)

    val source = AiTextField(MAX_TEXT) { onChanged() }
    val note = AiTextField(MAX_NOTE) { onChanged() }

    var sourceKind: AiSourceKind = AiSourceKind.NONE
        private set
    var clip: ClipAvailability = ClipAvailability.NONE
        private set
    var action: AiAssistAction = AiAssistAction.TRANSLATE
        private set
    var step: AiAssistStep = AiAssistStep.Compose
        private set
    var typing: AiAssistField? = null
        private set
    var previewExpanded: Boolean = false
        private set
    var outputPickerOpen: Boolean = false
        private set
    var notice: AiNotice = AiNotice.NONE
        private set

    private val picked = HashMap<AiAssistAction, String?>()
    private val sessionLocks = HashSet<AiTaskKind>()

    /** The account [sessionLocks] were learned for ([AiAvailability.accountGeneration]); another account forgets them. */
    private var locksGeneration = 0L
    private var selection: String? = null
    private var lastRequest: AiRequest? = null
    private var lastAction: AiAssistAction? = null
    private var lastOutput: String? = null

    val availability: AiAvailability get() = actions.availability
    val busy: Boolean get() = runner.busy

    /** Opens the panel: the host's selection (if any) becomes the text; the clipboard's description is checked. */
    fun open() {
        reset()
        editor.commitComposition()
        val sel = editor.selectedText()
        if (!sel.isNullOrBlank()) {
            selection = sel
            source.set(sel)
            sourceKind = AiSourceKind.SELECTION
        }
        clip = clipboard.availability()
        onChanged()
    }

    /** Closes the panel: a running call is cancelled and every text is dropped. */
    fun close() {
        reset()
    }

    private fun reset() {
        runner.cancel()
        source.set("")
        note.set("")
        sourceKind = AiSourceKind.NONE
        selection = null
        step = AiAssistStep.Compose
        typing = null
        previewExpanded = false
        outputPickerOpen = false
        notice = AiNotice.NONE
        picked.clear()
        lastRequest = null
        lastAction = null
    }

    // ------------------------------------------------------------------ source

    /** The user tapped "Paste from clipboard": the only place the clip's content is read. */
    fun pasteFromClipboard() {
        if (clip != ClipAvailability.TEXT) return
        val text = clipboard.readText()
        if (text.isNullOrBlank()) {
            clip = clipboard.availability()
            notice = AiNotice.CLIPBOARD_EMPTY
        } else {
            source.set(text)
            sourceKind = AiSourceKind.CLIPBOARD
            selection = null
            notice = AiNotice.NONE
        }
        onChanged()
    }

    /** Empties the text box (the paste and type options come back). */
    fun clearSource() {
        source.clear()
        sourceKind = AiSourceKind.NONE
        selection = null
        previewExpanded = false
        clip = clipboard.availability()
        notice = AiNotice.NONE
        onChanged()
    }

    fun togglePreview() {
        previewExpanded = !previewExpanded
        onChanged()
    }

    /** The keys now type into [field] (the panel gives way to the keys and a field bar). */
    fun startTyping(field: AiAssistField) {
        if (step !is AiAssistStep.Compose) return
        typing = field
        if (field == AiAssistField.SOURCE && sourceKind == AiSourceKind.NONE) sourceKind = AiSourceKind.TYPED
        notice = AiNotice.NONE
        onChanged()
    }

    /** Typing is done (Done, Enter on a one-line field, Back): the panel is back. */
    fun endTyping() {
        if (typing == null) return
        typing = null
        if (sourceKind == AiSourceKind.TYPED && source.isBlank) sourceKind = AiSourceKind.NONE
        clip = clipboard.availability()
        onChanged()
    }

    /** The field the keys type into, or null. */
    val typingField: AiTextField? get() = when (typing) {
        AiAssistField.SOURCE -> source
        AiAssistField.NOTE -> note
        null -> null
    }

    // ------------------------------------------------------------------ action and output language

    fun selectAction(a: AiAssistAction) {
        if (busy) return
        action = a
        outputPickerOpen = false
        notice = AiNotice.NONE
        if (step !is AiAssistStep.Compose) step = AiAssistStep.Compose
        onChanged()
    }

    /** The output language of [a]: the user's choice in this panel, else the action's default. Null: same as the text. */
    fun outputLanguage(a: AiAssistAction = action): String? = if (picked.containsKey(a)) picked[a] else defaultOutput(a)

    fun defaultOutput(a: AiAssistAction): String? = when (a.output) {
        AiOutputRule.DESTINATION -> translateDefault().takeIf { actions.translationCatalog().isDestination(it) } ?: "en"
        AiOutputRule.KEYBOARD -> keyboardLanguage().takeIf { it in AiLanguages.ALL } ?: "en"
        AiOutputRule.SAME_BY_DEFAULT, AiOutputRule.FIXED_SAME -> null
    }

    fun canChooseOutput(a: AiAssistAction = action): Boolean = a.output != AiOutputRule.FIXED_SAME

    /**
     * The choices of the output picker (null = "Same as text"). Translate (ADR 0040) offers the translation catalogue's
     * destinations: the current one, the keyboard languages and the recent ones first ([SHORT_LIST] of them until "More
     * languages" shows all, [moreOutputs]); the LLM actions write in the keyboard's languages.
     */
    fun outputChoices(a: AiAssistAction = action): List<String?> = when (a.output) {
        AiOutputRule.SAME_BY_DEFAULT -> listOf<String?>(null) + AiLanguages.ALL
        AiOutputRule.FIXED_SAME -> listOf(null)
        AiOutputRule.DESTINATION -> translationOutputs(a).let { if (showAllOutputs) it else it.take(SHORT_LIST) }
        else -> AiLanguages.ALL
    }

    /** More translation languages exist than the short list shows (the picker then ends with "More languages"). */
    fun hasMoreOutputs(a: AiAssistAction = action): Boolean =
        a.output == AiOutputRule.DESTINATION && !showAllOutputs && translationOutputs(a).size > SHORT_LIST

    /** "More languages": the picker lists every translation destination. */
    fun moreOutputs() {
        if (showAllOutputs) return
        showAllOutputs = true
        onChanged()
    }

    private var showAllOutputs = false

    private fun translationOutputs(a: AiAssistAction): List<String> {
        val chosen = outputLanguage(a)
        return TranslationLanguageSearch.order(actions.translationCatalog().destinations, listOfNotNull(chosen, translateDefault(), keyboardLanguage()))
            .map { it.code }
    }

    fun toggleOutputPicker() {
        if (!canChooseOutput()) return
        outputPickerOpen = !outputPickerOpen
        if (!outputPickerOpen) showAllOutputs = false
        onChanged()
    }

    fun chooseOutput(language: String?) {
        val allowed = if (action.output == AiOutputRule.DESTINATION) language != null && actions.translationCatalog().isDestination(language)
        else language in outputChoices()
        if (!canChooseOutput() || !allowed) return
        picked[action] = language
        outputPickerOpen = false
        showAllOutputs = false
        onChanged()
    }

    /** The cached plan state (or this session's refusal) says [a] is not part of the plan. */
    fun isLocked(a: AiAssistAction): Boolean {
        val now = availability
        return now.locks(a.kind) || (a.kind in sessionLocks && locksGeneration == now.accountGeneration)
    }

    /** Why [run] cannot send right now, or null. */
    val problem: AiAssistProblem?
        get() = when {
            source.isBlank -> AiAssistProblem.NO_TEXT
            source.overLimit -> AiAssistProblem.TEXT_TOO_LONG
            action == AiAssistAction.REPLY && note.overLimit -> AiAssistProblem.NOTE_TOO_LONG
            else -> null
        }

    // ------------------------------------------------------------------ sending (explicit taps)

    /** The Run button: sends the selected action on the text. */
    fun run() {
        if (busy || typing != null) return
        val a = action
        if (isLocked(a)) {
            step = AiAssistStep.Failed(a, AiFailure.NotEntitled)
            onChanged()
            return
        }
        AiGate.block(availability)?.let {
            step = AiAssistStep.Failed(a, it)
            onChanged()
            return
        }
        if (problem != null) {
            onChanged()
            return
        }
        val output = outputLanguage(a)
        send(a, output, AiRequest(runner.key(), AiAssistAction.task(a, output, note.text, 0), source.text))
    }

    private fun send(a: AiAssistAction, output: String?, request: AiRequest) {
        lastRequest = request
        lastAction = a
        lastOutput = output
        notice = AiNotice.NONE
        outputPickerOpen = false
        step = AiAssistStep.Working(a, output)
        onChanged()
        runner.start(request) { outcome ->
            step = when (outcome) {
                is AiOutcome.Success -> AiAssistStep.Result(a, output, outcome.result)
                is AiOutcome.Failure -> {
                    if (outcome.failure == AiFailure.NotEntitled) {
                        val generation = availability.accountGeneration
                        if (generation != locksGeneration) sessionLocks.clear()
                        locksGeneration = generation
                        sessionLocks += a.kind
                    }
                    AiAssistStep.Failed(a, outcome.failure)
                }
            }
            onChanged()
        }
    }

    /** Another version (Rewrite, Make professional, Simplify, Summarise, Explain, Reply): a new key and the next `variation`. */
    fun regenerate() {
        val r = step as? AiAssistStep.Result ?: return
        val prev = lastRequest ?: return
        if (busy || !r.action.regenerable) return
        AiGate.block(availability)?.let {
            step = AiAssistStep.Failed(r.action, it)
            onChanged()
            return
        }
        val task = when (val t = prev.task) {
            is AiTask.Reply -> t.copy(variation = AiEmailRules.nextVariation(t.variation))
            is AiTask.Rewrite -> t.copy(variation = AiEmailRules.nextVariation(t.variation))
            is AiTask.Simplify -> t.copy(variation = AiEmailRules.nextVariation(t.variation))
            is AiTask.Explain -> t.copy(variation = AiEmailRules.nextVariation(t.variation))
            is AiTask.Summarize -> t.copy(variation = AiEmailRules.nextVariation(t.variation))
            else -> t
        }
        send(r.action, r.output, AiRequest(runner.key(), task, prev.text))
    }

    /** Retry after a failure: the same key when the first attempt may have reached the server, else a new one. */
    fun retry() {
        val f = step as? AiAssistStep.Failed ?: return
        val prev = lastRequest
        val a = lastAction
        if (busy) return
        if (prev == null || a == null || f.failure == AiFailure.NotEntitled) {
            step = AiAssistStep.Compose
            run()
            return
        }
        AiGate.block(availability)?.let {
            step = AiAssistStep.Failed(a, it)
            onChanged()
            return
        }
        send(a, lastOutput, AiRequest(if (f.failure.retrySameKey) prev.requestId else runner.key(), prev.task, prev.text))
    }

    /** Cancel while working: the call is aborted; the inputs stay for another try. */
    fun cancel() {
        runner.cancel()
        step = AiAssistStep.Compose
        onChanged()
    }

    /** From a result or an error back to the inputs. */
    fun backToEdit() {
        if (busy) runner.cancel()
        step = AiAssistStep.Compose
        notice = AiNotice.NONE
        onChanged()
    }

    fun selectSuggestion(index: Int) {
        val r = step as? AiAssistStep.Result ?: return
        if (index !in r.result.suggestions.indices) return
        step = AiAssistStep.Result(r.action, r.output, r.result, index)
        onChanged()
    }

    // ------------------------------------------------------------------ applying

    /** The source is the host's selection, unchanged, so "Replace selection" is offered. */
    val canReplaceSelection: Boolean
        get() = sourceKind == AiSourceKind.SELECTION && selection != null && source.text == selection

    /**
     * Commits the shown result: with [replace] it replaces the selection it came from (if it still reads the same), else
     * it is inserted at the cursor (after the selection, if any).
     */
    fun insert(replace: Boolean): AiApplied {
        val r = step as? AiAssistStep.Result ?: return AiApplied.FAILED
        val text = r.shownText
        editor.commitComposition()
        val applied = if (replace && canReplaceSelection) {
            val sel = selection ?: return AiApplied.FAILED
            if (editor.replaceSelection(sel, text)) AiApplied.REPLACED
            else if (editor.insertAtCursor(text)) AiApplied.INSERTED_ORIGINAL_CHANGED else AiApplied.FAILED
        } else {
            if (editor.insertAtCursor(text)) AiApplied.INSERTED else AiApplied.FAILED
        }
        if (applied == AiApplied.FAILED) {
            notice = AiNotice.INSERT_FAILED
            onChanged()
        }
        return applied
    }

    /** Copies the shown result (a plain clip: the user asked for it). */
    fun copy(): Boolean {
        val r = step as? AiAssistStep.Result ?: return false
        val ok = clipboard.copy(r.shownText)
        notice = if (ok) AiNotice.COPIED else AiNotice.COPY_FAILED
        onChanged()
        return ok
    }

    companion object {
        /** `TransformRequest.text`, `TranslateRequest.text` and `RepliesRequest.contextText` (characters). */
        const val MAX_TEXT = 5000

        /** `RepliesRequest.instructions`. */
        const val MAX_NOTE = 1000

        /** Translate's output picker shows this many languages before "More languages" (ADR 0040). */
        const val SHORT_LIST = 8
    }
}
