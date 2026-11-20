package com.qalsmartkeyboard.ime.ai

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import com.qalsmartkeyboard.ime.CandidateStripView
import com.qalsmartkeyboard.ime.FakeEditor
import com.qalsmartkeyboard.ime.InputConnectionAdapter
import com.qalsmartkeyboard.ime.InputProfile
import com.qalsmartkeyboard.ime.KeyboardController
import com.qalsmartkeyboard.ime.KeyboardInsets
import com.qalsmartkeyboard.ime.KeyboardMetrics
import com.qalsmartkeyboard.ime.KeyboardSurface
import com.qalsmartkeyboard.ime.ComposerUpdate
import com.qalsmartkeyboard.ime.TextSink
import com.qalsmartkeyboard.ime.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import kotlin.math.roundToInt

/**
 * ADR 0044: the AI panels in place of the keys (the AI tools grid, AI Assist, Write Email) are a little taller than the
 * key area, capped to a share of the window (less in landscape), never shorter than the keys; the keys keep their height;
 * content scrolls; the quick actions' buttons all fit at 360 dp in every UI language.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real text measurement: line breaks and button widths as on a device
class AiPanelHeightTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).roundToInt()

    private class Sink : TextSink {
        override fun apply(update: ComposerUpdate) {}
        override fun sendEnterKey() {}
        override fun performEditorAction(actionId: Int) {}
        override fun wantsAutoCapital(capsFlags: Int) = false
    }

    /** An AI panel over a workspace whose state changes re-render it. */
    private fun assist(height: Int): Triple<AiPanelView, AiWorkspace, FakeAiActions> {
        val actions = FakeAiActions()
        val panel = AiPanelView(ctx)
        val ws = AiWorkspace(
            actions, FakeAiEditor("teh cat", "field"), FakeClipboard(ClipAvailability.NONE, null), AiLanguagePrefs.InMemory(),
            post = { it() }, keyboardLanguage = { "en" }, translateDefault = { "en" }, onChanged = { panel.render() },
        )
        panel.workspace = ws
        panel.heightProvider = { height }
        ws.openAssist()
        return Triple(panel, ws, actions)
    }

    private fun keys(): KeyboardSurface {
        val c = KeyboardController(Sink()) {}
        c.startField(InputProfile.DEFAULT)
        return KeyboardSurface(ctx).also { it.controller = c }
    }

    private fun measure(v: View, width: Int, height: Int = 0) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            if (height > 0) View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST) else View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    // ------------------------------------------------------------ the rule

    @Test fun portraitAddsFiftySixToSeventyTwoDpToTheKeyArea() {
        val density = 2.625f
        val window = (891 * density).roundToInt()
        val keys = (260 * density).roundToInt() // five 50 dp rows and the edges
        val inset = KeyboardInsets.padding(intArrayOf(0, 0, 0, 63), intArrayOf(0, 0, 0, 0), density = density).bottom
        val ai = KeyboardMetrics.aiPanelHeightInWindowPx(keys, density, (411 * density).roundToInt(), window, inset)
        assertEquals(keys + (56 * density).roundToInt(), ai) // 18 % of 260 dp is below the 56 dp floor
        // Taller keys (key height 130 %): 18 % of the key area, still at most 72 dp.
        val tall = (338 * density).roundToInt()
        assertEquals(tall + (338 * 0.18f * density).roundToInt(), KeyboardMetrics.aiPanelHeightInWindowPx(tall, density, (411 * density).roundToInt(), window, inset))
        val huge = (500 * density).roundToInt()
        val capped = KeyboardMetrics.aiPanelHeightPx(huge, density, 0, landscape = false)
        assertEquals(huge + (72 * density).roundToInt(), capped)
    }

    @Test fun theWholeKeyboardStaysWithinSixtyPercentOfTheWindowAndNeverShrinks() {
        val density = 2f
        val keys = (260 * density).roundToInt()
        val width = (360 * density).roundToInt()
        // A 360 x 640 dp phone with 3-button navigation: 60 % of 640 = 384 dp, minus the 44 dp bar and 56 dp of insets.
        val window = (640 * density).roundToInt()
        val inset = (56 * density).roundToInt()
        val ai = KeyboardMetrics.aiPanelHeightInWindowPx(keys, density, width, window, inset)
        assertEquals((window * 0.6f).toInt() - (CandidateStripView.HEIGHT_DP * density).roundToInt() - inset, ai)
        assertTrue(ai > keys && ai < keys + (56 * density).roundToInt())
        // The insets count: a taller navigation bar leaves less room.
        assertTrue(KeyboardMetrics.aiPanelHeightInWindowPx(keys, density, width, window, inset + (24 * density).roundToInt()) < ai)
        // A window too short for any extra: the panel keeps the keys' height, never less.
        assertEquals(keys, KeyboardMetrics.aiPanelHeightInWindowPx(keys, density, width, (480 * density).roundToInt(), inset))
        assertEquals(0, KeyboardMetrics.aiPanelHeightPx(0, density, window, false))
    }

    @Test fun landscapeGetsLessOrNothing() {
        val density = 2.625f
        // A phone on its side: the keys already take what the window allows.
        val phoneKeys = (172 * density).roundToInt()
        assertEquals(phoneKeys, KeyboardMetrics.aiPanelHeightInWindowPx(phoneKeys, density, (891 * density).roundToInt(), (411 * density).roundToInt(), dp(8f)))
        // A tablet on its side: 10 % of the key area (at most 32 dp).
        val tabletKeys = (190 * density).roundToInt()
        val tablet = KeyboardMetrics.aiPanelHeightInWindowPx(tabletKeys, density, (1280 * density).roundToInt(), (800 * density).roundToInt(), dp(8f))
        assertEquals(tabletKeys + (19 * density).roundToInt(), tablet)
        assertTrue(tablet - tabletKeys < (32 * density).roundToInt())
    }

    // ------------------------------------------------------------ the views

    @Test fun theKeysKeepTheirHeightAndTheAiPanelsTakeTheAiHeight() {
        val k = keys()
        val width = dp(411f)
        measure(k, width)
        val keyArea = k.preferredHeightPx()
        assertEquals(keyArea, k.measuredHeight) // the typing keyboard is unchanged
        val dm = ctx.resources.displayMetrics
        val ai = KeyboardMetrics.aiPanelHeightInWindowPx(keyArea, dm.density, dm.widthPixels, dm.heightPixels, dp(32f))
        assertEquals(keyArea + dp(56f), ai)
        val grid = AiToolsPanelView(ctx).also {
            it.heightProvider = { ai }
            it.tiles = { AiToolSet.tiles(AiAvailability()) }
            it.render()
        }
        measure(grid, width)
        assertEquals(ai, grid.measuredHeight)
        val panel = assist(ai).first
        measure(panel, width)
        assertEquals(ai, panel.measuredHeight) // the grid and Assist share one height: moving between them never resizes
        // Measuring again (as every layout pass does) gives the same height: no loop.
        measure(panel, width)
        assertEquals(ai, panel.measuredHeight)
        assertEquals(keyArea, k.preferredHeightPx())
    }

    @Test fun aLongResultScrollsInsideThePanel() {
        val (panel, ws, actions) = assist(dp(316f))
        all(panel).filterIsInstance<TextView>().first { it.text.toString() == "Rewrite" }.performClick()
        panel.footerRow!!.let { r -> (0 until r.childCount).map { r.getChildAt(it) as TextView }.first { it.text.toString() == "Rewrite" } }.performClick()
        actions.answer(0, FakeAiActions.ok("A long answer. ".repeat(80)))
        measure(panel, dp(360f))
        assertEquals(dp(316f), panel.measuredHeight)
        val body = all(panel).filterIsInstance<ScrollView>().first()
        assertTrue("a result: ${ws.assist.step}", ws.assist.step is AiAssistStep.Result)
        assertTrue("the body is taller than its window: ${body.getChildAt(0).measuredHeight} > ${body.height}", body.getChildAt(0).measuredHeight > body.height)
        body.scrollTo(0, Int.MAX_VALUE)
        assertTrue("the body scrolls", body.scrollY > 0)
        // The footer scrolls sideways with a fade at the edge, and its last button can be reached.
        val footer = panel.footerScroll
        assertTrue(footer.isHorizontalFadingEdgeEnabled)
        val row = panel.footerRow!!
        val last = row.getChildAt(row.childCount - 1)
        // At 360 dp the five result buttons are wider than the panel: the row scrolls (with the fade) to its end.
        val maxScroll = row.right + footer.paddingRight - footer.width
        assertTrue("the row overflows", maxScroll > 0)
        assertTrue(row.left + last.right <= maxScroll + footer.width)
    }

    @Test fun theQuickResultButtonsAllFitAt360DpInEveryUiLanguage() {
        for (lang in listOf("en", "am", "ti", "om")) {
            val ui = ctx.createConfigurationContext(Configuration(ctx.resources.configuration).apply { setLocale(Locale(lang)) })
            val actions = FakeAiActions()
            val ed = FakeEditor("Hello. How are you")
            val adapter = InputConnectionAdapter { ed }.also { it.startSession(false, ed.text.length, ed.text.length) }
            val view = AiToolbarView(ui)
            val c = AiToolbarController(actions, EditorAiAccess(adapter) {}, { it() }, {}, onChanged = { view.render() })
            view.controller = c
            c.onFieldStarted(InputProfile.DEFAULT, "x")
            c.run(AiQuickAction.REWRITE)
            actions.answer(0, FakeAiActions.ok("እንዴት ነህ"))
            measure(view, dp(360f))
            val row = view.buttonRow()!!
            val labels = (0 until row.childCount).map { (row.getChildAt(it) as TextView).text.toString() }
            assertEquals(lang, ui.getString(R.string.ai_cancel), labels.last())
            assertEquals(lang, 5, labels.size)
            // Every button lies inside the row and the row inside the panel: nothing is cut off at the right edge.
            assertEquals(lang, labels.size, row.fullyVisible().size)
            val loc = IntArray(2).also { row.getLocationInWindow(it) }
            assertTrue(lang, loc[0] >= 0 && loc[0] + row.width <= view.width)
            assertTrue(lang, row.bottom <= (row.parent as View).height)
            for (i in 0 until row.childCount) {
                val b = row.getChildAt(i) as TextView
                assertTrue("$lang ${b.text} not shortened", b.layout == null || b.layout.getEllipsisCount(0) == 0)
            }
        }
    }

    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)
}
