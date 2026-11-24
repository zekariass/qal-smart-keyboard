package com.qalsmartkeyboard.ime.draw

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Random

/** T8.4: the pure drawing model: strokes, undo / redo, smoothing, the saved format and the export plan. */
class DrawModelTest {
    private fun stroke(x: Float = 0f, tool: DrawTool = DrawTool.PEN, colour: Int = 0xFF112233.toInt(), w: Float = 4f) =
        Stroke(listOf(Pt(x, 0f), Pt(x + 10f, 5f, 0.5f), Pt(x + 20f, 0f)), colour, w, tool)

    // ---- session, undo / redo ------------------------------------------------------------------------------------

    @Test fun addUndoRedoClearRoundTrip() {
        val s = DrawSession(100f, 100f)
        assertTrue(s.isEmpty)
        assertFalse(s.canUndo)
        val a = stroke(0f)
        val b = stroke(30f)
        s.addStroke(a); s.addStroke(b)
        assertEquals(listOf(a, b), s.strokes)
        assertTrue(s.undo())
        assertEquals(listOf(a), s.strokes)
        assertTrue(s.canRedo)
        assertTrue(s.redo())
        assertEquals(listOf(a, b), s.strokes)
        s.clear()
        assertTrue(s.strokes.isEmpty())
        assertTrue(s.undo()) // the clear is one undoable step
        assertEquals(listOf(a, b), s.strokes)
        assertTrue(s.redo())
        assertTrue(s.strokes.isEmpty())
        assertFalse(s.redo())
    }

    @Test fun aNewStrokeClearsTheRedoSide() {
        val s = DrawSession(100f, 100f)
        s.addStroke(stroke(0f)); s.addStroke(stroke(30f))
        s.undo()
        assertTrue(s.canRedo)
        s.addStroke(stroke(60f))
        assertFalse(s.canRedo)
        assertFalse(s.redo())
        assertEquals(2, s.strokes.size)
    }

    @Test fun theHistoryIsBounded() {
        val s = DrawSession(100f, 100f, maxUndo = 3)
        repeat(5) { s.addStroke(stroke(it * 10f)) }
        var undone = 0
        while (s.undo()) undone++
        assertEquals(3, undone)
        assertEquals("the two oldest strokes stay", 2, s.strokes.size)
    }

    @Test fun undoStackDepthAndEmptyBehaviour() {
        val u = UndoStack<Int>(2)
        assertNull(u.undo()); assertNull(u.redo())
        u.push(1); u.push(2); u.push(3)
        assertEquals(2, u.undoDepth)
        assertEquals(3, u.undo()); assertEquals(1, u.redoDepth)
        u.push(9)
        assertEquals(0, u.redoDepth)
    }

    @Test fun clearOfAnEmptyCanvasAndAnEmptyStrokeDoNothing() {
        val s = DrawSession(100f, 100f)
        s.clear()
        s.addStroke(Stroke(emptyList(), 0, 1f))
        assertFalse(s.canUndo)
        assertEquals(0, s.revision)
    }

    @Test fun revisionAndDirtyTrackChanges() {
        val s = DrawSession(100f, 100f)
        assertFalse(s.dirty)
        s.addStroke(stroke())
        assertTrue(s.dirty)
        val r = s.revision
        s.markSaved()
        assertFalse(s.dirty)
        s.undo()
        assertTrue(s.revision > r)
        assertTrue(s.dirty)
    }

    @Test fun anEmptyCanvasAdoptsANewSizeButADrawnOneKeepsIt() {
        val s = DrawSession(100f, 100f)
        s.resizeIfEmpty(200f, 50f)
        assertEquals(200f, s.width); assertEquals(50f, s.height)
        s.addStroke(stroke())
        s.resizeIfEmpty(10f, 10f)
        assertEquals(200f, s.width)
    }

    @Test fun eraserOnlyDrawingCountsAsEmpty() {
        val s = DrawSession(100f, 100f)
        s.addStroke(stroke(tool = DrawTool.ERASER))
        assertTrue(s.isEmpty)
        assertTrue(s.snapshot().isEmpty)
    }

    @Test fun defaultInkContrastsTheBackground() {
        assertEquals(InkDefaults.BLACK, InkDefaults.inkFor(DrawBackground.WHITE))
        assertEquals(InkDefaults.WHITE, InkDefaults.inkFor(DrawBackground.BLACK))
        assertEquals(InkDefaults.BLACK, InkDefaults.inkFor(DrawBackground.TRANSPARENT))
        assertEquals(InkDefaults.BLACK, InkDefaults.inkFor(DrawBackground.CREAM))
        assertEquals(10, InkDefaults.PALETTE.size)
        assertEquals(4, InkDefaults.WIDTHS.size)
    }

    @Test fun plainPointListsExcludeEraserStrokes() {
        val d = Drawing(10f, 10f, strokes = listOf(stroke(0f), stroke(5f, DrawTool.ERASER), stroke(9f)))
        assertEquals(2, d.inkPointLists().size)
        assertTrue(d.strokes[1].pointList().isEmpty())
        assertEquals(3, d.strokes[0].pointList().size)
    }

