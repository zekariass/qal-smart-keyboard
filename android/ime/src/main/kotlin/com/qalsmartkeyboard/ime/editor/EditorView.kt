package com.qalsmartkeyboard.ime.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.EditText
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.qalsmartkeyboard.ime.theme.KeyboardTheme
import com.qalsmartkeyboard.ime.toolbar.Motion
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Draws an [EditorBuffer] and turns touches into cursor moves and selections (ADR 0038): the View half of the keyboard's
 * own text field. The keys never reach this View (an input method cannot type into its own windows); they write into the
 * buffer through the keyboard's redirect, and this View redraws on the buffer's changes.
 *
 * - **Text:** a [StaticLayout] with the first-strong direction heuristic, so an Arabic paragraph runs right to left and
 *   Ge'ez or Latin left to right, mixed text is reordered by the platform's bidi algorithm; it wraps and scrolls
 *   vertically between [minLines] and [maxLines] (the caret stays in view).
 * - **Tap** anywhere: the caret goes to the nearest position ([Layout.getOffsetForHorizontal], which knows the visual
 *   order of bidi runs), snapped to a grapheme cluster. A tap on the caret itself, a **long press** (selects the word)
 *   or a **double tap** (selects the word) shows the action row ([actionsShown]; Cut, Copy, Paste, Select all).
 *   After a long press, dragging extends the selection; the two **handles** under a selection can be dragged.
 * - **Caret** blinks (steady with "Remove animations"); the composition is underlined; the selection is highlighted.
 * - **Accessibility:** an editable text node (EditText role) with its text, hint and selection; TalkBack can set the
 *   selection, move by character or word, and cut/copy/paste/select all.
 *
 * [beforeEdit] runs before any change the user makes here (the service commits the open composition into the buffer
 * first, so the composer never continues a word at a cursor that moved). Holds no text of its own.
 */
class EditorView(context: Context) : View(context), EditorBuffer.Observer {
    var buffer: EditorBuffer? = null
        set(value) {
            if (field === value) return
            field?.removeObserver(this)
            field = value
            value?.addObserver(this) // removed again when the view leaves the window (no leak of an old input view)
            layoutVersion = -2
            scroll = 0f
            setActions(false)
            followCaret = true
            requestLayout()
            invalidate()
        }

    var hint: CharSequence = ""
        set(value) {
            if (field.toString() == value.toString()) return
            field = value
            hintLayout = null
            invalidate()
        }

    /** The text size in sp. */
    var textSizeSp: Float = 16f
        set(value) {
            if (field == value) return
            field = value
            paint.textSize = sp(value)
            hintPaint.textSize = paint.textSize
            layoutVersion = -2
            hintLayout = null
            requestLayout()
        }

    var minLines: Int = 1
        set(value) { field = value; requestLayout() }
    var maxLines: Int = 3
        set(value) { field = value; requestLayout() }

