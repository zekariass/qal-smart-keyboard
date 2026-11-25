package com.qalsmartkeyboard.ime.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Ask AI chat's state machine (plan T8.5, ADR 0048): explicit sends, the window of turns, retry with the same key,
 * Continue, cancel, New chat, the field text only on a tap, everything dropped on close, and the plan locks.
 */
class ChatControllerTest {
    /** An editor that counts every read of the host field. */
    private class CountingEditor(selection: String? = null, fieldText: String = "") : AiEditor {
        val inner = FakeAiEditor(selection, fieldText)
        var reads = 0
        override fun commitComposition() = inner.commitComposition()
        override fun selectedText(): String? { reads++; return inner.selectedText() }
        override fun textBeforeCursor(n: Int): String? { reads++; return inner.textBeforeCursor(n) }
        override fun replaceSelection(expected: String, replacement: String) = inner.replaceSelection(expected, replacement)
        override fun replaceBeforeCursor(expected: String, replacement: String) = inner.replaceBeforeCursor(expected, replacement)
        override fun insertAtCursor(text: String) = inner.insertAtCursor(text)
        override fun allText(max: Int): String? { reads++; return inner.allText(max) }
    }

    private class Rig(allowed: Boolean = true, selection: String? = null, fieldText: String = "") {
        val actions = FakeAiActions()
        val editor = CountingEditor(selection, fieldText)
        val clip = FakeClipboard()
        var changes = 0
        var keys = 0
        val chat = ChatController(
            actions, editor, clip, { it() }, { "en" }, fieldAllowed = { allowed }, continueText = { "Continue" },
            newKey = { "key-${++keys}" }, refresh = { actions.refresh() }, onChanged = { changes++ },
        )

        fun open(): Rig { chat.open(); return this }
        fun say(text: String) { chat.input.set(text); chat.send() }
        val task get() = actions.submitted.last().request.task as AiTask.Chat
        val lastRequest get() = actions.submitted.last().request
        fun reply(index: Int = actions.submitted.lastIndex, text: String = "Reply $index") = actions.answer(index, FakeAiActions.ok(text))
    }

    private fun turns(vararg t: Pair<ChatRole, String>) = t.map { ChatTurn(it.first, it.second) }
    private val u = ChatRole.USER
    private val a = ChatRole.ASSISTANT

    // ------------------------------------------------------------ open, send, reply

    @Test fun openingSendsAndReadsNothing() {
        val r = Rig(selection = "secret", fieldText = "secret text").open()
        assertTrue(r.chat.isOpen)
        assertTrue(r.actions.submitted.isEmpty())
        assertEquals("the host field is not read on open", 0, r.editor.reads)
        assertNull(r.chat.fieldText)
        assertEquals(1, r.actions.refreshCount) // the plan state is refreshed (no text)
    }

    @Test fun aSendPutsTheMessageInTheTranscriptAndAsksTheServer() {
        val r = Rig().open()
        r.say("  Write a greeting for my boss  ")
        assertEquals(listOf("Write a greeting for my boss"), r.chat.entries.map { it.text })
        assertTrue(r.chat.input.isBlank) // the box is empty for the next message
        assertTrue(r.chat.step is ChatStep.Sending)
        assertTrue(r.chat.busy)
        assertEquals(1, r.actions.submitted.size)
        assertEquals("key-1", r.lastRequest.requestId)
        assertEquals(turns(u to "Write a greeting for my boss"), r.task.messages)
        assertNull(r.task.language)
        assertNull(r.task.fieldText)
        r.reply(text = "Dear boss, ...")
        assertEquals(listOf(u, a), r.chat.entries.map { it.role })
        assertEquals("Dear boss, ...", r.chat.entries.last().text)
        assertEquals(ChatStep.Idle, r.chat.step)
        assertFalse(r.chat.busy)
    }

    @Test fun everyTurnResendsTheWindowAndGetsANewKey() {
        val r = Rig().open()
        r.say("Hello")
        r.reply(text = "Hi, how can I help?")
        r.say("Make it shorter")
        assertEquals(turns(u to "Hello", a to "Hi, how can I help?", u to "Make it shorter"), r.task.messages)
        assertEquals("key-2", r.lastRequest.requestId)
        assertNotEquals(r.actions.submitted[0].request.requestId, r.lastRequest.requestId)
    }

