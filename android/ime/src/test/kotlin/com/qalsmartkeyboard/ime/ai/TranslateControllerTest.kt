package com.qalsmartkeyboard.ime.ai

import android.text.InputType as T
import com.qalsmartkeyboard.ime.ComposerUpdate
import com.qalsmartkeyboard.ime.DefaultEnabledLanguages
import com.qalsmartkeyboard.ime.FakeEditor
import com.qalsmartkeyboard.ime.InMemoryModePreferences
import com.qalsmartkeyboard.ime.InputConnectionAdapter
import com.qalsmartkeyboard.ime.InputProfile
import com.qalsmartkeyboard.ime.KeyKind
import com.qalsmartkeyboard.ime.KeyboardController
import com.qalsmartkeyboard.ime.userdict.WordLearningSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The translate panel's state machine (ADR 0037): open, languages, swap, the box, translate, insert, errors, retry, cancel. */
class TranslateControllerTest {
    private class H(
        initial: String = "",
        val actions: FakeAiActions = FakeAiActions(),
        val prefs: AiLanguagePrefs = AiLanguagePrefs.InMemory(),
        var keyboard: String = "en",
    ) {
        val ed = FakeEditor(initial)
        val adapter = InputConnectionAdapter { ed }.also { it.startSession(false, initial.length, initial.length) }
        var changes = 0
        var inputChanges = 0
        var refreshes = 0
        val clipboard = ArrayList<String>()
        private var n = 0
        val c = TranslateController(
            actions = actions,
            editor = EditorAiAccess(adapter) { adapter.finishComposition() },
            post = { it() },
            copyToClipboard = { clipboard += it },
            keyboardLanguage = { keyboard },
            prefs = prefs,
            refresh = { refreshes++ },
            newKey = { "key-${++n}" },
            onChanged = { changes++ },
            onInputChanged = { inputChanges++ },
        ).also { it.onFieldStarted(InputProfile.DEFAULT, "com.example.chat") }

        fun type(text: String) = c.input.sink.apply(ComposerUpdate(commit = text))
        fun backspace() = c.input.sink.apply(ComposerUpdate(deleteBefore = 1))
        fun sent() = actions.submitted.last().request
        fun result() = c.step as TranslateStep.Result
        fun failure() = (c.step as TranslateStep.Failed).failure
    }

    private val mum = AiDestination("p1", "Mum", "am", isProfile = true, formality = "HONORIFIC")

    // ------------------------------------------------------------ open and close

    @Test fun openingReadsOnlyTheSelectionAndSendsNothing() {
        val h = H("Hello there. How are you")
        h.c.open()
        assertTrue(h.c.isOpen)
        assertEquals(TranslateSource.NONE, h.c.source)
        assertEquals("", h.c.input.text)
        assertTrue("the last sentence is offered", h.c.sentenceAvailable)
        assertTrue(h.actions.submitted.isEmpty())
        assertEquals("the plan state is refreshed (throttled by the toolbar)", 1, h.refreshes)
        assertTrue(h.c.fieldTyping() != null)
    }

    @Test fun anEmptyFieldOffersNoSentence() {
        val h = H("")
        h.c.open()
        assertFalse(h.c.sentenceAvailable)
    }

    @Test fun aSelectionPrefillsTheBoxAndReplaceReplacesIt() {
        val h = H("Keep this. Fix me please. After")
        h.ed.selStart = 11
        h.ed.selEnd = 24
        h.c.open()
        assertEquals(TranslateSource.SELECTION, h.c.source)
        assertEquals("Fix me please", h.c.input.text)
        assertTrue(h.c.replacesFieldText)
        h.c.translate()
        assertEquals("Fix me please", h.sent().text)
        h.actions.answer(0, FakeAiActions.ok("እባክህ አስተካክለኝ"))
        assertEquals(AiApplied.REPLACED, h.c.replace())
        assertEquals("Keep this. እባክህ አስተካክለኝ. After", h.ed.text.toString())
        // The panel stays open (translate mode) with an empty box for the next one.
        assertTrue(h.c.isOpen)
        assertEquals("", h.c.input.text)
        assertTrue(h.c.step is TranslateStep.Idle)
        assertEquals(TranslateSource.NONE, h.c.source)
    }

