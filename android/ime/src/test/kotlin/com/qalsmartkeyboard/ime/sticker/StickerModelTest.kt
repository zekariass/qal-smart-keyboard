package com.qalsmartkeyboard.ime.sticker

import com.qalsmartkeyboard.ime.ai.AiAvailability
import com.qalsmartkeyboard.ime.ai.AiBlock
import com.qalsmartkeyboard.ime.ai.AiLink
import com.qalsmartkeyboard.ime.ai.AiTaskKind
import com.qalsmartkeyboard.ime.ai.PlatformFeature
import com.qalsmartkeyboard.ime.draw.Drawing
import com.qalsmartkeyboard.ime.draw.InkDefaults
import com.qalsmartkeyboard.ime.draw.Pt
import com.qalsmartkeyboard.ime.draw.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T8.7: the sticker model (format helpers, the bundled packs, the plan gate). */
class StickerModelTest {
    // ---- format --------------------------------------------------------------------------------------------------

    @Test fun losslessWebpFromApi30AndTheLegacyCodecBelow() {
        assertEquals(StickerFormat.Codec.LEGACY, StickerFormat.codecFor(24)) // the module's minSdk
        assertEquals(StickerFormat.Codec.LEGACY, StickerFormat.codecFor(29))
        assertEquals(StickerFormat.Codec.LOSSLESS, StickerFormat.codecFor(30))
        assertEquals(StickerFormat.Codec.LOSSLESS, StickerFormat.codecFor(36))
        assertEquals(512, StickerFormat.SIZE_PX)
        assertEquals("image/webp", StickerFormat.MIME)
    }

