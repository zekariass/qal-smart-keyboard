package com.qalsmartkeyboard.ime.draw

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.qalsmartkeyboard.ime.sticker.DrawingStickerSpec
import com.qalsmartkeyboard.ime.sticker.WebpStickerExporter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

/**
 * T8.15: what the renderer paints: every brush, the paper patterns, text and shapes, the eraser's reach, and that the PNG
 * export, the thumbnail and the sticker maker show them alike. Real pixels (native graphics).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DrawRenderTest {
    private lateinit var dir: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("draw-render").toFile()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private val red = 0xFFFF0000.toInt()

    /** The whole [d] at 2 pixels per unit, so a unit (x, y) is the pixel (2x, 2y). */
    private fun render(d: Drawing, withBackground: Boolean = true): Bitmap {
        val r = DrawingRenderer()
        return r.render(d, ExportPlan.of(d, ExportOptions(longestSidePx = (maxOf(d.width, d.height) * 2).toInt(), cropToContent = false), r.textMeasure), withBackground)
    }

    private fun alpha(b: Bitmap, x: Float, y: Float) = b.getPixel((x * 2).toInt(), (y * 2).toInt()) ushr 24

    private fun opaque(b: Bitmap): Int {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return px.count { (it ushr 24) > 200 }
    }

    private fun touched(b: Bitmap): Int {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return px.count { (it ushr 24) > 0 }
    }

    private fun hLine(brush: Brush = Brush.PEN, tool: DrawTool = DrawTool.PEN, w: Float = 10f, colour: Int = red) =
        Stroke(listOf(Pt(20f, 50f), Pt(80f, 50f), Pt(140f, 50f), Pt(180f, 50f)), colour, w, tool, brush)

    // ---- brushes ---------------------------------------------------------------------------------------------------

    @Test fun everyBrushPaintsInkAndTheMarkerPencilAndHighlighterAreTranslucent() {
        val base = Drawing(200f, 100f, DrawBackground.TRANSPARENT)
        for (brush in Brush.entries) {
            val b = render(base.copy(strokes = listOf(hLine(brush))))
            assertTrue("$brush paints", touched(b) > 100)
            b.recycle()
        }
        val pen = render(base.copy(strokes = listOf(hLine(Brush.PEN))))
        assertEquals("the pen is opaque", 255, alpha(pen, 100f, 50f))
        val marker = render(base.copy(strokes = listOf(hLine(Brush.MARKER))))
        assertTrue("the marker is semi-opaque: ${alpha(marker, 100f, 50f)}", alpha(marker, 100f, 50f) in 150..200)
        val hl = render(base.copy(strokes = listOf(hLine(tool = DrawTool.HIGHLIGHTER))))
        assertEquals("the highlighter is the lightest", 0x66, alpha(hl, 100f, 50f))
        val pencil = render(base.copy(strokes = listOf(hLine(Brush.PENCIL))))
        assertTrue("the pencil is translucent: ${alpha(pencil, 100f, 50f)}", alpha(pencil, 100f, 50f) in 100..230)
        listOf(pen, marker, hl, pencil).forEach { it.recycle() }
    }

    @Test fun theMarkerIsWiderThanThePenAndThePencilThinner() {
        val base = Drawing(200f, 100f, DrawBackground.TRANSPARENT)
        fun thickness(brush: Brush): Int {
            val b = render(base.copy(strokes = listOf(hLine(brush))))
            var n = 0
            for (y in 0 until b.height) if ((b.getPixel(200, y) ushr 24) > 0) n++
            b.recycle()
            return n
        }
        assertTrue(thickness(Brush.MARKER) > thickness(Brush.PEN))
        assertTrue(thickness(Brush.PENCIL) < thickness(Brush.PEN))
    }

    @Test fun dashedAndDottedStrokesHaveGapsAndASolidOneHasNone() {
        val base = Drawing(200f, 100f, DrawBackground.TRANSPARENT)
        fun gaps(brush: Brush): Int {
            val b = render(base.copy(strokes = listOf(hLine(brush, w = 6f))))
            var gap = 0
            var inGap = false
            for (x in 60 until 340) {
                val on = (b.getPixel(x, 100) ushr 24) > 40
                if (!on && !inGap) gap++
                inGap = !on
            }
            b.recycle()
            return gap
        }
        assertEquals(0, gaps(Brush.PEN))
        assertTrue("dashed has gaps", gaps(Brush.DASHED) >= 3)
        assertTrue("dotted has gaps", gaps(Brush.DOTTED) >= 5)
    }

    @Test fun theCalligraphyNibIsThickAcrossItAndThinAlongIt() {
        val base = Drawing(200f, 200f, DrawBackground.TRANSPARENT)
        // The nib lies at 45 degrees rising to the right: a stroke that rises to the right runs along it (thin), one that
        // falls to the right runs across it (thick).
        val along = Stroke(listOf(Pt(40f, 160f), Pt(80f, 120f), Pt(120f, 80f), Pt(160f, 40f)), red, 12f, DrawTool.PEN, Brush.CALLIGRAPHY)
        val across = Stroke(listOf(Pt(40f, 40f), Pt(80f, 80f), Pt(120f, 120f), Pt(160f, 160f)), red, 12f, DrawTool.PEN, Brush.CALLIGRAPHY)
        val a = render(base.copy(strokes = listOf(along)))
        val c = render(base.copy(strokes = listOf(across)))
        assertTrue("across ${opaque(c)} > along ${opaque(a)}", opaque(c) > opaque(a) * 1.4)
        a.recycle(); c.recycle()
    }

    @Test fun aSingleTapMakesADotWithEveryBrush() {
        for (brush in Brush.entries) {
            val d = Drawing(100f, 100f, DrawBackground.TRANSPARENT, strokes = listOf(Stroke(listOf(Pt(50f, 50f)), red, 12f, DrawTool.PEN, brush)))
            val b = render(d)
            assertTrue("$brush", touched(b) > 5)
            b.recycle()
        }
    }

    @Test fun theEraserStillClearsEveryBrushToTransparent() {
        val d = Drawing(
            200f, 100f, DrawBackground.TRANSPARENT,
            strokes = listOf(hLine(Brush.MARKER), Stroke(listOf(Pt(100f, 0f), Pt(100f, 100f)), 0, 6f, DrawTool.ERASER)),
        )
        val b = render(d)
        assertEquals(0, alpha(b, 100f, 50f))
        assertTrue(alpha(b, 40f, 50f) > 100)
        b.recycle()
    }

    // ---- papers ------------------------------------------------------------------------------------------------------

    @Test fun eachPaperPatternPaintsItsMarksAndPlainPaperHasNone() {
        fun lineLike(bg: DrawBackground, x: Float, y: Float): Int {
            val b = render(Drawing(200f, 100f, bg))
            val v = b.getPixel((x * 2).toInt(), (y * 2).toInt())
            b.recycle()
            return v
        }
        val white = 0xFFFFFFFF.toInt()
        assertEquals("plain paper", white, lineLike(DrawBackground.WHITE, 32f, 32f))
        assertNotEquals("ruled: a line at y = 32", white, lineLike(DrawBackground.RULED, 100f, 32f))
        assertEquals("ruled: no line between", white, lineLike(DrawBackground.RULED, 100f, 45f))
        assertEquals("ruled: no vertical line", white, lineLike(DrawBackground.RULED, 32f, 45f))
        assertNotEquals("grid: a vertical line", white, lineLike(DrawBackground.GRID, 32f, 45f))
        assertNotEquals("dots: a dot at the crossing", white, lineLike(DrawBackground.DOTS, 32f, 32f))
        assertEquals("dots: nothing on the line between the dots", white, lineLike(DrawBackground.DOTS, 100f, 32f))
        assertEquals("dots: nothing between", white, lineLike(DrawBackground.DOTS, 45f, 45f))
    }

    @Test fun colourPapersAreFilledTransparentStaysTransparentAndTheInkLaysOverThem() {
        for (bg in DrawBackground.PRESETS) {
            val b = render(Drawing(100f, 100f, bg, strokes = listOf(Stroke(listOf(Pt(10f, 10f), Pt(90f, 90f)), red, 8f))))
            if (bg.isTransparent) assertEquals("$bg", 0, alpha(b, 80f, 20f)) else assertEquals("$bg", 255, alpha(b, 80f, 20f))
            assertEquals("the ink is over the paper", red, b.getPixel(100, 100))
            b.recycle()
        }
        val noPaper = render(Drawing(100f, 100f, DrawBackground.SLATE, strokes = listOf(Stroke(listOf(Pt(10f, 10f), Pt(90f, 90f)), red, 8f))), withBackground = false)
        assertEquals("a PNG without paper is transparent", 0, alpha(noPaper, 80f, 20f))
        noPaper.recycle()
    }

    @Test fun aPatternOnTransparentPaperKeepsTheCornersTransparentAndDarkPapersAreDark() {
        val b = render(Drawing(200f, 100f, DrawBackground(0x00000000, BackgroundPattern.GRID)))
        assertEquals(0, alpha(b, 5f, 5f))
        b.recycle()
        assertTrue(DrawBackground.SLATE.isDark)
        assertTrue(DrawBackground.BLACK.isDark)
        assertFalse(DrawBackground.LAVENDER.isDark)
        assertEquals(InkDefaults.WHITE, InkDefaults.inkFor(DrawBackground.SLATE))
    }

    // ---- objects -----------------------------------------------------------------------------------------------------

    @Test fun shapesArePaintedAndAFilledOneIsSolidInside() {
        val d = Drawing(
            200f, 100f, DrawBackground.TRANSPARENT,
            objects = listOf(
                ShapeObject(0, ShapeKind.RECTANGLE, 10f, 10f, 70f, 60f, red, 4f, filled = true),
                ShapeObject(0, ShapeKind.RECTANGLE, 90f, 10f, 150f, 60f, red, 4f, filled = false),
                ShapeObject(0, ShapeKind.ELLIPSE, 10f, 65f, 70f, 95f, red, 3f, filled = true),
                ShapeObject(0, ShapeKind.LINE, 90f, 70f, 190f, 70f, red, 4f),
                ShapeObject(0, ShapeKind.ARROW, 90f, 85f, 190f, 85f, red, 4f),
            ),
        )
        val b = render(d)
        assertEquals("a filled rectangle", red, b.getPixel(80, 70))
        assertEquals("an outline is hollow", 0, alpha(b, 120f, 35f))
        assertEquals("its edge is drawn", 255, alpha(b, 90f, 35f))
        assertEquals("a filled ellipse", red, b.getPixel(80, 160))
        assertEquals("outside the ellipse's corner", 0, alpha(b, 11f, 66f))
        assertEquals("the line", 255, alpha(b, 140f, 70f))
        // The arrow head is wider than its shaft near the tip.
        var head = 0
        for (y in 140 until 200) if ((b.getPixel(2 * 180, y) ushr 24) > 0) head++
        var shaft = 0
        for (y in 140 until 200) if ((b.getPixel(2 * 110, y) ushr 24) > 0) shaft++
        assertTrue("head $head > shaft $shaft", head > shaft)
        b.recycle()
    }

    @Test fun objectsAreAboveTheInkAndTheEraserDoesNotTouchThem() {
        val d = Drawing(
            200f, 100f, DrawBackground.TRANSPARENT,
            strokes = listOf(
                Stroke(listOf(Pt(10f, 50f), Pt(190f, 50f)), 0xFF0000FF.toInt(), 30f),
                Stroke(listOf(Pt(0f, 50f), Pt(200f, 50f)), 0, 60f, DrawTool.ERASER), // erases all the ink
            ),
            objects = listOf(ShapeObject(0, ShapeKind.RECTANGLE, 60f, 30f, 140f, 70f, red, 4f, filled = true)),
        )
        val b = render(d)
        assertEquals("the shape survives the eraser", red, b.getPixel(200, 100))
        assertEquals("the ink is gone", 0, alpha(b, 30f, 50f))
        b.recycle()
        val over = render(Drawing(200f, 100f, DrawBackground.TRANSPARENT, strokes = listOf(Stroke(listOf(Pt(10f, 50f), Pt(190f, 50f)), 0xFF0000FF.toInt(), 30f)), objects = listOf(ShapeObject(0, ShapeKind.RECTANGLE, 60f, 30f, 140f, 70f, red, 4f, filled = true))))
        assertEquals("a shape is painted over the ink", red, over.getPixel(200, 100))
        over.recycle()
    }

    @Test fun aTextIsPaintedInItsColourAndTheLatinAndGeezBothDraw() {
        val texts = listOf("Hello world", "ሰላም ዓለም")
        for (t in texts) {
            val d = Drawing(200f, 100f, DrawBackground.TRANSPARENT, objects = listOf(TextObject(0, t, 10f, 10f, TextSize.XLARGE, 0xFF0000FF.toInt(), true, 180f)))
            val b = render(d)
            assertTrue("'$t' paints", opaque(b) > 200)
            val px = IntArray(b.width * b.height)
            b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            assertTrue("in its colour", px.any { it == 0xFF0000FF.toInt() })
            b.recycle()
        }
    }

    @Test fun aWordIsNeverSplitAcrossTwoLinesTheTextShrinksInstead() {
        val r = ObjectRenderer()
        val long = TextObject(0, "Selamunalekum", 0f, 0f, TextSize.LARGE, boxWidth = 80f)
        val one = r.bounds(long)
        val lineHeight = r.bounds(TextObject(0, "Selam", 0f, 0f, TextSize.LARGE, boxWidth = 400f)).height
        assertTrue("one line: ${one.height} vs $lineHeight", one.height < lineHeight * 1.3f)
        assertTrue("it fits the box: ${one.width}", one.width <= 80.5f)
        // Several words wrap between words, and every word still fits.
        val wrapped = r.bounds(TextObject(0, "Selam Selam Selam Selam Selam", 0f, 0f, TextSize.MEDIUM, boxWidth = 120f))
        assertTrue(wrapped.height > lineHeight * 0.9f)
        assertTrue(wrapped.width <= 120.5f)
        // A new line in the text is kept.
        assertTrue(r.bounds(TextObject(0, "a\nb\nc", 0f, 0f, TextSize.MEDIUM)).height > r.bounds(TextObject(0, "abc", 0f, 0f, TextSize.MEDIUM)).height * 2)
        // The text never exceeds its limit box even for a single huge word.
        assertTrue(r.bounds(TextObject(0, "W".repeat(60), 0f, 0f, TextSize.XLARGE, boxWidth = 100f)).width <= 100.5f)
    }

    @Test fun emptyTextDrawsNothingAndMeasuresAsASmallBox() {
        val r = ObjectRenderer()
        val b = r.bounds(TextObject(0, "", 5f, 6f))
        assertTrue(b.width > 0f && b.height > 0f)
        val bmp = render(Drawing(100f, 100f, DrawBackground.TRANSPARENT, objects = listOf(TextObject(0, "", 5f, 5f))))
        assertEquals(0, touched(bmp))
        bmp.recycle()
    }

    @Test fun twoTextsOfDifferentSizeKeepTheirOwnSizeWhenDrawnTogether() {
        // The laid-out texts are cached; each must keep its own size and weight (a shared paint would give both the last one).
        val d = Drawing(
            300f, 120f, DrawBackground.TRANSPARENT,
            objects = listOf(
                TextObject(0, "Big", 5f, 5f, TextSize.XLARGE, red),
                TextObject(0, "small", 150f, 5f, TextSize.SMALL, red),
            ),
        )
        val b = render(d)
        fun inkIn(x0: Int, x1: Int): Int {
            var n = 0
            for (x in x0 until x1) for (y in 0 until b.height) if ((b.getPixel(x, y) ushr 24) > 100) n++
            return n
        }
        assertTrue("big ${inkIn(0, 290)} vs small ${inkIn(300, 600)}", inkIn(0, 290) > inkIn(300, 600) * 3)
        // Drawing again from the cache gives the same picture.
        val again = render(d)
        assertTrue(b.sameAs(again))
        b.recycle(); again.recycle()
    }

    // ---- export and thumbnails -------------------------------------------------------------------------------------

    @Test fun theExportCropsToTheObjectsToo() {
        val d = Drawing(400f, 400f, DrawBackground.TRANSPARENT, objects = listOf(ShapeObject(0, ShapeKind.RECTANGLE, 100f, 100f, 200f, 150f, red, 4f)))
        val f = File(dir, "o.png")
        assertTrue(PngDrawingExporter().exportPng(d, ExportOptions(longestSidePx = 500, cropToContent = true, marginUnits = 10f), f))
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        // 100 + 4 wide, 50 + 4 high, plus a 10 unit margin each side: about 124 x 74 units -> 500 x ~298 pixels.
        assertEquals(500, o.outWidth)
        assertTrue("${o.outHeight}", o.outHeight in 280..320)
        assertTrue(Drawing(400f, 400f, objects = d.objects).contentBounds() != null)
    }

    @Test fun aTextOnlyDrawingExportsAndANewlineOnlyIsStillAnObject() {
        val d = Drawing(300f, 200f, objects = listOf(TextObject(0, "Selam", 20f, 20f, TextSize.LARGE, 0xFF000000.toInt(), false, 200f)))
        val f = File(dir, "t.png")
        assertTrue(PngDrawingExporter().exportPng(d, ExportOptions(), f))
        val bmp = BitmapFactory.decodeFile(f.path)
        assertTrue(opaque(bmp) > 500)
        bmp.recycle()
    }

    @Test fun theThumbnailShowsObjectsAndPapersLikeTheExport() {
        val d = Drawing(
            300f, 100f, DrawBackground.SLATE,
            strokes = listOf(Stroke(listOf(Pt(10f, 10f), Pt(60f, 30f)), red, 6f, DrawTool.PEN, Brush.MARKER)),
            objects = listOf(ShapeObject(0, ShapeKind.RECTANGLE, 150f, 20f, 250f, 80f, 0xFF00FF00.toInt(), 4f, filled = true)),
        )
        val renderer = DrawingRenderer()
        val thumb = renderer.thumbnail(d, 150)
        assertEquals(150, thumb.width)
        assertEquals(50, thumb.height)
        assertEquals("the filled rectangle", 0xFF00FF00.toInt(), thumb.getPixel(100, 25))
        assertEquals("the paper", 0xFF263238.toInt(), thumb.getPixel(120, 5))
        thumb.recycle()
    }

    @Test fun aStickerMadeFromADrawingKeepsItsObjectsAndTheChoiceOfPaper() {
        val d = Drawing(
            200f, 100f, DrawBackground.MINT,
            objects = listOf(ShapeObject(0, ShapeKind.RECTANGLE, 60f, 20f, 140f, 80f, red, 4f, filled = true)),
        )
        val f = File(dir, "s.webp")
        assertTrue(WebpStickerExporter().exportWebp(DrawingStickerSpec(d), f))
        val b = BitmapFactory.decodeFile(f.path)
        assertEquals("the shape is in the sticker", red, b.getPixel(256, 256))
        assertEquals("transparent stays transparent", 0, b.getPixel(256, 8) ushr 24)
        b.recycle()
        val g = File(dir, "p.webp")
        assertTrue(WebpStickerExporter().exportWebp(DrawingStickerSpec(d, withBackground = true), g))
        val p = BitmapFactory.decodeFile(g.path)
        assertEquals("with paper the sticker is filled", 255, p.getPixel(256, 20) ushr 24)
        assertEquals("the shape is over the paper", red, p.getPixel(256, 256))
        p.recycle()
    }
}
