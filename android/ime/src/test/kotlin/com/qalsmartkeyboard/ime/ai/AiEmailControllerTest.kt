package com.qalsmartkeyboard.ime.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The keyboard's Write Email panel (ADR 0031) and the shared e-mail rules. */
class AiEmailControllerTest {
    private class H(selection: String? = null, field: String = "", clip: ClipAvailability = ClipAvailability.NONE, clipText: String? = null) {
        val actions = FakeAiActions()
        val editor = FakeAiEditor(selection, field)
        val clipboard = FakeClipboard(clip, clipText)
        val prefs = AiLanguagePrefs.InMemory()
        private var n = 0
        val c = AiEmailController(actions, editor, clipboard, prefs, post = { it() }, newKey = { "key-${++n}" })
        fun spec() = (actions.submitted.last().request.task as AiTask.Email).spec
    }

    private fun draftAnswer(subject: String?, body: String, placeholders: List<String> = emptyList()) =
        AiOutcome.Success(AiResult(body, subject = subject, placeholders = placeholders))

    // ------------------------------------------------------------ mode validation

    @Test fun newNeedsInstructions() {
        val h = H()
        h.c.open()
        h.c.generate()
        assertEquals(AiEmailProblem.MISSING_INSTRUCTIONS, h.c.problem)
        assertTrue(h.actions.submitted.isEmpty())
        h.c.instructions.set("ask for a day off on Friday")
        h.c.generate()
        val s = h.spec()
        assertEquals(AiEmailMode.NEW, s.mode)
        assertEquals("ask for a day off on Friday", s.instructions)
        assertEquals("ask for a day off on Friday", h.actions.submitted.single().request.text)
        assertEquals(AiEmailStep.Working, h.c.step)
    }

    @Test fun replyNeedsTheOriginalAndInstructions() {
        val h = H()
        h.c.open()
        h.c.setMode(AiEmailMode.REPLY)
        h.c.instructions.set("yes")
        h.c.generate()
        assertEquals(AiEmailProblem.MISSING_ORIGINAL, h.c.problem)
        h.c.original.set("Dear team, can you attend?")
        h.c.instructions.set("")
        h.c.generate()
        assertEquals(AiEmailProblem.MISSING_INSTRUCTIONS, h.c.problem)
        h.c.instructions.set("yes, I will attend")
        h.c.generate()
        val s = h.spec()
        assertEquals("Dear team, can you attend?", s.originalEmail)
        assertEquals("", s.draft)
    }

    @Test fun improveNeedsADraftOnly() {
        val h = H()
        h.c.open()
        h.c.setMode(AiEmailMode.IMPROVE)
        h.c.generate()
        assertEquals(AiEmailProblem.MISSING_DRAFT, h.c.problem)
        h.c.draft.set("hi i want leave friday")
        h.c.original.set("left over from Reply") // not sent outside REPLY
        h.c.generate()
        assertEquals("hi i want leave friday", h.spec().draft)
        assertEquals("", h.spec().originalEmail)
    }

    @Test fun limitsAreCheckedOnTheDevice() {
        val spec = AiEmailSpec(AiEmailMode.NEW, "en", instructions = "x".repeat(AiEmailRules.MAX_INSTRUCTIONS + 1))
        assertEquals(AiEmailProblem.INSTRUCTIONS_TOO_LONG, AiEmailRules.validate(spec))
        assertEquals(AiEmailProblem.ORIGINAL_TOO_LONG, AiEmailRules.validate(AiEmailSpec(AiEmailMode.REPLY, "en", "ok", originalEmail = "y".repeat(8001))))
        assertEquals(AiEmailProblem.DRAFT_TOO_LONG, AiEmailRules.validate(AiEmailSpec(AiEmailMode.IMPROVE, "en", draft = "z".repeat(8001))))
        assertEquals(AiEmailProblem.NAME_TOO_LONG, AiEmailRules.validate(AiEmailSpec(AiEmailMode.NEW, "en", "ok", senderName = "n".repeat(121))))
        // Ge'ez characters count as one each (code points, like the server).
        assertNull(AiEmailRules.validate(AiEmailSpec(AiEmailMode.NEW, "am", "ሰ".repeat(AiEmailRules.MAX_INSTRUCTIONS))))
    }

