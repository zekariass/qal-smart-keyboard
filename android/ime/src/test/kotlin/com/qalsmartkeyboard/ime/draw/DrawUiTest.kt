package com.qalsmartkeyboard.ime.draw

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputContentInfo
import androidx.core.view.inputmethod.EditorInfoCompat
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.share.FakeImageShare
import com.qalsmartkeyboard.ime.share.PngContent
import com.qalsmartkeyboard.ime.share.SystemImageShare
import com.qalsmartkeyboard.ime.theme.ThemeCatalog
import com.qalsmartkeyboard.ime.toolbar.DrawPanelView
import com.qalsmartkeyboard.ime.toolbar.QrPanelView
import com.qalsmartkeyboard.ime.utilities.QrController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

/**
 * T8.4: the Draw & Sketch views and the image export / insert plumbing under Robolectric: the PNG export (header, size,
 * crop, transparent background, the eraser), the canvas view's touch handling, the panel's controls (48dp, spoken names,
 * every theme) and gallery, `commitContent` / share sheet through [SystemImageShare], and the QR panel's image buttons.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real Canvas and Bitmap pixels for the export tests
class DrawUiTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val d = ctx.resources.displayMetrics.density
    private val widthPx = (411 * d).toInt()
    private lateinit var dir: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("draw-ui").toFile()
        // The manifest's FileProvider, registered the way the merged app manifest declares it (also checks the paths file).
        val info = android.content.pm.ProviderInfo().apply {
            authority = SystemImageShare.authority(ctx)
            exported = false
            grantUriPermissions = true
            packageName = ctx.packageName
            metaData = android.os.Bundle().apply { putInt("android.support.FILE_PROVIDER_PATHS", R.xml.draw_file_paths) }
        }
        org.robolectric.Robolectric.buildContentProvider(androidx.core.content.FileProvider::class.java).create(info)
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun View.layoutAt(w: Int, h: Int) {
        measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        layout(0, 0, measuredWidth, measuredHeight)
    }

    private fun line(colour: Int = 0xFFFF0000.toInt(), tool: DrawTool = DrawTool.PEN, w: Float = 10f) =
        Stroke(listOf(Pt(50f, 50f), Pt(150f, 50f), Pt(250f, 50f)), colour, w, tool)

    private fun pngSize(f: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        return o.outWidth to o.outHeight
    }

    // ---- export --------------------------------------------------------------------------------------------------

    @Test fun exportWritesAValidPngOfTheRequestedSize() {
        val drawing = Drawing(400f, 200f, strokes = listOf(line()))
        val f = File(dir, "out/a.png")
        assertTrue(PngDrawingExporter().exportPng(drawing, ExportOptions(longestSidePx = 1024, cropToContent = false), f))
        val head = f.readBytes().copyOf(8)
        assertEquals(listOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), head.map { it.toInt() and 0xFF })
        assertEquals(1024 to 512, pngSize(f))
        assertFalse("no temp file is left behind", File(dir, "out/a.png.tmp").exists())
    }

    @Test fun exportCropsToTheInkWithAMargin() {
        val drawing = Drawing(400f, 400f, strokes = listOf(line()))
        val f = File(dir, "crop.png")
        PngDrawingExporter().exportPng(drawing, ExportOptions(longestSidePx = 500, cropToContent = true, marginUnits = 10f), f)
        val (w, h) = pngSize(f)
        assertEquals(500, w)
        assertTrue("$h", h in 60..70) // 10 units of ink plus a 10 unit margin each side: 30 tall over 230 wide
    }

    private fun render(d: Drawing, withBackground: Boolean = true): Bitmap =
        DrawingRenderer().render(d, ExportPlan.of(d, ExportOptions(longestSidePx = 400, cropToContent = false)), withBackground)

    @Test fun aTransparentBackgroundExportsWithAlphaZeroAndTheInkOpaque() {
        val bmp = render(Drawing(400f, 100f, DrawBackground.TRANSPARENT, listOf(line())))
        assertEquals(0, bmp.getPixel(2, 2) ushr 24)
        assertEquals(0xFFFF0000.toInt(), bmp.getPixel(150, 50))
        bmp.recycle()
    }

    @Test fun theChosenBackgroundIsPaintedAndCanBeLeftOut() {
        val withBg = render(Drawing(400f, 100f, DrawBackground.BLACK, listOf(line())))
        assertEquals(0xFF000000.toInt(), withBg.getPixel(2, 2))
        val without = render(Drawing(400f, 100f, DrawBackground.BLACK, listOf(line())), withBackground = false)
        assertEquals(0, without.getPixel(2, 2) ushr 24)
        assertEquals(0xFFFF0000.toInt(), without.getPixel(150, 50))
    }

    @Test fun theEraserClearsInkToTransparentNotToWhite() {
        val eraser = Stroke(listOf(Pt(100f, 50f), Pt(200f, 50f)), 0, 4f, DrawTool.ERASER)
        val bmp = render(Drawing(400f, 100f, DrawBackground.TRANSPARENT, listOf(line(), eraser)))
        assertEquals("erased ink is transparent", 0, bmp.getPixel(150, 50) ushr 24)
        assertEquals("ink outside the eraser stays", 0xFFFF0000.toInt(), bmp.getPixel(60, 50))
        val onWhite = render(Drawing(400f, 100f, DrawBackground.WHITE, listOf(line(), eraser)))
        assertEquals("on a white page the erase shows the page", 0xFFFFFFFF.toInt(), onWhite.getPixel(150, 50))
    }

    @Test fun theHighlighterIsTranslucent() {
        val hl = Stroke(listOf(Pt(50f, 50f), Pt(250f, 50f)), 0xFFFFFF00.toInt(), 6f, DrawTool.HIGHLIGHTER)
        val bmp = render(Drawing(400f, 100f, DrawBackground.WHITE, listOf(hl)))
        val a = bmp.getPixel(150, 50)
        assertTrue("the page shows through", (a shr 16 and 0xFF) == 255 && (a and 0xFF) in 100..200)
    }

    @Test fun thumbnailIsTheWholeCanvasAtTheRequestedSize() {
        val bmp = DrawingRenderer().thumbnail(Drawing(300f, 100f, strokes = listOf(line())), 120)
        assertEquals(120, bmp.width); assertEquals(40, bmp.height)
        bmp.recycle()
    }

    // ---- the canvas view -----------------------------------------------------------------------------------------

    private fun canvas(sessionW: Float = 411f, sessionH: Float = 300f): DrawCanvasView {
        val v = DrawCanvasView(ctx)
        v.session = DrawSession(sessionW, sessionH)
        v.layoutAt((411 * d).toInt(), (300 * d).toInt())
        return v
    }

    private var t0 = SystemClock.uptimeMillis()
    private fun ev(action: Int, x: Float, y: Float, hist: List<Pair<Float, Float>> = emptyList()): MotionEvent {
        val e = MotionEvent.obtain(t0, t0 + 10, action, x, y, 0.7f, 1f, 0, 1f, 1f, 0, 0)
        for ((hx, hy) in hist) e.addBatch(t0 + 5, hx, hy, 0.6f, 1f, 0)
        return e
    }

    @Test fun aStrokeIsRecordedWithHistoryAndPressure() {
        val v = canvas()
        var added = 0
        v.onStrokeAdded = { added++ }
        v.colour = 0xFF00FF00.toInt()
        v.widthUnits = 8f
        assertTrue(v.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 100f, 100f)))
        // One MOVE event carrying two batched (historical) samples and a current one: all three are kept.
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, 100f, 100f).apply { addBatch(t0 + 12, 160f, 140f, 0.6f, 1f, 0); addBatch(t0 + 14, 220f, 180f, 0.5f, 1f, 0) })
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 300f, 220f))
        assertEquals(1, added)
        val s = v.session.strokes.single()
        assertEquals(DrawTool.PEN, s.tool); assertEquals(0xFF00FF00.toInt(), s.colour); assertEquals(8f, s.widthDp)
        assertTrue("${s.points.size}", s.points.size >= 4)
        assertTrue(s.points.all { it.pressure in 0f..1f })
        assertEquals(0, v.liveSamples)
    }

    @Test fun aSecondFingerOrPalmIsIgnored() {
        val v = canvas()
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 100f, 100f))
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }, MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { x = 120f; y = 120f; pressure = 1f }, MotionEvent.PointerCoords().apply { x = 400f; y = 400f; pressure = 1f })
        val pointerDown = MotionEvent.obtain(t0, t0 + 20, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        v.dispatchTouchEvent(pointerDown)
        val move = MotionEvent.obtain(t0, t0 + 30, MotionEvent.ACTION_MOVE, 2, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        v.dispatchTouchEvent(move)
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 130f, 130f))
        val s = v.session.strokes.single()
        val maxX = s.points.maxOf { it.x }
        assertTrue("the second pointer at x=400 left no ink: max x = $maxX", maxX < 300f)
    }

    @Test fun aCancelledStrokeIsDiscarded() {
        val v = canvas()
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 100f, 100f))
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, 160f, 150f))
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_CANCEL, 160f, 150f))
        assertTrue(v.session.strokes.isEmpty())
        assertEquals(0, v.liveSamples)
    }

    @Test fun aTapMakesADot() {
        val v = canvas()
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 100f, 100f))
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 100f, 100f))
        assertEquals(1, v.session.strokes.single().points.size)
    }

    @Test fun theViewDrawsEveryToolBackgroundAndALetterboxedResizeWithoutErrors() {
        val v = canvas()
        for (bg in DrawBackground.PRESETS) {
            v.session.setBackground(bg)
            for (tool in DrawTool.entries) {
                v.tool = tool
                v.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 50f, 50f))
                v.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, 90f, 80f))
                v.draw(Canvas(Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888))) // highlighter overlay while down
                v.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 140f, 90f))
            }
            v.draw(Canvas(Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)))
        }
        v.session.undo(); v.session.redo(); v.session.clear()
        v.layoutAt(widthPx / 2, (150 * d).toInt()) // a drawn canvas keeps its size and is fitted
        v.draw(Canvas(Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)))
        v.release()
    }

    @Test fun anEmptyCanvasTakesTheViewsSize() {
        val v = DrawCanvasView(ctx)
        v.layoutAt((300 * d).toInt(), (200 * d).toInt())
        assertEquals(300f, v.session.width, 0.5f)
        assertEquals(200f, v.session.height, 0.5f)
    }

    // ---- the panel -----------------------------------------------------------------------------------------------

    private class Rig(val ctx: Context, val dir: File, val share: FakeImageShare = FakeImageShare()) {
        val store = DrawingStore(File(dir, "drawings"))
        lateinit var panel: DrawPanelView
        val controller = DrawController(store, PngDrawingExporter(), { share }, onChanged = { panel.refresh() })
        var inserted = 0
    }

    private fun rig(heightDp: Int = 300): Rig {
        val r = Rig(ctx, dir)
        r.panel = DrawPanelView(ctx).apply {
            heightProvider = { (heightDp * d).toInt() }
            controller = r.controller
            onInserted = { r.inserted++ }
            render()
            layoutAt(widthPx, (heightDp * d).toInt())
        }
        return r
    }

    private fun DrawPanelView.relayout(heightDp: Int = 300) = layoutAt(widthPx, (heightDp * d).toInt())

    private fun DrawPanelView.control(label: String): View =
        controls.first { (it.contentDescription ?: (it as android.widget.TextView).text).toString() == label }

    private fun DrawPanelView.tap(label: String) {
        assertTrue(label, control(label).performClick())
        relayout()
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
        assertEquals("names are unique", p.controls.size, p.controls.map { it.contentDescription.toString() }.toSet().size)
    }

    @Test fun canvasControlsAre48dpWithSpokenNamesInBothPaletteModes() {
        val r = rig()
        assertAccessible(r.panel, "canvas")
        // 7 tools + 10 colours + 4 widths + 4 actions
        assertEquals(25, r.panel.controls.size)
        r.panel.tap(ctx.getString(R.string.draw_paper))
        assertAccessible(r.panel, "paper")
        assertEquals(7 + DrawBackground.PRESETS.size + 4 + 4, r.panel.controls.size)
        assertNotNull(r.panel.control(ctx.getString(R.string.draw_colours)))
    }

    @Test fun galleryControlsAre48dpWithSpokenNames() {
        val r = rig()
        repeat(4) { r.store.save(null, Drawing(100f, 100f, strokes = listOf(line()))) }
        r.controller.openGallery()
        r.panel.relayout()
        assertAccessible(r.panel, "gallery")
        val first = r.controller.gallery.first().id
        r.controller.select(first)
        r.panel.relayout()
        assertAccessible(r.panel, "gallery selection")
        assertTrue(r.panel.controls.any { it.contentDescription == ctx.getString(R.string.draw_gallery_delete) })
    }

    @Test fun toolColourWidthAndBackgroundButtonsDriveTheController() {
        val r = rig()
        val c = r.controller
        r.panel.tap(ctx.getString(R.string.draw_tool_eraser))
        assertEquals(DrawTool.ERASER, c.tool)
        r.panel.tap(ctx.getString(R.string.draw_colour_desc, ctx.getString(R.string.draw_colour_red)))
        assertEquals(DrawTool.PEN, c.tool)
        assertEquals(InkDefaults.PALETTE[2], c.colour)
        r.panel.tap(ctx.getString(R.string.draw_width_thick))
        assertEquals(2, c.widthIndex)
        r.panel.tap(ctx.getString(R.string.draw_tool_highlighter))
        assertEquals(DrawTool.HIGHLIGHTER, c.tool)
        r.panel.tap(ctx.getString(R.string.draw_paper))
        r.panel.tap(ctx.getString(R.string.draw_bg_desc, ctx.getString(R.string.draw_bg_black)))
        assertEquals(DrawBackground.BLACK, c.session.background)
        assertEquals(InkDefaults.PALETTE[2], r.panel.canvasView.colour)
        assertEquals(DrawBackground.BLACK, r.panel.canvasView.session.background)
    }

    @Test fun undoRedoClearAndTheConfirmThroughThePanel() {
        val r = rig()
        val c = r.controller
        c.session.addStroke(line()); c.strokeAdded()
        r.panel.relayout()
        r.panel.tap(ctx.getString(R.string.draw_undo))
        assertTrue(c.session.strokes.isEmpty())
        r.panel.tap(ctx.getString(R.string.draw_redo))
        assertEquals(1, c.session.strokes.size)
        r.panel.tap(ctx.getString(R.string.draw_clear))
        assertEquals("the first tap only asks", 1, c.session.strokes.size)
        assertEquals(ctx.getString(R.string.draw_clear_confirm), r.panel.statusText.toString())
        r.panel.tap(ctx.getString(R.string.draw_clear_confirm))
        assertTrue(c.session.strokes.isEmpty())
    }

    @Test fun saveInsertAndShareButtonsReportThroughTheStatusLine() {
        val r = rig()
        r.panel.tap(ctx.getString(R.string.draw_save))
        assertEquals(ctx.getString(R.string.draw_status_nothing), r.panel.statusText.toString())
        r.controller.session.addStroke(line()); r.controller.strokeAdded()
        r.panel.relayout()
        r.panel.tap(ctx.getString(R.string.draw_save))
        assertEquals(ctx.getString(R.string.draw_status_saved), r.panel.statusText.toString())
        assertEquals(1, r.store.count())
        assertTrue(r.store.pngFile(r.controller.currentId!!).length() > 0)
        r.panel.tap(ctx.getString(R.string.draw_insert))
        assertEquals(1, r.inserted)
        assertEquals(1, r.share.inserted.size)
        r.share.insertable = false
        r.panel.tap(ctx.getString(R.string.draw_insert))
        assertEquals("a share sheet is not an insert", 1, r.inserted)
        assertEquals(ctx.getString(R.string.draw_status_shared), r.panel.statusText.toString())
        r.panel.tap(ctx.getString(R.string.draw_share))
        assertEquals(2, r.share.shared.size)
    }

    @Test fun galleryListsOpensDuplicatesAndDeletesThroughThePanel() {
        val r = rig()
        repeat(DrawingStore.MAX_LISTED + 5) { r.store.save(null, Drawing(60f, 40f, strokes = listOf(Stroke(listOf(Pt(5f, 5f), Pt(40f, 30f)), 0, 3f)))) }
        r.panel.tap(ctx.getString(R.string.draw_gallery))
        assertEquals(DrawingStore.MAX_LISTED, r.controller.gallery.size)
        assertEquals("thumbnails are rendered for every shown drawing", DrawingStore.MAX_LISTED, r.panel.thumbnailCount)
        val first = r.controller.gallery.first().id
        r.panel.controls.first { it.contentDescription == ctx.getString(R.string.draw_gallery_item, 1, DrawingStore.MAX_LISTED) }.performClick()
        r.panel.relayout()
        assertEquals(first, r.controller.selectedId)
        r.panel.tap(ctx.getString(R.string.draw_gallery_duplicate))
        assertEquals(DrawingStore.MAX_LISTED + 6, r.store.count())
        r.panel.tap(ctx.getString(R.string.draw_gallery_delete))
        assertEquals("delete asks first", DrawingStore.MAX_LISTED + 6, r.store.count())
        r.panel.tap(ctx.getString(R.string.draw_gallery_delete_confirm))
        assertEquals(DrawingStore.MAX_LISTED + 5, r.store.count())
        r.controller.select(r.controller.gallery.first().id)
        r.panel.relayout()
        r.panel.tap(ctx.getString(R.string.draw_gallery_edit))
        assertEquals(DrawMode.CANVAS, r.controller.mode)
        assertEquals(1, r.controller.session.strokes.size)
        r.panel.release()
        assertEquals(0, r.panel.thumbnailCount)
    }

    @Test fun anEmptyGalleryShowsAHint() {
        val r = rig()
        r.panel.tap(ctx.getString(R.string.draw_gallery))
        r.panel.relayout()
        assertEquals(0, r.panel.thumbnailCount)
        assertTrue(r.panel.controls.any { it.contentDescription == ctx.getString(R.string.draw_gallery_new) })
    }

    @Test fun theCanvasKeepsAUsableMinimumHeightAndThePanelScrollsWhenTheKeyboardIsShort() {
        val r = rig(heightDp = 180)
        assertTrue("${r.panel.canvasView.height / d}dp", r.panel.canvasView.height >= (DrawPanelView.MIN_CANVAS_DP * d).toInt() - 2)
        val tall = rig(heightDp = 400)
        assertTrue(tall.panel.canvasView.height > r.panel.canvasView.height)
    }

    @Test fun landscapeAndPortraitSizesBuildAndDraw() {
        for ((w, h) in listOf(411 to 300, 800 to 190, 320 to 260)) {
            val r = rig(h)
            r.panel.layoutAt((w * d).toInt(), (h * d).toInt())
            r.panel.draw(Canvas(Bitmap.createBitmap((w * d).toInt(), (h * d).toInt(), Bitmap.Config.ARGB_8888)))
        }
    }

    @Test fun thePanelDrawsInEveryTheme() {
        val r = rig()
        for (theme in ThemeCatalog.all) {
            r.panel.theme = theme
            r.panel.render()
            r.panel.relayout()
            r.panel.draw(Canvas(Bitmap.createBitmap(widthPx, (300 * d).toInt(), Bitmap.Config.ARGB_8888)))
        }
        r.controller.openGallery()
        for (theme in ThemeCatalog.all.take(3)) {
            r.panel.theme = theme
            r.panel.relayout()
            r.panel.draw(Canvas(Bitmap.createBitmap(widthPx, (300 * d).toInt(), Bitmap.Config.ARGB_8888)))
        }
    }

    // ---- insert and share through the system ---------------------------------------------------------------------

    private fun png(): File = File(ctx.filesDir, "drawings/png/x.png").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }

    private class RecordingConnection(view: View) : BaseInputConnection(view, false) {
        var committed: InputContentInfo? = null
        var flags = -1
        override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: android.os.Bundle?): Boolean {
            committed = inputContentInfo
            this.flags = flags
            return true
        }
    }

    private fun info(vararg mimes: String) = EditorInfo().apply {
        packageName = "com.example.chat"
        if (mimes.isNotEmpty()) EditorInfoCompat.setContentMimeTypes(this, arrayOf(*mimes))
    }

    @Test fun pngContentMatchesExactAndWildcardMimeTypes() {
        assertTrue(PngContent.accepts(arrayOf("image/png")))
        assertTrue(PngContent.accepts(arrayOf("image/gif", "image/*")))
        assertFalse(PngContent.accepts(arrayOf("image/gif", "image/webp")))
        assertFalse(PngContent.accepts(emptyArray()))
        assertFalse(PngContent.accepts(null))
    }

    @Test fun commitContentIsUsedOnlyWhenTheFieldTakesPng() {
        val view = View(ctx)
        val ic = RecordingConnection(view)
        var ei: EditorInfo? = info("image/png")
        val sys = SystemImageShare(ctx, SystemImageShare.authority(ctx), { ei }, { ic })
        assertTrue(sys.canInsert())
        assertTrue(sys.insert(png(), "Drawing"))
        val committed = ic.committed!!
        assertEquals("image/png", committed.description.getMimeType(0))
        assertTrue(committed.contentUri.toString().startsWith("content://" + SystemImageShare.authority(ctx)))
        assertEquals(android.view.inputmethod.InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, ic.flags)

        ei = info("image/gif")
        assertFalse(sys.canInsert())
        ei = info()
        assertFalse(sys.canInsert())
        ei = null
        assertFalse(sys.canInsert())
        assertFalse(SystemImageShare(ctx, SystemImageShare.authority(ctx), { info("image/png") }, { null }).canInsert())
    }

    @Test fun theShareSheetIsALaunchableChooserWithAGrantedClipData() {
        val sys = SystemImageShare(ctx, SystemImageShare.authority(ctx), { null }, { null })
        assertTrue(sys.share(png(), "Share drawing"))
        val chooser = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/png", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val clip: ClipData = send.clipData!!
        assertEquals(1, clip.itemCount)
        assertEquals(clip.getItemAt(0).uri, send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
    }

    // ---- the QR panel's image buttons ----------------------------------------------------------------------------

    @Test fun qrPanelInsertsOrSharesTheCodeImage() {
        val share = FakeImageShare()
        lateinit var p: QrPanelView
        val qr = QrController(onChanged = { p.refresh() })
        var inserted = 0
        p = QrPanelView(ctx).apply {
            controller = qr
            imageShare = share
            imageFile = File(dir, "drawings/png/_qr.png")
            onImageInserted = { inserted++ }
            heightProvider = { (300 * d).toInt() }
            render()
            layoutAt(widthPx, (300 * d).toInt())
        }
        fun tap(label: String) {
            val v = p.controls.first { (it.contentDescription ?: (it as android.widget.TextView).text).toString() == label }
            assertTrue(v.performClick())
        }
        tap(ctx.getString(R.string.qr_insert_image))
        assertTrue("nothing to send yet", share.log.isEmpty())
        qr.setSource("hello")
        tap(ctx.getString(R.string.qr_insert_image))
        assertEquals(listOf("insert"), share.log)
        assertEquals(1, inserted)
        assertTrue(pngSize(p.imageFile!!).first in 400..512)
        share.insertable = false
        tap(ctx.getString(R.string.qr_insert_image))
        assertEquals(1, inserted)
        tap(ctx.getString(R.string.qr_share_image))
        assertEquals(listOf("insert", "share", "share"), share.log)
    }

    @Test fun qrPanelWithoutAnImageShareHasNoImageButtons() {
        val p = QrPanelView(ctx).apply { heightProvider = { (300 * d).toInt() }; render(); layoutAt(widthPx, (300 * d).toInt()) }
        assertFalse(p.controls.any { it.contentDescription == ctx.getString(R.string.qr_insert_image) })
    }

}
