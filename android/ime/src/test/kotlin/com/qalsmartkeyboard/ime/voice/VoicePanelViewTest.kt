package com.qalsmartkeyboard.ime.voice

import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.qalsmartkeyboard.ime.CandidateStripView
import com.qalsmartkeyboard.ime.CandidateStripView.Target
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.voice.FakeVoiceProvider.Companion.online
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The voice panel and the bar's microphone: fixed height, states, labels, announcements, picker. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class VoicePanelViewTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val d = ctx.resources.displayMetrics.density
    private val widthPx = (411 * d).toInt()
    private val keysPx = (240 * d).toInt()

    private inner class H(lang: String = "am") {
        val host = FakeVoiceHost(lang)
        val provider = FakeVoiceProvider(languages = mutableMapOf("default" to online("am-ET", "en-US", "fr-FR")))
        val c: VoiceController = VoiceController(host, provider, VoicePrefs.InMemory(), deviceLocale = { Locale.US }) { panel.render() }
        val panel: VoicePanelView = VoicePanelView(ctx).also {
            it.heightProvider = { keysPx }
            it.controller = c
        }

        fun layout(): Int {
            panel.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
            return panel.measuredHeight
        }

        val listener get() = provider.last.listener!!
        fun s(id: Int, vararg args: Any) = ctx.getString(id, *args)
    }

    @Test fun thePanelKeepsTheKeyAreaHeightInEveryState() {
        val h = H()
        h.c.onMicTapped()
        assertEquals(keysPx, h.layout())
        h.listener.onReady()
        h.listener.onPartial("ሰላም ሰላም ሰላም ሰላም ሰላም ሰላም ሰላም ሰላም ሰላም ሰላም ሰላም ሰላም ሰላም ሰላም ሰላም ሰላም")
        assertEquals(keysPx, h.layout())
        h.listener.onError(VoiceErrorKind.AUDIO)
        assertEquals(keysPx, h.layout())
        h.c.openPicker()
        assertEquals(keysPx, h.layout())
    }

    @Test fun listeningShowsThePreviewAndAStopButton() {
        val h = H()
        h.c.onMicTapped()
        assertEquals(h.s(R.string.voice_preparing), h.panel.status.text.toString())
        h.listener.onReady()
        assertEquals(h.s(R.string.voice_listening), h.panel.status.text.toString())
        assertEquals(VoicePanelView.MainButton.Mode.STOP, h.panel.button.mode)
        assertEquals(h.s(R.string.voice_stop), h.panel.button.contentDescription)
        h.listener.onPartial("ሰላም")
        assertEquals("ሰላም", h.panel.transcript.text.toString())
        assertEquals(View.VISIBLE, h.panel.transcript.visibility)
        h.listener.onLevel(0.8f)
        assertTrue(h.panel.button.shownLevel > 0.5f) // the ring follows the voice
        h.panel.button.performClick()
        assertEquals(h.s(R.string.voice_processing), h.panel.status.text.toString())
        assertEquals(VoicePanelView.MainButton.Mode.BUSY, h.panel.button.mode)
        assertTrue(h.provider.last.stopped)
    }

    @Test fun stateChangesAreAnnouncedThroughALiveRegion() {
        val h = H()
        assertEquals(ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE, ViewCompat.getAccessibilityLiveRegion(h.panel.status))
    }

    @Test fun anUnsupportedLanguageIsExplainedHonestlyWithTryAgain() {
        val h = H(lang = "ti")
        h.c.onMicTapped()
        val text = h.panel.status.text.toString()
        assertTrue(text, text.startsWith("Tigrinya voice typing isn’t available on this phone’s speech service. Try English"))
        assertEquals(View.VISIBLE, h.panel.retry.visibility)
        assertEquals(h.s(R.string.voice_retry), h.panel.button.contentDescription)
    }

    @Test fun everyErrorHasAPlainMessage() {
        for (k in VoiceErrorKind.entries) {
            for (tag in listOf("am-ET", "en-US")) {
                val m = VoicePanelView.errorText(ctx, k, tag)
                assertTrue("$k $tag", m.isNotBlank() && !m.contains("%"))
            }
        }
        assertTrue(VoicePanelView.errorText(ctx, VoiceErrorKind.LANGUAGE_NOT_SUPPORTED, "en-US").contains("Try another language"))
    }

    @Test fun controlsAreLabelled() {
        val h = H()
        h.c.onMicTapped()
        assertEquals(h.s(R.string.voice_language_chip, "Amharic (Ethiopia)"), h.panel.chip.contentDescription)
        assertEquals(h.s(R.string.voice_back), h.panel.back.contentDescription)
        h.panel.back.performClick()
        assertFalse(h.c.isOpen)
    }

    @Test fun theNoteShowsOnFirstUse() {
        val h = H()
        h.c.onMicTapped()
        assertEquals(View.VISIBLE, h.panel.notice.visibility)
        assertTrue(h.panel.notice.text.toString().contains("doesn’t record or store your voice"))
        h.c.cancel()
        h.c.onMicTapped()
        assertEquals(View.GONE, h.panel.notice.visibility)
    }

    @Test fun thePickerListsLanguagesWithTheirStateAndSelects() {
        val h = H()
        h.c.onMicTapped()
        h.panel.chip.performClick()
        assertTrue(h.c.pickerOpen)
        val rows = (0 until h.panel.pickerList.childCount).map { h.panel.pickerList.getChildAt(it) as TextView }
        val texts = rows.map { it.text.toString() }
        assertTrue(texts.toString(), texts.contains("✓ Amharic (Ethiopia)\n" + h.s(R.string.voice_support_online)))
        assertTrue(texts.toString(), texts.contains("Tigrinya (Ethiopia)\n" + h.s(R.string.voice_support_none)))
        assertTrue(texts.toString(), texts.contains(h.s(R.string.voice_more_languages) + "  ›"))
        rows.first { it.text.startsWith(h.s(R.string.voice_more_languages)) }.performClick()
        val more = (0 until h.panel.pickerList.childCount).map { (h.panel.pickerList.getChildAt(it) as TextView).text.toString() }
        assertTrue(more.toString(), more.any { it.startsWith("French (France)") })
        (0 until h.panel.pickerList.childCount).map { h.panel.pickerList.getChildAt(it) as TextView }
            .first { it.text.startsWith("French (France)") }.performClick()
        assertFalse(h.c.pickerOpen)
        assertEquals("fr-FR", h.provider.last.tag)
    }

    // ------------------------------------------------------------------ the bar's microphone

    @Test fun theBarShowsTheMicAtItsRightEndOnlyWhenOffered() {
        val strip = CandidateStripView(ctx)
        strip.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        strip.layout(0, 0, strip.measuredWidth, strip.measuredHeight)
        assertFalse(strip.slots().any { it.target == Target.Mic })
        strip.micShown = true
        val targets = strip.slots().map { it.target }
        assertEquals(Target.Mic, targets.last()) // ADR 0035: the mic ends the bar (the gear became a shortcut / tile)
        val mic = strip.slots().first { it.target == Target.Mic }
        assertTrue(strip.middleRange().second <= mic.hit.left) // candidates and AI actions end before it
        var taps = 0
        strip.onMic = { taps++ }
        strip.activate(Target.Mic)
        assertEquals(1, taps)
    }

    @Test fun theBarMicIsAnAccessibleButton() {
        val strip = CandidateStripView(ctx)
        strip.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        strip.layout(0, 0, strip.measuredWidth, strip.measuredHeight)
        strip.micShown = true
        var taps = 0
        strip.onMic = { taps++ }
        val provider = strip.accessibilityNodeProvider!!
        val node = provider.createAccessibilityNodeInfo(5)!!
        assertEquals(ctx.getString(R.string.voice_mic), node.contentDescription)
        assertEquals(android.widget.Button::class.java.name, node.className)
        provider.performAction(5, android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null)
        assertEquals(1, taps)
        strip.micActive = true
        assertEquals(ctx.getString(R.string.voice_mic_stop), provider.createAccessibilityNodeInfo(5)!!.contentDescription)
    }
}
