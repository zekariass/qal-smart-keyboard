package com.qalsmartkeyboard.ime.voice

import android.view.View
import android.widget.TextView
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.ai.AiDestination
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

/** The voice panel's "Insert in" chip, its picker and the "Translating…" status (T8.12, ADR 0049 D4). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class VoicePanelInsertChipTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val d = ctx.resources.displayMetrics.density

    private class Insert(
        override var choices: List<AiDestination> = emptyList(),
        override var current: String? = null,
        override var translating: Boolean = false,
    ) : VoicePanelView.InsertLanguageSource {
        val chosen = ArrayList<String?>()
        override fun choose(language: String?) {
            chosen += language
            current = language
        }
    }

    private val english = AiDestination("en", "English", "en")
    private val amharic = AiDestination("am", "Amharic", "am")

    private inner class H(val insert: Insert) {
        val provider = FakeVoiceProvider(languages = mutableMapOf("default" to online("am-ET", "en-US")))
        val c = VoiceController(FakeVoiceHost("am"), provider, VoicePrefs.InMemory(), deviceLocale = { Locale.US }) { panel.render() }
        val panel: VoicePanelView = VoicePanelView(ctx).also {
            it.heightProvider = { (240 * d).toInt() }
            it.insertSource = insert
            it.controller = c
        }

        fun rows() = (0 until panel.pickerList.childCount).map { (panel.pickerList.getChildAt(it) as TextView).text.toString() }
        fun row(prefix: String) = (0 until panel.pickerList.childCount).map { panel.pickerList.getChildAt(it) as TextView }.first { it.text.startsWith(prefix) }
        fun s(id: Int, vararg a: Any) = ctx.getString(id, *a)
    }

    @Test fun theChipIsHiddenWhileThereAreNoChoices() {
        val h = H(Insert())
        assertEquals(View.GONE, h.panel.insertChip.visibility)
        h.c.onMicTapped()
        assertEquals(View.GONE, h.panel.insertChip.visibility)
    }

    @Test fun theChipDefaultsToAsSpokenAndNamesTheCurrentLanguage() {
        val ins = Insert(listOf(english, amharic))
        val h = H(ins)
        assertEquals(View.VISIBLE, h.panel.insertChip.visibility)
        assertEquals(h.s(R.string.voice_insert_chip, "As spoken") + "  ▾", h.panel.insertChip.text.toString())
        assertEquals(h.s(R.string.voice_insert_chip_desc, "As spoken"), h.panel.insertChip.contentDescription)
        ins.current = "en"
        h.panel.render()
        assertEquals("Insert in: English  ▾", h.panel.insertChip.text.toString())
        assertEquals(h.s(R.string.voice_insert_chip_desc, "English"), h.panel.insertChip.contentDescription)
    }

    @Test fun tappingOpensThePickerWithTheCurrentOneMarked() {
        val ins = Insert(listOf(english, amharic), current = "en")
        val h = H(ins)
        h.panel.insertChip.performClick()
        assertEquals(View.VISIBLE, (h.panel.pickerList.parent as View).visibility)
        assertEquals(View.INVISIBLE, h.panel.bodyView.visibility)
        assertEquals(listOf(h.s(R.string.voice_insert_picker_title), "As spoken", "✓ English", "Amharic"), h.rows())
        assertFalse(h.c.pickerOpen) // the language picker stays closed
    }

    @Test fun selectingALanguageCallsTheControllerAndClosesThePicker() {
        val ins = Insert(listOf(english, amharic))
        val h = H(ins)
        h.panel.insertChip.performClick()
        h.row("Amharic").performClick()
        assertEquals(listOf<String?>("am"), ins.chosen)
        assertEquals(View.VISIBLE, h.panel.bodyView.visibility)
        assertEquals("Insert in: Amharic  ▾", h.panel.insertChip.text.toString())
        h.panel.insertChip.performClick()
        assertEquals("✓ Amharic", h.rows().last())
        h.row("As spoken").performClick()
        assertEquals(listOf<String?>("am", null), ins.chosen)
    }

    @Test fun thePickerRowsAreTappableTargetsAtLeast48dpHigh() {
        val h = H(Insert(listOf(english, amharic)))
        h.panel.insertChip.performClick()
        for (i in 1 until h.panel.pickerList.childCount) {
            val v = h.panel.pickerList.getChildAt(i) as TextView
            assertTrue(v.isClickable)
            assertTrue(v.minHeight >= (48 * d).toInt())
        }
    }

    @Test fun theTwoPickersExcludeEachOther() {
        val h = H(Insert(listOf(english, amharic)))
        h.c.onMicTapped()
        h.panel.insertChip.performClick()
        h.panel.chip.performClick()
        assertTrue(h.c.pickerOpen)
        assertTrue(h.rows().first() == h.s(R.string.voice_picker_title))
        h.panel.insertChip.performClick()
        assertFalse(h.c.pickerOpen)
        assertEquals(h.s(R.string.voice_insert_picker_title), h.rows().first())
    }

    @Test fun thePickerClosesWhenTheChoicesGoAway() {
        val ins = Insert(listOf(english))
        val h = H(ins)
        h.panel.insertChip.performClick()
        ins.choices = emptyList()
        h.panel.render()
        assertEquals(View.GONE, h.panel.insertChip.visibility)
        assertEquals(View.VISIBLE, h.panel.bodyView.visibility)
    }

    @Test fun translatingReplacesProcessingInTheStatusLine() {
        val ins = Insert(listOf(english), current = "en")
        val h = H(ins)
        h.c.onMicTapped()
        h.provider.last.listener!!.onReady()
        h.provider.last.listener!!.onPartial("hello")
        h.panel.button.performClick()
        assertEquals(h.s(R.string.voice_processing), h.panel.status.text.toString())
        ins.translating = true
        h.panel.render()
        assertEquals("Translating…", h.panel.status.text.toString())
        ins.translating = false
        h.panel.render()
        assertEquals(h.s(R.string.voice_processing), h.panel.status.text.toString())
    }

    @Test fun theInertControllerShowsNoChipAndNoTranslatingLine() {
        // Without a test source the panel reads the controller itself.
        val provider = FakeVoiceProvider(languages = mutableMapOf("default" to online("am-ET")))
        lateinit var panel: VoicePanelView
        val c = VoiceController(FakeVoiceHost("am"), provider, VoicePrefs.InMemory(), deviceLocale = { Locale.US }) { panel.render() }
        panel = VoicePanelView(ctx).also { it.controller = c }
        assertEquals(c.insertLanguageChoices().isNotEmpty(), panel.insertChip.visibility == View.VISIBLE)
    }
}
