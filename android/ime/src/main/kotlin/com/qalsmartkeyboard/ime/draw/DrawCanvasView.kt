package com.qalsmartkeyboard.ime.draw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The drawing surface of Draw & Sketch (T8.4, T8.15). Strokes are painted incrementally into an offscreen ink [Bitmap]: each
 * touch sample draws only its newest quadratic segment and invalidates only that segment's box, so the cost per sample is
 * one small `drawPath` (budget: stroke to render <= 16 ms, section 17.1). Nothing is allocated per touch sample: the
 * paints, paths and the growing point buffers are reused. Brushes that are translucent or dashed (the highlighter, marker,
 * pencil, dashed and dotted; see [StrokeStyle.overlay]) are shown as one live path over the ink and drawn into it when the
 * finger lifts, so overlapping samples never darken them and a dash pattern never restarts.
 *
 * The ink bitmap is transparent; the background is painted under it every frame (the eraser clears ink only, see
 * [DrawTool.ERASER]) and the objects (text, shapes) over it, so they are never part of the ink bitmap: moving a text repaints
 * no ink. The bitmap is rebuilt from the strokes only when they changed outside this view (undo, redo, clear, a loaded
 * drawing, a resize), never while drawing.
 *
 * What a touch does depends on [input]: ink (one pointer only: the first finger or pen; a palm or second finger landing later
 * is ignored; MotionEvent history is consumed so fast strokes keep every sample), a shape dragged out ([onShapeDrawn]), a tap
 * for a text ([onTextTap]), or select / move ([onObjectTapped], [onObjectMoved]). The surface is drawn in drawing units fitted
 * (letterboxed) to the view, so a drawing keeps its shape when the keyboard changes size.
 */
