package com.qalsmartkeyboard.app.creative.annotate

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Measures a text label in image pixels (the box its text takes). The app measures with the real text layout. */
fun interface LabelMeasurer {
    fun measure(label: Annotation.Label): Box

    companion object {
        /** An estimate for plain JVM use: 0.6 of the size per character, 1.25 of it per line, plus the plate's padding. */
        val Estimate = LabelMeasurer { l ->
            val lines = l.text.split('\n')
            val w = max(1, lines.maxOf { it.length }) * l.size * 0.6f
            val h = lines.size * l.size * 1.25f
            val pad = l.size * LABEL_PAD
            Box(l.at.x, l.at.y, l.at.x + w + 2 * pad, l.at.y + h + 2 * pad)
        }

        /** The plate around a label's text, as a fraction of its size. */
        const val LABEL_PAD = 0.3f
    }
}

/** Hit testing, bounds and moving of annotations (T8.7), in image pixels. Pure, JVM-tested. */
object Geometry {
    fun dist2ToSegment(px: Float, py: Float, a: Pt, b: Pt): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((px - a.x) * dx + (py - a.y) * dy) / len2).coerceIn(0f, 1f)
        val cx = a.x + t * dx - px
        val cy = a.y + t * dy - py
        return cx * cx + cy * cy
    }

    private fun nearPolyline(p: Pt, pts: List<Pt>, tol: Float): Boolean {
        val t2 = tol * tol
        if (pts.size == 1) return dist2ToSegment(p.x, p.y, pts[0], pts[0]) <= t2
        for (i in 0 until pts.size - 1) if (dist2ToSegment(p.x, p.y, pts[i], pts[i + 1]) <= t2) return true
        return false
    }

    /** Whether [p] touches [a], with [slop] image pixels of tolerance (a finger is wide). */
    fun hits(a: Annotation, p: Pt, slop: Float, measure: LabelMeasurer): Boolean = when (a) {
        is Annotation.Ink -> nearPolyline(p, a.points, a.width / 2 + slop)
        is Annotation.Arrow -> nearPolyline(p, listOf(a.from, a.to), a.width / 2 + slop)
        is Annotation.Shape -> {
            val b = a.box.normalized()
            when (a.kind) {
                // A frame is picked by its border, or inside when it is small (a thin frame is hard to hit).
                ShapeKind.RECT -> b.contains(p, slop) && (b.minSide < 4 * slop || !b.outset(-(a.width / 2 + slop)).contains(p))
                ShapeKind.ELLIPSE -> {
                    val rx = max(b.width / 2, 1f)
                    val ry = max(b.height / 2, 1f)
                    val nx = (p.x - (b.left + rx)) / rx
                    val ny = (p.y - (b.top + ry)) / ry
                    val d = kotlin.math.sqrt(nx * nx + ny * ny) // 1 on the outline
                    val tol = (a.width / 2 + slop) / min(rx, ry)
                    abs(d - 1f) <= tol || (b.minSide < 4 * slop && d <= 1f)
                }
            }
        }
        is Annotation.Label -> measure.measure(a).contains(p, slop)
        is Annotation.Step -> dist2ToSegment(p.x, p.y, a.at, a.at) <= (a.radius + slop) * (a.radius + slop)
        is Annotation.Spotlight -> a.box.normalized().contains(p, slop)
        is Annotation.Redaction -> when (val area = a.area) {
            is RedactArea.Rect -> area.box.normalized().contains(p, slop)
            is RedactArea.Brush -> nearPolyline(p, area.points, area.radius + slop)
        }
    }

    /** The topmost object at [p] (the last drawn wins), or null. */
    fun topmostAt(items: List<Annotation>, p: Pt, slop: Float, measure: LabelMeasurer): Annotation? =
        items.lastOrNull { hits(it, p, slop, measure) }

    /** The box [a] covers. */
    fun bounds(a: Annotation, measure: LabelMeasurer): Box = when (a) {
        is Annotation.Ink -> Box.around(a.points)!!.outset(a.width / 2)
        is Annotation.Arrow -> Box.of(a.from, a.to).outset(a.width * ARROW_HEAD)
        is Annotation.Shape -> a.box.normalized().outset(a.width / 2)
        is Annotation.Label -> measure.measure(a)
        is Annotation.Step -> Box(a.at.x - a.radius, a.at.y - a.radius, a.at.x + a.radius, a.at.y + a.radius)
        is Annotation.Spotlight -> a.box.normalized()
        is Annotation.Redaction -> a.area.bounds()
    }

    /** [a] moved by ([dx], [dy]). */
    fun moved(a: Annotation, dx: Float, dy: Float): Annotation {
        fun Pt.m() = Pt(x + dx, y + dy)
        return when (a) {
            is Annotation.Ink -> a.copy(points = a.points.map { it.m() })
            is Annotation.Arrow -> a.copy(from = a.from.m(), to = a.to.m())
            is Annotation.Shape -> a.copy(box = a.box.offset(dx, dy))
            is Annotation.Label -> a.copy(at = a.at.m())
            is Annotation.Step -> a.copy(at = a.at.m())
            is Annotation.Spotlight -> a.copy(box = a.box.offset(dx, dy))
            is Annotation.Redaction -> a.copy(
                area = when (val area = a.area) {
                    is RedactArea.Rect -> area.copy(box = area.box.offset(dx, dy))
                    is RedactArea.Brush -> area.copy(points = area.points.map { it.m() })
                },
            )
        }
    }

    /** [a] with every coordinate and size multiplied by [s] (the preview works on a smaller copy of the picture). */
    fun scaled(a: Annotation, s: Float): Annotation {
        if (s == 1f) return a
        fun Pt.k() = Pt(x * s, y * s)
        fun Box.k() = Box(left * s, top * s, right * s, bottom * s)
        return when (a) {
            is Annotation.Ink -> a.copy(points = a.points.map { it.k() }, width = a.width * s)
            is Annotation.Arrow -> a.copy(from = a.from.k(), to = a.to.k(), width = a.width * s)
            is Annotation.Shape -> a.copy(box = a.box.k(), width = a.width * s)
            is Annotation.Label -> a.copy(at = a.at.k(), size = a.size * s)
            is Annotation.Step -> a.copy(at = a.at.k(), radius = a.radius * s)
            is Annotation.Spotlight -> a.copy(box = a.box.k())
            is Annotation.Redaction -> a.copy(
                area = when (val area = a.area) {
                    is RedactArea.Rect -> RedactArea.Rect(area.box.k())
                    is RedactArea.Brush -> RedactArea.Brush(area.points.map { it.k() }, area.radius * s)
                },
            )
        }
    }

    /** An arrow head is this many stroke widths long. */
    const val ARROW_HEAD = 4f
}
