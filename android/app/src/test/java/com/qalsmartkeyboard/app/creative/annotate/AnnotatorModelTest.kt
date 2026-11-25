package com.qalsmartkeyboard.app.creative.annotate

import com.qalsmartkeyboard.aiclient.FeatureAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The annotator's plain rules (T8.7) on the JVM: the gate table, the bounded undo history, the editor's gestures
 * (draw, tap, select and move, erase, crop, steps, labels, pinch cancel), hit testing and the view maths.
 */
class AnnotatorModelTest {
    // ---- The gate table ---------------------------------------------------------------------------------------------

    @Test fun theGateTableIsTheAgreedSplit() {
        assertEquals(
            listOf(AnnotTool.SELECT, AnnotTool.PEN, AnnotTool.HIGHLIGHTER, AnnotTool.ARROW, AnnotTool.RECT, AnnotTool.ELLIPSE, AnnotTool.TEXT, AnnotTool.ERASER, AnnotTool.CROP, AnnotTool.REDACT_SOLID),
            AnnotatorGate.freeTools,
        )
        assertEquals(listOf(AnnotTool.BLUR, AnnotTool.PIXELATE, AnnotTool.STEP, AnnotTool.SPOTLIGHT), AnnotatorGate.premiumTools)
    }

    @Test fun hidingInformationIsNeverBehindThePlan() {
        // The safety invariant whatever else moves in the table: a solid box and the crop always work for free.
        assertFalse(AnnotatorGate.isPremium(AnnotTool.REDACT_SOLID))
        assertFalse(AnnotatorGate.isPremium(AnnotTool.CROP))
        for (a in FeatureAccess.entries) {
            assertTrue(AnnotatorGate.toolAllowed(AnnotTool.REDACT_SOLID, a))
            assertTrue(AnnotatorGate.toolAllowed(AnnotTool.CROP, a))
        }
    }

    private fun blur(id: Int, mode: RedactMode = RedactMode.BLUR) = Annotation.Redaction(id, mode, RedactArea.Rect(Box(10f, 10f, 60f, 30f)))

    @Test fun aPictureWithAPremiumToolIsEditableButNotExportableWithoutThePlan() {
        val free = AnnotDoc(200, 100).with(Annotation.Ink(1, listOf(Pt(1f, 1f)), 0, 3f)).with(blur(2, RedactMode.SOLID)).copy(crop = PixelRect(0, 0, 50, 50))
        val premium = free.with(blur(3)).with(Annotation.Step(4, Pt(5f, 5f), 1, 0, 10f))
        for (a in FeatureAccess.entries) assertTrue(AnnotatorGate.canExport(free, a))
        assertEquals(setOf(AnnotTool.BLUR, AnnotTool.STEP), AnnotatorGate.premiumToolsUsed(premium))
        for (a in listOf(FeatureAccess.SIGNED_OUT, FeatureAccess.NOT_ON_PLAN, FeatureAccess.NEEDS_CHECK)) {
            assertFalse(AnnotatorGate.canExport(premium, a))
            assertEquals(setOf(AnnotTool.BLUR, AnnotTool.STEP), AnnotatorGate.lockedTools(premium, a))
        }
        assertTrue(AnnotatorGate.canExport(premium, FeatureAccess.ALLOWED))
    }

    @Test fun lockedBlurBecomesASolidBoxOverTheSameAreaNeverLessRedaction() {
        val d = AnnotDoc(200, 100).with(blur(1)).with(blur(2, RedactMode.PIXELATE)).with(blur(3, RedactMode.SOLID))
        val solid = AnnotatorGate.solidifyLockedRedactions(d, FeatureAccess.NOT_ON_PLAN)
        assertEquals(3, solid.redactions.size)
        assertTrue(solid.redactions.all { it.mode == RedactMode.SOLID })
        assertEquals(d.redactions.map { it.area }, solid.redactions.map { it.area })
        assertTrue(AnnotatorGate.canExport(solid, FeatureAccess.NOT_ON_PLAN))
        // With the plan nothing changes; a locked step marker still needs the plan (or deleting).
        assertEquals(d, AnnotatorGate.solidifyLockedRedactions(d, FeatureAccess.ALLOWED))
        val withStep = d.with(Annotation.Step(9, Pt(1f, 1f), 1, 0, 5f))
        assertFalse(AnnotatorGate.canExport(AnnotatorGate.solidifyLockedRedactions(withStep, FeatureAccess.SIGNED_OUT), FeatureAccess.SIGNED_OUT))
    }

    // ---- Undo history -----------------------------------------------------------------------------------------------