class DrawCanvasView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val renderer = StrokeRenderer()

    /** Lays out and paints the objects; also the font-based measure for hit tests and the export. */
    val objectRenderer = ObjectRenderer()

    var session: DrawSession = DrawSession(360f, 240f)
        set(value) {
            if (field === value) return
            field = value
            if (width > 0 && height > 0) value.resizeIfEmpty(width / d, height / d)
            inkRevision = -1
            invalidate()
        }

    var tool: DrawTool = DrawTool.PEN
    var brush: Brush = Brush.PEN
    var colour: Int = InkDefaults.BLACK
    var widthUnits: Float = InkDefaults.WIDTHS[InkDefaults.DEFAULT_WIDTH_INDEX]

    /** What a touch does. */
    var input: DrawInput = DrawInput.INK
        set(value) {
            if (field == value) return
            cancel()
            field = value
        }
    var shapeKind: ShapeKind = ShapeKind.RECTANGLE
    var shapeFilled: Boolean = false

    /** Called after a finished stroke was added to the session. */
    var onStrokeAdded: () -> Unit = {}

    /** A shape was dragged from the first point to the second (drawing units). */
    var onShapeDrawn: (Float, Float, Float, Float) -> Unit = { _, _, _, _ -> }

    /** A tap at (x, y) in drawing units with the text tool. */
    var onTextTap: (Float, Float) -> Unit = { _, _ -> }

    /** The object picked by a tap with the select tool (null: nothing there). */
    var onObjectTapped: (Int?) -> Unit = {}

    /** The selected object was dragged by (dx, dy) drawing units. */
    var onObjectMoved: (Float, Float) -> Unit = { _, _ -> }

    /** The palette tint of the checkerboard drawn under a transparent background. */
    var checkerLight: Int = 0xFFFFFFFF.toInt()
    var checkerDark: Int = 0xFFD0D0D0.toInt()

    /** The colour of the selection outline. */
    var selectionColour: Int = 0xFF1E88E5.toInt()

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
    private var drawingBrush = Brush.PEN
    private var drawingColour = 0
    private var drawingWidth = 0f
    private var startX = 0f
    private var startY = 0f
    private val livePath = Path()
    private val dirty = android.graphics.Rect()
    private val checker = Paint()
    private var checkerShaderTile: Bitmap? = null

    // A shape being dragged out, a text tap, or an object being dragged (drawing units).
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var gestureX = 0f
    private var gestureY = 0f
    private var gestureMoved = false
    private var dragId = -1
    private var gestureActive = false
    private val selectionBox = RectF()
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var selectionDash: DashPathEffect? = null

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
        if (inkRevision != session.inkRevision) {
            b.eraseColor(0)
            inkCanvas?.let { renderer.drawStrokes(it, session.strokes) }
            inkRevision = session.inkRevision
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
        canvas.scale(scale, scale)
        if (n > 0 && input == DrawInput.INK && StrokeStyle.overlay(drawingTool, drawingBrush)) {
            renderer.configure(drawingTool, drawingColour, drawingWidth, drawingBrush)
            if (n == 1) renderer.drawDot(canvas, xs[0], ys[0]) else renderer.drawPath(canvas, livePath)
        }
        drawObjects(canvas)
        canvas.restore()
    }

    private fun drawObjects(canvas: Canvas) {
        val objects = session.objects
        if (objects.isNotEmpty()) {
            val dx = if (dragId >= 0 && gestureMoved) gestureX - gestureStartX else 0f
            val dy = if (dragId >= 0 && gestureMoved) gestureY - gestureStartY else 0f
            for (o in objects) {
                if (o.id == dragId && gestureMoved) {
                    canvas.save()
                    canvas.translate(dx, dy)
                    objectRenderer.draw(canvas, o)
                    canvas.restore()
                } else {
                    objectRenderer.draw(canvas, o)
                }
            }
            val sel = if (dragId >= 0) session.find(dragId) else session.selected()
            if (sel != null && input != DrawInput.INK) {
                val bounds = sel.bounds(objectRenderer)
                val mx = if (sel.id == dragId && gestureMoved) dx else 0f
                val my = if (sel.id == dragId && gestureMoved) dy else 0f
                drawSelection(canvas, bounds.left + mx, bounds.top + my, bounds.right + mx, bounds.bottom + my)
            }
        }
        if (gestureActive && input == DrawInput.SHAPE) {
            objectRenderer.draw(canvas, ShapeObject(0, shapeKind, gestureStartX, gestureStartY, gestureX, gestureY, colour, widthUnits, shapeFilled))
        }
    }

    private fun drawSelection(canvas: Canvas, l: Float, t: Float, r: Float, b: Float) {
        val pad = 3f
        selectionBox.set(l - pad, t - pad, r + pad, b + pad)
        val dash = selectionDash ?: DashPathEffect(floatArrayOf(6f, 4f), 0f).also { selectionDash = it }
        selectionPaint.pathEffect = null
        selectionPaint.strokeWidth = 3f
        selectionPaint.color = 0xCCFFFFFF.toInt()
        canvas.drawRect(selectionBox, selectionPaint)
        selectionPaint.pathEffect = dash
        selectionPaint.strokeWidth = 1.5f
        selectionPaint.color = selectionColour
        canvas.drawRect(selectionBox, selectionPaint)
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

    /** Frees the bitmaps and laid-out texts (the panel closed or was removed); the session is untouched and redrawn on the next show. */
    fun release() {
        ink?.recycle()
        ink = null
        inkCanvas = null
        inkRevision = -1
        checkerShaderTile?.recycle()
        checkerShaderTile = null
        checker.shader = null
        objectRenderer.release()
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
                    if (input == DrawInput.INK) {
                        val hist = e.historySize
                        for (h in 0 until hist) addSample(e.getHistoricalX(i, h), e.getHistoricalY(i, h), e.getHistoricalPressure(i, h))
                        addSample(e.getX(i), e.getY(i), e.getPressure(i))
                    } else {
                        gestureMove(e.getX(i), e.getY(i))
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (activePointer >= 0) {
                    val i = e.findPointerIndex(activePointer)
                    if (input == DrawInput.INK) {
                        if (i >= 0) addSample(e.getX(i), e.getY(i), e.getPressure(i))
                        finish()
                    } else {
                        if (i >= 0) gestureMove(e.getX(i), e.getY(i))
                        gestureEnd()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> cancel()
            // ACTION_POINTER_DOWN / UP: a second finger or a palm is ignored; the stroke follows the first pointer.
        }
        return true
    }

    private fun unitsX(px: Float) = (px - offX) / scale
    private fun unitsY(py: Float) = (py - offY) / scale

    private fun begin(e: MotionEvent) {
        activePointer = e.getPointerId(0)
        n = 0
        if (input != DrawInput.INK) {
            gestureBegin(e.getX(0), e.getY(0))
            return
        }
        drawingTool = tool
        drawingBrush = brush
        drawingColour = colour
        drawingWidth = widthUnits
        ensureInk()
        addSample(e.getX(0), e.getY(0), e.getPressure(0))
    }

    // ---- shapes, text and select gestures -----------------------------------------------------------------------

    private fun gestureBegin(px: Float, py: Float) {
        ensureInk()
        gestureStartX = unitsX(px)
        gestureStartY = unitsY(py)
        gestureX = gestureStartX
        gestureY = gestureStartY
        gestureMoved = false
        gestureActive = true
        dragId = -1
        if (input == DrawInput.SELECT) {
            val slop = SLOP_DP * d / scale
            val hit = session.objectAt(gestureStartX, gestureStartY, slop, objectRenderer)
            // The object under the finger can be dragged straight away; it is selected when the finger lifts (the panel is
            // rebuilt by a selection, never while a touch is in progress).
            if (hit != null) dragId = hit.id
        }
        invalidate()
    }

    private fun gestureMove(px: Float, py: Float) {
        if (!gestureActive) return
        gestureX = unitsX(px)
        gestureY = unitsY(py)
        if (!gestureMoved && hypot(gestureX - gestureStartX, gestureY - gestureStartY) * scale / d > MOVE_SLOP_DP) gestureMoved = true
        invalidate()
    }

    private fun gestureEnd() {
        if (!gestureActive) return
        val moved = gestureMoved
        val sx = gestureStartX
        val sy = gestureStartY
        val ex = gestureX
        val ey = gestureY
        val dragged = dragId
        gestureActive = false
        gestureMoved = false
        dragId = -1
        activePointer = -1
        invalidate()
        when (input) {
            DrawInput.SHAPE -> onShapeDrawn(sx, sy, if (moved) ex else sx, if (moved) ey else sy)
            DrawInput.TEXT -> if (!moved) onTextTap(sx, sy)
            DrawInput.SELECT -> {
                if (dragged >= 0) {
                    if (session.selectedId != dragged) onObjectTapped(dragged)
                    if (moved) onObjectMoved(ex - sx, ey - sy)
                } else if (!moved) {
                    onObjectTapped(null)
                }
            }
            DrawInput.INK -> Unit
        }
    }

    // ---- ink -----------------------------------------------------------------------------------------------------

    /** Adds one sample (view pixels) to the stroke: converted to drawing units, drawn as the newest segment. */
    private fun addSample(px: Float, py: Float, pressure: Float) {
        val x = unitsX(px)
        val y = unitsY(py)
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
        val live = !StrokeStyle.overlay(drawingTool, drawingBrush)
        val pad = drawingWidth * StrokeStyle.widthFactor(drawingTool, drawingBrush) / 2f + 2f
        if (n == 1) {
            startX = x
            startY = y
            livePath.rewind()
            livePath.moveTo(x, y)
            if (live) {
                renderer.configure(drawingTool, drawingColour, drawingWidth, drawingBrush)
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
            renderer.configure(drawingTool, drawingColour, drawingWidth, drawingBrush)
            renderer.drawQuad(c, startX, startY, cx, cy, ex, ey)
        }
        invalidateUnits(
            minOf(startX, cx, ex) - pad, minOf(startY, cy, ey) - pad,
            maxOf(startX, cx, ex) + pad, maxOf(startY, cy, ey) + pad,
        )
        startX = ex
        startY = ey
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
        val overlay = StrokeStyle.overlay(drawingTool, drawingBrush)
        if (count >= 2 && c != null) {
            // The last segment ends exactly on the last sample, as the replay of the stroke does.
            val lx = xs[count - 1]
            val ly = ys[count - 1]
            if (!overlay) {
                renderer.configure(drawingTool, drawingColour, drawingWidth, drawingBrush)
                renderer.drawQuad(c, startX, startY, lx, ly, lx, ly)
            }
        }
        if (count >= 1) {
            val pts = ArrayList<Pt>(count)
            for (k in 0 until count) pts += Pt(xs[k], ys[k], ps[k])
            val stroke = Stroke(pts, drawingColour, drawingWidth, drawingTool, if (drawingTool == DrawTool.PEN) drawingBrush else Brush.PEN)
            if (overlay && c != null) renderer.drawStroke(c, stroke)
            session.addStroke(stroke)
            inkRevision = session.inkRevision // the ink already shows this stroke
        }
        n = 0
        activePointer = -1
        invalidate()
        if (count >= 1) onStrokeAdded()
    }

    private fun cancel() {
        if (activePointer < 0 && !gestureActive) return
        n = 0
        activePointer = -1
        gestureActive = false
        gestureMoved = false
        dragId = -1
        inkRevision = -1 // the half-drawn stroke is wiped by a rebuild from the strokes
        invalidate()
    }

    companion object {
        private const val MIN_STEP_UNITS = 0.6f
        private const val CHECKER_DP = 8f

        /** How far (dp) a finger may be from an object and still pick it. */
        private const val SLOP_DP = 10f

        /** A touch that moved less than this (dp) is a tap. */
        private const val MOVE_SLOP_DP = 6f
    }
}
