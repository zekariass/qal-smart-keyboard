package com.qalsmartkeyboard.ime

import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import com.qalsmartkeyboard.ime.CandidateStripView.Target
import com.qalsmartkeyboard.ime.ai.AiAvailability
import com.qalsmartkeyboard.ime.ai.AiBlock
import com.qalsmartkeyboard.ime.ai.AiLink
import com.qalsmartkeyboard.ime.ai.AiPanel
import com.qalsmartkeyboard.ime.ai.AiQuickAction
import com.qalsmartkeyboard.ime.ai.AiTaskKind
import com.qalsmartkeyboard.ime.ai.AiToolbarController
import com.qalsmartkeyboard.ime.ai.AiToolbarView
import com.qalsmartkeyboard.ime.ai.EditorAiAccess
import com.qalsmartkeyboard.ime.ai.FakeAiActions
import com.qalsmartkeyboard.ime.toolbar.Tool
import com.qalsmartkeyboard.ime.toolbar.ToolContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The one bar above the keys (ADR 0035, 0041): its states (idle, composing, chooser, tool panel, sensitive field) and its fixed height. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class KeyboardBarTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val d = ctx.resources.displayMetrics.density
    private val widthPx = (411 * d).toInt()

    private inner class H(text: String = "Hello there.") {
        val actions = FakeAiActions()
        val ed = FakeEditor(text)
        val adapter = InputConnectionAdapter { ed }.also { it.startSession(false, text.length, text.length) }
        val strip = CandidateStripView(ctx)
        val ai = AiToolbarView(ctx)
        val controller = AiToolbarController(
            actions = actions,
            editor = EditorAiAccess(adapter) { adapter.finishComposition() },
            post = { it() },
            copyToClipboard = {},
            onChanged = { ai.render() },
        )
        val bar = KeyboardBar(ctx, strip, ai)
        val tools = ArrayList<Tool>()

        init {
            ai.controller = controller
            bar.onTool = { tools += it }
            controller.onFieldStarted(InputProfile.DEFAULT, "com.example.chat")
            strip.setModes(listOf(LayoutKind.PHONETIC, LayoutKind.FIDEL, LayoutKind.ABC), LayoutKind.PHONETIC)
            layout()
        }

        /** Measures and lays out the bar at phone width; returns the bar height. */
        fun layout(): Int {
            bar.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            bar.layout(0, 0, bar.measuredWidth, bar.measuredHeight)
            return bar.measuredHeight
        }

        fun targets() = strip.slots().map { it.target }
    }

    private val barPx get() = (CandidateStripView.HEIGHT_DP * d).toInt()
    private fun cands(vararg s: String) = s.map { Suggestion(it, SuggestionKind.TRANSLITERATION) }

    private fun shortcutTools(h: H) = h.targets().filterIsInstance<Target.Shortcut>().map { it.tool }

    private fun locks(h: H) = h.strip.slots().filter { it.target is Target.Shortcut }.associate { (it.target as Target.Shortcut).tool to it.locked }

    @Test fun idleShowsToolsPillAndPinnedShortcutsOnOneBar() {
        val h = H()
        assertEquals(barPx, h.layout())
        val t = h.targets()
        assertEquals(Target.Tools, t[0])
        assertEquals(Target.Pill, t[1])
        // Every shortcut of the field is on the bar; what does not fit scrolls in on a sideways drag.
        assertEquals(listOf(Tool.TRANSLATE, Tool.AI, Tool.CLIPBOARD, Tool.SETTINGS, Tool.THEME, Tool.EMOJI), shortcutTools(h))
        assertEquals("no panel above the bar when idle", View.GONE, h.ai.visibility)
        assertEquals("the bar is the strip alone (the AI row is retired, ADR 0041)", 1, h.bar.childCount)
        assertEquals(0, h.actions.refreshCount) // showing the keyboard never refreshes (D40)
    }

    @Test fun everyIconButtonIsAFullHeight48dpTarget() {
        val h = H()
        h.strip.micShown = true
        h.layout()
        // Fixed buttons are full 48 dp targets; a shortcut's touch target is its visible part (the rest is scrolled off).
        for (s in h.strip.slots().filter { it.target == Target.Tools || it.target == Target.Mic }) {
            assertTrue("${s.target} ${s.hit.width() / d} dp", s.hit.width() >= 48f * d - 0.5f)
            assertEquals(barPx.toFloat(), s.hit.height(), 1f)
        }
        // Shortcuts are evenly spaced 48 dp slots; their (clipped) targets never overlap the mic at the right end.
        val shortcuts = h.strip.slots().filter { it.target is Target.Shortcut }
        val widths = shortcuts.map { it.shape.width() }
        assertTrue(widths.max() - widths.min() < 0.5f)
        assertTrue(widths.first() >= 48f * d - 0.5f && widths.first() <= CandidateStripView.MAX_SHORTCUT_DP * d + 0.5f)
        for (s in shortcuts) assertEquals("${s.target} height", barPx.toFloat(), s.hit.height(), 1f)
        val mic = h.strip.slots().last()
        assertEquals(Target.Mic, mic.target)
        assertEquals(widthPx.toFloat(), mic.hit.right, 0.5f)
        assertTrue(shortcuts.last().hit.right <= mic.hit.left + 0.5f)
    }

    /** Owner request: nothing drops off the bar when it does not fit — the middle scrolls sideways on a drag. */
    @Test fun whenShortcutsDoNotFitTheyScrollSidewaysInsteadOfDropping() {
        val h = H()
        h.strip.micShown = true
        val narrow = (320 * d).toInt()
        h.bar.measure(View.MeasureSpec.makeMeasureSpec(narrow, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        h.bar.layout(0, 0, narrow, h.bar.measuredHeight)
        // Every tool of the field is a slot: Theme and Emoji included, off the right edge at first.
        assertEquals(listOf(Tool.TRANSLATE, Tool.AI, Tool.CLIPBOARD, Tool.SETTINGS, Tool.THEME, Tool.EMOJI), shortcutTools(h))
        assertTrue(h.strip.slots().first { it.target == Target.Shortcut(Tool.EMOJI) }.hit.isEmpty)
        assertTrue(h.strip.slots().first { it.target == Target.Shortcut(Tool.TRANSLATE) }.hit.width() > 0f)
        // A sideways drag across the middle scrolls them into reach (no tool tap fires).
        fun touch(action: Int, x: Float) = h.strip.onTouchEvent(
            android.view.MotionEvent.obtain(0L, 0L, action, x, (barPx / 2).toFloat(), 0),
        )
        val (midStart, midEnd) = h.strip.middleRange()
        touch(android.view.MotionEvent.ACTION_DOWN, midEnd - 8f)
        touch(android.view.MotionEvent.ACTION_MOVE, midEnd - 60f) // past the slop: the sideways drag starts
        touch(android.view.MotionEvent.ACTION_MOVE, midStart - 200f) // drag far past the end: clamps at the last icon
        assertTrue("a full drag reaches the last icon", h.strip.slots().first { it.target == Target.Shortcut(Tool.EMOJI) }.hit.width() >= 48f * d - 0.5f)
        assertTrue("the first icon scrolled off", h.strip.slots().first { it.target == Target.Shortcut(Tool.TRANSLATE) }.hit.isEmpty)
        touch(android.view.MotionEvent.ACTION_UP, midStart - 200f)
        assertTrue(h.tools.isEmpty())
    }

    /** The settings gear stays a bar shortcut beside the am/ti mode pill at every width (a drag away at most). */
    @Test fun theSettingsGearStaysOnTheBarBesideThePill() {
        for (dp in listOf(320, 360, 384, 411)) {
            val h = H()
            h.strip.micShown = true
            val w = (dp * d).toInt()
            h.bar.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            h.bar.layout(0, 0, w, h.bar.measuredHeight)
            assertTrue("$dp dp: pill shown", Target.Pill in h.targets())
            assertTrue("$dp dp: ${shortcutTools(h)}", Tool.SETTINGS in shortcutTools(h))
            assertEquals("$dp dp", listOf(Tool.TRANSLATE, Tool.AI), shortcutTools(h).take(2))
            // Every shortcut keeps its 48 dp cell (its visible touch target may be smaller while scrolled off).
            for (s in h.strip.slots().filter { it.target is Target.Shortcut }) assertTrue("$dp dp", s.shape.width() >= 48 * d - 0.5f)
        }
    }

    @Test fun withoutAPillTheShortcutsFollowTheToolsButton() {
        val h = H()
        h.strip.setModes(emptyList(), LayoutKind.ENGLISH)
        h.layout()
        assertEquals(Target.Tools, h.targets().first())
        assertEquals(listOf(Tool.TRANSLATE, Tool.AI, Tool.CLIPBOARD, Tool.SETTINGS, Tool.THEME, Tool.EMOJI), shortcutTools(h))
    }

    @Test fun theAiShortcutGoesToTheToolsControllerAndSendsNothing() {
        // ADR 0041: the service routes it (ToolsController.activate) to the AI tools grid in place of the keys.
        val h = H()
        h.strip.activate(Target.Shortcut(Tool.AI))
        assertEquals(listOf(Tool.AI), h.tools)
        assertEquals(barPx, h.layout())
        assertTrue(h.actions.submitted.isEmpty())
        assertFalse(h.ed.calls.any { it == "getTextBeforeCursor" || it == "getSelectedText" })
        assertEquals(View.GONE, h.ai.visibility)
    }

    @Test fun theTranslateShortcutGoesToTheService() {
        // ADR 0037: the bar routes the shortcut to the service (ToolHost.openTranslate), which opens the translate panel.
        val h = H()
        h.strip.activate(Target.Shortcut(Tool.TRANSLATE))
        assertEquals(listOf(Tool.TRANSLATE), h.tools)
        assertTrue(h.actions.submitted.isEmpty())
    }

    @Test fun composingTurnsTheBarIntoTheSuggestionListWithABackChevron() {
        val h = H()
        h.strip.show(cands("ሰላም", "ሰላማ"))
        assertEquals(barPx, h.layout())
        // Owner request: the whole bar is the suggestion list while composing — the back chevron takes the left end
        // (where the tools button was), then every candidate as a full word.
        assertEquals(listOf(Target.Back, Target.Candidate(0), Target.Candidate(1)), h.targets())
        // The chevron brings the tools bar back while composing.
        h.strip.activate(Target.Back)
        h.layout()
        assertEquals(Target.Tools, h.targets().first())
        assertTrue(Target.Pill in h.targets())
        // Typing on (a new list) restores the suggestions; the word done leaves the tools bar.
        h.strip.show(cands("ሰላም", "ሰላማ", "ሰለ"))
        assertEquals(Target.Back, h.targets().first())
        h.strip.show(emptyList())
        h.layout()
        assertEquals(listOf(Tool.TRANSLATE, Tool.AI, Tool.CLIPBOARD), shortcutTools(h).take(3))
    }

    @Test fun thePillCyclesPhoneticFidelEnglishPhonetic() {
        // Owner request: the mode pill is a toggle, not a dropdown — each tap moves to the next mode and
        // the middle keeps its shortcuts (no option buttons appear).
        val h = H()
        val picked = ArrayList<LayoutKind>()
        h.strip.onModeSelected = { picked += it }
        val modes = listOf(LayoutKind.PHONETIC, LayoutKind.FIDEL, LayoutKind.ABC)
        h.strip.activate(Target.Pill)
        h.strip.setModes(modes, LayoutKind.FIDEL)
        h.strip.activate(Target.Pill)
        h.strip.setModes(modes, LayoutKind.ABC)
        h.strip.activate(Target.Pill)
        assertEquals(listOf(LayoutKind.FIDEL, LayoutKind.ABC, LayoutKind.PHONETIC), picked)
        assertEquals(listOf(Tool.TRANSLATE, Tool.AI), shortcutTools(h).take(2)) // no chooser in the middle
        // The tools panel's Writing mode tile cycles the same way.
        h.strip.setModes(modes, LayoutKind.PHONETIC)
        h.strip.cycleMode()
        assertEquals(LayoutKind.FIDEL, picked.last())
    }

    @Test fun anOpenToolPanelLeavesOnlyBackAndTitle() {
        val h = H()
        h.strip.micShown = true
        h.strip.panelTitle = "AI tools"
        assertEquals(barPx, h.layout())
        assertEquals(listOf(Target.Tools, Target.Title), h.targets())
        assertEquals("AI tools", h.strip.slots()[1].label)
        h.strip.panelTitle = null
        h.layout()
        assertEquals(Target.Mic, h.targets().last())
    }

    @Test fun sensitiveFieldsShowNoAiNoCandidatesNoClipboardOrEmoji() {
        val h = H()
        val password = InputProfile.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.IME_ACTION_DONE, false, 0)
        h.bar.fieldContext = { ToolContext(password) }
        h.controller.onFieldStarted(password, "com.example.bank")
        h.strip.setModes(emptyList(), LayoutKind.ABC) // the controller offers no writing modes there
        h.strip.show(emptyList())
        assertEquals(barPx, h.layout())
        assertEquals(listOf(Target.Tools, Target.Shortcut(Tool.SETTINGS), Target.Shortcut(Tool.THEME)), h.targets())
        assertFalse(h.bar.toolContext().aiReady)
        assertEquals(View.GONE, h.ai.visibility)
        // Even a stray quick action cannot run here.
        h.controller.run(AiQuickAction.GRAMMAR)
        assertTrue(h.actions.submitted.isEmpty())
    }

    @Test fun everySensitiveKindHidesTheAiShortcuts() {
        val kinds = listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME,
            InputType.TYPE_NULL,
        )
        for (t in kinds) {
            val h = H()
            val p = InputProfile.from(t, 0, false, 0)
            h.bar.fieldContext = { ToolContext(p) }
            h.controller.onFieldStarted(p, "x")
            h.layout()
            assertFalse("inputType $t", h.bar.toolContext().aiReady)
            assertTrue("inputType $t", shortcutTools(h).none { it == Tool.TRANSLATE || it == Tool.AI })
        }
        val h = H()
        val incognito = InputProfile.from(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, false, 0)
        h.bar.fieldContext = { ToolContext(incognito) }
        h.controller.onFieldStarted(incognito, "x")
        h.layout()
        assertTrue("incognito: no AI", shortcutTools(h).none { it == Tool.TRANSLATE || it == Tool.AI })
        assertFalse("incognito: no clipboard", shortcutTools(h).contains(Tool.CLIPBOARD))
    }

    @Test fun aSignedOutPlanStateLocksTheAiShortcutsButNotTheOthers() {
        val h = H()
        h.actions.availability = AiAvailability(block = AiBlock.SIGNED_OUT)
        h.ai.render()
        h.layout()
        val locked = locks(h)
        assertEquals(true, locked[Tool.TRANSLATE])
        assertEquals(true, locked[Tool.AI])
        assertEquals(false, locked[Tool.CLIPBOARD])
        assertTrue(h.bar.toolContext().aiBlocked)
    }

    @Test fun aPlanWithoutAnyAiLocksTheAiEntryOnly() {
        val h = H()
        h.actions.availability = AiAvailability(entitled = setOf(AiTaskKind.TRANSLATE))
        h.ai.render()
        h.layout()
        assertEquals(false, locks(h)[Tool.TRANSLATE])
        assertEquals(false, locks(h)[Tool.AI]) // translation is one of the AI tools: the entry still offers it
        h.actions.availability = AiAvailability(entitled = emptySet())
        h.ai.render()
        h.layout()
        assertEquals(true, locks(h)[Tool.AI])
        assertTrue(h.bar.toolContext().aiLocked)
    }

    @Test fun aQuickActionWhileSignedOutShowsTheSignInPanelAboveTheBar() {
        val h = H()
        h.actions.availability = AiAvailability(block = AiBlock.SIGNED_OUT)
        h.controller.run(AiQuickAction.GRAMMAR)
        assertTrue(h.controller.panel is AiPanel.Failed)
        assertEquals(View.VISIBLE, h.ai.visibility)
        assertTrue(h.actions.submitted.isEmpty())
        assertEquals(barPx, h.layout())
        assertEquals(AiLink.SIGN_IN, (h.controller.panel as AiPanel.Failed).failure.link)
    }

    @Test fun theBarKeepsOneHeightAcrossEveryState() {
        val h = H()
        val heights = ArrayList<Int>()
        heights += h.layout()
        h.strip.show(cands("ሰላም")); heights += h.layout()
        h.strip.show(emptyList()); heights += h.layout()
        h.strip.activate(Target.Pill); heights += h.layout()
        h.strip.micShown = true; heights += h.layout()
        h.strip.panelTitle = "Tools"; heights += h.layout()
        h.strip.panelTitle = null
        h.strip.setModes(emptyList(), LayoutKind.ENGLISH); heights += h.layout()
        h.controller.onFieldStarted(InputProfile.from(InputType.TYPE_CLASS_NUMBER, 0, false, 0), null); heights += h.layout()
        assertEquals(listOf(barPx), heights.distinct())
    }

    @Test fun beforeTheAiIsAttachedThereAreNoAiShortcuts() {
        val strip = CandidateStripView(ctx)
        val ai = AiToolbarView(ctx)
        KeyboardBar(ctx, strip, ai)
        strip.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        strip.layout(0, 0, strip.measuredWidth, strip.measuredHeight)
        val tools = strip.slots().mapNotNull { (it.target as? Target.Shortcut)?.tool }
        assertEquals(listOf(Tool.CLIPBOARD, Tool.SETTINGS, Tool.THEME, Tool.EMOJI), tools)
    }
}
