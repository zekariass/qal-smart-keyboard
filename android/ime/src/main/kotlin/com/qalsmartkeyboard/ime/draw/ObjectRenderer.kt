package com.qalsmartkeyboard.ime.draw

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Paints the objects of a drawing (T8.15: text, lines, arrows, rectangles, ellipses) with reused paints, and lays text out
 * for the canvas, the thumbnails and the export alike. Units are drawing units: the caller scales the canvas.
 *
 * Text is wrapped to its box and shrunk until the widest word fits the box, so a word is never split across two lines (the
 * sticker maker's rule) unless even the smallest size cannot hold it. Any script the system fonts cover is drawn, Ge'ez
 * included. A laid-out text is kept in a small cache so a frame during a drag does not lay every text out again; the cache
 * is cleared by [release] (the panel closed). Not thread-safe: one renderer per thread.
 */
class ObjectRenderer : TextMeasure {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        isLinearText = true
        typeface = Typeface.DEFAULT
    }
    private val rect = RectF()

    private class Key(val text: String, val size: TextSize, val bold: Boolean, val box: Float) {
        override fun equals(other: Any?) = other is Key && other.text == text && other.size == size && other.bold == bold && other.box == box
        override fun hashCode() = ((text.hashCode() * 31 + size.hashCode()) * 31 + bold.hashCode()) * 31 + box.hashCode()
    }

    private class Fitted(val layout: StaticLayout, val widest: Float, val paint: TextPaint)

    private val cache = object : LinkedHashMap<Key, Fitted>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Fitted>?) = size > CACHE_SIZE
    }

    fun drawObjects(canvas: Canvas, list: List<DrawObject>) {
        for (o in list) draw(canvas, o)
    }

    fun draw(canvas: Canvas, o: DrawObject) {
        when (o) {
            is TextObject -> drawText(canvas, o)
            is ShapeObject -> drawShape(canvas, o)
        }
    }

    // ---- text ----------------------------------------------------------------------------------------------------

    private fun fit(t: TextObject): Fitted {
        val key = Key(t.text, t.size, t.bold, t.boxWidth)
        cache[key]?.let { return it }
        val width = t.boxWidth.toInt().coerceAtLeast(1)
        val minPx = max(6f, t.size.units * 0.3f)
        var px = t.size.units
        prepare(px, t.bold)
        // Shrink until no word is cut across two lines; a word breaks only when even the smallest size cannot hold it.
        while (px > minPx && widestWord(t.text) > width) {
            px = max(minPx, px * 0.9f)
            prepare(px, t.bold)
        }
        val own = TextPaint(text) // each laid-out text keeps its own size and weight
        val layout = StaticLayout.Builder.obtain(t.text, 0, t.text.length, own, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()
        var widest = 0f
        for (i in 0 until layout.lineCount) widest = max(widest, layout.getLineWidth(i))
        return Fitted(layout, widest, own).also { cache[key] = it }
    }

    private fun prepare(px: Float, bold: Boolean) {
        text.textSize = px
        text.isFakeBoldText = bold
    }

    private fun widestWord(str: String): Float {
        var widest = 0f
        for (word in str.split(' ', '\n')) if (word.isNotEmpty()) widest = max(widest, text.measureText(word))
        return widest
    }

    private fun drawText(canvas: Canvas, t: TextObject) {
        if (t.text.isEmpty()) return
        val f = fit(t)
        f.paint.color = t.colour // the colour is not part of the layout
        canvas.save()
        canvas.translate(t.x, t.y)
        f.layout.draw(canvas)
        canvas.restore()
    }

    override fun bounds(t: TextObject): Bounds {
        if (t.text.isEmpty()) return Bounds(t.x, t.y, t.x + t.size.units, t.y + t.size.units * 1.25f)
        val f = fit(t)
        return Bounds(t.x, t.y, t.x + max(f.widest, 1f), t.y + f.layout.height)
    }

    // ---- shapes --------------------------------------------------------------------------------------------------

    private fun drawShape(canvas: Canvas, s: ShapeObject) {
        stroke.color = s.colour
        stroke.strokeWidth = s.widthDp
        stroke.style = if (s.filled && (s.kind == ShapeKind.RECTANGLE || s.kind == ShapeKind.ELLIPSE)) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        when (s.kind) {
            ShapeKind.LINE -> canvas.drawLine(s.x1, s.y1, s.x2, s.y2, stroke)
            ShapeKind.ARROW -> {
                canvas.drawLine(s.x1, s.y1, s.x2, s.y2, stroke)
                val a = atan2(s.y2 - s.y1, s.x2 - s.x1)
                val head = s.headLength
                for (side in intArrayOf(-1, 1)) {
                    val ang = a + Math.PI.toFloat() + side * HEAD_ANGLE
                    canvas.drawLine(s.x2, s.y2, s.x2 + head * cos(ang), s.y2 + head * sin(ang), stroke)
                }
            }
            ShapeKind.RECTANGLE -> {
                rect.set(minOf(s.x1, s.x2), minOf(s.y1, s.y2), maxOf(s.x1, s.x2), maxOf(s.y1, s.y2))
                canvas.drawRect(rect, stroke)
            }
            ShapeKind.ELLIPSE -> {
                rect.set(minOf(s.x1, s.x2), minOf(s.y1, s.y2), maxOf(s.x1, s.x2), maxOf(s.y1, s.y2))
                canvas.drawOval(rect, stroke)
            }
        }
    }

    /** Drops the laid-out texts. */
    fun release() = cache.clear()

    companion object {
        private const val CACHE_SIZE = 32

        /** The arrow head's half angle (about 28 degrees). */
        private const val HEAD_ANGLE = 0.49f
    }
}
