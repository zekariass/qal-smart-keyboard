package com.qalsmartkeyboard.ime.draw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T8.15: text and shape objects in the session: placing, selecting, moving, deleting, undo / redo, the bounded history and hit tests. */
class DrawObjectsTest {
    private fun rect(x: Float = 10f, y: Float = 10f) = ShapeObject(0, ShapeKind.RECTANGLE, x, y, x + 40f, y + 30f, 0xFF112233.toInt(), 4f)
    private fun text(s: String = "Hello", x: Float = 5f, y: Float = 5f) = TextObject(0, s, x, y)
    private fun stroke() = Stroke(listOf(Pt(0f, 0f), Pt(9f, 9f)), 0, 2f)

    @Test fun anAddedObjectGetsAnIdIsSelectedAndIsOneUndoStep() {
        val s = DrawSession(200f, 100f)
        val a = s.addObject(rect())!!
        assertTrue(a.id > 0)
        assertEquals(a.id, s.selectedId)
        assertFalse(s.isEmpty)
        assertTrue(s.undo())
        assertTrue(s.objects.isEmpty())
        assertNull("the selection goes with the object", s.selectedId)
        assertTrue(s.redo())
        assertEquals(a.id, s.objects.single().id)
    }

    @Test fun objectsDoNotTouchTheInkRevisionButStrokesDo() {
        val s = DrawSession(200f, 100f)
        val ink = s.inkRevision
        s.addObject(rect())
        s.moveObject(s.objects.single().id, 5f, 5f)
        assertEquals(ink, s.inkRevision)
        s.addStroke(stroke())
        assertEquals(ink + 1, s.inkRevision)
    }

    @Test fun movingKeepsTheIdAndUndoPutsItBack() {
        val s = DrawSession(200f, 100f)
        val a = s.addObject(rect())!!
        assertTrue(s.moveObject(a.id, 7f, -3f))
        val moved = s.objects.single() as ShapeObject
        assertEquals(a.id, moved.id)
        assertEquals(17f, moved.x1, 0.001f)
        assertEquals(7f, moved.y1, 0.001f)
        s.undo()
        assertEquals(10f, (s.objects.single() as ShapeObject).x1, 0.001f)
        s.redo()
        assertEquals(17f, (s.objects.single() as ShapeObject).x1, 0.001f)
    }

    @Test fun replacingWithTheSameObjectIsNotAStep() {
        val s = DrawSession(200f, 100f)
        val a = s.addObject(rect())!!
        assertFalse(s.replaceObject(a))
        assertFalse(s.moveObject(a.id, 0f, 0f))
        assertFalse(s.replaceObject(rect().copy(id = 999)))
    }

    @Test fun deletingAnObjectIsUndoableAndRestoresItsPlaceInTheOrder() {
        val s = DrawSession(200f, 100f)
        val a = s.addObject(rect(0f, 0f))!!
        val b = s.addObject(text())!!
        val c = s.addObject(rect(50f, 50f))!!
        assertTrue(s.removeObject(b.id))
        assertEquals(listOf(a.id, c.id), s.objects.map { it.id })
        s.undo()
        assertEquals(listOf(a.id, b.id, c.id), s.objects.map { it.id })
        s.redo()
        assertEquals(listOf(a.id, c.id), s.objects.map { it.id })
        assertFalse(s.removeObject(12345))
    }

    @Test fun clearRemovesInkAndObjectsAndOneUndoBringsBothBack() {
        val s = DrawSession(200f, 100f)
        s.addStroke(stroke())
        s.addObject(rect())
        s.clear()
        assertTrue(s.strokes.isEmpty() && s.objects.isEmpty())
        assertNull(s.selectedId)
        s.undo()
        assertEquals(1, s.strokes.size)
        assertEquals(1, s.objects.size)
        s.redo()
        assertTrue(s.strokes.isEmpty() && s.objects.isEmpty())
    }

    @Test fun anEmptyCanvasWithOnlyEraserStrokesIsEmptyButObjectsMakeItNot() {
        val s = DrawSession(100f, 100f)
        s.addStroke(Stroke(listOf(Pt(1f, 1f)), 0, 5f, DrawTool.ERASER))
        assertTrue(s.isEmpty)
        s.addObject(text())
        assertFalse(s.isEmpty)
        assertFalse(s.snapshot().isEmpty)
    }

    @Test fun theHistoryIsBoundedAndStillWorksForObjects() {
        val s = DrawSession(200f, 100f, maxUndo = 3)
        val a = s.addObject(rect())!!
        repeat(5) { s.moveObject(a.id, 1f, 0f) }
        var undone = 0
        while (s.undo()) undone++
        assertEquals(3, undone)
        assertEquals("the oldest steps are forgotten, their effect stays", 12f, (s.objects.single() as ShapeObject).x1, 0.001f)
    }

    @Test fun theObjectLimitRefusesMoreWithoutBreakingTheSession() {
        val s = DrawSession(200f, 100f)
        repeat(DrawSession.MAX_OBJECTS) { assertNotNull(s.addObject(rect())) }
        assertNull(s.addObject(rect()))
        assertEquals(DrawSession.MAX_OBJECTS, s.objects.size)
    }

    @Test fun aLoadedDrawingKeepsItsObjectsWithFreshUniqueIds() {
        val s = DrawSession(200f, 100f, objects = listOf(rect(), text(), rect(60f, 60f)))
        assertEquals(3, s.objects.map { it.id }.toSet().size)
        assertTrue(s.objects.all { it.id > 0 })
        val next = s.addObject(rect())!!
        assertFalse(s.objects.dropLast(1).any { it.id == next.id })
        assertEquals(4, s.snapshot().objects.size)
    }

