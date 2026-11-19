package com.qalsmartkeyboard.ime.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The AI Assist panel's state machine (ADR 0031). */
class AiAssistControllerTest {
    private class H(
        selection: String? = null,
        clip: ClipAvailability = ClipAvailability.NONE,
        clipText: String? = null,
        keyboard: String = "am",
        translateTo: String = "en",
    ) {
        val actions = FakeAiActions()
        val editor = FakeAiEditor(selection)
        val clipboard = FakeClipboard(clip, clipText)
        var changes = 0
        private var n = 0
        val c = AiAssistController(
            actions, editor, clipboard, post = { it() },
            keyboardLanguage = { keyboard }, translateDefault = { translateTo },
            newKey = { "key-${++n}" }, onChanged = { changes++ },
        )
        fun sent() = actions.submitted.last().request
    }

    // ------------------------------------------------------------ source priority and the clipboard

    @Test fun theSelectionIsTheTextWhenThereIsOne() {
        val h = H(selection = "ሰላም ነው?", clip = ClipAvailability.TEXT, clipText = "copied")
        h.c.open()
        assertEquals(AiSourceKind.SELECTION, h.c.sourceKind)
        assertEquals("ሰላም ነው?", h.c.source.text)
        assertEquals("the clipboard content is never read on open", 0, h.clipboard.contentReads)
        assertTrue(h.c.canReplaceSelection)
    }

    @Test fun withoutASelectionThePasteChipIsOfferedOnlyForTextAndReadOnlyOnTap() {
        val h = H(clip = ClipAvailability.TEXT, clipText = "Meeting moved to 3pm")
        h.c.open()
        assertEquals(AiSourceKind.NONE, h.c.sourceKind)
        assertEquals(ClipAvailability.TEXT, h.c.clip)
        assertEquals(0, h.clipboard.contentReads)
        h.c.pasteFromClipboard()
        assertEquals(1, h.clipboard.contentReads)
        assertEquals(AiSourceKind.CLIPBOARD, h.c.sourceKind)
        assertEquals("Meeting moved to 3pm", h.c.source.text)
        assertFalse(h.c.canReplaceSelection)
    }

    @Test fun aSensitiveClipIsNeverOfferedOrRead() {
        val h = H(clip = ClipAvailability.SENSITIVE, clipText = "1234")
        h.c.open()
        assertEquals(ClipAvailability.SENSITIVE, h.c.clip)
        h.c.pasteFromClipboard() // not offered: a tap cannot happen, and the call does nothing anyway
        assertEquals(0, h.clipboard.contentReads)
        assertTrue(h.c.source.isBlank)
    }

    @Test fun anEmptiedClipboardSaysSoInsteadOfPasting() {
        val h = H(clip = ClipAvailability.TEXT, clipText = null)
        h.c.open()
        h.c.pasteFromClipboard()
        assertEquals(AiNotice.CLIPBOARD_EMPTY, h.c.notice)
        assertEquals(AiSourceKind.NONE, h.c.sourceKind)
    }

    @Test fun typingIntoTheTextBoxGoesThroughItsSink() {
        val h = H()
        h.c.open()
        h.c.startTyping(AiAssistField.SOURCE)
        assertEquals(h.c.source, h.c.typingField)
        h.c.source.sink.apply(com.qalsmartkeyboard.ime.ComposerUpdate(commit = "Hello"))
        h.c.endTyping()
        assertEquals(AiSourceKind.TYPED, h.c.sourceKind)
        assertEquals("Hello", h.c.source.text)
        assertNull(h.c.typingField)
    }

    @Test fun clearingTheTextBringsTheChoicesBack() {
        val h = H(selection = "x")
        h.c.open()
        h.c.clearSource()
        assertEquals(AiSourceKind.NONE, h.c.sourceKind)
        assertTrue(h.c.source.isBlank)
        assertFalse(h.c.canReplaceSelection)
    }

