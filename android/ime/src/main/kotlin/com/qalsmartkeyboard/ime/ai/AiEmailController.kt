package com.qalsmartkeyboard.ime.ai

/** The Email panel's text fields that the keys can type into. */
enum class AiEmailField(val multiline: Boolean) {
    INSTRUCTIONS(true), ORIGINAL(true), DRAFT(true), RECIPIENT(false), SENDER(false), SUBJECT(false), BODY(true),
}

/** The Email panel's steps: Compose → (Working) → Preview, or an error. */
sealed interface AiEmailStep {
    data object Compose : AiEmailStep
    data object Working : AiEmailStep
    data object Preview : AiEmailStep
    class Failed(val failure: AiFailure) : AiEmailStep
}

/**
 * The keyboard's "Write Email with AI" panel (ADR 0031), free of Views. A step flow inside the panel: **Compose** (mode
 * New / Reply / Improve, what to say, the e-mail received or the draft, tone, length, language, names) then **Preview**
 * (subject and body with the `[placeholders]` to fill in, Regenerate, Edit, Insert, Copy).
 *
 * - The e-mail received (Reply) comes from the host's selection or the clipboard (same rules as Assist: description
 *   only until the user taps Paste; a sensitive clip is never offered). The draft (Improve) is read from the host field
 *   only on the tap "Use text from this field".
 * - Only [generate], [regenerate] and [retry] send, each on a tap; [cancel] aborts the call.
 * - Insert puts the body in the host field (and the subject as a "Subject:" line only when the user turned that on: the
 *   keyboard cannot reach a separate subject field); Copy subject / Copy body / Copy all are separate.
 * - The user's own name is remembered on the device for the sign-off ([AiLanguagePrefs.senderName]).
 */
