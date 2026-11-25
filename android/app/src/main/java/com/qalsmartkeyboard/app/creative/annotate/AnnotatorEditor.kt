package com.qalsmartkeyboard.app.creative.annotate

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qalsmartkeyboard.app.creative.cards.ExportFormat
import kotlin.math.hypot
import kotlin.math.max

/**
 * An undo history of at most [maxDepth] states (the oldest goes first). [record] takes the state *before* a change;
 * [undo] and [redo] take the current one and give back the one to show.
 */
class BoundedHistory<T>(val maxDepth: Int = DEFAULT_DEPTH) {
    private val past = ArrayDeque<T>()
    private val future = ArrayDeque<T>()

    init {
        require(maxDepth > 0) { "depth $maxDepth" }
    }

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()
    val undoDepth: Int get() = past.size

    fun record(before: T) {
        past.addLast(before)
        while (past.size > maxDepth) past.removeFirst()
        future.clear()
    }

    fun undo(current: T): T? {
        val prev = past.removeLastOrNull() ?: return null
        future.addLast(current)
        return prev
    }

    fun redo(current: T): T? {
        val next = future.removeLastOrNull() ?: return null
        past.addLast(current)
        while (past.size > maxDepth) past.removeFirst()
        return next
    }

    fun clear() {
        past.clear()
        future.clear()
    }

    companion object {
        const val DEFAULT_DEPTH = 50
    }
}

/** Stroke thickness presets, as a fraction of the picture's shorter side (so they look the same on any picture). */
enum class Thickness(val id: String, val fraction: Float) {
    THIN("thin", 0.004f), MEDIUM("medium", 0.008f), THICK("thick", 0.016f);

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: MEDIUM
    }
}

/** Text size presets, as a fraction of the picture's shorter side. */
enum class LabelSize(val id: String, val fraction: Float) {
    SMALL("small", 0.03f), MEDIUM("medium", 0.045f), LARGE("large", 0.07f);

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: MEDIUM
    }
}

/** The area a redaction tool draws: a box, or a freehand brush. */
enum class RegionShape { BOX, BRUSH }

/** What the screen has to do after a gesture ends. */
sealed interface GestureOutcome {
    data object None : GestureOutcome

    /** Ask for a label's text: a new one at [at], or a new text for [editing]. */
    data class AskText(val at: Pt, val editing: Annotation.Label?) : GestureOutcome

    /** The picture already holds [AnnotDoc.MAX_ITEMS] objects. */
    data object Full : GestureOutcome
}

/**
 * The state of the picture being annotated (T8.7): the [AnnotDoc] (null until a picture is imported), the chosen tool
 * and its options, the object being drawn ([live]), the selection, and a bounded undo/redo history. Plain state without
 * Android services, so every rule is JVM-tested; Compose reads the snapshot state. Gestures arrive in image pixels.
 */
class AnnotatorEditor(var measure: LabelMeasurer = LabelMeasurer.Estimate, historyDepth: Int = BoundedHistory.DEFAULT_DEPTH) {
    var doc: AnnotDoc? by mutableStateOf(null)
        private set

    var tool: AnnotTool by mutableStateOf(AnnotTool.PEN)
    var color: Int by mutableStateOf(PALETTE[0])
    var thickness: Thickness by mutableStateOf(Thickness.MEDIUM)
    var labelSize: LabelSize by mutableStateOf(LabelSize.MEDIUM)
    var labelBackdrop: Boolean by mutableStateOf(true)
    var regionShape: RegionShape by mutableStateOf(RegionShape.BOX)
    var format: ExportFormat by mutableStateOf(ExportFormat.PNG)

    /** The object being drawn (not yet in [doc]). */
    var live: Annotation? by mutableStateOf(null)
        private set

    /** The crop being dragged (not yet in [doc]). */
    var liveCrop: PixelRect? by mutableStateOf(null)
        private set

    var selectedId: Int? by mutableStateOf(null)
        private set

    /** Something changed since the picture was imported, restored or saved. */
    var dirty: Boolean by mutableStateOf(false)
        private set