    @Test fun insertAppendsAtTheCursorAndNeverReplacesTheSource() {
        val h = H("Keep this. Fix me please. After")
        h.ed.selStart = 11
        h.ed.selEnd = 24
        h.adapter.onSelectionUpdate(11, 24, -1, -1)
        h.c.open()
        h.c.translate()
        h.actions.answer(0, FakeAiActions.ok("እባክህ አስተካክለኝ"))
        assertEquals(AiApplied.INSERTED, h.c.insert())
        assertEquals("the selection is kept; the translation lands after it", "Keep this. Fix me please እባክህ አስተካክለኝ. After", h.ed.text.toString())
    }

    @Test fun editingTheSelectionTurnsItIntoTypedTextThatIsInserted() {
        val h = H("abc def ghi")
        h.ed.selStart = 4
        h.ed.selEnd = 7
        h.adapter.onSelectionUpdate(4, 7, -1, -1)
        h.c.open()
        h.type("!")
        assertEquals(TranslateSource.TYPED, h.c.source)
        assertFalse(h.c.replacesFieldText)
        h.c.translate()
        assertEquals("def!", h.sent().text)
        h.actions.answer(0, FakeAiActions.ok("DEF!"))
        assertEquals(AiApplied.INSERTED, h.c.insert())
        assertEquals("abc def DEF! ghi", h.ed.text.toString())
    }

    @Test fun useLastSentenceFillsTheBoxAndReplaceReplacesTheSentence() {
        val h = H("One. Two three ")
        h.c.open()
        assertEquals(0, h.ed.calls.count { it == "deleteSurroundingText" })
        h.c.useLastSentence()
        assertEquals(TranslateSource.SENTENCE, h.c.source)
        assertEquals("Two three", h.c.input.text)
        h.c.translate()
        h.actions.answer(0, FakeAiActions.ok("ሁለት ሦስት"))
        assertEquals(AiApplied.REPLACED, h.c.replace())
        assertEquals("One. ሁለት ሦስት ", h.ed.text.toString())
    }

    @Test fun replaceFailsWhenTheFieldChangedButInsertStillLands() {
        val h = H("Hello. How are you")
        h.c.open()
        h.c.useLastSentence()
        h.c.translate()
        h.ed.commitText("?") // the field changed meanwhile (another input method, the app)
        h.actions.answer(0, FakeAiActions.ok("እንዴት ነህ"))
        assertEquals(AiApplied.FAILED, h.c.replace())
        assertEquals(AiNotice.INSERT_FAILED, h.c.notice)
        assertEquals("nothing was inserted silently", "Hello. How are you?", h.ed.text.toString())
        assertEquals(AiApplied.INSERTED, h.c.insert())
        assertEquals("Hello. How are you? እንዴት ነህ", h.ed.text.toString())
    }

    @Test fun closingDropsTheTextAndCancelsTheCall() {
        val h = H()
        h.c.open()
        h.type("Good morning")
        h.c.translate()
        h.c.close()
        assertFalse(h.c.isOpen)
        assertTrue(h.actions.submitted.single().cancelled)
        assertEquals("", h.c.input.text)
        assertNull(h.c.fieldTyping())
        h.actions.answer(0, FakeAiActions.ok("late"))
        assertTrue(h.c.step is TranslateStep.Idle)
    }

    @Test fun aNewFieldClosesThePanelAndRestartsTheDirection() {
        val h = H()
        h.c.open()
        h.c.chooseSource("am")
        h.type("x")
        h.c.onFieldStarted(InputProfile.DEFAULT, "com.other")
        assertFalse(h.c.isOpen)
        assertTrue(h.c.direction.isAuto)
        h.c.open()
        assertEquals("", h.c.input.text)
    }

    @Test fun sensitiveFieldsNeverOpenIt() {
        val h = H("secret")
        h.c.onFieldStarted(InputProfile.from(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD, 0, false, 0), "x")
        h.c.open()
        assertFalse(h.c.isOpen)
        assertFalse(h.ed.calls.any { it == "getSelectedText" || it == "getTextBeforeCursor" })
        assertEquals(0, h.refreshes)
    }

    // ------------------------------------------------------------ languages

