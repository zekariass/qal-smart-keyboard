package com.qalsmartkeyboard.app.creative.cards

import com.qalsmartkeyboard.aiclient.FeatureAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bundled templates (data) and the premium gate (`CARD_MAKER`) of the card maker, on the plain JVM. */
class CardTemplatesAndGateTest {
    private val resolve: (Int) -> String = { "s$it" }

    @Test fun thereAreEightFreeAndEightPremiumTemplates() {
        assertEquals(8, CardTemplates.free.size)
        assertEquals(8, CardTemplates.premium.size)
        assertEquals(16, CardTemplates.all.map { it.id }.toSet().size) // unique ids
        assertEquals(16, CardTemplates.all.map { it.nameRes }.toSet().size) // each with its own name
        assertFalse(CardTemplates.default.premium) // a new card starts free
        assertNotNull(CardTemplates.find("birthday"))
        assertNull(CardTemplates.find("nope"))
    }

    @Test fun theFreeTemplatesUseOnlyFreeParts() {
        for (t in CardTemplates.free) {
            val doc = t.newDoc(resolve)
            assertEquals("${t.id} uses a premium part", emptySet<LockedPart>(), CardGate.premiumParts(doc))
            assertFalse(t.frame.premium)
        }
    }

    @Test fun everyPremiumTemplateIsPremiumToThePlatformGate() {
        for (t in CardTemplates.premium) {
            assertTrue(t.id, LockedPart.TEMPLATE in CardGate.premiumParts(t.newDoc(resolve)))
        }
    }

    @Test fun aNewDocFromATemplateCarriesItsLookAndTexts() {
        val t = CardTemplates.find("congrats")!!
        val d = t.newDoc(resolve, CardAspect.PORTRAIT, ExportFormat.JPEG)
        assertEquals("congrats", d.templateId)
        assertEquals("s${t.sample.headline}", d.headline)
        assertEquals("s${t.sample.body}", d.body)
        assertEquals(t.background, d.background)
        assertEquals(CardAspect.PORTRAIT, d.aspect)
        assertEquals(ExportFormat.JPEG, d.format)
        // A template without a starting sender line leaves it empty.
        assertEquals("", CardTemplates.find("plain")!!.newDoc(resolve).from)
    }

    @Test fun layoutsAreValid() {
        for (t in CardTemplates.all) {
            for (s in listOf(t.layout.headline, t.layout.body, t.layout.from)) assertTrue(s.top < s.bottom && s.minSize <= s.maxSize)
            assertTrue(t.layout.marginX in 0.05f..0.2f)
        }
    }

    @Test fun gradientPresetsHaveIdsAndColours() {
        assertEquals(CardPalette.gradients.size, CardPalette.gradients.map { it.id }.toSet().size)
        for (g in CardPalette.gradients) assertTrue(g.colors.size in 2..3)
        assertTrue(CardPalette.gradients.any { it.premium } && CardPalette.gradients.any { !it.premium })
        assertTrue(CardPalette.solids.size >= 12 && CardPalette.patterns.size >= 8)
        assertEquals(PatternKind.entries.toSet(), CardPalette.patterns.map { it.kind }.toSet())
    }

    // ---- The gate ---------------------------------------------------------------------------------------------------

    private fun free() = CardTemplates.find("birthday")!!.newDoc(resolve)
    private fun premium() = CardTemplates.find("wedding")!!.newDoc(resolve)

    @Test fun aFreeCardNeverNeedsThePlan() {
        for (a in FeatureAccess.entries) {
            assertTrue("$a", CardGate.canExport(free(), a))
            assertTrue(CardGate.templateAllowed(CardTemplates.find("birthday")!!, a))
            assertTrue(CardGate.frameAllowed(CardFrame.THIN, a))
            assertTrue(CardGate.backgroundAllowed(CardBackground.Solid(0), a))
            assertTrue(CardGate.backgroundAllowed(CardBackground.Photo(null), a)) // a photo background is free
            assertTrue(CardGate.backgroundAllowed(CardPalette.gradient("ocean")!!.toBackground(), a))
        }
    }

    @Test fun aPremiumTemplateIsBlockedWithoutTheEntitlement() {
        val t = CardTemplates.find("wedding")!!
        for (a in listOf(FeatureAccess.SIGNED_OUT, FeatureAccess.NOT_ON_PLAN, FeatureAccess.NEEDS_CHECK)) {
            assertFalse("$a", CardGate.templateAllowed(t, a))
            assertFalse(CardGate.canExport(premium(), a))
            assertTrue(LockedPart.TEMPLATE in CardGate.lockedParts(premium(), a))
        }
    }

    @Test fun aPremiumTemplateIsAllowedWithTheEntitlement() {
        assertTrue(CardGate.templateAllowed(CardTemplates.find("wedding")!!, FeatureAccess.ALLOWED))
        assertTrue(CardGate.canExport(premium(), FeatureAccess.ALLOWED))
        assertTrue(CardGate.lockedParts(premium(), FeatureAccess.ALLOWED).isEmpty())
    }

    @Test fun premiumFramesAndGradientsOnAFreeTemplateAreGatedToo() {
        val withFrame = free().copy(frame = CardFrame.CORNERS)
        assertEquals(setOf(LockedPart.FRAME), CardGate.premiumParts(withFrame))
        assertFalse(CardGate.canExport(withFrame, FeatureAccess.NOT_ON_PLAN))
        assertTrue(CardGate.canExport(withFrame, FeatureAccess.ALLOWED))
        val gold = free().copy(background = CardPalette.gradient("gold")!!.toBackground())
        assertEquals(setOf(LockedPart.BACKGROUND), CardGate.premiumParts(gold))
        assertFalse(CardGate.backgroundAllowed(gold.background, FeatureAccess.SIGNED_OUT))
        assertTrue(CardGate.backgroundAllowed(gold.background, FeatureAccess.ALLOWED))
        val all = premium().copy(frame = CardFrame.DOUBLE, background = gold.background)
        assertEquals(setOf(LockedPart.TEMPLATE, LockedPart.BACKGROUND, LockedPart.FRAME), CardGate.premiumParts(all))
    }

    @Test fun onlyThinAndNoFrameAreFree() {
        assertEquals(setOf(CardFrame.NONE, CardFrame.THIN), CardFrame.entries.filter { !it.premium }.toSet())
    }

}