    @Test fun anEmptyOrTooLongMessageIsNotSent() {
        val r = Rig().open()
        r.chat.input.set("   ")
        assertFalse(r.chat.canSend)
        r.chat.send()
        assertTrue(r.actions.submitted.isEmpty())
        assertEquals(AiFailure.NothingToProcess, (r.chat.step as ChatStep.Failed).failure)
        r.chat.input.set("x".repeat(ChatWindow.MAX_TURN_CHARS + 1))
        assertFalse(r.chat.canSend)
        r.chat.send()
        assertTrue(r.actions.submitted.isEmpty())
        assertEquals(AiFailure.TooLong, (r.chat.step as ChatStep.Failed).failure)
        assertTrue("nothing was added to the transcript", r.chat.entries.isEmpty())
    }

    @Test fun onlyOneRequestRunsAtATime() {
        val r = Rig().open()
        r.say("one")
        r.chat.input.set("two")
        assertFalse(r.chat.canSend)
        r.chat.send()
        assertEquals(1, r.actions.submitted.size)
    }

    @Test fun enterOnTheKeysSendsLikeTheButton() {
        val r = Rig().open()
        r.chat.startTyping()
        r.chat.input.set("Hi")
        r.chat.onEnter()
        assertEquals(1, r.actions.submitted.size)
        assertFalse("the panel is back to show the answer", r.chat.typing)
    }

    // ------------------------------------------------------------ the window

    @Test fun theWindowKeepsTheTwelveNewestTurnsAndDropsTheOldestFirst() {
        val all = (1..15).map { ChatTurn(if (it % 2 == 1) u else a, "m$it") }
        val w = ChatWindow.fit(all)
        assertTrue(w.size <= ChatWindow.MAX_MESSAGES)
        assertEquals("m15", w.last().text)
        assertEquals(u, w.first().role)
        assertEquals(all.takeLast(w.size).map { it.text }, w.map { it.text })
        // 15 turns, window 12: m4 would lead but is the assistant's, so the window starts at the user turn after it.
        assertEquals("m5", w.first().text)
    }

    @Test fun theWindowRespectsTheCharacterBudgetAndAlwaysKeepsTheNewestUserTurn() {
        val big = "x".repeat(4000)
        val all = listOf(ChatTurn(u, big), ChatTurn(a, big), ChatTurn(u, big), ChatTurn(a, big), ChatTurn(u, big))
        val w = ChatWindow.fit(all)
        assertTrue(w.sumOf { it.text.length } <= ChatWindow.MAX_TOTAL_CHARS)
        // Newest first: 4000 + 4000 fit, a third turn would make 12000 > 10000; the window then starts at a user turn.
        assertEquals(listOf(u), w.map { it.role })
        assertEquals(big, w.last().text)
    }

    @Test fun aSingleLongestTurnStillGoesAlone() {
        val w = ChatWindow.fit(listOf(ChatTurn(a, "x".repeat(4000)), ChatTurn(u, "y".repeat(4000)), ChatTurn(a, "z".repeat(4000)), ChatTurn(u, "w".repeat(4000))))
        assertEquals("w".repeat(4000), w.last().text)
        assertTrue(w.sumOf { it.text.length } <= ChatWindow.MAX_TOTAL_CHARS)
        assertTrue(w.all { it.text.length <= ChatWindow.MAX_TURN_CHARS })
    }

    @Test fun aLongAssistantReplyIsCutToTheTurnLimitAndASurrogatePairIsNeverSplit() {
        val emoji = "😀" // one code point, two chars
        val w = ChatWindow.fit(listOf(ChatTurn(u, "q"), ChatTurn(a, emoji.repeat(4001)), ChatTurn(u, "next")))
        val reply = w.first { it.role == a }.text
        assertEquals(4000, reply.codePointCount(0, reply.length))
        assertEquals(ChatWindow.clip("a${emoji}b", 2), "a$emoji")
        assertEquals(ChatWindow.clipEnd("a${emoji}b", 2), "${emoji}b")
    }

