package com.qalsmartkeyboard.ime.ai

import android.text.InputType as T
import android.view.inputmethod.EditorInfo as E
import com.qalsmartkeyboard.ime.FakeEditor
import com.qalsmartkeyboard.ime.InputConnectionAdapter
import com.qalsmartkeyboard.ime.InputProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AiToolbarControllerTest {
    private class H(initial: String = "", val actions: FakeAiActions = FakeAiActions()) {
        val ed = FakeEditor(initial)
        val adapter = InputConnectionAdapter { ed }.also { it.startSession(false, initial.length, initial.length) }
        var changes = 0
        var now = 1_000_000L
        val clipboard = ArrayList<String>()
        private var n = 0
        val c = AiToolbarController(
            actions = actions,
            editor = EditorAiAccess(adapter) { adapter.finishComposition() },
            post = { it() },
            copyToClipboard = { clipboard += it },
            newKey = { "key-${++n}" },
            onChanged = { changes++ },
            clock = { now },
        ).also { it.onFieldStarted(InputProfile.DEFAULT, "com.example.chat") }

        val panel get() = c.panel
        fun review() = c.panel as AiPanel.Review
        fun failed() = (c.panel as AiPanel.Failed).failure
    }

    private val mum = AiDestination("p1", "Mum", "am", isProfile = true, formality = "HONORIFIC")

    // ------------------------------------------------------------ flow

    @Test fun translateSentenceThenReplace() {
        val h = H("Hello. How are you")
        h.c.chooseDestination(mum)
        h.c.onTask(AiTaskKind.TRANSLATE)
        assertTrue(h.panel is AiPanel.Working)
        val sent = h.actions.submitted.single().request
        assertEquals("How are you", sent.text)
        assertEquals(AiTask.Translate(mum), sent.task)
        assertEquals("key-1", sent.requestId)
        // Typing continues while the request is in flight: nothing blocks, no second call.
        h.ed.commitText("?")
        assertEquals(1, h.actions.submitted.size)

        h.actions.answer(0, FakeAiActions.ok("እንዴት ነህ"))
        assertEquals("How are you", h.review().original)
        assertEquals("እንዴት ነህ", h.review().shownText)
        assertEquals("Hello. How are you?", h.ed.text.toString()) // nothing inserted yet

        // The text changed after the request ("?" typed): Replace falls back to inserting at the cursor.
        assertEquals(AiApplied.INSERTED_ORIGINAL_CHANGED, h.c.replace())
        assertEquals("Hello. How are you? እንዴት ነህ", h.ed.text.toString())
        assertTrue(h.panel is AiPanel.Closed)
    }

    @Test fun replaceSentenceKeepsTrailingWhitespace() {
        val h = H("One. Two three ")
        h.c.onTask(AiTaskKind.GRAMMAR)
        h.actions.answer(0, FakeAiActions.ok("Two three."))
        assertEquals(AiApplied.REPLACED, h.c.replace())
        assertEquals("One. Two three. ", h.ed.text.toString())
        assertEquals(16, h.ed.selStart)
    }

    @Test fun selectionWinsOverTheSentenceAndIsReplaced() {
        val h = H("Keep this. Fix me please. After")
        h.ed.selStart = 11
        h.ed.selEnd = 24
        h.c.onTask(AiTaskKind.REWRITE)
        assertEquals("Fix me please", h.actions.submitted.single().request.text)
        h.actions.answer(0, FakeAiActions.ok("Please fix me"))
        assertEquals(AiApplied.REPLACED, h.c.replace())
        assertEquals("Keep this. Please fix me. After", h.ed.text.toString())
    }

    @Test fun insertKeepsTheOriginalAndAddsASpace() {
        val h = H("Good morning")
        h.c.onTask(AiTaskKind.TRANSLATE)
        h.actions.answer(0, FakeAiActions.ok("እንደምን አደርክ"))
        assertEquals(AiApplied.INSERTED, h.c.insert())
        assertEquals("Good morning እንደምን አደርክ", h.ed.text.toString())
    }

    @Test fun insertAfterASelectionCollapsesItFirst() {
        val h = H("abc def ghi")
        h.ed.selStart = 4
        h.ed.selEnd = 7
        h.adapter.onSelectionUpdate(4, 7, -1, -1)
        h.c.onTask(AiTaskKind.GRAMMAR)
        h.actions.answer(0, FakeAiActions.ok("DEF"))
        h.c.insert()
        assertEquals("abc def DEF ghi", h.ed.text.toString())
    }

    @Test fun alternativesCanBePickedAndCopied() {
        val h = H("Send it")
        h.c.onTask(AiTaskKind.TRANSLATE)
        h.actions.answer(0, FakeAiActions.ok("main", AiAlternative("to a woman", "alt-f")))
        h.c.selectAlternative(0)
        assertEquals("alt-f", h.review().shownText)
        h.c.copy()
        assertEquals(listOf("alt-f"), h.clipboard)
        h.c.replace()
        assertEquals("alt-f", h.ed.text.toString())
    }

    @Test fun nothingIsInsertedWithoutTheUser() {
        val h = H("Hello")
        h.c.onTask(AiTaskKind.GRAMMAR)
        h.actions.answer(0, FakeAiActions.ok("Hello!"))
        assertEquals("Hello", h.ed.text.toString())
        assertFalse(h.ed.calls.any { it.startsWith("commitText") || it.startsWith("deleteSurrounding") })
        assertTrue(h.ed.actions.isEmpty() && h.ed.keys.isEmpty()) // nothing pressed Send/Enter
    }

    @Test fun toneChipTurnsRewriteIntoToneAdjust() {
        val h = H("send the report")
        h.c.chooseTone(AiTone.POLITE)
        h.c.onTask(AiTaskKind.REWRITE)
        assertEquals(AiTask.Rewrite(AiTone.POLITE), h.actions.submitted.single().request.task)
    }

    @Test fun theOpenCompositionIsPartOfTheTextBeforeTheCursor() {
        val h = H("")
        h.ed.setComposingText("selam")
        h.adapter.onSelectionUpdate(5, 5, 0, 5)
        h.c.onTask(AiTaskKind.TRANSLATE)
        assertEquals("selam", h.actions.submitted.single().request.text)
    }

    // ------------------------------------------------------------ in flight, cancel, retry

    @Test fun aSecondTapWhileInFlightIsIgnored() {
        val h = H("Hello")
        h.c.onTask(AiTaskKind.GRAMMAR)
        h.c.onTask(AiTaskKind.TRANSLATE)
        h.c.onTask(AiTaskKind.REWRITE)
        assertEquals(1, h.actions.submitted.size)
        assertTrue(h.c.inFlight)
    }

    @Test fun cancelAbortsTheCallAndDropsTheLateAnswer() {
        val h = H("Hello")
        h.c.onTask(AiTaskKind.GRAMMAR)
        h.c.cancel()
        assertTrue(h.actions.submitted.single().cancelled)
        assertTrue(h.panel is AiPanel.Closed)
        h.actions.answer(0, FakeAiActions.ok("late"))
        assertTrue(h.panel is AiPanel.Closed)
        assertFalse(h.c.inFlight)
    }

    @Test fun retryAfterANetworkErrorReusesTheKeyAndText() {
        val h = H("Hello")
        h.c.onTask(AiTaskKind.TRANSLATE)
        h.actions.answer(0, FakeAiActions.fail(AiFailure.Timeout))
        assertEquals(AiFailure.Timeout, h.failed())
        h.ed.commitText(" changed") // the editor moved on; the retry still sends the original
        h.c.retry()
        val a = h.actions.submitted[0].request
        val b = h.actions.submitted[1].request
        assertEquals(a.requestId, b.requestId)
        assertEquals("Hello", b.text)
        h.actions.answer(1, FakeAiActions.ok("ሰላም"))
        assertEquals("ሰላም", h.review().shownText)
    }

    @Test fun retryAfterOtherFailuresAndAnotherResultUseANewKey() {
        val h = H("Hello")
        h.c.onTask(AiTaskKind.GRAMMAR)
        h.actions.answer(0, FakeAiActions.fail(AiFailure.GuardrailFailed))
        h.c.retry()
        assertNotEquals(h.actions.submitted[0].request.requestId, h.actions.submitted[1].request.requestId)
        h.actions.answer(1, FakeAiActions.ok("Hello."))
        h.c.retry() // "another result" from the review
        assertNotEquals(h.actions.submitted[1].request.requestId, h.actions.submitted[2].request.requestId)
    }

    @Test fun busyAndServiceUnavailableRetrySameKey() {
        for (f in listOf(AiFailure.Busy, AiFailure.ServiceUnavailable, AiFailure.Offline)) {
            val h = H("Hello")
            h.c.onTask(AiTaskKind.GRAMMAR)
            h.actions.answer(0, FakeAiActions.fail(f))
            h.c.retry()
            assertEquals(h.actions.submitted[0].request.requestId, h.actions.submitted[1].request.requestId)
        }
    }

    @Test fun anAnswerThatArrivesSynchronouslyIsHandled() {
        val a = FakeAiActions().apply { autoAnswer = FakeAiActions.ok("done") }
        val h = H("Hello", a)
        h.c.onTask(AiTaskKind.GRAMMAR)
        assertEquals("done", h.review().shownText)
        assertFalse(h.c.inFlight)
    }

    // ------------------------------------------------------------ explicit action only, field rules

    @Test fun anAnswerForAnEarlierFieldNeverLandsInTheNextOne() {
        val h = H("Hello")
        h.c.onTask(AiTaskKind.GRAMMAR)
        h.c.onFieldStarted(InputProfile.DEFAULT, "other.app")
        assertTrue(h.actions.submitted.single().cancelled)
        h.actions.answer(0, FakeAiActions.ok("late"))
        assertTrue(h.panel is AiPanel.Closed)
    }

    @Test fun hidingTheKeyboardCancelsAndForgets() {
        val h = H("Hello")
        h.c.onTask(AiTaskKind.GRAMMAR)
        h.c.onKeyboardHidden()
        assertTrue(h.actions.submitted.single().cancelled)
        assertTrue(h.panel is AiPanel.Closed)
    }

    @Test fun toolbarIsHiddenInSensitiveFields() {
        fun visible(type: Int, options: Int = 0) =
            H().also { it.c.onFieldStarted(InputProfile.from(type, options, false, 0), "x") }.c.visible
        assertTrue(visible(T.TYPE_CLASS_TEXT))
        assertTrue(visible(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_FLAG_MULTI_LINE))
        assertFalse(visible(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD))
        assertFalse(visible(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertFalse(visible(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertFalse(visible(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_URI))
        assertFalse(visible(T.TYPE_CLASS_NUMBER))
        assertFalse(visible(T.TYPE_CLASS_NUMBER or T.TYPE_NUMBER_VARIATION_PASSWORD))
        assertFalse(visible(T.TYPE_CLASS_PHONE))
        assertFalse(visible(T.TYPE_CLASS_DATETIME))
        assertFalse(visible(T.TYPE_NULL))
        assertFalse(visible(T.TYPE_CLASS_TEXT, E.IME_FLAG_NO_PERSONALIZED_LEARNING)) // incognito
    }

    @Test fun aHiddenToolbarNeverCallsAnything() {
        val h = H("secret")
        h.c.onFieldStarted(InputProfile.from(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD, 0, false, 0), "x")
        h.c.onTask(AiTaskKind.TRANSLATE)
        h.c.openPicker(AiPicker.TONE)
        assertTrue(h.actions.submitted.isEmpty())
        assertEquals(0, h.actions.refreshCount)
        assertFalse(h.ed.calls.any { it == "getTextBeforeCursor" || it == "getSelectedText" })
    }

    @Test fun nothingReadsTheEditorUntilATap() {
        val h = H("Some text")
        h.c.onFieldStarted(InputProfile.DEFAULT, "x")
        h.c.openPicker(AiPicker.DESTINATION)
        h.c.chooseDestination(mum)
        assertFalse(h.ed.calls.any { it == "getTextBeforeCursor" || it == "getSelectedText" })
        assertTrue(h.actions.submitted.isEmpty())
    }

    @Test fun destinationDefaultsFromTheAppRule() {
        val a = FakeAiActions().apply { defaultFor = { pkg -> if (pkg == "com.example.chat") AiDestination("p1", "Mum", "am", true) else AiDestination("lang:en", "English", "en") } }
        val h = H("x", a)
        assertEquals("Mum", h.c.destination.label)
        h.c.onFieldStarted(InputProfile.DEFAULT, "com.other")
        assertEquals("English", h.c.destination.label)
    }

    // ------------------------------------------------------------ graceful states

    @Test fun offlineSignedOutAndUpdateStatesMakeNoCall() {
        val cases = mapOf(
            AiBlock.OFFLINE to AiFailure.Offline,
            AiBlock.SIGNED_OUT to AiFailure.SignInRequired,
            AiBlock.UPDATE_REQUIRED to AiFailure.UpdateRequired,
        )
        for ((block, failure) in cases) {
            val a = FakeAiActions().apply { availability = AiAvailability(block = block) }
            val h = H("Hello", a)
            h.c.onTask(AiTaskKind.TRANSLATE)
            assertEquals(failure, h.failed())
            assertTrue(a.submitted.isEmpty())
            assertFalse(h.ed.calls.any { it == "getTextBeforeCursor" }) // nothing even read
        }
    }

    @Test fun goingOnlineAgainLetsRetryThrough() {
        val a = FakeAiActions().apply { availability = AiAvailability(block = AiBlock.OFFLINE) }
        val h = H("Hello", a)
        h.c.onTask(AiTaskKind.GRAMMAR)
        a.availability = AiAvailability()
        h.c.retry()
        assertEquals(1, a.submitted.size)
    }

    @Test fun everyServerFailureIsShownAndKeepsTheEditorUntouched() {
        val failures = listOf(
            AiFailure.QuotaExceeded(1_800_000_000_000, true), AiFailure.RateLimited(30), AiFailure.NotEntitled, AiFailure.OnDeviceOnly,
            AiFailure.ConsentRequired("AI_PROCESSING"), AiFailure.GuardrailFailed, AiFailure.ContentRejected, AiFailure.LanguageUnsupported,
            AiFailure.SignInRequired, AiFailure.AccountBlocked, AiFailure.UpdateRequired, AiFailure.ServiceUnavailable, AiFailure.Other,
            AiFailure.Busy, AiFailure.Timeout, AiFailure.Offline,
        )
        for (f in failures) {
            val h = H("Hello")
            h.c.onTask(AiTaskKind.TRANSLATE)
            h.actions.answer(0, FakeAiActions.fail(f))
            assertSame(f, h.failed())
            assertEquals("Hello", h.ed.text.toString())
        }
    }

    @Test fun failuresPointToTheRightPlace() {
        assertEquals(AiLink.UPGRADE, AiFailure.NotEntitled.link)
        assertEquals(AiLink.UPGRADE, AiFailure.QuotaExceeded(null, true).link)
        assertNull(AiFailure.QuotaExceeded(null, false).link)
        assertEquals(AiLink.AI_SETTINGS, AiFailure.OnDeviceOnly.link)
        assertEquals(AiLink.AI_SETTINGS, AiFailure.ConsentRequired(null).link)
        assertEquals(AiLink.SIGN_IN, AiFailure.SignInRequired.link)
        assertEquals(AiLink.UPDATE_APP, AiFailure.UpdateRequired.link)
        assertNull(AiFailure.GuardrailFailed.link)
    }

    @Test fun emptyAndOversizedTargetsAreLocalFailures() {
        val h = H("")
        h.c.onTask(AiTaskKind.GRAMMAR)
        assertEquals(AiFailure.NothingToProcess, h.failed())
        val big = H("x".repeat(AiTextTarget.MAX_CHARS + 1))
        big.ed.selStart = 0
        big.ed.selEnd = AiTextTarget.MAX_CHARS + 1
        big.c.onTask(AiTaskKind.GRAMMAR)
        assertEquals(AiFailure.TooLong, big.failed())
        assertTrue(h.actions.submitted.isEmpty() && big.actions.submitted.isEmpty())
    }

    @Test fun availabilityListenerRepaints() {
        val h = H("x")
        val before = h.changes
        h.actions.fireAvailabilityChanged()
        assertEquals(before + 1, h.changes)
    }

    @Test fun showingAndHidingTheKeyboardNeverRefreshes() {
        val h = H("secret words")
        repeat(25) {
            h.c.onFieldStarted(InputProfile.DEFAULT, "com.example.chat") // the keyboard appears in a field
            h.c.onKeyboardHidden()
        }
        assertEquals(0, h.actions.refreshCount)
        assertTrue(h.actions.submitted.isEmpty())
    }

    @Test fun openingTheToolbarRefreshesOncePerCacheWindow() {
        val h = H("secret words")
        h.c.openPicker(AiPicker.DESTINATION)
        h.c.openPicker(AiPicker.TONE)
        h.c.onTask(AiTaskKind.GRAMMAR)
        h.c.onFieldStarted(InputProfile.DEFAULT, "com.other") // another field: still inside the window
        h.c.openPicker(AiPicker.DESTINATION)
        assertEquals(1, h.actions.refreshCount)
        h.now += AiToolbarController.REFRESH_WINDOW_MS
        h.c.openPicker(AiPicker.TONE)
        assertEquals(2, h.actions.refreshCount)
    }

    @Test fun choosingADestinationIsRememberedForThatApp() {
        val h = H("x")
        h.c.openPicker(AiPicker.DESTINATION)
        h.c.chooseDestination(mum)
        assertEquals(listOf<Pair<String?, AiDestination>>("com.example.chat" to mum), h.actions.remembered)
        h.c.onFieldStarted(InputProfile.DEFAULT, "com.other")
        h.c.chooseDestination(mum)
        assertEquals("com.other", h.actions.remembered.last().first)
    }

    @Test fun closingThePickerChoosesNothingAndSendsNothing() {
        val h = H("Hello")
        h.c.openPicker(AiPicker.TONE)
        val before = h.changes
        h.c.closePicker()
        assertEquals(AiPicker.NONE, h.c.picker)
        assertNull(h.c.tone)
        assertEquals(before + 1, h.changes)
        h.c.closePicker() // already closed: no repaint
        assertEquals(before + 1, h.changes)
        assertTrue(h.actions.submitted.isEmpty())
    }
}
