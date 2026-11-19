package com.qalsmartkeyboard.ime.toolbar

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.qalsmartkeyboard.data.KeyboardSettings
import com.qalsmartkeyboard.ime.CoreTestEnv
import com.qalsmartkeyboard.ime.EnabledLanguages
import com.qalsmartkeyboard.ime.FidelVariant
import com.qalsmartkeyboard.ime.KeyKind
import com.qalsmartkeyboard.ime.KeyLayoutChoice
import com.qalsmartkeyboard.ime.LatinLayout
import com.qalsmartkeyboard.ime.LayoutKind
import com.qalsmartkeyboard.ime.SettingsPreferences
import com.qalsmartkeyboard.ime.TypingMode
import com.qalsmartkeyboard.ime.ai.AiTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * ADR 0043: the Layout tile's panel with the real keyboard controller and core: the current layout checked, a choice
 * applied and saved per language, an open composition kept as text, Back leaving the keyboard as it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LayoutPanelTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val d = ctx.resources.displayMetrics.density
    private val keysPx = (250 * d).toInt()

    /** The keyboard as the service wires it: settings store, controller, tools controller and the panel view. */
    private class Rig(initial: KeyboardSettings, ctx: Context, keysPx: Int, languages: EnabledLanguages? = null) {
        private val widthPx = (411 * ctx.resources.displayMetrics.density).toInt()
        var settings = initial
        val saves = ArrayList<(KeyboardSettings) -> KeyboardSettings>()
        val prefs = SettingsPreferences({ settings }, saveSettings = { saves += it })
        val s = CoreTestEnv.Session(languages = languages ?: prefs, prefs = prefs)
        val host = object : ToolHost {
            override fun openTranslate() {}
            override fun runAiTool(tool: AiTool) {}
            override fun openEmoji() {}
            override fun startVoice() {}
            override fun openWordArt() {}
            override fun openWritingModes() {}
            override fun openLanguages() {}
            override fun openTheme() {}
            override fun openSettings() {}
            override fun applyLayout(choice: KeyLayoutChoice) {
                s.c.selectLayout(choice)
                s.ed.deliver(s.adapter)
            }
            override fun closeOtherPanels() {
                s.c.commitComposition()
                s.ed.deliver(s.adapter)
            }
        }
        val tools = ToolsController(host, { ToolContext(s.c.profile, layouts = s.c.layoutOptions().size >= 2) })
        val panel = LayoutPanelView(ctx).also {
            it.heightProvider = { keysPx }
            it.options = { s.c.layoutOptions() }
            it.onChoose = { c -> tools.chooseLayout(c) }
        }

        fun open() {
            tools.toggleTools()
            tools.activate(Tool.LAYOUT)
            panel.render()
            panel.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
        }

        /** The store answers: the saved transforms are applied (as the DataStore does a moment later). */
        fun flush() {
            saves.forEach { settings = it(settings) }
            saves.clear()
        }

        fun topRow(): String = s.c.layout.rows[1].filter { it.kind == KeyKind.CHAR && !it.isSpacer }.joinToString("") { it.text }
    }

    private fun texts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun thePanelListsTheLatinLayoutsWithTheCurrentOneChecked() {
        val r = Rig(KeyboardSettings.DEFAULT, ctx, keysPx)
        r.open()
        assertEquals(ToolPanel.LAYOUT, r.tools.panel)
        assertEquals(keysPx, r.panel.measuredHeight) // the key area's height
        assertEquals(LatinLayout.entries.map { KeyLayoutChoice.Latin(it) }, r.panel.shown.map { it.choice })
        assertEquals(listOf(true, false, false, false, false), r.panel.shown.map { it.selected })
        val shown = texts(r.panel)
        for (name in listOf("QWERTY", "AZERTY", "QWERTZ", "Dvorak", "Colemak", "The usual layout")) assertTrue("$name in $shown", name in shown)
        // TalkBack: one radio button per layout, the current one checked.
        val row = r.panel.rowFor(KeyLayoutChoice.Latin(LatinLayout.QWERTY))!!
        val info = row.createAccessibilityNodeInfo()
        assertEquals("android.widget.RadioButton", info.className)
        assertTrue(info.isCheckable && info.isChecked)
        assertFalse(r.panel.rowFor(KeyLayoutChoice.Latin(LatinLayout.DVORAK))!!.createAccessibilityNodeInfo().isChecked)
    }

    @Test fun choosingAppliesAtOnceKeepsTheTextAndPersistsPerLanguage() {
        val r = Rig(KeyboardSettings.DEFAULT, ctx, keysPx)
        assertEquals(TypingMode("am", LayoutKind.PHONETIC), r.s.c.effectiveMode)
        r.s.type("sel")
        assertTrue(r.s.c.composing)
        val composed = r.s.ed.text.toString()
        r.open()
        // Opening the tools committed the composition as it stood: the text is in the field, nothing is composing.
        assertFalse(r.s.c.composing)
        assertEquals(composed, r.s.ed.text.toString())
        assertEquals("", r.s.ed.composing)
        assertTrue(r.panel.rowFor(KeyLayoutChoice.Latin(LatinLayout.DVORAK))!!.performClick())
        assertEquals(ToolPanel.NONE, r.tools.panel) // back to the keys
        assertEquals("',.pyfgcrl", r.topRow()) // at once, before the store answers
        assertEquals(composed, r.s.ed.text.toString()) // the field text is untouched
        // Typing goes on after the committed text.
        r.s.type("am ")
        assertTrue(r.s.ed.text.toString().startsWith(composed))
        assertFalse(r.s.ed.text.any { it in 'a'..'z' })
        // Saved for Amharic only.
        r.flush()
        assertEquals(mapOf("am" to "DVORAK"), r.settings.latinLayouts)
        assertEquals("',.pyfgcrl", r.topRow())
        r.s.mode(TypingMode("en", LayoutKind.ENGLISH))
        assertEquals("qwertyuiop", r.topRow())
        // A new keyboard session reads it back.
        val again = Rig(r.settings, ctx, keysPx)
        assertEquals("',.pyfgcrl", again.topRow())
    }

    @Test fun aChoiceMadeDirectlyCommitsAnOpenComposition() {
        val r = Rig(KeyboardSettings.DEFAULT, ctx, keysPx)
        r.s.type("selam")
        assertTrue(r.s.c.composing)
        r.s.c.selectLayout(KeyLayoutChoice.Latin(LatinLayout.COLEMAK))
        r.s.ed.deliver(r.s.adapter)
        assertFalse(r.s.c.composing)
        assertEquals("", r.s.ed.composing)
        assertTrue(r.s.ed.text.isNotEmpty())
        assertFalse(r.s.ed.text.any { it in 'a'..'z' }) // committed as converted text, not lost or typed twice
        assertEquals("qwfpgjluy", r.topRow())
    }

    @Test fun backReturnsToTheSameKeyboard() {
        val r = Rig(KeyboardSettings.DEFAULT.copy(latinLayouts = mapOf("am" to "AZERTY")), ctx, keysPx)
        r.s.type("se")
        val before = r.s.c.layout
        r.open()
        assertEquals(listOf(false, true, false, false, false), r.panel.shown.map { it.selected })
        assertTrue(r.tools.back())
        assertEquals(ToolPanel.NONE, r.tools.panel)
        assertEquals("azertyuiop", r.topRow())
        assertEquals(before.rows, r.s.c.layout.rows)
        assertTrue(r.saves.isEmpty())
        // Choosing the current layout changes nothing either.
        r.open()
        r.panel.rowFor(KeyLayoutChoice.Latin(LatinLayout.AZERTY))!!.performClick()
        assertTrue(r.saves.isEmpty())
    }

    @Test fun fidelOffersItsTwoVariants() {
        val r = Rig(KeyboardSettings.DEFAULT, ctx, keysPx)
        r.s.mode(TypingMode("am", LayoutKind.FIDEL))
        r.open()
        assertEquals(FidelVariant.entries.map { KeyLayoutChoice.Fidel(it) }, r.panel.shown.map { it.choice })
        val shown = texts(r.panel)
        assertTrue(shown.toString(), "Two-key grid" in shown && "Long-press grid" in shown)
        r.panel.rowFor(KeyLayoutChoice.Fidel(FidelVariant.LONG_PRESS))!!.performClick()
        assertTrue(r.s.c.layout.rows.flatten().none { it.kind == KeyKind.FIDEL_VOWEL })
        r.flush()
        assertEquals(mapOf("am" to "LONG_PRESS"), r.settings.fidelLayouts)
        // Tigrinya keeps its own choice.
        r.s.mode(TypingMode("ti", LayoutKind.FIDEL))
        assertTrue(r.s.c.layout.rows.flatten().any { it.kind == KeyKind.FIDEL_VOWEL })
    }

    @Test fun arabicHasNoLayoutTile() {
        val r = Rig(KeyboardSettings.DEFAULT, ctx, keysPx, languages = EnabledLanguages { listOf("ar", "en") })
        r.s.mode(TypingMode("ar", LayoutKind.ARABIC))
        assertTrue(r.s.c.layoutOptions().isEmpty())
        assertFalse(Tool.LAYOUT in r.tools.tiles().map { it.tool })
        r.s.mode(TypingMode("en", LayoutKind.ENGLISH))
        assertTrue(Tool.LAYOUT in r.tools.tiles().map { it.tool })
        assertNotNull(r.s.c.layoutOptions().singleOrNull { it.selected })
    }
}
