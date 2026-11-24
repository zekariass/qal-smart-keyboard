package com.qalsmartkeyboard.ime.draw

/**
 * The pure drawing model of Draw & Sketch (T8.4, ADR 0048): no Android types, so it is unit-tested on the JVM and can be
 * reused by phase 2 (text, shapes) and the AI phase (sketch enhancement, handwriting recognition, doodle-to-image).
 *
 * Coordinates are in "drawing units" (dp of the canvas the drawing was started on), so a drawing is resolution
 * independent: the view fits it to whatever size the keyboard has, and the export scales it to the PNG size.
 */
enum class DrawTool(val code: Int) {
    PEN(0), HIGHLIGHTER(1),

    /**
     * Eraser = a stroke painted with a real clear blend over the ink layer ([StrokeRenderer]). The background is a separate
     * layer below, so an erased area shows the background and a transparent export is really transparent (an erase with
     * "white paint" would leave white pixels in a transparent PNG). The erase stays in the stroke list, so undo/redo and
     * the saved file replay it exactly.
     */
    ERASER(2),
    ;

    companion object {
        fun fromCode(c: Int): DrawTool? = entries.firstOrNull { it.code == c }
    }
}

/** One sampled point: [x], [y] in drawing units, [pressure] 0..1 (1 when the device reports none). */
data class Pt(val x: Float, val y: Float, val pressure: Float = 1f)

/** One continuous touch: its sampled [points], the ink [colour] (ARGB), the nominal [widthDp] and the [tool]. */
class Stroke(val points: List<Pt>, val colour: Int, val widthDp: Float, val tool: DrawTool = DrawTool.PEN) {
    /** The plain point list, for future handwriting recognition: eraser strokes carry no ink and give an empty list. */
    fun pointList(): List<Pt> = if (tool == DrawTool.ERASER) emptyList() else points

    override fun equals(other: Any?): Boolean =
        other is Stroke && other.colour == colour && other.widthDp == widthDp && other.tool == tool && other.points == points

    override fun hashCode(): Int = ((points.hashCode() * 31 + colour) * 31 + widthDp.hashCode()) * 31 + tool.hashCode()
}

enum class BackgroundPattern(val code: Int) {
    NONE(0), RULED(1), GRID(2),
    ;

    companion object {
        fun fromCode(c: Int): BackgroundPattern? = entries.firstOrNull { it.code == c }
    }
}

/** The canvas background: [colour] (alpha 0 = transparent, shown as a checkerboard in the view) and an optional line [pattern]. */
data class DrawBackground(val colour: Int, val pattern: BackgroundPattern = BackgroundPattern.NONE) {
    val isTransparent: Boolean get() = (colour ushr 24) == 0

    /** True when the background is dark enough that dark ink would not show. */
    val isDark: Boolean get() = !isTransparent && Luma.of(colour) < 0.45f

    companion object {
        val WHITE = DrawBackground(0xFFFFFFFF.toInt())
        val BLACK = DrawBackground(0xFF000000.toInt())
        val TRANSPARENT = DrawBackground(0x00000000)
        val CREAM = DrawBackground(0xFFFFF8E1.toInt())
        val MINT = DrawBackground(0xFFE8F5E9.toInt())
        val SKY = DrawBackground(0xFFE3F2FD.toInt())
        val RULED = DrawBackground(0xFFFFFFFF.toInt(), BackgroundPattern.RULED)
        val GRID = DrawBackground(0xFFFFFFFF.toInt(), BackgroundPattern.GRID)
        val PRESETS = listOf(WHITE, BLACK, TRANSPARENT, CREAM, MINT, SKY, RULED, GRID)
    }
}

internal object Luma {
    fun of(argb: Int): Float {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (0.299f * r + 0.587f * g + 0.114f * b) / 255f
    }
}

/** An axis-aligned box in drawing units. */
data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * A drawing: a logical canvas of [width] x [height] drawing units, its [background], the [strokes] in paint order and
 * free [meta] (string keys; phase 3 attaches AI results or recognised text here without a format change).
 */
data class Drawing(
    val width: Float,
    val height: Float,
    val background: DrawBackground = DrawBackground.WHITE,
    val strokes: List<Stroke> = emptyList(),
    val meta: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = strokes.none { it.tool != DrawTool.ERASER }

    /**
     * The box around the ink (each stroke grown by half its width), or null when nothing is drawn. Erasers do not count: they
     * only remove ink, so the box may be a little larger than the visible ink, never smaller.
     */
    fun contentBounds(): Bounds? {
        var l = Float.POSITIVE_INFINITY
        var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY
        var b = Float.NEGATIVE_INFINITY
        for (s in strokes) {
            if (s.tool == DrawTool.ERASER) continue
            val half = s.widthDp * (if (s.tool == DrawTool.HIGHLIGHTER) HIGHLIGHTER_FACTOR else 1f) / 2f
            for (p in s.points) {
                if (p.x - half < l) l = p.x - half
                if (p.y - half < t) t = p.y - half
                if (p.x + half > r) r = p.x + half
                if (p.y + half > b) b = p.y + half
            }
        }
        return if (l > r) null else Bounds(l, t, r, b)
    }

    /** Plain point lists of the ink strokes (phase 3: handwriting recognition input). */
    fun inkPointLists(): List<List<Pt>> = strokes.filter { it.tool != DrawTool.ERASER }.map { it.points }

    companion object {
        /** The highlighter paints this much wider than the pen of the same preset. */
        const val HIGHLIGHTER_FACTOR = 3f

        /** The eraser paints this much wider than the pen of the same preset. */
        const val ERASER_FACTOR = 2.5f
    }
}

