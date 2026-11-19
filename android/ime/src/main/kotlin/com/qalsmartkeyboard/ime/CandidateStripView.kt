package com.qalsmartkeyboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.customview.widget.ExploreByTouchHelper
import kotlin.math.ceil
import kotlin.math.min
import com.qalsmartkeyboard.ime.theme.KeyboardTheme
import com.qalsmartkeyboard.ime.theme.ThemeCatalog

/**
 * The keyboard's one bar above the keys (SD 6.2 `KeyboardSurface`; custom View per ADR 0003), hosted
 * by [KeyboardBar]. Left to right:
 *
 * - the **writing-mode pill** (am/ti only): names the active mode (ፊደል / Phonetic / English); a tap
 *   opens a three-option chooser in the middle, a second tap or a choice closes it;
 * - the **AI entry** ([aiEntry], only where AI is allowed and nothing else uses the middle): a small
 *   "AI" chip that opens the AI actions in the middle ([KeyboardBar] lays them over [middleRange]);
 *   while they are open it is a close (×) button;
 * - the **middle**: candidates while composing in a phonetic mode (they replace the AI entry), the
 *   mode chooser, or the AI actions;
 * - the **microphone** ([micShown]; voice typing, ADR 0029): drawn filled with the accent while voice typing is open;
 * - the **settings gear**.
 *
 * It is shown in every field and always [HEIGHT_DP] tall, so the keyboard keeps one height.
 * Candidates are drawn left to right as they come from the core (index 0 is the default composition,
 * drawn bold); a tap reports the candidate's index, a long press adds it to the personal dictionary.
 * Candidates that do not fit are not drawn. While the active transliteration profile is DRAFT, a
 * "DRAFT" tag marks them as not verified (rule N9).
 *
 * Accessibility: pill, AI entry, DRAFT tag, candidates, chooser options and gear are virtual views
 * ([BarNodes], `ExploreByTouchHelper`) with a label and a role; an accessibility click does what a
 * tap does. Typed text is drawn only here, never logged (N2). Colours: only the theme's strip tokens.
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

    /** The microphone was tapped (voice typing). */
    var onMic: () -> Unit = {}

    /** The microphone is offered here (setting on, a speech service installed, not a password field). */
    var micShown: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            changed()
        }

    /** Voice typing is open: the microphone is drawn active and its tap stops. */
    var micActive: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
            nodes.invalidateRoot()
        }

    /** The AI entry (open) or its close button was tapped. */
    var onAiToggle: () -> Unit = {}

    /** What uses the middle, or its extent, may have changed ([KeyboardBar] re-lays the AI actions). */
    var onMiddleChanged: () -> Unit = {}

    /** The AI entry: none (AI not allowed here or not attached), closed, or open (actions in the middle). */
    enum class AiEntry { NONE, CLOSED, OPEN }

    var aiEntry: AiEntry = AiEntry.NONE
        set(value) {
            if (field == value) return
            field = value
            changed()
        }

    private var items: List<Suggestion> = emptyList()
    private var draft = false
    private var modes: List<LayoutKind> = emptyList()
    private var activeMode: LayoutKind = LayoutKind.ABC
    private var chooserOpen = false

    /** Candidates are shown (composing in a phonetic mode). */
    val hasCandidates: Boolean get() = items.isNotEmpty()

    /** The writing-mode chooser is open in the middle. */
    val isModeChooserOpen: Boolean get() = chooserOpen && modes.isNotEmpty()

    /** The AI entry is drawn: AI is allowed and neither candidates nor the chooser use the middle. */
    val showsAiEntry: Boolean get() = aiEntry != AiEntry.NONE && items.isEmpty() && !isModeChooserOpen

    /** The middle belongs to the AI actions. */
    val showsAiActions: Boolean get() = showsAiEntry && aiEntry == AiEntry.OPEN

    /** What a touch (or an accessibility action) can land on. */
    sealed interface Target {
        data class Candidate(val index: Int) : Target
        data object Pill : Target
        data object Ai : Target
        data object Gear : Target
        data object Mic : Target
        data object DraftTag : Target
        data class Mode(val kind: LayoutKind) : Target
    }

    /** One placed element: [hit] is its touch target (full bar height), [shape] what is drawn. */
    class Slot(val target: Target, val hit: RectF, val shape: RectF, val label: String = "")

    private class Geometry(val width: Int, val slots: List<Slot>, val middleStart: Float, val middleEnd: Float, val dividers: List<Float>)

    private var geo: Geometry? = null

    private val d = resources.displayMetrics.density
    private val barHeight get() = HEIGHT_DP * d

    /** The colours to draw with. Set by the service when the chosen theme changes; the next draw uses it. */
    var theme: KeyboardTheme = ThemeCatalog.classic(
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES,
    )
        set(value) {
            if (field === value) return
            field = value
            invalidate()
        }

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
    private val shape = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f * d; textAlign = Paint.Align.CENTER }
    private val chipBold = Paint(chip).apply { typeface = Typeface.DEFAULT_BOLD }
    private val tagLabel = context.getString(R.string.candidate_strip_draft)
    private val aiLabel = context.getString(R.string.top_bar_ai_label)

    fun show(candidates: List<Suggestion>, isDraft: Boolean) {
        if (candidates == items && isDraft == draft) return
        items = candidates
        draft = isDraft
        if (candidates.isNotEmpty()) chooserOpen = false
        changed()
    }

    /** The writing-mode selector: [options] in selector order (empty = no pill), [active] the current one. */
    fun setModes(options: List<LayoutKind>, active: LayoutKind) {
        if (options == modes && active == activeMode) return
        chooserOpen = false
        modes = options
        activeMode = active
        changed()
    }

    /** Closes the mode chooser (any keyboard change does, and opening the AI actions). */
    fun closeModeChooser() {
        if (!chooserOpen) return
        chooserOpen = false
        changed()
    }

    /** State changed: drop the geometry, redraw, refresh the accessibility tree, tell the bar. */
    private fun changed() {
        geo = null
        invalidate()
        nodes.invalidateRoot()
        onMiddleChanged()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(suggestedMinimumWidth, widthMeasureSpec), barHeight.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        geo = null
        nodes.invalidateRoot()
    }

    /** The middle (px from the left) for a bar [width] px wide: where candidates, the chooser or the AI actions go. */
    fun middleRange(width: Int = this.width): Pair<Float, Float> = geometry(width).let { it.middleStart to it.middleEnd }

    /** The placed elements for the current width, left to right (gear last). */
    fun slots(): List<Slot> = geometry(width).slots

    private fun geometry(w: Int): Geometry {
        geo?.let { if (it.width == w) return it }
        return compute(w).also { geo = it }
    }

    private fun compute(w: Int): Geometry {
        val h = barHeight
        val cy = h / 2
        val out = ArrayList<Slot>()
        val dividers = ArrayList<Float>()
        val gear = RectF(w - h, 0f, w.toFloat(), h)
        val pillH = PILL_DP * d
        var x = 8f * d
        if (modes.isNotEmpty()) {
            val label = LanguageCatalog.writingModeLabel(activeMode)
            val pw = chipBold.measureText(label) + 24f * d + CHEVRON_DP * d
            val r = RectF(x, cy - pillH / 2, x + pw, cy + pillH / 2)
            out += Slot(Target.Pill, RectF(0f, 0f, r.right + 4f * d, h), r, label)
            dividers += r.right + 8f * d
            x = r.right + 16f * d
        }
        val mic = if (micShown) RectF(gear.left - h * MIC_WIDTH, 0f, gear.left, h) else null
        val end = mic?.left ?: gear.left
        when {
            isModeChooserOpen -> {
                val gap = 6f * d
                val ow = min(((end - x) - gap * (modes.size + 1)) / modes.size, 120f * d)
                var ox = x
                for (k in modes) {
                    val r = RectF(ox, cy - pillH / 2, ox + ow, cy + pillH / 2)
                    out += Slot(Target.Mode(k), RectF(r.left - gap / 2, 0f, r.right + gap / 2, h), r, LanguageCatalog.writingModeLabel(k))
                    ox += ow + gap
                }
            }
            showsAiEntry -> {
                val aw = if (aiEntry == AiEntry.OPEN) pillH else AI_ICON_DP * d + chipBold.measureText(aiLabel) + 22f * d
                val r = RectF(x, cy - pillH / 2, x + aw, cy + pillH / 2)
                out += Slot(Target.Ai, RectF(r.left - 6f * d, 0f, r.right + 4f * d, h), r, aiLabel)
                x = r.right + 6f * d
            }
            else -> {
                if (draft && items.isNotEmpty()) {
                    val tw = tag.measureText(tagLabel)
                    out += Slot(Target.DraftTag, RectF(x, 0f, x + tw, h), RectF(x, 0f, x + tw, h), tagLabel)
                    x += tw + 10f * d
                }
                val pad = 14f * d
                items.forEachIndexed { i, s ->
                    val p = if (i == 0) bold else text
                    val cw = p.measureText(s.text) + pad * 2
                    if (x + cw > end) return@forEachIndexed
                    if (i > 0) dividers += x
                    val r = RectF(x, 0f, x + cw, h)
                    out += Slot(Target.Candidate(i), r, r, s.text)
                    x += cw
                }
            }
        }
        if (mic != null) out += Slot(Target.Mic, mic, mic, context.getString(R.string.voice_mic))
        out += Slot(Target.Gear, gear, gear, context.getString(R.string.top_bar_settings))
        val middleStart = if (showsAiEntry) x else out.firstOrNull { it.target == Target.Pill }?.let { it.shape.right + 16f * d } ?: 8f * d
        return Geometry(w, out, middleStart, end, dividers)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bg)
        line.color = divider
        val g = geometry(width)
        val cy = height / 2f
        for (s in g.slots) {
            when (val t = s.target) {
                Target.Gear -> drawGear(canvas, s.shape.centerX(), cy, 10f * d)
                Target.Mic -> drawMic(canvas, s.shape.centerX(), cy)
                Target.Pill -> drawPill(canvas, s)
                Target.Ai -> drawAi(canvas, s.shape)
                Target.DraftTag -> {
                    tag.color = draftColor
                    canvas.drawText(tagLabel, s.shape.left, cy - (tag.descent() + tag.ascent()) / 2, tag)
                }
                is Target.Candidate -> {
                    val p = if (t.index == 0) bold else text
                    p.color = textColor
                    canvas.drawText(s.label, s.shape.left + 14f * d, cy - (p.descent() + p.ascent()) / 2, p)
                }
                is Target.Mode -> drawModeOption(canvas, s.shape, s.label, t.kind == activeMode)
            }
        }
        for (x in g.dividers) canvas.drawLine(x, height * 0.25f, x, height * 0.75f, line)
    }

    /** The mode pill with a small chevron: up while the chooser is open, down otherwise. */
    private fun drawPill(canvas: Canvas, s: Slot) {
        val r = s.shape
        val cy = r.centerY()
        shape.style = Paint.Style.FILL
        shape.color = divider
        canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, shape)
        chipBold.color = textColor
        val textCx = r.left + 12f * d + chipBold.measureText(s.label) / 2
        canvas.drawText(s.label, textCx, cy - (chipBold.descent() + chipBold.ascent()) / 2, chipBold)
        val chevron = CHEVRON_DP * d
        val cx = r.right - 12f * d - chevron / 2 + 2f * d
        val half = chevron / 2
        val dir = if (chooserOpen) -1f else 1f
        val path = Path().apply {
            moveTo(cx - half, cy - dir * half / 2)
            lineTo(cx + half, cy - dir * half / 2)
            lineTo(cx, cy + dir * half / 2)
            close()
        }
        canvas.drawPath(path, chipBold)
    }

    /** The AI entry: a pill with a sparkle and "AI" (closed), or a round close button (open). */
    private fun drawAi(canvas: Canvas, r: RectF) {
        val cy = r.centerY()
        shape.style = Paint.Style.FILL
        shape.color = divider
        canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, shape)
        if (aiEntry == AiEntry.OPEN) {
            line.color = textColor
            line.strokeWidth = 1.8f * d
            line.isAntiAlias = true
            val a = r.height() * 0.2f
            canvas.drawLine(r.centerX() - a, cy - a, r.centerX() + a, cy + a, line)
            canvas.drawLine(r.centerX() - a, cy + a, r.centerX() + a, cy - a, line)
            line.strokeWidth = 1f * d
            line.color = divider
            return
        }
        val icon = AI_ICON_DP * d
        drawSparkle(canvas, r.left + 10f * d + icon / 2, cy, icon / 2)
        chipBold.color = textColor
        canvas.drawText(aiLabel, r.left + 10f * d + icon + 4f * d + chipBold.measureText(aiLabel) / 2, cy - (chipBold.descent() + chipBold.ascent()) / 2, chipBold)
    }

    /** A four-pointed sparkle in the text colour. */
    private fun drawSparkle(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val k = r * 0.28f
        val path = Path().apply {
            moveTo(cx, cy - r)
            quadTo(cx + k, cy - k, cx + r, cy)
            quadTo(cx + k, cy + k, cx, cy + r)
            quadTo(cx - k, cy + k, cx - r, cy)
            quadTo(cx - k, cy - k, cx, cy - r)
            close()
        }
        shape.color = textColor
        shape.style = Paint.Style.FILL
        canvas.drawPath(path, shape)
    }

    /** One option of the chooser: the selected mode filled, the others outlined. */
    private fun drawModeOption(canvas: Canvas, r: RectF, label: String, selected: Boolean) {
        val cy = r.centerY()
        shape.color = divider
        shape.style = if (selected) Paint.Style.FILL else Paint.Style.STROKE
        shape.strokeWidth = 1.5f * d
        canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, shape)
        shape.style = Paint.Style.FILL
        val p = if (selected) chipBold else chip
        p.color = textColor
        val size = p.textSize
        val tw = p.measureText(label)
        if (tw > r.width() - 8f * d) p.textSize = size * (r.width() - 8f * d) / tw
        canvas.drawText(label, r.centerX(), cy - (p.descent() + p.ascent()) / 2, p)
        p.textSize = size
    }

    /** The microphone: in the text colour, or on an accent circle while voice typing is open. */
    private fun drawMic(canvas: Canvas, cx: Float, cy: Float) {
        shape.style = Paint.Style.FILL
        if (micActive) {
            shape.color = theme.accent
            canvas.drawCircle(cx, cy, PILL_DP * d / 2, shape)
            shape.color = theme.onAccent
        } else {
            shape.color = textColor
        }
        com.qalsmartkeyboard.ime.voice.VoicePanelView.MainButton.drawMic(canvas, cx, cy, 9f * d, shape, d)
        shape.strokeWidth = 1.5f * d
    }

    /** A gear in [textColor]: eight teeth around a ring with an open centre. */
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

    // ------------------------------------------------------------------ touch and actions

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

    private fun targetAt(x: Float, y: Float): Target? =
        geometry(width).slots.firstOrNull { it.target != Target.DraftTag && it.hit.contains(x, y) }?.target

    /** What a tap on [t] does (touch and accessibility click alike). */
    fun activate(t: Target) {
        when (t) {
            is Target.Candidate -> onSelect(t.index)
            Target.Gear -> { closeModeChooser(); onSettings() }
            Target.Mic -> { closeModeChooser(); onMic() }
            Target.Pill -> { chooserOpen = !chooserOpen; changed() }
            Target.Ai -> onAiToggle()
            is Target.Mode -> {
                closeModeChooser()
                if (t.kind != activeMode) onModeSelected(t.kind)
            }
            Target.DraftTag -> Unit
        }
    }

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
                    activate(hit)
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

    // ------------------------------------------------------------------ accessibility

    private val nodes = BarNodes()

    init {
        ViewCompat.setAccessibilityDelegate(this, nodes)
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        nodes.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        nodes.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        nodes.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    }

    /** Stable virtual-view id of a target. */
    internal fun idOf(t: Target): Int = when (t) {
        Target.Pill -> 1
        Target.Ai -> 2
        Target.DraftTag -> 3
        Target.Gear -> 4
        Target.Mic -> 5
        is Target.Candidate -> 100 + t.index
        is Target.Mode -> 200 + t.kind.ordinal
    }

    private fun slotOf(id: Int): Slot? = geometry(width).slots.firstOrNull { idOf(it.target) == id }

    /** One virtual accessibility node per pill, AI entry, DRAFT tag, candidate, chooser option and gear. */
    private inner class BarNodes : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int =
            geometry(width).slots.firstOrNull { it.hit.contains(x, y) }?.let { idOf(it.target) } ?: INVALID_ID

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            if (width <= 0) return
            for (s in geometry(width).slots) virtualViewIds += idOf(s.target)
        }

        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            val s = slotOf(virtualViewId)
            if (s == null) {
                node.contentDescription = ""
                node.setBoundsInParent(Rect(0, 0, 1, 1))
                return
            }
            node.setBoundsInParent(Rect(s.hit.left.toInt(), s.hit.top.toInt(), ceil(s.hit.right).toInt(), ceil(s.hit.bottom).toInt()))
            val click = AccessibilityActionCompat.ACTION_CLICK
            when (val t = s.target) {
                Target.Pill -> {
                    node.className = Button::class.java.name
                    node.contentDescription = context.getString(R.string.top_bar_mode, LanguageCatalog.spokenWritingMode(activeMode))
                    node.addAction(AccessibilityActionCompat(click.id, context.getString(R.string.top_bar_mode_change)))
                }
                Target.Ai -> {
                    node.className = Button::class.java.name
                    node.contentDescription = context.getString(if (aiEntry == AiEntry.OPEN) R.string.top_bar_ai_close else R.string.top_bar_ai_open)
                    node.addAction(click)
                }
                Target.Gear -> {
                    node.className = Button::class.java.name
                    node.contentDescription = s.label
                    node.addAction(click)
                }
                Target.Mic -> {
                    node.className = Button::class.java.name
                    node.contentDescription = context.getString(if (micActive) R.string.voice_mic_stop else R.string.voice_mic)
                    node.addAction(click)
                }
                Target.DraftTag -> {
                    node.className = TextView::class.java.name
                    node.contentDescription = context.getString(R.string.candidate_strip_draft_description)
                }
                is Target.Candidate -> {
                    node.className = Button::class.java.name
                    node.text = s.label
                    node.addAction(click)
                    node.isLongClickable = true
                    node.addAction(AccessibilityActionCompat(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK, context.getString(R.string.top_bar_candidate_add)))
                }
                is Target.Mode -> {
                    node.className = RadioButton::class.java.name
                    node.contentDescription = context.getString(R.string.top_bar_mode_option, LanguageCatalog.spokenWritingMode(t.kind))
                    node.isCheckable = true
                    node.isChecked = t.kind == activeMode
                    node.addAction(click)
                }
            }
            if (s.target != Target.DraftTag) node.isClickable = true
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            val t = slotOf(virtualViewId)?.target ?: return false
            when (action) {
                AccessibilityNodeInfoCompat.ACTION_CLICK -> {
                    if (t == Target.DraftTag) return false
                    sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
                    activate(t)
                }
                AccessibilityNodeInfoCompat.ACTION_LONG_CLICK -> {
                    if (t !is Target.Candidate) return false
                    onLongSelect(t.index)
                }
                else -> return false
            }
            return true
        }
    }

    companion object {
        const val HEIGHT_DP = 40f
        private const val PILL_DP = 28f
        private const val CHEVRON_DP = 8f
        private const val AI_ICON_DP = 12f

        /** The microphone's touch target, as a fraction of the bar height (gear and mic stay comfortable to hit). */
        private const val MIC_WIDTH = 1f
    }
}
