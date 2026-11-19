package com.qalsmartkeyboard.ime.ai

import com.qalsmartkeyboard.ime.ComposerUpdate
import com.qalsmartkeyboard.ime.TextSink
import java.text.BreakIterator
import java.util.UUID

/**
 * A text field inside an AI panel (ADR 0031): the text to work on, the user's notes, an e-mail being answered, a draft,
 * a name, or a result being edited. The keys type into it through [sink] (the same redirect the emoji search and Word
 * Art use), so every layout (Fidel, phonetic, Qubee, English) works. Typing appends at the end and backspace deletes the
 * last grapheme: an in-keyboard field has no cursor of its own.
 *
 * The text is user content: it lives only here, in memory, while the panel is open; it is never logged or stored.
 * [limit] is the contract's limit in characters (code points, like the server); the field holds more (a pasted e-mail
 * may be longer) up to [HARD_CAP], and the panel shows the count and refuses to send while it is over the limit.
 */
class AiTextField(val limit: Int, private val onChanged: () -> Unit = {}) {
    private val buffer = StringBuilder()
    private var composing = ""

    /** The committed text plus the composition in progress. */
    val text: String get() = if (composing.isEmpty()) buffer.toString() else buffer.toString() + composing

    val isBlank: Boolean get() = text.isBlank()

    /** Characters as the server counts them (code points). */
    val length: Int get() = text.let { it.codePointCount(0, it.length) }

    val overLimit: Boolean get() = length > limit

    /** Replaces the whole text (a paste, the selection, a result to edit). Longer text is cut at [HARD_CAP]. */
    fun set(value: String) {
        buffer.setLength(0)
        buffer.append(if (value.length > HARD_CAP) value.substring(0, HARD_CAP) else value)
        composing = ""
        onChanged()
    }

    fun clear() = set("")

    val sink: TextSink = object : TextSink {
        override fun apply(update: ComposerUpdate) {
            if (update.deleteBefore > 0) deleteGraphemes(update.deleteBefore)
            if (update.commit.isNotEmpty() && buffer.length + update.commit.length <= HARD_CAP) buffer.append(update.commit)
            composing = update.composing
            onChanged()
        }

        override fun sendEnterKey() {}
        override fun performEditorAction(actionId: Int) {}
        override fun wantsAutoCapital(capsFlags: Int) = false
    }

    private fun deleteGraphemes(n: Int) {
        repeat(n) {
            if (buffer.isEmpty()) return
            val it = BreakIterator.getCharacterInstance()
            it.setText(buffer.toString())
            val start = it.preceding(buffer.length)
            buffer.setLength(if (start == BreakIterator.DONE) 0 else start)
        }
    }

    override fun toString() = "AiTextField(length=$length, limit=$limit)"

    companion object {
        /** Upper bound of what a field holds (a long paste is kept so the user sees it is too long, not silently cut). */
        const val HARD_CAP = 20_000
    }
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
