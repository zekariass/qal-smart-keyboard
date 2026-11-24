package com.qalsmartkeyboard.ime.draw

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** The result of reading a saved drawing: it never throws, a bad file is a [Failure]. */
sealed interface DecodeResult {
    class Ok(val drawing: Drawing) : DecodeResult

    /** The file is not a drawing, is cut short, or holds impossible values. */
    object Corrupt : DecodeResult

    /** A newer version of the app wrote it ([version] > [DrawingCodec.VERSION]); this build cannot edit it safely. */
    class Unsupported(val version: Int) : DecodeResult
}

/**
 * The compact, versioned binary format of a saved drawing (the gallery's `.qdraw` file), big-endian:
 *
 * ```
 * int   magic 'QDRW'        short version      short flags (0)
 * float width, height       int   background ARGB      byte pattern
 * short metaCount, then metaCount x (UTF key, UTF value)
 * int   strokeCount, then per stroke: byte tool, int ARGB, float width, int pointCount, then per point: float x, float y, byte pressure
 * short blockCount, then per block: short id, int length, bytes     (unknown blocks are skipped)
 * ```
 *
 * Phase 2 (text, shapes) and the AI phase add new blocks or bump the version; a reader of an older version rejects a newer
 * file as [DecodeResult.Unsupported] rather than guessing, and skips unknown blocks of its own version. Nothing is logged.
 */
object DrawingCodec {
    const val VERSION = 1
    private const val MAGIC = 0x51445257
    const val MAX_STROKES = 50_000
    const val MAX_POINTS = 2_000_000
    private const val MAX_SIDE = 20_000f
    private const val MAX_META = 64
    private const val POINT_BYTES = 9

    fun encode(d: Drawing): ByteArray {
        val bytes = ByteArrayOutputStream(256 + d.strokes.sumOf { it.points.size } * POINT_BYTES)
        DataOutputStream(bytes).use { o ->
            o.writeInt(MAGIC)
            o.writeShort(VERSION)
            o.writeShort(0)
            o.writeFloat(d.width)
            o.writeFloat(d.height)
            o.writeInt(d.background.colour)
            o.writeByte(d.background.pattern.code)
            val meta = d.meta.entries.take(MAX_META)
            o.writeShort(meta.size)
            for ((k, v) in meta) {
                o.writeUTF(k.take(MAX_META_TEXT))
                o.writeUTF(v.take(MAX_META_TEXT))
            }
            o.writeInt(d.strokes.size)
            for (s in d.strokes) {
                o.writeByte(s.tool.code)
                o.writeInt(s.colour)
                o.writeFloat(s.widthDp)
                o.writeInt(s.points.size)
                for (p in s.points) {
                    o.writeFloat(p.x)
                    o.writeFloat(p.y)
                    o.writeByte((p.pressure.coerceIn(0f, 1f) * 255f).roundToInt())
                }
            }
            o.writeShort(0) // no extension blocks in version 1
        }
        return bytes.toByteArray()
    }

