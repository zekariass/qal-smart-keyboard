package com.qalsmartkeyboard.ime.draw

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How a drawing becomes a PNG. */
data class ExportOptions(
    /** The longest side of the image in pixels (1024 by default, which keeps a shared sketch small and sharp). */
    val longestSidePx: Int = DEFAULT_LONGEST_SIDE,
    /** Crop to the ink plus [marginUnits]; false exports the whole canvas. */
    val cropToContent: Boolean = true,
    val marginUnits: Float = 16f,
    /** Paint the chosen background; false exports the ink alone on a transparent image. */
    val withBackground: Boolean = true,
) {
    companion object {
        const val DEFAULT_LONGEST_SIDE = 1024
    }
}

/** The region of the drawing to export and the pixel size it becomes (pure, JVM-tested). */
data class ExportPlan(val src: Bounds, val widthPx: Int, val heightPx: Int, val scale: Float) {
    companion object {
        fun of(d: Drawing, o: ExportOptions, measure: TextMeasure = RoughText): ExportPlan {
            val full = Bounds(0f, 0f, d.width, d.height)
            val content = if (o.cropToContent) d.contentBounds(measure) else null
            val src = if (content == null) {
                full
            } else {
                val m = max(0f, o.marginUnits)
                Bounds(
                    max(0f, content.left - m), max(0f, content.top - m),
                    min(d.width, content.right + m), min(d.height, content.bottom + m),
                ).takeIf { it.width >= 1f && it.height >= 1f } ?: full
            }
            val side = o.longestSidePx.coerceIn(16, MAX_SIDE_PX)
            val scale = side / max(src.width, src.height)
            return ExportPlan(
                src,
                max(1, (src.width * scale).roundToInt()),
                max(1, (src.height * scale).roundToInt()),
                scale,
            )
        }

        const val MAX_SIDE_PX = 4096
    }
}

/**
 * Paints strokes and backgrounds with reused Paint and Path objects (no allocation per stroke or per frame), shared by the
 * canvas view, the thumbnails and the exporter so the screen and the PNG show the same ink. Units are drawing units: the
 * caller scales the canvas, so widths follow.
 *
 * The brushes (T8.15) are all built from one stroke path: the pen is plain; the marker and highlighter are wide and
 * translucent; the pencil is two thin translucent passes, the second broken into grain; dashed and dotted use a dash effect;
 * the calligraphy nib is a thin line repeated along an angled nib, so its width follows the direction of the stroke.
 */
class StrokeRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val grain = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bgPaint = Paint().apply { style = Paint.Style.FILL }
    private val linePaint = Paint().apply { style = Paint.Style.STROKE; color = PATTERN_LINE }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = PATTERN_DOT }
    private val path = Path()
    private val clear = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    private var dotRadius = 1f
    private var nibHalf = 0f
    private var grained = false
    private var calligraphy = false

    /** Sets the paints for a stroke of [tool] (and [brush], for the pen) of nominal [widthUnits]. */
    fun configure(tool: DrawTool, colour: Int, widthUnits: Float, brush: Brush = Brush.PEN) {
        val w = widthUnits * StrokeStyle.widthFactor(tool, brush)
        val b = if (tool == DrawTool.PEN) brush else Brush.PEN
        var c = when (tool) {
            DrawTool.HIGHLIGHTER -> (colour and 0x00FFFFFF) or (HIGHLIGHT_ALPHA shl 24)
            DrawTool.ERASER -> Color.BLACK
            DrawTool.PEN -> when (b) {
                Brush.MARKER -> (colour and 0x00FFFFFF) or (MARKER_ALPHA shl 24)
                Brush.PENCIL -> (colour and 0x00FFFFFF) or (PENCIL_ALPHA shl 24)
                else -> colour
            }
        }
        paint.strokeCap = if (b == Brush.MARKER) Paint.Cap.SQUARE else Paint.Cap.ROUND
        paint.strokeJoin = if (b == Brush.MARKER) Paint.Join.BEVEL else Paint.Join.ROUND
        paint.pathEffect = null
        grained = false
        calligraphy = false
        var strokeW = w
        var dotR = w / 2f
        when (b) {
            Brush.DASHED -> {
                paint.strokeCap = Paint.Cap.BUTT
                paint.pathEffect = DashPathEffect(floatArrayOf(max(3f * w, 6f), max(2f * w, 4f)), 0f)
            }
            Brush.DOTTED -> paint.pathEffect = DashPathEffect(floatArrayOf(0.1f, max(2.2f * w, 4f)), 0f)
            Brush.PENCIL -> {
                grained = true
                grain.color = (colour and 0x00FFFFFF) or (PENCIL_GRAIN_ALPHA shl 24)
                grain.strokeWidth = max(0.8f, w * 0.55f)
                grain.pathEffect = DashPathEffect(floatArrayOf(max(1.2f, w * 1.1f), max(0.8f, w * 0.7f)), 0f)
            }
            Brush.CALLIGRAPHY -> {
                calligraphy = true
                strokeW = max(1f, w * 0.22f)
                nibHalf = w / 2f
                dotR = max(1f, w * 0.3f)
            }
            else -> Unit
        }
        if (tool == DrawTool.ERASER) c = Color.BLACK
        paint.color = c
        paint.strokeWidth = strokeW
        paint.xfermode = if (tool == DrawTool.ERASER) clear else null
        dot.color = c
        dot.xfermode = paint.xfermode
        dotRadius = dotR
    }

    /** The live path of a stroke being drawn (translucent and dashed brushes show it as an overlay until the finger lifts). */
    fun drawPath(canvas: Canvas, p: Path) = paintPath(canvas, p)

    fun drawDot(canvas: Canvas, x: Float, y: Float) {
        if (calligraphy) {
            // A short flat tick along the nib.
            canvas.drawLine(x - nibHalf * NIB_X, y - nibHalf * NIB_Y, x + nibHalf * NIB_X, y + nibHalf * NIB_Y, paint)
        } else {
            canvas.drawCircle(x, y, dotRadius, dot)
        }
    }

    /** One quadratic segment of a live stroke (see [Smoothing]). */
    fun drawQuad(canvas: Canvas, sx: Float, sy: Float, cx: Float, cy: Float, ex: Float, ey: Float) {
        path.rewind()
        path.moveTo(sx, sy)
        path.quadTo(cx, cy, ex, ey)
        paintPath(canvas, path)
    }

    private fun paintPath(canvas: Canvas, p: Path) {
        if (calligraphy) {
            // The nib is a line segment at 45 degrees: the path swept by it is the path repeated along that segment.
            for (k in -2..2) {
                val s = k / 2f
                canvas.save()
                canvas.translate(nibHalf * NIB_X * s, nibHalf * NIB_Y * s)
                canvas.drawPath(p, paint)
                canvas.restore()
            }
            return
        }
        canvas.drawPath(p, paint)
        if (grained) canvas.drawPath(p, grain)
    }

    /** A whole finished stroke. */
    fun drawStroke(canvas: Canvas, s: Stroke) {
        if (s.points.isEmpty()) return
        configure(s.tool, s.colour, s.widthDp, s.brush)
        if (s.points.size == 1) {
            drawDot(canvas, s.points[0].x, s.points[0].y)
            return
        }
        val q = Smoothing.quadPath(s.points)
        path.rewind()
        path.moveTo(q[0], q[1])
        var i = 2
        while (i + 3 < q.size) {
            path.quadTo(q[i], q[i + 1], q[i + 2], q[i + 3])
            i += 4
        }
        paintPath(canvas, path)
    }

    fun drawStrokes(canvas: Canvas, strokes: List<Stroke>) {
        for (s in strokes) drawStroke(canvas, s)
    }

    /** The background colour and its paper pattern over the whole [w] x [h] canvas (a transparent background paints no colour). */
    fun drawBackground(canvas: Canvas, bg: DrawBackground, w: Float, h: Float) {
        if (!bg.isTransparent) {
            bgPaint.color = bg.colour
            canvas.drawRect(0f, 0f, w, h, bgPaint)
        }
        if (bg.pattern == BackgroundPattern.NONE) return
        if (bg.pattern == BackgroundPattern.DOTS) {
            var y = PATTERN_STEP
            while (y < h) {
                var x = PATTERN_STEP
                while (x < w) {
                    canvas.drawCircle(x, y, 1.4f, dotPaint)
                    x += PATTERN_STEP
                }
                y += PATTERN_STEP
            }
            return
        }
        linePaint.strokeWidth = 1f
        var y = PATTERN_STEP
        while (y < h) {
            canvas.drawLine(0f, y, w, y, linePaint)
            y += PATTERN_STEP
        }
        if (bg.pattern == BackgroundPattern.GRID) {
            var x = PATTERN_STEP
            while (x < w) {
                canvas.drawLine(x, 0f, x, h, linePaint)
                x += PATTERN_STEP
            }
        }
    }

    companion object {
        /** Highlighter ink is 40% opaque. */
        const val HIGHLIGHT_ALPHA = 0x66

        /** Marker ink is 70% opaque. */
        const val MARKER_ALPHA = 0xB3
        const val PENCIL_ALPHA = 0xA0
        const val PENCIL_GRAIN_ALPHA = 0xC8
        const val PATTERN_STEP = 32f
        private const val PATTERN_LINE = 0x40808080
        private const val PATTERN_DOT = 0x80808080.toInt()

        /** The calligraphy nib's direction: 45 degrees, rising to the right. */
        private const val NIB_X = 0.7071f
        private const val NIB_Y = -0.7071f
    }
}

