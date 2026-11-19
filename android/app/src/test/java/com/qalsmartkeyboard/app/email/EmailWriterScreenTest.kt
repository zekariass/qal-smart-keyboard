package com.qalsmartkeyboard.app.email

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onFirst
import com.qalsmartkeyboard.ime.ai.AiActions
import com.qalsmartkeyboard.ime.ai.AiAvailability
import com.qalsmartkeyboard.ime.ai.AiCall
import com.qalsmartkeyboard.ime.ai.AiDestination
import com.qalsmartkeyboard.ime.ai.AiEmailHandoff
import com.qalsmartkeyboard.ime.ai.AiEmailMode
import com.qalsmartkeyboard.ime.ai.AiEmailSpec
import com.qalsmartkeyboard.ime.ai.AiEmailTone
import com.qalsmartkeyboard.ime.ai.AiFailure
import com.qalsmartkeyboard.ime.ai.AiLink
import com.qalsmartkeyboard.ime.ai.AiOutcome
import com.qalsmartkeyboard.ime.ai.AiRequest
import com.qalsmartkeyboard.ime.ai.AiResult
import com.qalsmartkeyboard.ime.ai.AiTask
import com.qalsmartkeyboard.ime.ai.AiTaskKind
import com.qalsmartkeyboard.ime.ai.ClipAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The companion app's "Write Email with AI" screen (ADR 0031) on the JVM, with a fake AI client. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h3000dp")
class EmailWriterScreenTest {
    @get:Rule val compose = createComposeRule()

    private class FakeActions : AiActions {
        val submitted = ArrayList<Pair<AiRequest, (AiOutcome) -> Unit>>()
        var cancelled = 0
        override var availability = AiAvailability()
        override fun destinations() = emptyList<AiDestination>()
        override fun defaultDestination(packageName: String?) = AiDestination("lang:en", "English", "en")
        override fun rememberDestination(packageName: String?, destination: AiDestination) {}
        override fun submit(request: AiRequest, onOutcome: (AiOutcome) -> Unit): AiCall {
            submitted += request to onOutcome
            return AiCall { cancelled++ }
        }
        override fun refresh() {}
        override fun addAvailabilityListener(listener: () -> Unit) = AutoCloseable {}
    }

    private class FakeHost(var clip: ClipAvailability = ClipAvailability.NONE, var clipText: String? = null) : EmailWriterHost {
        val copies = ArrayList<String>()
        val shares = ArrayList<Pair<String, String>>()
        val links = ArrayList<AiLink>()
        var pastes = 0
        override fun clipboard() = clip
        override fun paste(): String? { pastes++; return if (clip == ClipAvailability.TEXT) clipText else null }
        override fun copy(text: String): Boolean { copies += text; return true }
        override fun share(subject: String, body: String) { shares += subject to body }
        override fun openLink(link: AiLink) { links += link }
    }

    private val actions = FakeActions()
    private val remembered = ArrayList<String>()
    private val model = EmailWriterModel(actions, rememberSender = { remembered += it }, post = { it() }, newKey = { "key-${actions.submitted.size + 1}" })

    private fun show(host: FakeHost = FakeHost()): FakeHost {
        compose.setContent { EmailWriterScreen(model, host, onBack = {}) }
        return host
    }

    private fun spec(i: Int = actions.submitted.lastIndex) = (actions.submitted[i].first.task as AiTask.Email).spec

    private fun field(label: String) = compose.onAllNodes(hasSetTextAction() and hasText(label, substring = true)).onFirst()

    @Test fun newModeNeedsInstructionsThenSendsTheRequest() {
        show()
        compose.onNodeWithText("Generate email").performScrollTo().performClick()
        compose.onNodeWithText("Say what the e-mail should say.").assertExists()
        assertTrue(actions.submitted.isEmpty())
        field("What do you want to say?").performTextInput("ask for Friday off")
        compose.onNodeWithText("Formal").performScrollTo().performClick()
        compose.onNodeWithText("Amharic").performScrollTo().performClick()
        field("Your name").performScrollTo().performTextInput("Abebe")
        compose.onNodeWithText("Generate email").performScrollTo().performClick()
        val s = spec()
        assertEquals(AiEmailMode.NEW, s.mode)
        assertEquals("ask for Friday off", s.instructions)
        assertEquals(AiEmailTone.FORMAL, s.tone)
        assertEquals("am", s.language)
        assertEquals("Abebe", s.senderName)
        assertEquals(listOf("Abebe"), remembered)
        compose.onNodeWithText("Writing your e-mail…").assertExists()
    }

