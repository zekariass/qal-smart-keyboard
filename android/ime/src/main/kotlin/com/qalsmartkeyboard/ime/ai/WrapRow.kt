package com.qalsmartkeyboard.ime.ai

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * A row of buttons that wraps onto further lines instead of running off the edge (ADR 0044: QA found the quick actions'
 * "Cancel" cut off at 360 dp). Children keep their own size and margins; one wider than the row is measured to the row's
 * width (a single-line chip then ellipsizes). Lines start at the leading edge, so right-to-left UIs mirror. Plain View
 * code, no allocation per layout beyond the measure pass.
 */
class WrapRow(context: Context) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val avail = if (mode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var lineHeight = 0
        var height = 0
        var widest = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            val lp = c.layoutParams as MarginLayoutParams
            val hMargins = lp.leftMargin + lp.rightMargin
            val childSpec = if (avail == Int.MAX_VALUE) MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            else MeasureSpec.makeMeasureSpec((avail - hMargins).coerceAtLeast(0), MeasureSpec.AT_MOST)
            c.measure(childSpec, getChildMeasureSpec(heightMeasureSpec, 0, lp.height))
            val cw = c.measuredWidth + hMargins
            val ch = c.measuredHeight + lp.topMargin + lp.bottomMargin
            if (x > 0 && x + cw > avail) {
                height += lineHeight
                x = 0
                lineHeight = 0
            }
            x += cw
            widest = maxOf(widest, x)
            lineHeight = maxOf(lineHeight, ch)
        }
        height += lineHeight
        val w = if (mode == MeasureSpec.EXACTLY) MeasureSpec.getSize(widthMeasureSpec) else minOf(widest, avail) + paddingLeft + paddingRight
        setMeasuredDimension(w, resolveSize(height + paddingTop + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val avail = r - l - paddingLeft - paddingRight
        var x = 0
        var y = paddingTop
        var lineHeight = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            val lp = c.layoutParams as MarginLayoutParams
            val cw = c.measuredWidth + lp.leftMargin + lp.rightMargin
            val ch = c.measuredHeight + lp.topMargin + lp.bottomMargin
            if (x > 0 && x + cw > avail) {
                y += lineHeight
                x = 0
                lineHeight = 0
            }
            val left = if (rtl) r - l - paddingRight - x - cw + lp.leftMargin else paddingLeft + x + lp.leftMargin
            c.layout(left, y + lp.topMargin, left + c.measuredWidth, y + lp.topMargin + c.measuredHeight)
            x += cw
            lineHeight = maxOf(lineHeight, ch)
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams = MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: android.util.AttributeSet?): LayoutParams = MarginLayoutParams(context, attrs)

    override fun generateLayoutParams(p: LayoutParams?): LayoutParams = when (p) {
        is MarginLayoutParams -> MarginLayoutParams(p)
        null -> generateDefaultLayoutParams()
        else -> MarginLayoutParams(p)
    }

    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is MarginLayoutParams

    /** The children that lie completely inside the row (tests: nothing is cut off). */
    fun fullyVisible(): List<View> = (0 until childCount).map { getChildAt(it) }.filter {
        it.visibility != GONE && it.left >= 0 && it.right <= width && it.top >= 0 && it.bottom <= height
    }
}
