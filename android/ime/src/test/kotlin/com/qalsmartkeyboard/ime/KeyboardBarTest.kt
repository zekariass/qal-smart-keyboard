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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The one bar above the keys: its states (idle, composing, AI open, chooser, sensitive field) and its fixed height. */
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
        /** Every text view of the row in order (the direction control is a group of three: source, swap, destination). */
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

    @Test fun idleShowsPillAiEntryAndGearOnOneBar() {
        val h = H()
        assertEquals(barPx, h.layout())
        assertEquals(listOf(Target.Pill, Target.Ai, Target.Gear), h.targets())
        assertEquals(AiEntry.CLOSED, h.strip.aiEntry)
        assertEquals(View.GONE, h.row().visibility)
        assertEquals("no AI row or panel above the bar when idle", View.GONE, h.ai.visibility)
        assertEquals(0, h.actions.refreshCount) // showing the keyboard never refreshes (D40)
    }

    @Test fun withoutAPillTheAiEntryStartsAtTheLeft() {
        val h = H()
        h.strip.setModes(emptyList(), LayoutKind.ENGLISH)
        h.layout()
        assertEquals(listOf(Target.Ai, Target.Gear), h.targets())
        assertTrue(h.strip.slots().first().shape.left < 12f * d)
    }

    @Test fun openingTheAiEntryShowsTheActionsInTheMiddleWithoutSendingText() {
        val h = H()
        h.strip.activate(Target.Ai)
        assertEquals(barPx, h.layout())
        assertTrue(h.bar.aiOpen)
        assertEquals(AiEntry.OPEN, h.strip.aiEntry)
        assertEquals(View.VISIBLE, h.row().visibility)
        // ADR 0031: the direction control [ Auto · English ⇄ Amharic ] (the swap is an icon), Translate, Assist, Email, ...
        assertEquals(listOf("Auto · English", "", "Amharic", "Translate", "Assist", "Email", "Grammar", "Rewrite", "Natural"), h.rowTexts())
        // The row sits between the AI close button and the gear, as tall as the bar.
        val (start, end) = h.strip.middleRange(widthPx)
        assertEquals(start.toInt(), h.row().left)
        assertEquals(end.toInt() - start.toInt(), h.row().width)
        assertEquals(barPx, h.row().height)
        val gear = h.strip.slots().first { it.target == Target.Gear }
        assertTrue(h.row().right <= gear.hit.left + 1)
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

    @Test fun theCloseButtonClosesTheActionsAndAnOpenPicker() {
        val h = H()
        h.strip.activate(Target.Ai)
        h.rowChip("Amharic").performClick()
        assertEquals(AiPicker.DESTINATION, h.controller.picker)
        assertEquals(View.VISIBLE, h.ai.visibility)
        h.strip.activate(Target.Ai) // now the close button
        assertFalse(h.bar.aiOpen)
        assertEquals(AiEntry.CLOSED, h.strip.aiEntry)
        assertEquals(AiPicker.NONE, h.controller.picker)
        assertEquals(View.GONE, h.row().visibility)
        assertEquals(View.GONE, h.ai.visibility)
    }

    @Test fun composingPutsCandidatesInTheMiddleAndClosesTheActions() {
        val h = H()
        h.strip.activate(Target.Ai)
        h.strip.show(cands("ሰላም", "ሰላማ"), isDraft = true)
        assertEquals(barPx, h.layout())
        assertFalse(h.bar.aiOpen)
        assertFalse(h.strip.showsAiEntry)
        assertEquals(View.GONE, h.row().visibility)
        assertEquals(listOf(Target.Pill, Target.DraftTag, Target.Candidate(0), Target.Candidate(1), Target.Gear), h.targets())
        // The word is done: the AI entry is back, closed.
        h.strip.show(emptyList(), isDraft = true)
        h.layout()
        assertEquals(listOf(Target.Pill, Target.Ai, Target.Gear), h.targets())
        assertEquals(AiEntry.CLOSED, h.strip.aiEntry)
    }

    @Test fun theModeChooserAndTheActionsShareTheMiddleOneAtATime() {
        val h = H()
        h.strip.activate(Target.Pill)
        assertTrue(h.strip.isModeChooserOpen)
        assertEquals(listOf(Target.Pill, Target.Mode(LayoutKind.FIDEL), Target.Mode(LayoutKind.PHONETIC), Target.Mode(LayoutKind.ABC), Target.Gear), h.targets())
        h.strip.activate(Target.Pill)
        h.strip.activate(Target.Ai)
        assertTrue(h.bar.aiOpen)
        h.strip.activate(Target.Pill) // opening the chooser closes the actions
        assertFalse(h.bar.aiOpen)
        assertTrue(h.strip.isModeChooserOpen)
        assertEquals(View.GONE, h.row().visibility)
    }

    @Test fun sensitiveFieldsShowNoAiNoCandidatesOnlyTheGear() {
        val h = H()
        h.strip.activate(Target.Ai)
        val password = InputProfile.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.IME_ACTION_DONE, false, 0)
        h.bar.closeAi()
        h.controller.onFieldStarted(password, "com.example.bank")
        h.strip.setModes(emptyList(), LayoutKind.ABC) // the controller offers no writing modes there
        h.strip.show(emptyList(), isDraft = false)
        assertEquals(barPx, h.layout())
        assertEquals(AiEntry.NONE, h.strip.aiEntry)
        assertEquals(listOf(Target.Gear), h.targets())
        assertEquals(View.GONE, h.row().visibility)
        assertEquals(View.GONE, h.ai.visibility)
        // Even a stray toggle cannot open AI here.
        h.bar.setAiOpen(true)
        assertFalse(h.bar.aiOpen)
        assertTrue(h.actions.submitted.isEmpty())
    }

    @Test fun everySensitiveKindHidesTheAiEntry() {
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
            h.controller.onFieldStarted(InputProfile.from(t, 0, false, 0), "x")
            h.layout()
            assertEquals("inputType $t", AiEntry.NONE, h.strip.aiEntry)
        }
        val h = H()
        h.controller.onFieldStarted(InputProfile.from(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, false, 0), "x")
        assertEquals("incognito", AiEntry.NONE, h.strip.aiEntry)
    }

    @Test fun signedOutLinkComesFirstIsWholeAndOpensSignIn() {
        val h = H()
        h.actions.availability = AiAvailability(block = AiBlock.SIGNED_OUT)
        h.strip.activate(Target.Ai)
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
        h.strip.activate(Target.Ai)
        h.rowChip("Translate").performClick()
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
        h.strip.show(emptyList(), false); h.strip.activate(Target.Ai); heights += h.layout()
        h.strip.activate(Target.Pill); heights += h.layout()
        h.strip.setModes(emptyList(), LayoutKind.ENGLISH); heights += h.layout()
        h.controller.onFieldStarted(InputProfile.from(InputType.TYPE_CLASS_NUMBER, 0, false, 0), null); heights += h.layout()
        assertEquals(listOf(barPx), heights.distinct())
    }

    @Test fun aNewFieldStartsWithTheActionsClosed() {
        val h = H()
        h.strip.activate(Target.Ai)
        h.bar.closeAi()
        h.controller.onFieldStarted(InputProfile.DEFAULT, "com.other")
        assertFalse(h.bar.aiOpen)
        assertEquals(AiEntry.CLOSED, h.strip.aiEntry)
    }

    @Test fun beforeTheAiIsAttachedThereIsNoEntry() {
        val strip = CandidateStripView(ctx)
        val ai = AiToolbarView(ctx)
        KeyboardBar(ctx, strip, ai)
        assertEquals(AiEntry.NONE, strip.aiEntry)
    }
}