    @Test fun aConversationThatDoesNotEndWithAUserTurnHasNoWindow() {
        assertTrue(ChatWindow.fit(emptyList()).isEmpty())
        assertTrue(ChatWindow.fit(listOf(ChatTurn(u, "q"), ChatTurn(a, "r"))).isEmpty())
    }

    @Test fun aLongConversationSendsOnlyTheWindow() {
        val r = Rig().open()
        repeat(9) { i ->
            r.say("question $i")
            r.reply(text = "answer $i")
        }
        r.say("last question")
        val sent = r.task.messages
        assertTrue(sent.size <= ChatWindow.MAX_MESSAGES)
        assertEquals("last question", sent.last().text)
        assertEquals(u, sent.first().role)
        assertEquals("the transcript still shows everything", 19, r.chat.entries.size)
    }

    // ------------------------------------------------------------ failures, retry, cancel

    @Test fun aNetworkFailureRetriesWithTheSameKeyAndBody() {
        val r = Rig().open()
        r.say("Hello")
        r.actions.answer(0, FakeAiActions.fail(AiFailure.Timeout))
        val failed = r.chat.step as ChatStep.Failed
        assertEquals(AiFailure.Timeout, failed.failure)
        assertTrue(failed.retryable)
        assertEquals("the question stays in the transcript", 1, r.chat.entries.size)
        r.chat.retry()
        assertEquals(2, r.actions.submitted.size)
        assertEquals(r.actions.submitted[0].request.requestId, r.actions.submitted[1].request.requestId)
        assertEquals(r.actions.submitted[0].request.task, r.actions.submitted[1].request.task)
        assertEquals("no second copy of the question", 1, r.chat.entries.size)
        r.reply(1, "Hi!")
        assertEquals(listOf(u, a), r.chat.entries.map { it.role })
    }

    @Test fun anAnswerTheServerGaveForDoesNotRetryWithTheSameKey() {
        val r = Rig().open()
        r.say("Hello")
        r.actions.answer(0, FakeAiActions.fail(AiFailure.GuardrailFailed))
        r.chat.retry()
        assertNotEquals(r.actions.submitted[0].request.requestId, r.actions.submitted[1].request.requestId)
        assertEquals(r.actions.submitted[0].request.task, r.actions.submitted[1].request.task)
    }

    @Test fun quotaAndPlanFailuresOfferTheirLinksAndNoRetry() {
        val r = Rig().open()
        r.say("Hello")
        val quota = AiFailure.QuotaExceeded(null, canUpgrade = true, canBuyCredits = true)
        r.actions.answer(0, FakeAiActions.fail(quota))
        val f = r.chat.step as ChatStep.Failed
        assertFalse(f.retryable)
        assertEquals(AiLink.UPGRADE, f.failure.link)
        assertEquals(AiLink.BUY_CREDITS, f.failure.extraLink)
        r.chat.retry()
        assertEquals("no retry after a quota refusal", 1, r.actions.submitted.size)
    }

    @Test fun aRefusalOfThePlanLocksTheChatForTheSession() {
        val r = Rig().open()
        assertNull(r.chat.blocker)
        r.say("Hello")
        r.actions.answer(0, FakeAiActions.fail(AiFailure.NotEntitled))
        assertEquals(AiFailure.NotEntitled, r.chat.blocker)
        r.chat.input.set("again")
        assertFalse(r.chat.canSend)
        r.chat.send()
        assertEquals("nothing is sent while locked", 1, r.actions.submitted.size)
        // Another account forgets it.
        r.actions.availability = AiAvailability(accountGeneration = 5)
        assertNull(r.chat.blocker)
    }

