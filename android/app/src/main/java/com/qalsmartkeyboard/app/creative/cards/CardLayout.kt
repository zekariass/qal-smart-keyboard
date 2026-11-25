package com.qalsmartkeyboard.app.creative.cards

import kotlin.math.max

/**
 * Where the three texts go (pure maths, JVM-tested). A template lays its texts out in [TextSlot]s: a vertical band of the
 * card (fractions of its height) and the size range the text is fitted to (fractions of its width), so one layout serves
 * both export aspects.
 */
data class TextSlot(
    /** The band's top and bottom, as fractions of the card's height. */
    val top: Float,
    val bottom: Float,
    /** The largest and smallest text size, as fractions of the card's width. */
    val maxSize: Float,
    val minSize: Float,
    val bold: Boolean = false,
    val italic: Boolean = false,
    /** Line height as a multiple of the text size. */
    val lineSpacing: Float = 1.15f,
) {
    init {
        require(top in 0f..1f && bottom in 0f..1f && top < bottom) { "band $top..$bottom" }
        require(minSize in 0.005f..maxSize) { "size $minSize..$maxSize" }
    }
}

/** What a template draws besides text, in the template's accent colour (all generated, no images). */
enum class CardDecor {
    NONE, QUOTE_MARKS, CONFETTI, STARS, SUN, RIBBON, DOTS_ARC, HEARTS
}

data class TemplateLayout(
    val headline: TextSlot,
    val body: TextSlot,
    val from: TextSlot,
    /** The side margin, as a fraction of the card's width. */
    val marginX: Float = 0.1f,
    val decor: CardDecor = CardDecor.NONE,
)

/** A box on the card in pixels. */
data class Box(val left: Int, val top: Int, val width: Int, val height: Int) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
}

/** The size of what a measurer says a text takes. */
fun interface TextMeasurer {
    /** The height in pixels of [text] wrapped to [widthPx] at [sizePx]. */
    fun heightFor(text: String, sizePx: Float, widthPx: Int): Int
}

/** The result of fitting a text to a box. [fits] false: even [size] (the smallest allowed) overflows, so the text is cut. */
data class FitResult(val size: Float, val fits: Boolean)

object CardLayoutMath {
    /** The three boxes of [layout] on a [w] x [h] card, inside [frameInsetFraction] of the width kept free for a frame. */
    fun boxes(layout: TemplateLayout, w: Int, h: Int, frame: CardFrame): Triple<Box, Box, Box> {
        val inset = frameInset(frame, w)
        val side = (layout.marginX * w).toInt() + inset
        val width = max(1, w - 2 * side)
        fun box(s: TextSlot): Box {
            val top = (s.top * h).toInt()
            val bottom = (s.bottom * h).toInt()
            return Box(side, top, width, max(1, bottom - top))
        }
        return Triple(box(layout.headline), box(layout.body), box(layout.from))
    }

    /** How far a frame pushes the text in from the card's edge (pixels): the border and some air. */
    fun frameInset(frame: CardFrame, w: Int): Int = when (frame) {
        CardFrame.NONE -> 0
        CardFrame.THIN -> (w * 0.035f).toInt()
        else -> (w * 0.06f).toInt()
    }

    /**
     * The largest size in [minPx, maxPx] at which [text] fits [boxW] x [boxH] (binary search over whole pixels, since the
     * height never shrinks as the size grows). When even [minPx] overflows, that size comes back with `fits = false`.
     * A blank text fits at [maxPx].
     */
    fun fit(measure: TextMeasurer, text: String, boxW: Int, boxH: Int, maxPx: Float, minPx: Float): FitResult {
        val hi = max(maxPx, minPx)
        if (text.isBlank()) return FitResult(hi, true)
        if (measure.heightFor(text, hi, boxW) <= boxH) return FitResult(hi, true)
        if (measure.heightFor(text, minPx, boxW) > boxH) return FitResult(minPx, false)
        var lo = minPx.toInt()          // known to fit
        var up = hi.toInt() + 1         // known not to fit
        while (up - lo > 1) {
            val mid = (lo + up) / 2
            if (measure.heightFor(text, mid.toFloat(), boxW) <= boxH) lo = mid else up = mid
        }
        return FitResult(max(lo.toFloat(), minPx), true)
    }

    /**
     * The two ends of a linear gradient across a [w] x [h] card at [angleDeg] (0: left to right, 90: top to bottom, 135: top
     * left to bottom right): a line through the centre long enough that the first and last colour reach the corners.
     */
    fun gradientLine(w: Int, h: Int, angleDeg: Int): FloatArray {
        val a = Math.toRadians(angleDeg.toDouble())
        val dx = kotlin.math.cos(a)
        val dy = kotlin.math.sin(a)
        val half = (kotlin.math.abs(w * dx) + kotlin.math.abs(h * dy)) / 2
        val cx = w / 2.0
        val cy = h / 2.0
        return floatArrayOf((cx - dx * half).toFloat(), (cy - dy * half).toFloat(), (cx + dx * half).toFloat(), (cy + dy * half).toFloat())
    }

    /** The scale and offset that make a [bw] x [bh] picture cover a [w] x [h] card (centre crop): scale, dx, dy. */
    fun coverTransform(bw: Int, bh: Int, w: Int, h: Int): FloatArray {
        val s = max(w.toFloat() / bw, h.toFloat() / bh)
        return floatArrayOf(s, (w - bw * s) / 2f, (h - bh * s) / 2f)
    }
}
