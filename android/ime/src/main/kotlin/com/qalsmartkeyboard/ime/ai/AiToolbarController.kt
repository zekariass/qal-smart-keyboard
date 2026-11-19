package com.qalsmartkeyboard.ime.ai

import com.qalsmartkeyboard.ime.EditorOps
import com.qalsmartkeyboard.ime.InputConnectionAdapter
import com.qalsmartkeyboard.ime.InputProfile
import java.util.UUID

/** What the toolbar's panel shows. Held in memory only; closed on field change and keyboard hide. */
sealed interface AiPanel {
    data object Closed : AiPanel

    /** A request is in flight. The keyboard keeps typing; Cancel aborts the call. */
    class Working(val task: AiTask) : AiPanel

    /** The result, for review. [selected] is -1 for the main result, else the index of an alternative. */
    class Review(val task: AiTask, val original: String, val result: AiResult, val selected: Int = -1) : AiPanel {
        val shownText: String get() = if (selected in result.alternatives.indices) result.alternatives[selected].text else result.text
    }

    class Failed(val task: AiTask?, val failure: AiFailure) : AiPanel
}

/** The toolbar's choosers: the direction control's two sides (ADR 0031) and the rewrite tone. */
enum class AiPicker { NONE, SOURCE, DESTINATION, TONE }

/** What a Replace or Insert did, for the user's feedback. */
enum class AiApplied { REPLACED, INSERTED, INSERTED_ORIGINAL_CHANGED, FAILED }

/** The editor operations of the toolbar. Implemented over the adapter by [EditorAiAccess]; faked in tests. */
interface AiEditor {
    /** Commits the open composition as typed so the editor holds final text. */
    fun commitComposition()

    fun selectedText(): String?
    fun textBeforeCursor(n: Int): String?

    /** Replaces the selection if it still reads [expected]. */
    fun replaceSelection(expected: String, replacement: String): Boolean

    /** Replaces [expected] (the sentence and the whitespace after it) just before the cursor if it still reads so. */
    fun replaceBeforeCursor(expected: String, replacement: String): Boolean

    /** Inserts at the cursor (after a selection, if any), with a leading space when the text before is not whitespace. */
    fun insertAtCursor(text: String): Boolean

    /** The whole text of the field (up to [max] characters), read only on an explicit tap ("Use text from this field"); null when unknown. */
    fun allText(max: Int): String? = null

    /** Replaces the whole field text with [replacement] if it still reads [expected]; false otherwise. */
    fun replaceAll(expected: String, replacement: String): Boolean = false
}

/**
 * The AI toolbar's state machine (A2.5), free of Views so it runs on the JVM. Rules:
 *
 * - **Explicit action only:** the only code that calls [AiActions.submit] is [onTask] (a tap) and
 *   [retry] (a tap). Typing, selection changes and focus never send text.
 * - **Targets:** see [AiTextTarget]. Read at tap time, never earlier.
 * - **Non-blocking:** [AiActions.submit] returns at once; results arrive through [post] on the main
 *   thread. Keys keep working during a request; the panel stays open.
 * - **One at a time:** a tap on an action while a request is in flight is ignored (Cancel aborts
 *   it). A tap after a result or an error starts a new request with a fresh `Idempotency-Key`.
 * - **Retry:** after a network error, timeout, 503 or `REQUEST_IN_PROGRESS` the same key and the same
 *   original text are sent again (the server replays instead of charging twice); after anything
 *   else, and when asking for another result, a new key.
 * - **Never auto-insert or auto-send:** the result only enters the editor through [replace] or
 *   [insert], and nothing presses the host's Send.
 * - A request that is still running when the field changes or the keyboard hides is cancelled and
 *   its answer dropped, so it can never land in another field.
 */
