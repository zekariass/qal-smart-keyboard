package com.qalsmartkeyboard.ime.ai

import com.qalsmartkeyboard.ime.toolbar.ToolIcon

/**
 * A quick action of the AI tools grid (ADR 0041): it runs on the field's text (the selection, else the sentence before the
 * cursor) and its result opens in the review panel above the bar (Replace / Insert / Copy / Retry), as the AI row's
 * Grammar and Rewrite chips did. Each is one `/ai/transform` task the server serves (contract `TransformTask`, `Tone`).
 */
enum class AiQuickAction(val task: AiTask) {
    GRAMMAR(AiTask.Grammar),
    REWRITE(AiTask.Rewrite(null)),
    SIMPLIFY(AiTask.Simplify()),
    SHORTEN(AiTask.Rewrite(null, mode = AiRewriteMode.SHORTEN)),
    EXPAND(AiTask.Rewrite(null, mode = AiRewriteMode.EXPAND)),
    CLARIFY(AiTask.Rewrite(null, mode = AiRewriteMode.CLARIFY)),
    PROFESSIONAL(AiTask.Rewrite(AiTone.PROFESSIONAL)),
    FRIENDLY(AiTask.Rewrite(AiTone.FRIENDLY)),
    POLITE(AiTask.Rewrite(AiTone.POLITE)),
    CONCISE(AiTask.Rewrite(AiTone.CONCISE)),
    APOLOGETIC(AiTask.Rewrite(AiTone.APOLOGETIC)),
    HONORIFIC(AiTask.Rewrite(AiTone.HONORIFIC)),
    ROMANTIC(AiTask.Rewrite(AiTone.ROMANTIC)),
    CONFIDENT(AiTask.Rewrite(AiTone.CONFIDENT)),
    FUNNY(AiTask.Rewrite(AiTone.FUNNY)),
    PERSUASIVE(AiTask.Rewrite(AiTone.PERSUASIVE)),
}

/**
 * The tiles of the AI tools grid (ADR 0041), each an AI feature that works today:
 *
 * - [TRANSLATE] opens translate mode (ADR 0037, `/ai/translate`);
 * - the [quick] actions run on the field's text with the review panel (`/ai/transform`);
 * - [REPLY], [EXPLAIN] and [SUMMARIZE] open AI Assist on that action (ADR 0031): they read a received or selected text
 *   (the selection, or a copied message pasted on a tap) and their result is inserted, not swapped in (`/ai/replies`,
 *   `/ai/transform`);
 * - [EMAIL] opens Write Email (`/ai/drafts`), [ASSIST] opens AI Assist as it was left.
 */
enum class AiTool(val icon: ToolIcon, val quick: AiQuickAction? = null, val assist: AiAssistAction? = null) {
    TRANSLATE(ToolIcon.TRANSLATE),
    GRAMMAR(ToolIcon.GRAMMAR, quick = AiQuickAction.GRAMMAR),
    REWRITE(ToolIcon.REWRITE, quick = AiQuickAction.REWRITE),
    SIMPLIFY(ToolIcon.SIMPLIFY, quick = AiQuickAction.SIMPLIFY),
    SHORTEN(ToolIcon.SHORTEN, quick = AiQuickAction.SHORTEN),
    EXPAND(ToolIcon.EXPAND_TEXT, quick = AiQuickAction.EXPAND),
    CLARIFY(ToolIcon.CLARIFY, quick = AiQuickAction.CLARIFY),
    FORMAT(ToolIcon.FORMAT, assist = AiAssistAction.FORMAT),
    REPLY(ToolIcon.REPLY, assist = AiAssistAction.REPLY),
    EMAIL(ToolIcon.EMAIL),
    EXPLAIN(ToolIcon.EXPLAIN, assist = AiAssistAction.EXPLAIN),
    SUMMARIZE(ToolIcon.SUMMARIZE, assist = AiAssistAction.SUMMARIZE),
    PROFESSIONAL(ToolIcon.PROFESSIONAL, quick = AiQuickAction.PROFESSIONAL),
    FRIENDLY(ToolIcon.FRIENDLY, quick = AiQuickAction.FRIENDLY),
    POLITE(ToolIcon.POLITE, quick = AiQuickAction.POLITE),
    CONCISE(ToolIcon.CONCISE, quick = AiQuickAction.CONCISE),
    APOLOGETIC(ToolIcon.APOLOGETIC, quick = AiQuickAction.APOLOGETIC),
    HONORIFIC(ToolIcon.HONORIFIC, quick = AiQuickAction.HONORIFIC),
    ROMANTIC(ToolIcon.ROMANTIC, quick = AiQuickAction.ROMANTIC),
    CONFIDENT(ToolIcon.CONFIDENT, quick = AiQuickAction.CONFIDENT),
    FUNNY(ToolIcon.FUNNY, quick = AiQuickAction.FUNNY),
    PERSUASIVE(ToolIcon.PERSUASIVE, quick = AiQuickAction.PERSUASIVE),
    ASSIST(ToolIcon.ASSIST),
    ;

    /** The rewrite tone of a tone tile, or null. */
    val tone: AiTone? get() = (quick?.task as? AiTask.Rewrite)?.tone

    /**
     * The cached plan state says the plan does not include this tool (unknown state never locks). AI Assist offers every
     * text action: it is locked only when the plan includes none of them.
     */
    fun lockedBy(a: AiAvailability): Boolean = when (this) {
        TRANSLATE -> a.locks(AiTaskKind.TRANSLATE)
        EMAIL -> a.locks(AiTaskKind.EMAIL)
        ASSIST -> AiTaskKind.entries.filter { it != AiTaskKind.EMAIL }.all { a.locks(it) }
        else -> a.locks(quick?.task?.kind ?: assist!!.kind)
    }

    /** Runs the tool: the same flows as the AI row's chips and the Assist and Email panels. */
    fun runOn(host: AiToolHost) {
        val q = quick
        val action = assist
        when {
            this == TRANSLATE -> host.openTranslate()
            this == EMAIL -> host.openEmail()
            this == ASSIST -> host.openAssist(null)
            q != null -> host.runQuick(q)
            action != null -> host.openAssist(action)
        }
    }
}

/** One tile: [locked] = the cached plan state excludes it (it still opens and says why, ADR 0031). */
data class AiToolEntry(val tool: AiTool, val locked: Boolean = false)

/** What the AI tools do (implemented by the service; faked in tests). */
interface AiToolHost {
    fun openTranslate()

    /** Runs [action] on the field's text; its result opens in the review panel above the bar. */
    fun runQuick(action: AiQuickAction)

    /** Opens AI Assist in place of the keys, on [action] when set. */
    fun openAssist(action: AiAssistAction?)

    fun openEmail()
}

object AiToolSet {
    /**
     * The grid's tiles in order (4 columns): translate and the quick fixes; reply, e-mail and understanding; then the tones;
     * AI Assist last. [emailFirst]: the field looks like an e-mail body, so Write Email comes first. Only the cached plan
     * state is read (no call); signed out, offline or an update needed are one line above the grid, not locks.
     */
    fun tiles(availability: AiAvailability, emailFirst: Boolean = false): List<AiToolEntry> {
        val order = if (emailFirst) listOf(AiTool.EMAIL) + AiTool.entries.filter { it != AiTool.EMAIL } else AiTool.entries
        return order.map { AiToolEntry(it, it.lockedBy(availability)) }
    }
}