    @Test fun theUndoHistoryIsBounded() {
        val h = BoundedHistory<Int>(maxDepth = 3)
        var cur = 0
        for (i in 1..5) { h.record(cur); cur = i }
        assertEquals(3, h.undoDepth)
        cur = h.undo(cur)!!; cur = h.undo(cur)!!; cur = h.undo(cur)!!
        assertEquals(2, cur)
        assertNull(h.undo(cur))
        assertTrue(h.canRedo)
        cur = h.redo(cur)!!
        assertEquals(3, cur)
        h.record(cur); cur = 99 // a new change drops the redo future
        assertFalse(h.canRedo)
    }

    private fun editor(depth: Int = 50) = AnnotatorEditor(historyDepth = depth).also { it.start(1000, 800) }

    private fun AnnotatorEditor.stroke(vararg pts: Pt): GestureOutcome {
        press(pts.first(), 10f)
        pts.drop(1).forEach { drag(it) }
        return release()
    }

    @Test fun theEditorUndoHistoryKeepsTheLastFiftyStepsAndRedoes() {
        val e = editor(depth = 5)
        repeat(8) { i -> e.stroke(Pt(10f * i, 10f), Pt(10f * i + 50, 60f)) }
        assertEquals(8, e.doc!!.items.size)
        repeat(10) { e.undo() }
        assertEquals(3, e.doc!!.items.size) // only five steps back
        assertFalse(e.canUndo)
        e.redo(); e.redo()
        assertEquals(5, e.doc!!.items.size)
        assertTrue(e.dirty)
    }

    // ---- Gestures ---------------------------------------------------------------------------------------------------

    @Test fun penStrokesAreCommittedWithTheChosenColourAndThickness() {
        val e = editor()
        e.color = AnnotatorEditor.PALETTE[3]
        e.thickness = Thickness.THICK
        e.stroke(Pt(10f, 10f), Pt(100f, 100f), Pt(200f, 120f))
        val ink = e.doc!!.items.single() as Annotation.Ink
        assertEquals(AnnotatorEditor.PALETTE[3], ink.color)
        assertEquals(800 * Thickness.THICK.fraction, ink.width, 0.01f)
        assertEquals(3, ink.points.size)
        assertEquals(ink.id, e.selectedId) // the last placed object is selected (Delete works on it)
        assertTrue(e.canUndo)
        e.undo()
        assertTrue(e.doc!!.items.isEmpty())
    }

    @Test fun aTapWithAShapeToolDrawsNothingAndAPenTapIsADot() {
        val e = editor()
        for (t in listOf(AnnotTool.RECT, AnnotTool.ELLIPSE, AnnotTool.ARROW, AnnotTool.SPOTLIGHT, AnnotTool.BLUR, AnnotTool.CROP)) {
            e.tool = t
            e.stroke(Pt(100f, 100f), Pt(101f, 101f))
        }
        assertTrue(e.doc!!.items.isEmpty())
        assertNull(e.doc!!.crop)
        e.tool = AnnotTool.PEN
        e.stroke(Pt(100f, 100f))
        assertEquals(1, e.doc!!.items.size)
    }

    @Test fun shapesArrowsSpotlightsAndRedactionsFollowTheDrag() {
        val e = editor()
        e.tool = AnnotTool.RECT; e.stroke(Pt(300f, 200f), Pt(100f, 50f))
        e.tool = AnnotTool.ARROW; e.stroke(Pt(10f, 10f), Pt(90f, 40f))
        e.tool = AnnotTool.SPOTLIGHT; e.stroke(Pt(400f, 400f), Pt(500f, 450f))
        e.tool = AnnotTool.PIXELATE; e.stroke(Pt(600f, 100f), Pt(700f, 130f))
        e.tool = AnnotTool.REDACT_SOLID; e.regionShape = RegionShape.BRUSH; e.stroke(Pt(50f, 600f), Pt(80f, 610f), Pt(120f, 640f))
        val items = e.doc!!.items
        assertEquals(Box(100f, 50f, 300f, 200f), (items[0] as Annotation.Shape).box)
        assertEquals(Pt(90f, 40f), (items[1] as Annotation.Arrow).to)
        assertEquals(Box(400f, 400f, 500f, 450f), (items[2] as Annotation.Spotlight).box)
        assertEquals(RedactMode.PIXELATE, (items[3] as Annotation.Redaction).mode)
        val brush = items[4] as Annotation.Redaction
        assertEquals(RedactMode.SOLID, brush.mode)
        assertEquals(3, (brush.area as RedactArea.Brush).points.size)
    }