    @Test fun aWebpIsRecognisedByItsRiffHeader() {
        assertTrue(StickerFormat.isWebp(FakeStickerExporter.WEBP_HEAD))
        assertFalse(StickerFormat.isWebp(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 0, 0, 0, 0, 0, 0, 0)))
        assertFalse(StickerFormat.isWebp(FakeStickerExporter.WEBP_HEAD.copyOf(8)))
        assertFalse(StickerFormat.isWebp(ByteArray(0)))
    }

    @Test fun theSafeAreaShrinksWithTheShapeAndStaysInsideTheSticker() {
        val f = StickerShape.entries.associateWith { StickerFormat.safeFraction(it) }
        assertTrue(f.values.all { it > 0.4f && it <= 1f })
        assertTrue(f.getValue(StickerShape.NONE) > f.getValue(StickerShape.ROUNDED))
        assertTrue(f.getValue(StickerShape.ROUNDED) > f.getValue(StickerShape.CIRCLE))
        // A square inscribed in a circle of the sticker's width is 0.707 of it: text in a circle must not leave the circle.
        assertTrue(f.getValue(StickerShape.CIRCLE) <= 0.71f)
    }

    @Test fun shapesAndKindsRoundTripTheirCodes() {
        for (s in StickerShape.entries) assertEquals(s, StickerShape.fromCode(s.code))
        assertEquals(StickerShape.NONE, StickerShape.fromCode(99))
        for (k in StickerKind.entries) assertEquals(k, StickerKind.fromCode(k.code))
        assertEquals(StickerKind.OTHER, StickerKind.fromCode('?'))
    }

    @Test fun specsNeverPrintTheUsersText() {
        val t = TextStickerSpec("my secret words", 0xFF000000.toInt())
        val e = EmojiStickerSpec("😀", "my secret caption")
        assertFalse(t.toString().contains("secret"))
        assertFalse(e.toString().contains("secret"))
        assertEquals(StickerKind.TEXT, t.kind)
        assertEquals(StickerKind.EMOJI, e.kind)
        val d = DrawingStickerSpec(Drawing(10f, 10f, strokes = listOf(Stroke(listOf(Pt(1f, 1f)), InkDefaults.BLACK, 2f))))
        assertEquals(StickerKind.DRAWING, d.kind)
        assertEquals(StickerShape.NONE, d.shape)
    }

    @Test fun contrastPicksBlackOnLightAndWhiteOnDark() {
        assertEquals(0xFF000000.toInt(), StickerColours.contrastOn(0xFFFFFFFF.toInt()))
        assertEquals(0xFF000000.toInt(), StickerColours.contrastOn(0xFFFFE082.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), StickerColours.contrastOn(0xFF263238.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), StickerColours.contrastOn(0xFF000000.toInt()))
    }

    // ---- the bundled packs ---------------------------------------------------------------------------------------

    @Test fun threeSmallPacksOfEightDesignedStickersEach() {
        assertEquals(listOf(StickerPackId.GREETINGS, StickerPackId.CELEBRATIONS, StickerPackId.REACTIONS), StickerPacks.ALL.map { it.id })
        for (p in StickerPacks.ALL) {
            assertEquals("${p.id}", 8, p.stickers.size)
            assertEquals("${p.id}", 8, p.stickers.map { it.id }.toSet().size)
            for (s in p.stickers) {
                val spec = s.spec as EmojiStickerSpec // emoji + caption compositions: no third-party art is bundled (N10)
                assertTrue(s.label, spec.emoji.isNotBlank() && spec.caption.isNotBlank())
                assertTrue(s.label, s.label.contains(spec.caption))
                // The caption stays readable on its fill.
                assertEquals(s.label, StickerColours.contrastOn(spec.fill), spec.captionColour)
                assertTrue(s.id, Regex("[a-z]+").matches(s.id))
            }
        }
        val files = StickerPacks.ALL.flatMap { p -> p.stickers.map { StickerPacks.fileName(p.id, it.id) } }
        assertEquals("every sticker has its own file name", 24, files.toSet().size)
        assertTrue(files.all { it.endsWith("_v${StickerPacks.VERSION}.webp") && StickerStore.validId(it.removeSuffix(".webp").lowercase().replace("_", "")) })
    }

    @Test fun packStickersAreFoundByTheirKeys() {
        assertEquals("Hello", (StickerPacks.find("greetings", "hello")?.spec as EmojiStickerSpec).caption)
        assertNull(StickerPacks.find("greetings", "nope"))
        assertNull(StickerPacks.find("nope", "hello"))
        assertEquals(StickerPackId.CELEBRATIONS, StickerPacks.pack(StickerPackId.CELEBRATIONS).id)
    }

    // ---- the plan gate -------------------------------------------------------------------------------------------

    private val sticker = PlatformFeature.STICKER_MAKER

    @Test fun theFeatureNeverGrantsWhileTheStateIsUnknownOrDoesNotListIt() {
        assertFalse(AiAvailability().grants(sticker))
        assertFalse(AiAvailability(platformFeatures = emptySet()).grants(sticker))
        assertFalse(AiAvailability(platformFeatures = setOf("PREMIUM_THEMES")).grants(sticker))
        assertTrue(AiAvailability(platformFeatures = setOf("PREMIUM_THEMES", sticker)).grants(sticker))
        // The AI "entitled" hint is a different thing: it never grants a platform feature.
        assertFalse(AiAvailability(entitled = AiTaskKind.entries.toSet()).grants(sticker))
    }

    @Test fun accessIsAllowedOnlyWhenThePlanSaysSo() {
        assertEquals(PackAccess.UNKNOWN, PackAccess.of(null))
        assertEquals(PackAccess.UNKNOWN, PackAccess.of(AiAvailability()))
        assertEquals(PackAccess.NOT_ON_PLAN, PackAccess.of(AiAvailability(platformFeatures = emptySet())))
        assertEquals(PackAccess.ALLOWED, PackAccess.of(AiAvailability(platformFeatures = setOf(sticker))))
        assertEquals(PackAccess.SIGN_IN, PackAccess.of(AiAvailability(block = AiBlock.SIGNED_OUT)))
        // A plan known from earlier stays valid offline; an update-required state is not a lock on its own.
        assertEquals(PackAccess.ALLOWED, PackAccess.of(AiAvailability(block = AiBlock.OFFLINE, platformFeatures = setOf(sticker))))
        assertEquals(PackAccess.UNKNOWN, PackAccess.of(AiAvailability(block = AiBlock.UPDATE_REQUIRED)))
        assertTrue(PackAccess.ALLOWED.open)
        assertTrue(listOf(PackAccess.NOT_ON_PLAN, PackAccess.SIGN_IN, PackAccess.UNKNOWN).none { it.open })
    }

    @Test fun seePlansOpensThePlansScreenAndSignedOutOpensSignIn() {
        assertEquals(AiLink.UPGRADE, PackAccess.NOT_ON_PLAN.link)
        assertEquals(AiLink.UPGRADE, PackAccess.UNKNOWN.link)
        assertEquals(AiLink.SIGN_IN, PackAccess.SIGN_IN.link)
    }
}
