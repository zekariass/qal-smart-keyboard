package com.qalsmartkeyboard.ime.voice

import com.qalsmartkeyboard.ime.ai.AiActions
import com.qalsmartkeyboard.ime.ai.AiAvailability
import com.qalsmartkeyboard.ime.ai.AiBlock
import com.qalsmartkeyboard.ime.ai.AiDestination
import com.qalsmartkeyboard.ime.ai.AiFailure
import com.qalsmartkeyboard.ime.ai.AiTask
import com.qalsmartkeyboard.ime.ai.AiTextTarget
import com.qalsmartkeyboard.ime.ai.FakeAiActions
import com.qalsmartkeyboard.ime.ai.TranslationCatalog
import com.qalsmartkeyboard.ime.ai.TranslationLanguage
import com.qalsmartkeyboard.ime.editor.EditorBuffer
import com.qalsmartkeyboard.ime.voice.FakeVoiceProvider.Companion.online
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T8.12 (ADR 0049): the follow-up strip after a dictation (Fix grammar, Translate, Undo) and the "Insert in" mode. */
class VoiceFollowUpTest {
    /** A host that records replaceDictated and can answer it. */
    private class AppHost(lang: String = "am") : VoiceHost {
        override var keyboardLanguage: String = lang
        override var insertsIntoAppField = true
        var capital = false
        var replaceResult = true
        val committed = ArrayList<String>()
        val replaced = ArrayList<Pair<String, String>>()

        override fun hasPermission() = true
        override fun requestPermission() = Unit
        override fun beforeVoice() = Unit
        override fun textBeforeCursor(): CharSequence? = ""
        override fun wantsCapital() = capital
        override fun commitVoiceText(text: String) {
            committed += text
        }
        override fun replaceDictated(expected: String, replacement: String): Boolean {
            replaced += expected to replacement
            return replaceResult
        }
    }

    private inner class H(lang: String = "am", withAi: Boolean = true) {
        val host = AppHost(lang)
        val provider = FakeVoiceProvider(languages = mutableMapOf("default" to online("am-ET", "en-US", "om-ET")))
        val prefs = VoicePrefs.InMemory(noticeShown = true)
        val ai = FakeAiActions()
        var aiNow: AiActions? = if (withAi) ai else null
        val ids = ArrayList<String>()
        var changes = 0
        val c = VoiceController(
            host, provider, prefs, deviceLocale = { Locale.US },
            ai = { aiNow }, packageName = { "com.example.app" },
            newRequestId = { "req${ids.size + 1}".also { ids += it } },
        ) { changes++ }
        val listener get() = provider.last.listener!!

        fun dictate(text: String) {
            c.onMicTapped()
            listener.onReady()
            listener.onPartial(text)
            listener.onFinal(text)
        }

        fun block(b: AiBlock) {
            ai.availability = AiAvailability(block = b)
        }

        val dest get() = c.followUp!!.destination
    }

    // ------------------------------------------------------------------ the offer

    @Test fun aDictationIntoTheAppsFieldOffersTheStrip() {
        val h = H()
        h.dictate("ሰላም ነው")
        val f = h.c.followUp!!
        assertEquals(listOf("ሰላም ነው"), h.host.committed)
        assertEquals("ሰላም ነው", f.text)
        assertEquals("am", f.spokenLanguage)
        assertEquals(FollowUpPhase.Offer, f.phase)
        assertEquals("lang:en", f.destination.id)
        assertTrue("nothing is sent by itself", h.ai.submitted.isEmpty())
        assertFalse(h.c.isOpen)
    }

    @Test fun theStripTextIsWhatWasDictatedWithoutTheSpacingVoiceTypingAdded() {
        val h = H("en")
        h.host.capital = true
        h.c.onMicTapped()
        h.listener.onReady()
        h.listener.onFinal("hello there")
        assertEquals(listOf("Hello there"), h.host.committed)
        assertEquals("Hello there", h.c.followUp!!.text)
        assertEquals("en", h.c.followUp!!.spokenLanguage)
        // English text offers a translation into another language, never into itself.
        assertEquals("am", h.dest.language)
    }