    @Test fun theSourceStartsAtDetectWithTheKeyboardLanguageShown() {
        val h = H(keyboard = "am")
        h.c.open()
        assertTrue(h.c.direction.isAuto)
        assertEquals("Detect · Amharic", h.c.sourceLabel("Detect"))
        assertEquals("en", h.c.destination.language)
        h.keyboard = "ti"
        h.c.onKeyboardLanguageChanged()
        assertEquals("Detect · Tigrinya", h.c.sourceLabel("Detect"))
    }

    @Test fun pickingLanguagesClosesTheListAndExplicitSourceIsSent() {
        val h = H()
        h.c.open()
        h.c.openPicker(TranslatePicker.SOURCE)
        assertEquals(TranslatePicker.SOURCE, h.c.picker)
        assertNull("the keys are hidden while a list is shown", h.c.fieldTyping())
        h.c.chooseSource("om")
        assertEquals(TranslatePicker.NONE, h.c.picker)
        h.c.openPicker(TranslatePicker.DESTINATION)
        h.c.chooseDestination(mum)
        assertEquals(mapOf("om" to "p1"), (h.prefs as AiLanguagePrefs.InMemory).pairs)
        h.type("Akkam")
        h.c.translate()
        assertEquals(AiTask.Translate(mum, "om"), h.sent().task)
    }

    @Test fun tappingTheOpenSideAgainClosesItsList() {
        val h = H()
        h.c.open()
        h.c.openPicker(TranslatePicker.DESTINATION)
        h.c.openPicker(TranslatePicker.DESTINATION)
        assertEquals(TranslatePicker.NONE, h.c.picker)
        h.c.openPicker(TranslatePicker.SOURCE)
        assertTrue(h.c.back())
        assertEquals(TranslatePicker.NONE, h.c.picker)
        assertTrue(h.c.isOpen)
        assertTrue(h.c.back())
        assertFalse(h.c.isOpen)
        assertFalse(h.c.back())
    }

    @Test fun swapTurnsTheDirectionAroundAndRemembersIt() {
        val h = H(keyboard = "am")
        h.c.open()
        h.c.swap()
        assertEquals("en", h.c.direction.source)
        assertEquals("am", h.c.destination.language)
        assertEquals("lang:am", (h.prefs as AiLanguagePrefs.InMemory).pairs["en"])
        h.type("Hello")
        h.c.translate()
        assertEquals(AiTask.Translate(AiLanguages.plain("am"), "en"), h.sent().task)
    }

    @Test fun swapAfterADetectedSourceUsesTheDetectedLanguage() {
        val h = H(keyboard = "en")
        h.c.open()
        h.type("ሰላም ነህ")
        h.c.translate()
        assertEquals(AiTask.Translate(AiLanguages.plain("am"), null), h.sent().task)
        // The keyboard said English; the server detected Tigrinya. The header and the direction line follow the server.
        h.actions.answer(0, AiOutcome.Success(AiResult("ሰላም", language = "am", sourceLanguage = "ti")))
        assertEquals("ti", h.c.detected)
        assertEquals("Detect · Tigrinya", h.c.sourceLabel("Detect"))
        h.c.swap()
        assertEquals("the destination becomes the source", "am", h.c.direction.source)
        assertEquals("the detected source becomes the destination", "ti", h.c.destination.language)
        assertTrue("a new direction resets the output", h.c.step is TranslateStep.Idle)
        assertNull(h.c.detected)
    }

    @Test fun changingALanguageResetsTheOutputAndCancelsARunningCall() {
        val h = H()
        h.c.open()
        h.type("Hello")
        h.c.translate()
        h.c.chooseDestination(AiLanguages.plain("ti"))
        assertTrue(h.actions.submitted.single().cancelled)
        assertTrue(h.c.step is TranslateStep.Idle)
    }

    @Test fun anAppRuleIsTheDefaultDestination() {
        val a = FakeAiActions().apply { appRule = { pkg -> if (pkg == "com.example.chat") mum else null } }
        val h = H(actions = a)
        assertEquals("Mum", h.c.destination.label)
        h.c.onFieldStarted(InputProfile.DEFAULT, "com.other")
        assertEquals("Amharic", h.c.destination.label)
    }

