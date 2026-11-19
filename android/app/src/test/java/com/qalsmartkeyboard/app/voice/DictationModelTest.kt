package com.qalsmartkeyboard.app.voice

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.qalsmartkeyboard.ime.voice.RecognizerLanguages
import com.qalsmartkeyboard.ime.voice.RecognizerRef
import com.qalsmartkeyboard.ime.voice.VoiceErrorKind
import com.qalsmartkeyboard.ime.voice.VoiceInput
import com.qalsmartkeyboard.ime.voice.VoiceLanguages
import com.qalsmartkeyboard.ime.voice.VoiceListener
import com.qalsmartkeyboard.ime.voice.VoicePrefs
import com.qalsmartkeyboard.ime.voice.VoiceProvider
import com.qalsmartkeyboard.ime.voice.VoiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR 0038: dictation into the companion app's fields with a fake recogniser (permission, partials, errors, stop). */
class DictationModelTest {
    private class FakeInput : VoiceInput {
        var tag: String? = null
        var listener: VoiceListener? = null
        var stopped = false
        var cancelled = false
        override fun start(languageTag: String, listener: VoiceListener) {
            tag = languageTag
            this.listener = listener
        }
        override fun stop() { stopped = true }
        override fun cancel() { cancelled = true }
    }

    private class FakeProvider(override var available: Boolean = true, val declared: Set<String> = setOf("am-ET", "en-US")) : VoiceProvider {
        val inputs = ArrayList<FakeInput>()
        override fun recognizers() = listOf(RecognizerRef.DEFAULT)
        override fun languages(recognizer: RecognizerRef, done: (RecognizerLanguages?) -> Unit) =
            done(RecognizerLanguages(declared.map { VoiceLanguages.normalize(it) }.toSet(), emptySet()))
        override fun input(recognizer: RecognizerRef): VoiceInput = FakeInput().also { inputs += it }
    }

    private class Field(var value: TextFieldValue) : DictationTarget {
        override fun textBeforeCursor(): CharSequence = DictationText.before(value)
        override fun insert(text: String) {
            value = DictationText.insert(value, text)
        }
    }

    private class H(lang: String = "en", granted: Boolean = true) {
        var granted = granted
        val provider = FakeProvider()
        val m = DictationModel(provider, VoicePrefs.InMemory(noticeShown = true), { this.granted }, { lang }).also { it.servicesKnown = true }
        val listener get() = provider.inputs.last().listener!!
    }

    @Test fun dictatesAtTheCursorAndShowsPartialsAsProgressOnly() {
        val h = H()
        val f = Field(TextFieldValue("Dear team, thanks", TextRange(10)))
        assertTrue(h.m.available)
        h.m.toggle("body", f)
        assertEquals("body", h.m.activeField)
        assertEquals(VoiceState.Preparing, h.m.state)
        assertEquals("en-US", h.provider.inputs.last().tag)
        h.listener.onReady()
        h.listener.onPartial("see you")
        assertEquals(VoiceState.Listening("see you"), h.m.state)
        assertEquals("Dear team, thanks", f.value.text)
        h.listener.onFinal("see you soon")
        assertEquals("Dear team, see you soon thanks", f.value.text)
        assertEquals(TextRange(23), f.value.selection)
        assertEquals(VoiceState.Idle, h.m.state)
        assertNull(h.m.activeField)
    }

    @Test fun theMicAgainStopsAndInsertsWhatWasHeard() {
        val h = H()
        val f = Field(TextFieldValue("", TextRange(0)))
        h.m.toggle("text", f)
        h.listener.onReady()
        h.listener.onPartial("abebe")
        h.m.toggle("text", f)
        assertTrue(h.provider.inputs.last().stopped)
        assertTrue(h.m.state is VoiceState.Processing)
        h.listener.onError(VoiceErrorKind.NO_MATCH)
        assertEquals("Abebe", f.value.text) // English, start of the field: capitalised like the keyboard does
    }

    @Test fun withoutPermissionItAsksThenStarts() {
        val h = H(granted = false)
        val f = Field(TextFieldValue("x", TextRange(1)))
        h.m.toggle("text", f)
        assertTrue(h.m.permissionNeeded)
        assertTrue(h.provider.inputs.isEmpty())
        h.m.onPermissionAsked()
        assertFalse(h.m.permissionNeeded)
        h.granted = true
        h.m.onPermissionResult(true)
        assertEquals(1, h.provider.inputs.size)
        h.listener.onReady()
        h.listener.onFinal("y")
        assertEquals("x y", f.value.text)
    }

    @Test fun aDenialIsExplainedAndNothingListens() {
        val h = H(granted = false)
        val f = Field(TextFieldValue("", TextRange(0)))
        h.m.toggle("text", f)
        h.m.onPermissionResult(false)
        assertTrue(h.m.permissionDenied)
        assertEquals("text", h.m.activeField)
        assertTrue(h.provider.inputs.isEmpty())
        h.m.cancel()
        assertFalse(h.m.permissionDenied)
        assertNull(h.m.activeField)
    }

    @Test fun noSpeechAndNetworkErrorsOfferTryAgain() {
        val h = H()
        val f = Field(TextFieldValue("", TextRange(0)))
        h.m.toggle("text", f)
        h.listener.onReady()
        h.listener.onError(VoiceErrorKind.NO_SPEECH)
        assertEquals(VoiceState.Failed(VoiceErrorKind.NO_SPEECH, "en-US"), h.m.state)
        assertEquals("text", h.m.activeField)
        h.m.retry()
        h.listener.onReady()
        h.listener.onError(VoiceErrorKind.NETWORK)
        assertEquals(VoiceErrorKind.NETWORK, (h.m.state as VoiceState.Failed).kind)
        assertEquals("", f.value.text)
    }

    @Test fun anotherFieldsMicMovesDictationThere() {
        val h = H()
        val a = Field(TextFieldValue("", TextRange(0)))
        val b = Field(TextFieldValue("", TextRange(0)))
        h.m.toggle("a", a)
        val first = h.provider.inputs.last()
        h.m.toggle("b", b)
        assertTrue(first.cancelled)
        assertEquals("b", h.m.activeField)
        h.listener.onReady()
        h.listener.onFinal("hello")
        assertEquals("", a.value.text)
        assertEquals("Hello", b.value.text)
    }

    @Test fun anUndeclaredLanguageIsNotPretendedToWork() {
        val h = H(lang = "ti")
        h.m.toggle("text", Field(TextFieldValue("", TextRange(0))))
        assertEquals(VoiceErrorKind.LANGUAGE_NOT_SUPPORTED, (h.m.state as VoiceState.Failed).kind)
    }

    @Test fun noMicWithoutARecogniserOrWithVoiceTypingOff() {
        val none = DictationModel(FakeProvider(available = false), VoicePrefs.InMemory(), { true }, { "en" }).also { it.servicesKnown = true }
        assertFalse(none.available)
        val off = DictationModel(FakeProvider(), VoicePrefs.InMemory(enabled = false), { true }, { "en" }).also { it.servicesKnown = true }
        assertFalse(off.available)
        val unknown = DictationModel(FakeProvider(), VoicePrefs.InMemory(), { true }, { "en" })
        assertFalse("not before the installed services were read", unknown.available)
    }

    @Test fun insertReplacesTheSelection() {
        val v = DictationText.insert(TextFieldValue("I like tea", TextRange(7, 10)), "coffee")
        assertEquals("I like coffee", v.text)
        assertEquals(TextRange(13), v.selection)
        assertEquals("like", DictationText.before(TextFieldValue("I like tea", TextRange(6))))
    }
}
