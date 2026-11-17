package com.qalsmartkeyboard.ime

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * Candidate strip above the keys (SD 6.2 `KeyboardSurface`; custom View per ADR 0003). Shows the
 * candidates left to right as they come from the core (index 0 is the default composition, drawn
 * bold); a tap reports the candidate's index. Candidates that do not fit are not drawn.
 *
 * While the active transliteration profile is DRAFT, a "DRAFT" tag marks everything shown as not
 * verified (rule N9). The service hides the strip in password fields. Typed text is drawn only
 * here, never logged (N2).
 */
class CandidateStripView(context: Context) : View(context) {
    /** Tap on the candidate at this index. */
    var onSelect: (Int) -> Unit = {}

    private var items: List<Suggestion> = emptyList()
    private var draft = false
    private val hits = ArrayList<Pair<RectF, Int>>()

    private val d = resources.displayMetrics.density
    private val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val bg = if (dark) 0xFF1B1B1F.toInt() else 0xFFE4E5EA.toInt()
    private val textColor = if (dark) 0xFFF1F1F4.toInt() else 0xFF1B1B1F.toInt()
    private val divider = if (dark) 0xFF3A3B40.toInt() else 0xFFC9CBD3.toInt()
    private val draftColor = 0xFFB26A00.toInt()

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f * d }
    private val bold = Paint(text).apply { typeface = Typeface.DEFAULT_BOLD }
    private val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * d
        typeface = Typeface.DEFAULT_BOLD
        color = draftColor
    }
    private val line = Paint().apply { color = divider; strokeWidth = 1f * d }
    private val tagLabel = context.getString(R.string.candidate_strip_draft)

    fun show(candidates: List<Suggestion>, isDraft: Boolean) {
        items = candidates
        draft = isDraft
        contentDescription = if (isDraft) context.getString(R.string.candidate_strip_draft_description) else null
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(suggestedMinimumWidth, widthMeasureSpec), (40f * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bg)
        hits.clear()
        val pad = 14f * d
        var x = 8f * d
        val cy = height / 2f
        if (draft) {
            val w = tag.measureText(tagLabel)
            canvas.drawText(tagLabel, x, cy - (tag.descent() + tag.ascent()) / 2, tag)
            x += w + 10f * d
        }
        items.forEachIndexed { i, s ->
            val p = if (i == 0) bold else text
            p.color = textColor
            val w = p.measureText(s.text) + pad * 2
            if (x + w > width) return@forEachIndexed
            if (i > 0) canvas.drawLine(x, height * 0.25f, x, height * 0.75f, line)
            canvas.drawText(s.text, x + pad, cy - (p.descent() + p.ascent()) / 2, p)
            hits += RectF(x, 0f, x + w, height.toFloat()) to i
            x += w
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            hits.firstOrNull { it.first.contains(e.x, e.y) }?.let {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onSelect(it.second)
            }
        }
        return true
    }
}
