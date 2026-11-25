package com.qalsmartkeyboard.app.creative.annotate

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
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
 * The annotator screen (T8.7) on the JVM at 360 dp wide: the empty state and the draft, the tools with premium ones
 * locked (a locked tool opens the plans, never selects), the export dialog that explains flattening, the locked export
 * with the free "solid boxes" way out, touch targets, font scale 1.3 and right-to-left.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h740dp")
class AnnotatorScreenTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var editor: AnnotatorEditor
    private var picks = 0
    private var continues = 0
    private var discards = 0
    private val exports = ArrayList<ExportKind>()
    private var solidifies = 0
    private var unlocks = 0
    private var backs = 0

    private fun show(access: FeatureAccess, doc: AnnotDoc? = AnnotDoc(1000, 800), draftWaiting: Boolean = false, direction: LayoutDirection = LayoutDirection.Ltr) {
        editor = AnnotatorEditor()
        if (doc != null) editor.restore(doc, com.qalsmartkeyboard.app.creative.cards.ExportFormat.PNG)
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                AnnotatorScreen(
                    editor = editor, access = access, preview = null, draftWaiting = draftWaiting,
                    onPick = { picks++ }, onContinueDraft = { continues++ }, onDiscardDraft = { discards++ },
                    onExport = { exports += it }, onSolidify = { solidifies++; editor.replace(AnnotatorGate.solidifyLockedRedactions(editor.doc!!, access)) },
                    onUnlock = { unlocks++ }, onBack = { backs++ }, onFull = {},
                )
            }
        }
    }

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test fun withoutAPictureItOffersThePickerAndSaysNothingIsUploaded() {
        show(FeatureAccess.NOT_ON_PLAN, doc = null)
        compose.onNodeWithText("Choose a screenshot or photo").performClick()
        assertEquals(1, picks)
        compose.onNodeWithText("Pick a screenshot or photo to mark up. It is read on this phone only and never uploaded.").assertExists()
        compose.onNodeWithText("Your picture is edited on this phone. Nothing is uploaded. You choose where to share it.").assertExists()
        assertEquals(0, count("Continue"))
    }

    @Test fun aStoredDraftCanBeContinuedOrDiscardedAfterConfirmation() {
        show(FeatureAccess.NOT_ON_PLAN, doc = null, draftWaiting = true)
        compose.onNodeWithText("Continue your last picture?").assertExists()
        compose.onNodeWithText("Continue").performClick()
        assertEquals(1, continues)
        compose.onNodeWithText("Discard draft").performClick()
        compose.onNodeWithText("Discard this draft?").assertExists()
        assertEquals(0, discards)
        compose.onAllNodesWithText("Discard draft")[1].performClick()
        assertEquals(1, discards)
    }

    @Test fun premiumToolsAreLockedAndOpenThePlansInsteadOfBeingChosen() {
        show(FeatureAccess.NOT_ON_PLAN)
        for (name in listOf("Blur", "Pixelate", "Numbered steps", "Spotlight")) {
            compose.onNodeWithContentDescription("$name, premium, locked").performScrollTo().assertHasClickAction().performClick()
        }
        assertEquals(4, unlocks)
        assertEquals(AnnotTool.PEN, editor.tool)
        // Free tools, the solid box and the crop among them, are chosen at once.
        for ((name, tool) in listOf("Cover with a box" to AnnotTool.REDACT_SOLID, "Crop" to AnnotTool.CROP, "Arrow" to AnnotTool.ARROW, "Text" to AnnotTool.TEXT)) {
            compose.onNodeWithContentDescription(name).performScrollTo().performClick()
            assertEquals(tool, editor.tool)
            compose.onNodeWithContentDescription(name).assertIsSelected()
        }
        assertEquals(4, unlocks)
    }

    @Test fun withThePlanPremiumToolsAreChosenAndNotMarkedLocked() {
        show(FeatureAccess.ALLOWED)
        compose.onNodeWithContentDescription("Blur, premium").performScrollTo().performClick()
        assertEquals(AnnotTool.BLUR, editor.tool)
        assertEquals(0, unlocks)
        compose.onNodeWithText("Drag over private details. In the copy they are hidden for good.").assertExists()
        compose.onNodeWithText("Brush").performScrollTo().performClick()
        assertEquals(RegionShape.BRUSH, editor.regionShape)
        compose.onNodeWithText("Thick").performScrollTo().performClick()
        assertEquals(Thickness.THICK, editor.thickness)
    }

    @Test fun optionsFollowTheTool() {
        show(FeatureAccess.NOT_ON_PLAN)
        compose.onNodeWithContentDescription("Colour #1E88E5").performScrollTo().performClick()
        assertEquals(0xFF1E88E5.toInt(), editor.color)
        compose.onNodeWithContentDescription("Text").performScrollTo().performClick()
        compose.onNodeWithText("Large").performScrollTo().performClick()
        assertEquals(LabelSize.LARGE, editor.labelSize)
        compose.onNodeWithText("Background").performScrollTo().performClick()
        assertEquals(false, editor.labelBackdrop)
    }

    @Test fun saveACopyExplainsTheFlatteningFirst() {
        show(FeatureAccess.NOT_ON_PLAN)
        compose.onNodeWithText("Save a copy").performClick()
        compose.onNodeWithText("Save a flat copy").assertExists()
        compose.onNodeWithText(
            "This makes a new, flat picture. Blur, pixelate and boxes are burnt into it and cannot be undone in the copy, and anything outside the crop is cut off. Your draft stays on this phone until you discard it.",
        ).assertExists()
        compose.onNodeWithText("JPEG").performClick()
        assertEquals(com.qalsmartkeyboard.app.creative.cards.ExportFormat.JPEG, editor.format)
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        assertEquals(listOf(ExportKind.SAVE), exports)
        compose.onNodeWithText("Share").performClick()
        compose.onNodeWithText("Share a flat copy").assertExists()
        compose.onAllNodesWithText("Share")[1].performClick() // the dialog's confirm
        assertEquals(listOf(ExportKind.SAVE, ExportKind.SHARE), exports)
    }

    @Test fun aPictureWithLockedBlurCannotBeExportedButCanSwitchToFreeSolidBoxes() {
        val doc = AnnotDoc(1000, 800).with(Annotation.Redaction(1, RedactMode.BLUR, RedactArea.Rect(Box(10f, 10f, 200f, 60f))))
        show(FeatureAccess.SIGNED_OUT, doc)
        compose.onNodeWithText("Share").performClick()
        compose.onNodeWithText("This picture uses Premium tools").performScrollTo().assertExists()
        compose.onNodeWithText("Sign in to use the Premium tools of your account.").performScrollTo().assertExists()
        compose.onAllNodesWithText("Share")[1].assertIsNotEnabled()
        compose.onNodeWithText("Use solid boxes instead (free)").performScrollTo().performClick()
        assertEquals(1, solidifies)
        assertEquals(RedactMode.SOLID, editor.doc!!.redactions.single().mode)
        // Now it can go, still redacted.
        compose.onAllNodesWithText("Share")[1].assertIsEnabled().performClick()
        assertEquals(listOf(ExportKind.SHARE), exports)
        assertEquals(0, count("This picture uses Premium tools"))
    }

    @Test fun aLockedStepMarkerOffersThePlansButNoSolidBoxes() {
        val doc = AnnotDoc(1000, 800).with(Annotation.Step(1, Pt(10f, 10f), 1, 0, 20f))
        show(FeatureAccess.NOT_ON_PLAN, doc)
        compose.onNodeWithText("Save a copy").performClick()
        assertEquals(0, count("Use solid boxes instead (free)"))
        compose.onNodeWithText("Unlock with Premium").performScrollTo().performClick()
        assertEquals(1, unlocks)
    }

    @Test fun undoRedoAndTheMenuAreInTheBar() {
        show(FeatureAccess.ALLOWED)
        compose.onNodeWithContentDescription("Undo").assertIsNotEnabled()
        editor.press(Pt(10f, 10f), 5f); editor.drag(Pt(100f, 100f)); editor.release()
        compose.onNodeWithContentDescription("Undo").assertIsEnabled().performClick()
        assertTrue(editor.doc!!.items.isEmpty())
        compose.onNodeWithContentDescription("Redo").assertIsEnabled().performClick()
        assertEquals(1, editor.doc!!.items.size)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("New picture").performClick()
        compose.onNodeWithText("Start with a new picture?").assertExists() // there are unsaved marks
        compose.onAllNodesWithText("New picture")[0].performClick()
        assertEquals(1, picks)
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }

    @Test fun theLastMarkCanBeDeletedFromTheOptions() {
        show(FeatureAccess.ALLOWED)
        editor.press(Pt(10f, 10f), 5f); editor.drag(Pt(100f, 100f)); editor.release()
        compose.onNodeWithText("Delete mark").performScrollTo().performClick()
        assertTrue(editor.doc!!.items.isEmpty())
    }

    @Test fun touchTargetsAreAtLeast48dp() {
        show(FeatureAccess.NOT_ON_PLAN)
        for (d in listOf("Pen", "Blur, premium, locked", "Colour #E53935", "Undo")) {
            val node = compose.onNodeWithContentDescription(d)
            if (d != "Undo") node.performScrollTo() // the bar is not a scrolling row
            // The touch area (an IconButton draws 40 dp and extends its touch area to the 48 dp minimum).
            val t = node.fetchSemanticsNode().touchBoundsInRoot
            val density = compose.density.density
            assertTrue("$d: ${t.width / density} x ${t.height / density} dp", t.width / density >= 47.9f && t.height / density >= 47.9f)
        }
    }

    private fun assertWithinScreen(what: String, vararg nodes: SemanticsNodeInteraction) {
        for (n in nodes) {
            val b = n.getUnclippedBoundsInRoot()
            assertTrue("$what: ${b.left}..${b.right}", b.left >= 0.dp && b.right <= 360.dp)
        }
    }

    @Test fun nothingOverflowsAt360dpWithFontScale1_3() {
        RuntimeEnvironment.setFontScale(1.3f)
        show(FeatureAccess.NOT_ON_PLAN, AnnotDoc(1000, 800).with(Annotation.Redaction(1, RedactMode.PIXELATE, RedactArea.Rect(Box(1f, 1f, 50f, 50f)))))
        assertWithinScreen(
            "editor",
            compose.onNodeWithText("Save a copy"), compose.onNodeWithText("Share"),
            compose.onNodeWithContentDescription("Undo"), compose.onNodeWithContentDescription("More options"),
            compose.onNodeWithContentDescription("Back"),
        )
        compose.onNodeWithText("Save a copy").performClick()
        assertWithinScreen(
            "export dialog",
            compose.onNodeWithText("This picture uses Premium tools").performScrollTo(),
            compose.onNodeWithText("Use solid boxes instead (free)").performScrollTo(),
            compose.onNodeWithText("Unlock with Premium").performScrollTo(),
        )
    }

    @Test fun theEmptyStateFitsAt360dpWithFontScale1_3() {
        RuntimeEnvironment.setFontScale(1.3f)
        show(FeatureAccess.NOT_ON_PLAN, doc = null, draftWaiting = true)
        assertWithinScreen(
            "empty",
            compose.onNodeWithText("Continue").performScrollTo(), compose.onNodeWithText("Choose a screenshot or photo").performScrollTo(),
            compose.onNodeWithText("Your picture is edited on this phone. Nothing is uploaded. You choose where to share it.").performScrollTo(),
        )
    }

    @Test fun theLayoutMirrorsInARightToLeftLocaleWithoutLosingAnyControl() {
        show(FeatureAccess.ALLOWED, direction = LayoutDirection.Rtl)
        assertTrue(compose.onNodeWithContentDescription("Back").getUnclippedBoundsInRoot().left > 180.dp)
        assertTrue(compose.onNodeWithContentDescription("Undo").getUnclippedBoundsInRoot().right < 180.dp)
        compose.onNodeWithContentDescription("Blur, premium").performScrollTo().assertHasClickAction()
        assertWithinScreen("rtl", compose.onNodeWithText("Save a copy"), compose.onNodeWithText("Share"))
    }
}
