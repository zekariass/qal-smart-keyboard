package com.qalsmartkeyboard.ime.draw

import com.qalsmartkeyboard.ime.share.FakeImageShare
import com.qalsmartkeyboard.ime.share.ImageSender
import com.qalsmartkeyboard.ime.share.ShareOutcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** T8.4: the gallery store, the panel's controller (save, insert / share choice, clear and delete confirms) and the sender. */
class DrawControllerTest {
    private lateinit var dir: File
    private var clock = 1_000L
    private var ids = 0
    private lateinit var store: DrawingStore

    /** Writes a PNG-looking file; records the options it was asked for. */
    private class FakeExporter(var ok: Boolean = true) : DrawingExporter {
        val calls = ArrayList<Pair<File, ExportOptions>>()
        override fun exportPng(d: Drawing, options: ExportOptions, dest: File): Boolean {
            calls += dest to options
            if (!ok) return false
            dest.parentFile?.mkdirs()
            dest.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
            return true
        }
    }

    private val share = FakeImageShare()
    private val exporter = FakeExporter()
    private var changes = 0
    private lateinit var c: DrawController

    @Before fun setUp() {
        dir = Files.createTempDirectory("draw-test").toFile()
        store = DrawingStore(File(dir, "drawings"), clock = { clock++ }, newId = { "draw%04d".format(ids++) })
        c = DrawController(store, exporter, { share }, onChanged = { changes++ })
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun stroke(x: Float = 0f) = Stroke(listOf(Pt(x, 0f), Pt(x + 9f, 9f)), InkDefaults.BLACK, 4f)

    private fun draw(n: Int = 1) = repeat(n) { c.session.addStroke(stroke(it * 5f)); c.strokeAdded() }

    // ---- store ---------------------------------------------------------------------------------------------------

    @Test fun savedDrawingsListNewestFirstAndLoadBack() {
        val d1 = Drawing(100f, 100f, strokes = listOf(stroke()))
        val a = store.save(null, d1)!!
        val b = store.save(null, d1.copy(width = 120f))!!
        assertEquals(listOf(b, a), store.list().map { it.id })
        assertEquals(120f, ((store.load(b) as DecodeResult.Ok).drawing).width)
        store.save(a, d1) // saving again moves it to the top
        assertEquals(listOf(a, b), store.list().map { it.id })
    }

    @Test fun listIsCappedAtTheNewestN() {
        repeat(DrawingStore.MAX_LISTED + 7) { store.save(null, Drawing(10f, 10f)) }
        assertEquals(DrawingStore.MAX_LISTED, store.list().size)
        assertEquals(DrawingStore.MAX_LISTED + 7, store.count())
        assertEquals(3, store.list(3).size)
        assertEquals("draw%04d".format(DrawingStore.MAX_LISTED + 6), store.list().first().id)
    }

    @Test fun duplicateDeleteAndBadIds() {
        val a = store.save(null, Drawing(10f, 10f, strokes = listOf(stroke())))!!
        val copy = store.duplicate(a)!!
        assertNotEquals(a, copy)
        assertEquals(2, store.count())
        store.pngFile(a).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        assertTrue(store.delete(a))
        assertFalse(store.pngFile(a).exists())
        assertEquals(1, store.count())
        assertFalse(store.delete("../evil"))
        assertEquals(DecodeResult.Corrupt, store.load("../../x"))
        assertTrue("a bad id is replaced by a fresh one", DrawingStore.validId(store.save("../escape", Drawing(10f, 10f))!!))
        assertNull(store.duplicate("nothere1"))
    }

    @Test fun aCorruptFileDoesNotBreakTheGallery() {
        val a = store.save(null, Drawing(10f, 10f))!!
        File(dir, "drawings/$a.qdraw").writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(1, store.list().size)
        assertEquals(DecodeResult.Corrupt, store.load(a))
        assertFalse(c.edit(a))
        assertEquals(DrawStatus.OPEN_FAILED, c.status)
    }

    // ---- controller ----------------------------------------------------------------------------------------------

    @Test fun clearNeedsASecondTapAndAnyOtherActionDisarmsIt() {
        draw(2)
        assertFalse(c.requestClear())
        assertTrue(c.clearArmed)
        assertEquals(2, c.session.strokes.size)
        c.selectWidth(2)
        assertFalse(c.clearArmed)
        assertFalse(c.requestClear())
        assertTrue(c.requestClear())
        assertTrue(c.session.strokes.isEmpty())
        c.undo()
        assertEquals(2, c.session.strokes.size)
    }

    @Test fun inkFollowsTheBackgroundUntilAColourIsPicked() {
        assertEquals(InkDefaults.BLACK, c.colour)
        c.setBackground(DrawBackground.BLACK)
        assertEquals(InkDefaults.WHITE, c.colour)
        c.selectColour(InkDefaults.PALETTE[2])
        c.setBackground(DrawBackground.WHITE)
        assertEquals(InkDefaults.PALETTE[2], c.colour)
    }

    @Test fun pickingAColourLeavesTheEraser() {
        c.selectTool(DrawTool.ERASER)
        c.selectColour(InkDefaults.PALETTE[3])
        assertEquals(DrawTool.PEN, c.tool)
        c.selectWidth(99)
        assertEquals(InkDefaults.WIDTHS.lastIndex, c.widthIndex)
    }

    @Test fun saveWritesTheDrawingAndItsPngAndKeepsTheId() {
        assertNull(c.save())
        assertEquals(DrawStatus.NOTHING_TO_SEND, c.status)
        draw(2)
        val id = c.save()!!
        assertEquals(DrawStatus.SAVED, c.status)
        assertEquals(id, c.currentId)
        assertTrue(store.pngFile(id).isFile)
        assertFalse(c.session.dirty)
        draw()
        assertEquals("saving again overwrites", id, c.save())
        assertEquals(1, store.count())
        exporter.ok = false
        c.save()
        assertEquals(DrawStatus.SAVE_FAILED, c.status)
    }

    @Test fun insertUsesCommitContentWhenTheFieldTakesPngs() {
        draw()
        assertEquals(ShareOutcome.INSERTED, c.insert("Drawing", "Share"))
        assertEquals(listOf("insert"), share.log)
        assertEquals(DrawStatus.INSERTED, c.status)
        assertEquals(store.currentPngFile(), share.inserted.single())
        assertEquals(ExportOptions(), exporter.calls.last().second)
    }

    @Test fun insertFallsBackToTheShareSheetWhenTheFieldDoesNotTakePngs() {
        draw()
        share.insertable = false
        assertEquals(ShareOutcome.SHARED, c.insert("Drawing", "Share"))
        assertEquals(listOf("share"), share.log)
        assertEquals(DrawStatus.SHARED, c.status)
    }

    @Test fun insertFallsBackWhenTheHostRefusesTheContent() {
        draw()
        share.insertAccepted = false
        assertEquals(ShareOutcome.SHARED, c.insert("Drawing", "Share"))
        assertEquals(listOf("insert", "share"), share.log)
    }

    @Test fun neitherPathRunsWithoutAnImage() {
        assertEquals(ShareOutcome.NO_IMAGE, c.insert("d", "s"))
        assertEquals(ShareOutcome.NO_IMAGE, c.share("s"))
        assertTrue(share.log.isEmpty())
        assertEquals(DrawStatus.NOTHING_TO_SEND, c.status)
        // A blank canvas of erasers only is no image either.
        c.session.addStroke(Stroke(listOf(Pt(1f, 1f)), 0, 4f, DrawTool.ERASER))
        assertEquals(ShareOutcome.NO_IMAGE, c.insert("d", "s"))
        // An export that fails sends nothing.
        draw()
        exporter.ok = false
        assertEquals(ShareOutcome.NO_IMAGE, c.insert("d", "s"))
        assertTrue(share.log.isEmpty())
    }

    @Test fun shareAlwaysOpensTheShareSheet() {
        draw()
        assertEquals(ShareOutcome.SHARED, c.share("Share"))
        assertEquals(listOf("share"), share.log)
        share.shareAccepted = false
        assertEquals(ShareOutcome.FAILED, c.share("Share"))
        assertEquals(DrawStatus.SEND_FAILED, c.status)
    }

    @Test fun senderChoosesByFileAndCapability() {
        val f = File(dir, "x.png").apply { writeBytes(byteArrayOf(1)) }
        assertEquals(ShareOutcome.NO_IMAGE, ImageSender.insertOrShare(share, null, "d"))
        assertEquals(ShareOutcome.NO_IMAGE, ImageSender.insertOrShare(share, File(dir, "missing.png"), "d"))
        assertEquals(ShareOutcome.NO_IMAGE, ImageSender.shareOnly(share, File(dir, "empty.png").apply { writeBytes(ByteArray(0)) }, "d"))
        assertEquals(ShareOutcome.INSERTED, ImageSender.insertOrShare(share, f, "d"))
        share.insertable = false
        assertEquals(ShareOutcome.SHARED, ImageSender.insertOrShare(share, f, "d"))
        share.shareAccepted = false
        assertEquals(ShareOutcome.FAILED, ImageSender.insertOrShare(share, f, "d"))
    }

    // ---- gallery -------------------------------------------------------------------------------------------------

    @Test fun galleryOpensEditsDuplicatesAndDeletesWithAConfirm() {
        draw(3)
        val id = c.save()!!
        c.newDrawing()
        assertTrue(c.session.strokes.isEmpty())
        c.openGallery()
        assertEquals(DrawMode.GALLERY, c.mode)
        assertEquals(listOf(id), c.gallery.map { it.id })
        c.select(id)
        assertEquals(id, c.selectedId)
        assertTrue(c.edit(id))
        assertEquals(DrawMode.CANVAS, c.mode)
        assertEquals(3, c.session.strokes.size)
        assertEquals(id, c.currentId)

        c.openGallery()
        val copy = c.duplicate(id)!!
        assertEquals(2, c.gallery.size)
        assertEquals(copy, c.selectedId)
        assertEquals(DrawStatus.DUPLICATED, c.status)

        assertFalse("the first tap only arms", c.requestDelete(copy))
        assertEquals(copy, c.deleteArmedId)
        assertEquals(2, store.count())
        c.select(id) // moving on disarms
        assertNull(c.deleteArmedId)
        assertFalse(c.requestDelete(copy))
        assertTrue(c.requestDelete(copy))
        assertEquals(listOf(id), c.gallery.map { it.id })
        assertEquals(DrawStatus.DELETED, c.status)
    }

    @Test fun galleryInsertAndShareUseTheSavedPng() {
        draw()
        val id = c.save()!!
        c.openGallery()
        store.pngFile(id).delete()
        assertEquals(ShareOutcome.INSERTED, c.insertSaved(id, "d", "s"))
        assertTrue("the png is re-exported when it is missing", store.pngFile(id).isFile)
        assertEquals(store.pngFile(id), share.inserted.single())
        assertEquals(ShareOutcome.SHARED, c.shareSaved(id, "s"))
        assertEquals(ShareOutcome.NO_IMAGE, c.shareSaved("missing01", "s"))
    }

    @Test fun deletingTheDrawingBeingEditedDetachesIt() {
        draw()
        val id = c.save()!!
        c.openGallery()
        c.requestDelete(id); c.requestDelete(id)
        assertNull(c.currentId)
    }

    @Test fun discardDropsTheUnsavedDrawing() {
        draw(2)
        c.setBackground(DrawBackground.BLACK)
        c.discard()
        assertTrue(c.session.strokes.isEmpty())
        assertEquals(DrawBackground.WHITE, c.session.background)
        assertEquals(DrawTool.PEN, c.tool)
        assertEquals(InkDefaults.BLACK, c.colour)
        assertEquals(0, store.count())
        assertTrue(changes > 0)
    }
}
