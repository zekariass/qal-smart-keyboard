package com.qalsmartkeyboard.ime.draw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import com.qalsmartkeyboard.ime.KeyboardMetrics
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.share.FakeImageShare
import com.qalsmartkeyboard.ime.theme.ThemeCatalog
import com.qalsmartkeyboard.ime.toolbar.DrawFieldBar
import com.qalsmartkeyboard.ime.toolbar.DrawPanelView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

/**
 * T8.15: the Draw panel's second phase under Robolectric: touch gestures for shapes, text and select / move, the tools row and
 * its context row in every mode (48dp targets, spoken names, one Button node each), the gallery pages and the cap message,
 * the typing strip, and the fit in the key area (251dp) and in the AI panels' real height.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DrawPhase2UiTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val d = ctx.resources.displayMetrics.density
    private val widthPx = (411 * d).toInt()
    private lateinit var dir: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("draw-p2-ui").toFile()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun View.layoutAt(w: Int, h: Int) {
        measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        layout(0, 0, measuredWidth, measuredHeight)
    }

    private fun str(id: Int) = ctx.getString(id)
    private fun str(id: Int, vararg a: Any) = ctx.getString(id, *a)

    // ---- the canvas gestures ---------------------------------------------------------------------------------------

    private fun canvas(): DrawCanvasView {
        val v = DrawCanvasView(ctx)
        v.session = DrawSession(411f, 300f)
        v.layoutAt((411 * d).toInt(), (300 * d).toInt())
        return v
    }

    private var t0 = SystemClock.uptimeMillis()
    private fun ev(action: Int, x: Float, y: Float) = MotionEvent.obtain(t0, t0 + 10, action, x, y, 0.7f, 1f, 0, 1f, 1f, 0, 0)

    /** A finger down at the unit point, moving to the second, and up there. */
    private fun DrawCanvasView.drag(x1: Float, y1: Float, x2: Float, y2: Float) {
        val s = d // the 411 x 300 unit session fills the 411 x 300 dp view: one unit is one dp
        dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, x1 * s, y1 * s))
        dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, (x1 + x2) / 2 * s, (y1 + y2) / 2 * s))
        dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, x2 * s, y2 * s))
        dispatchTouchEvent(ev(MotionEvent.ACTION_UP, x2 * s, y2 * s))
    }

    @Test fun draggingWithTheShapeToolReportsTheShapeInDrawingUnitsAndAddsNoInk() {
        val v = canvas()
        v.input = DrawInput.SHAPE
        val got = ArrayList<List<Float>>()
        v.onShapeDrawn = { a, b, c, e -> got += listOf(a, b, c, e) }
        v.drag(100f, 100f, 200f, 150f)
        assertEquals(1, got.size)
        assertEquals(listOf(100f, 100f, 200f, 150f), got.single().map { Math.round(it * 10) / 10f })
        assertTrue(v.session.strokes.isEmpty())
        // A tap is reported as a zero-length drag (the controller makes a default shape of it).
        got.clear()
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 300f, 300f))
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 301f, 301f))
        assertEquals(got.single()[0], got.single()[2], 0.0001f)
        assertEquals(got.single()[1], got.single()[3], 0.0001f)
    }

    @Test fun aTapWithTheTextToolReportsThePointAndADragDoesNot() {
        val v = canvas()
        v.input = DrawInput.TEXT
        val taps = ArrayList<Pair<Float, Float>>()
        v.onTextTap = { x, y -> taps += x to y }
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 150f * d, 90f * d))
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 150f * d, 90f * d))
        assertEquals(1, taps.size)
        assertEquals(150f, taps.single().first, 0.5f)
        assertEquals(90f, taps.single().second, 0.5f)
        v.drag(10f, 10f, 200f, 200f)
        assertEquals("a drag is not a tap", 1, taps.size)
    }

    @Test fun theSelectToolPicksAnObjectDragsItAndATapOnNothingDeselects() {
        val v = canvas()
        val placed = v.session.addObject(ShapeObject(0, ShapeKind.RECTANGLE, 50f, 50f, 150f, 150f, filled = true))!!
        v.session.select(null)
        v.input = DrawInput.SELECT
        val tapped = ArrayList<Int?>()
        val moves = ArrayList<Pair<Float, Float>>()
        v.onObjectTapped = { tapped += it }
        v.onObjectMoved = { dx, dy -> moves += dx to dy }
        // A tap on it picks it, and does not move it.
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 100f * d, 100f * d))
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 100f * d, 100f * d))
        assertEquals(listOf<Int?>(placed.id), tapped)
        assertTrue(moves.isEmpty())
        // A drag of it moves it by the distance (and picks it first when it is not picked yet).
        tapped.clear()
        v.drag(100f, 100f, 130f, 120f)
        assertEquals(listOf<Int?>(placed.id), tapped)
        assertEquals(1, moves.size)
        assertEquals(30f, moves.single().first, 0.5f)
        assertEquals(20f, moves.single().second, 0.5f)
        // Once it is the selection, a drag moves it without picking again.
        v.session.select(placed.id)
        tapped.clear(); moves.clear()
        v.drag(100f, 100f, 90f, 95f)
        assertTrue(tapped.isEmpty())
        assertEquals(1, moves.size)
        // A tap on nothing clears the selection.
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 380f * d, 280f * d))
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 380f * d, 280f * d))
        assertEquals(listOf<Int?>(null), tapped)
        // A drag that starts on nothing moves nothing.
        moves.clear()
        v.drag(300f, 250f, 350f, 280f)
        assertTrue(moves.isEmpty())
    }

    @Test fun inkIsUnchangedByTheNewInputsAndSwitchingInputCancelsATouch() {
        val v = canvas()
        v.drag(10f, 10f, 100f, 100f)
        assertEquals(1, v.session.strokes.size)
        v.input = DrawInput.SHAPE
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 100f, 100f))
        v.input = DrawInput.INK // the panel changed the tool mid-touch: the touch is dropped
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 200f, 200f))
        assertEquals(1, v.session.strokes.size)
    }

    @Test fun theCanvasDrawsObjectsASelectionAndALiveShapeWithoutErrorsInEveryMode() {
        val v = canvas()
        v.session.addObject(TextObject(0, "Hello ሰላም", 20f, 20f, TextSize.LARGE, 0xFF0000FF.toInt(), true))
        v.session.addObject(ShapeObject(0, ShapeKind.ARROW, 100f, 100f, 200f, 150f, 0xFFFF0000.toInt(), 6f))
        for (input in DrawInput.entries) {
            v.input = input
            v.draw(Canvas(Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)))
        }
        v.input = DrawInput.SHAPE
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 100f, 100f))
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, 400f, 300f))
        v.draw(Canvas(Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)))
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_CANCEL, 400f, 300f))
        v.release()
    }

    @Test fun everyBrushDrawsLiveAndKeepsItsBrushInTheStroke() {
        for (b in Brush.entries) {
            val v = canvas()
            v.brush = b
            v.widthUnits = 8f
            v.drag(50f, 50f, 250f, 120f)
            val s = v.session.strokes.single()
            assertEquals(b, s.brush)
            assertEquals(DrawTool.PEN, s.tool)
            v.draw(Canvas(Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)))
        }
        val v = canvas()
        v.tool = DrawTool.HIGHLIGHTER
        v.brush = Brush.CALLIGRAPHY
        v.drag(50f, 50f, 250f, 120f)
        assertEquals("a highlighter stroke ignores the brush", Brush.PEN, v.session.strokes.single().brush)
    }

    // ---- the panel -------------------------------------------------------------------------------------------------

    private class Rig(val ctx: Context, val dir: File, val share: FakeImageShare = FakeImageShare()) {
        val store = DrawingStore(File(dir, "drawings"))
        lateinit var panel: DrawPanelView
        val controller = DrawController(store, PngDrawingExporter(), { share }, onChanged = { panel.refresh() })
    }

    private fun rig(heightDp: Int = 320): Rig {
        val r = Rig(ctx, dir)
        r.panel = DrawPanelView(ctx).apply {
            heightProvider = { (heightDp * d).toInt() }
            controller = r.controller
            render()
            layoutAt(widthPx, (heightDp * d).toInt())
        }
        return r
    }

    private fun DrawPanelView.relayout(heightDp: Int = 320) = layoutAt(widthPx, (heightDp * d).toInt())

    private fun DrawPanelView.control(label: String): View =
        controls.first { (it.contentDescription ?: (it as android.widget.TextView).text).toString() == label }

    private fun DrawPanelView.has(label: String) = controls.any { (it.contentDescription ?: (it as? android.widget.TextView)?.text).toString() == label }

    private fun DrawPanelView.tap(label: String, heightDp: Int = 320) {
        assertTrue(label, control(label).performClick())
        relayout(heightDp)
    }

    private fun assertAccessible(p: DrawPanelView, what: String) {
        assertTrue("$what has controls", p.controls.isNotEmpty())
        for (v in p.controls) {
            val name = v.contentDescription?.toString().orEmpty().ifBlank { (v as? android.widget.TextView)?.text?.toString().orEmpty() }
            assertTrue("$what: a control has no name", name.isNotBlank())
            assertTrue("$what/$name: not clickable", v.isClickable && v.isFocusable)
            assertTrue("$what/$name: ${v.measuredHeight / d}dp tall", v.measuredHeight >= (48 * d).toInt() - 1)
            assertTrue("$what/$name: ${v.measuredWidth / d}dp wide", v.measuredWidth >= (48 * d).toInt() - 1)
            assertEquals("$what/$name is one Button node", "android.widget.Button", v.createAccessibilityNodeInfo().className.toString())
        }
        assertEquals("$what: names are unique", p.controls.size, p.controls.map { it.contentDescription.toString() }.toSet().size)
    }

    @Test fun everyModeOfTheToolsAndContextRowsIsAccessible() {
        val r = rig()
        val c = r.controller
        assertAccessible(r.panel, "ink")
        r.panel.tap(str(R.string.draw_brushes))
        assertAccessible(r.panel, "brushes")
        assertEquals(9 + 7 + 8, r.panel.controls.size)
        r.panel.tap(str(R.string.draw_tool_pen))
        r.panel.tap(str(R.string.draw_paper))
        assertAccessible(r.panel, "paper")
        r.panel.tap(str(R.string.draw_colours))
        r.panel.tap(str(R.string.draw_shapes))
        assertAccessible(r.panel, "shapes")
        assertEquals("tools + 4 shapes + fill + 10 colours + 8", 9 + 5 + 10 + 8, r.panel.controls.size)
        r.panel.tap(str(R.string.draw_text))
        assertAccessible(r.panel, "text")
        assertEquals("tools + 4 sizes + bold + 10 colours + 8", 9 + 5 + 10 + 8, r.panel.controls.size)
        r.panel.tap(str(R.string.draw_select))
        assertAccessible(r.panel, "select, nothing picked")
        c.selectShape(ShapeKind.RECTANGLE)
        c.placeShape(10f, 10f, 80f, 60f)
        r.panel.tap(str(R.string.draw_select))
        assertAccessible(r.panel, "select, a shape")
        assertTrue(r.panel.has(str(R.string.draw_shape_fill)))
        assertFalse(r.panel.has(str(R.string.draw_text_edit)))
        c.selectTextTool()
        c.beginText(5f, 100f)
        c.textField.set("note")
        c.endTyping()
        r.panel.relayout()
        r.panel.tap(str(R.string.draw_select))
        assertAccessible(r.panel, "select, a text")
        assertTrue(r.panel.has(str(R.string.draw_text_edit)))
        assertTrue(r.panel.has(str(R.string.draw_text_bold)))
        assertFalse(r.panel.has(str(R.string.draw_shape_fill)))
    }

    @Test fun theToolsRowSwitchesWhatATouchDoesAndTheCanvasFollows() {
        val r = rig()
        val c = r.controller
        r.panel.tap(str(R.string.draw_shapes))
        assertEquals(DrawInput.SHAPE, c.input)
        assertEquals(DrawInput.SHAPE, r.panel.canvasView.input)
        r.panel.tap(str(R.string.draw_shape_ellipse))
        assertEquals(ShapeKind.ELLIPSE, c.shapeKind)
        assertEquals(ShapeKind.ELLIPSE, r.panel.canvasView.shapeKind)
        r.panel.tap(str(R.string.draw_shape_fill))
        assertTrue(c.shapeFilled)
        r.panel.tap(str(R.string.draw_text))
        assertEquals(DrawInput.TEXT, c.input)
        r.panel.tap(str(R.string.draw_text_size_large))
        assertEquals(TextSize.LARGE, c.textSize)
        r.panel.tap(str(R.string.draw_text_bold))
        assertTrue(c.textBold)
        r.panel.tap(str(R.string.draw_select))
        assertEquals(DrawInput.SELECT, c.input)
        r.panel.tap(str(R.string.draw_tool_eraser))
        assertEquals(DrawInput.INK, c.input)
        assertEquals(DrawTool.ERASER, c.tool)
    }

    @Test fun theBrushListPicksEveryBrushAndTheHighlighter() {
        val r = rig()
        val c = r.controller
        for ((name, brush) in listOf(
            R.string.draw_brush_marker to Brush.MARKER, R.string.draw_brush_pencil to Brush.PENCIL, R.string.draw_brush_dashed to Brush.DASHED,
            R.string.draw_brush_dotted to Brush.DOTTED, R.string.draw_brush_calligraphy to Brush.CALLIGRAPHY, R.string.draw_tool_pen to Brush.PEN,
        )) {
            r.panel.tap(str(R.string.draw_brushes))
            r.panel.tap(str(name))
            assertEquals(brush, c.brush)
            assertEquals(brush, r.panel.canvasView.brush)
            assertEquals(DrawTool.PEN, c.tool)
            assertEquals("the list closes after a pick", DrawOptions.COLOURS, c.options)
        }
        r.panel.tap(str(R.string.draw_brushes))
        r.panel.tap(str(R.string.draw_tool_highlighter))
        assertEquals(DrawTool.HIGHLIGHTER, c.tool)
    }

    @Test fun everyPaperIsOfferedAndAppliedToTheCanvas() {
        val r = rig()
        r.panel.tap(str(R.string.draw_paper))
        assertEquals(DrawBackground.PRESETS.size, DrawBackground.PRESETS.toSet().size)
        for (bg in DrawBackground.PRESETS) {
            val name = when (bg) {
                DrawBackground.WHITE -> R.string.draw_bg_white
                DrawBackground.BLACK -> R.string.draw_bg_black
                DrawBackground.TRANSPARENT -> R.string.draw_bg_transparent
                DrawBackground.CREAM -> R.string.draw_bg_cream
                DrawBackground.MINT -> R.string.draw_bg_mint
                DrawBackground.SKY -> R.string.draw_bg_sky
                DrawBackground.BLUSH -> R.string.draw_bg_blush
                DrawBackground.LAVENDER -> R.string.draw_bg_lavender
                DrawBackground.SLATE -> R.string.draw_bg_slate
                DrawBackground.RULED -> R.string.draw_bg_ruled
                DrawBackground.GRID -> R.string.draw_bg_grid
                else -> R.string.draw_bg_dots
            }
            r.panel.tap(str(R.string.draw_bg_desc, str(name)))
            assertEquals(bg, r.controller.session.background)
            assertEquals(bg, r.panel.canvasView.session.background)
        }
    }

    @Test fun theStatusLineHintsAtTheToolInHandAndClearsWhenSomethingIsPicked() {
        val r = rig()
        assertEquals("", r.panel.statusText.toString())
        r.panel.tap(str(R.string.draw_shapes))
        assertEquals(str(R.string.draw_hint_shape), r.panel.statusText.toString())
        r.panel.tap(str(R.string.draw_text))
        assertEquals(str(R.string.draw_hint_text), r.panel.statusText.toString())
        r.panel.tap(str(R.string.draw_select))
        assertEquals(str(R.string.draw_hint_select), r.panel.statusText.toString())
        r.controller.selectShape(ShapeKind.LINE)
        r.controller.placeShape(0f, 0f, 50f, 50f)
        r.panel.relayout()
        assertEquals("", r.panel.statusText.toString())
    }

    @Test fun clearIsEnabledByAnObjectAndItsConfirmClearsObjectsToo() {
        val r = rig()
        val c = r.controller
        c.selectShape(ShapeKind.RECTANGLE)
        c.placeShape(10f, 10f, 70f, 60f)
        r.panel.relayout()
        r.panel.tap(str(R.string.draw_clear))
        assertTrue(c.clearArmed)
        r.panel.tap(str(R.string.draw_clear_confirm))
        assertTrue(c.session.objects.isEmpty())
        r.panel.tap(str(R.string.draw_undo))
        assertEquals(1, c.session.objects.size)
    }

    // ---- the typing strip --------------------------------------------------------------------------------------------

    @Test fun theTypingStripEditsTheDrawingsTextField() {
        val r = rig()
        val c = r.controller
        c.selectTextTool()
        c.beginText(10f, 10f)
        val bar = DrawFieldBar(ctx).apply { controller = c }
        bar.render()
        assertTrue(bar.field.editor.buffer === c.textField.buffer)
        assertTrue(bar.field.label.contains(TextObject.MAX_CHARS.toString()))
        c.textField.set("abc")
        bar.render()
        assertTrue(bar.field.label.contains("3"))
        // Nothing is typed: the strip leaves the field alone.
        c.endTyping()
        bar.render()
    }

    // ---- the gallery -----------------------------------------------------------------------------------------------

    private fun save(r: Rig, n: Int) = repeat(n) { r.store.save(null, Drawing(60f, 40f, strokes = listOf(Stroke(listOf(Pt(5f, 5f), Pt(40f, 30f)), 0, 3f)), objects = listOf(TextObject(0, "x", 3f, 3f)))) }

    @Test fun theGalleryPagesThroughThumbnailsAndFreesTheOnesLeftBehind() {
        val r = rig()
        save(r, DrawController.GALLERY_PAGE + 4)
        r.panel.tap(str(R.string.draw_gallery))
        assertAccessible(r.panel, "gallery")
        assertEquals(DrawController.GALLERY_PAGE, r.panel.thumbnailCount)
        assertTrue(r.panel.has(str(R.string.draw_gallery_previous)))
        r.panel.tap(str(R.string.draw_gallery_next))
        assertEquals(1, r.controller.galleryPage)
        assertEquals("only the second page's four are alive", 4, r.panel.thumbnailCount)
        r.panel.tap(str(R.string.draw_gallery_previous))
        assertEquals(DrawController.GALLERY_PAGE, r.panel.thumbnailCount)
        r.panel.release()
        assertEquals(0, r.panel.thumbnailCount)
    }

    @Test fun theGalleryNamesItsPageAndSaysSoWhenFull() {
        val r = rig()
        save(r, DrawController.GALLERY_PAGE + 1)
        r.panel.tap(str(R.string.draw_gallery))
        assertNotNull(findText(r.panel, str(R.string.draw_gallery_count, 1, 2, DrawController.GALLERY_PAGE + 1)))
        // Fill it to the cap: the caption says it is full, and a save says so on the canvas.
        save(r, DrawingStore.MAX_DRAWINGS - r.store.count())
        r.controller.closeGallery()
        r.panel.relayout()
        r.controller.session.addStroke(Stroke(listOf(Pt(1f, 1f), Pt(9f, 9f)), 0, 3f))
        r.panel.tap(str(R.string.draw_save))
        assertEquals(DrawStatus.GALLERY_FULL, r.controller.status)
        assertEquals(str(R.string.draw_status_gallery_full, DrawingStore.MAX_DRAWINGS), r.panel.statusText.toString())
        r.panel.tap(str(R.string.draw_gallery))
        assertNotNull(findText(r.panel, str(R.string.draw_gallery_full)))
        assertEquals(DrawingStore.MAX_DRAWINGS, r.store.count())
    }

    @Test fun anEmptyGalleryHasTheHintAndNoPageCaption() {
        val r = rig()
        r.panel.tap(str(R.string.draw_gallery))
        assertNotNull(findText(r.panel, str(R.string.draw_gallery_empty)))
        assertNull(findText(r.panel, str(R.string.draw_gallery_count, 1, 1, 0)))
    }

    private fun findText(root: View, text: String): android.widget.TextView? {
        if (root is android.widget.TextView && root.text.toString() == text) return root
        if (root is android.view.ViewGroup) for (i in 0 until root.childCount) findText(root.getChildAt(i), text)?.let { return it }
        return null
    }

    // ---- fit -------------------------------------------------------------------------------------------------------

    private fun inRowsFit(r: Rig, what: String, scrollsAllowed: Boolean) {
        val p = r.panel
        if (!scrollsAllowed) assertFalse("$what scrolls", p.scrolls)
        assertTrue("$what: canvas ${p.canvasView.height / d}dp", p.canvasView.height >= (DrawPanelView.MIN_CANVAS_DP * d).toInt() - 2)
        for (v in p.controls) {
            var y = 0
            var x = 0
            var q: View = v
            var inStrip = false
            while (q !== p) {
                y += q.top
                x += q.left
                q = q.parent as View
                if (q is HorizontalScrollView) inStrip = true
            }
            assertTrue("$what/${v.contentDescription}: ${v.height / d}dp tall", v.height >= (48 * d).toInt() - 1)
            if (!scrollsAllowed) assertTrue("$what/${v.contentDescription}: bottom ${(y + v.height) / d}dp", y >= 0 && y + v.height <= p.height)
            // A control in a sideways-scrolling row may be past the edge, but the row must be one that scrolls.
            if (x + v.width > p.width) assertTrue("$what/${v.contentDescription} is cut off and its row does not scroll", inStrip)
        }
    }

    @Test fun everyModeFitsTheAiPanelHeightWithoutScrollingOrOverflow() {
        // The Draw panel takes the AI panels' height: the 251dp key area plus a little (ADR 0044).
        val dm = ctx.resources.displayMetrics
        val panelDp = (KeyboardMetrics.aiPanelHeightPx((251 * d).toInt(), d, dm.heightPixels, landscape = false) / d).toInt()
        assertTrue("the AI panel height is about 307dp: $panelDp", panelDp in 300..320)
        val r = rig(panelDp)
        for ((what, setup) in modes(r)) {
            setup()
            r.panel.relayout(panelDp)
            inRowsFit(r, what, scrollsAllowed = false)
        }
    }

    @Test fun inTheBareKeyAreaNothingIsClippedTheCanvasKeepsItsMinimumAndRowsScrollSideways() {
        val r = rig(251)
        for ((what, setup) in modes(r)) {
            setup()
            r.panel.relayout(251)
            // At 251dp the three rows and a usable canvas do not fit together: the panel scrolls up and down (never clips).
            inRowsFit(r, "251dp $what", scrollsAllowed = true)
        }
    }

    private fun modes(r: Rig): List<Pair<String, () -> Unit>> {
        val c = r.controller
        return listOf(
            "ink" to { c.selectBrush(Brush.PEN); Unit },
            "brushes" to { c.toggleBrushes() },
            "paper" to { c.togglePaper() },
            "shapes" to { c.selectShape(ShapeKind.ARROW) },
            "text" to { c.selectTextTool() },
            "select" to { c.selectSelectTool() },
        )
    }

    @Test fun landscapeAndNarrowSizesBuildAndDrawInEveryModeAndTheme() {
        val r = rig()
        val c = r.controller
        c.selectShape(ShapeKind.ELLIPSE)
        c.placeShape(20f, 20f, 120f, 90f)
        c.selectTextTool()
        c.beginText(30f, 100f)
        c.textField.set("ሰላም")
        c.endTyping()
        c.session.addStroke(Stroke(listOf(Pt(5f, 5f), Pt(90f, 60f)), 0xFF000000.toInt(), 6f, DrawTool.PEN, Brush.CALLIGRAPHY))
        for ((w, h) in listOf(411 to 307, 800 to 190, 320 to 260)) {
            r.panel.layoutAt((w * d).toInt(), (h * d).toInt())
            r.panel.draw(Canvas(Bitmap.createBitmap((w * d).toInt(), (h * d).toInt(), Bitmap.Config.ARGB_8888)))
        }
        for (theme in ThemeCatalog.all) {
            r.panel.theme = theme
            for ((_, setup) in modes(r)) {
                setup()
                r.panel.relayout(307)
                r.panel.draw(Canvas(Bitmap.createBitmap(widthPx, (307 * d).toInt(), Bitmap.Config.ARGB_8888)))
            }
        }
    }
}
