package com.qalsmartkeyboard.ime.voice

import com.qalsmartkeyboard.ime.editor.EditorBuffer
import com.qalsmartkeyboard.ime.voice.FakeVoiceProvider.Companion.online
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR 0038: dictation into the keyboard's own fields (the translate box, an AI panel field, Word Art's text) goes through
 * the same controller and recogniser as dictation into the app's field, with the text inserted at the field's cursor.
 */
class VoiceTargetTest {
    private inner class H(lang: String = "en") {
        val field = FakeVoiceHost(lang)
        var panelPrepared = 0
        val routed = RoutedVoiceHost(field) { panelPrepared++ }
        val provider = FakeVoiceProvider(languages = mutableMapOf("default" to online("am-ET", "en-US")))
        val c: VoiceController = VoiceController(routed, provider, VoicePrefs.InMemory(noticeShown = true), deviceLocale = { Locale.US }) {
            if (!ctl().isOpen) routed.target = null
        }
        private fun ctl(): VoiceController = c
        val listener get() = provider.last.listener!!

        fun dictateInto(b: EditorBuffer) {
            routed.target = VoiceTarget(b)
            c.onMicTapped()
            if (!c.isOpen) routed.target = null
        }
    }

    private fun buf(text: String, cursor: Int) = EditorBuffer().also {
        it.setText(text)
        it.setSelection(cursor)
    }

    @Test fun theFinalTextGoesInAtTheFieldsCursorNotTheAppsField() {
        val h = H()
        val b = buf("Hello world", 5)
        h.dictateInto(b)
        assertEquals(1, h.panelPrepared)
        assertEquals("the app's field preparation (closing panels) is not run", 0, h.field.beforeCalls)
        h.listener.onReady()
        h.listener.onPartial("my dear")
        assertEquals("a partial result is progress only", "Hello world", b.text)
        h.listener.onFinal("my dear")
        assertEquals("Hello my dear world", b.text)
        assertEquals(13, b.cursor)
        assertTrue(h.field.committed.isEmpty())
        assertFalse(h.c.isOpen)
        assertEquals(null, h.routed.target)
    }

    @Test fun dictationReplacesTheSelection() {
        val h = H()
        val b = buf("I like tea", 0)
        b.setSelection(7, 10)
        h.dictateInto(b)
        h.listener.onReady()
        h.listener.onFinal("coffee")
        assertEquals("I like coffee", b.text)
    }

    @Test fun englishIsCapitalisedAtTheStartOfTheField() {
        val h = H("en")
        val b = buf("", 0)
        h.dictateInto(b)
        h.listener.onReady()
        h.listener.onFinal("good morning")
        assertEquals("Good morning", b.text)
        val after = buf("Hi. ", 4)
        h.dictateInto(after)
        h.listener.onReady()
        h.listener.onFinal("how are you")
        assertEquals("Hi. How are you", after.text)
    }

    @Test fun amharicIsInsertedAsRecognisedWithASpaceBefore() {
        val h = H("am")
        val b = buf("ሰላም", 3)
        h.dictateInto(b)
        assertEquals("am-ET", h.provider.last.tag)
        h.listener.onReady()
        h.listener.onFinal("ነው")
        assertEquals("ሰላም ነው", b.text)
    }

    @Test fun noSpeechAndErrorsInsertNothingAndOfferTryAgain() {
        val h = H()
        val b = buf("abc", 3)
        h.dictateInto(b)
        h.listener.onReady()
        h.listener.onError(VoiceErrorKind.NO_SPEECH)
        assertEquals(VoiceState.Failed(VoiceErrorKind.NO_SPEECH, "en-US"), h.c.state)
        assertEquals("abc", b.text)
        h.c.retry()
        h.listener.onReady()
        h.listener.onError(VoiceErrorKind.NETWORK)
        assertEquals(VoiceErrorKind.NETWORK, (h.c.state as VoiceState.Failed).kind)
        assertEquals("abc", b.text)
        h.c.retry()
        h.listener.onReady()
        h.listener.onFinal("d")
        assertEquals("abc d", b.text)
    }

    @Test fun stoppingKeepsWhatWasHeard() {
        val h = H()
        val b = buf("", 0)
        h.dictateInto(b)
        h.listener.onReady()
        h.listener.onPartial("hello there")
        h.c.stop()
        h.listener.onError(VoiceErrorKind.NO_MATCH) // no final result: the last partial one is used
        assertEquals("Hello there", b.text)
    }

    @Test fun withoutThePermissionTheScreenOpensAndNothingStaysRouted() {
        val h = H()
        h.field.permission = false
        val b = buf("x", 1)
        h.dictateInto(b)
        assertEquals(1, h.field.permissionRequests)
        assertFalse(h.c.isOpen)
        assertEquals(null, h.routed.target)
        // The bar's microphone afterwards dictates into the app's field again.
        h.field.permission = true
        h.c.onMicTapped()
        assertEquals(1, h.field.beforeCalls)
        h.listener.onReady()
        h.listener.onFinal("hi")
        assertEquals(listOf("hi"), h.field.committed)
        assertEquals("x", b.text)
    }

    @Test fun cancelInsertsNothing() {
        val h = H()
        val b = buf("abc", 3)
        h.dictateInto(b)
        h.listener.onReady()
        h.listener.onPartial("never")
        h.c.cancel()
        assertEquals("abc", b.text)
        assertEquals(null, h.routed.target)
    }

    @Test fun onlyLanguagesTheRecogniserReportsAreUsable() {
        val h = H("ti")
        val b = buf("", 0)
        h.dictateInto(b)
        // Tigrinya is not declared by this recogniser: it fails with "not supported" instead of pretending.
        assertEquals(VoiceErrorKind.LANGUAGE_NOT_SUPPORTED, (h.c.state as VoiceState.Failed).kind)
        val options = h.c.languageOptions()
        assertFalse(options.first { VoiceLanguages.languageOf(it.tag) == "ti" }.support.usable)
        assertTrue(options.first { VoiceLanguages.languageOf(it.tag) == "am" }.support.usable)
    }
}