    var canUndo: Boolean by mutableStateOf(false)
        private set
    var canRedo: Boolean by mutableStateOf(false)
        private set

    private val history = BoundedHistory<AnnotDoc>(historyDepth)

    val selected: Annotation? get() = selectedId?.let { id -> doc?.items?.firstOrNull { it.id == id } }

    // ---- Picture -------------------------------------------------------------------------------------------------------

    /** Starts annotating a new [width] x [height] picture. */
    fun start(width: Int, height: Int) = reset(AnnotDoc(width, height))

    /** Continues a stored draft. */
    fun restore(d: AnnotDoc, f: ExportFormat) {
        reset(d)
        format = f
    }

    /** No picture. */
    fun close() {
        doc = null
        history.clear()
        live = null
        liveCrop = null
        selectedId = null
        dirty = false
        syncHistory()
    }

    private fun reset(d: AnnotDoc) {
        doc = d
        history.clear()
        live = null
        liveCrop = null
        selectedId = null
        dirty = false
        syncHistory()
    }

    /** The picture was saved: nothing new to save. */
    fun saved() {
        dirty = false
    }

    private fun commit(next: AnnotDoc) {
        val cur = doc ?: return
        if (next == cur) return
        history.record(cur)
        doc = next
        dirty = true
        syncHistory()
    }

    private fun syncHistory() {
        canUndo = history.canUndo
        canRedo = history.canRedo
    }

    fun undo() {
        val cur = doc ?: return
        doc = history.undo(cur) ?: return
        afterJump()
    }

    fun redo() {
        val cur = doc ?: return
        doc = history.redo(cur) ?: return
        afterJump()
    }

    private fun afterJump() {
        if (selected == null) selectedId = null
        live = null
        liveCrop = null
        dirty = true
        syncHistory()
    }

    /** Replaces the whole picture state as one undoable step (e.g. locked blur turned into solid boxes). */
    fun replace(next: AnnotDoc) = commit(next)

    // ---- Sizes ---------------------------------------------------------------------------------------------------------

    private val minSide: Float get() = doc?.let { minOf(it.width, it.height).toFloat() } ?: 1000f

    fun strokeWidth(t: Thickness = thickness) = max(2f, minSide * t.fraction)

    fun highlighterWidth(t: Thickness = thickness) = max(8f, minSide * t.fraction * 3.5f)

    fun brushRadius(t: Thickness = thickness) = max(6f, minSide * t.fraction * 2.5f)

    fun textSize(s: LabelSize = labelSize) = max(12f, minSide * s.fraction)

    fun stepRadius() = max(12f, minSide * 0.035f)

    /** A drag shorter than this (image pixels) is a tap. */
    private val minDrag: Float get() = max(3f, minSide * 0.006f)

    // ---- Gestures ------------------------------------------------------------------------------------------------------

    private var start: Pt? = null
    private var moveBefore: AnnotDoc? = null
    private var eraseBefore: AnnotDoc? = null
    private var maxTravel = 0f
    private var slop = 0f

    /**
     * A finger went down at [p] with [slop] image pixels of touch tolerance. False when the editor does not use this
     * drag (the select tool on empty space: the screen pans the view instead).
     */
    fun press(p: Pt, slop: Float): Boolean {
        val d = doc ?: return false
        start = p
        maxTravel = 0f
        this.slop = slop
        val id = d.nextId
        live = when (tool) {
            AnnotTool.PEN -> Annotation.Ink(id, listOf(p), color, strokeWidth())
            AnnotTool.HIGHLIGHTER -> Annotation.Ink(id, listOf(p), color, highlighterWidth(), highlighter = true)
            AnnotTool.ARROW -> Annotation.Arrow(id, p, p, color, strokeWidth())
            AnnotTool.RECT -> Annotation.Shape(id, ShapeKind.RECT, Box.of(p, p), color, strokeWidth())
            AnnotTool.ELLIPSE -> Annotation.Shape(id, ShapeKind.ELLIPSE, Box.of(p, p), color, strokeWidth())
            AnnotTool.SPOTLIGHT -> Annotation.Spotlight(id, Box.of(p, p))
            AnnotTool.BLUR, AnnotTool.PIXELATE, AnnotTool.REDACT_SOLID -> Annotation.Redaction(
                id, redactMode(tool),
                if (regionShape == RegionShape.BOX) RedactArea.Rect(Box.of(p, p)) else RedactArea.Brush(listOf(p), brushRadius()),
                color = Annotation.SOLID_DEFAULT,
            )
            else -> null
        }
        when (tool) {
            AnnotTool.SELECT -> {
                val hit = Geometry.topmostAt(d.items, p, slop, measure)
                selectedId = hit?.id
                if (hit == null) return false
                moveBefore = d
            }
            AnnotTool.ERASER -> {
                eraseBefore = d
                eraseAt(p)
            }
            AnnotTool.CROP -> liveCrop = null
            else -> Unit
        }
        return true
    }

