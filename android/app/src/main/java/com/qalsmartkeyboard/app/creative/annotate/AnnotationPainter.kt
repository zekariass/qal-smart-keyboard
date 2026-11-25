package com.qalsmartkeyboard.app.creative.annotate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import com.qalsmartkeyboard.app.creative.cards.CardImages
import com.qalsmartkeyboard.app.creative.cards.ExportFormat
import java.security.SecureRandom
import java.util.Random
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the vector annotations (everything except redactions, which change pixels: [RedactionPass]) on a platform
 * Canvas in image pixels (T8.7). The editor's preview and the export both draw with this, so what the user sees is
 * what is exported. Text goes through StaticLayout, so Ge'ez (Fidel) and Latin labels render with the system's fonts.
 */
class AnnotationPainter : LabelMeasurer {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val layouts = object : LinkedHashMap<Pair<String, Float>, StaticLayout>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Float>, StaticLayout>?) = size > 64
    }

    /** Draws [items] (redactions skipped) and then [live], the object being drawn, over a [width] x [height] picture. */
    fun draw(c: Canvas, items: List<Annotation>, width: Int, height: Int, live: Annotation? = null) {
        val all = if (live == null) items else items + live
        // Spotlights first, as one layer: the dimming covers the picture, the marks drawn after it stay bright.
        val spots = all.filterIsInstance<Annotation.Spotlight>()
        if (spots.isNotEmpty()) drawSpotlights(c, spots, width, height)
        for (a in all) drawOne(c, a)
    }

    private fun drawOne(c: Canvas, a: Annotation) {
        when (a) {
            is Annotation.Ink -> drawInk(c, a)
            is Annotation.Arrow -> drawArrow(c, a)
            is Annotation.Shape -> {
                stroke.color = a.color
                stroke.strokeWidth = a.width
                val b = a.box.normalized()
                if (a.kind == ShapeKind.RECT) c.drawRect(b.left, b.top, b.right, b.bottom, stroke)
                else c.drawOval(RectF(b.left, b.top, b.right, b.bottom), stroke)
            }
            is Annotation.Label -> drawLabel(c, a)
            is Annotation.Step -> drawStep(c, a)
            is Annotation.Spotlight, is Annotation.Redaction -> Unit
        }
    }

    private fun drawInk(c: Canvas, a: Annotation.Ink) {
        stroke.color = a.color
        stroke.strokeWidth = a.width
        if (a.highlighter) stroke.alpha = (Annotation.HIGHLIGHT_ALPHA * 255).toInt()
        val pts = a.points
        if (pts.size == 1) {
            fill.color = stroke.color
            c.drawCircle(pts[0].x, pts[0].y, a.width / 2, fill)
            return
        }
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size - 1) {
                val mx = (pts[i].x + pts[i + 1].x) / 2
                val my = (pts[i].y + pts[i + 1].y) / 2
                quadTo(pts[i].x, pts[i].y, mx, my)
            }
            lineTo(pts.last().x, pts.last().y)
        }
        c.drawPath(path, stroke) // one path: a translucent highlighter never darkens where it crosses itself
    }

    private fun drawArrow(c: Canvas, a: Annotation.Arrow) {
        val dx = a.to.x - a.from.x
        val dy = a.to.y - a.from.y
        val len = hypot(dx, dy)
        if (len < 0.5f) return
        val ux = dx / len
        val uy = dy / len
        val head = min(max(a.width * Geometry.ARROW_HEAD, 10f), len * 0.6f)
        val half = head * 0.6f
        stroke.color = a.color
        stroke.strokeWidth = a.width
        // The shaft stops inside the head, so its round cap never pokes past the tip.
        c.drawLine(a.from.x, a.from.y, a.to.x - ux * head * 0.8f, a.to.y - uy * head * 0.8f, stroke)
        fill.color = a.color
        val bx = a.to.x - ux * head
        val by = a.to.y - uy * head
        c.drawPath(
            Path().apply {
                moveTo(a.to.x, a.to.y)
                lineTo(bx - uy * half, by + ux * half)
                lineTo(bx + uy * half, by - ux * half)
                close()
            },
            fill,
        )
    }

    private fun layout(text: String, size: Float): StaticLayout = layouts.getOrPut(text to size) {
        this.text.textSize = size
        this.text.typeface = Typeface.DEFAULT_BOLD
        val widest = text.split('\n').maxOf { StaticLayout.getDesiredWidth(it, this.text) }
        val w = max(1, ceil(min(widest, size * MAX_EMS)).toInt())
        StaticLayout.Builder.obtain(text, 0, text.length, TextPaint(this.text), w)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setIncludePad(false)
            .build()
    }

    override fun measure(label: Annotation.Label): Box {
        val l = layout(label.text, label.size)
        val pad = label.size * LabelMeasurer.LABEL_PAD
        var w = 0f
        for (i in 0 until l.lineCount) w = max(w, l.getLineWidth(i))
        return Box(label.at.x, label.at.y, label.at.x + w + 2 * pad, label.at.y + l.height + 2 * pad)
    }

    private fun drawLabel(c: Canvas, a: Annotation.Label) {
        val l = layout(a.text, a.size)
        val pad = a.size * LabelMeasurer.LABEL_PAD
        val plate = contrast(a.color)
        if (a.backdrop) {
            val b = measure(a)
            fill.color = plate
            fill.alpha = PLATE_ALPHA
            c.drawRoundRect(RectF(b.left, b.top, b.right, b.bottom), pad, pad, fill)
        }
        c.save()
        c.translate(a.at.x + pad, a.at.y + pad)
        val p = l.paint
        if (!a.backdrop) {
            // An outline in the contrasting colour keeps the text readable on any picture.
            p.style = Paint.Style.STROKE
            p.strokeWidth = a.size * 0.14f
            p.strokeJoin = Paint.Join.ROUND
            p.color = plate
            l.draw(c)
        }
        p.style = Paint.Style.FILL
        p.color = a.color
        l.draw(c)
        c.restore()
    }

    private fun drawStep(c: Canvas, a: Annotation.Step) {
        fill.color = Color.WHITE
        c.drawCircle(a.at.x, a.at.y, a.radius, fill)
        fill.color = a.color or OPAQUE
        c.drawCircle(a.at.x, a.at.y, a.radius * 0.88f, fill)
        val label = a.number.toString()
        text.textSize = a.radius * (if (label.length > 2) 0.8f else 1.05f)
        text.typeface = Typeface.DEFAULT_BOLD
        text.color = contrast(a.color)
        text.textAlign = Paint.Align.CENTER
        val bounds = Rect()
        text.getTextBounds(label, 0, label.length, bounds)
        c.drawText(label, a.at.x, a.at.y - bounds.exactCenterY(), text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawSpotlights(c: Canvas, spots: List<Annotation.Spotlight>, width: Int, height: Int) {
        val holes = Path()
        for (s in spots) {
            val b = s.box.normalized()
            holes.op(Path().apply { addRect(b.left, b.top, b.right, b.bottom, Path.Direction.CW) }, Path.Op.UNION)
        }
        val dim = Path().apply { addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW) }
        dim.op(holes, Path.Op.DIFFERENCE)
        fill.color = SPOTLIGHT_DIM
        c.drawPath(dim, fill)
    }

    companion object {
        private const val OPAQUE = 0xFF000000.toInt()
        private const val PLATE_ALPHA = 217 // 85%
        private const val MAX_EMS = 22f
        const val SPOTLIGHT_DIM = 0x99000000.toInt() // 60% black

        /** Black or white, whichever reads better against [color]. */
        fun contrast(color: Int): Int {
            val lum = 0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)
            return if (lum > 150) Color.BLACK else Color.WHITE
        }
    }
}

