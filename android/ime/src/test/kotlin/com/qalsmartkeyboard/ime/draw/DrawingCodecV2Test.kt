package com.qalsmartkeyboard.ime.draw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** T8.15: the version-2 format (brushes, objects, dotted paper), its limits, and that a bad or newer file never throws. */
class DrawingCodecV2Test {
    private fun rich() = Drawing(
        width = 360f,
        height = 240f,
        background = DrawBackground(0xFF263238.toInt(), BackgroundPattern.DOTS),
        strokes = listOf(
            Stroke(listOf(Pt(1f, 2f), Pt(30f, 40f)), 0xFFFF0000.toInt(), 6f, DrawTool.PEN, Brush.MARKER),
            Stroke(listOf(Pt(5f, 5f), Pt(9f, 9f), Pt(40f, 20f)), 0xFF00FF00.toInt(), 3f, DrawTool.PEN, Brush.CALLIGRAPHY),
            Stroke(listOf(Pt(5f, 5f)), 0xFF0000FF.toInt(), 2f, DrawTool.HIGHLIGHTER),
            Stroke(listOf(Pt(0f, 0f), Pt(50f, 50f)), 0, 14f, DrawTool.ERASER),
        ),
        meta = mapOf("qal.created" to "123456789"),
        objects = listOf(
            TextObject(0, "ሰላም ዓለም hello\nline two", 20f, 30.5f, TextSize.LARGE, 0xFF112233.toInt(), true, 180f),
            ShapeObject(0, ShapeKind.ARROW, 1f, 2f, 3f, 4f, 0xFFABCDEF.toInt(), 6f, false),
            ShapeObject(0, ShapeKind.ELLIPSE, 10f, 20f, 110f, 90f, 0xFF000000.toInt(), 2f, true),
        ),
    )

    @Test fun theVersionIsTwoAndOldVersionOneIsStillRead() {
        assertEquals(2, DrawingCodec.VERSION)
        assertEquals(1, DrawingCodec.OLDEST_VERSION)
    }

    @Test fun aRichDrawingRoundTripsExactly() {
        val bytes = DrawingCodec.encode(rich())
        val back = (DrawingCodec.decode(bytes) as DecodeResult.Ok).drawing
        assertEquals(rich(), back)
        assertEquals("the same bytes again", bytes.toList(), DrawingCodec.encode(back).toList())
    }

    @Test fun everyBrushToolAndPatternIsPersisted() {
        for (b in Brush.entries) {
            val d = Drawing(10f, 10f, strokes = listOf(Stroke(listOf(Pt(1f, 1f), Pt(2f, 2f)), 1, 2f, DrawTool.PEN, b)))
            assertEquals(b, (DrawingCodec.decode(DrawingCodec.encode(d)) as DecodeResult.Ok).drawing.strokes.single().brush)
        }
        for (p in BackgroundPattern.entries) {
            val d = Drawing(10f, 10f, DrawBackground(0xFFFFFFFF.toInt(), p))
            assertEquals(p, (DrawingCodec.decode(DrawingCodec.encode(d)) as DecodeResult.Ok).drawing.background.pattern)
        }
        for (bg in DrawBackground.PRESETS) {
            assertEquals(bg, (DrawingCodec.decode(DrawingCodec.encode(Drawing(10f, 10f, bg))) as DecodeResult.Ok).drawing.background)
        }
    }

    @Test fun aDrawingWithoutObjectsWritesNoObjectBlock() {
        val plain = DrawingCodec.encode(Drawing(10f, 10f))
        val withText = DrawingCodec.encode(Drawing(10f, 10f, objects = listOf(TextObject(0, "a", 1f, 1f))))
        assertTrue(withText.size > plain.size)
    }

    @Test fun aNewerVersionIsUnsupportedNotCorrupt() {
        val bytes = DrawingCodec.encode(rich())
        bytes[4] = 0
        bytes[5] = 3
        val r = DrawingCodec.decode(bytes)
        assertTrue(r is DecodeResult.Unsupported)
        assertEquals(3, (r as DecodeResult.Unsupported).version)
        bytes[5] = 0
        assertEquals("version 0 never existed", DecodeResult.Corrupt, DrawingCodec.decode(bytes))
    }

