package com.qalsmartkeyboard.ime

import android.content.Context
import android.widget.FrameLayout
import com.qalsmartkeyboard.ime.ai.AiBlock
import com.qalsmartkeyboard.ime.ai.AiTaskKind
import com.qalsmartkeyboard.ime.ai.AiToolbarView
import com.qalsmartkeyboard.ime.toolbar.Tool
import com.qalsmartkeyboard.ime.toolbar.ToolContext
import com.qalsmartkeyboard.ime.toolbar.ToolSet

/**
 * The one bar above the keys: the [strip] (tools button, mode pill, shortcuts / candidates / chooser, mic). Always
 * [CandidateStripView.HEIGHT_DP] tall, so switching modes, languages, tools or fields never changes the keyboard's idle
 * height (ADR 0007 D45, ADR 0035). The quick actions' result panel (working, review, errors) is [ai], a separate view
 * above this bar that is shown only while it has content.
 *
 * States of the middle:
 * - **idle**: the pinned shortcuts ([ToolSet.shortcutCandidates] for the field, the first ones that fit). The "AI" shortcut
 *   opens the AI tools grid in place of the keys (ADR 0041); Translate opens the translate panel above the bar (ADR 0037);
 * - **composing** (phonetic candidates): the candidates;
 * - **chooser**: the writing-mode options;
 * - **tool panel open** ([CandidateStripView.panelTitle]: tools, clipboard, AI tools): a back arrow and the panel's title;
 * - **sensitive field** (password, email, ...; [com.qalsmartkeyboard.ime.ai.AiToolbarController.visible] false): no AI
 *   shortcuts, no candidates; clipboard and emoji only where the field allows them ([ToolContext]).
 *
 * Nothing here reads or sends text: only the AI tools' tiles do, on a tap.
 */
class KeyboardBar(context: Context, val strip: CandidateStripView, val ai: AiToolbarView) : FrameLayout(context) {
    /**
     * The field's tool context without the AI part (the service sets it from the field profile, the voice and
     * writing-mode state); the bar adds the AI client's state ([toolContext]).
     */
    var fieldContext: () -> ToolContext = { ToolContext(InputProfile.DEFAULT) }

    /** A shortcut was tapped. The service routes it through its tools controller. */
    var onTool: (Tool) -> Unit = {}

    init {
        addView(strip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        strip.onShortcut = { t -> onTool(t) }
        ai.onRendered = { sync() } // the plan state (locks) or the field's AI permission may have changed
        sync()
    }

    /** The field's full tool context: [fieldContext] plus whether the AI client is attached, allowed and blocked or locked. */
    fun toolContext(): ToolContext {
        val c = ai.controller
        val allowed = c?.visible == true
        val a = if (allowed) c?.availability else null
        return fieldContext().copy(
            aiReady = allowed,
            aiBlocked = a != null && a.block != AiBlock.NONE,
            translateLocked = a?.locks(AiTaskKind.TRANSLATE) == true,
            // The AI tools offer every feature: the entry is locked only when the plan includes none of them.
            aiLocked = a != null && AiTaskKind.entries.all { a.locks(it) },
        )
    }

    /** Re-derives the strip's shortcuts from the field and the AI client (cheap; the strip ignores an unchanged list). */
    fun sync() {
        strip.setShortcuts(ToolSet.shortcutCandidates(toolContext()))
    }
}
