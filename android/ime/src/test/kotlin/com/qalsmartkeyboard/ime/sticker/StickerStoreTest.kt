package com.qalsmartkeyboard.ime.sticker

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** T8.7: the personal sticker pack's files and index (round trip, the cap, failed writes, a lost index, ids). */
class StickerStoreTest {
    private lateinit var dir: File
    private var clock = 1_000L
    private var ids = 0
    private lateinit var store: StickerStore

    @Before fun setUp() {
        dir = Files.createTempDirectory("sticker-store").toFile()
        store = newStore()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun newStore(max: Int = 3) = StickerStore(File(dir, "stickers"), max = max, clock = { clock++ }, newId = { "stk%04d".format(ids++) })

    private fun add(kind: StickerKind = StickerKind.TEXT, bytes: ByteArray = FakeStickerExporter.WEBP_HEAD): AddResult =
        store.add(kind) { f -> f.writeBytes(bytes); true }

    @Test fun aStickerRoundTripsThroughTheFilesAndTheIndex() {
        val a = (add(StickerKind.TEXT) as AddResult.Added).id
        val b = (add(StickerKind.EMOJI) as AddResult.Added).id
        val c = (add(StickerKind.DRAWING) as AddResult.Added).id
        assertEquals(listOf(c, b, a), store.list().map { it.id })
        assertEquals(listOf(StickerKind.DRAWING, StickerKind.EMOJI, StickerKind.TEXT), store.list().map { it.kind })
        assertTrue(StickerFormat.isWebp(store.file(a).readBytes()))
        // A new store object (the keyboard restarted) reads the same pack.
        val again = newStore()
        assertEquals(listOf(c, b, a), again.list().map { it.id })
        assertEquals(3, again.count())
        assertFalse(File(dir, "stickers/webp/$a.webp.tmp").exists())
    }

    @Test fun thePackIsBoundedAndSaysSoBeforeAnythingIsWritten() {
        add(); add(); add()
        assertTrue(store.isFull)
        var asked = false
        val r = store.add(StickerKind.TEXT) { asked = true; true }
        assertEquals(AddResult.Full, r)
        assertFalse("a full pack renders nothing", asked)
        assertEquals(3, store.count())
        val first = store.list().last().id
        assertTrue(store.delete(first))
        assertFalse(store.isFull)
        assertTrue(add() is AddResult.Added)
    }

    @Test fun aFailedOrEmptyWriteLeavesNothingBehind() {
        assertEquals(AddResult.Failed, store.add(StickerKind.TEXT) { false })
        assertEquals(AddResult.Failed, store.add(StickerKind.TEXT) { f -> f.writeBytes(ByteArray(0)); true })
        assertEquals(AddResult.Failed, store.add(StickerKind.TEXT) { f -> f.writeBytes(byteArrayOf(1)); false })
        assertEquals(0, store.count())
        assertEquals(emptyList<String>(), File(dir, "stickers/webp").list()?.toList().orEmpty())
        assertFalse(File(dir, "stickers/index.tsv").exists())
    }

    @Test fun deleteRemovesTheFileAndTheEntryAndRefusesBadIds() {
        val a = (add() as AddResult.Added).id
        assertTrue(store.delete(a))
        assertFalse(store.file(a).exists())
        assertEquals(0, store.count())
        assertFalse(store.delete(a))
        assertFalse(store.delete("../evil"))
        assertFalse(store.delete(""))
        assertTrue(StickerStore.validId(StickerStore.randomId()))
        assertFalse(StickerStore.validId("../x"))
        assertFalse(StickerStore.validId("ABCDEF"))
    }

    @Test fun anInvalidOrReusedIdIsRefused() {
        val bad = StickerStore(File(dir, "bad"), newId = { "../x" })
        assertEquals(AddResult.Failed, bad.add(StickerKind.TEXT) { f -> f.writeBytes(byteArrayOf(1)); true })
        val same = StickerStore(File(dir, "same"), newId = { "stkaaaa" })
        assertTrue(same.add(StickerKind.TEXT) { f -> f.writeBytes(byteArrayOf(1)); true } is AddResult.Added)
        assertEquals(AddResult.Failed, same.add(StickerKind.TEXT) { f -> f.writeBytes(byteArrayOf(1)); true })
        assertEquals(1, same.count())
    }

    @Test fun aLostIndexIsRebuiltFromTheFilesAndAnOrphanEntryIsIgnored() {
        val a = (add(StickerKind.EMOJI) as AddResult.Added).id
        val b = (add(StickerKind.TEXT) as AddResult.Added).id
        File(dir, "stickers/index.tsv").delete()
        assertEquals(setOf(a, b), store.list().map { it.id }.toSet())
        assertTrue(store.list().all { it.kind == StickerKind.OTHER })
        // A line for a file that is gone and a line that is garbage change nothing.
        File(dir, "stickers/index.tsv").writeText("stkgone1\tt\t5\nnot a line\n$a\te\t7\n")
        assertEquals(setOf(a, b), store.list().map { it.id }.toSet())
        assertEquals(StickerKind.EMOJI, store.list().first { it.id == a }.kind)
        // An unwritten temp file and a zero-byte file are not stickers.
        File(dir, "stickers/webp/zzzzzz1.webp.tmp").writeBytes(byteArrayOf(1))
        File(dir, "stickers/webp/zzzzzz2.webp").writeBytes(ByteArray(0))
        assertEquals(2, store.count())
    }

    @Test fun theIndexNeverLeavesTheStickerDirectoryAndThePackCacheIsClearable() {
        val a = (add() as AddResult.Added).id
        assertEquals(File(dir, "stickers/webp/$a.webp").canonicalPath, store.file(a).canonicalPath)
        val pf = store.packFile(StickerPackId.GREETINGS, "hello")
        pf.parentFile!!.mkdirs()
        pf.writeBytes(byteArrayOf(1))
        assertTrue(pf.name.endsWith("_v${StickerPacks.VERSION}.webp"))
        assertEquals(File(dir, "stickers/pack").canonicalPath, pf.parentFile!!.canonicalPath)
        store.clearPackCache()
        assertFalse(pf.exists())
        assertEquals(1, store.count())
    }
}
