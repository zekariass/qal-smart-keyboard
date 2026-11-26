package com.qalsmartkeyboard.ime.draw

import com.qalsmartkeyboard.ime.ComposerUpdate
import com.qalsmartkeyboard.ime.share.FakeImageShare
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * T8.15: the Draw panel's controller with objects: what a touch does, placing shapes, typing a text through the typing
 * strip, select / move / delete / restyle, the eraser's reach, the paged gallery and its cap, and the silent close.
 */
class DrawPhase2ControllerTest {
    private lateinit var dir: File
    private var clock = 1_000L
    private var ids = 0
    private lateinit var store: DrawingStore
    private var changes = 0
    private lateinit var c: DrawController

    private class OkExporter : DrawingExporter {
        val drawings = ArrayList<Drawing>()
        override fun exportPng(d: Drawing, options: ExportOptions, dest: File): Boolean {
            drawings += d
            dest.parentFile?.mkdirs()
            dest.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
            return true
        }
    }

    private val exporter = OkExporter()

    @Before fun setUp() {
        dir = Files.createTempDirectory("draw-p2").toFile()
        store = DrawingStore(File(dir, "drawings"), clock = { clock++ }, newId = { "draw%04d".format(ids++) })
        c = DrawController(store, exporter, { FakeImageShare() }, onChanged = { changes++ })
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun stroke(x: Float = 0f) = Stroke(listOf(Pt(x, 0f), Pt(x + 9f, 9f)), InkDefaults.BLACK, 4f)

    // ---- tools, brushes and what a touch does -------------------------------------------------------------------

    @Test fun theBrushAndToolChoicesAreKeptAndTheInkModeIsRestored() {
        assertEquals(DrawInput.INK, c.input)
        c.selectBrush(Brush.CALLIGRAPHY)
        assertEquals(DrawTool.PEN, c.tool)
        assertEquals(Brush.CALLIGRAPHY, c.brush)
        c.selectTool(DrawTool.HIGHLIGHTER)
        assertEquals(DrawTool.HIGHLIGHTER, c.tool)
        c.selectTool(DrawTool.PEN)
        assertEquals("the pen keeps its brush", Brush.CALLIGRAPHY, c.brush)
        c.selectShape(ShapeKind.ARROW)
        assertEquals(DrawInput.SHAPE, c.input)
        c.selectBrush(Brush.DOTTED)
        assertEquals(DrawInput.INK, c.input)
        assertEquals(DrawOptions.COLOURS, c.options)
    }

    @Test fun theBrushButtonShowsTheBrushListOnASecondTap() {
        c.toggleBrushes()
        assertEquals(DrawOptions.BRUSHES, c.options)
        c.toggleBrushes()
        assertEquals(DrawOptions.COLOURS, c.options)
        c.selectTool(DrawTool.ERASER)
        c.toggleBrushes()
        assertEquals("from the eraser it goes back to the pen and shows the list", DrawTool.PEN, c.tool)
        assertEquals(DrawOptions.BRUSHES, c.options)
        c.togglePaper()
        assertEquals(DrawOptions.PAPER, c.options)
        c.togglePaper()
        assertEquals(DrawOptions.COLOURS, c.options)
    }

    // ---- shapes ----------------------------------------------------------------------------------------------------

    @Test fun aDraggedShapeIsPlacedWithTheCurrentStyleAndSelected() {
        c.selectColour(InkDefaults.PALETTE[2])
        c.selectWidth(2)
        c.selectShape(ShapeKind.RECTANGLE)
        c.setShapeFilled(true)
        assertTrue(c.placeShape(10f, 20f, 110f, 90f))
        val s = c.session.objects.single() as ShapeObject
        assertEquals(ShapeKind.RECTANGLE, s.kind)
        assertEquals(InkDefaults.PALETTE[2], s.colour)
        assertEquals(InkDefaults.WIDTHS[2], s.widthDp, 0f)
        assertTrue(s.filled)
        assertEquals(s.id, c.session.selectedId)
        assertTrue(c.hasSelection)
        assertTrue(c.session.canUndo)
    }

    @Test fun aTapPlacesADefaultSizedShapeCenteredOnTheTap() {
        c.selectShape(ShapeKind.ELLIPSE)
        c.placeShape(100f, 100f, 100f, 100f)
        val e = c.session.objects.single() as ShapeObject
        assertEquals(DrawController.DEFAULT_SHAPE, e.x2 - e.x1, 0.01f)
        assertEquals(100f, (e.x1 + e.x2) / 2f, 0.01f)
        c.selectShape(ShapeKind.LINE)
        c.placeShape(50f, 60f, 51f, 61f)
        val l = c.session.objects.last() as ShapeObject
        assertEquals("a tapped line is horizontal", l.y1, l.y2, 0f)
    }

    @Test fun changingTheStyleRestylesTheSelectedObjectAsOneUndoableStep() {
        c.selectShape(ShapeKind.RECTANGLE)
        c.placeShape(0f, 0f, 50f, 50f)
        c.selectColour(InkDefaults.PALETTE[7])
        c.selectWidth(3)
        c.setShapeFilled(true)
        val s = c.session.objects.single() as ShapeObject
        assertEquals(InkDefaults.PALETTE[7], s.colour)
        assertEquals(InkDefaults.WIDTHS[3], s.widthDp, 0f)
        assertTrue(s.filled)
        c.undo()
        assertFalse((c.session.objects.single() as ShapeObject).filled)
        c.undo()
        assertEquals(InkDefaults.WIDTHS[InkDefaults.DEFAULT_WIDTH_INDEX], (c.session.objects.single() as ShapeObject).widthDp, 0f)
    }

    @Test fun startingAnotherToolDeselectsSoItsStyleNeverRestylesAnOldObject() {
        c.selectShape(ShapeKind.LINE)
        c.placeShape(0f, 0f, 40f, 40f)
        c.selectBrush(Brush.PEN)
        assertFalse(c.hasSelection)
        c.selectColour(InkDefaults.PALETTE[4])
        assertEquals(InkDefaults.BLACK, (c.session.objects.single() as ShapeObject).colour)
    }

    @Test fun theObjectLimitSaysSoAndKeepsWorking() {
        c.selectShape(ShapeKind.LINE)
        repeat(DrawSession.MAX_OBJECTS) { assertTrue(c.placeShape(0f, 0f, 20f, 20f)) }
        assertFalse(c.placeShape(0f, 0f, 20f, 20f))
        assertEquals(DrawStatus.OBJECT_LIMIT, c.status)
        c.selectTextTool()
        c.beginText(5f, 5f)
        assertFalse("no text is started past the limit", c.typing)
        assertEquals(DrawStatus.OBJECT_LIMIT, c.status)
    }

    // ---- text ------------------------------------------------------------------------------------------------------

    @Test fun aTextIsTypedThroughTheFieldAndPlacedWhenTypingEnds() {
        c.selectColour(InkDefaults.PALETTE[5])
        c.selectTextTool()
        c.selectTextSize(TextSize.LARGE)
        c.toggleBold()
        c.beginText(30f, 40f)
        assertTrue(c.typing)
        assertNotNull(c.fieldTyping())
        // The keys write into the field through its sink, as in the panels of the AI keyboard.
        c.fieldTyping()!!.sink.apply(ComposerUpdate(commit = "ሰላም hi"))
        assertEquals("ሰላም hi", c.textField.text)
        c.endTyping()
        assertFalse(c.typing)
        assertNull(c.fieldTyping())
        val t = c.session.objects.single() as TextObject
        assertEquals("ሰላም hi", t.text)
        assertEquals(30f, t.x, 0f)
        assertEquals(40f, t.y, 0f)
        assertEquals(TextSize.LARGE, t.size)
        assertTrue(t.bold)
        assertEquals(InkDefaults.PALETTE[5], t.colour)
        assertEquals(t.id, c.session.selectedId)
        assertEquals("", c.textField.text)
    }

    @Test fun anEmptyTextPlacesNothingAndAnEditedTextIsReplacedInOneStep() {
        c.selectTextTool()
        c.beginText(10f, 10f)
        c.textField.set("   ")
        c.endTyping()
        assertTrue(c.session.objects.isEmpty())
        c.beginText(10f, 10f)
        c.textField.set("first")
        c.endTyping()
        c.selectSelectTool()
        c.editSelectedText()
        assertTrue(c.typing)
        assertEquals("the field starts with the text", "first", c.textField.text)
        c.textField.set("second")
        c.endTyping()
        assertEquals("second", (c.session.objects.single() as TextObject).text)
        c.undo()
        assertEquals("first", (c.session.objects.single() as TextObject).text)
    }

    @Test fun aTextIsLimitedInLengthAndKeptInsideTheCanvas() {
        c.selectTextTool()
        c.beginText(10_000f, 10_000f)
        c.textField.set("y".repeat(TextObject.MAX_CHARS + 100))
        assertEquals("the field holds at most the limit", TextObject.MAX_CHARS, c.textField.length)
        c.endTyping()
        val t = c.session.objects.single() as TextObject
        assertEquals(TextObject.MAX_CHARS, t.text.length)
        assertTrue(t.x + t.boxWidth <= c.session.width + 0.01f)
        assertTrue(t.y <= c.session.height)
    }

    @Test fun tappingAnExistingTextWithTheTextToolEditsItInsteadOfPlacingANewOne() {
        c.selectTextTool()
        c.beginText(10f, 10f)
        c.textField.set("original")
        c.endTyping()
        val id = c.session.objects.single().id
        c.session.select(null)
        c.editText(id)
        assertTrue(c.typing)
        assertEquals("original", c.textField.text)
        c.textField.set("changed")
        c.endTyping()
        assertEquals(1, c.session.objects.size)
        assertEquals("changed", (c.session.objects.single() as TextObject).text)
        c.selectShape(ShapeKind.LINE)
        c.placeShape(0f, 0f, 30f, 30f)
        c.editText(c.session.objects.last().id)
        assertFalse("only a text can be typed", c.typing)
    }

    @Test fun pickingTheHighlighterOrTheEraserClosesTheBrushList() {
        c.toggleBrushes()
        assertEquals(DrawOptions.BRUSHES, c.options)
        c.selectTool(DrawTool.HIGHLIGHTER)
        assertEquals(DrawOptions.COLOURS, c.options)
        c.toggleBrushes()
        c.selectTool(DrawTool.ERASER)
        assertEquals(DrawOptions.COLOURS, c.options)
        c.togglePaper()
        c.selectTool(DrawTool.PEN)
        assertEquals("the paper row stays", DrawOptions.PAPER, c.options)
    }

    @Test fun backEndsTheTypingAndPlacesTheText() {
        assertFalse(c.back())
        c.selectTextTool()
        c.beginText(5f, 5f)
        c.textField.set("kept")
        assertTrue(c.back())
        assertEquals("kept", (c.session.objects.single() as TextObject).text)
    }

    @Test fun textStyleChangesRestyleTheSelectedText() {
        c.selectTextTool()
        c.beginText(5f, 5f)
        c.textField.set("styled")
        c.endTyping()
        c.selectTextSize(TextSize.XLARGE)
        c.toggleBold()
        c.selectColour(InkDefaults.PALETTE[2])
        val t = c.session.objects.single() as TextObject
        assertEquals(TextSize.XLARGE, t.size)
        assertTrue(t.bold)
        assertEquals(InkDefaults.PALETTE[2], t.colour)
    }

    // ---- select, move, delete -------------------------------------------------------------------------------------

    @Test fun theSelectToolPicksTheLastObjectMovesItAndDeletesItWithUndo() {
        c.selectShape(ShapeKind.RECTANGLE)
        c.placeShape(10f, 10f, 60f, 50f)
        c.placeShape(100f, 100f, 150f, 140f)
        c.selectBrush(Brush.PEN) // deselects
        assertFalse(c.hasSelection)
        c.selectSelectTool()
        assertEquals("the last object is selected", c.session.objects.last().id, c.session.selectedId)
        c.moveSelected(5f, 6f)
        val moved = c.session.objects.last() as ShapeObject
        assertEquals(105f, moved.x1, 0.01f)
        assertEquals(106f, moved.y1, 0.01f)
        c.selectAt(c.session.objects.first().id)
        assertEquals(c.session.objects.first().id, c.session.selectedId)
        c.deleteSelected()
        assertEquals(1, c.session.objects.size)
        assertFalse(c.hasSelection)
        c.undo()
        assertEquals(2, c.session.objects.size)
        c.deleteSelected() // nothing selected: nothing happens
        assertEquals(2, c.session.objects.size)
    }

    @Test fun anObjectCannotBeDraggedCompletelyOffTheCanvas() {
        c.selectShape(ShapeKind.RECTANGLE)
        c.placeShape(10f, 10f, 60f, 50f)
        c.selectSelectTool()
        c.moveSelected(5_000f, 5_000f)
        val b = c.session.selected()!!.bounds()
        assertTrue(b.left < c.session.width)
        assertTrue(b.top < c.session.height)
        c.moveSelected(-50_000f, -50_000f)
        assertTrue(c.session.selected()!!.bounds().right > 0f)
    }

    // ---- the eraser ------------------------------------------------------------------------------------------------

    @Test fun theEraserClearsInkOnlyObjectsStayAndOnlyDeleteRemovesThem() {
        c.session.addStroke(stroke())
        c.selectShape(ShapeKind.RECTANGLE)
        c.placeShape(0f, 0f, 100f, 100f)
        c.selectTool(DrawTool.ERASER)
        c.session.addStroke(Stroke(listOf(Pt(0f, 0f), Pt(100f, 100f)), 0, 30f, DrawTool.ERASER))
        c.strokeAdded()
        val d = c.session.snapshot()
        assertEquals("an erase is an ink stroke", 2, d.strokes.size)
        assertEquals("the shape is still there", 1, d.objects.size)
        assertFalse(c.session.isEmpty)
        c.selectSelectTool()
        c.deleteSelected()
        assertTrue(c.session.objects.isEmpty())
    }

    // ---- save, open and the sticker maker's snapshot ---------------------------------------------------------------

    @Test fun savingAndOpeningKeepsTheInkTheObjectsTheBrushesAndThePaper() {
        c.selectBrush(Brush.MARKER)
        c.session.addStroke(Stroke(listOf(Pt(1f, 1f), Pt(30f, 30f)), InkDefaults.BLACK, 4f, DrawTool.PEN, Brush.MARKER))
        c.setBackground(DrawBackground.DOTS)
        c.selectShape(ShapeKind.ARROW)
        c.placeShape(5f, 5f, 80f, 40f)
        c.selectTextTool()
        c.beginText(20f, 60f)
        c.textField.set("note")
        c.endTyping()
        val id = c.save()!!
        assertEquals(DrawStatus.SAVED, c.status)
        assertEquals("the exported image shows the objects", 2, exporter.drawings.last().objects.size)
        c.newDrawing()
        assertTrue(c.session.isEmpty)
        c.openGallery()
        assertTrue(c.edit(id))
        assertEquals(DrawBackground.DOTS, c.session.background)
        assertEquals(Brush.MARKER, c.session.strokes.single().brush)
        assertEquals(listOf(ShapeKind.ARROW), c.session.objects.filterIsInstance<ShapeObject>().map { it.kind })
        assertEquals("note", c.session.objects.filterIsInstance<TextObject>().single().text)
        assertEquals(DrawInput.INK, c.input)
    }

    @Test fun theStickerMakersSnapshotSeesObjectsAndPaper() {
        c.setBackground(DrawBackground.MINT)
        assertTrue("an empty canvas has nothing to make a sticker of", c.session.snapshot().isEmpty)
        c.selectTextTool()
        c.beginText(10f, 10f)
        c.textField.set("sticker text")
        c.endTyping()
        val snap = c.session.snapshot()
        assertFalse(snap.isEmpty)
        assertEquals(DrawBackground.MINT, snap.background)
        assertEquals(1, snap.objects.size)
        assertEquals(snap.background.colour, com.qalsmartkeyboard.ime.sticker.DrawingStickerSpec(snap).fill)
    }

    // ---- the gallery -----------------------------------------------------------------------------------------------

    @Test fun theGalleryIsPagedNewestFirst() {
        val n = 2 * DrawController.GALLERY_PAGE + 3
        val saved = (0 until n).map { store.save(null, Drawing(10f, 10f, strokes = listOf(stroke(it.toFloat()))))!! }
        c.openGallery()
        assertEquals(3, c.galleryPages)
        assertEquals(saved.reversed().take(DrawController.GALLERY_PAGE), c.pageItems.map { it.id })
        c.nextPage()
        assertEquals(1, c.galleryPage)
        assertEquals(saved.reversed().drop(DrawController.GALLERY_PAGE).take(DrawController.GALLERY_PAGE), c.pageItems.map { it.id })
        c.nextPage(); c.nextPage()
        assertEquals("the last page is the limit", 2, c.galleryPage)
        assertEquals(3, c.pageItems.size)
        c.previousPage(); c.previousPage(); c.previousPage()
        assertEquals(0, c.galleryPage)
    }

    @Test fun deletingTheLastDrawingOfThePageStepsBackAPage() {
        val n = DrawController.GALLERY_PAGE + 1
        repeat(n) { store.save(null, Drawing(10f, 10f, strokes = listOf(stroke()))) }
        c.openGallery()
        c.nextPage()
        val only = c.pageItems.single().id
        c.select(only)
        c.requestDelete(only)
        assertTrue(c.requestDelete(only))
        assertEquals(0, c.galleryPage)
        assertEquals(DrawController.GALLERY_PAGE, c.pageItems.size)
        assertEquals(DrawStatus.DELETED, c.status)
    }

    @Test fun atTheCapASaveDuplicateSaysTheGalleryIsFullAndNothingIsLost() {
        repeat(DrawingStore.MAX_DRAWINGS) { store.save(null, Drawing(10f, 10f, strokes = listOf(stroke()))) }
        c.session.addStroke(stroke())
        assertNull(c.save())
        assertEquals(DrawStatus.GALLERY_FULL, c.status)
        assertEquals(DrawingStore.MAX_DRAWINGS, store.count())
        c.openGallery()
        assertTrue(c.galleryFull)
        val first = c.gallery.first().id
        assertNull(c.duplicate(first))
        assertEquals(DrawStatus.GALLERY_FULL, c.status)
        // An existing drawing is still saved over, and deleting makes room.
        assertTrue(c.edit(first))
        c.session.addStroke(stroke(3f))
        assertEquals(first, c.save())
        c.openGallery()
        c.requestDelete(first)
        c.requestDelete(first)
        assertFalse(c.galleryFull)
        c.newDrawing()
        c.session.addStroke(stroke())
        assertNotNull(c.save())
    }

    @Test fun duplicatingKeepsObjectsAndGivesTheCopyItsOwnCreationTime() {
        c.selectShape(ShapeKind.LINE)
        c.placeShape(0f, 0f, 30f, 30f)
        val id = c.save()!!
        c.openGallery()
        val copy = c.duplicate(id)!!
        val a = store.listItems().first { it.id == id }
        val b = store.listItems().first { it.id == copy }
        assertTrue(b.createdAt >= a.createdAt)
        assertEquals(1, (store.load(copy) as DecodeResult.Ok).drawing.objects.size)
    }

    // ---- closing is silent ----------------------------------------------------------------------------------------

    @Test fun closingDropsTheTextBeingTypedAndSaysNothing() {
        c.selectTextTool()
        c.beginText(5f, 5f)
        c.textField.set("unfinished")
        val before = changes
        c.close()
        assertFalse(c.typing)
        assertEquals("", c.textField.text)
        assertEquals("closing is silent", before, changes)
        assertTrue(c.session.objects.isEmpty())
    }

    @Test fun aServiceLikeOnChangedThatClosesTheControllerDoesNotLoop() {
        // The service calls close() from inside its own refreshViews(), which every change notification triggers: a
        // notification raised while closing re-entered it without end (the sticker maker's crash on the phone).
        var calls = 0
        lateinit var ctl: DrawController
        ctl = DrawController(
            store, exporter, { FakeImageShare() },
            onChanged = {
                calls++
                check(calls < 200) { "onChanged re-entered itself while closing" }
                ctl.close()
            },
        )
        ctl.selectTextTool()
        val afterTool = calls
        ctl.beginText(5f, 5f)
        ctl.textField.set("typed text") // the field notifies; the handler closes, which clears the field silently
        assertTrue("a bounded number of notifications", calls - afterTool < 10)
        assertFalse(ctl.typing)
        ctl.discard()
        assertTrue(calls < 20)
    }

    @Test fun discardIsSilentAndForgetsTheDrawingToo() {
        c.selectShape(ShapeKind.LINE)
        c.placeShape(0f, 0f, 30f, 30f)
        c.selectTextTool()
        c.beginText(5f, 5f)
        c.textField.set("x")
        val before = changes
        c.discard()
        assertEquals(before, changes)
        assertTrue(c.session.isEmpty)
        assertEquals(DrawInput.INK, c.input)
        assertEquals(Brush.PEN, c.brush)
    }

    @Test fun openingTheGalleryOrANewDrawingWhileTypingDropsTheTypedText() {
        c.selectTextTool()
        c.beginText(5f, 5f)
        c.textField.set("abc")
        c.openGallery()
        assertFalse(c.typing)
        assertEquals("", c.textField.text)
        c.newDrawing()
        assertTrue(c.session.objects.isEmpty())
    }
}
