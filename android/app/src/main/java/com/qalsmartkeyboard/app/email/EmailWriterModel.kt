package com.qalsmartkeyboard.app.email

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.qalsmartkeyboard.ime.ai.AiActions
import com.qalsmartkeyboard.ime.ai.AiCall
import com.qalsmartkeyboard.ime.ai.AiEmailMode
import com.qalsmartkeyboard.ime.ai.AiEmailProblem
import com.qalsmartkeyboard.ime.ai.AiEmailRules
import com.qalsmartkeyboard.ime.ai.AiEmailSpec
import com.qalsmartkeyboard.ime.ai.AiEmailTone
import com.qalsmartkeyboard.ime.ai.AiFailure
import com.qalsmartkeyboard.ime.ai.AiGate
import com.qalsmartkeyboard.ime.ai.AiLanguages
import com.qalsmartkeyboard.ime.ai.AiLength
import com.qalsmartkeyboard.ime.ai.AiOutcome
import com.qalsmartkeyboard.ime.ai.AiRequest
import com.qalsmartkeyboard.ime.ai.AiTask
import com.qalsmartkeyboard.ime.ai.AiTaskKind
import java.util.UUID

/** The steps of the companion app's e-mail writer. */
enum class EmailStep { COMPOSE, WORKING, PREVIEW, FAILED }

/**
 * State of the companion app's "Write Email with AI" screen (ADR 0031): the same three modes, rules and request as the
 * keyboard's Email panel ([AiEmailRules], [AiTask.Email]) through the same AI client (so the same timeouts, history
 * setting and error mapping), with comfortable multi-line fields and inline editing of the result.
 *
 * User text lives only in this ViewModel while the screen is open; nothing is logged. [rememberSender] stores the
 * user's own name on the device (settings), nothing else is persisted.
 */
class EmailWriterModel(
    private var actions: AiActions? = null,
    private val rememberSender: (String) -> Unit = {},
    private val post: (() -> Unit) -> Unit = { block -> MAIN.post(block) },
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    var mode by mutableStateOf(AiEmailMode.NEW)
    var instructions by mutableStateOf("")
    var originalEmail by mutableStateOf("")
    var draft by mutableStateOf("")
    var tone by mutableStateOf(AiEmailTone.PROFESSIONAL)
    var length by mutableStateOf(AiLength.MEDIUM)
    var language by mutableStateOf("en")
    var recipientName by mutableStateOf("")
    var senderName by mutableStateOf("")

    var step by mutableStateOf(EmailStep.COMPOSE)
        private set
    var problem by mutableStateOf<AiEmailProblem?>(null)
        private set
    var failure by mutableStateOf<AiFailure?>(null)
        private set
    var subject by mutableStateOf("")
    var body by mutableStateOf("")
    var notes by mutableStateOf<List<String>>(emptyList())
        private set
    private var serverPlaceholders: List<String> = emptyList()

    private var call: AiCall? = null
    private var generation = 0L
    private var lastRequest: AiRequest? = null
    private var savedSender = ""

    /** True when the keyboard handed its inputs over (shown once as a note). */
    var fromKeyboard by mutableStateOf(false)
        private set

    val placeholders: List<String> get() = AiEmailRules.placeholders(subject, body, serverPlaceholders)

    val hasResult: Boolean get() = body.isNotBlank()

    /** The AI client, once built (off the main thread). */
    fun attach(a: AiActions) {
        if (actions == null) actions = a
    }

    /** The remembered sender name (settings), applied once when the screen opens. */
    fun start(sender: String, handoff: AiEmailSpec?) {
        savedSender = sender
        if (senderName.isBlank()) senderName = sender
        if (handoff != null) {
            mode = handoff.mode
            language = handoff.language.takeIf { it in AiLanguages.ALL } ?: "en"
            tone = handoff.tone
            length = handoff.length
            instructions = handoff.instructions
            originalEmail = handoff.originalEmail
            draft = handoff.draft
            recipientName = handoff.recipientName
            if (handoff.senderName.isNotBlank()) senderName = handoff.senderName
            fromKeyboard = true
        }
    }

    fun spec(variation: Int = 0) = AiEmailSpec(
        mode = mode,
        language = language,
        instructions = instructions.trim(),
        originalEmail = if (mode == AiEmailMode.REPLY) originalEmail.trim() else "",
        draft = if (mode == AiEmailMode.IMPROVE) draft.trim() else "",
        tone = tone,
        length = length,
        includeSubject = true,
        recipientName = recipientName.trim(),
        senderName = senderName.trim(),
        variation = variation,
    )

    /** Generate: validated on the device first (nothing is sent with a missing or too long field). */
    fun generate() {
        if (step == EmailStep.WORKING) return
        val a = actions ?: return fail(AiFailure.ServiceUnavailable)
        AiGate.block(a.availability)?.let { return fail(it) }
        if (a.availability.locks(AiTaskKind.EMAIL)) return fail(AiFailure.NotEntitled)
        val s = spec()
        problem = AiEmailRules.validate(s)
        if (problem != null) return
        if (s.senderName != savedSender) {
            savedSender = s.senderName
            rememberSender(s.senderName)
        }
        send(AiRequest(newKey(), AiTask.Email(s), s.mainText))
    }

    /** Another version: the next `variation` and a new key (ADR 0027). */
    fun regenerate() {
        if (step == EmailStep.WORKING) return
        val prev = (lastRequest?.task as? AiTask.Email)?.spec ?: return
        val next = prev.copy(variation = AiEmailRules.nextVariation(prev.variation))
        send(AiRequest(newKey(), AiTask.Email(next), next.mainText))
    }

    fun retry() {
        val prev = lastRequest ?: return generate()
        val f = failure
        if (f == AiFailure.NotEntitled) return generate()
        send(AiRequest(if (f?.retrySameKey == true) prev.requestId else newKey(), prev.task, prev.text))
    }

    fun cancel() {
        generation++
        call?.cancel()
        call = null
        step = if (hasResult) EmailStep.PREVIEW else EmailStep.COMPOSE
    }

    fun backToCompose() {
        if (step == EmailStep.WORKING) cancel()
        step = EmailStep.COMPOSE
    }

    fun clearProblem() { problem = null }

    private fun fail(f: AiFailure) {
        failure = f
        step = EmailStep.FAILED
    }

    private fun send(request: AiRequest) {
        val a = actions ?: return fail(AiFailure.ServiceUnavailable)
        lastRequest = request
        failure = null
        problem = null
        step = EmailStep.WORKING
        val id = ++generation
        call = a.submit(request) { outcome ->
            post {
                if (id != generation) return@post
                call = null
                when (outcome) {
                    is AiOutcome.Success -> {
                        subject = outcome.result.subject.orEmpty()
                        body = outcome.result.text
                        notes = outcome.result.notes
                        serverPlaceholders = outcome.result.placeholders
                        step = EmailStep.PREVIEW
                    }
                    is AiOutcome.Failure -> fail(outcome.failure)
                }
            }
        }
    }

    override fun onCleared() {
        generation++
        call?.cancel()
    }

    private companion object {
        val MAIN by lazy { Handler(Looper.getMainLooper()) }
    }
}