    @Test fun selectAndMoveIsOneUndoStepAndDeleteRemovesTheSelection() {
        val e = editor()
        e.tool = AnnotTool.RECT
        e.stroke(Pt(100f, 100f), Pt(200f, 200f))
        e.tool = AnnotTool.SELECT
        assertTrue(e.press(Pt(100f, 150f), 12f)) // on the frame
        e.drag(Pt(130f, 160f)); e.drag(Pt(150f, 170f))
        e.release()
        assertEquals(Box(150f, 120f, 250f, 220f), (e.doc!!.items.single() as Annotation.Shape).box)
        e.undo()
        assertEquals(Box(100f, 100f, 200f, 200f), (e.doc!!.items.single() as Annotation.Shape).box)
        e.redo()
        // Empty space: the editor does not take the drag (the screen pans instead) and nothing is selected.
        assertFalse(e.press(Pt(800f, 700f), 12f))
        e.cancel()
        assertNull(e.selectedId)
        assertTrue(e.press(Pt(150f, 170f), 12f)); e.release()
        assertNotNull(e.selected)
        e.deleteSelected()
        assertTrue(e.doc!!.items.isEmpty())
    }

    @Test fun theEraserRemovesWhatItTouchesAsOneStep() {
        val e = editor()
        e.stroke(Pt(10f, 10f), Pt(100f, 10f))
        e.stroke(Pt(10f, 50f), Pt(100f, 50f))
        e.stroke(Pt(10f, 300f), Pt(100f, 300f))
        e.tool = AnnotTool.ERASER
        e.stroke(Pt(50f, 5f), Pt(50f, 30f), Pt(50f, 55f))
        assertEquals(1, e.doc!!.items.size)
        e.undo()
        assertEquals(3, e.doc!!.items.size)
    }

    @Test fun cropIsSetByADragClampedAndCleared() {
        val e = editor()
        e.tool = AnnotTool.CROP
        e.stroke(Pt(-50f, 100f), Pt(400f, 2000f))
        assertEquals(PixelRect(0, 100, 400, 800), e.doc!!.crop)
        assertEquals(PixelRect(0, 100, 400, 800), e.doc!!.outputRect)
        e.clearCrop()
        assertNull(e.doc!!.crop)
        e.undo()
        assertNotNull(e.doc!!.crop)
    }

    @Test fun stepsNumberThemselvesAndTextAsksForItsWords() {
        val e = editor()
        e.tool = AnnotTool.STEP
        e.stroke(Pt(100f, 100f)); e.stroke(Pt(200f, 100f)); e.stroke(Pt(300f, 100f))
        assertEquals(listOf(1, 2, 3), e.doc!!.items.map { (it as Annotation.Step).number })
        e.tool = AnnotTool.TEXT
        val ask = e.stroke(Pt(400f, 400f)) as GestureOutcome.AskText
        assertNull(ask.editing)
        e.putLabel(ask.at, "ሰላም Hello")
        val label = e.doc!!.items.last() as Annotation.Label
        assertEquals("ሰላም Hello", label.text)
        // Tapping the label asks to edit it; empty text removes it.
        val edit = e.stroke(Pt(410f, 410f)) as GestureOutcome.AskText
        assertEquals(label.id, edit.editing?.id)
        e.putLabel(edit.at, "Afaan Oromoo", edit.editing)
        assertEquals("Afaan Oromoo", (e.doc!!.items.last() as Annotation.Label).text)
        e.putLabel(edit.at, "", e.doc!!.items.last() as Annotation.Label)
        assertEquals(3, e.doc!!.items.size)
        // A long text is cut to the limit.
        e.putLabel(Pt(1f, 1f), "x".repeat(500))
        assertEquals(Annotation.MAX_TEXT, (e.doc!!.items.last() as Annotation.Label).text.length)
    }

    @Test fun aSecondFingerDropsWhatTheFirstWasDrawing() {
        val e = editor()
        e.press(Pt(10f, 10f), 10f); e.drag(Pt(100f, 100f))
        assertNotNull(e.live)
        e.cancel()
        assertNull(e.live)
        assertEquals(GestureOutcome.None, e.release())
        assertTrue(e.doc!!.items.isEmpty())
        // A move in progress is put back.
        e.stroke(Pt(10f, 10f), Pt(100f, 10f))
        e.tool = AnnotTool.SELECT
        e.press(Pt(50f, 10f), 10f); e.drag(Pt(50f, 300f)); e.cancel()
        assertEquals(Pt(10f, 10f), (e.doc!!.items.single() as Annotation.Ink).points.first())
    }

    @Test fun aPictureHoldsABoundedNumberOfMarksAndStrokesABoundedNumberOfPoints() {
        val e = editor()
        repeat(AnnotDoc.MAX_ITEMS) { e.stroke(Pt(1f, 1f)) }
        assertEquals(GestureOutcome.Full, e.stroke(Pt(5f, 5f)))
        assertEquals(AnnotDoc.MAX_ITEMS, e.doc!!.items.size)
        val f = editor()
        f.press(Pt(0f, 0f), 1f)
        for (i in 1..(AnnotDoc.MAX_POINTS + 500)) f.drag(Pt(i * 2f, (i % 7).toFloat()))
        f.release()
        assertEquals(AnnotDoc.MAX_POINTS, (f.doc!!.items.single() as Annotation.Ink).points.size)
    }

