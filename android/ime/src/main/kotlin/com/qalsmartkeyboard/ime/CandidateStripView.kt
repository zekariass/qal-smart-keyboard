package com.qalsmartkeyboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.qalsmartkeyboard.ime.theme.KeyboardTheme
import com.qalsmartkeyboard.ime.theme.ThemeCatalog

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

    /** Long press on the candidate at this index (A1.8: add it to the personal dictionary). */
    var onLongSelect: (Int) -> Unit = {}

    private var items: List<Suggestion> = emptyList()
    private var draft = false
    private val hits = ArrayList<Pair<RectF, Int>>()

    private val d = resources.displayMetrics.density
    /** The colours to draw with. Set by the service when the chosen theme changes; the next draw uses it. */
    var theme: KeyboardTheme = ThemeCatalog.classic(
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES,
    )
        set(value) {
            if (field === value) return
            field = value
            invalidate()
        }

    private val dark get() = !theme.isLight
    private val bg get() = theme.stripBg
    private val textColor get() = theme.stripText
    private val divider get() = theme.stripDivider
    private val draftColor get() = theme.warn

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f * d }
    private val bold = Paint(text).apply { typeface = Typeface.DEFAULT_BOLD }
    private val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * d
        typeface = Typeface.DEFAULT_BOLD
    }
    private val line = Paint().apply { strokeWidth = 1f * d }
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
        tag.color = draftColor
        line.color = divider
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

    private var pressed = -1
    private var longFired = false
    private val longPress = Runnable {
        if (pressed >= 0) {
            longFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongSelect(pressed)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hits.firstOrNull { it.first.contains(e.x, e.y) }?.second ?: -1
                longFired = false
                if (pressed >= 0) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val hit = hits.firstOrNull { it.first.contains(e.x, e.y) }?.second
                if (!longFired && hit != null && hit == pressed) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onSelect(hit)
                }
                pressed = -1
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                pressed = -1
            }
            MotionEvent.ACTION_MOVE ->
                if (pressed >= 0 && hits.none { it.second == pressed && it.first.contains(e.x, e.y) }) {
                    removeCallbacks(longPress)
                    pressed = -1
                }
        }
        return true
    }
}
