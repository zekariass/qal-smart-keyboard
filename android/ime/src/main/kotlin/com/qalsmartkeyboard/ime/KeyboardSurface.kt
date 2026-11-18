package com.qalsmartkeyboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.qalsmartkeyboard.ime.theme.KeyboardTheme
import com.qalsmartkeyboard.ime.theme.ThemeCatalog
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Custom-View key surface (ADR 0003): draws the keys on one Canvas and resolves touches itself.
 * Long-press popups (Fidel orders, Ethiopic numerals and punctuation, the language picker) are
 * drawn by a [PopupOverlay] that covers the whole input view, including a transparent headroom above
 * the strip (a PopupWindow would be clipped to the IME window); the surface keeps the pointer and does
 * the hit-testing, so a slide-and-release picks an item. Keys with a popup or a long-press action act on release; other
 * keys act on press. It draws no key-preview popups, so characters typed in password fields are
 * never echoed by us.
 */
class KeyboardSurface(context: Context) : View(context) {
    var controller: KeyboardController? = null

    /** Draws the open popup above everything (set by the service). */
    var overlay: PopupOverlay? = null

    private class Placed(val key: Key, val rect: RectF)

    private var placed: List<Placed> = emptyList()
    private val pressed = HashMap<Int, Placed>() // pointer id -> key
    private val d = resources.displayMetrics.density

    /** Space between keys in a row, and between rows (mainstream proportions: rows breathe more). */
    private val gap = 4f * d
    private val rowGap = 7f * d

    /** Outer margin of the key area. */
    private val edge = 3f * d
    /** The colours and shapes to draw with. Set by the service when the chosen theme changes; the next draw uses it. */
    var theme: KeyboardTheme = ThemeCatalog.classic(
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES,
    )
        set(value) {
            if (field === value) return
            field = value
            bgShader = null
            borderPaint.strokeWidth = value.borderWidthDp * d
            invalidate()
        }

    private val dark get() = !theme.isLight
    private val bg get() = theme.bgTop
    private val keyColor get() = theme.key
    private val specialColor get() = theme.special
    private val pressedColor get() = theme.pressed
    private val accent get() = theme.accent
    private val onAccent get() = theme.onAccent
    private val accentText get() = theme.accentText
    private val textColor get() = theme.keyText
    private val specialText get() = theme.specialText
    private val dimColor get() = theme.hintText

    private var bgShader: LinearGradient? = null
    private val bgPaint = Paint()
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** The keyboard background; the service also paints it under the navigation-bar and cutout padding. */
    val backgroundFill: Int get() = bg

    /** The keys use the dark palette: the service sets the navigation-bar icon appearance from it. */
    val isDark: Boolean get() = dark

    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop * 2f

    private var repeating: Key? = null
    private var repeatPointer = -1
    private val repeat = object : Runnable {
        override fun run() {
            val k = repeating ?: return
            controller?.onKey(k)
            postDelayed(this, 50)
        }
    }

    // Long-press state of the (single) pointer that can open a popup.
    private var lpPointer = -1
    private var lpKey: Placed? = null
    private var lpDownX = 0f
    private var lpDownY = 0f
    private var lpFired = false
    private val longPress = Runnable { fireLongPress() }

    private var popupOpen = false
    private var popupRect = RectF()
    private var popupItems: List<PopupItem> = emptyList()
    private var popupCols = 1
    private var popupCellW = 0f
    private var popupCellH = 0f
    private var popupSel = -1

    private var announcedMode: String? = null

