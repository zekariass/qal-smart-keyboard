package com.qalsmartkeyboard.ime.draw

import kotlin.math.abs
import kotlin.math.hypot

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

/**
 * The nib of a [DrawTool.PEN] stroke (T8.15). The highlighter and the eraser stay tools of their own (their codes are in the
 * files of version 1); a stroke of any other tool ignores its brush. A version-1 stroke has no brush: it is a [PEN].
 */
enum class Brush(val code: Int) {
    PEN(0),

    /** A wide flat ink, semi-opaque. */
    MARKER(1),

    /** Thin and grainy, translucent. */
    PENCIL(2),
    DASHED(3),
    DOTTED(4),

    /** An angled flat nib: thick across the nib, thin along it. */
    CALLIGRAPHY(5),
    ;

    companion object {
        fun fromCode(c: Int): Brush? = entries.firstOrNull { it.code == c }
    }
}

/** How wide a stroke paints and how it is drawn while the finger is down, the one place the canvas, the thumbnails and the export agree on. */
object StrokeStyle {
    /** The painted width of a stroke of nominal width 1. */
    fun widthFactor(tool: DrawTool, brush: Brush): Float = when (tool) {
        DrawTool.HIGHLIGHTER -> Drawing.HIGHLIGHTER_FACTOR
        DrawTool.ERASER -> Drawing.ERASER_FACTOR
        DrawTool.PEN -> when (brush) {
            Brush.PEN -> 1f
            Brush.MARKER -> 2.2f
            Brush.PENCIL -> 0.75f
            Brush.DASHED -> 1f
            Brush.DOTTED -> 1.2f
            Brush.CALLIGRAPHY -> 1.6f
        }
    }

    /**
     * True when the live stroke is shown as one path over the ink and drawn into it when the finger lifts: translucent inks
     * (their overlapping segments would darken) and dashed ones (the pattern would restart in every segment).
     */
    fun overlay(tool: DrawTool, brush: Brush): Boolean = tool == DrawTool.HIGHLIGHTER ||
        (tool == DrawTool.PEN && (brush == Brush.MARKER || brush == Brush.PENCIL || brush == Brush.DASHED || brush == Brush.DOTTED))
}

/** One sampled point: [x], [y] in drawing units, [pressure] 0..1 (1 when the device reports none). */
data class Pt(val x: Float, val y: Float, val pressure: Float = 1f)

/** One continuous touch: its sampled [points], the ink [colour] (ARGB), the nominal [widthDp], the [tool] and, for the pen, its [brush]. */
class Stroke(val points: List<Pt>, val colour: Int, val widthDp: Float, val tool: DrawTool = DrawTool.PEN, val brush: Brush = Brush.PEN) {
    /** The plain point list, for future handwriting recognition: eraser strokes carry no ink and give an empty list. */
    fun pointList(): List<Pt> = if (tool == DrawTool.ERASER) emptyList() else points

    override fun equals(other: Any?): Boolean =
        other is Stroke && other.colour == colour && other.widthDp == widthDp && other.tool == tool && other.brush == brush && other.points == points

    override fun hashCode(): Int = (((points.hashCode() * 31 + colour) * 31 + widthDp.hashCode()) * 31 + tool.hashCode()) * 31 + brush.hashCode()

    /** Half the painted width: how far the ink reaches beside its points. */
    fun halfWidth(): Float = widthDp * StrokeStyle.widthFactor(tool, brush) / 2f
}

enum class BackgroundPattern(val code: Int) {
    NONE(0), RULED(1), GRID(2), DOTS(3),
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
        val BLUSH = DrawBackground(0xFFFCE4EC.toInt())
        val LAVENDER = DrawBackground(0xFFEDE7F6.toInt())
        val SLATE = DrawBackground(0xFF263238.toInt())
        val RULED = DrawBackground(0xFFFFFFFF.toInt(), BackgroundPattern.RULED)
        val GRID = DrawBackground(0xFFFFFFFF.toInt(), BackgroundPattern.GRID)
        val DOTS = DrawBackground(0xFFFFFFFF.toInt(), BackgroundPattern.DOTS)

