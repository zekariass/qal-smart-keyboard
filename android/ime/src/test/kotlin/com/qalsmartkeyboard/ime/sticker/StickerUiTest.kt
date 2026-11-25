package com.qalsmartkeyboard.ime.sticker

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputContentInfo
import android.widget.TextView
import androidx.core.view.inputmethod.EditorInfoCompat
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.ai.AiLink
import com.qalsmartkeyboard.ime.draw.Drawing
import com.qalsmartkeyboard.ime.draw.InkDefaults
import com.qalsmartkeyboard.ime.draw.Pt
import com.qalsmartkeyboard.ime.draw.Stroke
import com.qalsmartkeyboard.ime.share.ImageMimes
import com.qalsmartkeyboard.ime.share.PngContent
import com.qalsmartkeyboard.ime.share.SystemImageShare
import com.qalsmartkeyboard.ime.theme.ThemeCatalog
import com.qalsmartkeyboard.ime.toolbar.StickerPanelView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * T8.7 under Robolectric: the real WebP export (header, 512 x 512, alpha, both codecs), the renderer, the panel's controls
 * (48dp, spoken names, the locked packs listed with their lock, every theme) and the WebP insert / share plumbing through
 * the FileProvider.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real Canvas, text layout and Bitmap pixels
class StickerUiTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val d = ctx.resources.displayMetrics.density
    private val widthPx = (411 * d).toInt()
    private lateinit var dir: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("sticker-ui").toFile()
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

    private fun decode(f: File): Bitmap = BitmapFactory.decodeFile(f.path)

    private fun opaquePixels(b: Bitmap): Int {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return px.count { (it ushr 24) > 200 }
    }

    // ---- the WebP export -----------------------------------------------------------------------------------------

    @Test fun aTextStickerIsA512WebpWithATransparentBackground() {
        val f = File(dir, "t.webp")
        assertTrue(WebpStickerExporter().exportWebp(TextStickerSpec("Selam", 0xFF000000.toInt()), f))
        assertTrue("RIFF....WEBP", StickerFormat.isWebp(f.readBytes()))
        val b = decode(f)
        assertEquals(512, b.width)
        assertEquals(512, b.height)
        assertEquals("the corner is transparent", 0, b.getPixel(2, 2) ushr 24)
        assertTrue("the text is drawn", opaquePixels(b) > 500)
        b.recycle()
    }

    @Test fun aShapedStickerFillsItsShapeAndKeepsTheCornersClear() {
        val f = File(dir, "s.webp")
        val fill = 0xFF80DEEA.toInt()
        assertTrue(WebpStickerExporter().exportWebp(TextStickerSpec("Hi", 0xFF000000.toInt(), StickerShape.CIRCLE, fill), f))
        val b = decode(f)
        assertEquals("outside the circle", 0, b.getPixel(4, 4) ushr 24)
        assertEquals("inside the circle", 255, b.getPixel(256, 60) ushr 24)
        assertEquals(fill, b.getPixel(256, 60))
        b.recycle()
    }

    @Test fun everyShapeAndSourceRendersWithoutALeftoverTempFile() {
        val exp = WebpStickerExporter()
        val specs = StickerShape.entries.flatMap { s ->
            listOf(
                TextStickerSpec("A long line of sticker text that has to shrink to fit", 0xFF000000.toInt(), s, size = StickerTextSize.LARGE),
                EmojiStickerSpec("😀", "Caption", 0xFF000000.toInt(), s),
                EmojiStickerSpec("😀", "", 0xFF000000.toInt(), s),
            )
        } + DrawingStickerSpec(Drawing(200f, 100f, strokes = listOf(Stroke(listOf(Pt(20f, 20f), Pt(180f, 80f)), 0xFFFF0000.toInt(), 8f))))
        specs.forEachIndexed { i, spec ->
            val f = File(dir, "all$i.webp")
            assertTrue("$spec", exp.exportWebp(spec, f))
            assertTrue("$spec", StickerFormat.isWebp(f.readBytes()))
        }
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test fun theBundledPacksAllRender() {
        val exp = WebpStickerExporter()
        for (p in StickerPacks.ALL) for (s in p.stickers) {
            val f = File(dir, "${p.id.key}_${s.id}.webp")
            assertTrue(s.label, exp.exportWebp(s.spec, f))
            val b = decode(f)
            assertEquals(512, b.width)
            assertTrue(s.label, opaquePixels(b) > 5000) // a filled shape, not an empty image
            b.recycle()
        }
    }

    @Test fun aDrawingStickerKeepsItsInkAndTheEraserOnlyRemovesInk() {
        val ink = Stroke(listOf(Pt(10f, 50f), Pt(190f, 50f)), 0xFFFF0000.toInt(), 20f)
        val eraser = Stroke(listOf(Pt(100f, 0f), Pt(100f, 100f)), 0xFF000000.toInt(), 8f, com.qalsmartkeyboard.ime.draw.DrawTool.ERASER)
        val f = File(dir, "d.webp")
        assertTrue(WebpStickerExporter().exportWebp(DrawingStickerSpec(Drawing(200f, 100f, strokes = listOf(ink, eraser))), f))
        val b = decode(f)
        assertEquals("the ink is opaque red", 0xFFFF0000.toInt(), b.getPixel(150, 256))
        assertEquals("the erased column is clear", 0, b.getPixel(256, 256) ushr 24)
        assertEquals("the background stays transparent", 0, b.getPixel(256, 20) ushr 24)
        b.recycle()
        // With the paper colour the whole rounded sticker is filled.
        val g = File(dir, "dp.webp")
        assertTrue(WebpStickerExporter().exportWebp(DrawingStickerSpec(Drawing(200f, 100f, strokes = listOf(ink)), withBackground = true), g))
        val p = decode(g)
        assertEquals(255, p.getPixel(256, 20) ushr 24)
        p.recycle()
    }

    @Test fun theLegacyCodecOfOlderPhonesAlsoWritesAWebpWithAlpha() {
        val f = File(dir, "legacy.webp")
        assertTrue(WebpStickerExporter(sdk = 26).exportWebp(TextStickerSpec("Selam", 0xFF000000.toInt()), f))
        assertTrue(StickerFormat.isWebp(f.readBytes()))
        val b = decode(f)
        assertEquals(512, b.width)
        assertEquals(0, b.getPixel(2, 2) ushr 24)
        b.recycle()
    }

    // ---- the panel -----------------------------------------------------------------------------------------------

    private class Rig(ctx: Context, dir: File, var access: PackAccess = PackAccess.ALLOWED) {
        val store = StickerStore(File(dir, "stickers"), max = 5)
        val share = FakeStickerShare()
        val links = ArrayList<AiLink>()
        var inserted = 0
        var drawing: Drawing? = null
        val draws = ArrayList<Boolean>()
        lateinit var panel: StickerPanelView
        val controller = StickerController(
            store, WebpStickerExporter(), { share }, access = { access }, drawing = { drawing }, openLink = { links += it },
            onChanged = { panel.refresh() },
        )

        init {
            controller.onInserted = { inserted++ }
        }
    }

    private fun rig(access: PackAccess = PackAccess.ALLOWED, heightDp: Int = 300): Rig {
        val r = Rig(ctx, dir, access)
        r.panel = StickerPanelView(ctx).apply {
            heightProvider = { (heightDp * d).toInt() }
            controller = r.controller
            onOpenDraw = { fresh -> r.draws += fresh }
        }
        r.controller.open()
        r.panel.render()
        r.panel.layoutAt(widthPx, (heightDp * d).toInt())
        return r
    }

    private fun StickerPanelView.relayout(heightDp: Int = 300) = layoutAt(widthPx, (heightDp * d).toInt())

    private fun StickerPanelView.control(label: String): View =
        controls.first { (it.contentDescription ?: (it as TextView).text).toString() == label }

    private fun StickerPanelView.tap(label: String) {
        assertTrue(label, control(label).performClick())
        relayout()
    }

    private fun assertAccessible(p: StickerPanelView, what: String) {
        assertTrue("$what has controls", p.controls.isNotEmpty())
        for (v in p.controls) {
            val name = v.contentDescription?.toString().orEmpty().ifBlank { (v as? TextView)?.text?.toString().orEmpty() }
            assertTrue("$what: a control has no name", name.isNotBlank())
            assertTrue("$what/$name: not clickable", v.isClickable && v.isFocusable)
            assertTrue("$what/$name: ${v.measuredHeight / d}dp tall", v.measuredHeight >= (48 * d).toInt() - 1)
            assertTrue("$what/$name: ${v.measuredWidth / d}dp wide", v.measuredWidth >= (48 * d).toInt() - 1)
            assertEquals("$what/$name is one Button node", "android.widget.Button", v.createAccessibilityNodeInfo().className.toString())
        }
        assertEquals("names are unique", p.controls.size, p.controls.map { it.contentDescription.toString() }.toSet().size)
    }

    @Test fun theStripListsMakeMyStickersAndAllThreePacksWithLocksWhenTheyAreLocked() {
        val locked = rig(PackAccess.NOT_ON_PLAN)
        assertAccessible(locked.panel, "strip")
        for (name in listOf("Greetings", "Celebrations", "Reactions")) {
            assertNotNull("$name pack is listed", locked.panel.control(ctx.getString(R.string.sticker_pack_locked_desc, name)))
            val chip = locked.panel.control(ctx.getString(R.string.sticker_pack_locked_desc, name)) as TextView
            assertNotNull("$name shows a lock", chip.compoundDrawablesRelative[0])
        }
        assertNotNull(locked.panel.control(ctx.getString(R.string.sticker_make_desc)))
        val open = rig(PackAccess.ALLOWED)
        for (name in listOf("Greetings", "Celebrations", "Reactions")) {
            val chip = open.panel.control(name) as TextView
            assertEquals("$name has no lock when the plan has it", null, chip.compoundDrawablesRelative[0])
        }
    }

    @Test fun aLockedPackShowsItsStickersDimmedWithSeePlansAndNothingIsSent() {
        val r = rig(PackAccess.NOT_ON_PLAN)
        r.controller.selectTab(StickerPackId.GREETINGS)
        r.panel.relayout()
        assertAccessible(r.panel, "locked pack")
        val see = r.panel.control(ctx.getString(R.string.sticker_see_plans_desc))
        assertTrue(see.performClick())
        assertEquals(listOf(AiLink.UPGRADE), r.links)
        val first = r.panel.control(ctx.getString(R.string.sticker_pack_item_locked_desc, StickerPacks.GREETINGS.stickers[0].label))
        assertTrue(first.performClick())
        assertEquals(StickerStatus.LOCKED, r.controller.status)
        assertEquals(ctx.getString(R.string.sticker_status_locked), r.panel.statusText.toString())
        assertTrue(r.share.log.isEmpty())
        assertEquals(8, StickerPacks.GREETINGS.stickers.size)
    }

    @Test fun signedOutOffersSignInAndAPlanThatHasThePackOffersNoUpsell() {
        val out = rig(PackAccess.SIGN_IN)
        out.controller.selectTab(StickerPackId.REACTIONS)
        out.panel.relayout()
        assertTrue(out.panel.control(ctx.getString(R.string.sticker_sign_in_desc)).performClick())
        assertEquals(listOf(AiLink.SIGN_IN), out.links)

        val ok = rig(PackAccess.ALLOWED)
        ok.controller.selectTab(StickerPackId.REACTIONS)
        ok.panel.relayout()
        assertAccessible(ok.panel, "open pack")
        assertTrue(ok.panel.controls.none { (it.contentDescription ?: "").toString() == ctx.getString(R.string.sticker_see_plans_desc) })
        // Tapping a sticker of an open pack renders it and inserts it.
        val lol = ok.panel.control(StickerPacks.REACTIONS.stickers[0].label)
        assertTrue(lol.performClick())
        assertEquals(StickerStatus.INSERTED, ok.controller.status)
        assertEquals(1, ok.inserted)
        assertTrue(StickerFormat.isWebp(ok.share.inserted.single().first.readBytes()))
    }

    @Test fun theMakerHasAccessibleControlsForEverySource() {
        val r = rig()
        r.panel.tap(ctx.getString(R.string.sticker_make_desc))
        assertEquals(StickerScreen.MAKE, r.controller.screen)
        assertAccessible(r.panel, "text")
        r.panel.tap(ctx.getString(R.string.sticker_source_emoji))
        assertAccessible(r.panel, "emoji")
        r.controller.selectShape(StickerShape.CIRCLE)
        r.panel.relayout()
        assertAccessible(r.panel, "emoji with fills")
        r.panel.tap(ctx.getString(R.string.sticker_source_drawing))
        assertAccessible(r.panel, "drawing")
    }

    @Test fun theMakerFitsTheKeyAreaWithoutScrolling() {
        // 251dp is the key area of a default keyboard; the panel is the AI panels' height, but nothing may need more than that.
        for (source in StickerSource.entries) {
            for (shape in listOf(StickerShape.NONE, StickerShape.BURST)) {
                val r = rig(heightDp = 251)
                r.controller.startMake(source)
                r.controller.selectShape(shape)
                r.panel.relayout(251)
                assertFalse("$source/$shape scrolls in 251dp", r.panel.scrolls)
            }
        }
    }

    @Test fun makingTypingAndSavingThroughTheViewsLeavesAThumbnailInTheStrip() {
        val r = rig()
        r.panel.tap(ctx.getString(R.string.sticker_make_desc))
        r.panel.tap(ctx.getString(R.string.sticker_text_field_desc) + ": ")
        assertEquals(StickerField.TEXT, r.controller.typing)
        r.controller.text.set("Selam")
        r.controller.endTyping()
        r.panel.relayout()
        r.panel.tap(ctx.getString(R.string.sticker_save_desc))
        assertEquals(StickerStatus.SAVED, r.controller.status)
        assertEquals(StickerScreen.STRIP, r.controller.screen)
        r.panel.relayout()
        assertEquals(1, r.controller.mine.size)
        assertEquals(1, r.panel.thumbnailCount)
        assertAccessible(r.panel, "strip with a sticker")
        assertEquals(ctx.getString(R.string.sticker_status_saved), r.panel.statusText.toString())
        // Closing the panel frees the thumbnails.
        r.panel.release()
        assertEquals(0, r.panel.thumbnailCount)
    }

    @Test fun aLongPressShowsTheActionsAndTheStickerIsInsertedThroughTheSystemShare() {
        val r = rig()
        r.controller.startMake(StickerSource.EMOJI)
        r.controller.make()
        r.controller.open()
        r.panel.render()
        r.panel.relayout()
        val cell = r.panel.controls.first { it.contentDescription.toString().startsWith("Sticker 1 of 1") }
        assertTrue(cell.performLongClick())
        r.panel.relayout()
        assertAccessible(r.panel, "actions")
        r.panel.tap(ctx.getString(R.string.sticker_insert))
        assertEquals(1, r.inserted)
        r.panel.tap(ctx.getString(R.string.sticker_share))
        assertEquals(StickerStatus.SHARED, r.controller.status)
        // Delete needs the second tap.
        r.panel.tap(ctx.getString(R.string.sticker_delete))
        assertEquals(1, r.controller.mine.size)
        r.panel.tap(ctx.getString(R.string.sticker_delete_confirm))
        assertEquals(0, r.controller.mine.size)
    }

    @Test fun theCapMessageIsShownAtTheLimit() {
        val r = rig()
        repeat(5) { r.controller.startMake(StickerSource.EMOJI); r.controller.make() }
        r.controller.startMake(StickerSource.EMOJI)
        r.controller.make()
        assertEquals(StickerStatus.FULL, r.controller.status)
        r.panel.relayout()
        assertEquals(ctx.getString(R.string.sticker_status_full, 5), r.panel.statusText.toString())
    }

    @Test fun theDrawingSourceOpensDrawOrStartsANewDrawing() {
        val r = rig()
        r.controller.startMake(StickerSource.DRAWING)
        r.panel.render()
        r.panel.relayout()
        assertTrue(r.panel.control(ctx.getString(R.string.sticker_open_draw_desc)).performClick())
        assertTrue(r.panel.control(ctx.getString(R.string.sticker_new_drawing_desc)).performClick())
        assertEquals(listOf(false, true), r.draws)
        r.drawing = Drawing(100f, 100f, strokes = listOf(Stroke(listOf(Pt(1f, 1f), Pt(50f, 50f)), InkDefaults.BLACK, 4f)))
        r.panel.render()
        r.panel.relayout()
        r.panel.tap(ctx.getString(R.string.sticker_save_desc))
        assertEquals(StickerStatus.SAVED, r.controller.status)
        assertEquals(StickerKind.DRAWING, r.controller.mine.single().kind)
    }

    @Test fun everyThemeDrawsEveryScreenWithoutOverflowing() {
        val r = rig(PackAccess.NOT_ON_PLAN)
        r.controller.text.set("Selam")
        for (theme in ThemeCatalog.all) {
            r.panel.theme = theme
            for (tab in listOf(null, StickerPackId.GREETINGS, StickerPackId.CELEBRATIONS)) {
                r.controller.selectTab(tab)
                r.panel.relayout()
                r.panel.draw(Canvas(Bitmap.createBitmap(widthPx, (300 * d).toInt(), Bitmap.Config.ARGB_8888)))
            }
            for (src in StickerSource.entries) {
                r.controller.startMake(src)
                r.controller.selectShape(StickerShape.CIRCLE)
                r.panel.relayout()
                r.panel.draw(Canvas(Bitmap.createBitmap(widthPx, (300 * d).toInt(), Bitmap.Config.ARGB_8888)))
            }
            r.controller.backToStrip()
        }
    }

    // ---- insert and share through the system ---------------------------------------------------------------------

    private fun webp(): File = File(ctx.filesDir, "stickers/webp/stkabc12.webp").apply { parentFile!!.mkdirs(); writeBytes(FakeStickerExporter.WEBP_HEAD) }

    private class RecordingConnection(view: View) : BaseInputConnection(view, false) {
        var committed: InputContentInfo? = null
        override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: android.os.Bundle?): Boolean {
            committed = inputContentInfo
            return true
        }
    }

    private fun info(vararg mimes: String) = EditorInfo().apply {
        packageName = "com.example.chat"
        if (mimes.isNotEmpty()) EditorInfoCompat.setContentMimeTypes(this, arrayOf(*mimes))
    }

    @Test fun webpIsCommittedOnlyWhenTheFieldTakesWebp() {
        val ic = RecordingConnection(View(ctx))
        var ei: EditorInfo? = info("image/webp")
        val sys = SystemImageShare(ctx, SystemImageShare.authority(ctx), { ei }, { ic })
        assertTrue(sys.canInsert(ImageMimes.WEBP))
        assertFalse("a PNG-only field is not a WebP field and the other way round", sys.canInsert())
        assertTrue(sys.insert(webp(), ImageMimes.WEBP, "Sticker"))
        val committed = ic.committed!!
        assertEquals("image/webp", committed.description.getMimeType(0))
        assertTrue(committed.contentUri.toString().startsWith("content://" + SystemImageShare.authority(ctx)))
        ei = info("image/png")
        assertFalse(sys.canInsert(ImageMimes.WEBP))
        assertTrue(sys.canInsert())
        ei = info("image/*")
        assertTrue(sys.canInsert(ImageMimes.WEBP))
        ei = info()
        assertFalse(sys.canInsert(ImageMimes.WEBP))
        ei = null
        assertFalse(sys.canInsert(ImageMimes.WEBP))
        assertTrue(ImageMimes.accepts(arrayOf("image/gif", "image/webp"), "image/webp"))
        assertFalse(ImageMimes.accepts(null, "image/webp"))
        assertFalse(PngContent.accepts(arrayOf("image/webp")))
    }

    @Test fun theShareSheetCarriesTheWebpType() {
        val sys = SystemImageShare(ctx, SystemImageShare.authority(ctx), { null }, { null })
        assertTrue(sys.share(webp(), ImageMimes.WEBP, "Share sticker"))
        val chooser = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("image/webp", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val clip: ClipData = send.clipData!!
        assertEquals(clip.getItemAt(0).uri, send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
    }

    @Test fun onlyTheWebpFoldersAreExposedNeverTheIndex() {
        val sys = SystemImageShare(ctx, SystemImageShare.authority(ctx), { null }, { null })
        val index = File(ctx.filesDir, "stickers/index.tsv").apply { parentFile!!.mkdirs(); writeText("x") }
        assertFalse("the index is not shareable", sys.share(index, ImageMimes.WEBP, "x"))
        val pack = File(ctx.filesDir, "stickers/pack/greetings_hello_v1.webp").apply { parentFile!!.mkdirs(); writeBytes(FakeStickerExporter.WEBP_HEAD) }
        assertTrue(sys.share(pack, ImageMimes.WEBP, "x"))
        assertNotEquals(0, pack.length())
    }
}