    @Test fun aProfileDefaultBecomesAPlainLanguageAndNeverTheSpokenLanguage() {
        val h = H()
        h.ai.defaultFor = { AiDestination("p1", "Mum", "en", isProfile = true, serverProfileId = "s1") }
        h.dictate("ሰላም")
        assertEquals(AiDestination("lang:en", h.dest.label, "en"), h.dest)
        val h2 = H()
        h2.ai.defaultFor = { AiDestination("lang:am", "Amharic", "am") }
        h2.dictate("ሰላም")
        assertEquals("the spoken language is no translation target", "en", h2.dest.language)
    }

    @Test fun noStripWhenTheConditionsDoNotHold() {
        val off = H().also { it.prefs.followUps = false; it.dictate("ሰላም") }
        assertNull(off.c.followUp)
        assertEquals(listOf("ሰላም"), off.host.committed)

        val noAi = H(withAi = false).also { it.dictate("ሰላም") }
        assertNull(noAi.c.followUp)

        for (b in listOf(AiBlock.OFFLINE, AiBlock.SIGNED_OUT, AiBlock.UPDATE_REQUIRED)) {
            val blocked = H().also { it.block(b); it.dictate("ሰላም") }
            assertNull("$b", blocked.c.followUp)
        }

        val own = H().also { it.host.insertsIntoAppField = false; it.dictate("ሰላም") }
        assertNull("a keyboard-own field has its own AI controls", own.c.followUp)
        assertEquals(listOf("ሰላም"), own.host.committed)

        val long = H().also { it.dictate("a".repeat(AiTextTarget.MAX_CHARS + 1)) }
        assertNull(long.c.followUp)
        assertEquals(1, long.host.committed.size)

        val max = H().also { it.dictate("a".repeat(AiTextTarget.MAX_CHARS)) }
        assertEquals(AiTextTarget.MAX_CHARS, max.c.followUp!!.text.length)
    }

    @Test fun aRoutedHostWithATargetIsAKeyboardOwnField() {
        val field = AppHost("en")
        val routed = RoutedVoiceHost(field) { }
        assertTrue(routed.insertsIntoAppField)
        routed.target = VoiceTarget(EditorBuffer())
        assertFalse(routed.insertsIntoAppField)
        routed.target = null
        assertTrue(routed.insertsIntoAppField)
    }

    @Test fun noStripWhenTheKeyboardOwnTargetIsRouted() {
        val field = AppHost("en")
        val routed = RoutedVoiceHost(field) { }
        val provider = FakeVoiceProvider(languages = mutableMapOf("default" to online("en-US")))
        val ai = FakeAiActions()
        lateinit var c: VoiceController
        c = VoiceController(routed, provider, VoicePrefs.InMemory(noticeShown = true), deviceLocale = { Locale.US }, ai = { ai }) {
            if (!c.isOpen) routed.target = null
        }
        val b = EditorBuffer()
        routed.target = VoiceTarget(b)
        c.onMicTapped()
        provider.last.listener!!.onReady()
        provider.last.listener!!.onFinal("hello")
        assertNull(c.followUp)
        assertEquals("Hello", b.text.toString())
    }

    // ------------------------------------------------------------------ Fix grammar / Translate

    @Test fun fixGrammarSendsTheTextAndReplacesItWithTheResult() {
        val h = H()
        h.dictate("ሰላም ነው")
        h.c.fixGrammar()
        assertEquals(FollowUpPhase.Working(FollowUpAction.GRAMMAR), h.c.followUp!!.phase)
        val req = h.ai.submitted.single().request
        assertEquals(AiTask.Grammar, req.task)
        assertEquals("ሰላም ነው", req.text)
        assertEquals("req1", req.requestId)
        h.ai.answer(0, FakeAiActions.ok("ሰላም ነው።"))
        assertEquals(listOf("ሰላም ነው" to "ሰላም ነው።"), h.host.replaced)
        val f = h.c.followUp!!
        assertEquals(FollowUpPhase.Done(FollowUpAction.GRAMMAR), f.phase)
        assertEquals("the result is now the dictated text", "ሰላም ነው።", f.text)
        assertEquals(listOf("ሰላም ነው"), h.host.committed) // nothing was inserted besides the dictation
    }

    @Test fun translateSendsTheSpokenLanguageAndTheDestination() {
        val h = H()
        h.dictate("ሰላም")
        h.c.translateFollowUp()
        val task = h.ai.submitted.single().request.task as AiTask.Translate
        assertEquals("en", task.destination.language)
        assertEquals("am", task.sourceLanguage)
        h.ai.answer(0, FakeAiActions.ok("Hello"))
        assertEquals(listOf("ሰላም" to "Hello"), h.host.replaced)
        assertEquals(FollowUpPhase.Done(FollowUpAction.TRANSLATE), h.c.followUp!!.phase)
        assertEquals("Hello", h.c.followUp!!.text)
    }