/** Burns redactions into the pixels of a mutable bitmap, one window at a time (T8.7). Export and preview share it. */
object RedactionPass {
    /** Applies [redactions] in order to [bitmap] (mutable, ARGB_8888). Memory: one window of pixels at a time. */
    fun apply(bitmap: Bitmap, redactions: List<Annotation.Redaction>, rng: Random) {
        require(bitmap.isMutable) { "the bitmap must be mutable" }
        val w = bitmap.width
        val h = bitmap.height
        for (r in redactions) {
            val win = Redactor.window(r, w, h)
            if (win.isEmpty) continue
            val px = IntArray(win.width * win.height)
            bitmap.getPixels(px, 0, win.width, win.left, win.top, win.width, win.height)
            Redactor.redact(PixelWindow(px, win.left, win.top, win.width, win.height), w, h, r, rng)
            bitmap.setPixels(px, 0, win.width, win.left, win.top, win.width, win.height)
        }
    }
}

/**
 * Flattens an annotation into a new picture (T8.7), in this order and no other:
 * 1. a private copy of the working bitmap's pixels (the imported picture itself is never changed or written);
 * 2. every redaction burnt into those **pixels** ([Redactor], the same function the tests prove irreversible);
 * 3. the crop: a new bitmap that receives only the kept pixels (everything outside the crop is gone);
 * 4. the vector annotations drawn over it ([AnnotationPainter]);
 * then a fresh PNG or JPEG encode (no metadata). Never a layered or object format.
 */
class AnnotationExporter(private val random: () -> Random = { Random(SecureRandom().nextLong()) }) {
    /** The flattened picture: a new bitmap of [AnnotDoc.outputRect]'s size. */
    fun render(base: Bitmap, doc: AnnotDoc): Bitmap {
        require(base.width == doc.width && base.height == doc.height) { "the picture is ${base.width} x ${base.height}, the doc ${doc.width} x ${doc.height}" }
        val work = base.copy(Bitmap.Config.ARGB_8888, true)
        try {
            RedactionPass.apply(work, doc.redactions, random())
            val o = doc.outputRect
            val out = Bitmap.createBitmap(o.width, o.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawBitmap(work, Rect(o.left, o.top, o.right, o.bottom), Rect(0, 0, o.width, o.height), null)
            c.translate(-o.left.toFloat(), -o.top.toFloat())
            AnnotationPainter().draw(c, doc.items, doc.width, doc.height) // its own painter: exports run off the main thread
            return out
        } finally {
            work.recycle()
        }
    }

    /** The flattened picture's file bytes in [format]. */
    fun export(base: Bitmap, doc: AnnotDoc, format: ExportFormat): ByteArray {
        val out = render(base, doc)
        try {
            return CardImages.encode(out, format)
        } finally {
            out.recycle()
        }
    }

    /**
     * The editor's preview ground: [preview] (a smaller copy of the working bitmap) with the redactions applied by the
     * same [Redactor], scaled to its size. A new bitmap.
     */
    fun redactedPreview(preview: Bitmap, doc: AnnotDoc): Bitmap {
        val out = preview.copy(Bitmap.Config.ARGB_8888, true)
        val s = preview.width.toFloat() / doc.width
        RedactionPass.apply(out, doc.redactions.map { Geometry.scaled(it, s) as Annotation.Redaction }, Random(PREVIEW_SEED))
        return out
    }

    private companion object {
        const val PREVIEW_SEED = 7L
    }
}
