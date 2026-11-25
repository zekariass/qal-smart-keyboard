package com.qalsmartkeyboard.ime.voice

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.ai.AiDestination
import com.qalsmartkeyboard.ime.ai.AiFailure
import com.qalsmartkeyboard.ime.ai.AiMessages
import com.qalsmartkeyboard.ime.theme.ThemeCatalog
import com.qalsmartkeyboard.ime.voice.FakeVoiceProvider.Companion.online
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The dictation follow-up strip (T8.12, ADR 0049): what each phase shows, the callbacks, TalkBack names, target sizes, privacy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class DictationFollowUpViewTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val d = ctx.resources.displayMetrics.density
    private val widthPx = (360 * d).toInt()

    /** Distinctive: it must never reach a description, a label or a log. */
    private val dictated = "SECRET-DICTATED-TEXT"
    private val amharic = AiDestination(id = "am", label = "Amharic", language = "am")

    private class Recorder : FollowUpActions {
        val calls = ArrayList<String>()
        override fun fixGrammar() { calls += "fix" }
        override fun translate() { calls += "translate" }
        override fun undo() { calls += "undo" }
        override fun dismiss() { calls += "dismiss" }
    }

    private val rec = Recorder()
    private val view = DictationFollowUpView(ctx)

    private fun show(phase: FollowUpPhase) {
        view.bind(DictationFollowUp(dictated, "en", amharic, phase), rec)
    }

    private fun layout(): Int {
        view.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return view.measuredHeight
    }

    private fun text(v: TextView) = v.text.toString()
    private fun s(id: Int, vararg a: Any) = ctx.getString(id, *a)

    @Test fun noFollowUpMeansTheStripIsGone() {
        show(FollowUpPhase.Offer)
        assertEquals(View.VISIBLE, view.visibility)
        view.bind(null, rec)
        assertEquals(View.GONE, view.visibility)
    }

    @Test fun anIdleControllerRendersGone() {
        val provider = FakeVoiceProvider(languages = mutableMapOf("default" to online("en-US")))
        val c = VoiceController(FakeVoiceHost("en"), provider, VoicePrefs.InMemory(), deviceLocale = { Locale.US }) { view.render() }
        view.controller = c
        assertEquals(View.GONE, view.visibility)
        view.controller = null
        assertEquals(View.GONE, view.visibility)
    }

    @Test fun offerShowsFixTranslateAndClose() {
        show(FollowUpPhase.Offer)
        assertEquals(View.VISIBLE, view.fix.visibility)
        assertEquals(View.VISIBLE, view.translate.visibility)
        assertEquals(View.VISIBLE, view.dismiss.visibility)
        assertEquals(View.GONE, view.action.visibility)
        assertEquals(s(R.string.ai_action_grammar), text(view.fix))
        assertEquals(s(R.string.ai_translate_to, "Amharic"), text(view.translate))
        assertEquals("", text(view.message))
    }

    @Test fun theChipsCallTheirActions() {
        show(FollowUpPhase.Offer)
        view.fix.performClick()
        view.translate.performClick()
        view.dismiss.performClick()
        assertEquals(listOf("fix", "translate", "dismiss"), rec.calls)
    }

    @Test fun workingShowsTheActionNameAndNoChips() {
        show(FollowUpPhase.Working(FollowUpAction.GRAMMAR))
        assertEquals(s(R.string.ai_working_grammar), text(view.message))
        assertEquals(View.GONE, view.fix.visibility)
        assertEquals(View.GONE, view.translate.visibility)
        assertEquals(View.GONE, view.action.visibility)
        assertEquals(View.VISIBLE, view.dismiss.visibility)
        show(FollowUpPhase.Working(FollowUpAction.TRANSLATE))
        assertEquals(s(R.string.ai_working_translate, "Amharic"), text(view.message))
    }

    @Test fun doneSaysWhatHappenedAndOffersUndo() {
        show(FollowUpPhase.Done(FollowUpAction.GRAMMAR))
        assertEquals("Fixed", text(view.message))
        assertEquals(View.VISIBLE, view.action.visibility)
        assertEquals(s(R.string.voice_followup_undo), text(view.action))
        view.action.performClick()
        assertEquals(listOf("undo"), rec.calls)
        show(FollowUpPhase.Done(FollowUpAction.TRANSLATE))
        assertEquals("Translated", text(view.message))
        assertEquals(View.GONE, view.fix.visibility)
    }

    @Test fun failedShowsTheExistingShortMessageAndRetriesTheSameAction() {
        show(FollowUpPhase.Failed(AiFailure.Offline, action = FollowUpAction.TRANSLATE))
        assertEquals(AiMessages.failure(ctx, AiFailure.Offline), text(view.message))
        assertEquals(s(R.string.voice_retry), text(view.action))
        assertEquals(View.VISIBLE, view.action.visibility)
        view.action.performClick()
        assertEquals(listOf("translate"), rec.calls)

        show(FollowUpPhase.Failed(AiFailure.Timeout, action = FollowUpAction.GRAMMAR))
        assertEquals(AiMessages.failure(ctx, AiFailure.Timeout), text(view.message))
        view.action.performClick()
        assertEquals(listOf("translate", "fix"), rec.calls)
    }

    @Test fun movedAndUnknownFailuresHaveTheirOwnWording() {
        show(FollowUpPhase.Failed(null, moved = true, action = FollowUpAction.GRAMMAR))
        assertEquals("The text changed - nothing was replaced", text(view.message))
        show(FollowUpPhase.Failed(null, action = FollowUpAction.GRAMMAR))
        assertEquals(s(R.string.ai_err_other), text(view.message))
    }

    @Test fun everyChipHasAPlainDescriptionAndTheButtonRole() {
        for (phase in listOf(FollowUpPhase.Offer, FollowUpPhase.Done(FollowUpAction.GRAMMAR), FollowUpPhase.Failed(AiFailure.Offline, action = FollowUpAction.TRANSLATE))) {
            show(phase)
            for (v in listOf(view.fix, view.translate, view.action, view.dismiss).filter { it.visibility == View.VISIBLE }) {
                assertFalse(v.contentDescription.isNullOrBlank())
                assertTrue(v.isClickable && v.isFocusable)
                val info = android.view.accessibility.AccessibilityNodeInfo.obtain()
                v.onInitializeAccessibilityNodeInfo(info)
                assertEquals(Button::class.java.name, info.className.toString())
            }
        }
        show(FollowUpPhase.Offer)
        assertEquals(s(R.string.voice_followup_translate_desc, "Amharic"), view.translate.contentDescription.toString())
        show(FollowUpPhase.Failed(AiFailure.Offline, action = FollowUpAction.TRANSLATE))
        assertEquals(s(R.string.voice_followup_retry_desc, s(R.string.ai_translate_to, "Amharic")), view.action.contentDescription.toString())
        assertEquals(ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE, ViewCompat.getAccessibilityLiveRegion(view.message))
    }

    @Test fun touchTargetsAreAtLeast48dpAndTheRowKeepsItsHeight() {
        for (phase in listOf(FollowUpPhase.Offer, FollowUpPhase.Done(FollowUpAction.TRANSLATE), FollowUpPhase.Failed(AiFailure.Offline, action = FollowUpAction.GRAMMAR))) {
            show(phase)
            assertEquals((48 * d).toInt(), layout())
            for (v in listOf(view.fix, view.translate, view.action, view.dismiss).filter { it.visibility == View.VISIBLE }) {
                assertTrue("${v.width}x${v.height}", v.height >= (48 * d).toInt() && v.width >= (48 * d).toInt())
            }
        }
    }

    @Test fun aLongFailureAndLongLanguageNamesStayInsideTheRow() {
        view.bind(DictationFollowUp(dictated, "en", amharic.copy(label = "Afaan Oromoo and a very long name"), FollowUpPhase.Offer), rec)
        layout()
        assertTrue(view.dismiss.right <= view.measuredWidth)
        show(FollowUpPhase.Failed(AiFailure.NotEntitled, action = FollowUpAction.GRAMMAR))
        layout()
        assertTrue(view.dismiss.right <= view.measuredWidth)
        assertTrue(view.message.width > 0)
    }

    @Test fun theDictatedTextIsNeverShownDescribedOrPrinted() {
        val phases = listOf(
            FollowUpPhase.Offer,
            FollowUpPhase.Working(FollowUpAction.GRAMMAR),
            FollowUpPhase.Done(FollowUpAction.TRANSLATE),
            FollowUpPhase.Failed(AiFailure.Offline, action = FollowUpAction.TRANSLATE),
        )
        for (phase in phases) {
            val f = DictationFollowUp(dictated, "en", amharic, phase)
            assertFalse(f.toString().contains(dictated))
            view.bind(f, rec)
            val seen = ArrayList<String>()
            fun walk(v: View) {
                v.contentDescription?.let { seen += it.toString() }
                if (v is TextView) seen += v.text.toString()
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(view)
            assertTrue(seen.none { it.contains(dictated) })
        }
    }

    @Test fun everyThemeDrawsEveryPhase() {
        for (theme in ThemeCatalog.all) {
            view.theme = theme
            for (phase in listOf(FollowUpPhase.Offer, FollowUpPhase.Done(FollowUpAction.GRAMMAR), FollowUpPhase.Failed(AiFailure.Offline, action = FollowUpAction.GRAMMAR))) {
                show(phase)
                layout()
                view.draw(Canvas(Bitmap.createBitmap(widthPx, view.measuredHeight, Bitmap.Config.ARGB_8888)))
            }
        }
        assertNotNull(view.background)
    }

    @Test fun everyUiLanguageHasTheNewStrings() {
        val ids = listOf(
            R.string.voice_insert_chip, R.string.voice_insert_chip_desc, R.string.voice_insert_as_spoken, R.string.voice_insert_picker_title,
            R.string.voice_translating, R.string.voice_followup_strip, R.string.voice_followup_fix_desc, R.string.voice_followup_fixed,
            R.string.voice_followup_translated, R.string.voice_followup_undo, R.string.voice_followup_undo_desc, R.string.voice_followup_dismiss,
            R.string.voice_followup_moved,
        )
        for (tag in listOf("en", "am", "ti", "om")) {
            val conf = android.content.res.Configuration(ctx.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
            val c = ctx.createConfigurationContext(conf)
            for (id in ids) assertFalse("$tag $id", c.getString(id).isBlank())
            assertTrue(tag, c.getString(R.string.voice_insert_chip, "X").contains("X"))
            assertTrue(tag, c.getString(R.string.voice_followup_translate_desc, "X").contains("X"))
            assertTrue(tag, c.getString(R.string.voice_followup_retry_desc, "X").contains("X"))
            if (tag != "en") assertFalse(tag, c.getString(R.string.voice_followup_fixed) == "Fixed")
        }
    }
}