    @Test fun translateToAnotherDestinationRemembersItForTheApp() {
        val h = H()
        h.dictate("ሰላም")
        val om = AiDestination("lang:om", "Afaan Oromoo", "om")
        h.c.translateFollowUp(om)
        assertEquals(om, (h.ai.submitted.single().request.task as AiTask.Translate).destination)
        assertEquals(listOf<Pair<String?, AiDestination>>("com.example.app" to om), h.ai.remembered)
        assertEquals(om, h.dest)
    }

    @Test fun resultsArriveOnAnyThreadAndAreHandedToTheMainThreadRunner() {
        val queue = ArrayList<() -> Unit>()
        val host = AppHost()
        val provider = FakeVoiceProvider(languages = mutableMapOf("default" to online("am-ET")))
        val ai = FakeAiActions()
        val c = VoiceController(host, provider, VoicePrefs.InMemory(noticeShown = true), deviceLocale = { Locale.US }, ai = { ai }, runOnMain = { queue += it }) { }
        c.onMicTapped()
        provider.last.listener!!.onReady()
        provider.last.listener!!.onFinal("ሰላም")
        c.fixGrammar()
        ai.answer(0, FakeAiActions.ok("ሰላም።"))
        assertTrue("the controller state is untouched until the main thread runs the block", host.replaced.isEmpty())
        assertEquals(FollowUpPhase.Working(FollowUpAction.GRAMMAR), c.followUp!!.phase)
        queue.single().invoke()
        assertEquals(FollowUpPhase.Done(FollowUpAction.GRAMMAR), c.followUp!!.phase)
    }

    @Test fun aFailureShowsTheFailureAndLeavesTheTextAlone() {
        val h = H()
        h.dictate("ሰላም")
        h.c.fixGrammar()
        h.ai.answer(0, FakeAiActions.fail(AiFailure.Offline))
        assertEquals(FollowUpPhase.Failed(AiFailure.Offline, false, FollowUpAction.GRAMMAR), h.c.followUp!!.phase)
        assertEquals("ሰላም", h.c.followUp!!.text)
        assertTrue(h.host.replaced.isEmpty())
        // The action can be tried again.
        h.c.fixGrammar()
        assertEquals(2, h.ai.submitted.size)
        h.ai.answer(1, FakeAiActions.ok("ሰላም።"))
        assertEquals(FollowUpPhase.Done(FollowUpAction.GRAMMAR), h.c.followUp!!.phase)
    }

    @Test fun anEmptyResultIsAFailure() {
        val h = H()
        h.dictate("ሰላም")
        h.c.fixGrammar()
        h.ai.answer(0, FakeAiActions.ok("  "))
        assertEquals(FollowUpPhase.Failed(AiFailure.Other, false, FollowUpAction.GRAMMAR), h.c.followUp!!.phase)
        assertTrue(h.host.replaced.isEmpty())
    }

    @Test fun anImplementationThatThrowsIsAFailureNotACrash() {
        val h = H()
        h.dictate("ሰላም")
        h.ai.submitThrows = IllegalStateException("boom")
        h.c.fixGrammar()
        assertEquals(FollowUpPhase.Failed(AiFailure.Other, false, FollowUpAction.GRAMMAR), h.c.followUp!!.phase)
    }

    @Test fun aBlockedAccountFailsWithoutACall() {
        val h = H()
        h.dictate("ሰላም")
        h.block(AiBlock.OFFLINE)
        h.c.translateFollowUp()
        assertTrue(h.ai.submitted.isEmpty())
        assertEquals(FollowUpPhase.Failed(AiFailure.Offline, false, FollowUpAction.TRANSLATE), h.c.followUp!!.phase)
    }

    @Test fun aMovedFieldReportsMovedAndInsertsNothingElsewhere() {
        val h = H()
        h.dictate("ሰላም")
        h.host.replaceResult = false
        h.c.fixGrammar()
        h.ai.answer(0, FakeAiActions.ok("ሰላም።"))
        assertEquals(FollowUpPhase.Failed(null, true, FollowUpAction.GRAMMAR), h.c.followUp!!.phase)
        assertEquals("ሰላም", h.c.followUp!!.text)
        assertEquals(listOf("ሰላም"), h.host.committed)
        assertEquals(1, h.host.replaced.size)
    }