    @Test fun selectingPicksTheTopmostObjectAndIgnoresUnknownIds() {
        val s = DrawSession(300f, 200f)
        val low = s.addObject(ShapeObject(0, ShapeKind.RECTANGLE, 0f, 0f, 100f, 100f, filled = true))!!
        val high = s.addObject(ShapeObject(0, ShapeKind.RECTANGLE, 50f, 50f, 150f, 150f, filled = true))!!
        assertEquals(high.id, s.objectAt(75f, 75f, 4f)!!.id)
        assertEquals(low.id, s.objectAt(10f, 10f, 4f)!!.id)
        assertNull(s.objectAt(250f, 10f, 4f))
        s.select(low.id)
        assertEquals(low.id, s.selected()!!.id)
        s.select(777)
        assertNull("an unknown id selects nothing", s.selectedId)
        s.select(null)
        assertNull(s.selected())
    }

    @Test fun anOutlineIsPickedOnItsEdgeNotInsideAndAFilledShapeInside() {
        val outline = ShapeObject(0, ShapeKind.RECTANGLE, 0f, 0f, 100f, 60f, widthDp = 4f)
        assertTrue(outline.hit(0f, 30f, 3f))
        assertTrue(outline.hit(50f, 61f, 3f))
        assertFalse(outline.hit(50f, 30f, 3f))
        assertTrue(outline.copy(filled = true).hit(50f, 30f, 3f))
        val oval = ShapeObject(0, ShapeKind.ELLIPSE, 0f, 0f, 100f, 60f, widthDp = 4f)
        assertTrue(oval.hit(50f, 0f, 3f))
        assertFalse(oval.hit(50f, 30f, 3f))
        assertFalse(oval.hit(1f, 1f, 3f)) // the box corner is outside an ellipse
        assertTrue(oval.copy(filled = true).hit(50f, 30f, 3f))
        val line = ShapeObject(0, ShapeKind.LINE, 0f, 0f, 100f, 0f, widthDp = 4f)
        assertTrue(line.hit(50f, 4f, 3f))
        assertFalse(line.hit(50f, 12f, 3f))
        assertTrue(line.hit(-3f, 0f, 3f))
    }

    @Test fun textHitsInsideItsBoxAndBoundsGrowWithTheText() {
        val t = text("Hello world, a longer line that wraps")
        val b = t.bounds()
        assertTrue(t.hit(b.left + 2f, b.top + 2f, 0f))
        assertFalse(t.hit(b.right + 50f, b.top, 4f))
        assertTrue(b.height > text("Hi").bounds().height)
        assertTrue("the box never exceeds the text box width", b.width <= t.boxWidth + 0.01f)
    }

    @Test fun contentBoundsCoverStrokesAndObjectsButNotErasers() {
        val d = Drawing(
            300f, 200f,
            strokes = listOf(stroke(), Stroke(listOf(Pt(250f, 150f)), 0, 40f, DrawTool.ERASER)),
            objects = listOf(ShapeObject(0, ShapeKind.LINE, 100f, 100f, 120f, 130f, widthDp = 4f)),
        )
        val b = d.contentBounds()!!
        assertEquals(-1f, b.left, 0.01f)
        assertEquals(122f, b.right, 0.01f)
        assertEquals(132f, b.bottom, 0.01f)
        assertNull(Drawing(10f, 10f).contentBounds())
        assertNotNull(Drawing(10f, 10f, objects = listOf(text())).contentBounds())
    }

    @Test fun anArrowReservesRoomForItsHead() {
        val line = ShapeObject(0, ShapeKind.LINE, 0f, 0f, 50f, 0f, widthDp = 4f).bounds()
        val arrow = ShapeObject(0, ShapeKind.ARROW, 0f, 0f, 50f, 0f, widthDp = 4f).bounds()
        assertTrue(arrow.width > line.width)
    }

    @Test fun strokeStylesAgreeOnWidthsAndOverlays() {
        assertEquals(1f, StrokeStyle.widthFactor(DrawTool.PEN, Brush.PEN), 0f)
        assertEquals(Drawing.HIGHLIGHTER_FACTOR, StrokeStyle.widthFactor(DrawTool.HIGHLIGHTER, Brush.MARKER), 0f)
        assertEquals(Drawing.ERASER_FACTOR, StrokeStyle.widthFactor(DrawTool.ERASER, Brush.PEN), 0f)
        assertTrue(StrokeStyle.widthFactor(DrawTool.PEN, Brush.MARKER) > 2f)
        assertTrue(StrokeStyle.widthFactor(DrawTool.PEN, Brush.PENCIL) < 1f)
        for (b in listOf(Brush.MARKER, Brush.PENCIL, Brush.DASHED, Brush.DOTTED)) assertTrue("$b", StrokeStyle.overlay(DrawTool.PEN, b))
        assertFalse(StrokeStyle.overlay(DrawTool.PEN, Brush.PEN))
        assertFalse(StrokeStyle.overlay(DrawTool.PEN, Brush.CALLIGRAPHY))
        assertTrue(StrokeStyle.overlay(DrawTool.HIGHLIGHTER, Brush.PEN))
        assertFalse(StrokeStyle.overlay(DrawTool.ERASER, Brush.PEN))
    }

    @Test fun aBrushIsPartOfAStrokesIdentity() {
        val a = Stroke(listOf(Pt(1f, 1f)), 1, 2f, DrawTool.PEN, Brush.PEN)
        assertEquals(a, Stroke(listOf(Pt(1f, 1f)), 1, 2f))
        assertFalse(a == Stroke(listOf(Pt(1f, 1f)), 1, 2f, DrawTool.PEN, Brush.MARKER))
    }
}
