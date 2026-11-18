package com.qalsmartkeyboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.customview.widget.ExploreByTouchHelper
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
 * keys act on press. A pressed character key shows a preview bubble in the same overlay
 * ([KeyPreview]); it is off in password fields, so what is typed there is never echoed by us.
 *
 * Accessibility: every key is a virtual view ([KeyNodes], `ExploreByTouchHelper`) with its spoken
 * name and the Button role; an accessibility click types the key like a tap, and keys with a
 * long-press action (the globe) offer it as a long click. Long-press popups (Fidel forms, Ethiopic
 * punctuation and numerals) are touch-only.
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

    // What the last full layout pass saw; a change confined to the Fidel composition state takes the cheap path.
    private var seenLayout: KeyboardLayout? = null
    private var seenShift = ShiftState.OFF
    private var seenPage = Page.LETTERS
    private var seenEnter: EnterLabel? = null
    private var seenSpoken: String? = null
    private var seenSeries: String? = null
    private val dirty = Rect()

    /**
     * Called when the page, shift state, language/layout or field profile changed. When only the pending
     * Fidel consonant changed (the vowel-order row's family, the selected-consonant highlight) the key
     * placement and accessibility tree stay and only the affected keys are redrawn and re-announced.
     */
    fun onLayoutChanged() {
        val c = controller
        if (c != null && placed.isNotEmpty() && c.layout === seenLayout && c.shift == seenShift && c.page == seenPage &&
            c.profile.enterLabel == seenEnter && c.modeDescription == seenSpoken
        ) {
            if (c.pendingSeries != seenSeries) refreshFidelKeys(seenSeries, c.pendingSeries)
            seenSeries = c.pendingSeries
            return
        }
        if (c != null) {
            seenLayout = c.layout
            seenShift = c.shift
            seenPage = c.page
            seenEnter = c.profile.enterLabel
            seenSpoken = c.modeDescription
            seenSeries = c.pendingSeries
        }
        dismissPopup()
        placed = emptyList()
        keyNodes.invalidateRoot()
        controller?.let { c ->
            val spoken = c.modeDescription
            contentDescription = "Keyboard, $spoken"
            if (announcedMode != null && announcedMode != spoken) announceForAccessibility(spoken)
            announcedMode = spoken
        }
        requestLayout()
        invalidate()
    }

    /** Redraws the vowel-order row and the old and new selected consonant keys only, and tells TalkBack their text changed. */
    private fun refreshFidelKeys(old: String?, new: String?) {
        var any = false
        placed.forEachIndexed { i, p ->
            val k = p.key
            val affected = k.kind == KeyKind.FIDEL_VOWEL ||
                (k.kind == KeyKind.FIDEL_CONSONANT && (k.text == old || k.text == new))
            if (!affected) return@forEachIndexed
            val r = p.rect
            if (!any) dirty.set(r.left.toInt(), r.top.toInt(), ceil(r.right).toInt(), ceil(r.bottom).toInt())
            else dirty.union(r.left.toInt(), r.top.toInt(), ceil(r.right).toInt(), ceil(r.bottom).toInt())
            any = true
            keyNodes.invalidateVirtualView(i, AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION)
        }
        if (any) invalidate(dirty.left, dirty.top, dirty.right + 1, dirty.bottom + (theme.shadowDyDp * d).toInt() + 1)
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
        keyNodes.invalidateRoot()
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
            val face = if (key.kind == KeyKind.FIDEL_VOWEL) c.orderFace(key) else null
            val dead = face != null && !face.active
            val selected = c.isSelectedConsonant(key)
            fill.color = when {
                p in down -> pressedColor
                selected -> accent
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
                KeyKind.FIDEL_VOWEL -> face?.label ?: key.label
                else -> key.label
            }
            text.color = when {
                selected -> onAccent
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
            if (key.draftBadge) {
                drawWithDraftTag(canvas, p.rect, label, base)
            } else {
                fitText(label, p.rect.width() - 4f * d, base)
                canvas.drawText(label, p.rect.centerX(), p.rect.centerY() - (text.descent() + text.ascent()) / 2, text)
            }
            // Secondary label: the first alternative, or (phonetic letters) the native character
            // for the current shift state. Read from the layout model only: no engine call here.
            val hint = if (key.shiftHint != null && c.shift != ShiftState.OFF) key.shiftHint else key.hint
            if (hint.isNotEmpty()) {
                hintPaint.color = dimColor
                hintPaint.textSize = 11f * d
                canvas.drawText(hint, p.rect.right - 4f * d, p.rect.top + 12f * d, hintPaint)
            }
        }
    }

    private val draftLabel = context.getString(R.string.candidate_strip_draft)

    /**
     * A label with the DRAFT tag (N9): stacked above it on tall keys, beside it on short (landscape)
     * keys, dropped when neither fits ([DraftTagLayout]), so the two never overlap. [text]'s colour is
     * the label's.
     */
    private fun drawWithDraftTag(canvas: Canvas, r: RectF, label: String, size: Float) {
        val labelColor = text.color
        text.textSize = size
        val l = DraftTagLayout.Text(text.measureText(label), text.ascent(), text.descent())
        text.textSize = TAG_TEXT_DP * d
        val t = DraftTagLayout.Text(text.measureText(draftLabel), text.ascent(), text.descent())
        val p = DraftTagLayout.place(r.width(), r.height(), l, t, pad = 2f * d, gap = TAG_GAP_DP * d)
        if (p.showsTag) {
            text.color = accentText
            canvas.drawText(draftLabel, r.left + p.tagX, r.top + p.tagBaseline, text)
        }
        text.color = labelColor
        text.textSize = size * p.labelScale
        canvas.drawText(label, r.left + p.labelX, r.top + p.labelBaseline, text)
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
        hidePreview()
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

    // ------------------------------------------------------------------ key press preview

    private var previewPointer = -1
    private val sl = IntArray(2)
    private val ol = IntArray(2)

    /**
     * Shows the preview bubble of [p] (its exact output) in the overlay, above the key, clamped into the
     * overlay's width; the overlay also covers the headroom above the bar, so the top row's bubble is not
     * clipped by the IME window. Must run before the key is handled (shift or the Fidel family may change).
     */
    private fun showPreview(id: Int, p: Placed, c: KeyboardController) {
        val o = overlay ?: return
        if (!c.keyPreviewsActive || o.width == 0) return
        val face = if (p.key.kind == KeyKind.FIDEL_VOWEL) c.orderFace(p.key) else null
        val label = KeyPreview.textFor(p.key, c.shift, c.page, face) ?: return
        getLocationInWindow(sl)
        o.getLocationInWindow(ol)
        val dx = (sl[0] - ol[0]).toFloat()
        val dy = (sl[1] - ol[1]).toFloat()
        val w = KeyPreview.width(p.rect.width(), PREVIEW_MIN_W_DP * d)
        val h = PREVIEW_H_DP * d
        val at = KeyPreview.place(p.rect.left + dx, p.rect.right + dx, p.rect.top + dy, w, h, PREVIEW_GAP_DP * d, o.width.toFloat(), 0f)
        previewPointer = id
        o.showPreview(label, at.left, at.top, w, h)
    }

    private fun hidePreview() {
        previewPointer = -1
        overlay?.hidePreview()
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
                showPreview(id, p, c)
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
                        if (previewPointer == lpPointer) hidePreview() // no key will be typed
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                val key = pressed.remove(id)
                if (id == previewPointer) hidePreview()
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
                hidePreview()
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

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) hidePreview()
    }

    private fun stopRepeat() {
        removeCallbacks(repeat)
        repeating = null
        repeatPointer = -1
    }

    private companion object {
        /** Size of the DRAFT tag text, and the space between it and the label. */
        const val TAG_TEXT_DP = 8f
        const val TAG_GAP_DP = 3f

        /** Key press preview bubble: smallest width, height, and the gap above the key. */
        const val PREVIEW_MIN_W_DP = 52f
        const val PREVIEW_H_DP = 56f
        const val PREVIEW_GAP_DP = 6f
    }

    // ------------------------------------------------------------------ accessibility

    private val keyNodes = KeyNodes()

    init {
        ViewCompat.setAccessibilityDelegate(this, keyNodes)
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        keyNodes.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        keyNodes.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        keyNodes.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    }

    /** What an accessibility service says for [key] in the current state (shift, Enter action, DRAFT). */
    internal fun spokenFor(key: Key): String {
        val c = controller
        return when (key.kind) {
            KeyKind.ENTER -> c?.profile?.enterLabel?.let { if (it == EnterLabel.RETURN) "Enter" else enterText(it) } ?: "Enter"
            KeyKind.SHIFT -> when (c?.shift) {
                ShiftState.LOCKED -> "Shift, caps lock on"
                ShiftState.ONCE -> "Shift, on"
                else -> key.spoken
            }
            KeyKind.CHAR -> if (key.isLetter && key.description.isEmpty() && c != null && c.shift != ShiftState.OFF) key.label.uppercase() else key.spoken
            KeyKind.FIDEL_VOWEL -> c?.orderFace(key)?.let { f ->
                if (f.active) "${f.label}, vowel order ${f.order}" else "Vowel order ${f.order}, not available"
            } ?: key.spoken
            KeyKind.FIDEL_CONSONANT -> if (c?.isSelectedConsonant(key) == true) "${key.spoken}, selected" else key.spoken
            else -> key.spoken
        }.let { if (key.draftBadge) "$it, ${context.getString(R.string.key_draft_description)}" else it }
    }

    /** One virtual accessibility node per key (index into [placed]). */
    private inner class KeyNodes : ExploreByTouchHelper(this) {
        private fun ensurePlaced() {
            if (placed.isEmpty() && width > 0 && height > 0) place()
        }

        override fun getVirtualViewAt(x: Float, y: Float): Int {
            ensurePlaced()
            val p = hit(x, y) ?: return INVALID_ID
            return placed.indexOf(p).takeIf { it >= 0 } ?: INVALID_ID
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            ensurePlaced()
            placed.forEachIndexed { i, p -> if (!p.key.isSpacer) virtualViewIds += i }
        }

        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            ensurePlaced()
            val p = placed.getOrNull(virtualViewId)
            if (p == null) {
                // Stale id (the layout changed): an empty node, as the helper requires bounds and a name.
                node.contentDescription = ""
                node.setBoundsInParent(Rect(0, 0, 1, 1))
                return
            }
            node.contentDescription = spokenFor(p.key)
            node.className = Button::class.java.name
            node.setBoundsInParent(Rect(p.rect.left.toInt(), p.rect.top.toInt(), ceil(p.rect.right).toInt(), ceil(p.rect.bottom).toInt()))
            node.isClickable = true
            node.addAction(AccessibilityActionCompat.ACTION_CLICK)
            if (p.key.longAction) {
                node.isLongClickable = true
                node.addAction(AccessibilityActionCompat(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK, context.getString(R.string.key_globe_long_action)))
            }
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            ensurePlaced() // a key press may have reset the placement (shift, page) before the next draw
            val p = placed.getOrNull(virtualViewId) ?: return false
            val c = controller ?: return false
            when (action) {
                AccessibilityNodeInfoCompat.ACTION_CLICK -> c.onKey(p.key)
                AccessibilityNodeInfoCompat.ACTION_LONG_CLICK -> if (p.key.longAction) c.onLongPress(p.key) else return false
                else -> return false
            }
            invalidate()
            sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
            return true
        }
    }

    override fun onDetachedFromWindow() {
        stopRepeat()
        removeCallbacks(longPress)
        dismissPopup()
        hidePreview()
        super.onDetachedFromWindow()
    }
}
