package com.qalsmartkeyboard.ime.draw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The drawing surface of Draw & Sketch (T8.4). Strokes are painted incrementally into an offscreen ink [Bitmap]: each
 * touch sample draws only its newest quadratic segment and invalidates only that segment's box, so the cost per sample is
 * one small `drawPath` (budget: stroke to render <= 16 ms, section 17.1). Nothing is allocated per touch sample: the
 * paints, paths and the growing point buffers are reused. The highlighter (translucent) is shown as one live path over the
 * ink and committed when the finger lifts, so overlapping samples never darken it.
 *
 * The ink bitmap is transparent; the background is painted under it every frame (the eraser clears ink only, see
 * [DrawTool.ERASER]). The bitmap is rebuilt from the strokes only when the session changed outside this view (undo, redo,
 * clear, a loaded drawing, a resize), never while drawing.
 *
 * Input: one pointer only (the first finger or pen; a palm or second finger landing later is ignored), MotionEvent history
 * is consumed so fast strokes keep every sample, and pressure is recorded in the stroke (not used for the width in phase 1).
 * The surface is drawn in drawing units fitted (letterboxed) to the view, so a drawing keeps its shape when the keyboard
 * changes size.
 */
class DrawCanvasView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val renderer = StrokeRenderer()

    var session: DrawSession = DrawSession(360f, 240f)
        set(value) {
            if (field === value) return
            field = value
            if (width > 0 && height > 0) value.resizeIfEmpty(width / d, height / d)
            inkRevision = -1
            invalidate()
        }

    var tool: DrawTool = DrawTool.PEN
    var colour: Int = InkDefaults.BLACK
    var widthUnits: Float = InkDefaults.WIDTHS[InkDefaults.DEFAULT_WIDTH_INDEX]

    /** Called after a finished stroke was added to the session. */
    var onStrokeAdded: () -> Unit = {}

    /** The palette tint of the checkerboard drawn under a transparent background. */
    var checkerLight: Int = 0xFFFFFFFF.toInt()
    var checkerDark: Int = 0xFFD0D0D0.toInt()

    private var ink: Bitmap? = null
    private var inkCanvas: Canvas? = null
    private var inkRevision = -1

    // Fit of the drawing's units into the view's pixels.
    private var scale = 1f
    private var offX = 0f
    private var offY = 0f

    // The stroke in progress: points in drawing units, in buffers that grow but are never reallocated per sample.
    private var xs = FloatArray(256)
    private var ys = FloatArray(256)
    private var ps = FloatArray(256)
    private var n = 0
    private var activePointer = -1
    private var drawingTool = DrawTool.PEN
    private var drawingColour = 0
    private var drawingWidth = 0f
    private var startX = 0f
    private var startY = 0f
    private val livePath = Path()
    private val dirty = android.graphics.Rect()
    private val checker = Paint()
    private var checkerShaderTile: Bitmap? = null

    /** The number of samples in the stroke in progress (tests). */
    val liveSamples: Int get() = n

    init {
        contentDescription = null
        isFocusable = false
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        session.resizeIfEmpty(w / d, h / d)
        recomputeFit()
        ink?.recycle()
        ink = null
        inkCanvas = null
        inkRevision = -1
    }

    private fun recomputeFit() {
        val dw = session.width
        val dh = session.height
        scale = min(width / dw, height / dh)
        offX = (width - dw * scale) / 2f
        offY = (height - dh * scale) / 2f
    }

    private fun ensureInk() {
        if (width <= 0 || height <= 0) return
        recomputeFit()
        val pw = max(1, (session.width * scale).roundToInt())
        val ph = max(1, (session.height * scale).roundToInt())
        var b = ink
        if (b == null || b.width != pw || b.height != ph) {
            b?.recycle()
            b = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
            ink = b
            inkCanvas = Canvas(b).also { it.scale(scale, scale) }
            inkRevision = -1
        }
        if (inkRevision != session.revision) {
            b.eraseColor(0)
            inkCanvas?.let { renderer.drawStrokes(it, session.strokes) }
            inkRevision = session.revision
        }
    }

    override fun onDraw(canvas: Canvas) {
        ensureInk()
        val b = ink ?: return
        canvas.save()
        canvas.translate(offX, offY)
        val bg = session.background
        if (bg.isTransparent) drawChecker(canvas, b.width.toFloat(), b.height.toFloat())
        canvas.save()
        canvas.scale(scale, scale)
        renderer.drawBackground(canvas, bg, session.width, session.height)
        canvas.restore()
        canvas.drawBitmap(b, 0f, 0f, null)
        if (n > 0 && drawingTool == DrawTool.HIGHLIGHTER) {
            canvas.scale(scale, scale)
            renderer.configure(drawingTool, drawingColour, drawingWidth)
            if (n == 1) renderer.drawDot(canvas, xs[0], ys[0]) else renderer.drawPath(canvas, livePath)
        }
        canvas.restore()
    }

    private fun drawChecker(canvas: Canvas, w: Float, h: Float) {
        var tile = checkerShaderTile
        if (tile == null) {
            val t = max(2, (CHECKER_DP * d).roundToInt())
            tile = Bitmap.createBitmap(t * 2, t * 2, Bitmap.Config.ARGB_8888)
            val c = Canvas(tile)
            val p = Paint()
            p.color = checkerLight
            c.drawRect(0f, 0f, t * 2f, t * 2f, p)
            p.color = checkerDark
            c.drawRect(0f, 0f, t.toFloat(), t.toFloat(), p)
            c.drawRect(t.toFloat(), t.toFloat(), t * 2f, t * 2f, p)
            checkerShaderTile = tile
            checker.shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
        canvas.drawRect(0f, 0f, w, h, checker)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }

    /** Frees the bitmaps (the panel closed or was removed); the session is untouched and redrawn on the next show. */
    fun release() {
        ink?.recycle()
        ink = null
        inkCanvas = null
        inkRevision = -1
        checkerShaderTile?.recycle()
        checkerShaderTile = null
        checker.shader = null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                begin(e)
            }
            MotionEvent.ACTION_MOVE -> if (activePointer >= 0) {
                val i = e.findPointerIndex(activePointer)
                if (i >= 0) {
                    val hist = e.historySize
                    for (h in 0 until hist) addSample(e.getHistoricalX(i, h), e.getHistoricalY(i, h), e.getHistoricalPressure(i, h))
                    addSample(e.getX(i), e.getY(i), e.getPressure(i))
                }
            }
            MotionEvent.ACTION_UP -> {
                if (activePointer >= 0) {
                    val i = e.findPointerIndex(activePointer)
                    if (i >= 0) addSample(e.getX(i), e.getY(i), e.getPressure(i))
                    finish()
                }
            }
            MotionEvent.ACTION_CANCEL -> cancel()
            // ACTION_POINTER_DOWN / UP: a second finger or a palm is ignored; the stroke follows the first pointer.
        }
        return true
    }

    private fun begin(e: MotionEvent) {
        activePointer = e.getPointerId(0)
        n = 0
        drawingTool = tool
        drawingColour = colour
        drawingWidth = widthUnits
        ensureInk()
        addSample(e.getX(0), e.getY(0), e.getPressure(0))
    }

    /** Adds one sample (view pixels) to the stroke: converted to drawing units, drawn as the newest segment. */
    private fun addSample(px: Float, py: Float, pressure: Float) {
        val x = (px - offX) / scale
        val y = (py - offY) / scale
        if (n > 0) {
            val dx = x - xs[n - 1]
            val dy = y - ys[n - 1]
            if (dx * dx + dy * dy < MIN_STEP_UNITS * MIN_STEP_UNITS) return // a jitter, not a move
        }
        if (n == xs.size) {
            xs = xs.copyOf(n * 2)
            ys = ys.copyOf(n * 2)
            ps = ps.copyOf(n * 2)
        }
        xs[n] = x
        ys[n] = y
        ps[n] = pressure.coerceIn(0f, 1f)
        n++
        val c = inkCanvas ?: return
        val live = drawingTool != DrawTool.HIGHLIGHTER
        val pad = drawingWidth * widthFactor() / 2f + 2f
        if (n == 1) {
            startX = x
            startY = y
            livePath.rewind()
            livePath.moveTo(x, y)
            if (live) {
                renderer.configure(drawingTool, drawingColour, drawingWidth)
                renderer.drawDot(c, x, y)
            }
            invalidateUnits(x - pad, y - pad, x + pad, y + pad)
            return
        }
        if (n == 2) {
            // The curve starts at the first point; the first control point is the second point, once there is a third.
            livePath.lineTo(x, y)
            invalidateUnits(minOf(xs[0], x) - pad, minOf(ys[0], y) - pad, maxOf(xs[0], x) + pad, maxOf(ys[0], y) + pad)
            return
        }
        // Third sample on: the quad from the last start, controlled by the previous sample, to the midpoint with this one.
        val cx = xs[n - 2]
        val cy = ys[n - 2]
        val ex = (cx + x) / 2f
        val ey = (cy + y) / 2f
        if (n == 3) {
            livePath.rewind()
            livePath.moveTo(startX, startY)
        }
        livePath.quadTo(cx, cy, ex, ey)
        if (live) {
            renderer.configure(drawingTool, drawingColour, drawingWidth)
            renderer.drawQuad(c, startX, startY, cx, cy, ex, ey)
        }
        invalidateUnits(
            minOf(startX, cx, ex) - pad, minOf(startY, cy, ey) - pad,
            maxOf(startX, cx, ex) + pad, maxOf(startY, cy, ey) + pad,
        )
        startX = ex
        startY = ey
    }

    private fun widthFactor() = when (drawingTool) {
        DrawTool.PEN -> 1f
        DrawTool.HIGHLIGHTER -> Drawing.HIGHLIGHTER_FACTOR
        DrawTool.ERASER -> Drawing.ERASER_FACTOR
    }

    private fun invalidateUnits(l: Float, t: Float, r: Float, b: Float) {
        dirty.set(
            (l * scale + offX).toInt() - 1, (t * scale + offY).toInt() - 1,
            (r * scale + offX).toInt() + 2, (b * scale + offY).toInt() + 2,
        )
        invalidate(dirty)
    }

    private fun finish() {
        val count = n
        val c = inkCanvas
        if (count >= 2 && c != null) {
            // The last segment ends exactly on the last sample, as the replay of the stroke does.
            val lx = xs[count - 1]
            val ly = ys[count - 1]
            if (drawingTool != DrawTool.HIGHLIGHTER) {
                renderer.configure(drawingTool, drawingColour, drawingWidth)
                renderer.drawQuad(c, startX, startY, lx, ly, lx, ly)
            }
        }
        if (count >= 1) {
            val pts = ArrayList<Pt>(count)
            for (k in 0 until count) pts += Pt(xs[k], ys[k], ps[k])
            val stroke = Stroke(pts, drawingColour, drawingWidth, drawingTool)
            if (drawingTool == DrawTool.HIGHLIGHTER && c != null) renderer.drawStroke(c, stroke)
            session.addStroke(stroke)
            inkRevision = session.revision // the ink already shows this stroke
        }
        n = 0
        activePointer = -1
        invalidate()
        if (count >= 1) onStrokeAdded()
    }

    private fun cancel() {
        if (activePointer < 0) return
        n = 0
        activePointer = -1
        inkRevision = -1 // the half-drawn stroke is wiped by a rebuild from the strokes
        invalidate()
    }

    companion object {
        private const val MIN_STEP_UNITS = 0.6f
        private const val CHECKER_DP = 8f
    }
}