    // ------------------------------------------------------------ action -> request, output language rules

    @Test fun everyActionMapsToItsRequestWithTheDefaultOutputLanguage() {
        val expected = mapOf(
            AiAssistAction.TRANSLATE to AiTask.Translate(AiLanguages.plain("en")),
            AiAssistAction.EXPLAIN to AiTask.Explain("am"),
            AiAssistAction.GRAMMAR to AiTask.Grammar,
            AiAssistAction.REWRITE to AiTask.Rewrite(null, null),
            AiAssistAction.PROFESSIONAL to AiTask.Rewrite(AiTone.PROFESSIONAL, null),
            AiAssistAction.SIMPLIFY to AiTask.Simplify(null),
            AiAssistAction.REPLY to AiTask.Reply("am", "", 3, 0),
            AiAssistAction.SUMMARIZE to AiTask.Summarize("am", AiLength.MEDIUM),
        )
        for ((action, task) in expected) {
            val h = H(selection = "Some text to work on")
            h.c.open()
            h.c.selectAction(action)
            h.c.run()
            assertEquals(action.name, task, h.sent().task)
            assertEquals("Some text to work on", h.sent().text)
            assertTrue(h.c.step is AiAssistStep.Working)
        }
    }

    @Test fun theUserCanChangeTheOutputLanguageExceptForGrammar() {
        val h = H(selection = "text")
        h.c.open()
        h.c.selectAction(AiAssistAction.REWRITE)
        assertEquals(listOf(null, "am", "ti", "om", "en", "ar"), h.c.outputChoices())
        h.c.chooseOutput("ti")
        h.c.run()
        assertEquals(AiTask.Rewrite(null, "ti"), h.sent().task)

        h.c.backToEdit()
        h.c.selectAction(AiAssistAction.GRAMMAR)
        assertFalse(h.c.canChooseOutput())
        h.c.chooseOutput("en")
        assertNull(h.c.outputLanguage())
        h.c.run()
        assertEquals(AiTask.Grammar, h.sent().task)
        // The choice made for Rewrite stays with Rewrite.
        assertEquals("ti", h.c.outputLanguage(AiAssistAction.REWRITE))
        assertEquals("am", h.c.outputLanguage(AiAssistAction.SUMMARIZE))
    }

    @Test fun replySendsTheUsersNote() {
        val h = H(selection = "Can we meet on Friday?")
        h.c.open()
        h.c.selectAction(AiAssistAction.REPLY)
        h.c.note.set("ok but at 3pm")
        h.c.chooseOutput("en")
        h.c.run()
        assertEquals(AiTask.Reply("en", "ok but at 3pm", 3, 0), h.sent().task)
        assertFalse("the note never appears in toString", h.sent().task.toString().contains("3pm"))
    }

    @Test fun nothingIsSentWithoutTextOrWithTooMuch() {
        val h = H()
        h.c.open()
        h.c.run()
        assertEquals(AiAssistProblem.NO_TEXT, h.c.problem)
        h.c.source.set("x".repeat(AiAssistController.MAX_TEXT + 1))
        assertEquals(AiAssistProblem.TEXT_TOO_LONG, h.c.problem)
        h.c.run()
        assertTrue(h.actions.submitted.isEmpty())
    }

    // ------------------------------------------------------------ result, regenerate, retry, cancel

    @Test fun replySuggestionsArePickedAndRegenerateAsksForTheNextVariationWithANewKey() {
        val h = H(selection = "Are you coming?")
        h.c.open()
        h.c.selectAction(AiAssistAction.REPLY)
        h.c.run()
        h.actions.answer(0, AiOutcome.Success(AiResult("Yes", suggestions = listOf("Yes", "No", "Maybe"))))
        val r = h.c.step as AiAssistStep.Result
        assertEquals("Yes", r.shownText)
        h.c.selectSuggestion(2)
        assertEquals("Maybe", (h.c.step as AiAssistStep.Result).shownText)
        h.c.regenerate()
        assertEquals(2, h.actions.submitted.size)
        assertEquals(1, (h.sent().task as AiTask.Reply).variation)
        assertNotEquals(h.actions.submitted[0].request.requestId, h.sent().requestId)
    }