    /** The caret and the touch editing are on (false: a display of the text that only reports taps, [onTap]). */
    var editable: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (!value) setActions(false)
            restartBlink()
        }

    /** A tap on the field (after the caret moved): e.g. the Word Art panel's field starts typing. */
    var onTap: () -> Unit = {}

    /** Before any edit made here (a tap, a selection, an action): the service finishes the open composition. */
    var beforeEdit: () -> Unit = {}

    /** The action row should show or hide. */
    var onActionsChanged: (Boolean) -> Unit = {}

    /** What TalkBack reads for the field (its name and the text). */
    var describe: (String) -> String = { it }

    /** The clipboard for TalkBack's copy/cut/paste and the action row's state. */
    var clipboard: EditorClipboard = EditorClipboard.None

    /** Whether a long press gives haptic feedback (the keys' setting). */
    var haptics: () -> Boolean = { true }

    /** The action row (Cut, Copy, Paste, Select all) is showing. */
    var actionsShown: Boolean = false
        private set

    private val d = resources.displayMetrics.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(16f) }
    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(16f) }
    private val caretPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    private var layout: StaticLayout? = null
    private var layoutVersion = -2
    private var layoutWidth = -1
    private var hintLayout: StaticLayout? = null

    /** Vertical scroll of the text inside the view (px). */
    private var scroll = 0f
    private var followCaret = true

    init {
        isClickable = true
        isFocusable = true
        isLongClickable = true
        setPadding((4f * d).toInt(), (4f * d).toInt(), (4f * d).toInt(), (HANDLE_RADIUS_DP * d).toInt())
        applyColors(0xFF000000.toInt(), 0x99000000.toInt(), 0xFF1A73E8.toInt())
        ViewCompat.setAccessibilityDelegate(this, Accessibility())
    }

    /** The theme's contrast-checked pairs (ADR 0031 §5): key text on the key fill; the accent for caret and selection. */
    fun applyTheme(t: KeyboardTheme) = applyColors(t.keyText, t.hintText, t.accentText)

    fun applyColors(text: Int, hintColor: Int, accent: Int) {
        paint.color = text
        hintPaint.color = hintColor
        caretPaint.color = accent
        selectionPaint.color = (accent and 0x00FFFFFF) or (0x4D shl 24)
        invalidate()
    }

    // ------------------------------------------------------------------ buffer changes

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        buffer?.addObserver(this)
        restartBlink()
    }

    override fun onDetachedFromWindow() {
        buffer?.removeObserver(this)
        removeCallbacks(blink)
        timer.removeCallbacks(longPress)
        super.onDetachedFromWindow()
    }

    override fun onEditorChanged(textChanged: Boolean) {
        // Typing (not an action of the row) hides the row, like a text field's selection toolbar.
        if (textChanged && !acting) setActions(false)
        if (!dragging) followCaret = true
        if (textChanged) requestLayout()
        restartBlink()
    }

    /** Re-reads the buffer (the host calls this after binding a new text; changes otherwise arrive by observation). */
    fun refresh() {
        layoutVersion = -2
        followCaret = true
        requestLayout()
        invalidate()
    }

    // ------------------------------------------------------------------ layout

    private val direction = TextDirectionHeuristics.FIRSTSTRONG_LTR

    private fun build(width: Int): StaticLayout {
        val b = buffer
        val ver = b?.version ?: -1
        val l = layout
        if (l != null && ver == layoutVersion && width == layoutWidth) return l
        val t = b?.text.orEmpty()
        val built = StaticLayout.Builder.obtain(t, 0, t.length, paint, maxOf(1, width))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(direction)
            .setIncludePad(false)
            .build()
        layout = built
        layoutVersion = ver
        layoutWidth = width
        return built
    }

    private fun hint(width: Int): StaticLayout {
        hintLayout?.takeIf { it.width == maxOf(1, width) }?.let { return it }
        val h = hint
        return StaticLayout.Builder.obtain(h, 0, h.length, hintPaint, maxOf(1, width))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(direction)
            .setIncludePad(false)
            .build().also { hintLayout = it }
    }

    private val lineHeight: Int get() = paint.fontMetricsInt.let { it.descent - it.ascent }

    private val textWidth: Int get() = maxOf(1, width - paddingLeft - paddingRight)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) (240f * d).toInt() else MeasureSpec.getSize(widthMeasureSpec)
        val inner = maxOf(1, w - paddingLeft - paddingRight)
        val l = build(inner)
        val lines = (if (buffer?.isEmpty != false) hint(inner).lineCount else l.lineCount).coerceIn(minLines, maxOf(minLines, maxLines))
        val want = lines * lineHeight + paddingTop + paddingBottom
        setMeasuredDimension(w, resolveSize(want, heightMeasureSpec))
    }

    private val visibleHeight: Int get() = maxOf(1, height - paddingTop - paddingBottom)

    private fun maxScroll(l: Layout): Float = maxOf(0, l.height - visibleHeight).toFloat()

    /** Keeps the caret's line (the selection's end) inside the visible lines. */
    private fun keepCaretVisible(l: Layout) {
        val b = buffer ?: return
        val line = l.getLineForOffset(b.selectionEnd)
        val top = l.getLineTop(line).toFloat()
        val bottom = l.getLineBottom(line).toFloat()
        if (top < scroll) scroll = top
        if (bottom > scroll + visibleHeight) scroll = bottom - visibleHeight
        scroll = scroll.coerceIn(0f, maxScroll(l))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (followCaret) {
            keepCaretVisible(build(textWidth))
            followCaret = false
        }
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        val l = build(textWidth)
        val b = buffer
        if (followCaret) {
            keepCaretVisible(l)
            followCaret = false
        }
        scroll = scroll.coerceIn(0f, maxScroll(l))
        canvas.save()
        canvas.clipRect(0, paddingTop, width, height)
        canvas.translate(paddingLeft.toFloat(), paddingTop - scroll)
        if (b == null || b.isEmpty) {
            if (hint.isNotEmpty()) hint(textWidth).draw(canvas)
        } else {
            if (b.hasSelection) {
                path.reset()
                l.getSelectionPath(b.selectionStart, b.selectionEnd, path)
                canvas.drawPath(path, selectionPaint)
            }
            if (b.isComposing) underline(canvas, l, b.composingStart, b.composingEnd)
            l.draw(canvas)
        }
        if (editable && b != null) {
            if (!b.hasSelection) {
                if (caretOn) {
                    val line = l.getLineForOffset(b.cursor)
                    val x = caretX(l, b.cursor)
                    canvas.drawRect(x - d, l.getLineTop(line).toFloat(), x + d, l.getLineBottom(line).toFloat(), caretPaint)
                }
            } else {
                handle(canvas, l, b.selectionStart)
                handle(canvas, l, b.selectionEnd)
            }
        }
        canvas.restore()
    }

    private fun caretX(l: Layout, offset: Int): Float = l.getPrimaryHorizontal(offset).coerceIn(d, textWidth - d)

    private fun underline(canvas: Canvas, l: Layout, start: Int, end: Int) {
        val first = l.getLineForOffset(start)
        val last = l.getLineForOffset(end)
        for (line in first..last) {
            val a = maxOf(start, l.getLineStart(line))
            val e = minOf(end, l.getLineEnd(line))
            if (a >= e) continue
            path.reset()
            l.getSelectionPath(a, e, path)
            path.computeBounds(rect, true)
            val y = l.getLineBottom(line) - d
            canvas.drawRect(rect.left, y - d, rect.right, y + d / 2, paint)
        }
    }

    /** A selection handle: a small knob under the line at [offset]. */
    private fun handle(canvas: Canvas, l: Layout, offset: Int) {
        val (x, y) = handlePoint(l, offset)
        canvas.drawRect(x - d, y - lineHeight, x + d, y, caretPaint)
        canvas.drawCircle(x, y + HANDLE_RADIUS_DP * d * 0.6f, HANDLE_RADIUS_DP * d * 0.6f, caretPaint)
    }

    private fun handlePoint(l: Layout, offset: Int): Pair<Float, Float> {
        val line = l.getLineForOffset(offset)
        return caretX(l, offset) to l.getLineBottom(line).toFloat()
    }

    // ------------------------------------------------------------------ caret blink

    private var caretOn = true
    private val blink = object : Runnable {
        override fun run() {
            if (!isShown) return
            caretOn = !caretOn
            invalidate()
            postDelayed(this, BLINK_MS)
        }
    }

    private fun restartBlink() {
        removeCallbacks(blink)
        caretOn = true
        invalidate()
        if (editable && isAttachedToWindow && Motion.enabled(context)) postDelayed(blink, BLINK_MS)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) restartBlink() else removeCallbacks(blink)
    }

    // ------------------------------------------------------------------ touch

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downScroll = 0f
    private var moved = false
    private var dragging = false
    private var longPressed = false
    private var dragAnchor = -1
    private var wordStart = -1
    private var wordEnd = -1
    private var lastTapAt = -1L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private enum class Drag { NONE, SCROLL, HANDLE, WORD }

    private var drag = Drag.NONE

    private val longPress = Runnable { onLongPress() }

    /** The long-press timer runs on the main looper directly (a view's own queue waits until it is attached). */
    private val timer = android.os.Handler(android.os.Looper.getMainLooper())

    /** The logical offset nearest to the view point ([x], [y]) (bidi-aware through the layout). */
    fun offsetAt(x: Float, y: Float): Int {
        val l = build(textWidth)
        if (buffer?.isEmpty != false) return 0
        val yy = (y - paddingTop + scroll).toInt().coerceIn(0, maxOf(0, l.height - 1))
        val line = l.getLineForVertical(yy)
        return l.getOffsetForHorizontal(line, x - paddingLeft)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val b = buffer ?: return super.onTouchEvent(e)
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                downScroll = scroll
                moved = false
                longPressed = false
                drag = Drag.NONE
                isPressed = true
                if (editable && b.hasSelection) {
                    val l = build(textWidth)
                    val near = listOf(b.selectionStart to b.selectionEnd, b.selectionEnd to b.selectionStart).firstOrNull { (o, _) ->
                        val (hx, hy) = handlePoint(l, o)
                        hypot(e.x - (hx + paddingLeft), e.y - (hy + paddingTop - scroll + HANDLE_RADIUS_DP * d * 0.6f)) < HANDLE_TOUCH_DP * d
                    }
                    if (near != null) {
                        drag = Drag.HANDLE
                        dragAnchor = near.second
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
                if (editable) timer.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) {
                    moved = true
                    timer.removeCallbacks(longPress)
                    if (drag == Drag.NONE) {
                        drag = if (longPressed) Drag.WORD else Drag.SCROLL
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                when (drag) {
                    Drag.HANDLE -> {
                        beforeEdit()
                        b.setSelection(dragAnchor, offsetAt(e.x, e.y - lineHeight / 2f))
                        followCaret = false
                        invalidate()
                    }
                    Drag.WORD -> {
                        val o = offsetAt(e.x, e.y)
                        if (o < wordStart) b.setSelection(wordEnd, o) else b.setSelection(wordStart, maxOf(o, wordEnd))
                        invalidate()
                    }
                    Drag.SCROLL -> {
                        scroll = (downScroll - (e.y - downY)).coerceIn(0f, maxScroll(build(textWidth)))
                        invalidate()
                    }
                    Drag.NONE -> Unit
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                timer.removeCallbacks(longPress)
                isPressed = false
                val wasDrag = drag
                dragging = false
                drag = Drag.NONE
                if (wasDrag == Drag.HANDLE || wasDrag == Drag.WORD) {
                    setActions(true)
                    return true
                }
                if (!moved && !longPressed) tap(e.x, e.y)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                timer.removeCallbacks(longPress)
                isPressed = false
                dragging = false
                drag = Drag.NONE
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun tap(x: Float, y: Float) {
        val b = buffer ?: return
        if (!editable) {
            // A display of the text (the Word Art panel): the cursor still goes where the tap was, for the typing to come.
            if (!b.isEmpty) b.setSelection(offsetAt(x, y))
            performClick()
            return
        }
        beforeEdit()
        val now = SystemClock.uptimeMillis()
        val double = lastTapAt >= 0 && now - lastTapAt < ViewConfiguration.getDoubleTapTimeout() && hypot(x - lastTapX, y - lastTapY) < slop * 2
        lastTapAt = if (double) -1L else now
        lastTapX = x
        lastTapY = y
        val o = offsetAt(x, y)
        if (double && b.selectWordAt(o)) {
            setActions(true)
        } else {
            val onCaret = !b.hasSelection && b.snap(o) == b.cursor
            b.setSelection(o)
            // A tap on the caret itself offers the row (Paste, Select all), like a text field's insertion handle.
            setActions(onCaret && !actionsShown)
        }
        performClick()
    }

    private fun onLongPress() {
        val b = buffer ?: return
        if (moved || !editable) return
        longPressed = true
        beforeEdit()
        if (haptics()) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val o = offsetAt(downX, downY)
        if (b.selectWordAt(o)) {
            wordStart = b.selectionStart
            wordEnd = b.selectionEnd
        } else {
            b.setSelection(o)
            wordStart = b.cursor
            wordEnd = b.cursor
        }
        setActions(true)
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap()
        return true
    }

    private var acting = false

    /** Shows or hides the action row. */
    fun setActions(shown: Boolean) {
        val s = shown && editable && buffer != null
        if (actionsShown == s) return
        actionsShown = s
        onActionsChanged(s)
        invalidate()
    }

    /** Runs a row action (Cut, Copy, Paste, Select all) on the buffer; the row stays only after Select all. */
    fun perform(action: EditorAction): Boolean {
        val b = buffer ?: return false
        beforeEdit()
        acting = true
        val done = try {
            EditorActions.perform(action, b, clipboard)
        } finally {
            acting = false
        }
        setActions(action == EditorAction.SELECT_ALL && b.hasSelection)
        if (done && action == EditorAction.COPY) announceForAccessibility(context.getString(com.qalsmartkeyboard.ime.R.string.ai_copied))
        return done
    }

    // ------------------------------------------------------------------ accessibility

    private inner class Accessibility : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            val b = buffer
            val t = b?.text.orEmpty()
            info.className = EditText::class.java.name
            info.isEditable = editable
            info.isFocusable = true
            info.isClickable = true
            info.isLongClickable = editable
            info.text = t.ifEmpty { null }
            info.hintText = hint.toString()
            info.contentDescription = describe(t)
            info.isShowingHintText = t.isEmpty()
            if (b != null && editable) {
                info.setTextSelection(b.selectionStart, b.selectionEnd)
                info.addAction(AccessibilityNodeInfoCompat.ACTION_SET_SELECTION)
                info.movementGranularities = AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_CHARACTER or AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_WORD
                info.addAction(AccessibilityNodeInfoCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY)
                info.addAction(AccessibilityNodeInfoCompat.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY)
                // Select all is a set-selection for TalkBack (it has no separate action).
                EditorActions.available(b, clipboard).forEach { a ->
                    when (a) {
                        EditorAction.CUT -> info.addAction(AccessibilityNodeInfoCompat.ACTION_CUT)
                        EditorAction.COPY -> info.addAction(AccessibilityNodeInfoCompat.ACTION_COPY)
                        EditorAction.PASTE -> info.addAction(AccessibilityNodeInfoCompat.ACTION_PASTE)
                        EditorAction.SELECT_ALL -> Unit
                    }
                }
            }
        }

        override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
            val b = buffer ?: return super.performAccessibilityAction(host, action, args)
            when (action) {
                AccessibilityNodeInfoCompat.ACTION_SET_SELECTION -> {
                    beforeEdit()
                    val s = args?.getInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_START_INT, b.cursor) ?: b.cursor
                    val e = args?.getInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_END_INT, s) ?: s
                    b.setSelection(s, e)
                    return true
                }
                AccessibilityNodeInfoCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY,
                AccessibilityNodeInfoCompat.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY -> {
                    val forward = action == AccessibilityNodeInfoCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
                    val g = args?.getInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT) ?: 0
                    beforeEdit()
                    if (g == AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_WORD) moveWord(b, forward) else b.moveCursor(if (forward) 1 else -1)
                    return true
                }
                AccessibilityNodeInfoCompat.ACTION_CUT -> return perform(EditorAction.CUT)
                AccessibilityNodeInfoCompat.ACTION_COPY -> return perform(EditorAction.COPY)
                AccessibilityNodeInfoCompat.ACTION_PASTE -> return perform(EditorAction.PASTE)
            }
            return super.performAccessibilityAction(host, action, args)
        }
    }

    private fun moveWord(b: EditorBuffer, forward: Boolean) {
        val t = b.text
        var i = b.cursor
        if (forward) {
            while (i < t.length && !Character.isLetterOrDigit(t.codePointAt(i))) i = b.nextBoundary(i)
            while (i < t.length && Character.isLetterOrDigit(t.codePointAt(i))) i = b.nextBoundary(i)
        } else {
            while (i > 0 && !Character.isLetterOrDigit(t.codePointBefore(i))) i = b.previousBoundary(i)
            while (i > 0 && Character.isLetterOrDigit(t.codePointBefore(i))) i = b.previousBoundary(i)
        }
        b.setSelection(i)
    }

    companion object {
        private const val BLINK_MS = 500L
        private const val HANDLE_RADIUS_DP = 8f
        private const val HANDLE_TOUCH_DP = 28f
    }
}