    // ------------------------------------------------------------ the box

    @Test fun typingFillsTheBoxCheaplyAndClearEmptiesIt() {
        val h = H()
        h.c.open()
        val before = h.changes
        h.type("Sel")
        h.type("am")
        assertEquals("Selam", h.c.input.text)
        assertEquals(TranslateSource.TYPED, h.c.source)
        assertEquals("keys only repaint the box", before, h.changes)
        assertEquals(2, h.inputChanges)
        assertTrue(h.c.canTranslate)
        h.backspace()
        assertEquals("Sela", h.c.input.text)
        h.c.clearInput()
        assertEquals("", h.c.input.text)
        assertEquals(TranslateSource.NONE, h.c.source)
        assertFalse(h.c.canTranslate)
        assertTrue(h.actions.submitted.isEmpty())
    }

    @Test fun anEditAfterAResultResetsTheOutput() {
        val h = H()
        h.c.open()
        h.type("Hello")
        h.c.translate()
        h.actions.answer(0, FakeAiActions.ok("ሰላም"))
        assertTrue(h.c.step is TranslateStep.Result)
        h.type("!")
        assertTrue(h.c.step is TranslateStep.Idle)
        assertEquals(1, h.actions.submitted.size)
    }

    @Test fun sendingHidesTheKeysUntilTheBoxIsTapped() {
        val h = H()
        h.c.open()
        h.type("Hello")
        assertFalse("typing: the keys are up", h.c.keysHidden)
        h.c.translate()
        assertTrue("sent: the keys give way", h.c.keysHidden)
        h.actions.answer(0, FakeAiActions.ok("ሰላም"))
        assertTrue(h.c.keysHidden)
        h.c.startBoxEditing()
        assertFalse("a tap in the box brings them back", h.c.keysHidden)
        h.c.translate()
        assertTrue("a new send hides them again", h.c.keysHidden)
        h.c.cancel()
        assertFalse("back to idle", h.c.keysHidden)
        h.c.close()
        assertFalse(h.c.keysHidden)
    }

    @Test fun anEmptyCommitIsNotAnEdit() {
        val h = H()
        h.c.open()
        h.type("Hello")
        h.c.translate()
        h.actions.answer(0, FakeAiActions.ok("ሰላም"))
        h.c.input.sink.apply(ComposerUpdate()) // the composer's commit of an empty composition
        assertTrue(h.c.step is TranslateStep.Result)
    }

    // ------------------------------------------------------------ translate

    @Test fun translateSendsTheBoxAndShowsTheResultWithoutInserting() {
        val h = H("Field text")
        h.c.open()
        h.type("Good morning")
        h.c.translate()
        assertTrue(h.c.step is TranslateStep.Working)
        assertTrue(h.c.busy)
        val sent = h.sent()
        assertEquals("Good morning", sent.text)
        assertEquals(AiTask.Translate(AiLanguages.plain("am"), null), sent.task)
        assertEquals("key-1", sent.requestId)
        h.c.translate() // one at a time
        assertEquals(1, h.actions.submitted.size)
        h.actions.answer(0, FakeAiActions.ok("እንደምን አደርክ"))
        assertEquals("እንደምን አደርክ", h.result().shownText)
        assertEquals("Field text", h.ed.text.toString())
        assertFalse(h.ed.calls.any { it.startsWith("commitText") })
    }

    @Test fun nothingInTheBoxIsALocalFailure() {
        val h = H()
        h.c.open()
        h.c.translate()
        assertEquals(AiFailure.NothingToProcess, h.failure())
        h.type(" ")
        h.c.translate()
        assertEquals(AiFailure.NothingToProcess, h.failure())
        assertTrue(h.actions.submitted.isEmpty())
    }

    @Test fun tooLongIsRefusedLocally() {
        val h = H()
        h.c.open()
        h.type("x".repeat(TranslateController.MAX_TEXT + 1))
        assertFalse(h.c.canTranslate)
        h.c.translate()
        assertEquals(AiFailure.TooLong, h.failure())
        assertTrue(h.actions.submitted.isEmpty())
    }