    // ---- smoothing -----------------------------------------------------------------------------------------------

    @Test fun quadPathStartsAndEndsOnTheSampledPointsAndIsDeterministic() {
        val pts = listOf(Pt(0f, 0f), Pt(10f, 10f), Pt(20f, 0f), Pt(30f, 10f))
        val q = Smoothing.quadPath(pts)
        assertEquals(2 + 4 * 3, q.size)
        assertEquals(0f, q[0]); assertEquals(0f, q[1])
        assertEquals(30f, q[q.size - 2]); assertEquals(10f, q[q.size - 1])
        // The first segment is controlled by the second point and ends at the midpoint of the second and third.
        assertEquals(10f, q[2]); assertEquals(10f, q[3]); assertEquals(15f, q[4]); assertEquals(5f, q[5])
        assertArrayEquals(q, Smoothing.quadPath(pts), 0f)
    }

    @Test fun quadPathOfFewPoints() {
        assertEquals(0, Smoothing.quadPath(emptyList()).size)
        assertEquals(2, Smoothing.quadPath(listOf(Pt(1f, 2f))).size)
        val two = Smoothing.quadPath(listOf(Pt(0f, 0f), Pt(4f, 4f)))
        assertEquals(6, two.size)
        assertEquals(4f, two[4])
    }

    @Test fun simplifyKeepsEndsAndDropsCollinearPoints() {
        val line = (0..10).map { Pt(it * 10f, it * 5f) }
        val s = Smoothing.simplify(line, 0.5f)
        assertEquals(listOf(line.first(), line.last()), s)
        val bend = listOf(Pt(0f, 0f), Pt(50f, 0f), Pt(100f, 0f), Pt(100f, 50f), Pt(100f, 100f))
        val sb = Smoothing.simplify(bend, 1f)
        assertEquals(listOf(Pt(0f, 0f), Pt(100f, 0f), Pt(100f, 100f)), sb)
        assertEquals(bend, Smoothing.simplify(bend, 0f))
    }

    // ---- the saved format ----------------------------------------------------------------------------------------

    private fun sample() = Drawing(
        320.5f, 200f, DrawBackground(0xFFFFF8E1.toInt(), BackgroundPattern.GRID),
        listOf(stroke(0f), stroke(40f, DrawTool.HIGHLIGHTER, 0x66FFFF00, 12f), stroke(10f, DrawTool.ERASER, 0, 10f)),
        mapOf("k" to "v", "ünï" to "çode"),
    )

    @Test fun roundTripKeepsEverything() {
        val d = sample()
        val back = (DrawingCodec.decode(DrawingCodec.encode(d)) as DecodeResult.Ok).drawing
        assertEquals(d.width, back.width); assertEquals(d.height, back.height)
        assertEquals(d.background, back.background)
        assertEquals(d.meta, back.meta)
        assertEquals(d.strokes.size, back.strokes.size)
        for ((a, b) in d.strokes.zip(back.strokes)) {
            assertEquals(a.tool, b.tool); assertEquals(a.colour, b.colour); assertEquals(a.widthDp, b.widthDp)
            for ((p, q) in a.points.zip(b.points)) {
                assertEquals(p.x, q.x); assertEquals(p.y, q.y)
                assertEquals(p.pressure, q.pressure, 1f / 255f) // pressure is stored in a byte
            }
        }
    }

    @Test fun encodingIsDeterministicAndStartsWithTheMagicAndVersion() {
        val a = DrawingCodec.encode(sample())
        assertArrayEquals(a, DrawingCodec.encode(sample()))
        assertEquals("QDRW", String(a, 0, 4, Charsets.US_ASCII))
        assertEquals(0, a[4].toInt()); assertEquals(DrawingCodec.VERSION, a[5].toInt())
    }

    @Test fun aNewerVersionIsRejectedAsUnsupportedNotCorrupt() {
        val bytes = DrawingCodec.encode(sample())
        bytes[5] = 9
        val r = DrawingCodec.decode(bytes)
        assertTrue(r is DecodeResult.Unsupported)
        assertEquals(9, (r as DecodeResult.Unsupported).version)
    }

    @Test fun unknownTrailingBlocksOfThisVersionAreSkipped() {
        val o = ByteArrayOutputStream()
        val base = DrawingCodec.encode(Drawing(50f, 50f, strokes = listOf(stroke())))
        o.write(base, 0, base.size - 2) // drop the zero block count
        DataOutputStream(o).apply {
            writeShort(1); writeShort(77); writeInt(3); write(byteArrayOf(1, 2, 3)); flush()
        }
        val r = DrawingCodec.decode(o.toByteArray())
        assertTrue(r is DecodeResult.Ok)
        assertEquals(1, (r as DecodeResult.Ok).drawing.strokes.size)
    }

