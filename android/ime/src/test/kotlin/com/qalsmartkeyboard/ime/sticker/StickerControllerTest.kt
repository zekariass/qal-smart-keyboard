package com.qalsmartkeyboard.ime.sticker

import com.qalsmartkeyboard.ime.ai.AiLink
import com.qalsmartkeyboard.ime.draw.Drawing
import com.qalsmartkeyboard.ime.draw.InkDefaults
import com.qalsmartkeyboard.ime.draw.Pt
import com.qalsmartkeyboard.ime.draw.Stroke
import com.qalsmartkeyboard.ime.share.FakeImageShare
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * T8.7: the sticker panel's state (strip, maker, cap, gate, insert-vs-share, delete, typing), free of Views like
 * DrawControllerTest. Background work runs inline unless a test queues it.
 */
class StickerControllerTest {
    private lateinit var dir: File
    private var clock = 1_000L
    private var ids = 0
    private lateinit var store: StickerStore
    private val exporter = FakeStickerExporter()
    private val share = FakeStickerShare()
    private var access = PackAccess.ALLOWED
    private var drawing: Drawing? = null
    private val links = ArrayList<AiLink>()
    private var changes = 0
    private var inserted = 0
    private val queue = ArrayDeque<Runnable>()
    private var async = false
    private lateinit var c: StickerController