    fun drag(p: Pt) {
        val s = start ?: return
        val d = doc ?: return
        maxTravel = max(maxTravel, hypot(p.x - s.x, p.y - s.y))
        when (val l = live) {
            is Annotation.Ink -> live = l.copy(points = appendPoint(l.points, p))
            is Annotation.Arrow -> live = l.copy(to = p)
            is Annotation.Shape -> live = l.copy(box = Box.of(s, p))
            is Annotation.Spotlight -> live = l.copy(box = Box.of(s, p))
            is Annotation.Redaction -> live = when (val a = l.area) {
                is RedactArea.Rect -> l.copy(area = RedactArea.Rect(Box.of(s, p)))
                is RedactArea.Brush -> l.copy(area = a.copy(points = appendPoint(a.points, p)))
            }
            else -> when (tool) {
                AnnotTool.SELECT -> {
                    val before = moveBefore ?: return
                    val id = selectedId ?: return
                    val dx = p.x - s.x
                    val dy = p.y - s.y
                    doc = before.copy(items = before.items.map { if (it.id == id) Geometry.moved(it, dx, dy) else it })
                }
                AnnotTool.ERASER -> eraseAt(p)
                AnnotTool.CROP -> liveCrop = Box.of(s, p).toPixelsOutward().clampTo(d.width, d.height)
                else -> Unit
            }
        }
    }

    private fun appendPoint(points: List<Pt>, p: Pt): List<Pt> {
        val last = points.last()
        if (points.size >= AnnotDoc.MAX_POINTS || hypot(p.x - last.x, p.y - last.y) < MIN_POINT_STEP) return points
        return points + p
    }

    private fun eraseAt(p: Pt) {
        val d = doc ?: return
        val hit = Geometry.topmostAt(d.items, p, slop, measure) ?: return
        doc = d.copy(items = d.items.filterNot { it.id == hit.id })
        if (selectedId == hit.id) selectedId = null
    }

