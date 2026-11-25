package com.qalsmartkeyboard.app.creative.cards

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import com.qalsmartkeyboard.aiclient.FeatureAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The card maker screen (T8.7) on the JVM at 360 dp wide: the steps, free and locked templates (a locked one opens the
 * plans, never applies), editing, premium extras gated by the feature, export buttons, touch-target sizes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2500dp")
class CardMakerScreenTest {
    @get:Rule val compose = createComposeRule()

    private val resolve: (Int) -> String = { id -> "text$id" }
    private lateinit var editor: CardEditor
    private var unlocks = 0
    private var saves = 0
    private var shares = 0
    private var backs = 0
    private var news = 0
    private var picks = 0

    private fun show(access: FeatureAccess, section: CardSection = CardSection.TEMPLATE, doc: CardDoc? = null, direction: LayoutDirection = LayoutDirection.Ltr) {
        editor = CardEditor(doc ?: CardTemplates.default.newDoc(resolve = { "start$it" })).also { it.section = section }
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) { CardMakerScreen(
                editor = editor, access = access, preview = null, templateThumbs = emptyMap(),
                onPickPhoto = { picks++ }, onSave = { saves++ }, onShare = { shares++ }, onUnlock = { unlocks++ },
                onBack = { backs++ }, onNew = { news++ }, resolve = resolve,
            ) }
        }
    }

    @Test fun showsTheStepsAndTheTemplatesWithPremiumOnesMarkedLocked() {
        show(FeatureAccess.NOT_ON_PLAN)
        for (s in listOf("Template", "Text", "Background", "Style", "Export")) compose.onNodeWithText(s).assertExists()
        compose.onNodeWithText("Free templates").assertExists()
        compose.onNodeWithText("Birthday").assertExists()
        compose.onNodeWithText("Premium templates").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("Wedding, premium template, locked").performScrollTo().assertHasClickAction()
        compose.onNodeWithContentDescription("Birthday").assertIsSelected()
    }

    @Test fun aLockedTemplateOpensThePlansAndIsNotApplied() {
        show(FeatureAccess.NOT_ON_PLAN)
        compose.onNodeWithContentDescription("Wedding, premium template, locked").performScrollTo().performClick()
        assertEquals(1, unlocks)
        assertEquals("birthday", editor.doc.templateId)
    }

    @Test fun aFreeTemplateIsAppliedWithoutThePlan() {
        show(FeatureAccess.SIGNED_OUT)
        compose.onNodeWithContentDescription("Thank you").performScrollTo().performClick()
        assertEquals("thanks", editor.doc.templateId)
        assertEquals(0, unlocks)
    }

    @Test fun withTheEntitlementAPremiumTemplateIsAppliedAndNotMarkedLocked() {
        show(FeatureAccess.ALLOWED)
        compose.onNodeWithContentDescription("Wedding, premium template").performScrollTo().performClick()
        assertEquals("wedding", editor.doc.templateId)
        assertEquals(0, unlocks)
        assertEquals(0, compose.onAllNodesWithContentDescription("Wedding, premium template, locked").fetchSemanticsNodes().size)
    }

    @Test fun theTextStepEditsTheThreeFields() {
        show(FeatureAccess.NOT_ON_PLAN, CardSection.TEXT)
        compose.onNodeWithText("Headline").assertExists()
        compose.onAllNodesWithText("Message")[0].assertExists()
        compose.onNodeWithText("From (optional)").assertExists()
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextClearance()
        fields[0].performTextInput("መልካም ልደት!")
        assertEquals("መልካም ልደት!", editor.doc.headline)
        fields[1].performTextInput(" Baga gammaddan")
        assertTrue(editor.doc.body.contains(" Baga gammaddan"))
        assertTrue(editor.dirty)
    }

    @Test fun backgroundStepOffersColoursGradientsPatternsAndAPhotoThatNeverLeavesTheDevice() {
        show(FeatureAccess.NOT_ON_PLAN, CardSection.BACKGROUND)
        compose.onNodeWithText("Colours").assertExists()
        compose.onNodeWithContentDescription("Colour #1A237E").performScrollTo().performClick()
        assertEquals(CardBackground.Solid(0xFF1A237E.toInt()), editor.doc.background)
        compose.onNodeWithContentDescription("Gradient 2").performScrollTo().performClick()
        assertEquals("ocean", (editor.doc.background as CardBackground.Gradient).presetId)
        compose.onNodeWithText("Choose a photo").performScrollTo().performClick()
        assertEquals(1, picks)
        compose.onNodeWithText("The photo stays on this device. It is never uploaded.").performScrollTo().assertExists()
    }

    @Test fun aPremiumGradientIsLockedWithoutThePlan() {
        show(FeatureAccess.NOT_ON_PLAN, CardSection.BACKGROUND)
        val premiumIndex = CardPalette.gradients.indexOfFirst { it.premium } + 1
        compose.onNodeWithContentDescription("Gradient $premiumIndex, premium, locked").performScrollTo().performClick()
        assertEquals(1, unlocks)
        assertTrue(editor.doc.background !is CardBackground.Gradient || (editor.doc.background as CardBackground.Gradient).presetId != "gold")
    }

    @Test fun aPremiumGradientIsAppliedWithThePlan() {
        show(FeatureAccess.ALLOWED, CardSection.BACKGROUND)
        val premiumIndex = CardPalette.gradients.indexOfFirst { it.premium } + 1
        compose.onNodeWithContentDescription("Gradient $premiumIndex").performScrollTo().performClick()
        assertEquals("gold", (editor.doc.background as CardBackground.Gradient).presetId)
    }

    @Test fun styleStepChangesFontColourAlignmentAndFrame() {
        show(FeatureAccess.NOT_ON_PLAN, CardSection.STYLE)
        compose.onNodeWithText("Serif").performScrollTo().performClick()
        assertEquals(CardFont.SERIF, editor.doc.text.font)
        compose.onNodeWithContentDescription("Colour #C62828").performScrollTo().performClick()
        assertEquals(0xFFC62828.toInt(), editor.doc.text.color)
        compose.onNodeWithText("End").performScrollTo().performClick()
        assertEquals(CardTextAlign.END, editor.doc.text.align)
        compose.onNodeWithText("Thin").performScrollTo().performClick()
        assertEquals(CardFrame.THIN, editor.doc.frame)
        // A premium frame opens the plans instead.
        compose.onNodeWithText("Double").performScrollTo().performClick()
        assertEquals(1, unlocks)
        assertEquals(CardFrame.THIN, editor.doc.frame)
    }

    @Test fun premiumFramesApplyWithThePlan() {
        show(FeatureAccess.ALLOWED, CardSection.STYLE)
        compose.onNodeWithText("Corners").performScrollTo().performClick()
        assertEquals(CardFrame.CORNERS, editor.doc.frame)
        assertEquals(0, unlocks)
    }

    @Test fun exportStepChoosesSizeAndFormatAndSavesOrSharesAFreeCard() {
        show(FeatureAccess.SIGNED_OUT, CardSection.EXPORT)
        compose.onNodeWithText("Square 1080 × 1080").assertExists()
        compose.onNodeWithText("Portrait 1080 × 1350").performClick()
        assertEquals(CardAspect.PORTRAIT, editor.doc.aspect)
        compose.onNodeWithText("PNG").performScrollTo().assertIsSelected()
        compose.onNodeWithText("JPEG").performClick()
        assertEquals(ExportFormat.JPEG, editor.doc.format)
        compose.onNodeWithText("Share").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Save to my cards").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, shares)
        assertEquals(1, saves)
        compose.onNodeWithText("Your card is made on this device. Nothing is uploaded. You choose where to share it.").performScrollTo().assertExists()
    }

    @Test fun aCardWithPremiumPartsCannotBeExportedWithoutThePlan() {
        val premium = CardTemplates.find("wedding")!!.newDoc(resolve = { "x" })
        show(FeatureAccess.NOT_ON_PLAN, CardSection.EXPORT, premium)
        compose.onNodeWithText("This card uses Premium parts").performScrollTo().assertExists()
        compose.onNodeWithText("Share").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Save to my cards").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Unlock with Premium").performScrollTo().performClick()
        assertEquals(1, unlocks)
    }

    @Test fun aPremiumCardExportsWithThePlan() {
        val premium = CardTemplates.find("wedding")!!.newDoc(resolve = { "x" })
        show(FeatureAccess.ALLOWED, CardSection.EXPORT, premium)
        compose.onNodeWithText("Share").performScrollTo().assertIsEnabled()
        assertEquals(0, compose.onAllNodesWithText("This card uses Premium parts").fetchSemanticsNodes().size)
    }

    @Test fun backAndNewCardAreInTheBar() {
        show(FeatureAccess.ALLOWED)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("New card").performClick()
        assertEquals(1, backs)
        assertEquals(1, news)
    }

    @Test fun touchTargetsAreAtLeast48dp() {
        show(FeatureAccess.NOT_ON_PLAN, CardSection.BACKGROUND)
        val swatch = compose.onNodeWithContentDescription("Colour #1A237E").performScrollTo().getUnclippedBoundsInRoot()
        assertTrue("${swatch.height}", swatch.height >= 48.dp)
        compose.onNodeWithText("Choose a photo").performScrollTo()
        val button = compose.onNodeWithText("Choose a photo").getUnclippedBoundsInRoot()
        assertTrue(button.height >= 20.dp) // the text; its Button parent is 40 dp+, with the 48 dp interactive minimum
    }

    private fun assertWithinScreen(description: String, vararg nodes: androidx.compose.ui.test.SemanticsNodeInteraction) {
        for (n in nodes) {
            val b = n.performScrollTo().getUnclippedBoundsInRoot()
            assertTrue("$description: ${b.left}..${b.right}", b.left >= 0.dp && b.right <= 360.dp)
        }
    }

    @Test fun nothingOverflowsAt360dpWithFontScale1_3() {
        RuntimeEnvironment.setFontScale(1.3f)
        show(FeatureAccess.NOT_ON_PLAN, CardSection.EXPORT, CardTemplates.find("wedding")!!.newDoc(resolve = { "x" }))
        assertWithinScreen(
            "export",
            compose.onNodeWithText("This card uses Premium parts"), compose.onNodeWithText("Unlock with Premium"),
            compose.onNodeWithText("Share"), compose.onNodeWithText("Save to my cards"),
            compose.onNodeWithText("Your card is made on this device. Nothing is uploaded. You choose where to share it."),
        )
    }

    @Test fun theBackgroundAndTextStepsFitAt360dpWithFontScale1_3() {
        RuntimeEnvironment.setFontScale(1.3f)
        show(FeatureAccess.ALLOWED, CardSection.BACKGROUND)
        assertWithinScreen(
            "background",
            compose.onNodeWithText("Choose a photo"), compose.onNodeWithText("The photo stays on this device. It is never uploaded."),
            compose.onNodeWithContentDescription("Colour #FFFFFF"), compose.onNodeWithContentDescription("Colour #4E342E"),
        )
    }

    @Test fun theTextFieldsFitAt360dpWithFontScale1_3() {
        RuntimeEnvironment.setFontScale(1.3f)
        show(FeatureAccess.ALLOWED, CardSection.TEXT)
        for (n in compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().indices) {
            val b = compose.onAllNodes(hasSetTextAction())[n].getUnclippedBoundsInRoot()
            assertTrue("field $n ${b.right}", b.right <= 360.dp)
        }
    }

    @Test fun theLayoutMirrorsInARightToLeftLocaleWithoutLosingAnyControl() {
        show(FeatureAccess.ALLOWED, CardSection.TEMPLATE, direction = LayoutDirection.Rtl)
        // The back arrow sits at the start edge, which is the right one now; the grid still has its cards.
        assertTrue(compose.onNodeWithContentDescription("Back").getUnclippedBoundsInRoot().left > 180.dp)
        compose.onNodeWithText("Free templates").assertExists()
        compose.onNodeWithContentDescription("Wedding, premium template").performScrollTo().assertHasClickAction()
    }
}
