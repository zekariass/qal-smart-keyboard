package com.qalsmartkeyboard.ime.ai

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * The Ask AI chat panel (plan T8.5): bubbles, the buttons' sizes and names, Insert and Copy, the banners with their links,
 * the typing indicator, the quick starters and the field-text chip, and that the panel at the AI panels' height never
 * clips its input row or Send, on a 411 dp and on a 360 dp phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real text measurement: line breaks and button widths as on a device
class ChatPanelViewTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).roundToInt()

    /** The AI panel height on a 2.625-density phone: the 260 dp key area plus 56 dp. */
    private val panelHeight get() = dp(316f)

    private inner class H(selection: String? = null, fieldText: String = "field text") {
        val actions = FakeAiActions()
        val editor = FakeAiEditor(selection, fieldText)
        val clipboard = FakeClipboard()
        val view = ChatPanelView(ctx)
        val links = ArrayList<AiLink>()
        val applied = ArrayList<AiApplied>()
        var closed = 0
        var backs = 0
        val ws = AiWorkspace(
            actions, editor, clipboard, AiLanguagePrefs.InMemory(), post = { it() },
            keyboardLanguage = { "en" }, translateDefault = { "en" }, onChanged = { view.render() },
        )
        val chat get() = ws.chat

        init {
            view.controller = ws.chat
            view.heightProvider = { panelHeight }
            view.onLink = { links += it }
            view.onApplied = { applied += it }
            view.onClose = { closed++ }
            view.onBack = { backs++ }
            ws.openChat()
        }

        fun layout(widthDp: Float = 411f): ChatPanelView {
            view.visibility = View.VISIBLE
            view.measure(View.MeasureSpec.makeMeasureSpec(dp(widthDp), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            return view
        }

        fun say(text: String, reply: String? = null) {
            chat.input.set(text)
            chat.send()
            if (reply != null) actions.answer(actions.submitted.lastIndex, FakeAiActions.ok(reply))
        }

        fun texts(): List<String> = all(view).filterIsInstance<TextView>().map { it.text.toString() }
        fun button(text: String): TextView = all(view).filterIsInstance<TextView>().first { it.text.toString() == text }
        fun click(text: String) = button(text).performClick()
    }

    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)

    // ------------------------------------------------------------ the empty chat

    @Test fun anEmptyChatShowsTheTitleTheStartersAndTheInputRow() {
        val h = H()
        val t = h.texts()
        assertTrue(t.contains("Ask AI"))
        assertTrue(t.contains("Ask for help with writing, words or ideas."))
        for (s in listOf("Write a message", "Explain a word", "Improve my text", "Brainstorm ideas")) assertTrue(s, t.contains(s))
        assertTrue(t.contains("Send"))
        assertTrue(t.contains("Reply in: Same as I write  ▾"))
        assertTrue(t.contains("Use text in field"))
        assertEquals(4, h.view.starterViews.size)
        assertFalse("nothing is sent by opening", h.actions.submitted.isNotEmpty())
        assertFalse(h.chat.canSend)
        assertFalse("Send is off without a message", h.view.sendView!!.isEnabled)
    }

    @Test fun aStarterPrefillsTheInputAndDoesNotSend() {
        val h = H()
        h.view.starterViews.getValue(ChatStarter.WRITE).performClick()
        assertEquals("Write a message to ", h.chat.input.text)
        assertTrue("the keys come up to finish it", h.chat.typing)
        assertTrue(h.actions.submitted.isEmpty())
        assertTrue(h.chat.entries.isEmpty())
        h.chat.endTyping()
        h.view.starterViews.getValue(ChatStarter.BRAINSTORM).performClick()
        assertEquals("Brainstorm ideas for ", h.chat.input.text)
        assertTrue(h.actions.submitted.isEmpty())
    }

    @Test fun theMessageBoxIsTheKeyboardsOwnFieldAndATapBringsTheKeysUp() {
        val h = H()
        h.layout()
        assertEquals(h.chat.input.buffer, h.view.inputEditor.buffer)
        assertEquals("Ask anything or write a message", h.view.inputEditor.contentDescription.toString())
        h.chat.input.set("Hello")
        assertEquals("Message: Hello", h.view.inputEditor.contentDescription.toString())
        h.view.inputEditor.onTap()
        assertTrue(h.chat.typing)
    }

    @Test fun sendUsesTheMessageAndTheTranscriptShowsBubbles() {
        val h = H()
        h.chat.input.set("Write a greeting")
        assertTrue(h.view.sendView!!.isEnabled)
        h.view.sendView!!.performClick()
        assertEquals(1, h.actions.submitted.size)
        val typingLine = h.texts()
        assertTrue("the typing indicator", typingLine.contains("Ask AI is writing…"))
        assertTrue("the wait hint", typingLine.contains("This can take up to 30 seconds."))
        assertNotNull(h.view.cancelView)
        h.actions.answer(0, FakeAiActions.ok("Hello there!"))
        assertEquals(listOf("Write a greeting"), h.view.userBubbleViews.map { it.text.toString() })
        assertEquals(listOf("Hello there!"), h.view.assistantBubbleViews.map { it.text.toString() })
        assertNull(h.view.indicatorView)
        assertFalse("the starters give way to the conversation", h.texts().contains("Write a message"))
    }

    // ------------------------------------------------------------ assistant bubbles

    @Test fun anAssistantBubbleHasNamedFortyEightDpInsertAndCopy() {
        val h = H()
        h.say("Hi", "Dear Sir")
        h.layout()
        val insert = h.view.insertButtonViews.single()
        val copy = h.view.copyButtonViews.single()
        for (b in listOf(insert, copy)) {
            assertTrue("48 dp tall: ${b.measuredHeight}", b.measuredHeight >= dp(48f))
            assertTrue("48 dp wide", b.measuredWidth >= dp(48f))
            assertTrue(b.isClickable)
            assertTrue(!b.contentDescription.isNullOrBlank())
        }
        assertEquals("Insert", insert.contentDescription.toString())
        assertEquals("Copy", copy.contentDescription.toString())
        assertTrue("a user bubble has no buttons", h.view.insertButtonViews.size == 1)
    }

    @Test fun insertPutsTheReplyInTheFieldAndTheServiceIsToldCopyKeepsThePanel() {
        val h = H()
        h.say("Hi", "Dear Sir")
        h.view.insertButtonViews.single().performClick()
        assertEquals(listOf("Dear Sir"), h.editor.inserted)
        assertEquals(listOf(AiApplied.INSERTED), h.applied)
        h.view.copyButtonViews.single().performClick()
        assertEquals(listOf("Dear Sir"), h.clipboard.copies)
        assertEquals(listOf(AiApplied.INSERTED), h.applied) // copying closes nothing
        assertTrue(h.texts().contains("Copied"))
    }

    @Test fun bubblesSitOnTheRightAndLeftWithTheThemeTokens() {
        val h = H()
        h.say("Question", "Answer")
        h.layout()
        val user = h.view.userBubbleViews.single()
        val bot = h.view.assistantBubbleViews.single()
        val mid = h.view.measuredWidth / 2
        fun left(v: View): Int { var x = 0; var p: View? = v; while (p != null && p !== h.view) { x += p.left; p = p.parent as? View }; return x }
        assertTrue("the user's bubble is on the right", left(user) > mid - user.measuredWidth)
        assertTrue("the assistant's is on the left", left(bot) < mid / 2)
        assertEquals(h.view.theme.onAccent, user.currentTextColor)
        assertEquals(h.view.theme.keyText, bot.currentTextColor)
    }

    @Test fun aLongReplyScrollsInsteadOfClippingTheInputRow() {
        val h = H()
        h.say("Tell me a story", "A very long line of text that wraps. ".repeat(60))
        h.say("And another one", "Another long answer follows here. ".repeat(40))
        h.layout()
        assertEquals(panelHeight, h.view.measuredHeight)
        val input = h.view.inputRowView
        val send = h.view.sendView!!
        assertTrue("the input row is inside the panel: ${input.bottom} <= ${h.view.measuredHeight}", input.bottom <= h.view.measuredHeight)
        assertTrue(input.top > 0)
        assertTrue("Send is inside the input row", send.right <= input.width && send.height >= dp(48f))
        assertTrue("the transcript keeps room to scroll", h.view.transcriptView.height >= dp(60f))
    }

    @Test fun thePanelFitsAt360DpAndInEveryBannerState() {
        val failures = listOf(
            AiFailure.QuotaExceeded(null, canUpgrade = true, canBuyCredits = true),
            AiFailure.NotEntitled,
            AiFailure.Timeout,
            AiFailure.InputTooLong("messages"),
        )
        for (f in failures) {
            val h = H()
            h.chat.attachFieldText()
            h.chat.chooseLanguage("am")
            h.say("Hi", "Hello")
            h.say("More")
            h.actions.answer(1, FakeAiActions.fail(f))
            h.layout(360f)
            val input = h.view.inputRowView
            assertEquals(panelHeight, h.view.measuredHeight)
            assertTrue("$f: input row inside: ${input.bottom}", input.bottom <= h.view.measuredHeight && input.height >= dp(48f))
            assertTrue("$f: Send visible", h.view.sendView!!.let { it.left >= 0 && it.right <= h.view.measuredWidth })
            assertTrue("$f: the transcript is still visible", h.view.transcriptView.height >= dp(40f))
        }
    }

    @Test fun theNewestAssistantMessageIsAnnouncedAsALiveRegion() {
        val h = H()
        h.say("Hi", "First answer")
        h.say("Again", "Second answer")
        val bubbles = h.view.assistantBubbleViews
        assertEquals(2, bubbles.size)
        assertEquals(ViewCompat.ACCESSIBILITY_LIVE_REGION_NONE, ViewCompat.getAccessibilityLiveRegion(bubbles[0]))
        assertEquals(ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE, ViewCompat.getAccessibilityLiveRegion(bubbles[1]))
        assertEquals("Ask AI: Second answer", bubbles[1].contentDescription.toString())
        assertEquals("You: Hi", h.view.userBubbleViews[0].contentDescription.toString())
    }

    // ------------------------------------------------------------ banners and links

    @Test fun aPlanLockShowsSeePlansAndDisablesSend() {
        val h = H()
        h.actions.availability = AiAvailability(entitled = setOf(AiTaskKind.TRANSLATE))
        h.view.render()
        assertEquals("This feature is not part of your plan.", h.view.bannerTextView!!.text.toString())
        val link = h.view.bannerLinkViews.getValue(AiLink.UPGRADE)
        assertEquals("See plans", (link as TextView).text.toString())
        h.chat.input.set("Hi")
        h.view.render()
        assertFalse(h.view.sendView!!.isEnabled)
        link.performClick()
        assertEquals(listOf(AiLink.UPGRADE), h.links)
        assertTrue(h.actions.submitted.isEmpty())
    }

    @Test fun aQuotaRefusalOffersSeePlansAndBuyCreditsAndNoRetry() {
        val h = H()
        h.say("Hi")
        h.actions.answer(0, FakeAiActions.fail(AiFailure.QuotaExceeded(null, canUpgrade = true, canBuyCredits = true)))
        assertTrue(h.view.bannerTextView!!.text.toString().contains("Upgrade or buy credits for more."))
        assertEquals(listOf(AiLink.UPGRADE, AiLink.BUY_CREDITS), h.view.bannerLinkViews.keys.toList())
        assertEquals("See plans", (h.view.bannerLinkViews.getValue(AiLink.UPGRADE) as TextView).text.toString())
        assertEquals("Buy credits", (h.view.bannerLinkViews.getValue(AiLink.BUY_CREDITS) as TextView).text.toString())
        assertNull(h.view.retryView)
        h.view.bannerLinkViews.getValue(AiLink.BUY_CREDITS).performClick()
        h.view.bannerLinkViews.getValue(AiLink.UPGRADE).performClick()
        assertEquals(listOf(AiLink.BUY_CREDITS, AiLink.UPGRADE), h.links)
    }

    @Test fun creditsOnlyQuotaOffersJustBuyCredits() {
        val h = H()
        h.say("Hi")
        h.actions.answer(0, FakeAiActions.fail(AiFailure.QuotaExceeded(null, canUpgrade = false, canBuyCredits = true)))
        assertEquals(listOf(AiLink.BUY_CREDITS), h.view.bannerLinkViews.keys.toList())
    }

    @Test fun aNetworkErrorOffersRetryWithTheSameKey() {
        val h = H()
        h.say("Hi")
        h.actions.answer(0, FakeAiActions.fail(AiFailure.Timeout))
        assertTrue(h.view.bannerTextView!!.text.toString().isNotBlank())
        val retry = h.view.retryView!!
        assertEquals("Retry", (retry as TextView).text.toString())
        retry.performClick()
        assertEquals(2, h.actions.submitted.size)
        assertEquals(h.actions.submitted[0].request.requestId, h.actions.submitted[1].request.requestId)
    }

    @Test fun signedOutAndOfflineAreOneLineEach() {
        val h = H()
        h.actions.availability = AiAvailability(block = AiBlock.SIGNED_OUT)
        h.view.render()
        assertEquals(listOf(AiLink.SIGN_IN), h.view.bannerLinkViews.keys.toList())
        h.actions.availability = AiAvailability(block = AiBlock.OFFLINE)
        h.view.render()
        assertEquals("Offline", h.view.bannerTextView!!.text.toString())
        assertTrue(h.view.bannerLinkViews.isEmpty())
    }

    @Test fun theAllowanceShowsWhenTheServerReportedIt() {
        val h = H()
        h.actions.availability = AiAvailability(
            quota = mapOf(AiTaskKind.CHAT to AiQuotaHint(12, 20, AiPeriod.DAILY, null)),
            creditBalance = 120,
        )
        h.view.render()
        val line = h.view.bannerTextView!!.text.toString()
        assertTrue(line, line.contains("12") && line.contains("20"))
        assertTrue(line, line.contains("AI credits: 120"))
    }

    // ------------------------------------------------------------ controls

    @Test fun cancelAbortsTheRunningCall() {
        val h = H()
        h.say("Hi")
        h.view.cancelView!!.performClick()
        assertTrue(h.actions.submitted[0].cancelled)
        assertEquals("Hi", h.chat.input.text)
        assertNull(h.view.indicatorView)
    }

    @Test fun aCutOffReplyOffersContinue() {
        val h = H()
        h.say("Long story")
        h.actions.answer(0, AiOutcome.Success(AiResult("Once upon", truncated = true)))
        assertTrue(h.texts().contains("This reply was cut off."))
        val cont = h.view.continueView!!
        assertTrue(cont.measuredHeight >= 0)
        cont.performClick()
        assertEquals(2, h.actions.submitted.size)
        assertEquals("Continue", (h.actions.submitted[1].request.task as AiTask.Chat).messages.last().text)
    }

    @Test fun theFieldTextChipAttachesOnTheTapAndCanBeRemoved() {
        val h = H(selection = "my selection")
        assertEquals(0, h.editor.allTextReads)
        h.click("Use text in field")
        assertEquals("my selection", h.chat.fieldText)
        val chip = h.view.fieldChipView as TextView
        assertEquals("Field text attached", chip.text.toString())
        assertEquals("Remove attached field text", chip.contentDescription.toString())
        chip.performClick()
        assertNull(h.chat.fieldText)
        assertEquals("Use text in field", (h.view.fieldChipView as TextView).text.toString())
        // An empty field says so.
        val empty = H(fieldText = "")
        empty.click("Use text in field")
        assertTrue(empty.texts().contains("There is no text in the field to use."))
    }

    @Test fun theReplyLanguageChipOpensTheCatalogueList() {
        val h = H()
        (h.view.languageChipView as TextView).performClick()
        val t = h.texts()
        for (name in listOf("Same as I write", "English", "Amharic")) assertTrue(name, t.contains(name))
        h.click("Amharic")
        assertEquals("am", h.chat.language)
        assertTrue(h.texts().contains("Reply in: Amharic  ▾"))
        assertFalse("the list closed", h.chat.languagePickerOpen)
    }

    @Test fun newChatClearsAndIsOffWhenThereIsNothingToClear() {
        val h = H()
        assertFalse(h.view.newChatView!!.isEnabled)
        h.say("Hi", "Hello")
        assertTrue(h.view.newChatView!!.isEnabled)
        h.view.newChatView!!.performClick()
        assertTrue(h.chat.entries.isEmpty())
        assertEquals(4, h.view.starterViews.size)
    }

    @Test fun headerControlsAreNamedAndCallBack() {
        val h = H()
        h.layout()
        val named = all(h.view).filter { it.isClickable }
        assertTrue(named.isNotEmpty())
        for (v in named) assertTrue("${v.javaClass.simpleName} has a name", !v.contentDescription.isNullOrBlank() || (v is TextView && v.text.isNotBlank()))
        all(h.view).first { it.contentDescription?.toString() == "Back" }.performClick()
        all(h.view).first { it.contentDescription?.toString() == "Back to keyboard" }.performClick()
        assertEquals(1, h.backs)
        assertEquals(1, h.closed)
    }

    @Test fun closingTheChatDropsTheBubbles() {
        val h = H()
        h.say("secret question", "secret answer")
        h.ws.close()
        h.view.render()
        assertFalse(h.texts().any { it.contains("secret") })
    }
}
