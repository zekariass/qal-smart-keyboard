package com.qalsmartkeyboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.LinearLayout

/**
 * The input view's vertical column (headroom, AI panel, the one bar, keys). Its padding holds the
 * navigation-bar and cutout insets ([KeyboardInsets]); it paints the keyboard background ([fill],
 * the key surface's background colour) everywhere below the transparent popup [headroom], so the
 * keyboard looks continuous under a transparent navigation bar and beside a landscape cutout.
 *
 * The headroom is [headroomPx] tall at most and shrinks ([KeyboardInsets.headroomPx]) when the
 * window is too short for it plus the keyboard (landscape phones), so the key rows are never cut off.
 */
class KeyboardColumn(
    context: Context,
    private val headroom: View,
    private val headroomPx: Int,
    private val fill: () -> Int,
) : LinearLayout(context) {
    private val paint = Paint()

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val lp = headroom.layoutParams
        if (lp != null && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            // Measure the keyboard without the headroom, then give the headroom what is left.
            lp.height = 0
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            lp.height = KeyboardInsets.headroomPx(headroomPx, MeasureSpec.getSize(heightMeasureSpec), measuredHeight)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val top = headroom.bottom.toFloat()
        if (top < height) {
            paint.color = fill()
            canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), paint)
        }
        super.dispatchDraw(canvas)
    }
}
