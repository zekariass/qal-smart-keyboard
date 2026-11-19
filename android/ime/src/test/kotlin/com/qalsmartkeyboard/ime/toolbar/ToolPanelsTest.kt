package com.qalsmartkeyboard.ime.toolbar

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.PersistableBundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.qalsmartkeyboard.ime.InputProfile
import com.qalsmartkeyboard.ime.theme.ThemeCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** ADR 0035: the tools panel and the clipboard panel (tiles, clips, paste, delete, clear, accessibility, height). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ToolPanelsTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val d = ctx.resources.displayMetrics.density
    private val widthPx = (411 * d).toInt()
    private val keysPx = (250 * d).toInt()
    private val button = "android.widget.Button"

    private fun View.measureAndLayout() {
        measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        layout(0, 0, measuredWidth, measuredHeight)
    }

    private fun View.drawOnce() = draw(Canvas(Bitmap.createBitmap(maxOf(1, width), maxOf(1, height), Bitmap.Config.ARGB_8888)))

    private fun texts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    // ---------------------------------------------------------------- tools panel

    private fun toolsPanel(c: ToolContext): Pair<ToolsPanelView, MutableList<Tool>> {
        val picked = ArrayList<Tool>()
        val p = ToolsPanelView(ctx).also {
            it.heightProvider = { keysPx }
            it.tiles = { ToolSet.tiles(c) }
            it.onTool = { t -> picked += t }
            it.render()
            it.measureAndLayout()
        }
        return p to picked
    }

    private val text = InputProfile.from(InputType.TYPE_CLASS_TEXT, 0, false, 0)

    @Test fun theToolsPanelTakesTheKeyAreaHeightAndShowsLabelledTiles() {
        val (p, picked) = toolsPanel(ToolContext(text, aiReady = true, voiceOffered = true, writingModes = true))
        assertEquals("same height as the keys it replaces", keysPx, p.measuredHeight)
        val labels = texts(p)
        for (l in listOf("Translate", "AI Assist", "Write Email", "Clipboard", "Emoji", "Voice typing", "Word Art", "Writing mode", "Languages", "Theme", "Settings")) {
            assertTrue("$l in $labels", l in labels)
        }
        assertEquals(11, p.shown.size)
        // Every tile is one Button node with its name; a tap reports the tool.
        val tile = p.tileFor(Tool.CLIPBOARD)!!
        assertEquals(button, tile.createAccessibilityNodeInfo().className)
        assertEquals("Clipboard", tile.contentDescription.toString())
        assertTrue(tile.performClick())
        assertEquals(listOf(Tool.CLIPBOARD), picked)
        p.drawOnce()
    }

    @Test fun tilesAreFourPerRowAndAtLeast48dpTall() {
        val (p, _) = toolsPanel(ToolContext(text, aiReady = true, voiceOffered = true, writingModes = true))
        val first = p.tileFor(Tool.TRANSLATE)!!
        val fifth = p.tileFor(Tool.EMOJI)!!
        assertTrue(first.width >= (411 * d / 4 - 8 * d).toInt())
        assertTrue(first.height >= (48 * d).toInt())
        assertTrue("row 2 below row 1", (fifth.parent as View).top > (first.parent as View).top)
        // The three rows of a full panel fit a portrait phone's key area without scrolling.
        val last = p.tileFor(Tool.SETTINGS)!!
        assertTrue("last row ends ${(last.parent as View).bottom} <= $keysPx", (last.parent as View).bottom <= keysPx)
    }

    @Test fun lockedTilesSayTheyAreLocked() {
        val (p, _) = toolsPanel(ToolContext(text, aiReady = true, aiBlocked = true))
        assertEquals("Translate, locked", p.tileFor(Tool.TRANSLATE)!!.contentDescription.toString())
        assertEquals("Clipboard", p.tileFor(Tool.CLIPBOARD)!!.contentDescription.toString())
    }

    @Test fun aPasswordFieldsPanelOnlyHasTheKeyboardsOwnTools() {
        val password = InputProfile.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0, false, 0)
        val (p, _) = toolsPanel(ToolContext(password))
        assertEquals(listOf(Tool.LANGUAGES, Tool.THEME, Tool.SETTINGS), p.shown.map { it.tool })
        assertNull(p.tileFor(Tool.CLIPBOARD))
    }

    @Test fun everyThemeDrawsBothPanels() {
        val clips = ClipboardHistory().apply { add("hello"); add("ሰላም") }
        for (t in ThemeCatalog.all) {
            val (tp, _) = toolsPanel(ToolContext(text, aiReady = true, aiBlocked = true, voiceOffered = true, writingModes = true))
            tp.theme = t
            tp.measureAndLayout()
            tp.drawOnce()
            val cp = clipPanel(clips).first
            cp.theme = t
            cp.measureAndLayout()
            cp.drawOnce()
        }
    }

    private fun findText(v: View, text: String): TextView? = when {
        v is TextView && v.text.toString() == text -> v
        v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findText(v.getChildAt(it), text) }
        else -> null
    }

    // ---------------------------------------------------------------- clipboard panel

    private class Events {
        val pasted = ArrayList<String>()
    }

    private fun clipPanel(h: ClipboardHistory, on: Boolean = true, current: String? = null): Pair<ClipboardPanelView, Events> {
        val e = Events()
        lateinit var p: ClipboardPanelView
        p = ClipboardPanelView(ctx).also {
            it.heightProvider = { keysPx }
            it.historyOn = { on }
            it.entries = { if (on) h.items() else listOfNotNull(current?.let { c -> Clip(0, c, 0) }) }
            it.onPaste = { c -> e.pasted += c.text }
            it.onDelete = { c -> h.remove(c.id); p.render() }
            it.onClearAll = { h.clear(); p.render() }
            it.render()
            it.measureAndLayout()
        }
        return p to e
    }

    @Test fun clipsAreCardsThatPasteOnTap() {
        val h = ClipboardHistory().apply { add("first"); add("second") }
        val (p, e) = clipPanel(h)
        assertEquals(keysPx, p.measuredHeight)
        assertEquals(listOf("second", "first"), p.shown.map { it.text })
        val card = p.cardFor(h.items().first().id)!!
        assertEquals(button, card.createAccessibilityNodeInfo().className)
        assertEquals("Paste second", card.contentDescription.toString())
        card.performClick()
        assertEquals(listOf("second"), e.pasted)
        p.drawOnce()
    }

    @Test fun deleteAndClearAll() {
        val h = ClipboardHistory().apply { add("a"); add("b"); add("c") }
        val (p, _) = clipPanel(h)
        val b = h.items().first { it.text == "b" }
        val del = p.deleteFor(b.id)!!
        assertEquals("Delete from clipboard history", del.contentDescription.toString())
        assertEquals(button, del.createAccessibilityNodeInfo().className)
        del.performClick()
        assertEquals(listOf("c", "a"), p.shown.map { it.text })
        val clear = p.clearAllButton!!
        assertEquals("Clear all", (clear as TextView).text.toString())
        clear.performClick()
        assertTrue(h.isEmpty)
        assertTrue(p.shown.isEmpty())
        assertNull("no Clear all without clips", p.clearAllButton)
    }

    @Test fun anEmptyHistoryShowsOneShortLine() {
        val (p, _) = clipPanel(ClipboardHistory())
        assertTrue("Text you copy will appear here" in texts(p))
    }

    @Test fun withTheHistoryOffOnlyTheCurrentClipIsOfferedWithoutDelete() {
        val h = ClipboardHistory().apply { add("kept before") }
        val (p, e) = clipPanel(h, on = false, current = "current")
        assertEquals(listOf("current"), p.shown.map { it.text })
        assertNull(p.deleteFor(0))
        assertNull(p.clearAllButton)
        p.cardFor(0)!!.performClick()
        assertEquals(listOf("current"), e.pasted)
        val (empty, _) = clipPanel(ClipboardHistory(), on = false)
        assertTrue("Clipboard history is off" in texts(empty))
    }

    @Test fun longClipsAreShortenedOnTheCardButPastedWhole() {
        val long = "x".repeat(2_000)
        val h = ClipboardHistory().apply { add(long) }
        val (p, e) = clipPanel(h)
        val card = p.cardFor(h.items().single().id)!!
        assertTrue(card.contentDescription.length < 400)
        card.performClick()
        assertEquals(long, e.pasted.single())
    }

    // ---------------------------------------------------------------- the system clipboard

    private val cm get() = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private class Source(ctx: Context, var on: Boolean = true, var field: InputProfile? = null) {
        val history = ClipboardHistory()
        var changes = 0
        val source = SystemClipboardSource(ctx, history, { on }, { field }) { changes++ }
    }

    private fun sensitiveClip(text: String) = ClipData.newPlainText("", text).also {
        it.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }

    @Test fun copiesAreRememberedWhileListening() {
        val s = Source(ctx, field = text)
        s.source.start()
        cm.setPrimaryClip(ClipData.newPlainText("", "copied text"))
        assertEquals(listOf("copied text"), s.history.items().map { it.text })
        assertEquals(1, s.changes)
        s.source.stop()
        cm.setPrimaryClip(ClipData.newPlainText("", "after stop"))
        assertEquals(listOf("copied text"), s.history.items().map { it.text })
    }

    @Test fun sensitiveClipsAreNeverRememberedOrOffered() {
        val s = Source(ctx, field = text)
        s.source.start()
        cm.setPrimaryClip(sensitiveClip("123456"))
        assertTrue(s.history.isEmpty)
        assertNull(s.source.currentText())
        s.source.stop()
    }

    @Test fun nothingIsRememberedFromAPasswordFieldOrWithTheHistoryOff() {
        val password = InputProfile.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0, false, 0)
        val s = Source(ctx, field = password)
        s.source.start()
        cm.setPrimaryClip(ClipData.newPlainText("", "hunter2"))
        assertTrue(s.history.isEmpty)
        s.field = text
        s.on = false
        cm.setPrimaryClip(ClipData.newPlainText("", "not kept"))
        assertTrue(s.history.isEmpty)
        // Opening the panel still offers the current clip (the user asked for it), without keeping it.
        assertEquals("not kept", s.source.currentText())
        assertTrue(s.history.isEmpty)
        s.source.stop()
    }

    @Test fun theCurrentClipJoinsTheHistoryWhenThePanelOpens() {
        cm.setPrimaryClip(ClipData.newPlainText("", "copied before the keyboard started"))
        val s = Source(ctx, field = text)
        assertEquals("copied before the keyboard started", s.source.currentText())
        assertEquals(listOf("copied before the keyboard started"), s.history.items().map { it.text })
    }

    @Test fun nonTextClipsAreIgnored() {
        val s = Source(ctx, field = text)
        s.source.start()
        cm.setPrimaryClip(ClipData("", arrayOf("image/png"), ClipData.Item(android.net.Uri.parse("content://x/1"))))
        assertTrue(s.history.isEmpty)
        assertNull(s.source.currentText())
        s.source.stop()
    }

    @Test fun noClipAtAllIsNull() {
        cm.clearPrimaryClip()
        assertNull(Source(ctx).source.currentText())
        assertNotNull(cm) // the shadow service exists; a missing one is handled the same way
        assertFalse(Source(ctx).history.items().isNotEmpty())
    }
}