    // ------------------------------------------------------------ request building

    @Test fun theRequestCarriesToneLengthLanguageAndNames() {
        val h = H()
        h.prefs.senderName = "Abebe"
        h.c.open()
        assertEquals("the remembered name is prefilled", "Abebe", h.c.sender.text)
        h.c.instructions.set("thank Dr. Almaz for the meeting")
        h.c.chooseTone(AiEmailTone.FORMAL)
        h.c.chooseLength(AiLength.SHORT)
        h.c.chooseLanguage("am")
        h.c.recipient.set("Dr. Almaz")
        h.c.sender.set("Abebe Kebede")
        h.c.generate()
        val s = h.spec()
        assertEquals(AiEmailTone.FORMAL, s.tone)
        assertEquals(AiLength.SHORT, s.length)
        assertEquals("am", s.language)
        assertEquals("Dr. Almaz", s.recipientName)
        assertEquals("Abebe Kebede", s.senderName)
        assertTrue(s.includeSubject)
        assertEquals(0, s.variation)
        assertEquals("a new name is remembered on the device", "Abebe Kebede", h.prefs.senderName)
        assertFalse("user text never in toString", s.toString().contains("Almaz"))
    }

    @Test fun regenerateAsksForTheNextVariationWithANewKey() {
        val h = H()
        h.c.open()
        h.c.instructions.set("say hello")
        h.c.generate()
        h.actions.answer(0, draftAnswer("Hello", "Hi [name],"))
        assertEquals(AiEmailStep.Preview, h.c.step)
        h.c.regenerate()
        assertEquals(1, h.spec().variation)
        assertNotEquals(h.actions.submitted[0].request.requestId, h.actions.submitted[1].request.requestId)
        h.c.cancel()
        assertEquals("a cancelled regenerate keeps the version the user had", AiEmailStep.Preview, h.c.step)
        assertTrue(h.actions.submitted[1].cancelled)
    }

    @Test fun cancellingTheFirstGenerateGoesBackToTheInputs() {
        val h = H()
        h.c.open()
        h.c.instructions.set("say hello")
        h.c.generate()
        h.c.cancel()
        assertEquals(AiEmailStep.Compose, h.c.step)
        assertEquals("say hello", h.c.instructions.text)
    }

    // ------------------------------------------------------------ sources

    @Test fun theSelectionAndTheClipboardFillTheEmailReceivedOnlyOnTap() {
        val h = H(selection = "Dear Abebe, please confirm.", clip = ClipAvailability.TEXT, clipText = "From the clipboard")
        h.c.open()
        h.c.setMode(AiEmailMode.REPLY)
        assertTrue(h.c.hasSelection)
        assertTrue(h.c.original.isBlank)
        assertEquals(0, h.clipboard.contentReads)
        h.c.useSelectionAsOriginal()
        assertEquals("Dear Abebe, please confirm.", h.c.original.text)
        h.c.pasteOriginal()
        assertEquals("From the clipboard", h.c.original.text)
        assertEquals(1, h.clipboard.contentReads)
    }

    @Test fun theFieldTextIsReadOnlyOnTheExplicitTapAndReplacedOnInsert() {
        val h = H(field = "hi i want leave friday")
        h.c.open()
        h.c.setMode(AiEmailMode.IMPROVE)
        assertEquals(0, h.editor.allTextReads)
        h.c.useFieldTextAsDraft()
        assertEquals(1, h.editor.allTextReads)
        assertTrue(h.c.draftFromField)
        h.c.generate()
        h.actions.answer(0, draftAnswer(null, "Dear manager, I would like to take Friday off."))
        assertEquals(AiApplied.REPLACED, h.c.insert())
        assertEquals("Dear manager, I would like to take Friday off.", h.editor.fieldText)
        assertTrue(h.editor.inserted.isEmpty())
    }