class AiToolbarController(
    private val actions: AiActions,
    private val editor: AiEditor,
    /** Runs a block on the UI thread (the main handler); tests pass a direct executor. */
    private val post: (() -> Unit) -> Unit,
    private val copyToClipboard: (String) -> Unit,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
    private val onChanged: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    /** The keyboard language (the Auto source's likely language). */
    private val keyboardLanguage: () -> String = { "en" },
    /** The device-local direction memory and the preferred destination (settings). */
    prefs: AiLanguagePrefs = AiLanguagePrefs.InMemory(),
) {
    var visible: Boolean = false
        private set
    var panel: AiPanel = AiPanel.Closed
        private set
    var picker: AiPicker = AiPicker.NONE
        private set

    /** The translation direction control (ADR 0031): source (Auto or explicit), ⇄, destination. */
    val direction = TranslationDirection(prefs) { actions.destinations() }

    /** The translation destination (the direction control's right side). */
    val destination: AiDestination get() = direction.also { it.keyboardLanguage = keyboardLanguage() }.destination

    var tone: AiTone? = null
        private set

    val availability: AiAvailability get() = actions.availability

    fun destinations(): List<AiDestination> = actions.destinations()

    private class Flight(val id: Long, val request: AiRequest, var call: AiCall? = null)

    private var flight: Flight? = null
    private var generation = 0L

    /** The text of the last request and its key, for [retry]; kept only while the panel is open. */
    private var lastRequest: AiRequest? = null
    private var lastKind: AiTaskKind? = null
    private var source: AiSource? = null
    private var listener: AutoCloseable? = null
    private var lastRefreshMs = 0L
    private var refreshed = false

    init {
        listener = actions.addAvailabilityListener { post { onChanged() } }
    }

    /** A field was focused: show the toolbar where AI is allowed, drop everything of the last field. */
    fun onFieldStarted(profile: InputProfile, packageName: String?) {
        discard()
        val was = visible
        visible = profile.allowsAi
        if (visible) direction.start(keyboardLanguage(), actions.appRuleDestination(packageName))
        if (was != visible || panel !is AiPanel.Closed) onChanged()
    }

    /** The keyboard language changed: the Auto source follows it (and the default destination with it). */
    fun onKeyboardLanguageChanged() {
        if (!visible) return
        direction.keyboardLanguage = keyboardLanguage()
        onChanged()
    }

    /**
     * The user touched the toolbar (opened a picker or tapped an action): refresh plan state and the
     * offline token in the background, at most once per [REFRESH_WINDOW_MS] (the implementation
     * throttles its calls further). Showing the keyboard never refreshes (ADR 0007 D40): the toolbar
     * renders cached state until the user acts. Sends no text.
     */
    fun onToolbarOpened() {
        val now = clock()
        if (refreshed && now - lastRefreshMs < REFRESH_WINDOW_MS) return
        refreshed = true
        lastRefreshMs = now
        actions.refresh()
    }

    /** The keyboard was hidden or the service ends: cancel and forget. */
    fun onKeyboardHidden() {
        if (flight != null || panel !is AiPanel.Closed) {
            discard()
            onChanged()
        }
    }

    fun release() {
        discard()
        listener?.close()
        listener = null
    }

    // ---------------------------------------------------------------- chips

    fun openPicker(p: AiPicker) {
        if (!visible) return
        onToolbarOpened()
        picker = if (picker == p) AiPicker.NONE else p
        onChanged()
    }

    /** Closes an open picker without choosing (the AI actions were closed). Sends nothing. */
    fun closePicker() {
        if (picker == AiPicker.NONE) return
        picker = AiPicker.NONE
        onChanged()
    }

    /** The destination side of the direction control; remembered for the current source language (device-local). */
    fun chooseDestination(d: AiDestination) {
        direction.keyboardLanguage = keyboardLanguage()
        direction.chooseDestination(d)
        picker = AiPicker.NONE
        onChanged()
    }

    /** The source side: a language, or null for Auto (the server detects it). */
    fun chooseSource(language: String?) {
        direction.keyboardLanguage = keyboardLanguage()
        direction.chooseSource(language)
        picker = AiPicker.NONE
        onChanged()
    }

    /** ⇄ of the direction control. */
    fun swapDirection() {
        if (!visible) return
        onToolbarOpened()
        direction.keyboardLanguage = keyboardLanguage()
        direction.swap()
        picker = AiPicker.NONE
        onChanged()
    }

    fun chooseTone(t: AiTone?) {
        tone = t
        picker = AiPicker.NONE
        onChanged()
    }

    // ---------------------------------------------------------------- actions (explicit taps)

    /** A tap on Translate, Grammar or Rewrite. */
    fun onTask(kind: AiTaskKind) {
        if (!visible) return
        onToolbarOpened()
        if (flight != null) return // one request at a time; Cancel aborts it
        picker = AiPicker.NONE
        lastKind = kind
        lastRequest = null
        source = null
        val block = blockFailure()
        if (block != null) {
            panel = AiPanel.Failed(null, block)
            onChanged()
            return
        }
        val task = taskFor(kind) ?: return
        if (actions.availability.locks(task.kind)) {
            // The cached plan state says the plan does not include it: the upgrade link, and nothing is sent.
            panel = AiPanel.Failed(task, AiFailure.NotEntitled)
            onChanged()
            return
        }
        editor.commitComposition()
        val src = capture()
        when {
            src == null -> fail(null, AiFailure.NothingToProcess)
            src.text.length > AiTextTarget.MAX_CHARS -> fail(null, AiFailure.TooLong)
            else -> {
                source = src
                start(AiRequest(newKey(), task, src.text))
            }
        }
    }

    /** The toolbar's quick actions on the sentence or selection; the other kinds live in the Assist and Email panels. */
    private fun taskFor(kind: AiTaskKind): AiTask? = when (kind) {
        AiTaskKind.TRANSLATE -> AiTask.Translate(destination, direction.manualSource)
        AiTaskKind.GRAMMAR -> AiTask.Grammar
        AiTaskKind.REWRITE, AiTaskKind.TONE -> AiTask.Rewrite(tone)
        else -> null
    }

    private fun capture(): AiSource? {
        val selected = editor.selectedText()
        if (!selected.isNullOrEmpty()) return AiSource.Selection(selected)
        val before = editor.textBeforeCursor(AiTextTarget.LOOKBACK) ?: return null
        return AiTextTarget.sentenceBeforeCursor(before)
    }

    private fun blockFailure(): AiFailure? = when (actions.availability.block) {
        AiBlock.NONE -> null
        AiBlock.OFFLINE -> AiFailure.Offline
        AiBlock.SIGNED_OUT -> AiFailure.SignInRequired
        AiBlock.UPDATE_REQUIRED -> AiFailure.UpdateRequired
    }

    private fun start(request: AiRequest) {
        lastRequest = request
        val id = ++generation
        val f = Flight(id, request)
        flight = f
        panel = AiPanel.Working(request.task)
        onChanged()
        val call = actions.submit(request) { outcome -> post { onOutcome(id, outcome) } }
        if (flight === f) f.call = call else call.cancel()
    }

    private fun onOutcome(id: Long, outcome: AiOutcome) {
        val f = flight ?: return
        if (f.id != id) return // cancelled or superseded
        flight = null
        when (outcome) {
            is AiOutcome.Success -> {
                panel = AiPanel.Review(f.request.task, f.request.text, outcome.result)
                onChanged()
            }
            is AiOutcome.Failure -> fail(f.request.task, outcome.failure)
        }
    }

    private fun fail(task: AiTask?, failure: AiFailure) {
        panel = AiPanel.Failed(task, failure)
        onChanged()
    }

    /** Retry after a failure (same key when the first attempt may have arrived) or "another result" from a review. */
    fun retry() {
        if (flight != null) return
        val prev = lastRequest
        val p = panel
        if (prev == null || source == null) {
            // A local failure (nothing to process, too long) or a block: read the editor again.
            lastKind?.let(::onTask)
            return
        }
        val block = blockFailure()
        if (block != null) {
            fail(prev.task, block)
            return
        }
        val sameKey = p is AiPanel.Failed && p.failure.retrySameKey
        start(AiRequest(if (sameKey) prev.requestId else newKey(), prev.task, prev.text))
    }

    /** Cancel: aborts the call and closes the panel. */
    fun cancel() {
        discard()
        onChanged()
    }

    fun selectAlternative(index: Int) {
        val p = panel as? AiPanel.Review ?: return
        panel = AiPanel.Review(p.task, p.original, p.result, if (index in p.result.alternatives.indices) index else -1)
        onChanged()
    }

    fun copy() {
        val p = panel as? AiPanel.Review ?: return
        copyToClipboard(p.shownText)
    }

    /** Replace the original with the shown text. If the text changed meanwhile it is inserted at the cursor instead. */
    fun replace(): AiApplied {
        val p = panel as? AiPanel.Review ?: return AiApplied.FAILED
        val src = source ?: return AiApplied.FAILED
        editor.commitComposition()
        val text = p.shownText
        val replaced = when (src) {
            is AiSource.Selection -> editor.replaceSelection(src.text, text)
            is AiSource.Sentence -> editor.replaceBeforeCursor(src.text + src.trailing, text + src.trailing)
        }
        val applied = if (replaced) AiApplied.REPLACED else if (editor.insertAtCursor(text)) AiApplied.INSERTED_ORIGINAL_CHANGED else AiApplied.FAILED
        if (applied != AiApplied.FAILED) close()
        return applied
    }

    /** Insert the shown text at the cursor, keeping the original. */
    fun insert(): AiApplied {
        val p = panel as? AiPanel.Review ?: return AiApplied.FAILED
        editor.commitComposition()
        val ok = editor.insertAtCursor(p.shownText)
        if (ok) close()
        return if (ok) AiApplied.INSERTED else AiApplied.FAILED
    }

    /** Close the panel (Done, or the result was applied). */
    fun close() {
        discard()
        onChanged()
    }

    private fun discard() {
        flight?.call?.cancel()
        flight = null
        generation++
        panel = AiPanel.Closed
        picker = AiPicker.NONE
        lastRequest = null
        lastKind = null
        source = null
    }

    val inFlight: Boolean get() = flight != null

    companion object {
        /** Matches the plan-state cache of `:ai-client` (5 minutes). */
        const val REFRESH_WINDOW_MS = 5L * 60_000
    }
}