    @Test fun corruptInputIsRejectedWithoutACrash() {
        val good = DrawingCodec.encode(sample())
        assertEquals(DecodeResult.Corrupt, DrawingCodec.decode(ByteArray(0)))
        assertEquals(DecodeResult.Corrupt, DrawingCodec.decode("not a drawing at all".toByteArray()))
        for (cut in 0 until good.size) { // every truncation is Corrupt, never an exception
            assertTrue("cut $cut", DrawingCodec.decode(good.copyOf(cut)) is DecodeResult.Corrupt)
        }
        val rnd = Random(42)
        repeat(300) {
            val b = good.copyOf()
            repeat(1 + rnd.nextInt(6)) { b[6 + rnd.nextInt(b.size - 6)] = rnd.nextInt(256).toByte() }
            DrawingCodec.decode(b) // any result is fine, none may throw
        }
        // Huge counts are refused before any allocation.
        fun header(strokeCount: Int, body: DataOutputStream.() -> Unit = {}): ByteArray {
            val o = ByteArrayOutputStream()
            DataOutputStream(o).apply {
                writeInt(0x51445257); writeShort(1); writeShort(0); writeFloat(10f); writeFloat(10f); writeInt(-1); writeByte(0)
                writeShort(0); writeInt(strokeCount); body(); flush()
            }
            return o.toByteArray()
        }
        assertEquals(DecodeResult.Corrupt, DrawingCodec.decode(header(Int.MAX_VALUE)))
        assertEquals(DecodeResult.Corrupt, DrawingCodec.decode(header(-1)))
        assertEquals(DecodeResult.Corrupt, DrawingCodec.decode(header(1) { writeByte(0); writeInt(-1); writeFloat(2f); writeInt(Int.MAX_VALUE) }))
        assertEquals(DecodeResult.Corrupt, DrawingCodec.decode(header(1) { writeByte(7); writeInt(-1); writeFloat(2f); writeInt(0) })) // unknown tool
    }

    @Test fun nonFiniteSizesAndCoordinatesAreCorrupt() {
        val bad = Drawing(Float.NaN, 10f)
        assertEquals(DecodeResult.Corrupt, DrawingCodec.decode(DrawingCodec.encode(bad)))
        val inf = Drawing(10f, 10f, strokes = listOf(Stroke(listOf(Pt(Float.POSITIVE_INFINITY, 0f)), 0, 2f)))
        assertEquals(DecodeResult.Corrupt, DrawingCodec.decode(DrawingCodec.encode(inf)))
    }

    // ---- export plan ---------------------------------------------------------------------------------------------

    @Test fun planFitsTheLongestSideAndKeepsTheAspect() {
        val d = Drawing(400f, 200f)
        val p = ExportPlan.of(d, ExportOptions(longestSidePx = 1024, cropToContent = false))
        assertEquals(1024, p.widthPx); assertEquals(512, p.heightPx)
        assertEquals(2.56f, p.scale, 1e-4f)
        val tall = ExportPlan.of(Drawing(100f, 400f), ExportOptions(cropToContent = false))
        assertEquals(256, tall.widthPx); assertEquals(1024, tall.heightPx)
    }

    @Test fun planCropsToTheInkWithAMarginClippedToTheCanvas() {
        val d = Drawing(400f, 400f, strokes = listOf(Stroke(listOf(Pt(100f, 100f), Pt(200f, 150f)), 0xFF000000.toInt(), 10f)))
        val p = ExportPlan.of(d, ExportOptions(longestSidePx = 500, cropToContent = true, marginUnits = 20f))
        assertEquals(75f, p.src.left); assertEquals(75f, p.src.top)
        assertEquals(225f, p.src.right); assertEquals(175f, p.src.bottom)
        assertEquals(500, p.widthPx)
        assertEquals(333, p.heightPx)
        val edge = Drawing(100f, 100f, strokes = listOf(Stroke(listOf(Pt(2f, 2f), Pt(50f, 50f)), 0, 4f)))
        val pe = ExportPlan.of(edge, ExportOptions(cropToContent = true, marginUnits = 30f))
        assertEquals(0f, pe.src.left); assertEquals(0f, pe.src.top)
    }

    @Test fun anEmptyDrawingExportsTheWholeCanvas() {
        val p = ExportPlan.of(Drawing(300f, 150f), ExportOptions(longestSidePx = 600))
        assertEquals(Bounds(0f, 0f, 300f, 150f), p.src)
        assertEquals(600, p.widthPx); assertEquals(300, p.heightPx)
    }

    @Test fun highlighterBoundsGrowWithItsWiderStroke() {
        val pen = Drawing(100f, 100f, strokes = listOf(Stroke(listOf(Pt(50f, 50f)), 0, 4f)))
        val hl = Drawing(100f, 100f, strokes = listOf(Stroke(listOf(Pt(50f, 50f)), 0, 4f, DrawTool.HIGHLIGHTER)))
        assertTrue(hl.contentBounds()!!.width > pen.contentBounds()!!.width)
        assertNull(Drawing(10f, 10f).contentBounds())
    }
}
