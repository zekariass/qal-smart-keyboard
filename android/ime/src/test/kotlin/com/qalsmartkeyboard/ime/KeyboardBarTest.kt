package com.qalsmartkeyboard.ime

import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.HorizontalScrollView
import android.widget.TextView
import com.qalsmartkeyboard.ime.CandidateStripView.AiEntry
import com.qalsmartkeyboard.ime.CandidateStripView.Target
import com.qalsmartkeyboard.ime.ai.AiAvailability
import com.qalsmartkeyboard.ime.ai.AiBlock
import com.qalsmartkeyboard.ime.ai.AiLink
import com.qalsmartkeyboard.ime.ai.AiPanel
import com.qalsmartkeyboard.ime.ai.AiPicker
import com.qalsmartkeyboard.ime.ai.AiToolbarController
import com.qalsmartkeyboard.ime.ai.AiToolbarView
import com.qalsmartkeyboard.ime.ai.EditorAiAccess
import com.qalsmartkeyboard.ime.ai.FakeAiActions
import com.qalsmartkeyboard.ime.toolbar.Tool
import com.qalsmartkeyboard.ime.toolbar.ToolContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The one bar above the keys (ADR 0035): its states (idle, composing, AI open, chooser, tool panel, sensitive field) and its fixed height. */
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
        val links = ArrayList<AiLink>()
        val controller = AiToolbarController(
            actions = actions,
            editor = EditorAiAccess(adapter) { adapter.finishComposition() },
            post = { it() },
            copyToClipboard = {},
            onChanged = { ai.render() },
        )
        val bar = KeyboardBar(ctx, strip, ai)

        init {
            ai.onLink = { links += it }
            ai.controller = controller
            controller.onFieldStarted(InputProfile.DEFAULT, "com.example.chat")
            strip.setModes(listOf(LayoutKind.FIDEL, LayoutKind.PHONETIC, LayoutKind.ABC), LayoutKind.PHONETIC)
            layout()
        }

        /** Measures and lays out the bar (and the panel) at phone width; returns the bar height. */
        fun layout(): Int {
            bar.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            bar.layout(0, 0, bar.measuredWidth, bar.measuredHeight)
            return bar.measuredHeight
        }

        fun targets() = strip.slots().map { it.target }
        fun row() = ai.actionsRow
        /** Every text view of the row in order. */
        fun rowViews(): List<TextView> {
            fun walk(v: View): List<TextView> = when (v) {
                is TextView -> listOf(v)
                is ViewGroup -> (0 until v.childCount).flatMap { walk(v.getChildAt(it)) }
                else -> emptyList()
            }
            return walk(row().getChildAt(0))
        }
        fun rowTexts(): List<String> = rowViews().map { it.text.toString() }
        fun rowChip(text: String): TextView = rowViews().first { it.text.toString() == text }
    }

    private val barPx get() = (CandidateStripView.HEIGHT_DP * d).toInt()
    private fun cands(vararg s: String) = s.map { Suggestion(it, SuggestionKind.TRANSLITERATION) }

    private fun shortcutTools(h: H) = h.targets().filterIsInstance<Target.Shortcut>().map { it.tool }

    @Test fun idleShowsToolsPillAndPinnedShortcutsOnOneBar() {
        val h = H()
        assertEquals(barPx, h.layout())
        val t = h.targets()
        assertEquals(Target.Tools, t[0])
        assertEquals(Target.Pill, t[1])
        // Translate, AI and Clipboard first, then Settings and Theme while they fit.
        assertEquals(listOf(Tool.TRANSLATE, Tool.AI_ACTIONS, Tool.CLIPBOARD, Tool.SETTINGS), shortcutTools(h).take(4))
        assertEquals(AiEntry.CLOSED, h.strip.aiEntry)
        assertEquals(View.GONE, h.row().visibility)
        assertEquals("no AI row or panel above the bar when idle", View.GONE, h.ai.visibility)
        assertEquals(0, h.actions.refreshCount) // showing the keyboard never refreshes (D40)
    }

    @Test fun everyIconButtonIsAFullHeight48dpTarget() {
        val h = H()
        h.strip.micShown = true
        h.layout()
        for (s in h.strip.slots().filter { it.target is Target.Shortcut || it.target == Target.Tools || it.target == Target.Mic }) {
            assertTrue("${s.target} ${s.hit.width() / d} dp", s.hit.width() >= 48f * d - 0.5f)
            assertEquals(barPx.toFloat(), s.hit.height(), 1f)
        }
        // Shortcuts are evenly spaced, never further apart than 72 dp, and never overlap the mic at the right end.
        val shortcuts = h.strip.slots().filter { it.target is Target.Shortcut }
        val widths = shortcuts.map { it.hit.width() }
        assertTrue(widths.max() - widths.min() < 0.5f)
        assertTrue(widths.first() <= CandidateStripView.MAX_SHORTCUT_DP * d + 0.5f)
        val mic = h.strip.slots().last()
        assertEquals(Target.Mic, mic.target)
        assertEquals(widthPx.toFloat(), mic.hit.right, 0.5f)
        assertTrue(shortcuts.last().hit.right <= mic.hit.left + 0.5f)
    }

    @Test fun narrowScreensShowFewerShortcutsButKeepTranslateAndAi() {
        val h = H()
        h.strip.micShown = true
        val narrow = (320 * d).toInt()
        h.bar.measure(View.MeasureSpec.makeMeasureSpec(narrow, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        h.bar.layout(0, 0, narrow, h.bar.measuredHeight)
        assertEquals(listOf(Tool.TRANSLATE, Tool.AI_ACTIONS), shortcutTools(h).take(2))
        assertTrue(shortcutTools(h).size < 6)
    }

    @Test fun withoutAPillTheShortcutsFollowTheToolsButton() {
        val h = H()
        h.strip.setModes(emptyList(), LayoutKind.ENGLISH)
        h.layout()
        assertEquals(Target.Tools, h.targets().first())
        assertEquals(listOf(Tool.TRANSLATE, Tool.AI_ACTIONS, Tool.CLIPBOARD, Tool.SETTINGS, Tool.THEME, Tool.EMOJI), shortcutTools(h))
    }

    @Test fun theAiShortcutShowsTheActionsInTheMiddleWithoutSendingText() {
        val h = H()
        h.strip.activate(Target.Shortcut(Tool.AI_ACTIONS))
        assertEquals(barPx, h.layout())
        assertTrue(h.bar.aiOpen)
        assertEquals(AiEntry.OPEN, h.strip.aiEntry)
        assertEquals(View.VISIBLE, h.row().visibility)
        assertEquals(listOf(Target.Tools, Target.Pill, Target.AiClose), h.targets())
        // ADR 0037: Translate (opens the translate panel; no direction control in the row any more), Assist, Email, ...
        assertEquals(listOf("Translate", "Assist", "Email", "Grammar", "Rewrite", "Natural"), h.rowTexts())
        // The row sits right after the close button, to the bar's end, as tall as the bar.
        val (start, end) = h.strip.middleRange(widthPx)
        val close = h.strip.slots().first { it.target == Target.AiClose }
        assertEquals(close.hit.right, start, 0.5f)
        assertEquals(start.toInt(), h.row().left)
        assertEquals(end.toInt() - start.toInt(), h.row().width)
        assertEquals(barPx, h.row().height)
        // Opening refreshes the cached plan state (D40) but reads and sends no text.
        assertEquals(1, h.actions.refreshCount)
        assertTrue(h.actions.submitted.isEmpty())
        assertEquals(View.GONE, h.ai.visibility)
        // Only a tap on an action sends.
        h.rowChip("Grammar").performClick()
        assertEquals("Hello there.", h.actions.submitted.single().request.text)
        assertTrue(h.controller.panel is AiPanel.Working)
        assertEquals("the working panel opens above the bar", View.VISIBLE, h.ai.visibility)
        assertEquals("the bar keeps its height", barPx, h.layout())
    }

    @Test fun theTranslateShortcutGoesToTheTranslatePanelNotTheActionsRow() {
        // ADR 0037: the bar routes the shortcut to the service (ToolHost.openTranslate), which opens the translate panel.
        val h = H()
        val tools = ArrayList<Tool>()
        h.bar.onTool = { tools += it }
        h.strip.activate(Target.Shortcut(Tool.TRANSLATE))
        assertEquals(listOf(Tool.TRANSLATE), tools)
        assertFalse(h.bar.aiOpen)
        assertTrue(h.actions.submitted.isEmpty())
        // The row's Translate chip opens the same panel.
        var opened = 0
        h.ai.onOpenTranslate = { opened++ }
        h.bar.setAiOpen(true)
        h.layout()
        h.rowChip("Translate").performClick()
        assertEquals(1, opened)
        assertTrue(h.actions.submitted.isEmpty())
    }

    @Test fun theCloseButtonClosesTheActionsAndAnOpenPicker() {
        val h = H()
        h.strip.activate(Target.Shortcut(Tool.AI_ACTIONS))
        h.rowChip("Natural").performClick()
        assertEquals(AiPicker.TONE, h.controller.picker)
        assertEquals(View.VISIBLE, h.ai.visibility)
        h.strip.activate(Target.AiClose)
        assertFalse(h.bar.aiOpen)
        assertEquals(AiEntry.CLOSED, h.strip.aiEntry)
        assertEquals(AiPicker.NONE, h.controller.picker)
        assertEquals(View.GONE, h.row().visibility)
        assertEquals(View.GONE, h.ai.visibility)
        h.layout()
        assertTrue(h.targets().contains(Target.Shortcut(Tool.AI_ACTIONS)))
    }

    @Test fun composingPutsCandidatesInTheMiddleKeepsToolsAndClosesTheActions() {
        val h = H()
        h.strip.activate(Target.Shortcut(Tool.AI_ACTIONS))
        h.strip.show(cands("ሰላም", "ሰላማ"), isDraft = true)
        assertEquals(barPx, h.layout())
        assertFalse(h.bar.aiOpen)
        assertEquals(View.GONE, h.row().visibility)
        // The tools button stays reachable at the left while composing; the composition is in the centre slot, the
        // other candidate on its left (ADR 0039).
        assertEquals(listOf(Target.Tools, Target.Pill, Target.DraftTag, Target.Candidate(1), Target.Candidate(0)), h.targets())
        // The word is done: the shortcuts are back, the AI closed.
        h.strip.show(emptyList(), isDraft = true)
        h.layout()
        assertEquals(listOf(Tool.TRANSLATE, Tool.AI_ACTIONS, Tool.CLIPBOARD), shortcutTools(h).take(3))
        assertEquals(AiEntry.CLOSED, h.strip.aiEntry)
    }

    @Test fun theModeChooserAndTheActionsShareTheMiddleOneAtATime() {
        val h = H()
        h.strip.activate(Target.Pill)
        assertTrue(h.strip.isModeChooserOpen)
        assertEquals(listOf(Target.Tools, Target.Pill, Target.Mode(LayoutKind.FIDEL), Target.Mode(LayoutKind.PHONETIC), Target.Mode(LayoutKind.ABC)), h.targets())
        h.strip.activate(Target.Pill)
        h.strip.activate(Target.Shortcut(Tool.AI_ACTIONS))
        assertTrue(h.bar.aiOpen)
        h.strip.activate(Target.Pill) // opening the chooser closes the actions
        assertFalse(h.bar.aiOpen)
        assertTrue(h.strip.isModeChooserOpen)
        assertEquals(View.GONE, h.row().visibility)
        // The tools panel's Writing mode tile opens the same chooser.
        h.strip.closeModeChooser()
        h.strip.openModeChooser()
        assertTrue(h.strip.isModeChooserOpen)
    }

    @Test fun anOpenToolPanelLeavesOnlyBackAndTitleAndClosesTheActions() {
        val h = H()
        h.strip.micShown = true
        h.strip.activate(Target.Shortcut(Tool.AI_ACTIONS))
        h.strip.panelTitle = "Clipboard"
        assertEquals(barPx, h.layout())
        assertFalse(h.bar.aiOpen)
        assertEquals(View.GONE, h.row().visibility)
        assertEquals(listOf(Target.Tools, Target.Title), h.targets())
        assertEquals("Clipboard", h.strip.slots()[1].label)
        h.strip.panelTitle = null
        h.layout()
        assertEquals(Target.Mic, h.targets().last())
    }

    @Test fun sensitiveFieldsShowNoAiNoCandidatesNoClipboardOrEmoji() {
        val h = H()
        h.strip.activate(Target.Shortcut(Tool.AI_ACTIONS))
        val password = InputProfile.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.IME_ACTION_DONE, false, 0)
        h.bar.closeAi()
        h.bar.fieldContext = { ToolContext(password) }
        h.controller.onFieldStarted(password, "com.example.bank")
        h.strip.setModes(emptyList(), LayoutKind.ABC) // the controller offers no writing modes there
        h.strip.show(emptyList(), isDraft = false)
        assertEquals(barPx, h.layout())
        assertEquals(AiEntry.NONE, h.strip.aiEntry)
        assertEquals(listOf(Target.Tools, Target.Shortcut(Tool.SETTINGS), Target.Shortcut(Tool.THEME)), h.targets())
        assertEquals(View.GONE, h.row().visibility)
        assertEquals(View.GONE, h.ai.visibility)
        // Even a stray toggle cannot open AI here.
        h.bar.setAiOpen(true)
        assertFalse(h.bar.aiOpen)
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
            assertEquals("inputType $t", AiEntry.NONE, h.strip.aiEntry)
            assertTrue("inputType $t", shortcutTools(h).none { it == Tool.TRANSLATE || it == Tool.AI_ACTIONS })
        }
        val h = H()
        val incognito = InputProfile.from(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, false, 0)
        h.bar.fieldContext = { ToolContext(incognito) }
        h.controller.onFieldStarted(incognito, "x")
        h.layout()
        assertEquals("incognito", AiEntry.NONE, h.strip.aiEntry)
        assertFalse("incognito: no clipboard", shortcutTools(h).contains(Tool.CLIPBOARD))
    }

    @Test fun aSignedOutPlanStateLocksTheAiShortcutsButNotTheOthers() {
        val h = H()
        h.actions.availability = AiAvailability(block = AiBlock.SIGNED_OUT)
        h.ai.render()
        h.layout()
        val locked = h.strip.slots().filter { it.target is Target.Shortcut }.associate { (it.target as Target.Shortcut).tool to it.locked }
        assertEquals(true, locked[Tool.TRANSLATE])
        assertEquals(true, locked[Tool.AI_ACTIONS])
        assertEquals(false, locked[Tool.CLIPBOARD])
        assertTrue(h.bar.toolContext().aiBlocked)
    }

    @Test fun signedOutLinkComesFirstIsWholeAndOpensSignIn() {
        val h = H()
        h.actions.availability = AiAvailability(block = AiBlock.SIGNED_OUT)
        h.strip.activate(Target.Shortcut(Tool.AI_ACTIONS))
        h.layout()
        val first = (h.row().getChildAt(0) as ViewGroup).getChildAt(0) as TextView
        assertEquals("Sign in to use AI", first.text.toString())
        // Never truncated: one line, no ellipsis, laid out at its full text width (the row scrolls).
        assertTrue(h.row() is HorizontalScrollView)
        assertEquals(null, first.ellipsize)
        val full = first.paint.measureText(first.text.toString()) + first.paddingLeft + first.paddingRight
        assertTrue("chip ${first.width} < text $full", first.width >= full - 1)
        assertNotNull(first.layout)
        assertEquals(0, first.layout.getEllipsisCount(0))
        first.performClick()
        assertEquals(listOf(AiLink.SIGN_IN), h.links)
        assertTrue(h.actions.submitted.isEmpty())
        // The actions after it are laid out at full width too: the content scrolls instead of shrinking.
        val content = h.row().getChildAt(0) as ViewGroup
        val sum = (0 until content.childCount).sumOf { content.getChildAt(it).width }
        assertTrue("content ${content.width} holds every chip ($sum)", content.width >= sum)
    }

    @Test fun anActionWhileSignedOutShowsTheSignInPanelAboveTheBar() {
        val h = H()
        h.actions.availability = AiAvailability(block = AiBlock.SIGNED_OUT)
        h.strip.activate(Target.Shortcut(Tool.AI_ACTIONS))
        h.rowChip("Grammar").performClick()
        assertTrue(h.controller.panel is AiPanel.Failed)
        assertEquals(View.VISIBLE, h.ai.visibility)
        assertTrue(h.actions.submitted.isEmpty())
        assertEquals(barPx, h.layout())
    }

    @Test fun theBarKeepsOneHeightAcrossEveryState() {
        val h = H()
        val heights = ArrayList<Int>()
        heights += h.layout()
        h.strip.show(cands("ሰላም"), false); heights += h.layout()
        h.strip.show(emptyList(), false); h.strip.activate(Target.Shortcut(Tool.AI_ACTIONS)); heights += h.layout()
        h.strip.activate(Target.Pill); heights += h.layout()
        h.strip.micShown = true; heights += h.layout()
        h.strip.panelTitle = "Tools"; heights += h.layout()
        h.strip.panelTitle = null
        h.strip.setModes(emptyList(), LayoutKind.ENGLISH); heights += h.layout()
        h.controller.onFieldStarted(InputProfile.from(InputType.TYPE_CLASS_NUMBER, 0, false, 0), null); heights += h.layout()
        assertEquals(listOf(barPx), heights.distinct())
    }

    @Test fun aNewFieldStartsWithTheActionsClosed() {
        val h = H()
        h.strip.activate(Target.Shortcut(Tool.AI_ACTIONS))
        h.bar.closeAi()
        h.controller.onFieldStarted(InputProfile.DEFAULT, "com.other")
        assertFalse(h.bar.aiOpen)
        assertEquals(AiEntry.CLOSED, h.strip.aiEntry)
    }

    @Test fun beforeTheAiIsAttachedThereAreNoAiShortcuts() {
        val strip = CandidateStripView(ctx)
        val ai = AiToolbarView(ctx)
        KeyboardBar(ctx, strip, ai)
        assertEquals(AiEntry.NONE, strip.aiEntry)
        strip.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        strip.layout(0, 0, strip.measuredWidth, strip.measuredHeight)
        val tools = strip.slots().mapNotNull { (it.target as? Target.Shortcut)?.tool }
        assertEquals(listOf(Tool.CLIPBOARD, Tool.SETTINGS, Tool.THEME, Tool.EMOJI), tools)
    }
}
