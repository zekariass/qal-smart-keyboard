package com.qalsmartkeyboard.ime.voice

import android.text.InputType as T
import android.view.inputmethod.EditorInfo as E
import com.qalsmartkeyboard.ime.InputProfile
import com.qalsmartkeyboard.ime.voice.FakeVoiceProvider.Companion.GOOGLE
import com.qalsmartkeyboard.ime.voice.FakeVoiceProvider.Companion.online
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Voice typing's state machine, permission routing, mic gating and language memory (ADR 0029). */
class VoiceControllerTest {
    private inner class H(lang: String = "am", languages: Map<String, RecognizerLanguages?> = mapOf("default" to online("am-ET", "om-ET", "en-US", "en-GB", "fr-FR"))) {
        val host = FakeVoiceHost(lang)
        val provider = FakeVoiceProvider(languages = languages.toMutableMap())
        val prefs = VoicePrefs.InMemory()
        var changes = 0
        val c = VoiceController(host, provider, prefs, deviceLocale = { Locale.US }) { changes++ }
        val input get() = provider.last
        val listener get() = input.listener!!
    }

    // ------------------------------------------------------------------ the happy path

    @Test fun idleListeningProcessingInserted() {
        val h = H()
        assertEquals(VoiceState.Idle, h.c.state)
        h.c.onMicTapped()
        assertEquals(1, h.host.beforeCalls) // composition committed, emoji and AI closed first
        assertEquals(VoiceState.Preparing, h.c.state)
        assertEquals("am-ET", h.input.tag)
        h.listener.onReady()
        assertEquals(VoiceState.Listening(""), h.c.state)
        h.listener.onLevel(0.6f)
        assertEquals(0.6f, h.c.level, 0f)
        h.listener.onPartial("ሰላም")
        assertEquals(VoiceState.Listening("ሰላም"), h.c.state)
        assertTrue(h.host.committed.isEmpty()) // a preview is never committed
        h.listener.onEndOfSpeech()
        assertEquals(VoiceState.Processing("ሰላም"), h.c.state)
        h.listener.onFinal("ሰላም ነው")
        assertEquals(listOf("ሰላም ነው"), h.host.committed)
        assertEquals(VoiceState.Idle, h.c.state)
        assertFalse(h.c.isOpen)
    }

    @Test fun stopInsertsTheFinalResult() {
        val h = H()
        h.c.onMicTapped()
        h.listener.onReady()
        h.listener.onPartial("hello")
        h.c.onMainButton() // the big stop button
        assertTrue(h.input.stopped)
        assertEquals(VoiceState.Processing("hello"), h.c.state)
        h.listener.onFinal("hello world")
        assertEquals(listOf("hello world"), h.host.committed)
    }

    @Test fun stopKeepsThePartialTextWhenNoFinalResultComes() {
        val h = H()
        h.c.onMicTapped()
        h.listener.onReady()
        h.listener.onPartial("ሰላም")
        h.c.stop()
        h.listener.onError(VoiceErrorKind.NO_MATCH)
        assertEquals(listOf("ሰላም"), h.host.committed)
        assertEquals(VoiceState.Idle, h.c.state)
        // An empty final result also falls back to what was heard.
        h.c.onMicTapped()
        h.listener.onReady()
        h.listener.onPartial("ደህና")
        h.c.onMicTapped() // the bar's mic stops too
        h.listener.onFinal("")
        assertEquals(listOf("ሰላም", "ደህና"), h.host.committed)
    }

    @Test fun nothingHeardIsAnErrorWithRetry() {
        val h = H()
        h.c.onMicTapped()
        h.listener.onReady()
        h.listener.onFinal("  ")
        assertEquals(VoiceState.Failed(VoiceErrorKind.NO_MATCH, "am-ET"), h.c.state)
        assertTrue(h.host.committed.isEmpty())
        h.c.retry()
        assertEquals(VoiceState.Preparing, h.c.state)
        assertEquals(2, h.provider.inputs.size)
    }

