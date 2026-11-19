package com.qalsmartkeyboard.ime

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import com.qalsmartkeyboard.ime.CandidateStripView.AiEntry
import com.qalsmartkeyboard.ime.ai.AiBlock
import com.qalsmartkeyboard.ime.ai.AiTaskKind
import com.qalsmartkeyboard.ime.ai.AiToolbarView
import com.qalsmartkeyboard.ime.toolbar.Motion
import com.qalsmartkeyboard.ime.toolbar.Tool
import com.qalsmartkeyboard.ime.toolbar.ToolContext
import com.qalsmartkeyboard.ime.toolbar.ToolSet

/**
 * The one bar above the keys: the [strip] (tools button, mode pill, shortcuts / candidates / chooser, mic) with the AI
 * actions ([AiToolbarView.actionsRow]) laid over its middle while the user has opened them. Always
 * [CandidateStripView.HEIGHT_DP] tall, so switching modes, languages, tools or fields never changes the keyboard's idle
 * height (ADR 0007 D45, ADR 0035). The AI panel (picker, working, review, errors) is [ai] itself, a separate view above
 * this bar that is shown only while it has content.
 *
 * States of the middle:
 * - **idle**: the pinned shortcuts ([ToolSet.shortcutCandidates] for the field, the first ones that fit);
 * - **composing** (phonetic candidates): the candidates; open actions close;
 * - **chooser**: the writing-mode options; opening it closes the AI actions and vice versa;
 * - **AI open**: a close (×) button and the actions row (opened by the AI or Translate shortcut);
 * - **tool panel open** ([CandidateStripView.panelTitle]): a back arrow and the panel's title;
 * - **sensitive field** (password, email, ...; [com.qalsmartkeyboard.ime.ai.AiToolbarController.visible] false): no AI
 *   shortcuts, no candidates; clipboard and emoji only where the field allows them ([ToolContext]).
 *
 * Opening the actions refreshes the cached plan state ([com.qalsmartkeyboard.ime.ai.AiToolbarController.onToolbarOpened],
 * ADR 0007 D40). It never reads or sends text: only the action chips do, on a tap.
 */
class KeyboardBar(context: Context, val strip: CandidateStripView, val ai: AiToolbarView) : FrameLayout(context) {
    private val row: View = ai.actionsRow

    /** The user opened the AI actions (and nothing since closed them). */
    var aiOpen: Boolean = false
        private set

    /**
     * The field's tool context without the AI part (the service sets it from the field profile, the voice and
     * writing-mode state); the bar adds the AI client's state ([toolContext]).
     */
    var fieldContext: () -> ToolContext = { ToolContext(InputProfile.DEFAULT) }

    /**
     * A shortcut was tapped. The service routes it through its tools controller; without one (tests, before the service
     * wires it) Translate and AI open the actions row.
     */
    var onTool: (Tool) -> Unit = { t -> if (t == Tool.TRANSLATE || t == Tool.AI_ACTIONS) setAiOpen(true) }

    private var syncing = false

    /** Something the layout depends on changed since the last pass (the strip's middle, the row's visibility). */
    private var layoutDirty = true

    init {
        addView(strip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(row, LayoutParams(0, LayoutParams.MATCH_PARENT))
        row.visibility = GONE
        strip.onAiToggle = { setAiOpen(!aiOpen) }
        strip.onShortcut = { t -> onTool(t) }
        strip.onMiddleChanged = {
            layoutDirty = true
            sync()
        }
        ai.onRendered = { sync() }
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
            // Assist offers every text action: it is locked only when the plan includes none of them.
            assistLocked = a != null && AiTaskKind.entries.filter { it != AiTaskKind.EMAIL }.all { a.locks(it) },
            emailLocked = a?.locks(AiTaskKind.EMAIL) == true,
        )
    }

    /** Opens or closes the AI actions in the middle of the bar. */
    fun setAiOpen(open: Boolean) {
        val allowed = ai.controller?.visible == true
        val next = open && allowed
        if (next == aiOpen) return
        aiOpen = next
        if (next) {
            strip.closeModeChooser()
            ai.controller?.onToolbarOpened()
            ai.render() // the actions show the current (cached) plan state
            ai.actionsRow.scrollTo(0, 0) // the direction control and Translate come first
        } else {
            ai.controller?.closePicker()
        }
        sync()
    }

    /** A new field or the keyboard was hidden: the actions start closed again. */
    fun closeAi() = setAiOpen(false)

    /** Re-derives the strip's AI entry, its shortcuts and the row's visibility from the controller and the strip. */
    fun sync() {
        if (syncing) return
        syncing = true
        try {
            val allowed = ai.controller?.visible == true
            // Candidates, the chooser or a tool panel take the bar: the actions close (and any open picker with them).
            if (aiOpen && (!allowed || strip.hasCandidates || strip.isModeChooserOpen || strip.panelTitle != null)) {
                aiOpen = false
                ai.controller?.closePicker()
            }
            strip.aiEntry = when {
                !allowed -> AiEntry.NONE
                aiOpen -> AiEntry.OPEN
                else -> AiEntry.CLOSED
            }
            strip.setShortcuts(ToolSet.shortcutCandidates(toolContext()))
            val show = strip.showsAiActions
            val vis = if (show) VISIBLE else GONE
            if (row.visibility != vis) {
                row.visibility = vis
                layoutDirty = true
                if (show) Motion.reveal(row, fromXPx = -REVEAL_DP * resources.displayMetrics.density)
            }
            // Called on every keyboard refresh: only a real change costs a layout pass (never per key).
            if (layoutDirty) {
                layoutDirty = false
                requestLayout()
            }
        } finally {
            syncing = false
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        strip.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val h = strip.measuredHeight
        if (row.visibility != GONE) {
            val (start, end) = strip.middleRange(w)
            row.measure(
                MeasureSpec.makeMeasureSpec((end - start).toInt().coerceAtLeast(0), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = right - left
        strip.layout(0, 0, w, strip.measuredHeight)
        if (row.visibility != GONE) {
            val start = strip.middleRange(w).first.toInt()
            row.layout(start, 0, start + row.measuredWidth, row.measuredHeight)
        }
    }

    private companion object {
        /** The actions row slides this far (dp) out from the close button as it fades in. */
        const val REVEAL_DP = 12f
    }
}
