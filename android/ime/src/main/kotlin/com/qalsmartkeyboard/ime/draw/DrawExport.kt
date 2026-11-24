package com.qalsmartkeyboard.ime.draw

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
        fun of(d: Drawing, o: ExportOptions): ExportPlan {
            val full = Bounds(0f, 0f, d.width, d.height)
            val content = if (o.cropToContent) d.contentBounds() else null
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
 * canvas view and the exporter so the screen and the PNG show the same ink. Units are drawing units: the caller scales the
 * canvas, so widths follow.
 */
class StrokeRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bgPaint = Paint().apply { style = Paint.Style.FILL }
    private val linePaint = Paint().apply { style = Paint.Style.STROKE; color = PATTERN_LINE }
    private val path = Path()
    private val clear = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    private var dotRadius = 1f

    /** Sets the paints for a tool: the highlighter is translucent and wide, the eraser a wide clearing stroke. */
    fun configure(tool: DrawTool, colour: Int, widthUnits: Float) {
        val w = when (tool) {
            DrawTool.PEN -> widthUnits
            DrawTool.HIGHLIGHTER -> widthUnits * Drawing.HIGHLIGHTER_FACTOR
            DrawTool.ERASER -> widthUnits * Drawing.ERASER_FACTOR
        }
        val c = when (tool) {
            DrawTool.HIGHLIGHTER -> (colour and 0x00FFFFFF) or (HIGHLIGHT_ALPHA shl 24)
            DrawTool.ERASER -> Color.BLACK
            DrawTool.PEN -> colour
        }
        paint.color = c
        paint.strokeWidth = w
        paint.xfermode = if (tool == DrawTool.ERASER) clear else null
        dot.color = c
        dot.xfermode = paint.xfermode
        dotRadius = w / 2f
    }

    /** The live path of a stroke being drawn (the highlighter shows it as an overlay until the finger lifts). */
    fun drawPath(canvas: Canvas, p: Path) = canvas.drawPath(p, paint)

    fun drawDot(canvas: Canvas, x: Float, y: Float) = canvas.drawCircle(x, y, dotRadius, dot)

    /** One quadratic segment of a live stroke (see [Smoothing]). */
    fun drawQuad(canvas: Canvas, sx: Float, sy: Float, cx: Float, cy: Float, ex: Float, ey: Float) {
        path.rewind()
        path.moveTo(sx, sy)
        path.quadTo(cx, cy, ex, ey)
        canvas.drawPath(path, paint)
    }

    /** A whole finished stroke. */
    fun drawStroke(canvas: Canvas, s: Stroke) {
        if (s.points.isEmpty()) return
        configure(s.tool, s.colour, s.widthDp)
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
        canvas.drawPath(path, paint)
    }

    fun drawStrokes(canvas: Canvas, strokes: List<Stroke>) {
        for (s in strokes) drawStroke(canvas, s)
    }

    /** The background colour and line pattern over the whole [w] x [h] canvas (a transparent background paints nothing). */
    fun drawBackground(canvas: Canvas, bg: DrawBackground, w: Float, h: Float) {
        if (!bg.isTransparent) {
            bgPaint.color = bg.colour
            canvas.drawRect(0f, 0f, w, h, bgPaint)
        }
        if (bg.pattern == BackgroundPattern.NONE) return
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
        const val PATTERN_STEP = 32f
        private const val PATTERN_LINE = 0x40808080
    }
}

/**
 * Renders a [Drawing] to pixels. The ink is painted on its own transparent layer (so the eraser's clear blend only removes
 * ink), then laid over the background: a transparent background, or [ExportOptions.withBackground] false, gives a PNG with
 * a real alpha channel.
 */
class DrawingRenderer(private val strokes: StrokeRenderer = StrokeRenderer()) {
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
        return out
    }

    /** A small thumbnail of the whole canvas, [longestSidePx] on its longest side. */
    fun thumbnail(d: Drawing, longestSidePx: Int): Bitmap =
        render(d, ExportPlan.of(d, ExportOptions(longestSidePx, cropToContent = false)))
}

/** Writes a drawing as a PNG file. An interface so panels and tests can swap it. */
interface DrawingExporter {
    /** Renders [d] and writes it to [dest] (parent directories are created); false when it could not be written. */
    fun exportPng(d: Drawing, options: ExportOptions, dest: File): Boolean
}

/** The system exporter: [DrawingRenderer] and `Bitmap.compress`. Nothing is logged. */
class PngDrawingExporter(private val renderer: DrawingRenderer = DrawingRenderer()) : DrawingExporter {
    override fun exportPng(d: Drawing, options: ExportOptions, dest: File): Boolean {
        val bmp = renderer.render(d, ExportPlan.of(d, options), options.withBackground)
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
        }
    }
}