    @Test fun enterTranslatesThenInserts() {
        val h = H("")
        h.c.open()
        h.c.onEnter() // empty: nothing
        assertTrue(h.actions.submitted.isEmpty())
        h.type("Thanks")
        h.c.fieldTyping()!!.submit()
        assertEquals("Thanks", h.sent().text)
        h.c.onEnter() // working: ignored
        assertEquals(1, h.actions.submitted.size)
        h.actions.answer(0, FakeAiActions.ok("አመሰግናለሁ"))
        h.c.onEnter()
        assertEquals("አመሰግናለሁ", h.ed.text.toString())
        assertTrue(h.ed.actions.isEmpty() && h.ed.keys.isEmpty()) // nothing pressed the host's Enter or Send
    }

    @Test fun alternativesCanBePickedCopiedAndInserted() {
        val h = H("")
        h.c.open()
        h.type("Send it")
        h.c.translate()
        h.actions.answer(0, FakeAiActions.ok("main", AiAlternative("to a woman", "alt-f")))
        h.c.selectAlternative(0)
        assertEquals("alt-f", h.result().shownText)
        h.c.copy()
        assertEquals(listOf("alt-f"), h.clipboard)
        assertEquals(AiNotice.COPIED, h.c.notice)
        h.c.insert()
        assertEquals("alt-f", h.ed.text.toString())
    }

    @Test fun theResultKeepsTheServersLanguageForItsDirection() {
        val h = H()
        h.c.open()
        h.c.chooseDestination(AiLanguages.plain("ar"))
        h.type("Hello")
        h.c.translate()
        h.actions.answer(0, AiOutcome.Success(AiResult("مرحبا", language = "ar")))
        assertEquals("ar", h.result().language)
        assertTrue(AiLanguages.isRtl(h.result().language))
    }

    // ------------------------------------------------------------ cancel, errors, retry

    @Test fun cancelAbortsAndKeepsTheText() {
        val h = H()
        h.c.open()
        h.type("Hello")
        h.c.translate()
        h.c.cancel()
        assertTrue(h.actions.submitted.single().cancelled)
        assertTrue(h.c.step is TranslateStep.Idle)
        assertEquals("Hello", h.c.input.text)
        h.actions.answer(0, FakeAiActions.ok("late"))
        assertTrue(h.c.step is TranslateStep.Idle)
    }

    @Test fun retryAfterANetworkErrorReusesTheKeyAndText() {
        for (f in listOf(AiFailure.Timeout, AiFailure.Offline, AiFailure.Busy, AiFailure.ServiceUnavailable)) {
            val h = H()
            h.c.open()
            h.type("Hello")
            h.c.translate()
            h.actions.answer(0, FakeAiActions.fail(f))
            assertSame(f, h.failure())
            h.c.retry()
            val a = h.actions.submitted[0].request
            val b = h.actions.submitted[1].request
            assertEquals(a.requestId, b.requestId)
            assertEquals("Hello", b.text)
            assertEquals(a.task, b.task)
        }
    }

    @Test fun retryAfterOtherFailuresUsesANewKey() {
        val h = H()
        h.c.open()
        h.type("Hello")
        h.c.translate()
        h.actions.answer(0, FakeAiActions.fail(AiFailure.GuardrailFailed))
        h.c.retry()
        assertNotEquals(h.actions.submitted[0].request.requestId, h.actions.submitted[1].request.requestId)
        h.actions.answer(1, FakeAiActions.ok("ሰላም"))
        h.c.translate() // Translate again after a result: another request, a new key
        assertEquals(3, h.actions.submitted.size)
        assertNotEquals(h.actions.submitted[1].request.requestId, h.actions.submitted[2].request.requestId)
    }

    @Test fun everyServerFailureIsShownAndKeepsTheFieldUntouched() {
        val failures = listOf(
            AiFailure.QuotaExceeded(1_800_000_000_000, true), AiFailure.RateLimited(30), AiFailure.NotEntitled, AiFailure.OnDeviceOnly,
            AiFailure.ConsentRequired("AI_PROCESSING"), AiFailure.GuardrailFailed, AiFailure.ContentRejected, AiFailure.LanguageUnsupported,
            AiFailure.SignInRequired, AiFailure.AccountBlocked, AiFailure.UpdateRequired, AiFailure.ServiceUnavailable, AiFailure.Other,
        )
        for (f in failures) {
            val h = H("Field")
            h.c.open()
            h.type("Hello")
            h.c.translate()
            h.actions.answer(0, FakeAiActions.fail(f))
            assertSame(f, h.failure())
            assertEquals("Field", h.ed.text.toString())
        }
    }