        /** The paper choices: plain colours (white, black, transparent and a small palette), then lined, grid and dotted paper. */
        val PRESETS = listOf(WHITE, BLACK, TRANSPARENT, CREAM, MINT, SKY, BLUSH, LAVENDER, SLATE, RULED, GRID, DOTS)
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

/** What the screen needs to size a text object: its box in drawing units, from the real font. [RoughText] is the estimate used without one. */
fun interface TextMeasure {
    fun bounds(t: TextObject): Bounds
}

/** A font-free estimate of a text box (about 0.55 em per character, 1.25 em per line), so the model works on the JVM. */
object RoughText : TextMeasure {
    override fun bounds(t: TextObject): Bounds {
        val em = t.size.units
        val perLine = maxOf(1, (t.boxWidth / (em * 0.55f)).toInt())
        var lines = 0
        var longest = 0
        for (line in t.text.split('\n')) {
            lines += maxOf(1, (line.length + perLine - 1) / perLine)
            longest = maxOf(longest, minOf(line.length, perLine))
        }
        return Bounds(t.x, t.y, t.x + maxOf(em, longest * em * 0.55f), t.y + lines * em * 1.25f)
    }
}

/**
 * A drawing: a logical canvas of [width] x [height] drawing units, its [background], the [strokes] in paint order, the
 * [objects] (text and shapes; painted above all the ink, see [DrawObject]) and free [meta] (string keys; phase 3 attaches AI
 * results or recognised text here without a format change).
 */
data class Drawing(
    val width: Float,
    val height: Float,
    val background: DrawBackground = DrawBackground.WHITE,
    val strokes: List<Stroke> = emptyList(),
    val meta: Map<String, String> = emptyMap(),
    val objects: List<DrawObject> = emptyList(),
) {
    val isEmpty: Boolean get() = objects.isEmpty() && strokes.none { it.tool != DrawTool.ERASER }

    /**
     * The box around the ink and the objects (each stroke grown by half its width), or null when nothing is drawn. Erasers do
     * not count: they only remove ink, so the box may be a little larger than the visible ink, never smaller.
     */
    fun contentBounds(measure: TextMeasure = RoughText): Bounds? {
        var l = Float.POSITIVE_INFINITY
        var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY
        var b = Float.NEGATIVE_INFINITY
        for (s in strokes) {
            if (s.tool == DrawTool.ERASER) continue
            val half = s.halfWidth()
            for (p in s.points) {
                if (p.x - half < l) l = p.x - half
                if (p.y - half < t) t = p.y - half
                if (p.x + half > r) r = p.x + half
                if (p.y + half > b) b = p.y + half
            }
        }
        for (o in objects) {
            val ob = o.bounds(measure)
            if (ob.left < l) l = ob.left
            if (ob.top < t) t = ob.top
            if (ob.right > r) r = ob.right
            if (ob.bottom > b) b = ob.bottom
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

enum class ShapeKind(val code: Int) {
    LINE(0), ARROW(1), RECTANGLE(2), ELLIPSE(3),
    ;

    companion object {
        fun fromCode(c: Int): ShapeKind? = entries.firstOrNull { it.code == c }
    }
}

/** The text sizes of a text object, in drawing units (the em of the font). */
enum class TextSize(val code: Int, val units: Float) {
    SMALL(0, 14f), MEDIUM(1, 22f), LARGE(2, 34f), XLARGE(3, 50f),
    ;

    companion object {
        fun fromCode(c: Int): TextSize? = entries.firstOrNull { it.code == c }
    }
}

/**
 * Something placed on a drawing besides the ink: a [TextObject] or a [ShapeObject]. Objects are painted above all strokes, in
 * list order, and the eraser never touches them (it clears ink only): an object is moved, restyled or deleted on its own. Each
 * has an [id] unique in its session (0 = not placed yet); the id survives a move or a restyle, so a selection does.
 */
sealed interface DrawObject {
    val id: Int
    val colour: Int

    fun withId(id: Int): DrawObject
    fun withColour(colour: Int): DrawObject
    fun moved(dx: Float, dy: Float): DrawObject

    /** The box of the object including its line width (text: [measure]'s box). */
    fun bounds(measure: TextMeasure = RoughText): Bounds

    /** True when ([x], [y]) is on the object within [slop] units. */
    fun hit(x: Float, y: Float, slop: Float, measure: TextMeasure = RoughText): Boolean
}

/** Text at ([x], [y]) (its top left), wrapped to [boxWidth] units; [size] is the largest size, it shrinks so no word is cut. */
data class TextObject(
    override val id: Int = 0,
    val text: String,
    val x: Float,
    val y: Float,
    val size: TextSize = TextSize.MEDIUM,
    override val colour: Int = InkDefaults.BLACK,
    val bold: Boolean = false,
    val boxWidth: Float = DEFAULT_BOX,
) : DrawObject {
    override fun withId(id: Int) = copy(id = id)
    override fun withColour(colour: Int) = copy(colour = colour)
    override fun moved(dx: Float, dy: Float) = copy(x = x + dx, y = y + dy)
    override fun bounds(measure: TextMeasure) = measure.bounds(this)
    override fun hit(x: Float, y: Float, slop: Float, measure: TextMeasure): Boolean {
        val b = measure.bounds(this)
        return x >= b.left - slop && x <= b.right + slop && y >= b.top - slop && y <= b.bottom + slop
    }

    override fun toString() = "TextObject(id=$id, ${text.length} chars)" // the text is the user's: never in a log line

    companion object {
        const val MAX_CHARS = 200
        const val DEFAULT_BOX = 240f
    }
}

/**
 * A line, arrow, rectangle or ellipse from ([x1], [y1]) to ([x2], [y2]) (the arrow points at the second; the rectangle and
 * the ellipse fill that box). [filled] paints the inside of a rectangle or ellipse; [widthDp] is the outline's thickness.
 */
data class ShapeObject(
    override val id: Int = 0,
    val kind: ShapeKind,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    override val colour: Int = InkDefaults.BLACK,
    val widthDp: Float = 4f,
    val filled: Boolean = false,
) : DrawObject {
    override fun withId(id: Int) = copy(id = id)
    override fun withColour(colour: Int) = copy(colour = colour)
    override fun moved(dx: Float, dy: Float) = copy(x1 = x1 + dx, y1 = y1 + dy, x2 = x2 + dx, y2 = y2 + dy)

    /** The length of an arrow's head. */
    val headLength: Float get() = maxOf(10f, widthDp * 4f)

    override fun bounds(measure: TextMeasure): Bounds {
        val pad = widthDp / 2f + if (kind == ShapeKind.ARROW) headLength / 2f else 0f
        return Bounds(minOf(x1, x2) - pad, minOf(y1, y2) - pad, maxOf(x1, x2) + pad, maxOf(y1, y2) + pad)
    }

    override fun hit(x: Float, y: Float, slop: Float, measure: TextMeasure): Boolean {
        val tol = widthDp / 2f + slop
        return when (kind) {
            ShapeKind.LINE, ShapeKind.ARROW -> distanceToSegment(x, y, x1, y1, x2, y2) <= tol
            ShapeKind.RECTANGLE -> {
                val l = minOf(x1, x2)
                val r = maxOf(x1, x2)
                val t = minOf(y1, y2)
                val b = maxOf(y1, y2)
                val inside = x >= l && x <= r && y >= t && y <= b
                if (filled && inside) {
                    true
                } else {
                    val outer = x >= l - tol && x <= r + tol && y >= t - tol && y <= b + tol
                    val inner = x > l + tol && x < r - tol && y > t + tol && y < b - tol
                    outer && !inner
                }
            }
            ShapeKind.ELLIPSE -> {
                val rx = maxOf(abs(x2 - x1) / 2f, 0.5f)
                val ry = maxOf(abs(y2 - y1) / 2f, 0.5f)
                val nx = (x - (x1 + x2) / 2f) / rx
                val ny = (y - (y1 + y2) / 2f) / ry
                val d = hypot(nx, ny)
                if (filled) d <= 1f + tol / minOf(rx, ry) else abs(d - 1f) * minOf(rx, ry) <= tol
            }
        }
    }

    companion object {
        fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            if (len2 == 0f) return hypot(px - ax, py - ay)
            val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
            return hypot(px - (ax + t * dx), py - (ay + t * dy))
        }
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
 * The editable state of one drawing: strokes and objects with undo / redo. A stroke, an object added, removed, moved or
 * restyled, and a clear are each one undoable step (the history is bounded, see [UndoStack]). [revision] grows on every
 * change; [inkRevision] only when the strokes changed, so a view rebuilds its ink bitmap only for those (moving a text
 * never repaints the ink).
 */
class DrawSession(
    width: Float,
    height: Float,
    background: DrawBackground = DrawBackground.WHITE,
    strokes: List<Stroke> = emptyList(),
    private val meta: Map<String, String> = emptyMap(),
    maxUndo: Int = UndoStack.DEFAULT_DEPTH,
    objects: List<DrawObject> = emptyList(),
) {
    private sealed interface Step {
        class Add(val stroke: Stroke) : Step
        class Clear(val removed: List<Stroke>, val removedObjects: List<DrawObject>) : Step
        class AddObject(val obj: DrawObject, val index: Int) : Step
        class RemoveObject(val obj: DrawObject, val index: Int) : Step
        class Replace(val before: DrawObject, val after: DrawObject) : Step
    }

    private val history = UndoStack<Step>(maxUndo)
    private val list = ArrayList<Stroke>(strokes)
    private val things = ArrayList<DrawObject>()
    private var nextId = 1

    init {
        for (o in objects.take(MAX_OBJECTS)) things += o.withId(nextId++)
    }

    var width: Float = width
        private set
    var height: Float = height
        private set
    var background: DrawBackground = background
        private set
    var revision: Int = 0
        private set

    /** Grows only when the strokes changed (the ink bitmap of a view is stale). */
    var inkRevision: Int = 0
        private set

    /** Unsaved changes since creation or the last [markSaved]. */
    var dirty: Boolean = false
        private set

    /** The selected object's id (the one just placed, or tapped with the select tool), or null. */
    var selectedId: Int? = null
        private set

    val strokes: List<Stroke> get() = list
    val objects: List<DrawObject> get() = things
    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo
    val isEmpty: Boolean get() = things.isEmpty() && list.none { it.tool != DrawTool.ERASER }

    fun selected(): DrawObject? = selectedId?.let { id -> things.firstOrNull { it.id == id } }

    fun find(id: Int): DrawObject? = things.firstOrNull { it.id == id }

    /** Selects the object [id] (null = none); an unknown id selects nothing. Not an undoable change. */
    fun select(id: Int?) {
        val next = id?.takeIf { find(it) != null }
        if (next == selectedId) return
        selectedId = next
        revision++
    }

    /** The topmost object under ([x], [y]) within [slop] units, or null. */
    fun objectAt(x: Float, y: Float, slop: Float, measure: TextMeasure = RoughText): DrawObject? =
        things.lastOrNull { it.hit(x, y, slop, measure) }

    fun addStroke(s: Stroke) {
        if (s.points.isEmpty()) return
        list += s
        history.push(Step.Add(s))
        inkChanged()
    }

    /** Places [o] above everything and selects it; null (nothing added) when the object limit is reached. */
    fun addObject(o: DrawObject): DrawObject? {
        if (things.size >= MAX_OBJECTS) return null
        val placed = o.withId(nextId++)
        things += placed
        history.push(Step.AddObject(placed, things.size - 1))
        selectedId = placed.id
        changed()
        return placed
    }

    /** Swaps the object with [o]'s id for [o] (a move, a new colour, edited text); one undoable step. False when nothing changed. */
    fun replaceObject(o: DrawObject): Boolean {
        val i = things.indexOfFirst { it.id == o.id }
        if (i < 0 || things[i] == o) return false
        val before = things[i]
        things[i] = o
        history.push(Step.Replace(before, o))
        changed()
        return true
    }

    fun moveObject(id: Int, dx: Float, dy: Float): Boolean {
        val o = find(id) ?: return false
        return replaceObject(o.moved(dx, dy))
    }

    fun removeObject(id: Int): Boolean {
        val i = things.indexOfFirst { it.id == id }
        if (i < 0) return false
        val o = things.removeAt(i)
        history.push(Step.RemoveObject(o, i))
        if (selectedId == id) selectedId = null
        changed()
        return true
    }

    fun undo(): Boolean {
        when (val step = history.undo() ?: return false) {
            is Step.Add -> { list.removeAt(list.size - 1); inkRevision++ }
            is Step.Clear -> { list.clear(); list += step.removed; things.clear(); things += step.removedObjects; inkRevision++ }
            is Step.AddObject -> things.removeAll { it.id == step.obj.id }
            is Step.RemoveObject -> things.add(step.index.coerceAtMost(things.size), step.obj)
            is Step.Replace -> things[things.indexOfFirst { it.id == step.after.id }] = step.before
        }
        keepSelectionValid()
        changed()
        return true
    }

    fun redo(): Boolean {
        when (val step = history.redo() ?: return false) {
            is Step.Add -> { list += step.stroke; inkRevision++ }
            is Step.Clear -> { list.clear(); things.clear(); inkRevision++ }
            is Step.AddObject -> things.add(step.index.coerceAtMost(things.size), step.obj)
            is Step.RemoveObject -> things.removeAll { it.id == step.obj.id }
            is Step.Replace -> things[things.indexOfFirst { it.id == step.before.id }] = step.after
        }
        keepSelectionValid()
        changed()
        return true
    }

    /** Removes everything (ink and objects); one undo brings it back. A clear of an empty canvas does nothing. */
    fun clear() {
        if (list.isEmpty() && things.isEmpty()) return
        history.push(Step.Clear(list.toList(), things.toList()))
        list.clear()
        things.clear()
        selectedId = null
        inkChanged()
    }

    fun setBackground(b: DrawBackground) {
        if (b == background) return
        background = b
        changed()
    }

    /** An empty canvas adopts a new logical size (the keyboard was rotated before anything was drawn). */
    fun resizeIfEmpty(w: Float, h: Float) {
        if (list.isNotEmpty() || things.isNotEmpty() || (w == width && h == height)) return
        width = w
        height = h
        revision++
        inkRevision++
    }

    fun markSaved() {
        dirty = false
    }

    fun snapshot(): Drawing = Drawing(width, height, background, list.toList(), meta, things.toList())

    private fun keepSelectionValid() {
        val s = selectedId
        if (s != null && find(s) == null) selectedId = null
    }

    private fun inkChanged() {
        inkRevision++
        changed()
    }

    private fun changed() {
        revision++
        dirty = true
    }

    companion object {
        /** A drawing holds at most this many objects. */
        const val MAX_OBJECTS = 200
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