    @Test fun errorsShowTheirKindAndTheLanguage() {
        val h = H()
        h.c.onMicTapped()
        h.listener.onError(VoiceErrorKind.NETWORK)
        assertEquals(VoiceState.Failed(VoiceErrorKind.NETWORK, "am-ET"), h.c.state)
        assertTrue(h.c.isOpen) // the panel stays to explain
    }

    @Test fun backToKeyboardCancelsWithoutInserting() {
        val h = H()
        h.c.onMicTapped()
        h.listener.onReady()
        h.listener.onPartial("ሰላም")
        h.c.backToKeyboard()
        assertTrue(h.input.cancelled)
        assertEquals(VoiceState.Idle, h.c.state)
        h.listener.onFinal("late") // a late callback of the cancelled session is ignored
        assertTrue(h.host.committed.isEmpty())
    }

    @Test fun hidingTheKeyboardOrChangingFieldCancels() {
        val h = H()
        h.c.onMicTapped()
        h.listener.onReady()
        h.c.onKeyboardHidden()
        assertTrue(h.input.cancelled)
        assertEquals(VoiceState.Idle, h.c.state)
        h.c.onMicTapped()
        h.c.onFieldStarted(allowsVoice = true)
        assertTrue(h.input.cancelled)
        assertEquals(VoiceState.Idle, h.c.state)
    }

    @Test fun theSameFieldRestartingKeepsListening() {
        val h = H()
        h.c.onMicTapped()
        h.listener.onReady()
        h.c.onFieldStarted(allowsVoice = true, sameField = true)
        assertFalse(h.input.cancelled)
        assertEquals(VoiceState.Listening(""), h.c.state)
    }

    @Test fun turningTheSettingOffClosesThePanelAndHidesTheMic() {
        val h = H()
        h.c.onMicTapped()
        h.prefs.enabled = false
        h.c.onPreferencesChanged()
        assertEquals(VoiceState.Idle, h.c.state)
        assertFalse(h.c.micVisible)
        h.c.onMicTapped()
        assertEquals(VoiceState.Idle, h.c.state)
    }

    // ------------------------------------------------------------------ unsupported languages

    @Test fun anUndeclaredLanguageFailsHonestlyWithoutListening() {
        val h = H(lang = "ti")
        h.c.onMicTapped()
        assertEquals(VoiceState.Failed(VoiceErrorKind.LANGUAGE_NOT_SUPPORTED, "ti-ET"), h.c.state)
        assertTrue(h.provider.inputs.isEmpty()) // the microphone was never opened
    }

    @Test fun googleIsUsedWhenTheDefaultServiceLacksTheLanguage() {
        val h = H(lang = "ti", languages = mapOf("default" to online("en-US"), GOOGLE.id to online("ti-ER")))
        h.provider.recognizerList = listOf(RecognizerRef.DEFAULT, GOOGLE)
        h.c.onMicTapped()
        assertEquals(GOOGLE, h.input.recognizer)
        assertEquals("ti-ER", h.input.tag)
    }

    @Test fun beforeAndroid13ALanguageErrorMovesToTheNextTagThenStops() {
        val h = H(lang = "ti", languages = emptyMap()) // cannot ask: every tag is tried
        h.c.onMicTapped()
        assertEquals("ti-ET", h.input.tag)
        h.listener.onError(VoiceErrorKind.LANGUAGE_NOT_SUPPORTED)
        assertEquals("ti-ER", h.input.tag) // retried automatically with the next tag
        h.listener.onError(VoiceErrorKind.LANGUAGE_NOT_SUPPORTED)
        assertEquals(VoiceState.Failed(VoiceErrorKind.LANGUAGE_NOT_SUPPORTED, "ti-ER"), h.c.state)
        assertEquals(2, h.provider.inputs.size)
        // Today the rejection is remembered: no microphone is opened again for it.
        h.c.retry()
        assertEquals(2, h.provider.inputs.size)
        assertTrue(h.c.state is VoiceState.Failed)
    }