/** [AiEditor] over the keyboard's adapter. [commit] commits the open composition through the composer. */
class EditorAiAccess(private val adapter: InputConnectionAdapter, private val commit: () -> Unit) : AiEditor {
    private val ed: EditorOps? get() = adapter.editorOps()

    override fun commitComposition() = commit()

    override fun selectedText(): String? = ed?.getSelectedText()?.toString()

    override fun textBeforeCursor(n: Int): String? = ed?.getTextBeforeCursor(n)?.toString()

    override fun replaceSelection(expected: String, replacement: String): Boolean {
        val e = ed ?: return false
        if (e.getSelectedText()?.toString() != expected) return false
        edit(e) { e.commitText(replacement) }
        return true
    }

    override fun replaceBeforeCursor(expected: String, replacement: String): Boolean {
        val e = ed ?: return false
        if (!e.getSelectedText().isNullOrEmpty()) return false
        if (e.getTextBeforeCursor(expected.length)?.toString() != expected) return false
        edit(e) {
            e.deleteSurroundingText(expected.length)
            e.commitText(replacement)
        }
        return true
    }

    override fun insertAtCursor(text: String): Boolean {
        val e = ed ?: return false
        val selected = e.getSelectedText()
        if (!selected.isNullOrEmpty()) {
            val end = adapter.selectionEnd
            if (end >= 0) e.setSelection(end, end) else e.sendKey(collapseToEndKey(selected))
        }
        val before = e.getTextBeforeCursor(1)
        val space = if (!before.isNullOrEmpty() && !before.last().isWhitespace()) " " else ""
        edit(e) { e.commitText(space + text) }
        return true
    }

