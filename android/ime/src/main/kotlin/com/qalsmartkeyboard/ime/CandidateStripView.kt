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
import com.qalsmartkeyboard.ime.theme.KeyboardTheme
import com.qalsmartkeyboard.ime.theme.ThemeCatalog
import com.qalsmartkeyboard.ime.toolbar.Tool
import com.qalsmartkeyboard.ime.toolbar.ToolEntry
import com.qalsmartkeyboard.ime.toolbar.ToolIcon
import com.qalsmartkeyboard.ime.toolbar.ToolIcons
import com.qalsmartkeyboard.ime.toolbar.toolLabel
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * The keyboard's one bar above the keys (SD 6.2 `KeyboardSurface`; custom View per ADR 0003), hosted by [KeyboardBar].
 * Laid out after Gboard's toolbar (ADR 0035), left to right:
 *
 * - the **tools button** ([Target.Tools], a 2×2 grid): opens the tools panel in place of the keys. While a tool panel is
 *   open ([panelTitle] set) the bar shows only a back arrow and the panel's title;
 * - the **writing-mode pill** (am/ti only): names the active mode (ፊደል / Phonetic / English); a tap opens a three-option
 *   chooser in the middle, a second tap or a choice closes it;
 * - the **middle**: the pinned **shortcuts** when idle ([setShortcuts]: Translate, AI, Clipboard, then Settings, Theme and
 *   Emoji while they fit, evenly spaced, each a 48 dp target); candidates while composing in a phonetic mode; the mode
 *   chooser; or, while the AI actions are open, a close (×) button followed by the actions row ([KeyboardBar] lays it
 *   over [middleRange]);
 * - the **microphone** ([micShown]; voice typing, ADR 0029) at the right end.
 *
 * Icons are one outline family ([ToolIcons]); an active tool (its panel or row is open, voice typing listens) is drawn
 * on an accent circle, a locked one carries a small lock. The bar is shown in every field and always [HEIGHT_DP] tall,
 * so the keyboard keeps one height. Candidates (ADR 0039, Gboard-like) take three equal slots: the centre holds what
 * Space commits (the composition, or the correction Space will apply), bold; left and right the next two; thin
 * separators; mirrored for right-to-left languages. An autocorrection is underlined in the accent colour and the typed
 * word moves to the side in quotes. With more than three candidates a chevron expands the strip into the full list (the
 * flowing layout, left to right as they come from the core) and back. A tap reports the candidate's core index, a long
 * press adds it to the personal dictionary. While the data is DRAFT, a "DRAFT" tag marks them as not verified (rule N9).
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

    /** A writing mode was picked in the chooser. */
    var onModeSelected: (LayoutKind) -> Unit = {}

    /** The microphone was tapped (voice typing). */
    var onMic: () -> Unit = {}

    /** The tools button (or, while a tool panel is open, the back arrow) was tapped. */
    var onTools: () -> Unit = {}

    /** A pinned shortcut was tapped. */
    var onShortcut: (Tool) -> Unit = {}

    /** The AI actions' close button was tapped (the bar wires it to close the row). */
    var onAiToggle: () -> Unit = {}

    /** What uses the middle, or its extent, may have changed ([KeyboardBar] re-lays the AI actions). */
    var onMiddleChanged: () -> Unit = {}

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

    /** The AI entry: none (AI not allowed here or not attached), closed, or open (actions in the middle). */
    enum class AiEntry { NONE, CLOSED, OPEN }

    var aiEntry: AiEntry = AiEntry.NONE
        set(value) {
            if (field == value) return
            field = value
            changed()
        }

    /** While a tool panel replaces the keys: its title (the bar shows a back arrow and the title only). */
    var panelTitle: String? = null
        set(value) {
            if (field == value) return
            field = value
            if (value != null) chooserOpen = false
            changed()
        }

    private var items: List<Suggestion> = emptyList()
    private var draft = false
    private var autocorrect = -1
    private var rtl = false

    /** The strip shows every candidate (the chevron was tapped) instead of three slots. */
    private var expanded = false
    private var modes: List<LayoutKind> = emptyList()
    private var activeMode: LayoutKind = LayoutKind.ABC
    private var chooserOpen = false
    private var shortcuts: List<ToolEntry> = emptyList()
    private var activeTools: Set<Tool> = emptySet()

    /** Candidates are shown (composing in a phonetic mode). */
    val hasCandidates: Boolean get() = items.isNotEmpty()

    /** The writing-mode chooser is open in the middle. */
    val isModeChooserOpen: Boolean get() = chooserOpen && modes.isNotEmpty() && panelTitle == null

    /** Neither candidates, the chooser nor a tool panel use the bar: the shortcuts (or the AI actions) can. */
    private val middleFree: Boolean get() = items.isEmpty() && !isModeChooserOpen && panelTitle == null

    /** The AI shortcut (or, while the actions are open, their close button) can be shown. */
    val showsAiEntry: Boolean get() = aiEntry != AiEntry.NONE && middleFree

    /** The middle belongs to the AI actions. */
    val showsAiActions: Boolean get() = showsAiEntry && aiEntry == AiEntry.OPEN

    /** What a touch (or an accessibility action) can land on. */
    sealed interface Target {
        data class Candidate(val index: Int) : Target
        data object Tools : Target
        data object Pill : Target
        data object AiClose : Target
        data object Mic : Target
        data object DraftTag : Target
        data object Title : Target

        /** The chevron that expands the three slots into the full list, or folds it back. */
        data object More : Target
        data class Shortcut(val tool: Tool) : Target
        data class Mode(val kind: LayoutKind) : Target
    }

    /** One placed element: [hit] is its touch target (full bar height), [shape] what is drawn. */
    class Slot(
        val target: Target,
        val hit: RectF,
        val shape: RectF,
        val label: String = "",
        val locked: Boolean = false,
        /** A candidate in one of the three slots: drawn centred in its cell. */
        val centred: Boolean = false,
        /** The centre slot (or index 0 of the full list): what Space commits, drawn bold. */
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
    private val draftColor get() = theme.warn

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f * d }
    private val bold = Paint(text).apply { typeface = Typeface.DEFAULT_BOLD }
    private val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * d
        typeface = Typeface.DEFAULT_BOLD
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 16f * d
        typeface = Typeface.DEFAULT_BOLD
    }
    private val line = Paint().apply { strokeWidth = 1f * d }
    private val shape = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f * d; textAlign = Paint.Align.CENTER }
    private val chipBold = Paint(chip).apply { typeface = Typeface.DEFAULT_BOLD }
    private val chevronPath = Path()
    private val tagLabel = context.getString(R.string.candidate_strip_draft)

    /**
     * The candidates to show (empty: the middle goes back to the shortcuts). [autocorrect] is the index of the correction
     * Space will apply (-1: none); [rtl] mirrors the side slots (Arabic).
     */
    fun show(candidates: List<Suggestion>, isDraft: Boolean, autocorrect: Int = -1, rtl: Boolean = false) {
        if (candidates == items && isDraft == draft && autocorrect == this.autocorrect && rtl == this.rtl) return
        if (candidates != items) expanded = false
        items = candidates
        draft = isDraft
        this.autocorrect = autocorrect
        this.rtl = rtl
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

    /** The shortcuts this field offers, in priority order; the bar shows the first ones that fit. */
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

    /** Opens the mode chooser (the tools panel's Writing mode tile). */
    fun openModeChooser() {
        if (modes.isEmpty() || chooserOpen) return
        chooserOpen = true
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
        out += Slot(Target.Tools, tools, tools, context.getString(R.string.tools_title))
        var x = tools.right
        if (modes.isNotEmpty()) {
            val label = LanguageCatalog.writingModeLabel(activeMode)
            val pw = chipBold.measureText(label) + 24f * d + CHEVRON_DP * d
            val r = RectF(x + 2f * d, cy - pillH / 2, x + 2f * d + pw, cy + pillH / 2)
            out += Slot(Target.Pill, RectF(x, 0f, r.right + 4f * d, h), r, label)
            x = r.right + 8f * d
        }
        val mic = if (micShown) RectF(w - target, 0f, w.toFloat(), h) else null
        val end = mic?.left ?: (w - 4f * d)
        var middleStart = x
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
            items.isNotEmpty() -> {
                if (draft) {
                    val tw = tag.measureText(tagLabel)
                    out += Slot(Target.DraftTag, RectF(x, 0f, x + tw, h), RectF(x, 0f, x + tw, h), tagLabel)
                    x += tw + 10f * d
                }
                val more = items.size > SLOTS
                val listEnd = if (more) end - MORE_DP * d else end
                if (expanded && more) {
                    // The full list, left to right as the core orders it (the pre-ADR 0039 layout).
                    val pad = 14f * d
                    items.forEachIndexed { i, s ->
                        val p = if (i == 0) bold else text
                        val cw = p.measureText(s.text) + pad * 2
                        if (x + cw > listEnd) return@forEachIndexed
                        if (i > 0) dividers += x
                        val r = RectF(x, 0f, x + cw, h)
                        out += Slot(Target.Candidate(i), r, r, s.text, primary = i == 0, autocorrect = i == autocorrect)
                        x += cw
                    }
                } else {
                    val order = slotOrder(items.size, autocorrect, rtl)
                    val cw = (listEnd - x) / SLOTS
                    order.forEachIndexed { k, i ->
                        if (k > 0) dividers += x + cw * k
                        if (i == null) return@forEachIndexed
                        val r = RectF(x + cw * k, 0f, x + cw * (k + 1), h)
                        // Beside a correction, the word as typed is quoted ("keep what I typed").
                        val typedBesideCorrection = autocorrect in items.indices && i == 0
                        val label = if (typedBesideCorrection) "\u201C${items[i].text}\u201D" else items[i].text
                        out += Slot(Target.Candidate(i), r, r, label, centred = true, primary = k == 1, autocorrect = i == autocorrect)
                    }
                }
                if (more) {
                    val r = RectF(listEnd, 0f, end, h)
                    val label = context.getString(if (expanded) R.string.top_bar_fewer_suggestions else R.string.top_bar_more_suggestions)
                    out += Slot(Target.More, r, r, label)
                }
            }
            aiEntry == AiEntry.OPEN -> {
                // The AI actions: a close button, then the row ([KeyboardBar] lays it over the rest of the middle).
                val r = RectF(x, 0f, x + CLOSE_DP * d, h)
                out += Slot(Target.AiClose, r, r, context.getString(R.string.top_bar_ai_close))
                middleStart = r.right
            }
            else -> {
                // The pinned shortcuts: as many as fit at 48 dp each, evenly spread (at most 72 dp apart), centred.
                val room = end - x
                val n = min(shortcuts.size, floor(room / target).toInt())
                if (n > 0) {
                    val sw = min(room / n, MAX_SHORTCUT_DP * d)
                    var sx = x + (room - sw * n) / 2
                    for (e in shortcuts.take(n)) {
                        val r = RectF(sx, 0f, sx + sw, h)
                        out += Slot(Target.Shortcut(e.tool), r, r, toolLabel(context, e.tool), e.locked)
                        sx += sw
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
                Target.Title -> {
                    titlePaint.color = textColor
                    canvas.drawText(fit(s.label, titlePaint, s.shape.width()), s.shape.left, cy - (titlePaint.descent() + titlePaint.ascent()) / 2, titlePaint)
                }
                is Target.Shortcut -> drawIcon(canvas, s, t.tool.icon, active = t.tool in activeTools, down)
                Target.AiClose -> drawIcon(canvas, s, ToolIcon.CLOSE, active = true, down)
                Target.Mic -> drawIcon(canvas, s, ToolIcon.MIC, active = micActive, down)
                Target.Pill -> drawPill(canvas, s, down)
                Target.DraftTag -> {
                    tag.color = draftColor
                    canvas.drawText(tagLabel, s.shape.left, cy - (tag.descent() + tag.ascent()) / 2, tag)
                }
                is Target.Candidate -> {
                    val p = if (s.primary) bold else text
                    p.color = textColor
                    if (down) {
                        shape.style = Paint.Style.FILL
                        shape.color = divider
                        canvas.drawRoundRect(s.shape.left + 2f * d, cy - 16f * d, s.shape.right - 2f * d, cy + 16f * d, 8f * d, 8f * d, shape)
                    }
                    val label = if (s.centred) fit(s.label, p, s.shape.width() - 12f * d) else s.label
                    val w = p.measureText(label)
                    val left = if (s.centred) s.shape.centerX() - w / 2 else s.shape.left + 14f * d
                    val baseline = cy - (p.descent() + p.ascent()) / 2
                    canvas.drawText(label, left, baseline, p)
                    if (s.autocorrect) {
                        // The correction Space will apply: an accent underline.
                        shape.style = Paint.Style.FILL
                        shape.color = theme.accent
                        val y = baseline + p.descent() / 2 + 2f * d
                        canvas.drawRoundRect(left, y, left + w, y + 2f * d, d, d, shape)
                    }
                }
                Target.More -> drawChevron(canvas, s.hit.centerX(), cy, up = expanded)
                is Target.Mode -> drawModeOption(canvas, s.shape, s.label, t.kind == activeMode)
            }
        }
        for (x in g.dividers) canvas.drawLine(x, height * 0.25f, x, height * 0.75f, line)
    }

    /** Shortens [label] with an ellipsis until it fits [max] px. */
    private fun fit(label: String, p: Paint, max: Float): String {
        if (p.measureText(label) <= max) return label
        var n = label.length
        while (n > 1 && p.measureText(label, 0, n) + p.measureText("…") > max) n--
        return label.substring(0, n) + "…"
    }

    /**
     * One icon button: [ToolIcons] in the strip text colour; active = on an accent circle in the on-accent colour (the
     * treatment the microphone already had while voice typing listens); pressed = a subtle circle behind it; locked = a
     * small lock at the lower right.
     */
    private fun drawIcon(canvas: Canvas, s: Slot, icon: ToolIcon, active: Boolean, down: Boolean) {
        val cx = s.hit.centerX()
        val cy = s.hit.centerY()
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
        ToolIcons.draw(canvas, icon, cx, cy, size, if (active) theme.onAccent else textColor, iconPaint)
        if (s.locked && !active) {
            val b = LOCK_DP * d
            val bx = cx + size / 2 - b / 4
            val by = cy + size / 2 - b / 4
            shape.color = bg
            canvas.drawCircle(bx, by, b * 0.75f, shape)
            ToolIcons.draw(canvas, ToolIcon.LOCK, bx, by, b, theme.hintText, iconPaint, strokeScale = 1.4f)
        }
    }

    /** The mode pill with a small chevron: up while the chooser is open, down otherwise. */
    private fun drawPill(canvas: Canvas, s: Slot, down: Boolean) {
        val r = s.shape
        val cy = r.centerY()
        shape.style = Paint.Style.FILL
        shape.color = if (down) theme.pressed else divider
        canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, shape)
        chipBold.color = if (down) theme.keyText else textColor
        val textCx = r.left + 12f * d + chipBold.measureText(s.label) / 2
        canvas.drawText(s.label, textCx, cy - (chipBold.descent() + chipBold.ascent()) / 2, chipBold)
        val chevron = CHEVRON_DP * d
        val cx = r.right - 12f * d - chevron / 2 + 2f * d
        val half = chevron / 2
        val dir = if (chooserOpen) -1f else 1f
        chevronPath.reset()
        chevronPath.moveTo(cx - half, cy - dir * half / 2)
        chevronPath.lineTo(cx + half, cy - dir * half / 2)
        chevronPath.lineTo(cx, cy + dir * half / 2)
        chevronPath.close()
        canvas.drawPath(chevronPath, chipBold)
    }

    /** The expand / fold chevron at the end of the candidates. */
    private fun drawChevron(canvas: Canvas, cx: Float, cy: Float, up: Boolean) {
        val half = CHEVRON_DP * d * 0.75f
        val dir = if (up) -1f else 1f
        line.color = textColor
        line.strokeWidth = 2f * d
        canvas.drawLine(cx - half, cy - dir * half / 2, cx, cy + dir * half / 2, line)
        canvas.drawLine(cx, cy + dir * half / 2, cx + half, cy - dir * half / 2, line)
        line.strokeWidth = 1f * d
        line.color = divider
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

    // ------------------------------------------------------------------ touch and actions

    private var pressed: Target? = null
    private var longFired = false
    private val longPress = Runnable {
        val p = pressed
        if (p is Target.Candidate) {
            longFired = true
            if (haptics()) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongSelect(p.index)
        }
    }

    private fun targetAt(x: Float, y: Float): Target? =
        geometry(width).slots.firstOrNull { it.target != Target.DraftTag && it.target != Target.Title && it.hit.contains(x, y) }?.target

    /** What a tap on [t] does (touch and accessibility click alike). */
    fun activate(t: Target) {
        when (t) {
            is Target.Candidate -> onSelect(t.index)
            Target.Tools -> { closeModeChooser(); onTools() }
            Target.Mic -> { closeModeChooser(); onMic() }
            Target.Pill -> { chooserOpen = !chooserOpen; changed() }
            Target.AiClose -> onAiToggle()
            Target.More -> { expanded = !expanded; changed() }
            is Target.Shortcut -> { closeModeChooser(); onShortcut(t.tool) }
            is Target.Mode -> {
                closeModeChooser()
                if (t.kind != activeMode) onModeSelected(t.kind)
            }
            Target.DraftTag, Target.Title -> Unit
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
                if (pressed is Target.Candidate) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val hit = targetAt(e.x, e.y)
                val p = pressed
                setPressed(null)
                if (!longFired && hit != null && hit == p) {
                    if (haptics()) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    activate(hit)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                setPressed(null)
            }
            MotionEvent.ACTION_MOVE ->
                if (pressed != null && targetAt(e.x, e.y) != pressed) {
                    removeCallbacks(longPress)
                    setPressed(null)
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
        Target.Tools -> 1
        Target.AiClose -> 2
        Target.DraftTag -> 3
        Target.Title -> 4
        Target.Mic -> 5
        Target.Pill -> 6
        Target.More -> 7
        is Target.Candidate -> 100 + t.index
        is Target.Mode -> 200 + t.kind.ordinal
        is Target.Shortcut -> 300 + t.tool.ordinal
    }

    private fun slotOf(id: Int): Slot? = geometry(width).slots.firstOrNull { idOf(it.target) == id }

    /** One virtual accessibility node per element of the bar. */
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
                Target.Tools, Target.AiClose, Target.More -> {
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
                Target.DraftTag -> {
                    node.className = TextView::class.java.name
                    node.contentDescription = context.getString(R.string.candidate_strip_draft_description)
                }
                is Target.Candidate -> {
                    node.className = Button::class.java.name
                    node.text = s.label
                    if (s.autocorrect) node.contentDescription = context.getString(R.string.top_bar_candidate_autocorrect, s.label)
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
            if (s.target != Target.DraftTag && s.target != Target.Title) node.isClickable = true
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            val t = slotOf(virtualViewId)?.target ?: return false
            when (action) {
                AccessibilityNodeInfoCompat.ACTION_CLICK -> {
                    if (t == Target.DraftTag || t == Target.Title) return false
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
        /** Candidate slots (ADR 0039): left, centre (what Space commits), right. */
        const val SLOTS = 3

        /**
         * The core indices shown in the left, centre and right slot (null: empty slot) for [n] candidates: the centre is
         * the correction Space will apply ([autocorrect]) or else index 0 (the composition / the word as typed); the next
         * two in core order go left, then right. [rtl] mirrors the sides, so the first alternative is read first.
         */
        fun slotOrder(n: Int, autocorrect: Int, rtl: Boolean): List<Int?> {
            if (n <= 0) return listOf(null, null, null)
            val primary = if (autocorrect in 0 until n) autocorrect else 0
            val others = (0 until n).filter { it != primary }
            val first = others.getOrNull(0)
            val second = others.getOrNull(1)
            return if (rtl) listOf(second, primary, first) else listOf(first, primary, second)
        }

        /** The bar's height (ADR 0035: 44 dp, up from 40, so 22 dp icons sit in Gboard-like room). */
        const val HEIGHT_DP = 44f

        /** The touch target of every icon button (tools, shortcuts, mic): 48 dp wide, the bar's full height. */
        const val TARGET_DP = 48f

        /** Shortcuts never spread further apart than this, so a few of them stay a group. */
        const val MAX_SHORTCUT_DP = 72f

        /** The icon size (dp) of the bar's buttons. */
        const val ICON_DP = 22f
        private const val CLOSE_DP = 44f
        private const val CIRCLE_DP = 36f
        private const val LOCK_DP = 10f
        private const val PILL_DP = 30f
        private const val CHEVRON_DP = 8f

        /** Width of the expand / fold chevron at the end of the candidates. */
        private const val MORE_DP = 36f
    }
}