    @Test fun undoPutsTheOriginalBackAndDismisses() {
        val h = H()
        h.dictate("ሰላም")
        h.c.fixGrammar()
        h.ai.answer(0, FakeAiActions.ok("ሰላም።"))
        h.c.undoFollowUp()
        assertEquals(listOf("ሰላም" to "ሰላም።", "ሰላም።" to "ሰላም"), h.host.replaced)
        assertNull(h.c.followUp)
        h.c.undoFollowUp() // nothing left: no second replace
        assertEquals(2, h.host.replaced.size)
    }

    @Test fun undoIsOnlyForADoneStrip() {
        val h = H()
        h.dictate("ሰላም")
        h.c.undoFollowUp()
        assertTrue(h.host.replaced.isEmpty())
        assertNull(h.c.followUp)
    }

    // ------------------------------------------------------------------ stale results and dismissal

    @Test fun aResultAfterTheStripWasDismissedIsIgnoredAndTheCallCancelled() {
        val h = H()
        h.dictate("ሰላም")
        h.c.fixGrammar()
        h.c.dismissFollowUp()
        assertTrue(h.ai.submitted.single().cancelled)
        assertNull(h.c.followUp)
        h.ai.answer(0, FakeAiActions.ok("ሰላም።"))
        assertTrue(h.host.replaced.isEmpty())
        assertNull(h.c.followUp)
    }

    @Test fun aResultOfAnEarlierDictationNeverTouchesTheNewOne() {
        val h = H()
        h.dictate("አንድ")
        h.c.fixGrammar()
        h.dictate("ሁለት") // a new dictation dismisses the old strip
        assertTrue(h.ai.submitted.single().cancelled)
        assertEquals("ሁለት", h.c.followUp!!.text)
        h.ai.answer(0, FakeAiActions.ok("ሌላ"))
        assertTrue(h.host.replaced.isEmpty())
        assertEquals(FollowUpPhase.Offer, h.c.followUp!!.phase)
    }

    @Test fun aRetryAfterAFailureIgnoresTheFirstCallsLateAnswer() {
        val h = H()
        h.dictate("ሰላም")
        h.c.fixGrammar()
        h.c.dismissFollowUp()
        h.dictate("ሰላም")
        h.c.fixGrammar()
        h.ai.answer(0, FakeAiActions.ok("old"))
        assertEquals(FollowUpPhase.Working(FollowUpAction.GRAMMAR), h.c.followUp!!.phase)
        h.ai.answer(1, FakeAiActions.ok("new"))
        assertEquals(listOf("ሰላም" to "new"), h.host.replaced)
    }

    @Test fun everyWayTheEditorMovesOnDismissesTheStrip() {
        fun strip() = H().also { it.dictate("ሰላም") }
        strip().let { it.c.onEditorChanged(); assertNull(it.c.followUp) }
        strip().let { it.c.cancel(); assertNull(it.c.followUp) }
        strip().let { it.c.backToKeyboard(); assertNull(it.c.followUp) }
        strip().let { it.c.onKeyboardHidden(); assertNull(it.c.followUp) }
        strip().let { it.c.onFieldStarted(allowsVoice = true); assertNull(it.c.followUp) }
        strip().let { it.c.onFieldStarted(allowsVoice = true, sameField = true); assertEquals("same field: kept", FollowUpPhase.Offer, it.c.followUp!!.phase) }
        strip().let { it.c.onFieldStarted(allowsVoice = false, sameField = true); assertNull(it.c.followUp) }
        strip().let { it.c.dismissFollowUp(); assertNull(it.c.followUp) }
        strip().let { it.c.onMicTapped(); assertNull("a new dictation", it.c.followUp) }
        strip().let { it.prefs.followUps = false; it.c.onPreferencesChanged(); assertNull(it.c.followUp) }
    }

    @Test fun editorChangeWhileWorkingCancelsTheRequest() {
        val h = H()
        h.dictate("ሰላም")
        h.c.translateFollowUp()
        h.c.onEditorChanged()
        assertTrue(h.ai.submitted.single().cancelled)
        assertNull(h.c.followUp)
    }