/**
 * Undo / redo history of [A] with a bounded depth: pushing clears the redo side (a new action forks history); once
 * [maxDepth] actions are held the oldest is forgotten (it can no longer be undone, its effect stays).
 */
class UndoStack<A>(val maxDepth: Int = DEFAULT_DEPTH) {
    private val undoList = ArrayList<A>()
    private val redoList = ArrayList<A>()

    init {
        require(maxDepth >= 1)
    }

    val canUndo: Boolean get() = undoList.isNotEmpty()
    val canRedo: Boolean get() = redoList.isNotEmpty()
    val undoDepth: Int get() = undoList.size
    val redoDepth: Int get() = redoList.size

    fun push(a: A) {
        redoList.clear()
        undoList += a
        if (undoList.size > maxDepth) undoList.removeAt(0)
    }

    /** The action to reverse, now moved to the redo side; null when there is none. */
    fun undo(): A? {
        if (undoList.isEmpty()) return null
        val a = undoList.removeAt(undoList.size - 1)
        redoList += a
        return a
    }

    /** The action to re-apply, moved back to the undo side; null when there is none. */
    fun redo(): A? {
        if (redoList.isEmpty()) return null
        val a = redoList.removeAt(redoList.size - 1)
        undoList += a
        return a
    }

    fun clear() {
        undoList.clear()
        redoList.clear()
    }

    companion object {
        const val DEFAULT_DEPTH = 100
    }
}

/**
 * The editable state of one drawing: strokes with undo / redo. A stroke and a clear are each one undoable step. [revision]
 * grows on every change so views know when to redraw from scratch (after undo / redo / clear) rather than incrementally.
 */
class DrawSession(
    width: Float,
    height: Float,
    background: DrawBackground = DrawBackground.WHITE,
    strokes: List<Stroke> = emptyList(),
    private val meta: Map<String, String> = emptyMap(),
    maxUndo: Int = UndoStack.DEFAULT_DEPTH,
) {
    private sealed interface Step {
        class Add(val stroke: Stroke) : Step
        class Clear(val removed: List<Stroke>) : Step
    }

    private val history = UndoStack<Step>(maxUndo)
    private val list = ArrayList<Stroke>(strokes)

    var width: Float = width
        private set
    var height: Float = height
        private set
    var background: DrawBackground = background
        private set
    var revision: Int = 0
        private set

    /** Unsaved changes since creation or the last [markSaved]. */
    var dirty: Boolean = false
        private set

    val strokes: List<Stroke> get() = list
    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo
    val isEmpty: Boolean get() = list.none { it.tool != DrawTool.ERASER }

    fun addStroke(s: Stroke) {
        if (s.points.isEmpty()) return
        list += s
        history.push(Step.Add(s))
        changed()
    }

    fun undo(): Boolean {
        when (val step = history.undo() ?: return false) {
            is Step.Add -> list.removeAt(list.size - 1)
            is Step.Clear -> { list.clear(); list += step.removed }
        }
        changed()
        return true
    }

    fun redo(): Boolean {
        when (val step = history.redo() ?: return false) {
            is Step.Add -> list += step.stroke
            is Step.Clear -> list.clear()
        }
        changed()
        return true
    }

    /** Removes everything; one undo brings it back. A clear of an empty canvas does nothing. */
    fun clear() {
        if (list.isEmpty()) return
        history.push(Step.Clear(list.toList()))
        list.clear()
        changed()
    }

    fun setBackground(b: DrawBackground) {
        if (b == background) return
        background = b
        changed()
    }

    /** An empty canvas adopts a new logical size (the keyboard was rotated before anything was drawn). */
    fun resizeIfEmpty(w: Float, h: Float) {
        if (list.isNotEmpty() || (w == width && h == height)) return
        width = w
        height = h
        revision++
    }

    fun markSaved() {
        dirty = false
    }

    fun snapshot(): Drawing = Drawing(width, height, background, list.toList(), meta)

    private fun changed() {
        revision++
        dirty = true
    }
}

/** The default ink for a background: black on light and transparent backgrounds, white on dark ones; the palette and widths. */
object InkDefaults {
    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()

    fun inkFor(bg: DrawBackground): Int = if (bg.isDark) WHITE else BLACK

    /** The ten swatches: neutrals first, then hues. */
    val PALETTE: List<Int> = listOf(
        0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(),
        0xFF43A047.toInt(), 0xFF00ACC1.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFF6D4C41.toInt(),
    )

    /** Pen widths of the four presets, in drawing units. */
    val WIDTHS: List<Float> = listOf(2f, 4f, 8f, 14f)

    const val DEFAULT_WIDTH_INDEX = 1
}
