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
 * int   strokeCount, then per stroke: byte tool, [version 2: byte brush,] int ARGB, float width, int pointCount, then per point: float x, float y, byte pressure
 * short blockCount, then per block: short id, int length, bytes     (unknown blocks are skipped)
 *   block 1 = objects (version 2): short count, then per object: byte type, int length, bytes (unknown types are skipped)
 *     type 0 text:  float x, y, boxWidth; byte size; byte flags (bit 0 bold); int ARGB; UTF text
 *     type 1 shape: byte kind; byte flags (bit 0 filled); int ARGB; float width; float x1, y1, x2, y2
 * ```
 *
 * Version 1 (T8.4) had no brush byte and no blocks; this reader still loads it unchanged (every stroke is a plain pen stroke,
 * there are no objects), and the writer always writes the current version. A reader rejects a newer file as
 * [DecodeResult.Unsupported] rather than guessing, and skips unknown blocks and unknown object types of its own version.
 * Nothing is logged.
 */
object DrawingCodec {
    const val VERSION = 2

    /** The oldest version this reader still loads. */
    const val OLDEST_VERSION = 1
    private const val MAGIC = 0x51445257
    const val MAX_STROKES = 50_000
    const val MAX_POINTS = 2_000_000
    private const val MAX_SIDE = 20_000f
    private const val MAX_META = 64
    private const val POINT_BYTES = 9
    private const val BLOCK_OBJECTS = 1
    private const val OBJ_TEXT = 0
    private const val OBJ_SHAPE = 1

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
                o.writeByte(if (s.tool == DrawTool.PEN) s.brush.code else Brush.PEN.code)
                o.writeInt(s.colour)
                o.writeFloat(s.widthDp)
                o.writeInt(s.points.size)
                for (p in s.points) {
                    o.writeFloat(p.x)
                    o.writeFloat(p.y)
                    o.writeByte((p.pressure.coerceIn(0f, 1f) * 255f).roundToInt())
                }
            }
            val objects = d.objects.take(DrawSession.MAX_OBJECTS)
            if (objects.isEmpty()) {
                o.writeShort(0)
            } else {
                val block = ByteArrayOutputStream()
                DataOutputStream(block).use { b ->
                    b.writeShort(objects.size)
                    for (obj in objects) {
                        val payload = ByteArrayOutputStream()
                        val type = DataOutputStream(payload).use { p -> writeObject(p, obj) }
                        b.writeByte(type)
                        b.writeInt(payload.size())
                        payload.writeTo(b)
                    }
                }
                o.writeShort(1)
                o.writeShort(BLOCK_OBJECTS)
                o.writeInt(block.size())
                block.writeTo(o)
            }
        }
        return bytes.toByteArray()
    }

    /** Writes the payload of [obj]; returns its type byte. */
    private fun writeObject(o: DataOutputStream, obj: DrawObject): Int {
        when (obj) {
            is TextObject -> {
                o.writeFloat(obj.x)
                o.writeFloat(obj.y)
                o.writeFloat(obj.boxWidth)
                o.writeByte(obj.size.code)
                o.writeByte(if (obj.bold) 1 else 0)
                o.writeInt(obj.colour)
                o.writeUTF(obj.text.take(TextObject.MAX_CHARS))
                return OBJ_TEXT
            }
            is ShapeObject -> {
                o.writeByte(obj.kind.code)
                o.writeByte(if (obj.filled) 1 else 0)
                o.writeInt(obj.colour)
                o.writeFloat(obj.widthDp)
                o.writeFloat(obj.x1)
                o.writeFloat(obj.y1)
                o.writeFloat(obj.x2)
                o.writeFloat(obj.y2)
                return OBJ_SHAPE
            }
        }
    }

    fun decode(data: ByteArray): DecodeResult {
        try {
            val i = DataInputStream(ByteArrayInputStream(data))
            if (i.readInt() != MAGIC) return DecodeResult.Corrupt
            val version = i.readUnsignedShort()
            if (version > VERSION) return DecodeResult.Unsupported(version)
            if (version < OLDEST_VERSION) return DecodeResult.Corrupt
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
                val brush = if (version >= 2) (Brush.fromCode(i.readUnsignedByte()) ?: return DecodeResult.Corrupt) else Brush.PEN
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
                strokes += Stroke(pts, colour, width, tool, brush)
            }
            var objects: List<DrawObject> = emptyList()
            val blocks = i.readUnsignedShort()
            repeat(blocks) {
                val id = i.readUnsignedShort()
                val len = i.readInt()
                if (len < 0 || len > i.available()) return DecodeResult.Corrupt
                if (id == BLOCK_OBJECTS && version >= 2) {
                    val raw = ByteArray(len)
                    i.readFully(raw)
                    objects = readObjects(DataInputStream(ByteArrayInputStream(raw))) ?: return DecodeResult.Corrupt
                } else {
                    i.skipBytes(len)
                }
            }
            return DecodeResult.Ok(Drawing(w, h, DrawBackground(bg, pattern), strokes, meta, objects))
        } catch (e: IOException) {
            return DecodeResult.Corrupt
        } catch (e: RuntimeException) {
            return DecodeResult.Corrupt
        }
    }

    /** The objects of the block, or null when it is damaged. */
    private fun readObjects(i: DataInputStream): List<DrawObject>? {
        val count = i.readUnsignedShort()
        if (count > DrawSession.MAX_OBJECTS) return null
        val out = ArrayList<DrawObject>(count)
        repeat(count) {
            val type = i.readUnsignedByte()
            val len = i.readInt()
            if (len < 0 || len > i.available()) return null
            val raw = ByteArray(len)
            i.readFully(raw)
            val p = DataInputStream(ByteArrayInputStream(raw))
            when (type) {
                OBJ_TEXT -> {
                    val x = p.readFloat()
                    val y = p.readFloat()
                    val box = p.readFloat()
                    val size = TextSize.fromCode(p.readUnsignedByte()) ?: return null
                    val bold = (p.readUnsignedByte() and 1) != 0
                    val colour = p.readInt()
                    val text = p.readUTF().take(TextObject.MAX_CHARS)
                    if (!okCoord(x) || !okCoord(y) || !box.isFinite() || box < 1f || box > MAX_SIDE) return null
                    out += TextObject(0, text, x, y, size, colour, bold, box)
                }
                OBJ_SHAPE -> {
                    val kind = ShapeKind.fromCode(p.readUnsignedByte()) ?: return null
                    val filled = (p.readUnsignedByte() and 1) != 0
                    val colour = p.readInt()
                    val width = p.readFloat()
                    val x1 = p.readFloat()
                    val y1 = p.readFloat()
                    val x2 = p.readFloat()
                    val y2 = p.readFloat()
                    if (!width.isFinite() || width <= 0f || width > 1000f) return null
                    if (!okCoord(x1) || !okCoord(y1) || !okCoord(x2) || !okCoord(y2)) return null
                    out += ShapeObject(0, kind, x1, y1, x2, y2, colour, width, filled)
                }
                else -> Unit // a type this build does not know: skipped
            }
        }
        return out
    }

    private fun okCoord(v: Float) = v.isFinite() && abs(v) <= 1e6f
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
