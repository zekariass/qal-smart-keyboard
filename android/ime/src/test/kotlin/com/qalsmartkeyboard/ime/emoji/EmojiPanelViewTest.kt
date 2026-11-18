package com.qalsmartkeyboard.ime.emoji

import android.text.InputType as T
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.qalsmartkeyboard.ime.ComposerUpdate
import com.qalsmartkeyboard.ime.InMemoryModePreferences
import com.qalsmartkeyboard.ime.InputProfile
import com.qalsmartkeyboard.ime.KeyboardController
import com.qalsmartkeyboard.ime.TextSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The drawn panel and search strip: layout, touch routing and TalkBack nodes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class EmojiPanelViewTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val d = ctx.resources.displayMetrics.density
    private val widthPx = (411 * d).toInt()
    private val heightPx = (250 * d).toInt()

    private class Host : TextSink {
        val out = StringBuilder()
        override fun apply(update: ComposerUpdate) { out.append(update.commit) }
        override fun sendEnterKey() {}
        override fun performEditorAction(actionId: Int) {}
        override fun wantsAutoCapital(capsFlags: Int) = false
    }

    private inner class H {
        val host = Host()
        val controller = KeyboardController(
            host, prefs = InMemoryModePreferences(), emojiLoader = EmojiLoader(EmojiDataTest.assets),
        ) {}.also { it.startField(InputProfile.from(T.TYPE_CLASS_TEXT, 0, false, 0)) }
        val panel = EmojiPanelView(ctx).also { it.controller = controller; it.heightProvider = { heightPx } }
        val search = EmojiSearchBar(ctx).also { it.controller = controller }

        init {
            controller.emoji.open(true)
            layout()
            panel.onOpened()
        }

        fun layout() {
            panel.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
            search.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            search.layout(0, 0, search.measuredWidth, search.measuredHeight)
        }

        fun tap(v: View, x: Float, y: Float) {
            val t = android.os.SystemClock.uptimeMillis()
            v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
            v.dispatchTouchEvent(MotionEvent.obtain(t, t + 20, MotionEvent.ACTION_UP, x, y, 0))
        }

        /** The visible virtual views as (description, id). */
        fun nodes(): List<Pair<String, Int>> {
            val provider = panel.accessibilityNodeProvider!!
            return panel.accessibilityIds().map { id -> (provider.createAccessibilityNodeInfo(id)!!.contentDescription?.toString() ?: "") to id }
        }
    }

    @Test fun takesTheHeightItIsGiven() {
        val h = H()
        assertEquals(heightPx, h.panel.measuredHeight)
    }

    @Test fun tabsKeysAndEmojiAreLabelledButtons() {
        val h = H()
        val names = h.nodes().map { it.first }
        for (label in listOf("Search emoji", "Recent", "Smileys and emotion", "People and body", "Animals and nature", "Food and drink",
            "Travel and places", "Activities", "Objects", "Symbols", "Flags", "Back to keyboard", "Space", "Delete")) {
            assertTrue("missing $label in $names", label in names)
        }
        // The panel opens on the first category (no recents yet), so its first emoji is there with its CLDR name.
        assertTrue("grinning face" in names)
        val id = h.nodes().first { it.first == "grinning face" }.second
        val node = h.panel.accessibilityNodeProvider!!.createAccessibilityNodeInfo(id)!!
        assertEquals("android.widget.Button", node.className)
        assertTrue(node.isClickable)
    }

    @Test fun clickingAnEmojiNodeInsertsItIntoTheHostField() {
        val h = H()
        val id = h.nodes().first { it.first == "grinning face" }.second
        assertTrue(h.panel.accessibilityNodeProvider!!.performAction(id, AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals("😀", h.host.out.toString())
        assertEquals(listOf("😀"), h.controller.emoji.recentTexts)
    }

    @Test fun skinToneActionsAreOfferedForEmojiWithTones() {
        val h = H()
        // Scroll to people & body, where the waving hand is.
        val people = h.nodes().first { it.first == "People and body" }.second
        h.panel.accessibilityNodeProvider!!.performAction(people, AccessibilityNodeInfo.ACTION_CLICK, null)
        repeat(20) { h.panel.computeScroll() }
        val hand = h.nodes().firstOrNull { it.first == "waving hand" }
        if (hand != null) {
            val info = h.panel.accessibilityNodeProvider!!.createAccessibilityNodeInfo(hand.second)!!
            assertTrue(info.actionList.any { it.label?.toString() == "Skin tone: medium skin tone" })
        }
    }

    @Test fun tappingTheSearchTabOpensTheSearchAndTheBackKeyClosesThePanel() {
        val h = H()
        h.tap(h.panel, 10f * d, 10f * d) // first slot: search
        assertEquals(EmojiMode.SEARCH, h.controller.emoji.mode)
        h.controller.emoji.closeSearch()
        // Bottom row, first key: ABC.
        h.tap(h.panel, 20f * d, h.panel.height - 10f * d)
        assertEquals(EmojiMode.CLOSED, h.controller.emoji.mode)
    }

    @Test fun searchStripShowsResultsAndInsertsOnTap() {
        val h = H()
        h.controller.emoji.openSearch()
        h.controller.emoji.querySink.apply(ComposerUpdate(commit = "grin"))
        h.search.render()
        h.layout()
        val provider = h.search.accessibilityNodeProvider!!
        val host = provider.createAccessibilityNodeInfo(View.NO_ID)!!
        assertTrue(host.childCount >= 2) // back arrow and results
        assertEquals("Search query: grin", h.search.contentDescription.toString())
        val first = h.controller.emoji.results.first()
        // Tap the first result cell (right of the back arrow and the query box).
        val x = h.search.width.toFloat().let { 44f * d + (minOf(maxOf(it * 0.36f, 120f * d), 190f * d)) + 8f * d + 22f * d }
        h.tap(h.search, x, h.search.height / 2f)
        assertEquals(first.text, h.host.out.toString())
        assertTrue(h.controller.emoji.searching)
        // The back arrow returns to the panel.
        h.tap(h.search, 20f * d, h.search.height / 2f)
        assertEquals(EmojiMode.PANEL, h.controller.emoji.mode)
        assertFalse(h.controller.emoji.searching)
    }
}
