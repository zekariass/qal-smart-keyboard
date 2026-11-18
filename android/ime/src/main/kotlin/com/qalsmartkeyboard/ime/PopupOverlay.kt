package com.qalsmartkeyboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.qalsmartkeyboard.ime.theme.KeyboardTheme
import com.qalsmartkeyboard.ime.theme.ThemeCatalog

/**
 * Draws the long-press popup of [KeyboardSurface] (ADR 0003 follow-up). It covers the whole input
 * view, which has a transparent, non-touchable headroom above the strip (see
 * `KeyboardImeService.onComputeInsets`), so a popup can rise above the top key row. It never takes
 * touches: the surface keeps the pointer and reports the highlighted item.
 */
class PopupOverlay(context: Context) : View(context) {
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
    private val panel get() = theme.popupBg
    private val edge get() = theme.popupBorder
    private val accent get() = theme.accent
    private val onAccent get() = theme.onAccent
    private val accentText get() = theme.accentText
    private val textColor get() = theme.popupText
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private var items: List<PopupItem> = emptyList()
    private var cols = 1
    private var cw = 0f
    private var chh = 0f
    private var left = 0f
    private var top = 0f
    private var sel = -1

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun show(items: List<PopupItem>, cols: Int, cellW: Float, cellH: Float, left: Float, top: Float, selected: Int) {
        this.items = items
        this.cols = cols
        cw = cellW
        chh = cellH
        this.left = left
        this.top = top
        sel = selected
        invalidate()
    }

    fun hide() {
        if (items.isEmpty()) return
        items = emptyList()
        invalidate()
    }

    // Key press preview bubble (one at a time). Plain fields, reused: nothing is allocated per frame.
    private var previewText: String? = null
    private var pvLeft = 0f
    private var pvTop = 0f
    private var pvW = 0f
    private var pvH = 0f

    /** Draws the key preview bubble of [text] at [left],[top] (overlay coordinates); redraws only the old and new bubble areas. */
    fun showPreview(text: String, left: Float, top: Float, w: Float, h: Float) {
        invalidatePreview()
        previewText = text
        pvLeft = left
        pvTop = top
        pvW = w
        pvH = h
        invalidatePreview()
    }

    fun hidePreview() {
        if (previewText == null) return
        invalidatePreview()
        previewText = null
    }

    val previewShown: Boolean get() = previewText != null

    private fun invalidatePreview() {
        if (previewText == null) return
        val pad = (2f * d).toInt() + 1
        invalidate(pvLeft.toInt() - pad, pvTop.toInt() - pad, (pvLeft + pvW).toInt() + pad, (pvTop + pvH).toInt() + pad)
    }

    private fun drawPreview(canvas: Canvas, label: String) {
        val r = 10f * d
        fill.color = edge
        canvas.drawRoundRect(pvLeft - d, pvTop - d, pvLeft + pvW + d, pvTop + pvH + d, r, r, fill)
        fill.color = panel
        canvas.drawRoundRect(pvLeft, pvTop, pvLeft + pvW, pvTop + pvH, r, r, fill)
        text.color = textColor
        text.textSize = PREVIEW_TEXT_DP * d
        val w = text.measureText(label)
        if (w > pvW - 8f * d) text.textSize = PREVIEW_TEXT_DP * d * (pvW - 8f * d) / w
        canvas.drawText(label, pvLeft + pvW / 2f, pvTop + pvH / 2f - (text.descent() + text.ascent()) / 2f, text)
    }

    // Never intercept touches.
    override fun onTouchEvent(event: android.view.MotionEvent) = false

    override fun onDraw(canvas: Canvas) {
        previewText?.let { drawPreview(canvas, it) }
        if (items.isEmpty()) return
        val r = 8f * d
        val rows = (items.size + cols - 1) / cols
        fill.color = edge
        canvas.drawRoundRect(left - d, top - d, left + cw * cols + d, top + chh * rows + d, r, r, fill)
        fill.color = panel
        canvas.drawRoundRect(left, top, left + cw * cols, top + chh * rows, r, r, fill)
        items.forEachIndexed { i, item ->
            val cell = RectF(left + (i % cols) * cw, top + (i / cols) * chh, left + (i % cols + 1) * cw, top + (i / cols + 1) * chh)
            if (i == sel) {
                fill.color = accent
                canvas.drawRoundRect(cell.left + 2 * d, cell.top + 2 * d, cell.right - 2 * d, cell.bottom - 2 * d, r, r, fill)
            }
            text.color = when {
                i == sel -> onAccent
                item.selected -> accentText
                else -> textColor
            }
            text.textSize = 20f * d
            val w = text.measureText(item.label)
            if (w > cell.width() - 6f * d) text.textSize = 20f * d * (cell.width() - 6f * d) / w
            text.isFakeBoldText = item.selected
            canvas.drawText(item.label, cell.centerX(), cell.centerY() - (text.descent() + text.ascent()) / 2, text)
            text.isFakeBoldText = false
        }
    }
}

private const val PREVIEW_TEXT_DP = 30f

/**
 * Stacks the keyboard column (child 0, sized by its content) and the popup overlay (child 1, given
 * exactly the column's size so it never makes the input view taller than the keyboard).
 */
class OverlayStack(context: Context) : android.view.ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val column = getChildAt(0)
        val maxH = MeasureSpec.getSize(heightMeasureSpec)
        column.measure(
            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST),
        )
        val w = column.measuredWidth
        val h = column.measuredHeight
        getChildAt(1).measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) getChildAt(i).layout(0, 0, r - l, b - t)
    }
}
