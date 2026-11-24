package com.qalsmartkeyboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
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
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.qalsmartkeyboard.ime.theme.KeyboardTheme
import com.qalsmartkeyboard.ime.theme.ThemeCatalog
import com.qalsmartkeyboard.ime.toolbar.Tool
import com.qalsmartkeyboard.ime.toolbar.ToolEntry
import com.qalsmartkeyboard.ime.toolbar.ToolIcon
import com.qalsmartkeyboard.ime.toolbar.ToolIcons
import com.qalsmartkeyboard.ime.toolbar.toolLabel
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

/**
 * The keyboard's one bar above the keys (SD 6.2 `KeyboardSurface`; custom View per ADR 0003), hosted by [KeyboardBar].
 * Laid out after Gboard's toolbar (ADR 0035), left to right:
 *
 * - the **tools button** ([Target.Tools], a 2×2 grid): opens the tools panel in place of the keys. While a tool panel is
 *   open ([panelTitle] set) the bar shows only a back arrow and the panel's title;
 * - the **writing-mode pill** (am/ti only): names the active mode (ፊደል / Phonetic / English); a tap cycles to the next
 *   mode (Phonetic → Fidel → English → Phonetic);
 * - the **middle** ([middleRange]): the pinned **shortcuts** when idle ([setShortcuts]: Translate, AI, Clipboard,
 *   Settings, Theme, Emoji — every one is a 48 dp target and, when they do not all fit, the middle scrolls sideways on
 *   a drag so nothing drops off the bar). The AI shortcut is the "AI" wordmark ([ToolIcon.AI], ADR 0041): it opens the
 *   AI tools grid in place of the keys, like the tools button opens the tools panel;
 * - the **microphone** ([micShown]; voice typing, ADR 0029) at the right end.
 *
 * While composing, the whole bar becomes the suggestion bar (owner request): the tools button's place holds a
 * **back chevron** ([Target.Back]) whose tap brings the tools bar back — the next word's list restores the
 * suggestions — and the candidates take the rest of the width as full words in natural-width cells, left to right as
 * the core orders them, scrolling sideways on a drag when they overflow (no truncation, nothing dropped). What Space
 * commits (the composition, or the correction Space will apply) is bold; an autocorrection is also underlined in the
 * accent colour and the typed word is quoted. A tap reports the candidate's core index, a long press adds it to the
 * personal dictionary.
 *
 * Icons are one outline family ([ToolIcons]); an active tool (its panel is open, voice typing listens) is drawn
 * on an accent circle, a locked one carries a small lock. The bar is shown in every field and always [HEIGHT_DP] tall,
 * so the keyboard keeps one height.
 *
 * Accessibility: every element is a virtual view ([BarNodes], `ExploreByTouchHelper`) with a label and a role; an
 * accessibility click does what a tap does. Typed text is drawn only here, never logged (N2). Colours: only the
 * theme's strip tokens (plus accent / on-accent for the active state).
 */
class CandidateStripView(context: Context) : View(context) {
    /** Tap on the candidate at this index. */
    var onSelect: (Int) -> Unit = {}

    /** Long press on the candidate at this index (A1.8: add it to the personal dictionary). */
    var onLongSelect: (Int) -> Unit = {}

    /** The mode toggle picked the next writing mode. */
    var onModeSelected: (LayoutKind) -> Unit = {}

    /** The microphone was tapped (voice typing). */
    var onMic: () -> Unit = {}

    /** The tools button (or, while a tool panel is open, the back arrow) was tapped. */
    var onTools: () -> Unit = {}

    /** A pinned shortcut was tapped. */
    var onShortcut: (Tool) -> Unit = {}

    /** Whether a tap gives haptic feedback (the keys' setting; set by the service). */
    var haptics: () -> Boolean = { true }

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

    /** While a tool panel replaces the keys: its title (the bar shows a back arrow and the title only). */
    var panelTitle: String? = null
        set(value) {
            if (field == value) return
            field = value
            changed()
        }

