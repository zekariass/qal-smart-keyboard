package com.qalsmartkeyboard.ime

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.min

/**
 * The keyboard's top bar (SD 6.2 `KeyboardSurface`; custom View per ADR 0003): the writing-mode pill
 * on the left (am/ti only), the candidates in the middle and the settings gear on the right. It is
 * shown in every field so the keyboard keeps one height; candidates appear only where the service
 * passes them (phonetic modes, never in password fields).
 *
 * Candidates are drawn left to right as they come from the core (index 0 is the default composition,
 * drawn bold); a tap reports the candidate's index. Candidates that do not fit are not drawn. While
 * the active transliteration profile is DRAFT, a "DRAFT" tag marks them as not verified (rule N9).
 *
 * The pill names the active writing mode (ፊደል / Phonetic / English); a tap opens a three-option
 * chooser in place of the candidates, a second tap or a choice closes it. Typed text is drawn only
 * here, never logged (N2). Colours: only the fields below (themes replace their values).
 */
class CandidateStripView(context: Context) : View(context) {
    /** Tap on the candidate at this index. */
    var onSelect: (Int) -> Unit = {}

    /** Long press on the candidate at this index (A1.8: add it to the personal dictionary). */
    var onLongSelect: (Int) -> Unit = {}

    /** The settings gear was tapped. */
    var onSettings: () -> Unit = {}

    /** A writing mode was picked in the chooser. */
    var onModeSelected: (LayoutKind) -> Unit = {}

    private var items: List<Suggestion> = emptyList()
    private var draft = false
    private var modes: List<LayoutKind> = emptyList()
    private var activeMode: LayoutKind = LayoutKind.ABC
    private var chooserOpen = false

    /** What a touch can land on. */
    private sealed interface Target {
        data class Candidate(val index: Int) : Target
        data object Pill : Target
        data object Gear : Target
        data class Mode(val kind: LayoutKind) : Target
    }