    @Test fun garbageEmptyAndEveryTruncationAreCorruptAndNeverThrow() {
        assertEquals(DecodeResult.Corrupt, DrawingCodec.decode(ByteArray(0)))
        assertEquals(DecodeResult.Corrupt, DrawingCodec.decode(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)))
        val bytes = DrawingCodec.encode(rich())
        for (n in 0 until bytes.size) {
            val r = DrawingCodec.decode(bytes.copyOf(n))
            assertTrue("cut at $n gave $r", r is DecodeResult.Corrupt)
        }
        // A flipped byte anywhere must not crash the reader (it may still decode, or be Corrupt).
        for (i in bytes.indices) {
            val copy = bytes.copyOf()
            copy[i] = (copy[i].toInt() xor 0xFF).toByte()
            DrawingCodec.decode(copy)
        }
    }

    private fun header(o: DataOutputStream, blocks: Int) {
        o.writeInt(0x51445257); o.writeShort(2); o.writeShort(0)
        o.writeFloat(50f); o.writeFloat(50f); o.writeInt(-1); o.writeByte(0)
        o.writeShort(0) // no meta
        o.writeInt(0) // no strokes
        o.writeShort(blocks)
    }

    private fun shapePayload(width: Float, x: Float): ByteArray {
        val s = ByteArrayOutputStream()
        DataOutputStream(s).use { p ->
            p.writeByte(0); p.writeByte(0); p.writeInt(0xFF000000.toInt()); p.writeFloat(width)
            p.writeFloat(x); p.writeFloat(0f); p.writeFloat(10f); p.writeFloat(10f)
        }
        return s.toByteArray()
    }

    @Test fun anUnknownBlockAndAnUnknownObjectTypeAreSkipped() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { o ->
            header(o, 2)
            o.writeShort(99); o.writeInt(3); o.write(byteArrayOf(1, 2, 3))
            val objs = ByteArrayOutputStream()
            DataOutputStream(objs).use { b ->
                b.writeShort(2)
                b.writeByte(7); b.writeInt(2); b.write(byteArrayOf(9, 9))
                val shape = shapePayload(4f, 0f)
                b.writeByte(1); b.writeInt(shape.size); b.write(shape)
            }
            o.writeShort(1); o.writeInt(objs.size()); objs.writeTo(o)
        }
        val back = (DrawingCodec.decode(out.toByteArray()) as DecodeResult.Ok).drawing
        assertEquals(1, back.objects.size)
        assertEquals(ShapeObject(0, ShapeKind.LINE, 0f, 0f, 10f, 10f, 0xFF000000.toInt(), 4f, false), back.objects.single())
    }

    private fun withShape(width: Float, x: Float): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { o ->
            header(o, 1)
            val objs = ByteArrayOutputStream()
            DataOutputStream(objs).use { b ->
                b.writeShort(1)
                val s = shapePayload(width, x)
                b.writeByte(1); b.writeInt(s.size); b.write(s)
            }
            o.writeShort(1); o.writeInt(objs.size()); objs.writeTo(o)
        }
        return out.toByteArray()
    }

    @Test fun impossibleObjectsAreCorrupt() {
        assertTrue(DrawingCodec.decode(withShape(4f, 5f)) is DecodeResult.Ok)
        assertEquals(DecodeResult.Corrupt, DrawingCodec.decode(withShape(0f, 5f)))
        assertEquals(DecodeResult.Corrupt, DrawingCodec.decode(withShape(4f, Float.NaN)))
        assertEquals(DecodeResult.Corrupt, DrawingCodec.decode(withShape(4f, 1e9f)))
    }

    @Test fun textIsCutAtTheLimitOnWrite() {
        val long = "x".repeat(TextObject.MAX_CHARS + 50)
        val back = (DrawingCodec.decode(DrawingCodec.encode(Drawing(10f, 10f, objects = listOf(TextObject(0, long, 1f, 1f))))) as DecodeResult.Ok).drawing
        assertEquals(TextObject.MAX_CHARS, (back.objects.single() as TextObject).text.length)
    }

    @Test fun theObjectLimitIsHeldOnWrite() {
        val many = (0 until DrawSession.MAX_OBJECTS + 20).map { ShapeObject(0, ShapeKind.LINE, 0f, 0f, 1f, 1f) }
        val back = (DrawingCodec.decode(DrawingCodec.encode(Drawing(10f, 10f, objects = many))) as DecodeResult.Ok).drawing
        assertEquals(DrawSession.MAX_OBJECTS, back.objects.size)
    }
}
