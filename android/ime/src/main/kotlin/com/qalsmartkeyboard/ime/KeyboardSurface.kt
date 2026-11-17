package com.qalsmartkeyboard.ime

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * Custom-View key surface (ADR 0003): draws the keys on one Canvas and resolves touches itself.
 * It draws no key-preview popups, so characters typed in password fields are never echoed by us.
 */
class KeyboardSurface(context: Context) : View(context) {
    var controller: KeyboardController? = null

    private class Placed(val key: Key, val rect: RectF)

    private var placed: List<Placed> = emptyList()
    private val pressed = HashMap<Int, Placed>() // pointer id -> key
    private val d = resources.displayMetrics.density
    private val rowHeight = 50f * d
    private val gap = 3f * d
    private val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private val bg = if (dark) 0xFF1B1B1F.toInt() else 0xFFE4E5EA.toInt()
    private val keyColor = if (dark) 0xFF3A3B40.toInt() else 0xFFFFFFFF.toInt()
    private val specialColor = if (dark) 0xFF2A2B30.toInt() else 0xFFC9CBD3.toInt()
    private val pressedColor = if (dark) 0xFF5A5C66.toInt() else 0xFFA9ABB5.toInt()
    private val accent = 0xFF2E7D32.toInt()
    private val textColor = if (dark) 0xFFF1F1F4.toInt() else 0xFF1B1B1F.toInt()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private var repeating: Key? = null
    private var repeatPointer = -1
    private val repeat = object : Runnable {
        override fun run() {
            val k = repeating ?: return
            controller?.onKey(k)
            postDelayed(this, 50)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rows = controller?.layout?.rows?.size ?: 5
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            (rows * rowHeight + gap * 2).toInt(),
        )
    }

    /** Called when the page, shift state or field profile changed. */
    fun onLayoutChanged() {
        placed = emptyList()
        requestLayout()
        invalidate()
    }

    private fun place() {
        val layout = controller?.layout ?: return
        val out = ArrayList<Placed>()
        val rh = (height - gap * 2) / layout.rows.size
        layout.rows.forEachIndexed { r, row ->
            val total = row.sumOf { it.weight.toDouble() }.toFloat()
            val unit = (width - gap * 2) / total
            var x = gap
            val top = gap + r * rh
            for (k in row) {
                val w = unit * k.weight
                out += Placed(k, RectF(x + gap / 2, top + gap / 2, x + w - gap / 2, top + rh - gap / 2))
                x += w
            }
        }
        placed = out
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        placed = emptyList()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bg)
        if (placed.isEmpty()) place()
        val c = controller ?: return
        val down = pressed.values.toSet()
        for (p in placed) {
            if (p.key.isSpacer) continue
            fill.color = when {
                p in down -> pressedColor
                p.key.kind == KeyKind.ENTER -> accent
                p.key.kind == KeyKind.CHAR || p.key.kind == KeyKind.SPACE -> keyColor
                p.key.kind == KeyKind.SHIFT && c.shift != ShiftState.OFF -> pressedColor
                else -> specialColor
            }
            canvas.drawRoundRect(p.rect, 6f * d, 6f * d, fill)
            val label = when (p.key.kind) {
                KeyKind.ENTER -> enterText(c.profile.enterLabel)
                KeyKind.SHIFT -> if (c.shift == ShiftState.LOCKED) "⇪" else "⇧"
                KeyKind.SPACE -> ""
                KeyKind.CHAR -> if (p.key.isLetter && c.shift != ShiftState.OFF) p.key.label.uppercase() else p.key.label
                else -> p.key.label
            }
            text.color = if (p.key.kind == KeyKind.ENTER) 0xFFFFFFFF.toInt() else textColor
            text.textSize = (if (label.length > 2) 15f else 21f) * d
            val y = p.rect.centerY() - (text.descent() + text.ascent()) / 2
            canvas.drawText(label, p.rect.centerX(), y, text)
        }
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

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val c = controller ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val p = hit(e.getX(i), e.getY(i)) ?: return true
                pressed[id] = p
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                c.onKey(p.key)
                if (p.key.kind == KeyKind.BACKSPACE) {
                    repeating = p.key
                    repeatPointer = id
                    postDelayed(repeat, 400)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                pressed.remove(id)
                if (id == repeatPointer) stopRepeat()
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed.clear()
                stopRepeat()
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

    override fun onDetachedFromWindow() {
        stopRepeat()
        super.onDetachedFromWindow()
    }
}
