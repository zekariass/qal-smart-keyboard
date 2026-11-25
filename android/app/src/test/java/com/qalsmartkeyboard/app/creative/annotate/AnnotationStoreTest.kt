package com.qalsmartkeyboard.app.creative.annotate

import com.qalsmartkeyboard.app.creative.cards.ExportFormat
import com.qalsmartkeyboard.app.creative.cards.SaveResult
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The draft JSON, the draft file and the personal gallery of annotated pictures (T8.7). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnnotationStoreTest {
    private lateinit var dir: File
    private var now = 1_000L
    private var counter = 0

    @Before fun setUp() {
        dir = Files.createTempDirectory("annot-test").toFile()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun everyKind() = AnnotDoc(1200, 900)
        .with(Annotation.Ink(1, listOf(Pt(1.5f, 2.25f), Pt(30f, 40f), Pt(31f, 44f)), 0xFFE53935.toInt(), 6f))
        .with(Annotation.Ink(2, listOf(Pt(5f, 5f)), 0xFFFFEB3B.toInt(), 20f, highlighter = true))
        .with(Annotation.Arrow(3, Pt(0f, 0f), Pt(100f, 80f), 0xFF1E88E5.toInt(), 5f))
        .with(Annotation.Shape(4, ShapeKind.RECT, Box(10f, 10f, 50f, 60f), 0xFF000000.toInt(), 3f))
        .with(Annotation.Shape(5, ShapeKind.ELLIPSE, Box(100f, 100f, 300f, 200f), 0xFFFFFFFF.toInt(), 4f))
        .with(Annotation.Label(6, Pt(40f, 400f), "ሰላም ለሁሉም\nHello \"quoted\" & <tags>", 0xFF43A047.toInt(), 36f, backdrop = false))
        .with(Annotation.Step(7, Pt(500f, 500f), 3, 0xFF8E24AA.toInt(), 28f))
        .with(Annotation.Spotlight(8, Box(600f, 100f, 900f, 300f)))
        .with(Annotation.Redaction(9, RedactMode.BLUR, RedactArea.Rect(Box(20f, 700f, 400f, 740f))))
        .with(Annotation.Redaction(10, RedactMode.PIXELATE, RedactArea.Brush(listOf(Pt(1f, 1f), Pt(80f, 90f)), 14f)))
        .with(Annotation.Redaction(11, RedactMode.SOLID, RedactArea.Rect(Box(0f, 0f, 9f, 9f)), 0xFF123456.toInt()))
        .copy(crop = PixelRect(5, 6, 1100, 880))

    // ---- The draft JSON ---------------------------------------------------------------------------------------------

    @Test fun everyKindOfObjectRoundTripsThroughTheDraftJson() {
        val draft = AnnotDraft("content://media/picker/0/x/42", everyKind(), ExportFormat.JPEG)
        val back = AnnotationCodec.decode(AnnotationCodec.encode(draft))
        assertEquals(draft, back)
        assertEquals(12, back!!.doc.nextId)
        // No pixels and no file name of the picture in the draft: only the reference the picker gave.
        assertFalse(AnnotationCodec.encode(draft).contains("base64"))
    }

    @Test fun aDamagedDraftReadsAsNothingNeverAsHalfADraft() {
        val good = AnnotationCodec.encode(AnnotDraft("content://x/1", everyKind()))
        val damaged = listOf(
            "", "{", "[]", "{\"v\":99}",
            good.replace("\"v\":1", "\"v\":2"),
            good.replace("\"t\":\"ink\"", "\"t\":\"laser\""),
            good.replace("\"mode\":\"blur\"", "\"mode\":\"melt\""),
            good.replace("\"w\":1200", "\"w\":0"),
            good.replace("\"id\":2,", "\"id\":1,"), // a duplicate id
            good.replace("[1.5,2.25,", "[1.5,"), // an odd number of coordinates
            good.replace("1.5,2.25", "\"NaN\",2.25"),
            good.replace("\"src\":", "\"source\":"),
        )
        for (d in damaged) assertNull(d.take(60), AnnotationCodec.decode(d))
        // Too many objects.
        var big = AnnotDoc(10, 10)
        repeat(AnnotDoc.MAX_ITEMS + 1) { big = big.with(Annotation.Ink(it + 1, listOf(Pt(1f, 1f)), 0, 1f)) }
        assertNull(AnnotationCodec.decode(AnnotationCodec.encode(AnnotDraft("content://x/1", big))))
    }

    // ---- The draft file ---------------------------------------------------------------------------------------------

    @Test fun theDraftFileIsWrittenReadAndDiscarded() {
        val store = AnnotationDraftStore(File(dir, "annotator/draft.json"))
        assertFalse(store.exists())
        assertNull(store.load())
        val draft = AnnotDraft("content://media/picker/0/x/7", everyKind())
        assertTrue(store.save(draft))
        assertTrue(store.exists())
        assertEquals(draft, store.load())
        assertTrue(store.save(draft.copy(format = ExportFormat.JPEG))) // replaces
        assertEquals(ExportFormat.JPEG, store.load()!!.format)
        assertTrue(store.delete())
        assertFalse(store.exists())
        assertTrue(File(dir, "annotator").list()!!.none { it.startsWith("draft.json") }) // no temporary file left either
        File(dir, "annotator/draft.json").writeText("garbage")
        assertNull(store.load())
    }

    // ---- The gallery ------------------------------------------------------------------------------------------------

    private fun gallery(max: Int = 3) = FileAnnotationStore(dir, clock = { now }, newId = { "anno%04d".format(++counter) }, maxItems = max)

    @Test fun savedPicturesAreListedNewestFirstAndDeleted() {
        val g = gallery()
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        val a = (g.save(png, ExportFormat.PNG) as SaveResult.Saved).id
        now = 2_000
        val b = (g.save(byteArrayOf(0xFF.toByte(), 0xD8.toByte()), ExportFormat.JPEG) as SaveResult.Saved).id
        val list = g.list()
        assertEquals(listOf(b, a), list.map { it.id })
        assertEquals(ExportFormat.JPEG, list[0].format)
        assertArrayEquals(png, list[1].image.readBytes())
        assertTrue(g.delete(a))
        assertEquals(listOf(b), g.list().map { it.id })
        assertFalse(g.delete("../escape"))
        assertFalse(g.delete(a))
    }

    @Test fun theGalleryIsBoundedAndNeverDeletesBehindTheUsersBack() {
        val g = gallery(max = 3)
        repeat(3) { assertTrue(g.save(byteArrayOf(1), ExportFormat.PNG) is SaveResult.Saved) }
        assertEquals(SaveResult.Full, g.save(byteArrayOf(2), ExportFormat.PNG))
        assertEquals(3, g.count())
        g.delete(g.list().last().id)
        assertTrue(g.save(byteArrayOf(3), ExportFormat.PNG) is SaveResult.Saved)
        assertEquals(100, AnnotationStore.MAX_ITEMS)
    }

    @Test fun foreignFilesInTheDirectoryAreIgnored() {
        val g = gallery()
        File(dir, "notes.txt").writeText("x")
        File(dir, "../x.png".replace("../", "BAD NAME.")).writeText("x")
        File(dir, "anno0001.png.tmp").writeText("half")
        assertEquals(0, g.count())
    }
}