    @Test fun dismissalTellsTheView() {
        val h = H()
        h.dictate("ሰላም")
        val before = h.changes
        h.c.dismissFollowUp()
        assertEquals(before + 1, h.changes)
        h.c.dismissFollowUp() // nothing to dismiss: no redraw
        assertEquals(before + 1, h.changes)
    }

    @Test fun theTimeoutDismissesAnOfferedOrFinishedStripOnly() {
        val h = H()
        h.dictate("ሰላም")
        h.c.onFollowUpTimeout()
        assertNull(h.c.followUp)

        h.dictate("ሰላም")
        h.c.fixGrammar()
        h.c.onFollowUpTimeout()
        assertEquals("a running request stays", FollowUpPhase.Working(FollowUpAction.GRAMMAR), h.c.followUp!!.phase)
        h.ai.answer(0, FakeAiActions.ok("ሰላም።"))
        h.c.onFollowUpTimeout()
        assertNull("a finished one goes", h.c.followUp)

        h.c.onFollowUpTimeout() // nothing: harmless
    }

    @Test fun theStripNeverStartsATimerOrCallsByItself() {
        val h = H()
        h.dictate("ሰላም")
        h.c.onEditorChanged()
        assertTrue(h.ai.submitted.isEmpty())
        assertEquals(0, h.ai.refreshCount)
    }

    // ------------------------------------------------------------------ Insert in

    private fun H.insertIn(lang: String) {
        prefs.chooseInsertLanguage(lang)
    }

    @Test fun insertInTranslatesTheFinalResultBeforeItIsCommitted() {
        val h = H()
        h.insertIn("en")
        h.c.onMicTapped()
        h.listener.onReady()
        h.listener.onPartial("ሰላም")
        h.listener.onFinal("ሰላም ነው")
        assertTrue(h.c.insertTranslating)
        assertEquals(VoiceState.Processing("ሰላም"), h.c.state)
        assertTrue("nothing is committed before the translation", h.host.committed.isEmpty())
        val req = h.ai.submitted.single().request
        val task = req.task as AiTask.Translate
        assertEquals("en", task.destination.language)
        assertFalse(task.destination.isProfile)
        assertEquals("am", task.sourceLanguage)
        assertEquals("ሰላም ነው", req.text)

        h.host.capital = true
        h.ai.answer(0, FakeAiActions.ok("hello there"))
        assertEquals("the translation is capitalised as English", listOf("Hello there"), h.host.committed)
        assertFalse(h.c.insertTranslating)
        assertEquals(VoiceState.Idle, h.c.state)
        assertNull("no strip after a translated insert", h.c.followUp)
    }

    @Test fun insertInEnglishTextStaysEnglishWhenSpokenInEnglishAndNeverTranslatesTheSameLanguage() {
        val h = H("en")
        h.insertIn("en")
        h.dictate("hello")
        assertTrue(h.ai.submitted.isEmpty())
        assertEquals(listOf("hello"), h.host.committed)
        assertFalse(h.c.insertTranslating)
        // The spoken regional variant counts as the same language.
        val h2 = H("am")
        h2.insertIn("am")
        h2.dictate("ሰላም")
        assertTrue(h2.ai.submitted.isEmpty())
    }

    @Test fun insertInFailureCommitsTheOriginalWithATranslateRetry() {
        val h = H()
        h.insertIn("en")
        h.dictate("ሰላም")
        h.ai.answer(0, FakeAiActions.fail(AiFailure.QuotaExceeded(null, canUpgrade = true)))
        assertEquals(listOf("ሰላም"), h.host.committed)
        assertEquals(VoiceState.Idle, h.c.state)
        assertFalse(h.c.insertTranslating)
        val f = h.c.followUp!!
        assertEquals("ሰላም", f.text)
        assertEquals(FollowUpPhase.Failed(AiFailure.QuotaExceeded(null, canUpgrade = true), false, FollowUpAction.TRANSLATE), f.phase)
        assertEquals("en", f.destination.language)

        // Retry from the strip replaces the dictation with the translation.
        h.c.translateFollowUp()
        val task = h.ai.submitted[1].request.task as AiTask.Translate
        assertEquals("en", task.destination.language)
        assertEquals("am", task.sourceLanguage)
        h.ai.answer(1, FakeAiActions.ok("Hello"))
        assertEquals(listOf("ሰላም" to "Hello"), h.host.replaced)
        assertEquals(FollowUpPhase.Done(FollowUpAction.TRANSLATE), h.c.followUp!!.phase)
        h.c.undoFollowUp()
        assertEquals("Hello" to "ሰላም", h.host.replaced.last())
    }