    @Before fun setUp() {
        dir = Files.createTempDirectory("sticker-ctl").toFile()
        store = StickerStore(File(dir, "stickers"), max = 3, clock = { clock++ }, newId = { "stk%04d".format(ids++) })
        c = StickerController(
            store, exporter, { share }, access = { access }, drawing = { drawing }, openLink = { links += it },
            background = { r -> if (async) queue.addLast(r) else r.run() },
            post = { r -> if (async) queue.addLast(r) else r.run() },
            onChanged = { changes++ },
        )
        c.onInserted = { inserted++ }
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun drain() {
        while (queue.isNotEmpty()) queue.removeFirst().run()
    }

    private fun make(text: String = "Hello"): SavedSticker {
        c.startMake(StickerSource.TEXT)
        c.text.set(text)
        c.make()
        return c.mine.first()
    }

    private fun stroke() = Stroke(listOf(Pt(0f, 0f), Pt(40f, 40f)), InkDefaults.BLACK, 6f)

    // ---- making --------------------------------------------------------------------------------------------------

    @Test fun makingATextStickerSavesItToThePersonalPackAndShowsItFirst() {
        c.open()
        assertTrue(c.mine.isEmpty())
        c.startMake()
        assertEquals(StickerScreen.MAKE, c.screen)
        c.text.set("  Selam  ")
        c.selectShape(StickerShape.CIRCLE)
        c.selectFill(3)
        c.selectSize(StickerTextSize.LARGE)
        c.make()
        assertEquals(StickerStatus.SAVED, c.status)
        assertEquals(StickerScreen.STRIP, c.screen)
        assertNull("the personal tab is shown", c.tab)
        assertEquals(1, c.mine.size)
        assertEquals(StickerKind.TEXT, c.mine[0].kind)
        val spec = exporter.specs.single() as TextStickerSpec
        assertEquals("Selam", spec.text)
        assertEquals(StickerShape.CIRCLE, spec.shape)
        assertEquals(StickerColours.FILLS[3], spec.fill)
        assertEquals(StickerTextSize.LARGE, spec.size)
        assertEquals("the text is not kept once it is a sticker", "", c.text.text)
        assertTrue(store.file(c.mine[0].id).isFile)
    }

    @Test fun nothingIsMadeWithoutText() {
        c.startMake(StickerSource.TEXT)
        c.text.set("   ")
        c.make()
        assertEquals(StickerStatus.NOTHING_TO_MAKE, c.status)
        assertTrue(exporter.specs.isEmpty())
        assertEquals(StickerScreen.MAKE, c.screen)
        assertEquals(0, store.count())
    }

    @Test fun anEmojiStickerTakesTheEmojiTheCaptionAndTheInk() {
        c.startMake(StickerSource.EMOJI)
        c.selectEmoji("🎉")
        c.caption.set("Congrats")
        c.selectShape(StickerShape.BURST)
        c.selectFill(7) // dark fill: the ink follows it until one is picked
        assertEquals(0xFFFFFFFF.toInt(), c.ink)
        c.make()
        val spec = exporter.specs.single() as EmojiStickerSpec
        assertEquals("🎉", spec.emoji)
        assertEquals("Congrats", spec.caption)
        assertEquals(0xFFFFFFFF.toInt(), spec.captionColour)
        assertEquals(StickerKind.EMOJI, c.mine[0].kind)
    }

    @Test fun anEmojiAloneIsAStickerToo() {
        c.startMake(StickerSource.EMOJI)
        c.make()
        assertEquals(StickerStatus.SAVED, c.status)
        assertEquals("", (exporter.specs.single() as EmojiStickerSpec).caption)
    }

    @Test fun thePickedInkWinsOverTheAutomaticOneAndAPlainStickerGetsBlackInk() {
        c.startMake(StickerSource.TEXT)
        assertEquals(StickerColours.INK[0], c.ink) // transparent sticker: black with a light outline
        c.selectShape(StickerShape.ROUNDED)
        c.selectFill(7)
        assertEquals(0xFFFFFFFF.toInt(), c.ink)
        c.selectInk(2)
        assertEquals(StickerColours.INK[2], c.ink)
        c.selectFill(1)
        assertEquals("a picked colour stays", StickerColours.INK[2], c.ink)
        assertEquals(2, c.inkIndex)
        c.selectInk(99)
        assertEquals(StickerColours.INK.last(), c.ink)
    }

    @Test fun aDrawingStickerUsesTheCurrentDrawingOrSaysThereIsNone() {
        c.startMake(StickerSource.DRAWING)
        assertFalse(c.hasDrawing)
        c.make()
        assertEquals(StickerStatus.NO_DRAWING, c.status)
        drawing = Drawing(100f, 100f) // a canvas with no ink is nothing to make
        c.make()
        assertEquals(StickerStatus.NO_DRAWING, c.status)
        drawing = Drawing(100f, 100f, strokes = listOf(stroke()))
        assertTrue(c.hasDrawing)
        c.setDrawingPaper(true)
        c.make()
        assertEquals(StickerStatus.SAVED, c.status)
        val spec = exporter.specs.single() as DrawingStickerSpec
        assertTrue(spec.withBackground)
        assertEquals(StickerKind.DRAWING, c.mine[0].kind)
    }

    @Test fun theTypedTextIsCutAtTheLimit() {
        c.text.set("x".repeat(100))
        assertEquals(StickerFormat.MAX_TEXT, c.text.text.length)
        c.caption.set("y".repeat(100))
        assertEquals(StickerFormat.MAX_CAPTION, c.caption.text.length)
    }

    @Test fun theCapRefusesWithAMessageAndDeletingMakesRoomAgain() {
        repeat(3) { make("n$it") }
        assertTrue(c.full)
        c.startMake(StickerSource.TEXT)
        c.text.set("one more")
        val before = exporter.specs.size
        c.make()
        assertEquals(StickerStatus.FULL, c.status)
        assertEquals("nothing is rendered for a full pack", before, exporter.specs.size)
        assertEquals(StickerScreen.MAKE, c.screen)
        assertEquals("what was typed is kept", "one more", c.text.text)
        c.backToStrip()
        val oldest = c.mine.last().id
        c.requestDelete(oldest)
        assertTrue(c.requestDelete(oldest))
        c.startMake(StickerSource.TEXT)
        c.make()
        assertEquals(StickerStatus.SAVED, c.status)
        assertEquals(3, c.mine.size)
    }

    @Test fun aFailedRenderLeavesNoStickerAndSaysSo() {
        exporter.ok = false
        c.startMake(StickerSource.TEXT)
        c.text.set("Hi")
        c.make()
        assertEquals(StickerStatus.SAVE_FAILED, c.status)
        assertEquals(0, store.count())
        assertEquals(StickerScreen.MAKE, c.screen)
        assertFalse(c.busy)
    }

    @Test fun renderingRunsInTheBackgroundAndASecondTapWhileBusyIsIgnored() {
        async = true
        c.startMake(StickerSource.TEXT)
        c.text.set("Hi")
        c.make()
        assertTrue(c.busy)
        assertEquals(StickerStatus.WORKING, c.status)
        assertTrue("nothing was rendered on the calling thread", exporter.specs.isEmpty())
        c.make()
        assertEquals(1, queue.size)
        drain()
        assertFalse(c.busy)
        assertEquals(StickerStatus.SAVED, c.status)
        assertEquals(1, exporter.specs.size)
        assertEquals(1, store.count())
    }

    // ---- insert, share, delete -----------------------------------------------------------------------------------

    @Test fun tappingAStickerInsertsItWhenTheFieldTakesWebp() {
        val s = make()
        c.insert(StickerRef.Mine(s.id), "Sticker")
        assertEquals(StickerStatus.INSERTED, c.status)
        assertEquals(1, inserted)
        assertEquals("image/webp", share.inserted.single().second)
        assertEquals(store.file(s.id), share.inserted.single().first)
        assertTrue(share.shared.isEmpty())
    }

    @Test fun aFieldThatRefusesWebpGetsTheShareSheetInstead() {
        val s = make()
        share.acceptsMime = emptySet()
        c.insert(StickerRef.Mine(s.id), "Sticker", "Share sticker")
        assertEquals(StickerStatus.SHARED, c.status)
        assertEquals(0, inserted)
        assertEquals(listOf("share:image/webp"), share.log)
        // The host accepted the type but refused the content: still the share sheet.
        share.log.clear()
        share.acceptsMime = setOf("image/webp")
        share.insertAccepted = false
        c.insert(StickerRef.Mine(s.id), "Sticker")
        assertEquals(listOf("insert:image/webp", "share:image/webp"), share.log)
        assertEquals(StickerStatus.SHARED, c.status)
        assertEquals(0, inserted)
    }

    @Test fun whenNothingWorksTheUserIsToldAndNothingIsInserted() {
        val s = make()
        share.acceptsMime = emptySet()
        share.shareAccepted = false
        c.insert(StickerRef.Mine(s.id), "Sticker")
        assertEquals(StickerStatus.SEND_FAILED, c.status)
        assertEquals(0, inserted)
        c.insert(StickerRef.Mine("stkmissing"), "Sticker")
        assertEquals(StickerStatus.SEND_FAILED, c.status)
    }

    @Test fun shareAlwaysOpensTheShareSheet() {
        val s = make()
        c.share(StickerRef.Mine(s.id), "Share sticker")
        assertEquals(StickerStatus.SHARED, c.status)
        assertEquals(listOf("share:image/webp"), share.log)
        assertEquals(0, inserted)
    }

    @Test fun aPngOnlyShareNeverGetsAWebpAndTheFailureIsReported() {
        val s = make()
        val old = FakeImageShare()
        val ctl = StickerController(store, exporter, { old }, access = { access })
        ctl.open()
        ctl.insert(StickerRef.Mine(s.id), "Sticker")
        assertEquals(StickerStatus.SEND_FAILED, ctl.status)
        assertTrue(old.log.isEmpty())
    }

    @Test fun deleteNeedsASecondTapAndAnyOtherActionDisarmsIt() {
        val a = make("a")
        val b = make("b")
        c.select(StickerRef.Mine(a.id))
        assertFalse(c.requestDelete(a.id))
        assertEquals(a.id, c.deleteArmedId)
        assertEquals(2, c.mine.size)
        c.select(StickerRef.Mine(b.id))
        assertNull(c.deleteArmedId)
        assertFalse(c.requestDelete(b.id))
        assertTrue(c.requestDelete(b.id))
        assertEquals(StickerStatus.DELETED, c.status)
        assertEquals(listOf(a.id), c.mine.map { it.id })
        assertFalse(store.file(b.id).exists())
        assertNull(c.selected)
    }

    @Test fun longPressSelectsTogglesAndTabsClearIt() {
        val a = make()
        val ref = StickerRef.Mine(a.id)
        c.select(ref)
        assertEquals(ref, c.selected)
        c.select(ref)
        assertNull(c.selected)
        c.select(ref)
        c.selectTab(StickerPackId.REACTIONS)
        assertNull(c.selected)
        assertEquals(StickerPackId.REACTIONS, c.tab)
        c.selectTab(null)
        assertNull(c.tab)
    }

    // ---- the premium packs ---------------------------------------------------------------------------------------

    private val hello = StickerRef.Pack(StickerPackId.GREETINGS, "hello")

    @Test fun aLockedPackRendersAndSendsNothingButStaysListed() {
        for (a in listOf(PackAccess.NOT_ON_PLAN, PackAccess.SIGN_IN, PackAccess.UNKNOWN)) {
            access = a
            assertTrue("$a", c.locked())
            c.insert(hello, "Sticker")
            assertEquals("$a", StickerStatus.LOCKED, c.status)
            c.share(hello, "Share")
            assertEquals("$a", StickerStatus.LOCKED, c.status)
        }
        assertTrue("a locked sticker is never rendered", exporter.specs.isEmpty())
        assertTrue(share.log.isEmpty())
        assertEquals(0, inserted)
        // The packs are listed: three of them, eight stickers each, whatever the plan says.
        assertEquals(3, StickerPacks.ALL.size)
        assertTrue(StickerPacks.ALL.all { it.stickers.size == 8 })
    }

    @Test fun anOpenPackIsRenderedOnFirstUseKeptAndInserted() {
        access = PackAccess.ALLOWED
        c.insert(hello, "Sticker")
        assertEquals(StickerStatus.INSERTED, c.status)
        assertEquals(1, exporter.specs.size)
        assertEquals((StickerPacks.find("greetings", "hello")!!.spec), exporter.specs[0])
        val f = store.packFile(StickerPackId.GREETINGS, "hello")
        assertTrue(f.isFile)
        assertFalse("no temporary file is left", File(f.path + ".tmp").exists())
        assertEquals(f, share.inserted.single().first)
        // The second use reuses the rendered file.
        c.insert(hello, "Sticker")
        assertEquals(1, exporter.specs.size)
        assertEquals(2, inserted)
        assertEquals("a bundled sticker is not part of the personal pack", 0, store.count())
    }

    @Test fun thePlanIsReadAtEveryTapSoALapsedPlanLocksARenderedPackAgain() {
        c.insert(hello, "Sticker")
        assertEquals(StickerStatus.INSERTED, c.status)
        access = PackAccess.NOT_ON_PLAN
        c.insert(hello, "Sticker")
        assertEquals(StickerStatus.LOCKED, c.status)
        assertEquals(1, share.inserted.size)
    }

    @Test fun yourOwnStickersAreNeverGatedByThePlan() {
        val s = make()
        access = PackAccess.SIGN_IN
        c.startMake(StickerSource.TEXT)
        c.text.set("still free")
        c.make()
        assertEquals(StickerStatus.SAVED, c.status)
        c.insert(StickerRef.Mine(s.id), "Sticker")
        assertEquals(StickerStatus.INSERTED, c.status)
    }

    @Test fun aBundledStickerIsRenderedOffTheMainThreadAndAFailureIsReported() {
        async = true
        c.insert(hello, "Sticker")
        assertTrue(c.busy)
        assertEquals(StickerStatus.WORKING, c.status)
        assertTrue(exporter.specs.isEmpty())
        drain()
        assertFalse(c.busy)
        assertEquals(StickerStatus.INSERTED, c.status)
        // A render that fails sends nothing and leaves no file.
        exporter.ok = false
        val wow = StickerRef.Pack(StickerPackId.REACTIONS, "wow")
        c.insert(wow, "Sticker")
        drain()
        assertEquals(StickerStatus.SEND_FAILED, c.status)
        assertFalse(store.packFile(StickerPackId.REACTIONS, "wow").exists())
        assertEquals(1, share.inserted.size)
    }

    @Test fun anUnknownBundledStickerIsAFailureNotACrash() {
        c.insert(StickerRef.Pack(StickerPackId.GREETINGS, "nope"), "Sticker")
        assertEquals(StickerStatus.SEND_FAILED, c.status)
    }

    @Test fun seePlansOpensTheCompanionAppAtThePlansScreenOrSignIn() {
        access = PackAccess.NOT_ON_PLAN
        c.seePlans()
        access = PackAccess.UNKNOWN
        c.seePlans()
        access = PackAccess.SIGN_IN
        c.seePlans()
        assertEquals(listOf(AiLink.UPGRADE, AiLink.UPGRADE, AiLink.SIGN_IN), links)
    }

    // ---- typing --------------------------------------------------------------------------------------------------

    @Test fun theKeysTypeIntoTheChosenFieldAndEnterEndsACaptionButBreaksALine() {
        assertNull(c.fieldTyping())
        c.startMake(StickerSource.TEXT)
        c.beginTyping(StickerField.TEXT)
        val f = c.fieldTyping()!!
        assertSame(c.text.sink, f.sink)
        assertTrue(f.newlineOnEnter)
        f.done()
        assertNull(c.typing)
        assertNull(c.fieldTyping())
        c.beginTyping(StickerField.CAPTION)
        assertSame(c.caption.sink, c.fieldTyping()!!.sink)
        assertFalse(c.fieldTyping()!!.newlineOnEnter)
        c.fieldTyping()!!.submit()
        assertNull(c.typing)
    }

    @Test fun backLeavesTypingThenTheMakerThenASelectionBeforeThePanelCloses() {
        val s = make()
        c.startMake(StickerSource.TEXT)
        c.beginTyping(StickerField.TEXT)
        assertTrue(c.back())
        assertNull(c.typing)
        assertEquals(StickerScreen.MAKE, c.screen)
        assertTrue(c.back())
        assertEquals(StickerScreen.STRIP, c.screen)
        c.select(StickerRef.Mine(s.id))
        assertTrue(c.back())
        assertNull(c.selected)
        assertFalse("nothing left to step out of: the panel closes", c.back())
    }

    @Test fun makingAfterTypingEndsTyping() {
        c.startMake(StickerSource.TEXT)
        c.text.set("Hi")
        c.beginTyping(StickerField.TEXT)
        c.make()
        assertNull(c.typing)
    }

    // ---- opening and closing -------------------------------------------------------------------------------------

    @Test fun openingShowsTheNewestFirstAndClosingForgetsEverythingTyped() {
        val a = make("a")
        val b = make("b")
        c.open()
        assertEquals(listOf(b.id, a.id), c.mine.map { it.id })
        c.startMake(StickerSource.EMOJI)
        c.text.set("typed")
        c.caption.set("cap")
        c.selectShape(StickerShape.BURST)
        c.beginTyping(StickerField.CAPTION)
        c.close(keepMaker = true) // a trip to Draw keeps the choices, never the texts
        assertEquals(StickerScreen.STRIP, c.screen)
        assertNull(c.typing)
        assertEquals("", c.text.text)
        assertEquals("", c.caption.text)
        assertEquals(StickerSource.EMOJI, c.source)
        assertEquals(StickerShape.BURST, c.shape)
        assertTrue(c.mine.isEmpty())
        c.discard()
        assertEquals(StickerSource.TEXT, c.source)
        assertEquals(StickerShape.NONE, c.shape)
        assertNotNull(c.packAccess)
        assertTrue(changes > 0)
    }

    @Test fun theStripShowsTheCapMessageWhenTheStoreIsFull() {
        repeat(3) { make("n$it") }
        c.open()
        assertEquals(3, c.mine.size)
        assertTrue(c.full)
    }
}