    private var items: List<Suggestion> = emptyList()
    private var autocorrect = -1

    /** The user sent the suggestions away for the tools bar ([Target.Back]); the next word's list brings them back. */
    private var dismissed = false
    private var modes: List<LayoutKind> = emptyList()
    private var activeMode: LayoutKind = LayoutKind.ABC
    private var shortcuts: List<ToolEntry> = emptyList()
    private var activeTools: Set<Tool> = emptySet()

    /** Candidates are shown (composing in a phonetic mode). */
    val hasCandidates: Boolean get() = items.isNotEmpty()

    /** What a touch (or an accessibility action) can land on. */
    sealed interface Target {
        data class Candidate(val index: Int) : Target
        data object Tools : Target

        /** The chevron at the left end of the suggestion bar: back to the tools bar while composing. */
        data object Back : Target
        data object Pill : Target
        data object Mic : Target
        data object Title : Target
        data class Shortcut(val tool: Tool) : Target
    }

    /** One placed element: [hit] is its touch target (full bar height), [shape] what is drawn. */
    class Slot(
        val target: Target,
        val hit: RectF,
        val shape: RectF,
        val label: String = "",
        val locked: Boolean = false,
        /** What Space commits (the composition, or the correction it applies): drawn bold. */
        val primary: Boolean = false,
        /** The correction Space will apply: marked with an accent underline. */
        val autocorrect: Boolean = false,
    )

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

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f * d }
    private val bold = Paint(text).apply { typeface = Typeface.DEFAULT_BOLD }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 16f * d
        typeface = Typeface.DEFAULT_BOLD
    }
    private val line = Paint().apply { strokeWidth = 1f * d }
    private val shape = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f * d; textAlign = Paint.Align.CENTER }
    private val chipBold = Paint(chip).apply { typeface = Typeface.DEFAULT_BOLD }

    /**
     * The candidates to show (empty: the bar goes back to the tools). [autocorrect] is the index of the correction
     * Space will apply (-1: none). A new list un-dismisses the suggestions the user sent away with [Target.Back].
     */
    fun show(candidates: List<Suggestion>, autocorrect: Int = -1) {
        if (candidates == items && autocorrect == this.autocorrect) return
        if (candidates != items) dismissed = false
        items = candidates
        this.autocorrect = autocorrect
        changed()
    }

    /** The writing-mode toggle: [options] in cycle order (empty = no pill), [active] the current one. */
    fun setModes(options: List<LayoutKind>, active: LayoutKind) {
        if (options == modes && active == activeMode) return
        modes = options
        activeMode = active
        changed()
    }

    /** The shortcuts this field offers, in priority order; all of them, the overflow a drag away. */
    fun setShortcuts(entries: List<ToolEntry>) {
        if (entries == shortcuts) return
        shortcuts = entries
        changed()
    }

    /** Tools whose panel or row is open: their shortcut is drawn active. */
    fun setActiveTools(tools: Set<Tool>) {
        if (tools == activeTools) return
        activeTools = tools
        invalidate()
        nodes.invalidateRoot()
    }

    /**
     * The writing-mode pill tap (and the tools panel's Writing mode tile): cycles to the next mode of
     * [LanguageCatalog.writingModes] order — Phonetic → Fidel → English — and reports it through
     * [onModeSelected]; the controller applies it and remembers it for the language.
     */
    fun cycleMode() {
        if (modes.size < 2) return
        val next = modes[(modes.indexOf(activeMode) + 1) % modes.size]
        if (next != activeMode) onModeSelected(next)
    }

    /** State changed: drop the geometry, restart the scroll, redraw, refresh the accessibility tree. */
    private fun changed() {
        scrollX = 0f
        geo = null
        invalidate()
        nodes.invalidateRoot()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(suggestedMinimumWidth, widthMeasureSpec), barHeight.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        geo = null
        nodes.invalidateRoot()
    }

    /** The middle (px from the left) for a bar [width] px wide: where the shortcuts, candidates or the chooser go. */
    fun middleRange(width: Int = this.width): Pair<Float, Float> = geometry(width).let { it.middleStart to it.middleEnd }

    /** The placed elements for the current width, left to right (the microphone last). */
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
        val target = TARGET_DP * d
        val pillH = PILL_DP * d
        scrollable = false
        maxScrollX = 0f
        // The tools button (or the back arrow) always takes the left end.
        val tools = RectF(0f, 0f, target, h)
        val title = panelTitle
        if (title != null) {
            out += Slot(Target.Tools, tools, tools, context.getString(R.string.emoji_back_to_keys))
            val x = tools.right + 4f * d
            val r = RectF(x, 0f, w - 8f * d, h)
            out += Slot(Target.Title, r, r, title)
            return Geometry(w, out, x, w.toFloat(), dividers)
        }
        // Composing: the bar is the suggestion bar — a back chevron takes the left end (back to the tools bar),
        // not the tools button, so the candidates get the whole middle.
        val composing = items.isNotEmpty() && !dismissed
        out += Slot(
            if (composing) Target.Back else Target.Tools, tools, tools,
            context.getString(if (composing) R.string.top_bar_back_to_tools else R.string.tools_title),
        )
        var x = tools.right
        if (!composing && modes.isNotEmpty()) {
            val label = LanguageCatalog.writingModeLabel(activeMode)
            val pw = chipBold.measureText(label) + 24f * d
            val r = RectF(x + 2f * d, cy - pillH / 2, x + 2f * d + pw, cy + pillH / 2)
            out += Slot(Target.Pill, RectF(x, 0f, r.right + 4f * d, h), r, label)
            x = r.right + 8f * d
        }
        val mic = if (micShown) RectF(w - target, 0f, w.toFloat(), h) else null
        val end = mic?.left ?: (w - 4f * d)
        val middleStart = x
        when {
            composing -> {
                // The full list, left to right as the core orders it, every word whole in a natural-width cell.
                // What Space commits is bold (the correction it applies, else the composition); an autocorrection
                // is underlined and the word as typed is quoted. Like the shortcuts, what overflows the middle
                // scrolls sideways on a drag and its touch target is clipped to the middle.
                val primary = if (autocorrect in items.indices) autocorrect else 0
                val pad = 14f * d
                val room = end - x
                val labels = items.mapIndexed { i, s -> if (autocorrect in items.indices && i == 0) "\u201C${s.text}\u201D" else s.text }
                val widths = labels.mapIndexed { i, l -> (if (i == primary) bold else text).measureText(l) + pad * 2 }
                val total = widths.sum()
                scrollable = total > room
                maxScrollX = (total - room).coerceAtLeast(0f)
                scrollX = scrollX.coerceIn(0f, maxScrollX)
                var sx = x - scrollX
                widths.forEachIndexed { i, cw ->
                    if (i > 0) dividers += sx
                    val shape = RectF(sx, 0f, sx + cw, h)
                    val hit = RectF(maxOf(sx, x), 0f, minOf(sx + cw, end), h)
                    out += Slot(Target.Candidate(i), hit, shape, labels[i], primary = i == primary, autocorrect = i == autocorrect)
                    sx += cw
                }
            }
            else -> {
                // The pinned shortcuts, in priority order, evenly spread (at most 72 dp apart) and centred when they
                // all fit. When they do not (e.g. beside the am/ti mode pill or the microphone) every one stays a
                // 48 dp slot and the middle scrolls sideways on a drag: nothing drops off the bar. The touch target
                // is clipped to the middle so a scrolled-off icon cannot be tapped or drawn outside it.
                val room = end - x
                val n = shortcuts.size
                if (n > 0) {
                    val fits = n * target <= room
                    val sw = if (fits) min(room / n, MAX_SHORTCUT_DP * d) else target
                    scrollable = !fits
                    maxScrollX = (sw * n - room).coerceAtLeast(0f)
                    scrollX = scrollX.coerceIn(0f, maxScrollX)
                    val sx0 = if (scrollable) x - scrollX else x + (room - sw * n) / 2
                    shortcuts.forEachIndexed { i, e ->
                        val sx = sx0 + i * sw
                        val shapeRect = RectF(sx, 0f, sx + sw, h)
                        val hit = RectF(maxOf(sx, x), 0f, minOf(sx + sw, end), h)
                        out += Slot(Target.Shortcut(e.tool), hit, shapeRect, toolLabel(context, e.tool), e.locked)
                    }
                }
            }
        }
        if (mic != null) out += Slot(Target.Mic, mic, mic, context.getString(R.string.voice_mic))
        return Geometry(w, out, middleStart, end, dividers)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bg)
        line.color = divider
        val g = geometry(width)
        val cy = height / 2f
        for (s in g.slots) {
            val down = s.target == pressed
            when (val t = s.target) {
                Target.Tools -> drawIcon(canvas, s, if (panelTitle != null) ToolIcon.BACK else ToolIcon.TOOLS, active = false, down)
                Target.Back -> drawIcon(canvas, s, ToolIcon.BACK, active = false, down)
                Target.Title -> {
                    titlePaint.color = textColor
                    canvas.drawText(fit(s.label, titlePaint, s.shape.width()), s.shape.left, cy - (titlePaint.descent() + titlePaint.ascent()) / 2, titlePaint)
                }
                is Target.Shortcut -> {
                    // The scrolled middle is clipped: a half-visible icon is not drawn over the pill or the mic.
                    val save = if (scrollable) canvas.save().also { canvas.clipRect(g.middleStart, 0f, g.middleEnd, height.toFloat()) } else -1
                    // Owner request: the translate entry stands out — drawn in the accent colour, like the pill's chip.
                    drawIcon(canvas, s, t.tool.icon, active = t.tool in activeTools, down, tint = if (t.tool == Tool.TRANSLATE) theme.accentText else null)
                    if (save >= 0) canvas.restoreToCount(save)
                }
                Target.Mic -> drawIcon(canvas, s, ToolIcon.MIC, active = micActive, down)
                Target.Pill -> drawPill(canvas, s, down)
                is Target.Candidate -> {
                    // The scrolled middle is clipped: a half-visible word is not drawn over the chevron or the mic.
                    val save = if (scrollable) canvas.save().also { canvas.clipRect(g.middleStart, 0f, g.middleEnd, height.toFloat()) } else -1
                    val p = if (s.primary) bold else text
                    p.color = textColor
                    if (down) {
                        shape.style = Paint.Style.FILL
                        shape.color = divider
                        canvas.drawRoundRect(s.shape.left + 2f * d, cy - 16f * d, s.shape.right - 2f * d, cy + 16f * d, 8f * d, 8f * d, shape)
                    }
                    val w = p.measureText(s.label)
                    val left = s.shape.left + 14f * d
                    val baseline = cy - (p.descent() + p.ascent()) / 2
                    canvas.drawText(s.label, left, baseline, p)
                    if (s.autocorrect) {
                        // The correction Space will apply: an accent underline.
                        shape.style = Paint.Style.FILL
                        shape.color = theme.accent
                        val y = baseline + p.descent() / 2 + 2f * d
                        canvas.drawRoundRect(left, y, left + w, y + 2f * d, d, d, shape)
                    }
                    if (save >= 0) canvas.restoreToCount(save)
                }
            }
        }
        for (x in g.dividers) if (x in g.middleStart..g.middleEnd) canvas.drawLine(x, height * 0.25f, x, height * 0.75f, line)
    }

    /** Shortens [label] with an ellipsis until it fits [max] px. */
    private fun fit(label: String, p: Paint, max: Float): String {
        if (p.measureText(label) <= max) return label
        var n = label.length
        while (n > 1 && p.measureText(label, 0, n) + p.measureText("…") > max) n--
        return label.substring(0, n) + "…"
    }

    /**
     * One icon button: [ToolIcons] in the strip text colour ([tint] overrides it while not active); active = on an
     * accent circle in the on-accent colour (the treatment the microphone already had while voice typing listens);
     * pressed = a subtle circle behind it; locked = a small lock at the lower right.
     */
    private fun drawIcon(canvas: Canvas, s: Slot, icon: ToolIcon, active: Boolean, down: Boolean, tint: Int? = null) {
        // The icon is centred in its (unclipped) cell: a scrolled-off shortcut keeps the icon at the slot's centre.
        val cx = s.shape.centerX()
        val cy = s.shape.centerY()
        val r = CIRCLE_DP * d / 2
        shape.style = Paint.Style.FILL
        if (active) {
            shape.color = theme.accent
            canvas.drawCircle(cx, cy, r, shape)
        } else if (down) {
            shape.color = divider
            canvas.drawCircle(cx, cy, r, shape)
        }
        val size = ICON_DP * d
        ToolIcons.draw(canvas, icon, cx, cy, size, if (active) theme.onAccent else (tint ?: textColor), iconPaint)
        if (s.locked && !active) {
            val b = LOCK_DP * d
            val bx = cx + size / 2 - b / 4
            val by = cy + size / 2 - b / 4
            shape.color = bg
            canvas.drawCircle(bx, by, b * 0.75f, shape)
            ToolIcons.draw(canvas, ToolIcon.LOCK, bx, by, b, theme.hintText, iconPaint, strokeScale = 1.4f)
        }
    }

    /** The mode pill: the active mode's name on an accent chip (a tap cycles the modes); it stands out on purpose. */
    private fun drawPill(canvas: Canvas, s: Slot, down: Boolean) {
        val r = s.shape
        val cy = r.centerY()
        shape.style = Paint.Style.FILL
        shape.color = if (down) theme.pressed else theme.accent
        canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, shape)
        chipBold.color = if (down) theme.keyText else theme.onAccent
        canvas.drawText(s.label, r.centerX(), cy - (chipBold.descent() + chipBold.ascent()) / 2, chipBold)
    }

    // ------------------------------------------------------------------ touch and actions

    private var pressed: Target? = null
    private var longFired = false

    /** The middle's sideways scroll: content offset, whether it overflows, and the drag in progress. */
    private var scrollX = 0f
    private var scrollable = false
    private var maxScrollX = 0f
    private var scrolling = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var downInMiddle = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val longPress = Runnable {
        val p = pressed
        if (p is Target.Candidate) {
            longFired = true
            if (haptics()) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongSelect(p.index)
        }
    }

    private fun targetAt(x: Float, y: Float): Target? =
        geometry(width).slots.firstOrNull { it.target != Target.Title && it.hit.contains(x, y) }?.target

    /** What a tap on [t] does (touch and accessibility click alike). */
    fun activate(t: Target) {
        when (t) {
            is Target.Candidate -> onSelect(t.index)
            Target.Tools -> onTools()
            Target.Back -> { dismissed = true; changed() }
            Target.Mic -> onMic()
            Target.Pill -> cycleMode()
            is Target.Shortcut -> onShortcut(t.tool)
            Target.Title -> Unit
        }
    }

    private fun setPressed(t: Target?) {
        if (pressed == t) return
        pressed = t
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                setPressed(targetAt(e.x, e.y))
                longFired = false
                scrolling = false
                downX = e.x
                downY = e.y
                lastX = e.x
                downInMiddle = geometry(width).let { e.x in it.middleStart..it.middleEnd }
                if (pressed is Target.Candidate) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (scrolling) {
                    scrolling = false
                    setPressed(null)
                    nodes.invalidateRoot()
                } else {
                    val hit = targetAt(e.x, e.y)
                    val p = pressed
                    setPressed(null)
                    if (!longFired && hit != null && hit == p) {
                        if (haptics()) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        activate(hit)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                scrolling = false
                setPressed(null)
            }
            MotionEvent.ACTION_MOVE ->
                if (scrolling) {
                    scrollMiddle(e.x - lastX)
                    lastX = e.x
                } else if (scrollable && downInMiddle && abs(e.x - downX) > touchSlop && abs(e.x - downX) > abs(e.y - downY)) {
                    // A sideways drag on the shortcut area scrolls it (cancelling the pressed icon).
                    scrolling = true
                    removeCallbacks(longPress)
                    setPressed(null)
                    lastX = e.x
                } else if (pressed != null && targetAt(e.x, e.y) != pressed) {
                    removeCallbacks(longPress)
                    setPressed(null)
                }
        }
        return true
    }

    /** Move the shortcut strip by a finger delta [dx] (negative drags the icons left, revealing later ones). */
    private fun scrollMiddle(dx: Float) {
        val next = (scrollX - dx).coerceIn(0f, maxScrollX)
        if (next == scrollX) return
        scrollX = next
        geo = null
        invalidate()
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
        Target.Tools -> 1
        Target.Title -> 4
        Target.Mic -> 5
        Target.Pill -> 6
        Target.Back -> 7
        is Target.Candidate -> 100 + t.index
        is Target.Shortcut -> 300 + t.tool.ordinal
    }

    private fun slotOf(id: Int): Slot? = geometry(width).slots.firstOrNull { idOf(it.target) == id }

    /** One virtual accessibility node per element of the bar. */
    private inner class BarNodes : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int =
            geometry(width).slots.firstOrNull { it.hit.contains(x, y) }?.let { idOf(it.target) } ?: INVALID_ID

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            if (width <= 0) return
            // A shortcut scrolled fully out of the middle has no touch target and gets no node either.
            for (s in geometry(width).slots) if (!s.hit.isEmpty) virtualViewIds += idOf(s.target)
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
                Target.Tools, Target.Back -> {
                    node.className = Button::class.java.name
                    node.contentDescription = s.label
                    node.addAction(click)
                }
                Target.Title -> {
                    node.className = TextView::class.java.name
                    node.text = s.label
                    node.isHeading = true
                }
                is Target.Shortcut -> {
                    node.className = Button::class.java.name
                    node.contentDescription = if (s.locked) context.getString(R.string.tool_locked, s.label) else s.label
                    node.isSelected = t.tool in activeTools
                    node.addAction(click)
                }
                Target.Mic -> {
                    node.className = Button::class.java.name
                    node.contentDescription = context.getString(if (micActive) R.string.voice_mic_stop else R.string.voice_mic)
                    node.addAction(click)
                }
                is Target.Candidate -> {
                    node.className = Button::class.java.name
                    node.text = s.label
                    if (s.autocorrect) node.contentDescription = context.getString(R.string.top_bar_candidate_autocorrect, s.label)
                    node.addAction(click)
                    node.isLongClickable = true
                    node.addAction(AccessibilityActionCompat(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK, context.getString(R.string.top_bar_candidate_add)))
                }
            }
            if (s.target != Target.Title) node.isClickable = true
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            val t = slotOf(virtualViewId)?.target ?: return false
            when (action) {
                AccessibilityNodeInfoCompat.ACTION_CLICK -> {
                    if (t == Target.Title) return false
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
        /** The bar's height (ADR 0035: 44 dp, up from 40, so 22 dp icons sit in Gboard-like room). */
        const val HEIGHT_DP = 44f

        /** The touch target of every icon button (tools, back chevron, shortcuts, mic): 48 dp wide, the bar's full height. */
        const val TARGET_DP = 48f

        /** Shortcuts never spread further apart than this, so a few of them stay a group. */
        const val MAX_SHORTCUT_DP = 72f

        /** The icon size (dp) of the bar's buttons. */
        const val ICON_DP = 22f
        private const val CIRCLE_DP = 36f
        private const val LOCK_DP = 10f
        private const val PILL_DP = 30f
    }
}