    @Test fun insertInWithBlockedAiCommitsTheOriginalAndShowsTheBlock() {
        val h = H()
        h.insertIn("en")
        h.block(AiBlock.OFFLINE)
        h.dictate("ሰላም")
        assertTrue(h.ai.submitted.isEmpty())
        assertEquals(listOf("ሰላም"), h.host.committed)
        assertEquals(FollowUpPhase.Failed(AiFailure.Offline, false, FollowUpAction.TRANSLATE), h.c.followUp!!.phase)
    }

    @Test fun insertInWithoutAiJustInsertsWhatWasSpoken() {
        val h = H(withAi = false)
        h.insertIn("en")
        h.dictate("ሰላም")
        assertEquals(listOf("ሰላም"), h.host.committed)
        assertNull(h.c.followUp)
    }

    @Test fun insertInFailureInAKeyboardOwnFieldOffersNoStrip() {
        val h = H()
        h.host.insertsIntoAppField = false
        h.insertIn("en")
        h.dictate("ሰላም")
        h.ai.answer(0, FakeAiActions.fail(AiFailure.Timeout))
        assertEquals(listOf("ሰላም"), h.host.committed)
        assertNull(h.c.followUp)
    }

    @Test fun insertInAnImplementationThatThrowsCommitsTheOriginal() {
        val h = H()
        h.insertIn("en")
        h.ai.submitThrows = IllegalStateException("boom")
        h.dictate("ሰላም")
        assertEquals(listOf("ሰላም"), h.host.committed)
        assertFalse(h.c.insertTranslating)
        assertEquals(FollowUpPhase.Failed(AiFailure.Other, false, FollowUpAction.TRANSLATE), h.c.followUp!!.phase)
    }

    @Test fun cancelWhileTranslatingInsertsNothing() {
        val h = H()
        h.insertIn("en")
        h.dictate("ሰላም")
        assertTrue(h.c.insertTranslating)
        h.c.cancel()
        assertTrue(h.ai.submitted.single().cancelled)
        assertFalse(h.c.insertTranslating)
        assertEquals(VoiceState.Idle, h.c.state)
        h.ai.answer(0, FakeAiActions.ok("late"))
        assertTrue(h.host.committed.isEmpty())
        assertNull(h.c.followUp)
    }

    @Test fun backToKeyboardWhileTranslatingInsertsNothing() {
        val h = H()
        h.insertIn("en")
        h.dictate("ሰላም")
        h.c.backToKeyboard()
        assertTrue(h.ai.submitted.single().cancelled)
        h.ai.answer(0, FakeAiActions.ok("late"))
        assertTrue(h.host.committed.isEmpty())
    }

    @Test fun theMicAndALateRecogniserCallbackDoNothingWhileTranslating() {
        val h = H()
        h.insertIn("en")
        h.dictate("ሰላም")
        h.c.onMicTapped()
        h.listener.onFinal("again")
        h.listener.onError(VoiceErrorKind.NETWORK)
        assertEquals(1, h.ai.submitted.size)
        assertTrue(h.c.insertTranslating)
        assertTrue(h.host.committed.isEmpty())
    }

    @Test fun aNewSessionWhileTranslatingDropsTheTranslation() {
        val h = H()
        h.insertIn("en")
        h.dictate("ሰላም")
        h.c.retry()
        assertTrue(h.ai.submitted.single().cancelled)
        assertFalse(h.c.insertTranslating)
        h.ai.answer(0, FakeAiActions.ok("late"))
        assertTrue(h.host.committed.isEmpty())
    }

    @Test fun insertInAlsoTranslatesAPartialResultKeptAfterNoMatch() {
        val h = H()
        h.insertIn("en")
        h.c.onMicTapped()
        h.listener.onReady()
        h.listener.onPartial("ሰላም")
        h.c.stop()
        h.listener.onError(VoiceErrorKind.NO_MATCH)
        assertEquals("ሰላም", h.ai.submitted.single().request.text)
        h.ai.answer(0, FakeAiActions.ok("Hello"))
        assertEquals(listOf("Hello"), h.host.committed)
    }