    @Test fun regenerateOfARewriteAsksForTheNextVariationWithANewKey() {
        val h = H(selection = "text")
        h.c.open()
        h.c.selectAction(AiAssistAction.SIMPLIFY)
        h.c.run()
        h.actions.answer(0, FakeAiActions.ok("simple"))
        h.c.regenerate()
        assertEquals(AiTask.Simplify(null, variation = 1), h.sent().task)
        assertEquals("key-2", h.sent().requestId)
        h.actions.answer(1, FakeAiActions.ok("simpler"))
        h.c.regenerate()
        assertEquals(2, (h.sent().task as AiTask.Simplify).variation)
    }

    @Test fun translateAndExplainOfferNoRegenerate() {
        val h = H(selection = "text")
        h.c.open()
        h.c.run() // Translate
        h.actions.answer(0, FakeAiActions.ok("ጽሑፍ"))
        h.c.regenerate()
        assertEquals(1, h.actions.submitted.size)
    }

    @Test fun cancelAbortsTheCallAndKeepsTheInputs() {
        val h = H(selection = "keep me")
        h.c.open()
        h.c.selectAction(AiAssistAction.SUMMARIZE)
        h.c.run()
        h.c.cancel()
        assertTrue(h.actions.submitted.single().cancelled)
        assertEquals(AiAssistStep.Compose, h.c.step)
        assertEquals("keep me", h.c.source.text)
        assertEquals(AiAssistAction.SUMMARIZE, h.c.action)
        // A late answer of the cancelled call is dropped.
        h.actions.answer(0, FakeAiActions.ok("late"))
        assertEquals(AiAssistStep.Compose, h.c.step)
    }

    @Test fun aNetworkErrorRetriesWithTheSameKeyAndAGuardrailErrorWithANewOne() {
        val h = H(selection = "text")
        h.c.open()
        h.c.run()
        h.actions.answer(0, FakeAiActions.fail(AiFailure.Timeout))
        h.c.retry()
        assertEquals("key-1", h.sent().requestId)
        h.actions.answer(1, FakeAiActions.fail(AiFailure.GuardrailFailed))
        h.c.retry()
        assertEquals("key-2", h.sent().requestId)
    }

    @Test fun everyFailureEndsInTheFailedStep() {
        val failures = listOf(
            AiFailure.Offline, AiFailure.Timeout, AiFailure.Busy, AiFailure.QuotaExceeded(null, true), AiFailure.RateLimited(5),
            AiFailure.NotEntitled, AiFailure.OnDeviceOnly, AiFailure.ConsentRequired(null), AiFailure.GuardrailFailed,
            AiFailure.ContentRejected, AiFailure.LanguageUnsupported, AiFailure.SignInRequired, AiFailure.AccountBlocked,
            AiFailure.UpdateRequired, AiFailure.ServiceUnavailable, AiFailure.InputTooLong("text"), AiFailure.Invalid("outputLanguage"),
            AiFailure.Other,
        )
        for (f in failures) {
            val h = H(selection = "text")
            h.c.open()
            h.c.run()
            h.actions.answer(0, FakeAiActions.fail(f))
            assertEquals(f, (h.c.step as AiAssistStep.Failed).failure)
        }
    }

    // ------------------------------------------------------------ plan locks and blocks

