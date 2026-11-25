package com.qalsmartkeyboard.app.creative.cards

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The saved-card JSON and the gallery store (T8.7): round trips, damaged input, ids, the bound, photo copies. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CardStoreTest {
    private lateinit var dir: File
    private var now = 1_000L
    private var counter = 0
    private lateinit var store: FileCardStore
    private val resolve: (Int) -> String = { "s$it" }

    @Before fun setUp() {
        dir = Files.createTempDirectory("cards-test").toFile()
        store = FileCardStore(dir, clock = { now }, newId = { "card%04d".format(++counter) }, maxItems = 3)
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun doc(template: String = "birthday") = CardTemplates.find(template)!!.newDoc(resolve)
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

    // ---- JSON -------------------------------------------------------------------------------------------------------

    @Test fun everyTemplateRoundTripsThroughJson() {
        for (t in CardTemplates.all) {
            val d = t.newDoc(resolve, CardAspect.PORTRAIT, ExportFormat.JPEG)
            assertEquals(t.id, d, CardCodec.decode(CardCodec.encode(d)))
        }
    }

    @Test fun everyBackgroundKindRoundTrips() {
        val backgrounds = listOf(
            CardBackground.Solid(0xFF112233.toInt()),
            CardPalette.gradient("aurora")!!.toBackground(),
            CardPalette.patterns.last(),
            CardBackground.Photo("c1.bg.jpg", 55),
            CardBackground.Photo(null, 0),
        )
        for (b in backgrounds) {
            val d = doc().copy(background = b, frame = CardFrame.DOTTED, text = CardTextStyle(CardFont.CURSIVE, 1.25f, 0xFFABCDEF.toInt(), CardTextAlign.END, bold = true, shadow = true))
            assertEquals(d, CardCodec.decode(CardCodec.encode(d)))
        }
    }

    @Test fun geezAndLatinTextSurvivesAndNewlinesToo() {
        val d = doc().copy(headline = "መልካም ልደት!", body = "ሰላም ለሁላችሁ\nBaga gammaddan — Happy \"birthday\" \\ ✓", from = "Zekarias ዘካርያስ")
        val back = CardCodec.decode(CardCodec.encode(d))!!
        assertEquals(d, back)
    }

    @Test fun damagedOrForeignJsonIsNullNeverAHalfCard() {
        assertNull(CardCodec.decode(""))
        assertNull(CardCodec.decode("not json"))
        assertNull(CardCodec.decode("[]"))
        assertNull(CardCodec.decode("""{"v":1}""")) // no template
        assertNull(CardCodec.decode("""{"v":1,"template":"birthday"}""")) // no background
        assertNull(CardCodec.decode("""{"v":99,"template":"birthday","background":{"type":"solid","color":1}}""")) // a newer format
        assertNull(CardCodec.decode("""{"v":1,"template":"birthday","background":{"type":"mystery"}}"""))
        assertNull(CardCodec.decode("""{"v":1,"template":"birthday","background":{"type":"gradient","colors":[1],"angle":0}}"""))
    }

    @Test fun missingOptionalFieldsTakeDefaultsAndLimitsApply() {
        val d = CardCodec.decode("""{"v":1,"template":"thanks","headline":"${"h".repeat(500)}","text":{"scale":9.0},"background":{"type":"photo","dim":999}}""")!!
        assertEquals(CardDoc.MAX_HEADLINE, d.headline.length)
        assertEquals(CardTextStyle.MAX_SCALE, d.text.sizeScale, 0f)
        assertEquals(CardBackground.MAX_DIM, (d.background as CardBackground.Photo).dimPercent)
        assertEquals(CardAspect.SQUARE, d.aspect)
        assertEquals(ExportFormat.PNG, d.format)
        assertEquals(CardFrame.NONE, d.frame)
    }

    // ---- The gallery ------------------------------------------------------------------------------------------------

    @Test fun saveListLoadAndDelete() {
        val r = store.save(null, doc(), png, null) as SaveResult.Saved
        assertEquals("card0001", r.id)
        assertEquals(1, store.count())
        assertEquals(doc(), store.load(r.id))
        val listed = store.list().single()
        assertEquals(r.id, listed.id)
        assertArrayEquals(png, listed.image.readBytes())
        assertTrue(listed.image.name.endsWith(".png"))
        assertTrue(store.delete(r.id))
        assertEquals(0, store.count())
        assertTrue(store.list().isEmpty())
        assertNull(store.load(r.id))
        assertEquals(listOf<String>(), dir.list()!!.toList()) // every file of the card is gone
    }

    @Test fun listIsNewestFirst() {
        val a = (store.save(null, doc(), png, null) as SaveResult.Saved).id
        now += 10
        val b = (store.save(null, doc("thanks"), png, null) as SaveResult.Saved).id
        now += 10
        store.save(a, doc().copy(headline = "edited"), png, null) // re-saving bumps it to the top
        assertEquals(listOf(a, b), store.list().map { it.id })
        assertEquals("edited", store.load(a)!!.headline)
    }

    @Test fun theGalleryIsBoundedAndNeverDeletesBehindTheUsersBack() {
        val ids = (1..3).map { (store.save(null, doc(), png, null) as SaveResult.Saved).id }
        assertEquals(SaveResult.Full, store.save(null, doc(), png, null))
        assertEquals(3, store.count())
        // Replacing an existing card is still fine at the limit.
        assertTrue(store.save(ids[1], doc().copy(body = "changed"), png, null) is SaveResult.Saved)
        // Deleting one makes room.
        store.delete(ids[0])
        assertTrue(store.save(null, doc(), png, null) is SaveResult.Saved)
        assertEquals(3, store.count())
    }

    @Test fun theDefaultBoundIsAHundred() {
        assertEquals(100, CardStore.MAX_ITEMS)
    }

    @Test fun aPhotoBackgroundKeepsItsCopyNextToTheCard() {
        val photo = byteArrayOf(1, 2, 3)
        val id = (store.save(null, doc().copy(background = CardBackground.Photo(null, 40)), png, photo) as SaveResult.Saved).id
        assertArrayEquals(photo, store.photoFile(id)!!.readBytes())
        assertEquals(CardBackground.Photo("$id.bg.jpg", 40), store.load(id)!!.background)
        // A later save without new photo bytes keeps the copy.
        assertTrue(store.save(id, store.load(id)!!.copy(headline = "again"), png, null) is SaveResult.Saved)
        assertNotNull(store.photoFile(id))
        // Changing to a colour drops the copy.
        store.save(id, doc(), png, null)
        assertNull(store.photoFile(id))
    }

    @Test fun aPhotoCardWithoutAPhotoIsRefused() {
        assertEquals(SaveResult.Failed, store.save(null, doc().copy(background = CardBackground.Photo(null)), png, null))
        assertEquals(0, store.count())
    }

    @Test fun changingTheFormatReplacesTheImage() {
        val id = (store.save(null, doc(), png, null) as SaveResult.Saved).id
        store.save(id, doc().copy(format = ExportFormat.JPEG), byteArrayOf(9), null)
        assertTrue(store.list().single().image.name.endsWith(".jpg"))
        assertFalse(File(dir, "$id.png").exists())
    }

    @Test fun idsCannotLeaveTheDirectory() {
        assertNull(store.load("../secret"))
        assertFalse(store.delete("../x"))
        assertNull(store.photoFile("a/b"))
        // An unusable id is never used as a file name: the card gets a fresh one, inside the directory.
        val saved = store.save("../evil", doc(), png, null) as SaveResult.Saved
        assertEquals("card0001", saved.id)
        assertFalse(File(dir.parentFile, "evil.json").exists())
        assertTrue(FileCardStore.validId(FileCardStore.randomId()))
        assertFalse(FileCardStore.validId("UPPER"))
        assertFalse(FileCardStore.validId("a"))
    }

    @Test fun anUnwritableDirectoryFailsCleanly() {
        val blocker = File(dir, "blocked").also { it.writeText("a file where the directory should be") }
        val broken = FileCardStore(File(blocker, "cards"), newId = { "card9999" })
        assertEquals(SaveResult.Failed, broken.save(null, doc(), png, null))
    }

    @Test fun aDamagedJsonFileIsSkippedNotCrashed() {
        val id = (store.save(null, doc(), png, null) as SaveResult.Saved).id
        File(dir, "$id.json").writeText("{ broken")
        assertNull(store.load(id))
        assertEquals(1, store.list().size) // still listed (its image exists); opening says "could not be opened"
    }
}