    // ------------------------------------------------------------ preview, placeholders, subject, insert, copy

    @Test fun placeholdersAreListedAndFollowEdits() {
        val h = H()
        h.c.open()
        h.c.instructions.set("invite the team")
        h.c.generate()
        h.actions.answer(0, draftAnswer("Meeting on [date]", "Hello [name],\nSee you at [time] on [date].", listOf("[date]", "[name]", "[time]")))
        assertEquals(listOf("[date]", "[name]", "[time]"), h.c.placeholders)
        h.c.startTyping(AiEmailField.BODY)
        h.c.body.set("Hello Almaz,\nSee you at [time] on [date].")
        h.c.endTyping()
        assertEquals(listOf("[date]", "[time]"), h.c.placeholders)
    }

    @Test fun insertPutsOnlyTheBodyUnlessTheSubjectIsTurnedOn() {
        val h = H()
        h.c.open()
        h.c.instructions.set("x")
        h.c.generate()
        h.actions.answer(0, draftAnswer("Day off", "Dear manager,"))
        assertEquals(AiApplied.INSERTED, h.c.insert())
        assertEquals("Dear manager,", h.editor.inserted.last())
        h.c.toggleInsertSubject()
        h.c.insert()
        assertEquals("Subject: Day off\n\nDear manager,", h.editor.inserted.last())
    }

    @Test fun copySubjectBodyAndAll() {
        val h = H()
        h.c.open()
        h.c.instructions.set("x")
        h.c.generate()
        h.actions.answer(0, draftAnswer("Day off", "Dear manager,"))
        h.c.copySubject()
        h.c.copyBody()
        h.c.copyAll()
        assertEquals(listOf("Day off", "Dear manager,", "Day off\n\nDear manager,"), h.clipboard.copies)
        assertEquals(AiNotice.COPIED, h.c.notice)
    }

    @Test fun aLockedPlanShowsTheUpgradeLink() {
        val h = H()
        h.actions.availability = AiAvailability(entitled = setOf(AiTaskKind.TRANSLATE))
        h.c.open()
        h.c.instructions.set("x")
        h.c.generate()
        assertTrue(h.actions.submitted.isEmpty())
        assertEquals(AiFailure.NotEntitled, (h.c.step as AiEmailStep.Failed).failure)
    }

    @Test fun aServerRefusalLocksUntilTheAccountChanges() {
        // ADR 0035: the lock learned from a refusal belongs to the account that got it.
        val h = H()
        h.c.open()
        h.c.instructions.set("x")
        h.c.generate()
        h.actions.answer(0, FakeAiActions.fail(AiFailure.NotEntitled))
        assertTrue(h.c.isLocked)
        h.actions.availability = AiAvailability(accountGeneration = 1)
        assertFalse(h.c.isLocked)
    }

    @Test fun theHandoffCarriesTheInputsOnceAndExpires() {
        val h = H()
        h.c.open()
        h.c.setMode(AiEmailMode.REPLY)
        h.c.original.set("Original")
        h.c.instructions.set("Agree")
        h.c.handoff()
        val taken = AiEmailHandoff.take()
        assertEquals("Original", taken?.originalEmail)
        assertNull("taken once", AiEmailHandoff.take())
        AiEmailHandoff.put(AiEmailSpec(AiEmailMode.NEW, "en", "x"), nowMs = 0)
        assertNull("expired", AiEmailHandoff.take(nowMs = AiEmailHandoff.TTL_MS + 1))
    }

    @Test fun insertTextAndPlaceholderRules() {
        assertEquals("body", AiEmailRules.insertText(null, "body", true))
        assertEquals("body", AiEmailRules.insertText("S", "body", false))
        assertEquals(listOf(0..5), AiEmailRules.placeholderRanges("[date] and [link](https://x) and [] and [ ]").take(1))
        assertEquals(listOf("[ቀን]"), AiEmailRules.placeholders(null, "በ[ቀን] እንገናኝ"))
        assertEquals(20, AiEmailRules.nextVariation(20))
    }
}