    private val hits = ArrayList<Pair<RectF, Target>>()

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
    private val shape = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f * d; textAlign = Paint.Align.CENTER }
    private val tagLabel = context.getString(R.string.candidate_strip_draft)
    private val settingsLabel = context.getString(R.string.top_bar_settings)

    init {
        contentDescription = settingsLabel
    }

    fun show(candidates: List<Suggestion>, isDraft: Boolean) {
        items = candidates
        draft = isDraft
        if (candidates.isNotEmpty()) chooserOpen = false
        updateDescription()
        invalidate()
    }

    /** The writing-mode selector: [options] in selector order (empty = no pill), [active] the current one. */
    fun setModes(options: List<LayoutKind>, active: LayoutKind) {
        if (options != modes || active != activeMode) chooserOpen = false
        modes = options
        activeMode = active
        updateDescription()
        invalidate()
    }

    /** Closes the mode chooser (any keyboard change does). */
    fun closeModeChooser() {
        if (!chooserOpen) return
        chooserOpen = false
        invalidate()
    }

    private fun updateDescription() {
        val parts = ArrayList<String>()
        if (modes.isNotEmpty()) parts += context.getString(R.string.top_bar_mode, LanguageCatalog.spokenWritingMode(activeMode))
        if (draft) parts += context.getString(R.string.candidate_strip_draft_description)
        parts += settingsLabel
        contentDescription = parts.joinToString(", ")
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(suggestedMinimumWidth, widthMeasureSpec), (HEIGHT_DP * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bg)
        hits.clear()
        val cy = height / 2f
        // Settings gear: a full-height square touch target on the right.
        val gearW = height.toFloat()
        val gearRect = RectF(width - gearW, 0f, width.toFloat(), height.toFloat())
        drawGear(canvas, gearRect.centerX(), cy, 10f * d)
        hits += gearRect to Target.Gear
        var x = 8f * d
        if (modes.isNotEmpty()) {
            x = drawPill(canvas, x, cy)
        }
        val end = gearRect.left
        if (chooserOpen && modes.isNotEmpty()) {
            drawChooser(canvas, x, end, cy)
            return
        }
        if (draft && items.isNotEmpty()) {
            val w = tag.measureText(tagLabel)
            canvas.drawText(tagLabel, x, cy - (tag.descent() + tag.ascent()) / 2, tag)
            x += w + 10f * d
        }
        val pad = 14f * d
        items.forEachIndexed { i, s ->
            val p = if (i == 0) bold else text
            p.color = textColor
            val w = p.measureText(s.text) + pad * 2
            if (x + w > end) return@forEachIndexed
            if (i > 0) canvas.drawLine(x, height * 0.25f, x, height * 0.75f, line)
            canvas.drawText(s.text, x + pad, cy - (p.descent() + p.ascent()) / 2, p)
            hits += RectF(x, 0f, x + w, height.toFloat()) to Target.Candidate(i)
            x += w
        }
    }

    /** The mode pill; returns the x where the candidates start. Its touch target is the full bar height. */
    private fun drawPill(canvas: Canvas, left: Float, cy: Float): Float {
        val label = LanguageCatalog.writingModeLabel(activeMode)
        chip.typeface = Typeface.DEFAULT_BOLD
        val chevron = 8f * d
        val w = chip.measureText(label) + 24f * d + chevron
        val h = PILL_DP * d
        val r = RectF(left, cy - h / 2, left + w, cy + h / 2)
        shape.style = Paint.Style.FILL
        shape.color = divider
        canvas.drawRoundRect(r, h / 2, h / 2, shape)
        chip.color = textColor
        val textCx = r.left + 12f * d + chip.measureText(label) / 2
        canvas.drawText(label, textCx, cy - (chip.descent() + chip.ascent()) / 2, chip)
        // A small chevron: up while the chooser is open, down otherwise.
        val cx = r.right - 12f * d - chevron / 2 + 2f * d
        val half = chevron / 2
        val dir = if (chooserOpen) -1f else 1f
        val path = Path().apply {
            moveTo(cx - half, cy - dir * half / 2)
            lineTo(cx + half, cy - dir * half / 2)
            lineTo(cx, cy + dir * half / 2)
            close()
        }
        canvas.drawPath(path, chip)
        chip.typeface = Typeface.DEFAULT
        hits += RectF(0f, 0f, r.right + 4f * d, height.toFloat()) to Target.Pill
        // A divider separates the pill from the candidates.
        canvas.drawLine(r.right + 8f * d, height * 0.25f, r.right + 8f * d, height * 0.75f, line)
        return r.right + 16f * d
    }

    /** The three-option chooser: the selected mode filled, the others outlined. */
    private fun drawChooser(canvas: Canvas, start: Float, end: Float, cy: Float) {
        val gap = 6f * d
        val h = PILL_DP * d
        val w = min(((end - start) - gap * (modes.size + 1)) / modes.size, 120f * d)
        var x = start
        for (k in modes) {
            val r = RectF(x, cy - h / 2, x + w, cy + h / 2)
            val selected = k == activeMode
            shape.color = divider
            shape.style = if (selected) Paint.Style.FILL else Paint.Style.STROKE
            shape.strokeWidth = 1.5f * d
            canvas.drawRoundRect(r, h / 2, h / 2, shape)
            shape.style = Paint.Style.FILL
            chip.color = textColor
            chip.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val label = LanguageCatalog.writingModeLabel(k)
            val size = chip.textSize
            val tw = chip.measureText(label)
            if (tw > w - 8f * d) chip.textSize = size * (w - 8f * d) / tw
            canvas.drawText(label, r.centerX(), cy - (chip.descent() + chip.ascent()) / 2, chip)
            chip.textSize = size
            hits += RectF(r.left - gap / 2, 0f, r.right + gap / 2, height.toFloat()) to Target.Mode(k)
            x += w + gap
        }
        chip.typeface = Typeface.DEFAULT
    }

    /** A gear outline in [textColor]: eight teeth around a ring with an open centre. */
    private fun drawGear(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        shape.color = textColor
        shape.style = Paint.Style.FILL
        val toothW = r * 0.42f
        for (i in 0 until 8) {
            canvas.save()
            canvas.rotate(i * 45f, cx, cy)
            canvas.drawRoundRect(cx - toothW / 2, cy - r, cx + toothW / 2, cy - r * 0.55f, toothW * 0.25f, toothW * 0.25f, shape)
            canvas.restore()
        }
        canvas.drawCircle(cx, cy, r * 0.72f, shape)
        shape.color = bg
        canvas.drawCircle(cx, cy, r * 0.32f, shape)
    }

    private var pressed: Target? = null
    private var longFired = false
    private val longPress = Runnable {
        val p = pressed
        if (p is Target.Candidate) {
            longFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongSelect(p.index)
        }
    }

    private fun targetAt(x: Float, y: Float): Target? = hits.firstOrNull { it.first.contains(x, y) }?.second

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = targetAt(e.x, e.y)
                longFired = false
                if (pressed is Target.Candidate) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val hit = targetAt(e.x, e.y)
                val p = pressed
                pressed = null
                if (!longFired && hit != null && hit == p) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    when (hit) {
                        is Target.Candidate -> onSelect(hit.index)
                        Target.Gear -> { chooserOpen = false; invalidate(); onSettings() }
                        Target.Pill -> { chooserOpen = !chooserOpen; invalidate() }
                        is Target.Mode -> {
                            chooserOpen = false
                            invalidate()
                            if (hit.kind != activeMode) onModeSelected(hit.kind)
                        }
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                pressed = null
            }
            MotionEvent.ACTION_MOVE ->
                if (pressed != null && targetAt(e.x, e.y) != pressed) {
                    removeCallbacks(longPress)
                    pressed = null
                }
        }
        return true
    }

    companion object {
        const val HEIGHT_DP = 40f
        private const val PILL_DP = 28f
    }
}