    @Test fun replyModeUsesThePasteButtonAndNeedsBothTexts() {
        val host = show(FakeHost(ClipAvailability.TEXT, "Can you attend on Monday?"))
        compose.onNodeWithText("Reply").performClick()
        compose.onNodeWithText("Paste from clipboard").performScrollTo().performClick()
        assertEquals(1, host.pastes)
        compose.onNodeWithText("Generate email").performScrollTo().performClick()
        compose.onNodeWithText("Say what the e-mail should say.").assertExists()
        field("What do you want to reply?").performScrollTo().performTextInput("yes I will")
        compose.onNodeWithText("Generate email").performScrollTo().performClick()
        assertEquals("Can you attend on Monday?", spec().originalEmail)
        assertEquals("yes I will", spec().instructions)
    }

    @Test fun aSensitiveClipCannotBePasted() {
        val host = show(FakeHost(ClipAvailability.SENSITIVE, "123456"))
        compose.onNodeWithText("Improve").performClick()
        compose.onNodeWithText("Copied item is marked sensitive, so it is not offered here.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Paste from clipboard").performScrollTo().performClick()
        assertEquals(0, host.pastes)
    }

    @Test fun thePreviewHighlightsPlaceholdersAndCopiesSharesAndRegenerates() {
        val host = show()
        model.instructions = "invite the team"
        compose.onNodeWithText("Generate email").performScrollTo().performClick()
        actions.submitted.single().second(AiOutcome.Success(AiResult("Hello [name],\nSee you on [date].", subject = "Team lunch", placeholders = listOf("[name]", "[date]"))))
        compose.onNodeWithText("Your e-mail").performScrollTo().assertExists()
        compose.onNodeWithText("Fill in before sending: [name], [date]").performScrollTo().assertExists()
        compose.onNodeWithText("Copy subject").performScrollTo().performClick()
        compose.onNodeWithText("Copy body").performScrollTo().performClick()
        compose.onNodeWithText("Copy all").performScrollTo().performClick()
        assertEquals(listOf("Team lunch", "Hello [name],\nSee you on [date].", "Team lunch\n\nHello [name],\nSee you on [date]."), host.copies)
        compose.onNodeWithText("Share").performScrollTo().performClick()
        assertEquals(listOf("Team lunch" to "Hello [name],\nSee you on [date]."), host.shares)
        compose.onNodeWithText("Regenerate").performScrollTo().performClick()
        assertEquals(1, spec().variation)
        assertNotEquals(actions.submitted[0].first.requestId, actions.submitted[1].first.requestId)
    }

    @Test fun inlineEditsUpdateThePlaceholdersToFill() {
        show()
        model.instructions = "x"
        compose.onNodeWithText("Generate email").performScrollTo().performClick()
        actions.submitted.single().second(AiOutcome.Success(AiResult("Hello [name]", subject = "S")))
        compose.runOnIdle { model.body = "Hello Almaz" }
        compose.onNodeWithText("Fill in before sending: [name]").assertDoesNotExist()
    }

    @Test fun aNotEntitledAnswerOffersThePlans() {
        val host = show()
        model.instructions = "x"
        compose.onNodeWithText("Generate email").performScrollTo().performClick()
        actions.submitted.single().second(AiOutcome.Failure(AiFailure.NotEntitled))
        compose.onNodeWithText("This feature is not part of your plan.").performScrollTo().assertExists()
        compose.onNodeWithText("See plans").performScrollTo().performClick()
        assertEquals(listOf(AiLink.UPGRADE), host.links)
    }

    @Test fun aLockedPlanSendsNothing() {
        actions.availability = AiAvailability(entitled = setOf(AiTaskKind.TRANSLATE))
        show()
        model.instructions = "x"
        compose.onNodeWithText("Generate email").performScrollTo().performClick()
        assertTrue(actions.submitted.isEmpty())
        compose.onNodeWithText("See plans").performScrollTo().assertExists()
    }

    @Test fun cancelAbortsTheCall() {
        show()
        model.instructions = "x"
        compose.onNodeWithText("Generate email").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        assertEquals(1, actions.cancelled)
        compose.onNodeWithText("Generate email").assertExists()
    }

    @Test fun theKeyboardsInputsArriveThroughTheHandoff() {
        AiEmailHandoff.put(AiEmailSpec(AiEmailMode.REPLY, "ti", "agree", originalEmail = "Original e-mail", tone = AiEmailTone.CASUAL))
        model.start("Saved Name", AiEmailHandoff.take())
        show()
        compose.onNodeWithText("Your inputs from the keyboard are filled in. When the e-mail is ready, copy it and paste it in your mail app.").assertExists()
        assertEquals(AiEmailMode.REPLY, model.mode)
        assertEquals("Original e-mail", model.originalEmail)
        assertEquals("ti", model.language)
        assertEquals("Saved Name", model.senderName)
    }
}