class AiEmailController(
    private val actions: AiActions,
    private val editor: AiEditor,
    private val clipboard: AiClipboard,
    private val prefs: AiLanguagePrefs,
    private val post: (() -> Unit) -> Unit,
    newKey: () -> String = AiRunner::uuid,
    private val onChanged: () -> Unit = {},
) {
    private val runner = AiRunner(actions, post, newKey)

    val instructions = AiTextField(AiEmailRules.MAX_INSTRUCTIONS) { onChanged() }
    val original = AiTextField(AiEmailRules.MAX_EMAIL) { onChanged() }
    val draft = AiTextField(AiEmailRules.MAX_EMAIL) { onChanged() }
    val recipient = AiTextField(AiEmailRules.MAX_NAME) { onChanged() }
    val sender = AiTextField(AiEmailRules.MAX_NAME) { onChanged() }
    val subject = AiTextField(1000) { onChanged() }
    val body = AiTextField(AiTextField.HARD_CAP) { onChanged() }

    var mode: AiEmailMode = AiEmailMode.NEW
        private set
    var tone: AiEmailTone = AiEmailTone.PROFESSIONAL
        private set
    var length: AiLength = AiLength.MEDIUM
        private set
    var language: String = "en"
        private set
    var step: AiEmailStep = AiEmailStep.Compose
        private set
    var typing: AiEmailField? = null
        private set
    /** The last Generate tap found this problem (shown under the button until the input changes). */
    var problem: AiEmailProblem? = null
        private set
    var clip: ClipAvailability = ClipAvailability.NONE
        private set
    /** Insert also puts a "Subject: ..." line first. Off by default (most mail apps have their own subject field). */
    var insertSubject: Boolean = false
        private set
    var notice: AiNotice = AiNotice.NONE
        private set
    /** The host had a selection when the panel opened (offered as the e-mail received). */
    val hasSelection: Boolean get() = selection != null
    /** The draft was taken from the host field ("Use text from this field"): Insert replaces it there. */
    val draftFromField: Boolean get() = fieldDraft != null && draft.text == fieldDraft
    var result: AiResult? = null
        private set
    var showDetails: Boolean = false
        private set

    private var selection: String? = null
    private var fieldDraft: String? = null
    private var lastRequest: AiRequest? = null
    private var sessionLocked = false

    /** The account [sessionLocked] was learned for ([AiAvailability.accountGeneration]); another account forgets it. */
    private var lockGeneration = 0L
    private var regenerating = false

    val availability: AiAvailability get() = actions.availability
    val busy: Boolean get() = runner.busy
    val isLocked: Boolean get() {
        val now = availability
        return now.locks(AiTaskKind.EMAIL) || (sessionLocked && lockGeneration == now.accountGeneration)
    }

    /** Opens the panel (from the AI row); [prefill] is a spec to start from (e.g. coming back from the full editor). */
    fun open(prefill: AiEmailSpec? = null) {
        reset()
        editor.commitComposition()
        selection = editor.selectedText()?.takeIf { it.isNotBlank() }
        clip = clipboard.availability()
        sender.set(prefs.senderName)
        if (prefill != null) apply(prefill)
        onChanged()
    }

    fun close() = reset()

    private fun reset() {
        runner.cancel()
        listOf(instructions, original, draft, recipient, sender, subject, body).forEach { it.set("") }
        mode = AiEmailMode.NEW
        tone = AiEmailTone.PROFESSIONAL
        length = AiLength.MEDIUM
        language = "en"
        step = AiEmailStep.Compose
        typing = null
        problem = null
        insertSubject = false
        notice = AiNotice.NONE
        selection = null
        fieldDraft = null
        lastRequest = null
        result = null
        showDetails = false
    }

    private fun apply(spec: AiEmailSpec) {
        mode = spec.mode
        language = spec.language.takeIf { it in AiLanguages.ALL } ?: "en"
        tone = spec.tone
        length = spec.length
        instructions.set(spec.instructions)
        original.set(spec.originalEmail)
        draft.set(spec.draft)
        recipient.set(spec.recipientName)
        if (spec.senderName.isNotBlank()) sender.set(spec.senderName)
    }

    // ------------------------------------------------------------------ inputs

    fun setMode(m: AiEmailMode) {
        if (busy || m == mode) return
        mode = m
        problem = null
        step = AiEmailStep.Compose
        onChanged()
    }

    fun chooseTone(t: AiEmailTone) { tone = t; onChanged() }

    fun chooseLength(l: AiLength) { length = l; onChanged() }

    fun chooseLanguage(code: String) {
        if (code !in AiLanguages.ALL) return
        language = code
        onChanged()
    }

    fun toggleDetails() { showDetails = !showDetails; onChanged() }

    /** Reply: the host's selection (read when the panel opened, on the user's tap) is the e-mail received. */
    fun useSelectionAsOriginal() {
        val s = selection ?: return
        original.set(s)
        problem = null
        onChanged()
    }

    /** Reply: the clipboard's text is the e-mail received (read on this tap only). */
    fun pasteOriginal() = paste(original)

    /** Improve: the clipboard's text is the draft (read on this tap only). */
    fun pasteDraft() {
        if (paste(draft)) fieldDraft = null
    }

    private fun paste(into: AiTextField): Boolean {
        if (clip != ClipAvailability.TEXT) return false
        val text = clipboard.readText()
        return if (text.isNullOrBlank()) {
            clip = clipboard.availability()
            notice = AiNotice.CLIPBOARD_EMPTY
            onChanged()
            false
        } else {
            into.set(text)
            problem = null
            notice = AiNotice.NONE
            onChanged()
            true
        }
    }

    /** Improve: "Use text from this field": the host field's text, read now (on the tap), becomes the draft. */
    fun useFieldTextAsDraft() {
        editor.commitComposition()
        val text = editor.allText(AiEmailRules.MAX_EMAIL * 2)?.takeIf { it.isNotBlank() }
        if (text == null) {
            notice = AiNotice.INSERT_FAILED
        } else {
            draft.set(text)
            fieldDraft = text
            problem = null
            notice = AiNotice.NONE
        }
        onChanged()
    }

    fun clearField(f: AiEmailField) {
        field(f).clear()
        if (f == AiEmailField.DRAFT) fieldDraft = null
        clip = clipboard.availability()
        onChanged()
    }

    fun field(f: AiEmailField): AiTextField = when (f) {
        AiEmailField.INSTRUCTIONS -> instructions
        AiEmailField.ORIGINAL -> original
        AiEmailField.DRAFT -> draft
        AiEmailField.RECIPIENT -> recipient
        AiEmailField.SENDER -> sender
        AiEmailField.SUBJECT -> subject
        AiEmailField.BODY -> body
    }

    fun startTyping(f: AiEmailField) {
        if (busy) return
        val inPreview = f == AiEmailField.SUBJECT || f == AiEmailField.BODY
        if (inPreview != (step == AiEmailStep.Preview)) return
        typing = f
        problem = null
        notice = AiNotice.NONE
        onChanged()
    }

    fun endTyping() {
        if (typing == null) return
        typing = null
        clip = clipboard.availability()
        onChanged()
    }

    val typingField: AiTextField? get() = typing?.let(::field)

    /** What would be sent now (also what "Open full editor" hands over). */
    fun spec(variation: Int = 0): AiEmailSpec = AiEmailSpec(
        mode = mode,
        language = language,
        instructions = instructions.text.trim(),
        originalEmail = if (mode == AiEmailMode.REPLY) original.text.trim() else "",
        draft = if (mode == AiEmailMode.IMPROVE) draft.text.trim() else "",
        tone = tone,
        length = length,
        includeSubject = true,
        recipientName = recipient.text.trim(),
        senderName = sender.text.trim(),
        variation = variation,
    )

    // ------------------------------------------------------------------ sending (explicit taps)

    fun generate() {
        if (busy || typing != null) return
        if (isLocked) return fail(AiFailure.NotEntitled)
        AiGate.block(availability)?.let { return fail(it) }
        val s = spec()
        problem = AiEmailRules.validate(s)
        if (problem != null) {
            onChanged()
            return
        }
        if (s.senderName != prefs.senderName) prefs.rememberSenderName(s.senderName)
        send(AiRequest(runner.key(), AiTask.Email(s), s.mainText))
    }

    private fun fail(f: AiFailure) {
        step = AiEmailStep.Failed(f)
        onChanged()
    }

    private fun send(request: AiRequest, regenerate: Boolean = false) {
        regenerating = regenerate
        lastRequest = request
        notice = AiNotice.NONE
        step = AiEmailStep.Working
        onChanged()
        runner.start(request) { outcome ->
            when (outcome) {
                is AiOutcome.Success -> {
                    val r = outcome.result
                    result = r
                    subject.set(r.subject.orEmpty())
                    body.set(r.text)
                    step = AiEmailStep.Preview
                }
                is AiOutcome.Failure -> {
                    if (outcome.failure == AiFailure.NotEntitled) {
                        sessionLocked = true
                        lockGeneration = availability.accountGeneration
                    }
                    step = AiEmailStep.Failed(outcome.failure)
                }
            }
            onChanged()
        }
    }

    /** Another version: the same inputs, the next `variation` and a new key (the same key would replay, ADR 0027). */
    fun regenerate() {
        if (busy || step != AiEmailStep.Preview) return
        val prev = lastRequest ?: return
        val spec = (prev.task as? AiTask.Email)?.spec ?: return
        AiGate.block(availability)?.let { return fail(it) }
        val next = spec.copy(variation = AiEmailRules.nextVariation(spec.variation))
        send(AiRequest(runner.key(), AiTask.Email(next), next.mainText), regenerate = true)
    }

    fun retry() {
        val f = step as? AiEmailStep.Failed ?: return
        if (busy) return
        val prev = lastRequest
        if (prev == null || f.failure == AiFailure.NotEntitled) {
            step = AiEmailStep.Compose
            generate()
            return
        }
        AiGate.block(availability)?.let { return fail(it) }
        send(AiRequest(if (f.failure.retrySameKey) prev.requestId else runner.key(), prev.task, prev.text))
    }

    fun cancel() {
        runner.cancel()
        // A cancelled Regenerate keeps the version the user already had; a cancelled first Generate goes back to the inputs.
        step = if (regenerating && result != null) AiEmailStep.Preview else AiEmailStep.Compose
        onChanged()
    }

    /** From the preview (or an error) back to the inputs; the result is kept until the next Generate. */
    fun backToCompose() {
        if (busy) runner.cancel()
        typing = null
        step = AiEmailStep.Compose
        notice = AiNotice.NONE
        onChanged()
    }

    /** Back to the preview after an error that followed a result (e.g. a failed Regenerate). */
    val hasResult: Boolean get() = result != null

    fun backToPreview() {
        if (result == null || busy) return
        step = AiEmailStep.Preview
        onChanged()
    }

    fun toggleInsertSubject() { insertSubject = !insertSubject; onChanged() }

    /** The placeholders still in the (possibly edited) subject and body. */
    val placeholders: List<String>
        get() = AiEmailRules.placeholders(subject.text, body.text, result?.placeholders.orEmpty())

    // ------------------------------------------------------------------ applying

    /** Inserts the e-mail into the host field (replacing the draft there when it came from the field and is unchanged). */
    fun insert(): AiApplied {
        if (step != AiEmailStep.Preview) return AiApplied.FAILED
        val text = AiEmailRules.insertText(subject.text, body.text, insertSubject)
        editor.commitComposition()
        val from = fieldDraft
        val applied = when {
            mode == AiEmailMode.IMPROVE && from != null && draft.text == from && editor.replaceAll(from, text) -> AiApplied.REPLACED
            editor.insertAtCursor(text) -> AiApplied.INSERTED
            else -> AiApplied.FAILED
        }
        if (applied == AiApplied.FAILED) {
            notice = AiNotice.INSERT_FAILED
            onChanged()
        }
        return applied
    }

    fun copySubject(): Boolean = copy(subject.text)

    fun copyBody(): Boolean = copy(body.text)

    fun copyAll(): Boolean = copy(AiEmailRules.fullText(subject.text, body.text))

    private fun copy(text: String): Boolean {
        if (text.isBlank()) return false
        val ok = clipboard.copy(text)
        notice = if (ok) AiNotice.COPIED else AiNotice.COPY_FAILED
        onChanged()
        return ok
    }

    /** The inputs for the app's full editor (ADR 0031: an in-memory handoff, never an Intent extra). */
    fun handoff(): AiEmailSpec {
        val s = spec()
        AiEmailHandoff.put(s)
        return s
    }
}