    @Test fun aGenericErrorOnAnUntriedLanguageSaysItMayNotBeSupported() {
        val h = H(lang = "om", languages = emptyMap())
        h.c.onMicTapped()
        h.listener.onError(VoiceErrorKind.SERVER)
        assertEquals(VoiceState.Failed(VoiceErrorKind.LANGUAGE_MAYBE_UNSUPPORTED, "om-ET"), h.c.state)
        // Once the language produced a result, the same error is just a server error.
        h.c.retry()
        h.listener.onReady()
        h.listener.onFinal("nagaa")
        h.c.onMicTapped()
        h.listener.onError(VoiceErrorKind.SERVER)
        assertEquals(VoiceState.Failed(VoiceErrorKind.SERVER, "om-ET"), h.c.state)
    }

    @Test fun englishGenericErrorsAreNotBlamedOnTheLanguage() {
        val h = H(lang = "en", languages = emptyMap())
        h.c.onMicTapped()
        h.listener.onError(VoiceErrorKind.CLIENT)
        assertEquals(VoiceState.Failed(VoiceErrorKind.CLIENT, "en-US"), h.c.state)
    }

    // ------------------------------------------------------------------ permission and gating

    @Test fun withoutPermissionTheMicOpensThePermissionScreenOnly() {
        val h = H()
        h.host.permission = false
        h.c.onMicTapped()
        assertEquals(1, h.host.permissionRequests)
        assertEquals(VoiceState.Idle, h.c.state)
        assertEquals(0, h.host.beforeCalls)
        assertTrue(h.provider.inputs.isEmpty())
        h.host.permission = true
        h.c.onMicTapped()
        assertEquals(VoiceState.Preparing, h.c.state)
    }

    @Test fun theMicIsHiddenInPasswordFieldsWhenTheSettingIsOffOrWithoutAService() {
        val text = InputProfile.from(T.TYPE_CLASS_TEXT, E.IME_ACTION_NONE, false, 0)
        val password = InputProfile.from(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD, E.IME_ACTION_NONE, false, 0)
        val numberPassword = InputProfile.from(T.TYPE_CLASS_NUMBER or T.TYPE_NUMBER_VARIATION_PASSWORD, E.IME_ACTION_NONE, false, 0)
        val terminal = InputProfile.from(T.TYPE_NULL, E.IME_ACTION_NONE, false, 0)
        assertTrue(VoiceController.fieldAllowsVoice(text, null))
        assertFalse(VoiceController.fieldAllowsVoice(password, null))
        assertFalse(VoiceController.fieldAllowsVoice(numberPassword, null))
        assertFalse(VoiceController.fieldAllowsVoice(terminal, null))
        assertFalse(VoiceController.fieldAllowsVoice(text, "nm"))
        assertFalse(VoiceController.fieldAllowsVoice(text, "foo,com.google.android.inputmethod.latin.noMicrophoneKey"))
        assertTrue(VoiceController.fieldAllowsVoice(text, "nmx,other"))

        val h = H()
        assertTrue(h.c.micVisible)
        h.c.onFieldStarted(allowsVoice = false)
        assertFalse(h.c.micVisible)
        h.c.onMicTapped()
        assertEquals(VoiceState.Idle, h.c.state)
        h.c.onFieldStarted(allowsVoice = true)
        h.provider.available = false
        assertFalse(h.c.micVisible)
        h.provider.available = true
        h.prefs.enabled = false
        assertFalse(h.c.micVisible)
    }

    // ------------------------------------------------------------------ language picker and memory

