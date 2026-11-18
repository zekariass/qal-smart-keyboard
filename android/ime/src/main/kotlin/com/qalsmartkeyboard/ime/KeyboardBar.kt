package com.qalsmartkeyboard.ime

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import com.qalsmartkeyboard.ime.CandidateStripView.AiEntry
import com.qalsmartkeyboard.ime.ai.AiToolbarView

/**
 * The one bar above the keys: the [strip] (mode pill, AI entry, candidates or chooser, gear) with the
 * AI actions ([AiToolbarView.actionsRow]) laid over its middle while the user has opened them.
 * Always [CandidateStripView.HEIGHT_DP] tall, so switching modes, languages or fields never changes
 * the keyboard's idle height. The AI panel (picker, working, review, errors) is [ai] itself, a
 * separate view above this bar that is shown only while it has content.
 *
 * States of the middle:
 * - **idle**: AI entry (where AI is allowed), else empty;
 * - **composing** (phonetic candidates): the candidates; the AI entry hides and open actions close;
 * - **chooser**: the writing-mode options; opening it closes the AI actions and vice versa;
 * - **AI open**: the actions row, the entry turned into a close (×) button;
 * - **sensitive field** (password, email, ...; [com.qalsmartkeyboard.ime.ai.AiToolbarController.visible]
 *   false): no AI entry, no candidates, only the gear (and no pill).
 *
 * Opening the actions refreshes the cached plan state ([com.qalsmartkeyboard.ime.ai.AiToolbarController.onToolbarOpened],
 * ADR 0007 D40). It never reads or sends text: only the action chips do, on a tap.
 */
class KeyboardBar(context: Context, val strip: CandidateStripView, val ai: AiToolbarView) : FrameLayout(context) {
    private val row: View = ai.actionsRow

    /** The user opened the AI actions (and nothing since closed them). */
    var aiOpen: Boolean = false
        private set

    private var syncing = false

    init {
        addView(strip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(row, LayoutParams(0, LayoutParams.MATCH_PARENT))
        row.visibility = GONE
        strip.onAiToggle = { setAiOpen(!aiOpen) }
        strip.onMiddleChanged = { sync() }
        ai.onRendered = { sync() }
        sync()
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
        } else {
            ai.controller?.closePicker()
        }
        sync()
    }

    /** A new field or the keyboard was hidden: the actions start closed again. */
    fun closeAi() = setAiOpen(false)

    /** Re-derives the strip's AI entry and the row's visibility from the controller and the strip. */
    private fun sync() {
        if (syncing) return
        syncing = true
        try {
            val allowed = ai.controller?.visible == true
            // Candidates or the chooser take the middle: the actions close (and any open picker with them).
            if (aiOpen && (!allowed || strip.hasCandidates || strip.isModeChooserOpen)) {
                aiOpen = false
                ai.controller?.closePicker()
            }
            strip.aiEntry = when {
                !allowed -> AiEntry.NONE
                aiOpen -> AiEntry.OPEN
                else -> AiEntry.CLOSED
            }
            val show = strip.showsAiActions
            val vis = if (show) VISIBLE else GONE
            if (row.visibility != vis) row.visibility = vis
            requestLayout()
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
}