    @Test fun blockedStatesShowBeforeAnythingIsSentAndMakeNoCall() {
        val cases = mapOf(
            AiBlock.OFFLINE to AiFailure.Offline,
            AiBlock.SIGNED_OUT to AiFailure.SignInRequired,
            AiBlock.UPDATE_REQUIRED to AiFailure.UpdateRequired,
        )
        for ((block, failure) in cases) {
            val a = FakeAiActions().apply { availability = AiAvailability(block = block) }
            val h = H(actions = a)
            h.c.open()
            assertEquals(failure, h.c.blocker)
            h.type("Hello")
            assertEquals("offline may be retried", block == AiBlock.OFFLINE, h.c.canTranslate)
            h.c.translate()
            assertEquals(failure, h.failure())
            assertTrue(a.submitted.isEmpty())
            // Back online / signed in: Retry goes through.
            a.availability = AiAvailability()
            h.c.retry()
            assertEquals(1, a.submitted.size)
        }
    }

    @Test fun aPlanWithoutTranslationShowsTheUpgradeLinkAndSendsNothing() {
        val a = FakeAiActions().apply { availability = AiAvailability(entitled = setOf(AiTaskKind.GRAMMAR)) }
        val h = H(actions = a)
        h.c.open()
        assertEquals(AiFailure.NotEntitled, h.c.blocker)
        assertEquals(AiLink.UPGRADE, h.c.blocker!!.link)
        h.type("Hello")
        assertFalse(h.c.canTranslate)
        h.c.translate()
        assertEquals(AiFailure.NotEntitled, h.failure())
        assertTrue(a.submitted.isEmpty())
    }

    @Test fun aLateAnswerAfterTheFieldChangedNeverLands() {
        val h = H()
        h.c.open()
        h.type("Hello")
        h.c.translate()
        h.c.onFieldStarted(InputProfile.DEFAULT, "other.app")
        h.actions.answer(0, FakeAiActions.ok("late"))
        assertFalse(h.c.isOpen)
        assertTrue(h.c.step is TranslateStep.Idle)
    }

    // ------------------------------------------------------------ the keys type into the box

    @Test fun theKeysTypeIntoTheBoxNotTheFieldAndNeverSend() {
        val ed = FakeEditor("Field")
        val adapter = InputConnectionAdapter { ed }.also { it.startSession(false, 5, 5) }
        val actions = FakeAiActions()
        lateinit var kc: KeyboardController
        val tc = TranslateController(actions, EditorAiAccess(adapter) { kc.commitComposition() }, { it() }, {}, { "en" })
        kc = KeyboardController(adapter, DefaultEnabledLanguages, InMemoryModePreferences(), WordLearningSink.None) {}
        kc.fieldTyping = { tc.fieldTyping() }
        adapter.onCompositionAbandoned = { kc.onCompositionAbandoned() }
        kc.startField(InputProfile.DEFAULT)
        tc.onFieldStarted(InputProfile.DEFAULT, "x")
        tc.open()
        val keys = kc.layout.rows.flatten()
        for (ch in "hi") kc.onKey(keys.first { it.kind == KeyKind.CHAR && it.text.equals(ch.toString(), ignoreCase = true) })
        kc.onKey(keys.first { it.kind == KeyKind.SPACE })
        ed.deliver(adapter)
        assertEquals("hi ", tc.input.text.lowercase())
        assertEquals("Field", ed.text.toString())
        assertTrue("typing never sends", actions.submitted.isEmpty())
        kc.onKey(keys.first { it.kind == KeyKind.ENTER })
        assertEquals("Enter translates", 1, actions.submitted.size)
        assertEquals("Field", ed.text.toString())
        tc.close()
        kc.onKey(keys.first { it.kind == KeyKind.CHAR && it.isLetter })
        ed.deliver(adapter)
        assertNotEquals("after close the keys type into the field again", "Field", ed.text.toString())
    }
}