/**
 * Renders a [Drawing] to pixels. The ink is painted on its own transparent layer (so the eraser's clear blend only removes
 * ink), then laid over the background, and the objects (text, shapes) go on top: a transparent background, or
 * [ExportOptions.withBackground] false, gives a PNG with a real alpha channel.
 */
class DrawingRenderer(
    private val strokes: StrokeRenderer = StrokeRenderer(),
    private val objects: ObjectRenderer = ObjectRenderer(),
) {
    /** The font-based text boxes of this renderer (for [ExportPlan.of]). */
    val textMeasure: TextMeasure get() = objects

    fun render(d: Drawing, plan: ExportPlan, withBackground: Boolean = true): Bitmap {
        val out = Bitmap.createBitmap(plan.widthPx, plan.heightPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.save()
        c.scale(plan.scale, plan.scale)
        c.translate(-plan.src.left, -plan.src.top)
        if (withBackground) strokes.drawBackground(c, d.background, d.width, d.height)
        c.restore()
        val ink = Bitmap.createBitmap(plan.widthPx, plan.heightPx, Bitmap.Config.ARGB_8888)
        try {
            val ic = Canvas(ink)
            ic.scale(plan.scale, plan.scale)
            ic.translate(-plan.src.left, -plan.src.top)
            strokes.drawStrokes(ic, d.strokes)
            c.drawBitmap(ink, 0f, 0f, null)
        } finally {
            ink.recycle()
        }
        if (d.objects.isNotEmpty()) {
            c.save()
            c.scale(plan.scale, plan.scale)
            c.translate(-plan.src.left, -plan.src.top)
            objects.drawObjects(c, d.objects)
            c.restore()
        }
        return out
    }

    /** A small thumbnail of the whole canvas, [longestSidePx] on its longest side. */
    fun thumbnail(d: Drawing, longestSidePx: Int): Bitmap =
        render(d, ExportPlan.of(d, ExportOptions(longestSidePx, cropToContent = false), objects))

    /** Frees what the renderer caches (laid-out texts). */
    fun release() = objects.release()
}

/** Writes a drawing as a PNG file. An interface so panels and tests can swap it. */
interface DrawingExporter {
    /** Renders [d] and writes it to [dest] (parent directories are created); false when it could not be written. */
    fun exportPng(d: Drawing, options: ExportOptions, dest: File): Boolean
}

/** The system exporter: [DrawingRenderer] and `Bitmap.compress`. Nothing is logged. */
class PngDrawingExporter(private val renderer: DrawingRenderer = DrawingRenderer()) : DrawingExporter {
    override fun exportPng(d: Drawing, options: ExportOptions, dest: File): Boolean {
        val bmp = renderer.render(d, ExportPlan.of(d, options, renderer.textMeasure), options.withBackground)
        return try {
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (dest.exists()) dest.delete()
            tmp.renameTo(dest)
        } catch (e: IOException) {
            false
        } finally {
            bmp.recycle()
            renderer.release()
        }
    }
}