    @Test fun thePlanStateLocksBeforeAnythingIsSent() {
        val r = Rig().open()
        r.actions.availability = AiAvailability(entitled = setOf(AiTaskKind.TRANSLATE))
        assertEquals(AiFailure.NotEntitled, r.chat.blocker)
        r.chat.input.set("Hi")
        assertFalse(r.chat.canSend)
        r.chat.send()
        assertTrue(r.actions.submitted.isEmpty())
        assertEquals(AiFailure.NotEntitled, (r.chat.step as ChatStep.Failed).failure)
        assertTrue("the message was not added to the transcript", r.chat.entries.isEmpty())
        assertEquals("the text stays in the box", "Hi", r.chat.input.text)
        r.actions.availability = AiAvailability(entitled = setOf(AiTaskKind.CHAT))
        assertNull(r.chat.blocker)
    }

    @Test fun signedOutOfflineAndUpdateAreBlockersNotLocks() {
        val r = Rig().open()
        r.actions.availability = AiAvailability(block = AiBlock.SIGNED_OUT)
        assertEquals(AiFailure.SignInRequired, r.chat.blocker)
        r.actions.availability = AiAvailability(block = AiBlock.UPDATE_REQUIRED)
        assertEquals(AiFailure.UpdateRequired, r.chat.blocker)
        r.actions.availability = AiAvailability(block = AiBlock.OFFLINE)
        assertEquals(AiFailure.Offline, r.chat.blocker)
        r.chat.input.set("Hi")
        assertTrue("offline may still try and say so", r.chat.canSend)
        r.chat.send()
        assertTrue(r.actions.submitted.isEmpty())
        assertEquals(AiFailure.Offline, (r.chat.step as ChatStep.Failed).failure)
    }

    @Test fun cancelAbortsTheCallAndPutsTheQuestionBack() {
        val r = Rig().open()
        r.say("Draft a reply")
        r.chat.cancel()
        assertTrue(r.actions.submitted[0].cancelled)
        assertEquals(ChatStep.Idle, r.chat.step)
        assertTrue(r.chat.entries.isEmpty())
        assertEquals("Draft a reply", r.chat.input.text)
        r.reply(0) // an answer that still arrives is dropped
        assertTrue(r.chat.entries.isEmpty())
    }

    @Test fun anImplementationThatThrowsStillEndsTheRequest() {
        val r = Rig().open()
        r.actions.submitThrows = IllegalStateException("boom")
        r.say("Hi")
        assertEquals(AiFailure.Other, (r.chat.step as ChatStep.Failed).failure)
        assertFalse(r.chat.busy)
    }

    // ------------------------------------------------------------ Continue

    @Test fun aCutOffReplyOffersContinueAndContinueAsksForTheRest() {
        val r = Rig().open()
        r.say("Write a long story")
        r.actions.answer(0, AiOutcome.Success(AiResult("Once upon", truncated = true)))
        assertTrue(r.chat.entries.last().cutOff)
        assertTrue(r.chat.canContinue)
        r.chat.continueReply()
        assertEquals(turns(u to "Write a long story", a to "Once upon", u to "Continue"), r.task.messages)
        assertFalse(r.chat.canContinue) // running
        r.reply(1, " a time.")
        assertFalse("a complete reply has no Continue", r.chat.canContinue)
        assertEquals(4, r.chat.entries.size)
    }

    @Test fun continueIsNotOfferedAfterACompleteReplyOrAFailure() {
        val r = Rig().open()
        r.say("Hi")
        r.reply(text = "Hello")
        assertFalse(r.chat.canContinue)
        r.chat.continueReply()
        assertEquals(1, r.actions.submitted.size)
    }

    // ------------------------------------------------------------ New chat, close

    @Test fun newChatClearsTheConversationAndKeepsThePanelAndLanguage() {
        val r = Rig(selection = "x").open()
        r.chat.chooseLanguage("am")
        r.chat.attachFieldText()
        r.say("Hi")
        r.reply()
        r.chat.input.set("half written")
        r.chat.newChat()
        assertTrue(r.chat.isOpen)
        assertTrue(r.chat.entries.isEmpty())
        assertTrue(r.chat.input.isBlank)
        assertNull(r.chat.fieldText)
        assertEquals(ChatStep.Idle, r.chat.step)
        assertEquals("am", r.chat.language)
    }

