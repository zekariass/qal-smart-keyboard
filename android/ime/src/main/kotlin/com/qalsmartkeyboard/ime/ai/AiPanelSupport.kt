package com.qalsmartkeyboard.ime.ai

import com.qalsmartkeyboard.ime.TextSink
import com.qalsmartkeyboard.ime.editor.EditorBuffer
import com.qalsmartkeyboard.ime.editor.EditorClipboard
import java.util.UUID

/**
 * A text field inside an AI panel (ADR 0031): the text to work on, the user's notes, an e-mail being answered, a draft,
 * a name, or a result being edited. Since ADR 0038 it is an [EditorBuffer]: the keys type into it through [sink] (the
 * same redirect the emoji search and Word Art use) **at its cursor**, so every layout (Fidel, phonetic, Qubee, English)
 * works mid-text; the typing strip's [com.qalsmartkeyboard.ime.editor.EditorView] moves the cursor, selects, cuts, copies
 * and pastes, and voice typing dictates at the cursor.
 *
 * The text is user content: it lives only here, in memory, while the panel is open; it is never logged or stored.
 * [limit] is the contract's limit in characters (code points, like the server); the field holds more (a pasted e-mail
 * may be longer) up to [HARD_CAP], and the panel shows the count and refuses to send while it is over the limit.
 */
class AiTextField(val limit: Int, private val onChanged: () -> Unit = {}) {
    /** The text, the cursor and the selection (ADR 0038). */
    val buffer = EditorBuffer(HARD_CAP).also { it.onTextChanged = { onChanged() } }

    /** The committed text plus the composition in progress. */
    val text: String get() = buffer.text

    val isBlank: Boolean get() = text.isBlank()

    /** Characters as the server counts them (code points). */
    val length: Int get() = text.let { it.codePointCount(0, it.length) }

    val overLimit: Boolean get() = length > limit

    /** Replaces the whole text (a paste, the selection, a result to edit); the cursor goes to the end. Cut at [HARD_CAP]. */
    fun set(value: String) {
        val was = buffer.version
        buffer.setText(value)
        if (buffer.version == was) onChanged() // the same text again still counts as a change for the panels
    }

    fun clear() = set("")

    /** Where the keys write (at the cursor, replacing a selection). */
    val sink: TextSink get() = buffer.sink

    override fun toString() = "AiTextField(length=$length, limit=$limit)"

    companion object {
        /** Upper bound of what a field holds (a long paste is kept so the user sees it is too long, not silently cut). */
        const val HARD_CAP = 20_000
    }
}

/** The AI panels' clipboard for the keyboard's text fields' action row (the same rules: sensitive clips never read). */
fun AiClipboard.forEditor(): EditorClipboard = object : EditorClipboard {
    override fun canPaste() = availability() == ClipAvailability.TEXT
    override fun paste(): String? = if (availability() == ClipAvailability.TEXT) readText() else null
    override fun copy(text: String) = this@forEditor.copy(text)
}

/** What the clipboard holds, read from its description only (no content, no "pasted" notice). */
enum class ClipAvailability {
    /** Nothing, or nothing textual. */
    NONE,

    /** Text: the "Paste from clipboard" chip is offered. */
    TEXT,

    /** The copying app marked the clip sensitive (`ClipDescription.EXTRA_IS_SENSITIVE`): never offered, never read. */
    SENSITIVE,
}

/**
 * The clipboard as the AI panels may use it (ADR 0031): [availability] reads only the clip's description (MIME types
 * and the sensitive flag) and never its content; [readText] is called only from the user's tap on "Paste from
 * clipboard"; [copy] puts a result on the clipboard as a plain clip (user-requested output, not marked sensitive).
 * Implemented over `ClipboardManager` by [SystemAiClipboard]; faked in tests.
 */
interface AiClipboard {
    fun availability(): ClipAvailability

    /** The clip's text, or null. Only on an explicit tap; returns null for a sensitive clip. */
    fun readText(): String?

    /** False when the system refused. */
    fun copy(text: String): Boolean
}

/** Explicit user actions that need one request at a time, with the answer delivered on the UI thread (shared by the panels). */
internal class AiRunner(
    private val actions: AiActions,
    private val post: (() -> Unit) -> Unit,
    private val newKey: () -> String,
) {
    private var call: AiCall? = null
    private var generation = 0L

    val busy: Boolean get() = call != null

    fun key(): String = newKey()

    /** Starts [request]; [done] runs once on the UI thread unless [cancel] (or another [start]) came first. */
    fun start(request: AiRequest, done: (AiOutcome) -> Unit) {
        cancel()
        val id = ++generation
        var finished = false
        val c = actions.submit(request) { outcome ->
            post {
                if (id != generation) return@post
                finished = true
                call = null
                done(outcome)
            }
        }
        if (id == generation && !finished) call = c else if (id != generation) c.cancel()
    }

    /** Aborts the HTTP call; its answer, if it still comes, is dropped. */
    fun cancel() {
        generation++
        call?.cancel()
        call = null
    }

    companion object {
        fun uuid(): String = UUID.randomUUID().toString()
    }
}

/** Shared rules of the panels: why nothing can be sent right now, and the plan locks. */
object AiGate {
    /** The failure to show instead of sending while [a] is blocked (offline, signed out, update needed), else null. */
    fun block(a: AiAvailability): AiFailure? = when (a.block) {
        AiBlock.NONE -> null
        AiBlock.OFFLINE -> AiFailure.Offline
        AiBlock.SIGNED_OUT -> AiFailure.SignInRequired
        AiBlock.UPDATE_REQUIRED -> AiFailure.UpdateRequired
    }

    /** A retry after [f] should be offered (the cause may go away by itself or with another try). */
    fun canRetry(f: AiFailure): Boolean = when (f) {
        is AiFailure.QuotaExceeded, AiFailure.NotEntitled, AiFailure.OnDeviceOnly, is AiFailure.ConsentRequired,
        AiFailure.SignInRequired, AiFailure.AccountBlocked, AiFailure.UpdateRequired, AiFailure.LanguageUnsupported,
        AiFailure.ContentRejected, is AiFailure.RateLimited, is AiFailure.InputTooLong, is AiFailure.Invalid,
        AiFailure.NothingToProcess, AiFailure.TooLong,
        -> false
        else -> true
    }
}

/**
 * The process-wide, in-memory handoff of the keyboard's e-mail inputs to the companion app's full editor (ADR 0031). The
 * keyboard and the app run in one process, so the text never goes into an Intent extra (which other components and logs
 * could see): the keyboard [put]s the spec and opens the app with [AiLink.EMAIL_WRITER]; the app [take]s it once.
 * Expires after [TTL_MS]; nothing is written to disk.
 */
object AiEmailHandoff {
    const val TTL_MS = 10L * 60_000

    @Volatile private var held: Pair<AiEmailSpec, Long>? = null

    fun put(spec: AiEmailSpec, nowMs: Long = System.currentTimeMillis()) {
        held = spec to nowMs
    }

    /** The spec handed over in the last [TTL_MS], once; null when there is none. */
    @Synchronized
    fun take(nowMs: Long = System.currentTimeMillis()): AiEmailSpec? {
        val h = held ?: return null
        held = null
        return if (nowMs - h.second <= TTL_MS) h.first else null
    }
}
