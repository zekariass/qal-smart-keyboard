package com.qalsmartkeyboard.ime.ai

/**
 * The limits of one `POST /ai/chat` request (contract `ChatRequest`, ADR 0048) and the rule that fits the conversation
 * into them. The server keeps no conversation: the client resends the window every turn.
 */
object ChatWindow {
    /** `ChatRequest.messages` maxItems. */
    const val MAX_MESSAGES = 12

    /** `ChatMessage.text` maxLength, in code points. */
    const val MAX_TURN_CHARS = 4000

    /** The messages together, in code points. */
    const val MAX_TOTAL_CHARS = 10_000

    /** `ChatRequest.context.fieldText` maxLength. */
    const val MAX_FIELD_CHARS = 4000

    private fun length(s: String) = s.codePointCount(0, s.length)

    /** [s] cut to at most [max] code points from its start, never inside a surrogate pair. */
    fun clip(s: String, max: Int): String =
        if (length(s) <= max) s else s.substring(0, s.offsetByCodePoints(0, max))

    /** [s] cut to its last [max] code points (the text nearest the cursor), never inside a surrogate pair. */
    fun clipEnd(s: String, max: Int): String {
        val n = length(s)
        return if (n <= max) s else s.substring(s.offsetByCodePoints(0, n - max))
    }

    /**
     * The turns to send: the most recent ones that fit [MAX_MESSAGES] and [MAX_TOTAL_CHARS] (the oldest are dropped
     * first), each cut to [MAX_TURN_CHARS], starting with a user turn. The last turn must be the user's: it is always
     * kept (a conversation that does not end with one gives an empty window).
     */
    fun fit(turns: List<ChatTurn>): List<ChatTurn> {
        if (turns.isEmpty() || turns.last().role != ChatRole.USER) return emptyList()
        val kept = ArrayList<ChatTurn>()
        var total = 0
        for (turn in turns.asReversed()) {
            val text = clip(turn.text, MAX_TURN_CHARS)
            val n = length(text)
            if (kept.isNotEmpty() && (kept.size >= MAX_MESSAGES || total + n > MAX_TOTAL_CHARS)) break
            kept += ChatTurn(turn.role, text)
            total += n
        }
        kept.reverse()
        // A window that starts with an assistant reply has lost the question it answers: start at a user turn.
        while (kept.size > 1 && kept.first().role != ChatRole.USER) kept.removeAt(0)
        return kept
    }
}

/** One message of the transcript. [cutOff]: the assistant's reply ended at the provider's output limit ("Continue"). */
class ChatEntry(val id: Int, val role: ChatRole, val text: String, val cutOff: Boolean = false) {
    val turn: ChatTurn get() = ChatTurn(role, text)
    override fun toString() = "ChatEntry($id, $role)"
}

/** The part of the chat that is not the transcript. */
sealed interface ChatStep {
    data object Idle : ChatStep

    /** A request is in flight (the typing indicator); Cancel aborts it. */
    data object Sending : ChatStep

    /** The last send failed. [retryable]: the sent request is kept, so Retry sends it again (same key when it may have arrived). */
    class Failed(val failure: AiFailure, val retryable: Boolean) : ChatStep
}

/** The quick starters shown on an empty chat: each prefills the input (never sends). */
enum class ChatStarter { WRITE, EXPLAIN_WORD, IMPROVE, BRAINSTORM }

/**
 * The in-keyboard "Ask AI" chat (plan T8.5, ADR 0048), free of Views so it runs on the JVM. Rules:
 *
 * - **Memory only:** the turns, the input and the attached field text live here while the panel is open. They are never
 *   persisted or logged, and [close] (the panel closes, the field changes, the keyboard hides) drops all of them and
 *   cancels a running call. [newChat] clears the conversation and keeps the panel.
 * - **Explicit send only:** [send] (the Send button, or Enter on the keys), [continueReply] and [retry] are the only
 *   calls that send, each on a tap. One request at a time; [cancel] aborts it and puts the question back in the input.
 * - **Stateless server:** every send carries the window of the latest turns ([ChatWindow]: at most 12 messages and 10000
 *   characters, the oldest dropped first, the newest user turn always kept).
 * - **Field text only on a tap:** [attachFieldText] ("Use text in field") reads the host field's selection, else the text
 *   before the cursor (the same reads as AI Assist and Translate), once, on that tap, and never in a field that does not
 *   allow AI (password, raw key fields). The text goes along as `context.fieldText` with each send until [removeFieldText].
 * - **Retry:** after a network error, timeout, 503 or `REQUEST_IN_PROGRESS` the same `Idempotency-Key` and body (the
 *   server replays); after anything else a new key. Every new turn gets a new key.
 * - **Never auto-insert:** a reply enters the host field only through [insert].
 * - **Blocked / locked:** offline, signed out, update needed and a plan without `AI_CHAT` show as [blocker] before
 *   anything is sent.
 */