    @Test fun closingDropsEverythingAndCancelsTheCall() {
        val r = Rig(selection = "my field").open()
        r.chat.chooseLanguage("om")
        r.chat.attachFieldText()
        r.say("Hi")
        r.chat.input.set("next")
        r.chat.close()
        assertFalse(r.chat.isOpen)
        assertTrue(r.actions.submitted[0].cancelled)
        assertTrue(r.chat.entries.isEmpty())
        assertTrue(r.chat.input.isBlank)
        assertNull(r.chat.fieldText)
        assertNull(r.chat.language)
        assertEquals(ChatStep.Idle, r.chat.step)
        r.reply(0) // late answer: nothing comes back
        assertTrue(r.chat.entries.isEmpty())
        r.chat.open()
        assertTrue("a new session starts empty", r.chat.entries.isEmpty())
    }

    @Test fun transcriptTextIsNeverPrinted() {
        val r = Rig().open()
        r.say("my secret plan")
        r.reply(text = "secret answer")
        assertFalse(r.chat.entries.toString().contains("secret"))
        assertFalse(r.lastRequest.toString().contains("secret"))
        assertFalse(r.task.toString().contains("secret"))
        assertFalse(r.chat.input.toString().contains("secret"))
    }

    @Test fun theTranscriptIsBounded() {
        val r = Rig().open()
        repeat(40) { i ->
            r.say("q$i")
            r.reply(text = "a$i")
        }
        assertEquals(ChatController.MAX_ENTRIES, r.chat.entries.size)
        assertEquals("a39", r.chat.entries.last().text)
    }

    // ------------------------------------------------------------ the host field

    @Test fun fieldTextIsReadOnlyOnTheTapAndThePreferenceIsTheSelection() {
        val r = Rig(selection = "the selected words", fieldText = "all of the field text").open()
        assertEquals(0, r.editor.reads)
        r.chat.attachFieldText()
        assertEquals("the selected words", r.chat.fieldText)
        assertTrue(r.editor.reads > 0)
        assertTrue("the open composition is finished first", r.editor.inner.commits > 0)
        val reads = r.editor.reads
        r.say("Improve this")
        r.reply()
        r.say("And again")
        assertEquals("sending does not read the field again", reads, r.editor.reads)
        assertEquals("the same text goes with every send while it is attached", "the selected words", r.task.fieldText)
    }

    @Test fun withoutASelectionTheTextBeforeTheCursorIsUsedAndBounded() {
        val long = "w".repeat(6000) + " END"
        val r = Rig(fieldText = long).open()
        r.chat.attachFieldText()
        val text = r.chat.fieldText!!
        assertEquals(ChatWindow.MAX_FIELD_CHARS, text.length)
        assertTrue("the end (nearest the cursor) is kept", text.endsWith("END"))
    }

    @Test fun aLongSelectionIsCutToTheContractLimit() {
        val r = Rig(selection = "s".repeat(9000)).open()
        r.chat.attachFieldText()
        assertEquals(ChatWindow.MAX_FIELD_CHARS, r.chat.fieldText!!.length)
    }

    @Test fun anEmptyFieldAttachesNothingAndSaysSo() {
        val r = Rig(fieldText = "  ").open()
        r.chat.attachFieldText()
        assertNull(r.chat.fieldText)
        assertEquals(AiNotice.FIELD_EMPTY, r.chat.notice)
    }

    @Test fun theAttachedTextCanBeRemovedAndThenIsNotSent() {
        val r = Rig(selection = "context").open()
        r.chat.attachFieldText()
        r.say("First")
        assertEquals("context", r.task.fieldText)
        r.reply()
        r.chat.removeFieldText()
        assertNull(r.chat.fieldText)
        r.say("Second")
        assertNull(r.task.fieldText)
    }

    @Test fun nothingOpensOrIsReadInAFieldThatDoesNotAllowAi() {
        val r = Rig(allowed = false, selection = "a password", fieldText = "hunter2")
        r.chat.open()
        assertFalse(r.chat.isOpen)
        r.chat.attachFieldText()
        assertNull(r.chat.fieldText)
        assertEquals(0, r.editor.reads)
        assertTrue(r.actions.submitted.isEmpty())
    }