    // ---- Hit testing ------------------------------------------------------------------------------------------------

    @Test fun hitTestingFindsTheTopmostObjectWithAFingerTolerance() {
        val m = LabelMeasurer.Estimate
        val ink = Annotation.Ink(1, listOf(Pt(0f, 0f), Pt(100f, 0f)), 0, 4f)
        val frame = Annotation.Shape(2, ShapeKind.RECT, Box(50f, 50f, 250f, 250f), 0, 4f)
        val oval = Annotation.Shape(3, ShapeKind.ELLIPSE, Box(300f, 0f, 500f, 100f), 0, 4f)
        val label = Annotation.Label(4, Pt(0f, 300f), "Hello", 0, 40f)
        assertTrue(Geometry.hits(ink, Pt(50f, 8f), 10f, m))
        assertFalse(Geometry.hits(ink, Pt(50f, 30f), 10f, m))
        assertTrue(Geometry.hits(frame, Pt(52f, 150f), 10f, m)) // its border
        assertFalse(Geometry.hits(frame, Pt(150f, 150f), 10f, m)) // its hollow middle
        assertTrue(Geometry.hits(oval, Pt(400f, 2f), 10f, m))
        assertFalse(Geometry.hits(oval, Pt(400f, 50f), 10f, m))
        assertTrue(Geometry.hits(label, Pt(30f, 320f), 0f, m))
        val over = ink.copy(id = 5)
        assertEquals(5, Geometry.topmostAt(listOf(ink, over), Pt(50f, 0f), 5f, m)?.id)
        assertNull(Geometry.topmostAt(listOf(ink, frame), Pt(900f, 900f), 5f, m))
    }

    @Test fun movingAndScalingKeepEveryKindOfObjectConsistent() {
        val items = listOf(
            Annotation.Ink(1, listOf(Pt(1f, 2f)), 0, 2f),
            Annotation.Arrow(2, Pt(0f, 0f), Pt(10f, 10f), 0, 2f),
            Annotation.Shape(3, ShapeKind.RECT, Box(0f, 0f, 4f, 4f), 0, 2f),
            Annotation.Label(4, Pt(5f, 5f), "a", 0, 10f),
            Annotation.Step(5, Pt(3f, 3f), 1, 0, 6f),
            Annotation.Spotlight(6, Box(0f, 0f, 8f, 8f)),
            Annotation.Redaction(7, RedactMode.BLUR, RedactArea.Brush(listOf(Pt(2f, 2f)), 3f)),
        )
        for (a in items) {
            val b0 = Geometry.bounds(a, LabelMeasurer.Estimate)
            val b1 = Geometry.bounds(Geometry.moved(a, 10f, -5f), LabelMeasurer.Estimate)
            assertEquals("${a::class.simpleName}", b0.left + 10f, b1.left, 0.001f)
            assertEquals(b0.top - 5f, b1.top, 0.001f)
            val b2 = Geometry.bounds(Geometry.scaled(a, 0.5f), LabelMeasurer.Estimate)
            assertEquals(b0.width * 0.5f, b2.width, 0.01f)
        }
    }

    // ---- The view ---------------------------------------------------------------------------------------------------

    @Test fun theViewFitsZoomsAroundTheFingersAndMapsBackToImagePixels() {
        val v = AnnotViewport(2000, 1000)
        v.containerWidth = 1000f; v.containerHeight = 1000f
        assertEquals(0.5f, v.scale, 0.0001f)
        assertEquals(Pt(1000f, 500f), v.toImage(500f, 500f)) // the centre
        val anchor = v.toImage(250f, 400f)
        v.transform(3f, 250f, 400f, 0f, 0f)
        assertEquals(3f, v.zoom, 0.0001f)
        val after = v.toImage(250f, 400f)
        assertEquals(anchor.x, after.x, 0.5f)
        assertEquals(anchor.y, after.y, 0.5f)
        v.transform(100f, 0f, 0f, 0f, 0f)
        assertEquals(AnnotViewport.MAX_ZOOM, v.zoom, 0.0001f)
        v.transform(0.0001f, 0f, 0f, 0f, 0f)
        assertEquals(1f, v.zoom, 0.0001f)
        assertEquals(0f, v.panX, 0f) // at fit the picture is centred
        val p = v.toImage(v.toScreenX(123f), v.toScreenY(456f))
        assertEquals(123f, p.x, 0.01f)
        assertEquals(456f, p.y, 0.01f)
    }
}