class ChatController(
    private val actions: AiActions,
    private val editor: AiEditor,
    private val clipboard: AiClipboard,
    post: (() -> Unit) -> Unit,
    /** The keyboard language (the first choice of the reply-language list). */
    private val keyboardLanguage: () -> String,
    /** AI is allowed in the focused field ([com.qalsmartkeyboard.ime.InputProfile.allowsAi]); else nothing opens or is read. */
    private val fieldAllowed: () -> Boolean = { true },
    /** The user turn that asks for the rest of a cut-off reply (UI language). */
    private val continueText: () -> String = { "Continue" },
    newKey: () -> String = AiRunner::uuid,
    /** Refreshes the cached plan state (throttled). Called when the panel opens. */
    private val refresh: () -> Unit = {},
    private val onChanged: () -> Unit = {},
) {
    private val runner = AiRunner(actions, post, newKey)
    private var nextId = 1

    /** The message being written. Its limit is one turn's. */
    val input = AiTextField(ChatWindow.MAX_TURN_CHARS) { onChanged() }

    var isOpen: Boolean = false
        private set

    /** The keys type into [input] (the panel gives way to the keys and the field bar). */
    var typing: Boolean = false
        private set

    var step: ChatStep = ChatStep.Idle
        private set
    var notice: AiNotice = AiNotice.NONE
        private set

    private var items: List<ChatEntry> = emptyList()

    /** The transcript, oldest first. */
    val entries: List<ChatEntry> get() = items

    /** The host field's text the user attached with "Use text in field", or null. */
    var fieldText: String? = null
        private set

    /** The reply language the user picked, or null: "same as I write". */
    var language: String? = null
        private set
    var languagePickerOpen: Boolean = false
        private set
    private var showAllLanguages = false

    private var lastRequest: AiRequest? = null
    private val sessionLocks = HashSet<Long>()

    val availability: AiAvailability get() = actions.availability
    val busy: Boolean get() = runner.busy

    // ------------------------------------------------------------------ lifecycle

    /** True when the chat may open here: the field allows AI. */
    val allowed: Boolean get() = fieldAllowed()

    /** Opens the panel (the tools grid's "Ask AI" tile). Sends nothing and reads nothing from the field. */
    fun open() {
        if (isOpen || !allowed) return
        reset()
        refresh()
        isOpen = true
        onChanged()
    }

    /** Closes the panel: a running call is cancelled and every text is dropped. */
    fun close() {
        if (!isOpen) return
        reset()
        isOpen = false
        onChanged()
    }

    /** "New chat": the conversation, the input and the attached text are cleared; the panel and the language stay. */
    fun newChat() {
        if (!isOpen) return
        val lang = language
        reset()
        language = lang
        onChanged()
    }

    private fun reset() {
        runner.cancel()
        items = emptyList()
        input.set("")
        typing = false
        step = ChatStep.Idle
        notice = AiNotice.NONE
        fieldText = null
        language = null
        languagePickerOpen = false
        showAllLanguages = false
        lastRequest = null
    }

    // ------------------------------------------------------------------ the input

    /** The keys now type into [input]. */
    fun startTyping() {
        if (!isOpen || typing) return
        typing = true
        languagePickerOpen = false
        notice = AiNotice.NONE
        onChanged()
    }

    /** Typing is done (Done, Back): the panel is back. The caller commits the open composition first. */
    fun endTyping() {
        if (!typing) return
        typing = false
        onChanged()
    }

    /** A quick starter: its [text] (UI language) fills the input and the keys come up to finish it. Sends nothing. */
    fun prefill(text: String) {
        if (!isOpen || busy) return
        input.set(text)
        typing = true
        notice = AiNotice.NONE
        onChanged()
    }

    // ------------------------------------------------------------------ field text ("Use text in field")

    /**
     * The "Use text in field" tap, the only place the host field is read: its selection, else the text before the cursor
     * (the open composition is finished into it first), cut to [ChatWindow.MAX_FIELD_CHARS]. Nothing in a field that does
     * not allow AI; an empty field says so and attaches nothing.
     */
    fun attachFieldText() {
        if (!isOpen || !allowed) return
        editor.commitComposition()
        val selected = editor.selectedText()?.takeIf { it.isNotBlank() }?.let { ChatWindow.clip(it, ChatWindow.MAX_FIELD_CHARS) }
        val text = selected
            ?: editor.textBeforeCursor(ChatWindow.MAX_FIELD_CHARS)?.takeIf { it.isNotBlank() }?.let { ChatWindow.clipEnd(it, ChatWindow.MAX_FIELD_CHARS) }
        if (text == null) {
            notice = AiNotice.FIELD_EMPTY
        } else {
            fieldText = text
            notice = AiNotice.NONE
        }
        onChanged()
    }

    /** The × on the "Field text attached" chip: later sends carry no field text. */
    fun removeFieldText() {
        if (fieldText == null) return
        fieldText = null
        onChanged()
    }

    // ------------------------------------------------------------------ language

    /**
     * The reply-language choices (null = "Same as I write"): the translate panel's destination catalogue, the current
     * choice and the keyboard language first ([SHORT_LIST] of them until [moreLanguages]).
     */
    fun languageChoices(): List<String?> {
        val all = TranslationLanguageSearch.order(actions.translationCatalog().destinations, listOfNotNull(language, keyboardLanguage()))
            .map { it.code }
        return listOf<String?>(null) + if (showAllLanguages) all else all.take(SHORT_LIST)
    }

    val hasMoreLanguages: Boolean
        get() = !showAllLanguages && actions.translationCatalog().destinations.size > SHORT_LIST

    fun moreLanguages() {
        if (showAllLanguages) return
        showAllLanguages = true
        onChanged()
    }

    fun toggleLanguagePicker() {
        if (!isOpen) return
        languagePickerOpen = !languagePickerOpen
        if (!languagePickerOpen) showAllLanguages = false
        onChanged()
    }

    fun chooseLanguage(code: String?) {
        if (code != null && !actions.translationCatalog().isDestination(code)) return
        language = code
        languagePickerOpen = false
        showAllLanguages = false
        onChanged()
    }

    // ------------------------------------------------------------------ sending (explicit taps)

    /** Why nothing can be sent right now (shown as one line with its link), or null. Reads cached state only. */
    val blocker: AiFailure?
        get() {
            val a = availability
            if (!isOpen) return null
            AiGate.block(a)?.let { return it }
            val sessionLocked = a.accountGeneration in sessionLocks
            return if (a.locks(AiTaskKind.CHAT) || sessionLocked) AiFailure.NotEntitled else null
        }

    /** Send is enabled: text in the input within the limit, nothing running, not blocked (offline may try and say so). */
    val canSend: Boolean
        get() = isOpen && !busy && !input.isBlank && !input.overLimit && blocker.let { it == null || it == AiFailure.Offline }

    /** The last assistant message was cut off and nothing is running: "Continue" is offered. */
    val canContinue: Boolean
        get() = isOpen && !busy && step is ChatStep.Idle && items.lastOrNull()?.let { it.role == ChatRole.ASSISTANT && it.cutOff } == true

    /** The Send button (or Enter on the keys). */
    fun send() {
        if (!isOpen || busy) return
        refresh()
        editor.commitComposition() // the keys type into the input: an open composition is finished into it first
        typing = false
        notice = AiNotice.NONE
        blocker?.let { return fail(it) }
        val text = input.text.trim()
        when {
            text.isEmpty() -> fail(AiFailure.NothingToProcess)
            input.overLimit -> fail(AiFailure.TooLong)
            else -> {
                append(ChatRole.USER, text)
                input.set("")
                dispatch(runner.key(), makeRequest())
            }
        }
    }

    /** "Continue" after a cut-off reply: a user turn asks for the rest. */
    fun continueReply() {
        if (!canContinue) return
        blocker?.let { return fail(it) }
        append(ChatRole.USER, continueText())
        dispatch(runner.key(), makeRequest())
    }

    /**
     * Retry after a failure: the same request again, with the same key when the first attempt may have reached the server
     * (so it is replayed, not charged twice), else a new key.
     */
    fun retry() {
        val f = step as? ChatStep.Failed ?: return
        val prev = lastRequest
        if (busy || prev == null || !f.retryable) return
        blocker?.let { return fail(it) }
        dispatch(if (f.failure.retrySameKey) prev.requestId else runner.key(), prev)
    }

    /** Cancel while sending: the call is aborted and the question goes back into the input (when it is empty). */
    fun cancel() {
        if (!busy) return
        runner.cancel()
        val last = items.lastOrNull()
        if (last != null && last.role == ChatRole.USER) {
            items = items.dropLast(1)
            if (input.isBlank) input.set(last.text)
        }
        step = ChatStep.Idle
        lastRequest = null
        onChanged()
    }

    /** Enter on the keys: sends, and the panel shows the answer. */
    fun onEnter() = send()

    private fun fail(f: AiFailure) {
        step = ChatStep.Failed(f, retryable = false)
        onChanged()
    }

    private fun append(role: ChatRole, text: String, cutOff: Boolean = false) {
        val next = items + ChatEntry(nextId++, role, text, cutOff)
        items = if (next.size > MAX_ENTRIES) next.takeLast(MAX_ENTRIES) else next
    }

    /** The request for the transcript as it is now: the window, the reply language, the attached field text. */
    private fun makeRequest(): AiRequest {
        val window = ChatWindow.fit(items.map { it.turn })
        val task = AiTask.Chat(window, language, fieldText)
        return AiRequest("", task, window.lastOrNull()?.text.orEmpty())
    }

    private fun dispatch(key: String, template: AiRequest) {
        val request = AiRequest(key, template.task, template.text)
        lastRequest = request
        step = ChatStep.Sending
        languagePickerOpen = false
        onChanged()
        runner.start(request) { outcome ->
            when (outcome) {
                is AiOutcome.Success -> {
                    append(ChatRole.ASSISTANT, outcome.result.text, cutOff = outcome.result.truncated)
                    step = ChatStep.Idle
                    lastRequest = null
                }
                is AiOutcome.Failure -> {
                    if (outcome.failure == AiFailure.NotEntitled) sessionLocks += availability.accountGeneration
                    step = ChatStep.Failed(outcome.failure, retryable = AiGate.canRetry(outcome.failure))
                }
            }
            onChanged()
        }
    }

    // ------------------------------------------------------------------ applying

    /** Inserts the assistant message [id] at the cursor of the host field (never replaces; the user's text stays). */
    fun insert(id: Int): AiApplied {
        val entry = items.firstOrNull { it.id == id && it.role == ChatRole.ASSISTANT } ?: return AiApplied.FAILED
        editor.commitComposition()
        val applied = if (editor.insertAtCursor(entry.text)) AiApplied.INSERTED else AiApplied.FAILED
        if (applied == AiApplied.FAILED) {
            notice = AiNotice.INSERT_FAILED
            onChanged()
        }
        return applied
    }

    /** Copies the message [id] (a plain clip: the user asked for it). */
    fun copy(id: Int): Boolean {
        val entry = items.firstOrNull { it.id == id } ?: return false
        val ok = clipboard.copy(entry.text)
        notice = if (ok) AiNotice.COPIED else AiNotice.COPY_FAILED
        onChanged()
        return ok
    }

    // ------------------------------------------------------------------ typing redirect

    /** The field the keys type into while [typing], else null. */
    val typingField: AiTextField? get() = if (typing) input else null

    companion object {
        /** The reply-language list's first screen. */
        const val SHORT_LIST = 8

        /** The transcript's size bound (memory only): older messages fall off the top. */
        const val MAX_ENTRIES = 60
    }
}