    @Test fun theKeyboardLanguageIsTheDefaultAndAChoiceIsRememberedPerKeyboardLanguage() {
        val h = H(lang = "en")
        h.c.onMicTapped()
        assertEquals("en-US", h.input.tag)
        h.c.openPicker()
        assertTrue(h.input.cancelled) // no listening while choosing
        assertEquals(VoiceState.Ready, h.c.state)
        h.c.selectLanguage("en-GB")
        assertEquals("en-GB", h.prefs.chosen("en"))
        assertEquals("en-GB", h.input.tag)
        h.c.cancel()
        h.c.onMicTapped()
        assertEquals("en-GB", h.input.tag) // remembered
        h.c.cancel()
        h.host.keyboardLanguage = "am"
        h.c.onMicTapped()
        assertEquals("am-ET", h.input.tag) // per keyboard language
        assertNull(h.prefs.chosen("am"))
    }

    @Test fun anUnsupportedChoiceIsExplainedButNotRemembered() {
        val h = H(lang = "am")
        h.c.onMicTapped()
        h.c.openPicker()
        h.c.selectLanguage("ti-ET")
        assertEquals(VoiceState.Failed(VoiceErrorKind.LANGUAGE_NOT_SUPPORTED, "ti-ET"), h.c.state)
        assertNull(h.prefs.chosen("am"))
    }

    @Test fun thePickerListsTheFourLanguagesWithTheirSupportAndMoreLanguages() {
        val h = H(lang = "am")
        h.c.onMicTapped()
        h.c.openPicker()
        assertFalse(h.c.pickerLoading)
        val options = h.c.languageOptions()
        assertEquals(listOf("am-ET", "ti-ET", "om-ET", "en-US"), options.map { it.tag })
        assertEquals(
            listOf(SupportLevel.ONLINE, SupportLevel.UNSUPPORTED, SupportLevel.ONLINE, SupportLevel.ONLINE),
            options.map { it.support },
        )
        assertEquals(listOf(true, false, false, false), options.map { it.selected })
        assertEquals("Amharic (Ethiopia)", options[0].label)
        assertEquals(listOf("en-GB", "fr-FR"), h.c.moreOptions().map { it.tag }) // sorted by name
        h.c.showMore(true)
        assertTrue(h.c.moreOpen)
        h.c.closePicker()
        assertFalse(h.c.pickerOpen)
        assertEquals(VoiceState.Ready, h.c.state)
        h.c.onMainButton()
        assertEquals(VoiceState.Preparing, h.c.state) // the big button starts again
    }

    @Test fun thePickerWaitsForASlowService() {
        val h = H(lang = "am")
        h.provider.deferLanguages = true
        h.c.onMicTapped()
        h.provider.answerLanguages()
        h.c.openPicker()
        assertFalse(h.c.pickerLoading) // already cached today
        val h2 = H(lang = "am", languages = mapOf("default" to online("am-ET")))
        h2.provider.recognizerList = listOf(RecognizerRef.DEFAULT, GOOGLE)
        h2.provider.languages[GOOGLE.id] = online("ti-ER")
        h2.c.onMicTapped() // the default has am: Google is not asked yet
        h2.provider.deferLanguages = true
        h2.c.openPicker()
        assertTrue(h2.c.pickerLoading)
        h2.provider.answerLanguages()
        assertFalse(h2.c.pickerLoading)
        assertEquals(SupportLevel.ONLINE, h2.c.languageOptions()[1].support) // ti via Google
    }

    @Test fun thePrivacyNoteShowsOnTheFirstUseOnly() {
        val h = H()
        h.c.onMicTapped()
        assertTrue(h.c.showNotice)
        assertTrue(h.prefs.noticeShown)
        h.c.cancel()
        assertFalse(h.c.showNotice)
        h.c.onMicTapped()
        assertFalse(h.c.showNotice)
    }

    @Test fun englishIsSpacedAndCapitalisedAtInsertion() {
        val h = H(lang = "en")
        h.host.before = "Done."
        h.host.capital = true
        h.c.onMicTapped()
        h.listener.onReady()
        h.listener.onFinal("see you soon")
        assertEquals(listOf(" See you soon"), h.host.committed)
        h.host.keyboardLanguage = "am"
        h.host.before = "ሰላም"
        h.c.onMicTapped()
        h.listener.onFinal("ነው")
        assertEquals(" ነው", h.host.committed.last())
    }
}