    fun decode(data: ByteArray): DecodeResult {
        try {
            val i = DataInputStream(ByteArrayInputStream(data))
            if (i.readInt() != MAGIC) return DecodeResult.Corrupt
            val version = i.readUnsignedShort()
            if (version > VERSION) return DecodeResult.Unsupported(version)
            if (version < 1) return DecodeResult.Corrupt
            i.readUnsignedShort() // flags
            val w = i.readFloat()
            val h = i.readFloat()
            if (!okSide(w) || !okSide(h)) return DecodeResult.Corrupt
            val bg = i.readInt()
            val pattern = BackgroundPattern.fromCode(i.readUnsignedByte()) ?: return DecodeResult.Corrupt
            val metaCount = i.readUnsignedShort()
            if (metaCount > MAX_META) return DecodeResult.Corrupt
            val meta = LinkedHashMap<String, String>()
            repeat(metaCount) { meta[i.readUTF()] = i.readUTF() }
            val strokeCount = i.readInt()
            if (strokeCount < 0 || strokeCount > MAX_STROKES) return DecodeResult.Corrupt
            val strokes = ArrayList<Stroke>(minOf(strokeCount, 1024))
            var totalPoints = 0
            repeat(strokeCount) {
                val tool = DrawTool.fromCode(i.readUnsignedByte()) ?: return DecodeResult.Corrupt
                val colour = i.readInt()
                val width = i.readFloat()
                if (!width.isFinite() || width <= 0f || width > 1000f) return DecodeResult.Corrupt
                val n = i.readInt()
                if (n < 0 || n > MAX_POINTS - totalPoints || n.toLong() * POINT_BYTES > i.available()) return DecodeResult.Corrupt
                totalPoints += n
                val pts = ArrayList<Pt>(n)
                repeat(n) {
                    val x = i.readFloat()
                    val y = i.readFloat()
                    if (!x.isFinite() || !y.isFinite() || abs(x) > 1e6f || abs(y) > 1e6f) return DecodeResult.Corrupt
                    pts += Pt(x, y, i.readUnsignedByte() / 255f)
                }
                strokes += Stroke(pts, colour, width, tool)
            }
            val blocks = i.readUnsignedShort()
            repeat(blocks) {
                i.readUnsignedShort()
                val len = i.readInt()
                if (len < 0 || len > i.available()) return DecodeResult.Corrupt
                i.skipBytes(len)
            }
            return DecodeResult.Ok(Drawing(w, h, DrawBackground(bg, pattern), strokes, meta))
        } catch (e: IOException) {
            return DecodeResult.Corrupt
        } catch (e: RuntimeException) {
            return DecodeResult.Corrupt
        }
    }

    private fun okSide(v: Float) = v.isFinite() && v >= 1f && v <= MAX_SIDE
    private const val MAX_META_TEXT = 4000
}

/**
 * Deterministic stroke smoothing and simplification, kept free of Android so it is unit-tested.
 *
 * The curve through the sampled points is the midpoint quadratic: a quadratic Bezier from the midpoint of each pair to the
 * next, with the sampled point as the control point. It passes through the first and last point, is continuous and needs no
 * look-ahead, so the incremental drawing of a live stroke and the replay of a finished one produce the same ink.
 */
object Smoothing {
    /**
     * The flat path of [points]: `[x0, y0, cx1, cy1, ex1, ey1, cx2, cy2, ex2, ey2, ...]` (a start, then control + end pairs, the
     * last end being the last point). Fewer than two points give just the start (or nothing).
     */
    fun quadPath(points: List<Pt>): FloatArray {
        val n = points.size
        if (n == 0) return FloatArray(0)
        if (n == 1) return floatArrayOf(points[0].x, points[0].y)
        if (n == 2) return floatArrayOf(points[0].x, points[0].y, points[0].x, points[0].y, points[1].x, points[1].y)
        val out = FloatArray(2 + 4 * (n - 1))
        out[0] = points[0].x
        out[1] = points[0].y
        var o = 2
        for (k in 1 until n - 1) {
            out[o++] = points[k].x
            out[o++] = points[k].y
            out[o++] = (points[k].x + points[k + 1].x) / 2f
            out[o++] = (points[k].y + points[k + 1].y) / 2f
        }
        // The final segment ends exactly on the last sampled point.
        out[o++] = points[n - 1].x
        out[o++] = points[n - 1].y
        out[o++] = points[n - 1].x
        out[o] = points[n - 1].y
        return out
    }

    /** Ramer-Douglas-Peucker: drops points within [epsilon] of the line through their neighbours; keeps first and last. */
    fun simplify(points: List<Pt>, epsilon: Float): List<Pt> {
        val n = points.size
        if (n < 3 || epsilon <= 0f) return points
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = ArrayList<IntArray>()
        stack += intArrayOf(0, n - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeAt(stack.size - 1)
            var worst = -1f
            var at = -1
            for (k in a + 1 until b) {
                val dist = distanceToSegment(points[k], points[a], points[b])
                if (dist > worst) {
                    worst = dist
                    at = k
                }
            }
            if (at >= 0 && worst > epsilon) {
                keep[at] = true
                stack += intArrayOf(a, at)
                stack += intArrayOf(at, b)
            }
        }
        val out = ArrayList<Pt>(n)
        for (k in 0 until n) if (keep[k]) out += points[k]
        return out
    }

    private fun distanceToSegment(p: Pt, a: Pt, b: Pt): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }
}
