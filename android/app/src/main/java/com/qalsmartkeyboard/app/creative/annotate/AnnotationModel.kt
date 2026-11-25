package com.qalsmartkeyboard.app.creative.annotate

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The image annotator's data model (T8.7): plain Kotlin, no Android types, so the geometry, the gate, the undo history,
 * the redaction maths and the draft JSON are JVM-tested. Every coordinate is in **image pixels** of the working bitmap
 * (the imported picture after its bounded downscale and EXIF rotation), never in screen pixels, so zooming the view
 * never changes an annotation.
 */

/** A point in image pixels. */
data class Pt(val x: Float, val y: Float)

/** An axis-aligned box in image pixels; [normalized] orders the corners. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val minSide: Float get() = min(width, height)

    fun normalized() = Box(min(left, right), min(top, bottom), max(left, right), max(top, bottom))

    fun offset(dx: Float, dy: Float) = Box(left + dx, top + dy, right + dx, bottom + dy)

    fun outset(d: Float) = Box(left - d, top - d, right + d, bottom + d)

    fun contains(p: Pt, slop: Float = 0f) = p.x >= left - slop && p.x <= right + slop && p.y >= top - slop && p.y <= bottom + slop

    /** The pixels this box touches (rounded outwards), not clamped. */
    fun toPixelsOutward() = PixelRect(floor(left).toInt(), floor(top).toInt(), ceil(right).toInt(), ceil(bottom).toInt())

    companion object {
        fun of(a: Pt, b: Pt) = Box(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))

        fun around(points: List<Pt>): Box? {
            if (points.isEmpty()) return null
            var l = Float.MAX_VALUE
            var t = Float.MAX_VALUE
            var r = -Float.MAX_VALUE
            var b = -Float.MAX_VALUE
            for (p in points) {
                l = min(l, p.x); t = min(t, p.y); r = max(r, p.x); b = max(b, p.y)
            }
            return Box(l, t, r, b)
        }
    }
}

/** A rectangle of whole pixels, half open: columns [left, right), rows [top, bottom). */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun clampTo(w: Int, h: Int) = PixelRect(left.coerceIn(0, w), top.coerceIn(0, h), right.coerceIn(0, w), bottom.coerceIn(0, h))

    fun outset(d: Int) = PixelRect(left - d, top - d, right + d, bottom + d)

    fun contains(x: Int, y: Int) = x >= left && x < right && y >= top && y < bottom
}

/** The tools of the annotator's toolbar. Which of them need the premium feature is [AnnotatorGate]'s table. */
enum class AnnotTool(val id: String) {
    SELECT("select"),
    PEN("pen"),
    HIGHLIGHTER("highlighter"),
    ARROW("arrow"),
    RECT("rect"),
    ELLIPSE("ellipse"),
    TEXT("text"),
    ERASER("eraser"),
    CROP("crop"),

    /** A solid, opaque box: the strongest redaction (nothing of the pixels under it survives). */
    REDACT_SOLID("redact_solid"),
    BLUR("blur"),
    PIXELATE("pixelate"),

    /** Numbered step markers (1, 2, 3 ...). */
    STEP("step"),

    /** Dims everything outside the boxes the user draws. */
    SPOTLIGHT("spotlight");

    /** The tool covers pixels for good (blur, pixelate, solid box). */
    val redacts: Boolean get() = this == REDACT_SOLID || this == BLUR || this == PIXELATE

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/** How a redaction changes the pixels under it. All three are applied to the pixels themselves before export. */
enum class RedactMode(val id: String) {
    BLUR("blur"), PIXELATE("pixelate"), SOLID("solid");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/** The area a redaction covers: a box, or a freehand brush path (every pixel within [radius] of the path). */
sealed interface RedactArea {
    data class Rect(val box: Box) : RedactArea

    data class Brush(val points: List<Pt>, val radius: Float) : RedactArea {
        init {
            require(points.isNotEmpty()) { "a brush area needs a point" }
            require(radius > 0f) { "radius $radius" }
        }
    }

    /** The box of the area before any safety margin. */
    fun bounds(): Box = when (this) {
        is Rect -> box.normalized()
        is Brush -> Box.around(points)!!.outset(radius)
    }
}

enum class ShapeKind(val id: String) {
    RECT("rect"), ELLIPSE("ellipse");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/**
 * One object drawn over the picture. While editing they stay separate (undo, select, move, delete); the export
 * flattens them into new pixels ([AnnotationExporter]). Colours are ARGB ints, sizes image pixels.
 */
sealed interface Annotation {
    val id: Int

    /** A pen or highlighter stroke. A highlighter is drawn translucent ([HIGHLIGHT_ALPHA]). */
    data class Ink(override val id: Int, val points: List<Pt>, val color: Int, val width: Float, val highlighter: Boolean = false) : Annotation

    data class Arrow(override val id: Int, val from: Pt, val to: Pt, val color: Int, val width: Float) : Annotation

    data class Shape(override val id: Int, val kind: ShapeKind, val box: Box, val color: Int, val width: Float) : Annotation

    /** A text label whose top-start corner is [at]; [backdrop] draws a contrasting plate behind it (else an outline). */
    data class Label(override val id: Int, val at: Pt, val text: String, val color: Int, val size: Float, val backdrop: Boolean = true) : Annotation

    /** A numbered step marker centred on [at]. */
    data class Step(override val id: Int, val at: Pt, val number: Int, val color: Int, val radius: Float) : Annotation

    /** Everything outside [box] is dimmed. */
    data class Spotlight(override val id: Int, val box: Box) : Annotation

    /** Pixels under [area] are blurred, pixelated or filled with [color] (opaque) for good, before anything is drawn. */
    data class Redaction(override val id: Int, val mode: RedactMode, val area: RedactArea, val color: Int = SOLID_DEFAULT) : Annotation

    companion object {
        const val HIGHLIGHT_ALPHA = 0.38f
        const val SOLID_DEFAULT = 0xFF000000.toInt()
        const val MAX_TEXT = 200
    }
}

/**
 * The annotated picture: the working bitmap's size, the objects in drawing order, and an optional crop (pixels outside
 * it are discarded by the export). [nextId] gives each new object its id.
 */
data class AnnotDoc(
    val width: Int,
    val height: Int,
    val items: List<Annotation> = emptyList(),
    val crop: PixelRect? = null,
    val nextId: Int = 1,
) {
    init {
        require(width > 0 && height > 0) { "size $width x $height" }
    }

    val redactions: List<Annotation.Redaction> get() = items.filterIsInstance<Annotation.Redaction>()

    /** The part of the picture the export keeps: the crop, clamped, or the whole picture. */
    val outputRect: PixelRect
        get() = crop?.clampTo(width, height)?.takeIf { !it.isEmpty } ?: PixelRect(0, 0, width, height)

    fun with(item: Annotation) = copy(items = items + item, nextId = max(nextId, item.id + 1))

    companion object {
        /** An annotation holds at most this many objects (keeps the draft, the undo history and drawing bounded). */
        const val MAX_ITEMS = 400

        /** A stroke keeps at most this many points. */
        const val MAX_POINTS = 4000
    }
}