    @Test fun aLockedActionShowsTheUpgradeLinkInsteadOfSending() {
        val h = H(selection = "text")
        h.actions.availability = AiAvailability(entitled = setOf(AiTaskKind.TRANSLATE, AiTaskKind.EXPLAIN, AiTaskKind.GRAMMAR, AiTaskKind.REWRITE))
        h.c.open()
        assertTrue(h.c.isLocked(AiAssistAction.REPLY))
        assertTrue(h.c.isLocked(AiAssistAction.SUMMARIZE))
        assertTrue(h.c.isLocked(AiAssistAction.PROFESSIONAL))
        assertFalse(h.c.isLocked(AiAssistAction.EXPLAIN))
        h.c.selectAction(AiAssistAction.SUMMARIZE)
        h.c.run()
        assertTrue(h.actions.submitted.isEmpty())
        val f = (h.c.step as AiAssistStep.Failed).failure
        assertEquals(AiFailure.NotEntitled, f)
        assertEquals(AiLink.UPGRADE, f.link)
    }

    @Test fun unknownPlanStateLocksNothingButAServerRefusalLocksForTheSession() {
        val h = H(selection = "text")
        h.c.open()
        assertFalse(h.c.isLocked(AiAssistAction.REPLY))
        h.c.selectAction(AiAssistAction.REPLY)
        h.c.run()
        h.actions.answer(0, FakeAiActions.fail(AiFailure.NotEntitled))
        assertTrue(h.c.isLocked(AiAssistAction.REPLY))
    }

    @Test fun aServerRefusalLockIsForgottenWhenTheAccountChanges() {
        // ADR 0035: signing in to another account (in the app or the keyboard) must not keep the old account's locks.
        val h = H(selection = "text")
        h.c.open()
        h.c.selectAction(AiAssistAction.REPLY)
        h.c.run()
        h.actions.answer(0, FakeAiActions.fail(AiFailure.NotEntitled))
        assertTrue(h.c.isLocked(AiAssistAction.REPLY))
        h.actions.availability = AiAvailability(accountGeneration = 1)
        assertFalse(h.c.isLocked(AiAssistAction.REPLY))
    }

    @Test fun offlineSendsNothing() {
        val h = H(selection = "text")
        h.actions.availability = AiAvailability(block = AiBlock.OFFLINE)
        h.c.open()
        h.c.run()
        assertTrue(h.actions.submitted.isEmpty())
        assertEquals(AiFailure.Offline, (h.c.step as AiAssistStep.Failed).failure)
    }

    // ------------------------------------------------------------ applying

    @Test fun replaceSelectionOrInsertAfter() {
        val h = H(selection = "teh cat")
        h.c.open()
        h.c.selectAction(AiAssistAction.GRAMMAR)
        h.c.run()
        h.actions.answer(0, FakeAiActions.ok("the cat"))
        assertEquals(AiApplied.REPLACED, h.c.insert(replace = true))
        assertEquals(listOf("teh cat" to "the cat"), h.editor.replaced)

        val h2 = H(selection = "teh cat")
        h2.c.open()
        h2.c.run()
        h2.actions.answer(0, FakeAiActions.ok("ድመቷ"))
        assertEquals(AiApplied.INSERTED, h2.c.insert(replace = false))
        assertEquals(listOf("ድመቷ"), h2.editor.inserted)
    }

    @Test fun anEditedSelectionIsNoLongerReplaced() {
        val h = H(selection = "teh cat")
        h.c.open()
        h.c.source.set("teh cat sat")
        assertFalse(h.c.canReplaceSelection)
    }

    @Test fun copyIsAPlainCopyOfTheShownResult() {
        val h = H(selection = "text")
        h.c.open()
        h.c.run()
        h.actions.answer(0, FakeAiActions.ok("result"))
        assertTrue(h.c.copy())
        assertEquals(listOf("result"), h.clipboard.copies)
        assertEquals(AiNotice.COPIED, h.c.notice)
    }

    @Test fun closingDropsEverythingAndCancels() {
        val h = H(selection = "secret")
        h.c.open()
        h.c.run()
        h.c.close()
        assertTrue(h.actions.submitted.single().cancelled)
        assertTrue(h.c.source.isBlank)
        assertEquals(AiSourceKind.NONE, h.c.sourceKind)
    }
}