    // ------------------------------------------------------------ language

    @Test fun theReplyLanguageIsSentWhenChosenAndOmittedForSameAsIWrite() {
        val r = Rig().open()
        r.say("Hi")
        assertNull(r.task.language)
        r.reply()
        r.chat.chooseLanguage("ti")
        r.say("Hi again")
        assertEquals("ti", r.task.language)
        r.reply()
        r.chat.chooseLanguage(null)
        r.say("And once more")
        assertNull(r.task.language)
    }

    @Test fun theLanguageListIsTheTranslateCatalogueAndRefusesOthers() {
        val r = Rig().open()
        val choices = r.chat.languageChoices()
        assertNull("same as I write comes first", choices.first())
        assertEquals("en", choices[1]) // the keyboard language leads
        assertTrue(choices.containsAll(listOf("am", "ti", "om", "ar")))
        r.chat.chooseLanguage("xx")
        assertNull("not in the catalogue: refused", r.chat.language)
        r.actions.catalog = TranslationCatalog.of((1..12).map { TranslationLanguage("l$it", "Lang $it", "Lang $it") })
        r.chat.toggleLanguagePicker()
        assertTrue(r.chat.hasMoreLanguages)
        assertEquals(1 + ChatController.SHORT_LIST, r.chat.languageChoices().size)
        r.chat.moreLanguages()
        assertEquals(1 + 12, r.chat.languageChoices().size)
        r.chat.chooseLanguage("l11")
        assertEquals("l11", r.chat.language)
        assertFalse(r.chat.languagePickerOpen)
    }

    // ------------------------------------------------------------ starters, typing, applying

    @Test fun aStarterPrefillsTheBoxAndDoesNotSend() {
        val r = Rig().open()
        r.chat.prefill("Write a message to ")
        assertEquals("Write a message to ", r.chat.input.text)
        assertTrue(r.chat.typing)
        assertTrue(r.actions.submitted.isEmpty())
        assertTrue(r.chat.entries.isEmpty())
        assertNotNull(r.chat.typingField)
    }

    @Test fun typingStartsAndEndsAndAFailureLeavesTheKeysAlone() {
        val r = Rig().open()
        assertNull(r.chat.typingField)
        r.chat.startTyping()
        assertEquals(r.chat.input, r.chat.typingField)
        r.chat.endTyping()
        assertNull(r.chat.typingField)
    }

    @Test fun insertPutsAnAssistantMessageAtTheCursorAndNeverTheUsers() {
        val r = Rig().open()
        r.say("Hi")
        r.reply(text = "Dear Sir")
        val reply = r.chat.entries.last()
        assertEquals(AiApplied.INSERTED, r.chat.insert(reply.id))
        assertEquals(listOf("Dear Sir"), r.editor.inner.inserted)
        assertEquals(AiApplied.FAILED, r.chat.insert(r.chat.entries.first().id))
        assertEquals(AiApplied.FAILED, r.chat.insert(999))
        assertEquals(1, r.editor.inner.inserted.size)
    }

    @Test fun copyUsesTheClipboardOnTheTap() {
        val r = Rig().open()
        r.say("Hi")
        r.reply(text = "Dear Sir")
        assertTrue(r.clip.copies.isEmpty())
        assertTrue(r.chat.copy(r.chat.entries.last().id))
        assertEquals(listOf("Dear Sir"), r.clip.copies)
        assertEquals(AiNotice.COPIED, r.chat.notice)
        r.clip.copyWorks = false
        assertFalse(r.chat.copy(r.chat.entries.last().id))
        assertEquals(AiNotice.COPY_FAILED, r.chat.notice)
    }

    @Test fun theChatNeverReadsTheClipboard() {
        val r = Rig(selection = "x").open()
        r.clip.state = ClipAvailability.TEXT
        r.clip.content = "copied secret"
        r.chat.attachFieldText()
        r.say("Hi")
        r.reply()
        assertEquals(0, r.clip.contentReads)
        assertEquals(0, r.clip.descriptionReads)
    }
}