    override fun allText(max: Int): String? {
        val e = ed ?: return null
        e.getAllText(max)?.toString()?.let { return it }
        // The editor does not report its whole text: what is before the cursor is the best we can read.
        return e.getTextBeforeCursor(max)?.toString()
    }

    override fun replaceAll(expected: String, replacement: String): Boolean {
        val e = ed ?: return false
        if (e.getAllText(expected.length + 1)?.toString() != expected) return false
        if (!e.selectAll()) return false
        edit(e) { e.commitText(replacement) }
        return true
    }

    private inline fun edit(e: EditorOps, block: () -> Unit) {
        e.beginBatchEdit()
        try {
            block()
        } finally {
            e.endBatchEdit()
        }
        adapter.editedExternally()
    }

    internal companion object {
        const val KEYCODE_DPAD_LEFT = 21
        const val KEYCODE_DPAD_RIGHT = 22

        /**
         * The arrow key that collapses a selection to its logical end. Arrow keys move visually: in right-to-left text
         * (Arabic, ADR 0034) the end is on the left. The selection's first strong character decides.
         */
        fun collapseToEndKey(selected: CharSequence): Int {
            for (c in selected) {
                when (Character.getDirectionality(c)) {
                    Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return KEYCODE_DPAD_LEFT
                    Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return KEYCODE_DPAD_RIGHT
                }
            }
            return KEYCODE_DPAD_RIGHT
        }
    }
}