    /** Row height in px: the layout's (key-height setting included), fitted to orientation and screen. */
    private val rowHeight: Float
        get() {
            val c = controller
            val layout = c?.layout
            val cfg = resources.configuration
            return KeyboardMetrics.rowHeightDp(
                layout?.rowHeightDp ?: 50f, c?.prefs?.keyHeightScale ?: 1f, layout?.rows?.size ?: 5,
                cfg.screenWidthDp, cfg.screenHeightDp,
            ) * d
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rows = controller?.layout?.rows?.size ?: 5
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            (rows * rowHeight + edge * 2).toInt(),
        )
    }

    /** Called when the page, shift state, language/layout or field profile changed. */
    fun onLayoutChanged() {
        dismissPopup()
        placed = emptyList()
        controller?.let { c ->
            val spoken = c.modeDescription
            contentDescription = "Keyboard, $spoken"
            if (announcedMode != null && announcedMode != spoken) announceForAccessibility(spoken)
            announcedMode = spoken
        }
        requestLayout()
        invalidate()
    }

    private fun place() {
        val layout = controller?.layout ?: return
        val out = ArrayList<Placed>()
        val rh = (height - edge * 2) / layout.rows.size
        layout.rows.forEachIndexed { r, row ->
            val total = row.sumOf { it.weight.toDouble() }.toFloat()
            val unit = (width - edge * 2) / total
            var x = edge
            val top = edge + r * rh
            for (k in row) {
                val w = unit * k.weight
                out += Placed(k, RectF(x + gap / 2, top + rowGap / 2, x + w - gap / 2, top + rh - rowGap / 2))
                x += w
            }
        }
        placed = out
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        placed = emptyList()
        bgShader = null
    }

    private fun fitText(label: String, maxWidth: Float, size: Float) {
        text.textSize = size
        val w = text.measureText(label)
        if (w > maxWidth) text.textSize = size * maxWidth / w
    }

    private fun drawBackground(canvas: Canvas) {
        if (!theme.isGradient) {
            canvas.drawColor(bg)
            return
        }
        // The gradient shader is built once per theme and size, never per frame.
        var shader = bgShader
        if (shader == null) {
            shader = LinearGradient(0f, 0f, 0f, height.toFloat(), theme.bgTop, theme.bgBottom, Shader.TileMode.CLAMP)
            bgShader = shader
            bgPaint.shader = shader
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
    }

    override fun onDraw(canvas: Canvas) {
        drawBackground(canvas)
        if (placed.isEmpty()) place()
        val c = controller ?: return
        val down = pressed.values.toSet()
        for (p in placed) {
            val key = p.key
            if (key.isSpacer) continue
            val dead = key.kind == KeyKind.FIDEL_VOWEL && !c.composing
            fill.color = when {
                p in down -> pressedColor
                key.kind == KeyKind.ENTER -> accent
                key.kind == KeyKind.CHAR || key.kind == KeyKind.SPACE || key.kind == KeyKind.FIDEL_CONSONANT -> keyColor
                key.kind == KeyKind.SHIFT && c.shift != ShiftState.OFF -> pressedColor
                else -> specialColor
            }
            val r = p.rect
            val radius = theme.cornerRadiusDp * d
            if (theme.hasShadow) {
                val keyFill = fill.color
                fill.color = theme.shadow
                val dy = theme.shadowDyDp * d
                canvas.drawRoundRect(r.left, r.top + dy, r.right, r.bottom + dy, radius, radius, fill)
                fill.color = keyFill
            }
            canvas.drawRoundRect(r, radius, radius, fill)
            if (theme.hasBorder) {
                val hw = borderPaint.strokeWidth / 2f
                borderPaint.color = theme.border
                canvas.drawRoundRect(r.left + hw, r.top + hw, r.right - hw, r.bottom - hw, radius, radius, borderPaint)
            }
            if (key.kind == KeyKind.GLOBE) {
                drawGlobe(canvas, p.rect.centerX(), p.rect.centerY(), min(p.rect.width(), p.rect.height()) * 0.24f)
                continue
            }
            val label = when (key.kind) {
                KeyKind.ENTER -> enterText(c.profile.enterLabel)
                KeyKind.SHIFT -> if (c.shift == ShiftState.LOCKED) "⇪" else "⇧"
                KeyKind.SPACE -> key.label.trim()
                KeyKind.CHAR -> if (key.isLetter && c.shift != ShiftState.OFF) key.label.uppercase() else key.label
                else -> key.label
            }
            text.color = when {
                key.kind == KeyKind.ENTER -> onAccent
                key.kind == KeyKind.SPACE || dead -> dimColor
                key.kind == KeyKind.CHAR || key.kind == KeyKind.FIDEL_CONSONANT -> textColor
                else -> specialText
            }
            val base = when {
                key.kind == KeyKind.SPACE -> 13f
                label.length > 2 -> 15f
                else -> 21f
            } * d
            fitText(label, p.rect.width() - 4f * d, base)
            var y = p.rect.centerY() - (text.descent() + text.ascent()) / 2
            // Short keys (landscape): keep the label clear of the DRAFT badge above it.
            if (key.draftBadge) y += max(0f, p.rect.top + BADGE_BOTTOM_DP * d - (y + text.ascent()))
            canvas.drawText(label, p.rect.centerX(), y, text)
            // Secondary label: the first alternative, or (phonetic letters) the native character
            // for the current shift state. Read from the layout model only: no engine call here.
            val hint = if (key.shiftHint != null && c.shift != ShiftState.OFF) key.shiftHint else key.hint
            if (hint.isNotEmpty()) {
                hintPaint.color = dimColor
                hintPaint.textSize = 11f * d
                canvas.drawText(hint, p.rect.right - 4f * d, p.rect.top + 12f * d, hintPaint)
            }
            if (key.draftBadge) {
                text.color = accentText
                text.textSize = 8f * d
                canvas.drawText("DRAFT", p.rect.centerX(), p.rect.top + 9f * d, text)
            }
        }
    }

    /** The language key as a drawn globe in the special-key label colour (no colour emoji). */
    private fun drawGlobe(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        icon.color = specialText
        icon.strokeWidth = 1.6f * d
        canvas.drawCircle(cx, cy, r, icon)
        canvas.drawOval(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r, icon)
        canvas.drawLine(cx - r, cy, cx + r, cy, icon)
        canvas.drawLine(cx, cy - r, cx, cy + r, icon)
        val y = r * 0.5f
        val half = r * 0.866f
        canvas.drawLine(cx - half, cy - y, cx + half, cy - y, icon)
        canvas.drawLine(cx - half, cy + y, cx + half, cy + y, icon)
    }

    private fun enterText(l: EnterLabel) = when (l) {
        EnterLabel.RETURN -> "⏎"
        EnterLabel.GO -> "Go"
        EnterLabel.SEARCH -> "Search"
        EnterLabel.SEND -> "Send"
        EnterLabel.NEXT -> "Next"
        EnterLabel.PREVIOUS -> "Prev"
        EnterLabel.DONE -> "Done"
    }

    /** The key under the point; taps in the gaps land on the nearest key within one row height. */
    private fun hit(x: Float, y: Float): Placed? {
        if (placed.isEmpty()) place()
        placed.firstOrNull { !it.key.isSpacer && it.rect.contains(x, y) }?.let { return it }
        val limit = rowHeight * rowHeight
        return placed.filter { !it.key.isSpacer }
            .map { it to sq(x - it.rect.centerX()) + sq(y - it.rect.centerY()) }
            .minByOrNull { it.second }
            ?.takeIf { it.second < limit }?.first
    }

    private fun sq(v: Float) = v * v

    // ------------------------------------------------------------------ long-press popup

    private fun fireLongPress() {
        val p = lpKey ?: return
        val c = controller ?: return
        if (pressed[lpPointer] != p) return
        lpFired = true
        if (c.prefs.hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (p.key.popup.isNotEmpty()) showPopup(p) else if (p.key.longAction) c.onLongPress(p.key)
    }

    private fun showPopup(p: Placed) {
        val items = p.key.popup
        val cols = min(p.key.popupColumns, items.size).coerceAtLeast(1)
        val rows = ceil(items.size / cols.toFloat()).toInt()
        text.textSize = 20f * d
        val widest = items.maxOf { text.measureText(it.label) }
        val cw = if (cols == 1) max(widest + 40f * d, 120f * d) else max(44f * d, widest + 16f * d)
        val ch = if (cols == 1) 44f * d else 48f * d
        val pw = cw * cols
        val ph = ch * rows
        val left = (p.rect.centerX() - pw / 2).coerceIn(0f, max(0f, width - pw))
        val top = p.rect.top - ph - 4f * d
        popupRect = RectF(left, top, left + pw, top + ph)
        popupItems = items
        popupCols = cols
        popupCellW = cw
        popupCellH = ch
        popupSel = -1
        popupOpen = true
        pushPopup()
    }

    /** Hands the popup state to the overlay, converting from surface to overlay coordinates. */
    private fun pushPopup() {
        val o = overlay ?: return
        val sl = IntArray(2)
        val ol = IntArray(2)
        getLocationInWindow(sl)
        o.getLocationInWindow(ol)
        o.show(popupItems, popupCols, popupCellW, popupCellH, popupRect.left + sl[0] - ol[0], popupRect.top + sl[1] - ol[1], popupSel)
    }

    private fun popupIndexAt(x: Float, y: Float): Int {
        if (!popupRect.contains(x, y)) return -1
        val col = ((x - popupRect.left) / popupCellW).toInt().coerceIn(0, popupCols - 1)
        val row = ((y - popupRect.top) / popupCellH).toInt()
        val i = row * popupCols + col
        return if (i in popupItems.indices) i else -1
    }

    private fun dismissPopup() {
        popupOpen = false
        overlay?.hide()
        popupSel = -1
        popupItems = emptyList()
    }

    // ------------------------------------------------------------------ touch

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val c = controller ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val p = hit(e.getX(i), e.getY(i)) ?: return true
                pressed[id] = p
                if (c.prefs.hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (p.key.actsOnRelease) {
                    // A new press while a popup is open cancels it.
                    if (popupOpen) dismissPopup()
                    lpPointer = id
                    lpKey = p
                    lpDownX = e.getX(i)
                    lpDownY = e.getY(i)
                    lpFired = false
                    removeCallbacks(longPress)
                    postDelayed(longPress, longPressMs)
                } else {
                    c.onKey(p.key)
                    if (p.key.kind == KeyKind.BACKSPACE) {
                        repeating = p.key
                        repeatPointer = id
                        postDelayed(repeat, 400)
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(lpPointer)
                if (i >= 0) {
                    val x = e.getX(i)
                    val y = e.getY(i)
                    if (popupOpen) {
                        val sel = popupIndexAt(x, y)
                        if (sel != popupSel) {
                            popupSel = sel
                            pushPopup()
                        }
                    } else if (!lpFired && (sq(x - lpDownX) + sq(y - lpDownY)) > slop * slop) {
                        // Slid away before the long-press: neither a tap nor a popup.
                        removeCallbacks(longPress)
                        lpKey = null
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                val key = pressed.remove(id)
                if (id == repeatPointer) stopRepeat()
                if (id == lpPointer) {
                    removeCallbacks(longPress)
                    val sel = popupSel
                    val items = popupItems
                    val hadPopup = popupOpen
                    val k = lpKey
                    val fired = lpFired
                    dismissPopup()
                    lpPointer = -1
                    lpKey = null
                    if (hadPopup) {
                        if (sel in items.indices) c.onPopupSelected(items[sel])
                    } else if (k != null && !fired) {
                        c.onKey(k.key) // a plain tap on a key that acts on release
                    }
                } else if (key == null) {
                    // pointer that never hit a key
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed.clear()
                stopRepeat()
                removeCallbacks(longPress)
                dismissPopup()
                lpPointer = -1
                lpKey = null
                invalidate()
            }
        }
        return true
    }

    private fun stopRepeat() {
        removeCallbacks(repeat)
        repeating = null
        repeatPointer = -1
    }

    private companion object {
        /** Bottom of the DRAFT badge below the key top, plus a 1dp gap. */
        const val BADGE_BOTTOM_DP = 11f
    }

    override fun onDetachedFromWindow() {
        stopRepeat()
        removeCallbacks(longPress)
        dismissPopup()
        super.onDetachedFromWindow()
    }
}