    /** The finger went up: commits what was drawn. */
    fun release(): GestureOutcome {
        val s = start ?: return GestureOutcome.None
        start = null
        val d = doc ?: return GestureOutcome.None
        val tap = maxTravel < minDrag
        val l = live
        live = null
        when (tool) {
            AnnotTool.SELECT -> {
                val before = moveBefore
                moveBefore = null
                if (before != null && doc != before) {
                    val moved = doc!!
                    doc = before
                    commit(moved)
                }
                return GestureOutcome.None
            }
            AnnotTool.ERASER -> {
                val before = eraseBefore
                eraseBefore = null
                if (before != null && doc != before) {
                    val after = doc!!
                    doc = before
                    commit(after)
                }
                return GestureOutcome.None
            }
            AnnotTool.CROP -> {
                val c = liveCrop
                liveCrop = null
                if (c != null && !tap && c.width >= minDrag && c.height >= minDrag) commit(d.copy(crop = c))
                return GestureOutcome.None
            }
            AnnotTool.TEXT -> {
                if (!tap) return GestureOutcome.None
                val hit = d.items.lastOrNull { it is Annotation.Label && Geometry.hits(it, s, slop, measure) } as Annotation.Label?
                return GestureOutcome.AskText(hit?.at ?: s, hit)
            }
            AnnotTool.STEP -> {
                if (!tap) return GestureOutcome.None
                if (d.items.size >= AnnotDoc.MAX_ITEMS) return GestureOutcome.Full
                val n = (d.items.filterIsInstance<Annotation.Step>().maxOfOrNull { it.number } ?: 0) + 1
                return add(Annotation.Step(d.nextId, s, n, color, stepRadius()))
            }
            else -> Unit
        }
        if (l == null) return GestureOutcome.None
        val keep = when (l) {
            is Annotation.Ink -> true // a tap is a dot
            is Annotation.Arrow -> !tap
            is Annotation.Shape -> !tap && l.box.width >= minDrag && l.box.height >= minDrag
            is Annotation.Spotlight -> !tap && l.box.width >= minDrag && l.box.height >= minDrag
            is Annotation.Redaction -> when (val a = l.area) {
                is RedactArea.Rect -> !tap && a.box.width >= minDrag && a.box.height >= minDrag
                is RedactArea.Brush -> true
            }
            else -> false
        }
        if (!keep) return GestureOutcome.None
        if (d.items.size >= AnnotDoc.MAX_ITEMS) return GestureOutcome.Full
        return add(l)
    }

    /** A second finger came down (a pinch): what the first one was drawing is dropped. */
    fun cancel() {
        val before = moveBefore ?: eraseBefore
        if (before != null) doc = before
        moveBefore = null
        eraseBefore = null
        start = null
        live = null
        liveCrop = null
    }

    private fun add(a: Annotation): GestureOutcome {
        val d = doc ?: return GestureOutcome.None
        commit(d.with(a))
        selectedId = a.id
        return GestureOutcome.None
    }

    // ---- Commands ------------------------------------------------------------------------------------------------------

    /** Adds a label with [text] at [at], or changes the text of [editing]; empty text removes the label being edited. */
    fun putLabel(at: Pt, text: String, editing: Annotation.Label? = null): GestureOutcome {
        val d = doc ?: return GestureOutcome.None
        val t = text.take(Annotation.MAX_TEXT)
        if (editing != null) {
            if (d.items.none { it.id == editing.id }) return GestureOutcome.None
            commit(
                if (t.isBlank()) d.copy(items = d.items.filterNot { it.id == editing.id })
                else d.copy(items = d.items.map { if (it.id == editing.id) editing.copy(text = t) else it }),
            )
            if (t.isBlank() && selectedId == editing.id) selectedId = null
            return GestureOutcome.None
        }
        if (t.isBlank()) return GestureOutcome.None
        if (d.items.size >= AnnotDoc.MAX_ITEMS) return GestureOutcome.Full
        return add(Annotation.Label(d.nextId, at, t, color, textSize(), labelBackdrop))
    }

    fun deleteSelected() {
        val d = doc ?: return
        val id = selectedId ?: return
        selectedId = null
        commit(d.copy(items = d.items.filterNot { it.id == id }))
    }

    fun deselect() {
        selectedId = null
    }

    fun clearCrop() {
        val d = doc ?: return
        if (d.crop != null) commit(d.copy(crop = null))
    }

    companion object {
        /** Stroke points closer than this (image pixels) are not kept. */
        const val MIN_POINT_STEP = 1.5f

        /** The annotator's colours: red first (the classic mark-up colour), then high-contrast choices. */
        val PALETTE: List<Int> = listOf(
            0xFFE53935.toInt(), 0xFFFFEB3B.toInt(), 0xFF43A047.toInt(), 0xFF1E88E5.toInt(),
            0xFFFB8C00.toInt(), 0xFF8E24AA.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt(),
        )

        fun redactMode(t: AnnotTool): RedactMode = when (t) {
            AnnotTool.BLUR -> RedactMode.BLUR
            AnnotTool.PIXELATE -> RedactMode.PIXELATE
            else -> RedactMode.SOLID
        }
    }
}