    @Test fun insertInTranslationResultsArriveOnAnyThread() {
        val queue = ArrayList<() -> Unit>()
        val host = AppHost()
        val provider = FakeVoiceProvider(languages = mutableMapOf("default" to online("am-ET")))
        val ai = FakeAiActions()
        val prefs = VoicePrefs.InMemory(noticeShown = true, insertLanguage = "en")
        val c = VoiceController(host, provider, prefs, deviceLocale = { Locale.US }, ai = { ai }, runOnMain = { queue += it }) { }
        c.onMicTapped()
        provider.last.listener!!.onReady()
        provider.last.listener!!.onFinal("ሰላም")
        ai.answer(0, FakeAiActions.ok("Hello"))
        assertTrue(host.committed.isEmpty())
        queue.single().invoke()
        assertEquals(listOf("Hello"), host.committed)
    }

    // ------------------------------------------------------------------ the picker

    @Test fun theInsertLanguageIsThePreferenceWhenAiIsUsable() {
        val h = H()
        assertNull(h.c.insertLanguage)
        h.c.chooseInsertLanguage("en")
        assertEquals("en", h.prefs.insertLanguage)
        assertEquals("en", h.c.insertLanguage)
        assertTrue(h.changes > 0)
        h.block(AiBlock.SIGNED_OUT)
        assertNull("the chip is hidden while AI is unusable", h.c.insertLanguage)
        h.block(AiBlock.NONE)
        h.aiNow = null
        assertNull(h.c.insertLanguage)
        h.c.chooseInsertLanguage(null)
        assertNull(h.prefs.insertLanguage)
    }

    @Test fun aLanguageOutsideTheCatalogueIsNoInsertLanguage() {
        val h = H()
        h.ai.catalog = TranslationCatalog(listOf(TranslationLanguage("en", "English", "English"), TranslationLanguage("fr", "French", "Français")), fromServer = true)
        h.prefs.chooseInsertLanguage("am")
        assertNull(h.c.insertLanguage)
        h.prefs.chooseInsertLanguage("fr")
        assertEquals("fr", h.c.insertLanguage)
    }

    @Test fun theChoicesArePlainLanguagesOfTheCatalogue() {
        val h = H()
        val choices = h.c.insertLanguageChoices()
        assertEquals(listOf("am", "ti", "om", "en", "ar"), choices.map { it.language })
        assertTrue(choices.none { it.isProfile })
        assertEquals("lang:en", choices.first { it.language == "en" }.id)
        h.ai.catalog = TranslationCatalog(
            listOf(TranslationLanguage("en", "English", "English"), TranslationLanguage("fr", "French", "Francais", asDestination = false), TranslationLanguage("de", "German", "Deutsch")),
            fromServer = true,
        )
        assertEquals(listOf("en", "de"), h.c.insertLanguageChoices().map { it.language })
        h.block(AiBlock.OFFLINE)
        assertTrue(h.c.insertLanguageChoices().isEmpty())
        h.block(AiBlock.NONE)
        h.aiNow = null
        assertTrue(h.c.insertLanguageChoices().isEmpty())
    }

    // ------------------------------------------------------------------ privacy (N2)

    @Test fun noNewTypePrintsTheText() {
        val secret = "ሚስጥራዊ-secret-text"
        val h = H()
        h.dictate(secret)
        val strings = ArrayList<String>()
        strings += h.c.followUp.toString()
        h.c.fixGrammar()
        strings += h.c.followUp.toString()
        strings += h.ai.submitted.single().request.toString()
        h.ai.answer(0, FakeAiActions.ok("$secret result"))
        strings += h.c.followUp.toString()
        strings += h.c.followUp!!.phase.toString()
        h.host.replaceResult = false
        strings += FollowUpPhase.Failed(null, true, FollowUpAction.GRAMMAR).toString()
        for (s in strings) assertFalse(s, s.contains("secret") || s.contains("ሚስጥራዊ"))
    }

    @Test fun dismissalAndCancelForgetTheText() {
        val h = H()
        h.dictate("ሰላም")
        h.c.fixGrammar()
        h.ai.answer(0, FakeAiActions.ok("ሰላም።"))
        h.c.cancel()
        assertNull(h.c.followUp)
        h.c.undoFollowUp() // the original is forgotten too: nothing to put back
        assertEquals(1, h.host.replaced.size)
    }
}
