package com.qalsmartkeyboard.ime

import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.TextView
import com.qalsmartkeyboard.ime.CandidateStripView.Target
import com.qalsmartkeyboard.ime.ai.AiToolbarController
import com.qalsmartkeyboard.ime.ai.AiToolbarView
import com.qalsmartkeyboard.ime.ai.EditorAiAccess
import com.qalsmartkeyboard.ime.ai.FakeAiActions
import com.qalsmartkeyboard.ime.toolbar.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * TalkBack: the drawn bar (tools button, pill, shortcuts, AI close, DRAFT tag, candidates, chooser options, mic) and the
 * keys are virtual accessibility nodes with a label, a role and working click actions; the AI action chips are real
 * views with the Button role.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class BarAccessibilityTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val d = ctx.resources.displayMetrics.density
    private val widthPx = (411 * d).toInt()

    private inner class H {
        val actions = FakeAiActions()
        val ed = FakeEditor("Hello there.")
        val adapter = InputConnectionAdapter { ed }.also { it.startSession(false, 12, 12) }
        val strip = CandidateStripView(ctx)
        val ai = AiToolbarView(ctx)
        val controller = AiToolbarController(actions, EditorAiAccess(adapter) { adapter.finishComposition() }, { it() }, {}, onChanged = { ai.render() })
        val bar = KeyboardBar(ctx, strip, ai)
        val events = ArrayList<String>()

        init {
            ai.controller = controller
            controller.onFieldStarted(InputProfile.DEFAULT, "com.example.chat")
            strip.setModes(listOf(LayoutKind.FIDEL, LayoutKind.PHONETIC, LayoutKind.ABC), LayoutKind.PHONETIC)
            strip.onSelect = { events += "select $it" }
            strip.onLongSelect = { events += "add $it" }
            strip.onModeSelected = { events += "mode $it" }
            strip.onTools = { events += "tools" }
            val open = bar.onTool
            bar.onTool = { events += "tool $it"; open(it) }
            layout()
        }

        fun layout() {
            bar.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            bar.layout(0, 0, bar.measuredWidth, bar.measuredHeight)
        }

        val provider: AccessibilityNodeProvider get() = strip.accessibilityNodeProvider!!
        fun node(t: Target): AccessibilityNodeInfo = provider.createAccessibilityNodeInfo(strip.idOf(t))!!
        fun click(t: Target) = provider.performAction(strip.idOf(t), AccessibilityNodeInfo.ACTION_CLICK, null)
    }

    private fun AccessibilityNodeInfo.hasAction(id: Int) = actionList.any { it.id == id }
    private fun AccessibilityNodeInfo.actionLabel(id: Int) = actionList.firstOrNull { it.id == id }?.label?.toString()
    private val button = "android.widget.Button"

    @Test fun theHostListsOneNodePerElement() {
        val h = H()
        val host = h.provider.createAccessibilityNodeInfo(View.NO_ID)!!
        assertEquals(h.strip.slots().size, host.childCount)
        // Tools button, pill and the shortcuts that fit at 411 dp without a mic: all six.
        assertEquals(
            listOf(Target.Tools, Target.Pill) + listOf(Tool.TRANSLATE, Tool.AI_ACTIONS, Tool.CLIPBOARD, Tool.SETTINGS, Tool.THEME, Tool.EMOJI).map { Target.Shortcut(it) },
            h.strip.slots().map { it.target },
        )
    }

    @Test fun pillIsAButtonNamingTheModeAndOpensTheChooser() {
        val h = H()
        val n = h.node(Target.Pill)
        assertEquals(button, n.className)
        assertEquals("Writing mode: Phonetic", n.contentDescription.toString())
        assertTrue(n.isClickable)
        assertEquals("Change writing mode", n.actionLabel(AccessibilityNodeInfo.ACTION_CLICK))
        assertTrue(h.click(Target.Pill))
        assertTrue(h.strip.isModeChooserOpen)
        // The options are radio buttons, the active one checked; a click picks one.
        val fidel = h.node(Target.Mode(LayoutKind.FIDEL))
        val phonetic = h.node(Target.Mode(LayoutKind.PHONETIC))
        assertEquals("android.widget.RadioButton", fidel.className)
        assertEquals("Fidel writing mode", fidel.contentDescription.toString())
        assertTrue(fidel.isCheckable)
        assertFalse(fidel.isChecked)
        assertTrue(phonetic.isChecked)
        assertTrue(h.click(Target.Mode(LayoutKind.FIDEL)))
        assertEquals(listOf("mode FIDEL"), h.events)
        assertFalse(h.strip.isModeChooserOpen)
    }

    @Test fun aiShortcutOpensTheActionsAndTheCloseButtonClosesThem() {
        val h = H()
        val n = h.node(Target.Shortcut(Tool.AI_ACTIONS))
        assertEquals(button, n.className)
        assertEquals("AI tools", n.contentDescription.toString())
        assertTrue(n.hasAction(AccessibilityNodeInfo.ACTION_CLICK))
        assertTrue(h.click(Target.Shortcut(Tool.AI_ACTIONS)))
        assertTrue(h.bar.aiOpen)
        assertEquals(listOf("tool AI_ACTIONS"), h.events)
        assertEquals("Close AI tools", h.node(Target.AiClose).contentDescription.toString())
        assertTrue(h.actions.submitted.isEmpty())
        // The action chips are real views with the Button role and their names.
        h.layout()
        val chips = h.ai.actionsRow.getChildAt(0) as ViewGroup
        // ADR 0037: Translate comes first (it opens the translate panel); every chip is a Button with its name.
        val names = (0 until chips.childCount).map { i ->
            val v = chips.getChildAt(i) as TextView
            val info = v.createAccessibilityNodeInfo()
            assertEquals(button, info.className)
            assertTrue(v.isClickable && v.hasOnClickListeners())
            v.contentDescription.toString()
        }
        assertEquals("Translate", names.first())
        assertTrue(names.containsAll(listOf("Email", "Grammar", "Rewrite")))
        assertTrue(h.click(Target.AiClose))
        assertFalse(h.bar.aiOpen)
    }

    @Test fun candidatesAreButtonsWithTheirTextAndAnAddToDictionaryLongClick() {
        val h = H()
        h.strip.show(listOf(Suggestion("ሰላም", SuggestionKind.TRANSLITERATION), Suggestion("ሰላማ", SuggestionKind.COMPLETION)), isDraft = true)
        h.layout()
        val c0 = h.node(Target.Candidate(0))
        assertEquals(button, c0.className)
        assertEquals("ሰላም", c0.text.toString())
        assertTrue(c0.hasAction(AccessibilityNodeInfo.ACTION_CLICK))
        assertEquals("Add to your dictionary", c0.actionLabel(AccessibilityNodeInfo.ACTION_LONG_CLICK))
        assertTrue(h.provider.performAction(h.strip.idOf(Target.Candidate(1)), AccessibilityNodeInfo.ACTION_CLICK, null))
        assertTrue(h.provider.performAction(h.strip.idOf(Target.Candidate(0)), AccessibilityNodeInfo.ACTION_LONG_CLICK, null))
        assertEquals(listOf("select 1", "add 0"), h.events)
        // The DRAFT tag is read, not clickable.
        val tag = h.node(Target.DraftTag)
        assertTrue(tag.contentDescription.toString().startsWith("Draft suggestions"))
        assertFalse(tag.isClickable)
        assertFalse(h.provider.performAction(h.strip.idOf(Target.DraftTag), AccessibilityNodeInfo.ACTION_CLICK, null))
    }

    @Test fun toolsButtonAndShortcutsAreNamedButtons() {
        val h = H()
        val tools = h.node(Target.Tools)
        assertEquals(button, tools.className)
        assertEquals("Tools", tools.contentDescription.toString())
        assertTrue(h.click(Target.Tools))
        val settings = h.node(Target.Shortcut(Tool.SETTINGS))
        assertEquals(button, settings.className)
        assertEquals("Settings", settings.contentDescription.toString())
        assertTrue(h.click(Target.Shortcut(Tool.SETTINGS)))
        assertEquals("Translate", h.node(Target.Shortcut(Tool.TRANSLATE)).contentDescription.toString())
        assertEquals("Clipboard", h.node(Target.Shortcut(Tool.CLIPBOARD)).contentDescription.toString())
        assertEquals(listOf("tools", "tool SETTINGS"), h.events)
    }

    @Test fun theTranslateShortcutIsSelectedWhileTranslateModeIsOn() {
        // ADR 0037: the active state (accent circle) tells TalkBack the shortcut's mode is on.
        val h = H()
        assertFalse(h.node(Target.Shortcut(Tool.TRANSLATE)).isSelected)
        h.strip.setActiveTools(setOf(Tool.TRANSLATE))
        h.layout()
        assertTrue(h.node(Target.Shortcut(Tool.TRANSLATE)).isSelected)
    }

    @Test fun lockedShortcutsSayTheyAreLocked() {
        val h = H()
        h.actions.availability = com.qalsmartkeyboard.ime.ai.AiAvailability(block = com.qalsmartkeyboard.ime.ai.AiBlock.SIGNED_OUT)
        h.ai.render()
        h.layout()
        assertEquals("Translate, locked", h.node(Target.Shortcut(Tool.TRANSLATE)).contentDescription.toString())
        assertEquals("Clipboard", h.node(Target.Shortcut(Tool.CLIPBOARD)).contentDescription.toString())
    }

    @Test fun anOpenToolPanelLeavesABackButtonAndAHeading() {
        val h = H()
        h.strip.panelTitle = "Tools"
        h.layout()
        assertEquals(listOf(Target.Tools, Target.Title), h.strip.slots().map { it.target })
        assertEquals("Back to keyboard", h.node(Target.Tools).contentDescription.toString())
        val title = h.node(Target.Title)
        assertEquals("Tools", title.text.toString())
        assertTrue(title.isHeading)
        assertFalse(title.isClickable)
        assertTrue(h.click(Target.Tools))
        assertEquals(listOf("tools"), h.events)
    }

    @Test fun micIsAButtonNamedForItsState() {
        val h = H()
        h.strip.micShown = true
        h.layout()
        assertEquals(Target.Mic, h.strip.slots().last().target)
        assertEquals("Voice typing", h.node(Target.Mic).contentDescription.toString())
        h.strip.micActive = true
        assertEquals("Stop voice typing and insert the text", h.node(Target.Mic).contentDescription.toString())
    }

    @Test fun nodeBoundsMatchTheTouchTargets() {
        val h = H()
        for (s in h.strip.slots()) {
            val r = android.graphics.Rect()
            h.node(s.target).getBoundsInParent(r)
            assertEquals("${s.target}", s.hit.left.toInt(), r.left)
            assertTrue("${s.target}", r.width() > 0 && r.height() > 0)
        }
    }

    // ---------------------------------------------------------------- keys

    private class Sink : TextSink {
        val out = StringBuilder()
        override fun apply(update: ComposerUpdate) {
            repeat(update.deleteBefore) { if (out.isNotEmpty()) out.setLength(out.length - 1) }
            out.append(update.commit)
        }
        override fun sendEnterKey() { out.append('\n') }
        override fun performEditorAction(actionId: Int) {}
        override fun wantsAutoCapital(capsFlags: Int) = false
    }

    @Test fun keysAreButtonNodesThatTypeOnAccessibilityClick() {
        val sink = Sink()
        val surface = KeyboardSurface(ctx)
        val controller = KeyboardController(sink) { surface.onLayoutChanged() }
        surface.controller = controller
        surface.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        surface.layout(0, 0, surface.measuredWidth, surface.measuredHeight)
        val provider = surface.accessibilityNodeProvider!!
        val host = provider.createAccessibilityNodeInfo(View.NO_ID)!!
        assertTrue("one node per key", host.childCount > 20)
        val nodes = (0 until 80).mapNotNull { id -> provider.createAccessibilityNodeInfo(id)?.let { id to it } }
            .filter { !it.second.contentDescription.isNullOrEmpty() }
        assertEquals(host.childCount, nodes.size)
        val (qId, q) = nodes.first { it.second.contentDescription.toString() == "q" }
        assertEquals(button, q.className)
        assertTrue(q.hasAction(AccessibilityNodeInfo.ACTION_CLICK))
        assertTrue(provider.performAction(qId, AccessibilityNodeInfo.ACTION_CLICK, null))
        val (spaceId, _) = nodes.first { it.second.contentDescription.toString().startsWith("Space") }
        assertTrue(provider.performAction(spaceId, AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals("q ", sink.out.toString())
        val delete = nodes.first { it.second.contentDescription.toString() == "Delete" }
        assertEquals(button, delete.second.className)
        nodes.firstOrNull { it.second.contentDescription.toString() == "Switch language" }?.let { (_, globe) ->
            assertEquals("Change layout", globe.actionLabel(AccessibilityNodeInfo.ACTION_LONG_CLICK))
        }
        assertTrue(nodes.any { it.second.contentDescription.toString() == "Enter" })
    }
}
