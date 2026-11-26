package com.qalsmartkeyboard.ime.draw

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** T8.15 step S2: the port a later optional backup is written against, implemented by [DrawingStore]. */
class DrawingBackupSourceTest {
    private lateinit var dir: File
    private var clock = 5_000L
    private var ids = 0
    private lateinit var store: DrawingStore
    private val port: DrawingBackupSource get() = store

    @Before fun setUp() {
        dir = Files.createTempDirectory("draw-port").toFile()
        store = DrawingStore(File(dir, "drawings"), clock = { clock }, newId = { "draw%04d".format(ids++) })
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun drawing(x: Float = 1f) = Drawing(
        100f, 80f,
        strokes = listOf(Stroke(listOf(Pt(x, 1f), Pt(x + 5f, 6f)), 0xFF000000.toInt(), 3f)),
        objects = listOf(TextObject(0, "hi", 5f, 5f)),
    )

    @Test fun itemsListNewestFirstWithIdSizeTimesAndRevision() {
        clock = 5_000
        val a = store.save(null, drawing(1f))!!
        clock = 6_000
        val b = store.save(null, drawing(2f))!!
        val items = port.listItems()
        assertEquals(listOf(b, a), items.map { it.id })
        val ia = items.first { it.id == a }
        assertEquals(5_000L, ia.createdAt)
        assertEquals(5_000L, ia.updatedAt)
        assertEquals(File(dir, "drawings/$a.qdraw").length(), ia.sizeBytes)
        assertEquals(ia.updatedAt, ia.revision)
    }

    @Test fun everySaveChangesTheRevisionEvenWithinTheSameMillisecondAndKeepsTheCreationTime() {
        clock = 7_000
        val id = store.save(null, drawing())!!
        val r1 = port.listItems().single().revision
        val r2 = store.save(id, drawing(9f))!!.let { port.listItems().single().revision }
        val r3 = store.save(id, drawing(9f))!!.let { port.listItems().single().revision }
        assertTrue("$r1 < $r2", r2 > r1)
        assertTrue("$r2 < $r3", r3 > r2)
        assertEquals("created stays the first save", 7_000L, port.listItems().single().createdAt)
    }

    @Test fun anOldDrawingWithoutACreationStampFallsBackToItsModificationTime() {
        File(dir, "drawings").mkdirs()
        val f = File(dir, "drawings/olddrawing1.qdraw")
        f.writeBytes(DrawV1Fixtures.bytes(DrawV1Fixtures.FULL))
        f.setLastModified(4_321L)
        val item = port.listItems().single()
        assertEquals("olddrawing1", item.id)
        assertEquals(4_321L, item.createdAt)
        assertEquals(4_321L, item.updatedAt)
        // Saving it again stamps the creation time it had.
        val id = store.save("olddrawing1", (store.load("olddrawing1") as DecodeResult.Ok).drawing)!!
        assertEquals(4_321L, port.listItems().single { it.id == id }.createdAt)
    }

    @Test fun rawBytesAreTheSavedFileAndRoundTripThroughWriteRaw() {
        val id = store.save(null, drawing())!!
        val raw = port.readRaw(id)!!
        assertArrayEquals(File(dir, "drawings/$id.qdraw").readBytes(), raw)
        val other = DrawingStore(File(dir, "restored"), clock = { 9_000L })
        assertEquals(RawWriteResult.WRITTEN, other.writeRaw(id, raw, updatedAt = 8_000L))
        assertArrayEquals(raw, other.readRaw(id))
        val item = other.listItems().single()
        assertEquals(8_000L, item.updatedAt)
        assertEquals(8_000L, item.revision)
        assertEquals(id, item.id)
        assertEquals((store.load(id) as DecodeResult.Ok).drawing, (other.load(id) as DecodeResult.Ok).drawing)
    }

    @Test fun writeRawReplacesTheDrawingWithThatIdAndDropsItsStaleImage() {
        val id = store.save(null, drawing(1f))!!
        store.pngFile(id).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        val replacement = DrawingCodec.encode(drawing(7f))
        assertEquals(RawWriteResult.WRITTEN, port.writeRaw(id, replacement))
        assertEquals(1, store.count())
        assertArrayEquals(replacement, port.readRaw(id))
        assertFalse("the exported image is derived and is made again", store.pngFile(id).exists())
    }

    @Test fun writeRawAcceptsAVersionOneFileAndKeepsItsBytes() {
        val v1 = DrawV1Fixtures.bytes(DrawV1Fixtures.FULL)
        assertEquals(RawWriteResult.WRITTEN, port.writeRaw("fromold001", v1))
        assertArrayEquals(v1, port.readRaw("fromold001"))
        assertEquals(DrawV1Fixtures.full(), (store.load("fromold001") as DecodeResult.Ok).drawing)
    }

    @Test fun writeRawValidatesThroughTheCodecAndTheIdAndTouchesNothingOtherwise() {
        assertEquals(RawWriteResult.INVALID, port.writeRaw("abcdef12", byteArrayOf(1, 2, 3)))
        assertEquals(RawWriteResult.INVALID, port.writeRaw("abcdef12", DrawingCodec.encode(drawing()).copyOf(30)))
        val newer = DrawingCodec.encode(drawing()).also { it[5] = 9 }
        assertEquals(RawWriteResult.UNSUPPORTED_VERSION, port.writeRaw("abcdef12", newer))
        assertEquals(RawWriteResult.FAILED, port.writeRaw("../evil", DrawingCodec.encode(drawing())))
        assertEquals(RawWriteResult.FAILED, port.writeRaw("A", DrawingCodec.encode(drawing())))
        assertEquals(0, store.count())
        assertTrue(port.listItems().isEmpty())
        assertEquals(0L, port.changeCounter)
        assertFalse(File(dir, "drawings").listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    @Test fun writeRawRefusesANewItemAtTheCapButReplacesAnExisting() {
        val ids = (0 until DrawingStore.MAX_DRAWINGS).map { store.save(null, drawing())!! }
        val bytes = DrawingCodec.encode(drawing(4f))
        assertEquals(RawWriteResult.FULL, port.writeRaw("brandnew1", bytes))
        assertEquals(RawWriteResult.WRITTEN, port.writeRaw(ids[5], bytes))
        assertEquals(DrawingStore.MAX_DRAWINGS, store.count())
        assertEquals("the port lists all, not only the visible ones", DrawingStore.MAX_DRAWINGS, port.listItems().size)
    }

    @Test fun readRawAndDeleteHandleMissingAndBadIds() {
        assertNull(port.readRaw("nothere1"))
        assertNull(port.readRaw("../x"))
        assertFalse(port.deleteItem("nothere1"))
        assertFalse(port.deleteItem("../x"))
        val id = store.save(null, drawing())!!
        store.pngFile(id).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        assertTrue(port.deleteItem(id))
        assertNull(port.readRaw(id))
        assertFalse(store.pngFile(id).exists())
        assertTrue(port.listItems().isEmpty())
    }

    @Test fun theChangeCounterAndListenersSeeSaveDuplicateRestoreAndDelete() {
        var heard = 0
        val sub = port.addChangeListener { heard++ }
        assertEquals(0L, port.changeCounter)
        val id = store.save(null, drawing())!!
        assertEquals(1L, port.changeCounter)
        store.duplicate(id)
        port.writeRaw("restored01", DrawingCodec.encode(drawing()))
        port.deleteItem(id)
        assertEquals(4L, port.changeCounter)
        assertEquals(4, heard)
        sub.close()
        store.save(null, drawing())
        assertEquals("a closed subscription hears nothing", 4, heard)
        assertEquals(5L, port.changeCounter)
        // Reads change nothing.
        port.listItems(); port.readRaw(id); store.load(id)
        assertEquals(5L, port.changeCounter)
    }

    @Test fun aFailedSaveDoesNotCountAsAChange() {
        repeat(DrawingStore.MAX_DRAWINGS) { store.save(null, drawing()) }
        val before = port.changeCounter
        assertNull(store.save(null, drawing()))
        assertEquals(before, port.changeCounter)
        assertNotEquals(0L, before)
    }

    @Test fun theStoreSkipsTemporaryAndForeignFiles() {
        File(dir, "drawings").mkdirs()
        File(dir, "drawings/x.qdraw.tmp").writeBytes(byteArrayOf(1))
        File(dir, "drawings/notes.txt").writeBytes(byteArrayOf(1))
        File(dir, "drawings/BAD ID.qdraw").writeBytes(byteArrayOf(1))
        assertTrue(port.listItems().isEmpty())
        assertEquals(0, store.count())
    }
}
