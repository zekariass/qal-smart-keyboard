package com.qalsmartkeyboard.ime.editor

import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.EditText
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.qalsmartkeyboard.ime.ComposerUpdate
import com.qalsmartkeyboard.ime.ai.ClipAvailability
import com.qalsmartkeyboard.ime.ai.FakeClipboard
import com.qalsmartkeyboard.ime.ai.forEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * ADR 0038: the keyboard's text field View on real text layout (native graphics): a tap puts the caret at the nearest
 * cluster in logical order (also in right-to-left Arabic), a long press or double tap selects a word and shows the action
 * row, and TalkBack sees an editable text node.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorViewTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val d = ctx.resources.displayMetrics.density

    private fun field(text: String, widthDp: Int = 300, cursor: Int = text.length): KeyboardTextField {
        val f = KeyboardTextField(ctx)
        f.editor.buffer = EditorBuffer().also {
            it.setText(text)
            it.setSelection(cursor)
        }
        f.editor.haptics = { false }
        layout(f, widthDp)
        return f
    }

    private fun layout(v: View, widthDp: Int = 300) {
        v.measure(View.MeasureSpec.makeMeasureSpec((widthDp * d).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    private fun event(action: Int, x: Float, y: Float): MotionEvent {
        val t = SystemClock.uptimeMillis()
        return MotionEvent.obtain(t, t, action, x, y, 0)
    }

    /** A tap, then time passes (so the next tap is not a double tap). */
    private fun tap(v: EditorView, x: Float, y: Float = firstLine(v)) {
        v.onTouchEvent(event(MotionEvent.ACTION_DOWN, x, y))
        v.onTouchEvent(event(MotionEvent.ACTION_UP, x, y))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
    }

    private fun longPress(v: EditorView, x: Float, y: Float = firstLine(v)) {
        v.onTouchEvent(event(MotionEvent.ACTION_DOWN, x, y))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 50L))
        v.onTouchEvent(event(MotionEvent.ACTION_UP, x, y))
    }

    private fun firstLine(v: EditorView) = v.paddingTop + 10 * d

    private fun EditorView.b() = buffer!!

    @Test fun aTapAtEitherEndOfLatinTextPutsTheCaretThere() {
        val f = field("Hello world", cursor = 5)
        val e = f.editor
        tap(e, 1f)
        assertEquals(0, e.b().cursor)
        tap(e, e.width - 2f)
        assertEquals(11, e.b().cursor)
    }

    @Test fun aTapInTheMiddleLandsInTheMiddle() {
        val e = field("Hello world, how are you").editor
        tap(e, e.offsetX(5))
        assertEquals(5, e.b().cursor)
    }

    @Test fun inArabicTheRightEdgeIsTheStartOfTheText() {
        // Right to left: the first letter is drawn at the right edge.
        val text = "مرحبا بالعالم"
        val e = field(text).editor
        tap(e, e.width - 2f)
        assertEquals(0, e.b().cursor)
        tap(e, 1f)
        assertEquals(text.length, e.b().cursor)
    }

    @Test fun mixedBidiTapsMapToLogicalOffsets() {
        val text = "Hi مرحبا ok"
        val e = field(text).editor
        // The Arabic run is reversed on screen: a tap left of its middle is later in logical order than one right of it.
        val left = e.offsetAt(e.offsetX(4) - 1f, firstLine(e))
        val right = e.offsetAt(e.offsetX(7) + 1f, firstLine(e))
        assertTrue("$left $right", left in 3..8 && right in 3..8)
        tap(e, 1f)
        assertEquals(0, e.b().cursor)
    }

    @Test fun aTapNeverLandsInsideACluster() {
        val text = "بِسْمِ ሰ፟ላ" // Arabic with harakat, Ethiopic with gemination
        val e = field(text).editor
        val boundaries = setOf(0, 2, 4, 6, 7, 9, 10)
        var x = 1f
        while (x < e.width) {
            tap(e, x)
            assertTrue("x=$x cursor=${e.b().cursor}", e.b().cursor in boundaries)
            x += 7 * d
        }
    }

    @Test fun aLongPressSelectsTheWordAndShowsTheRow() {
        val f = field("Hello world", cursor = 0)
        f.clipboard = FakeClipboard(ClipAvailability.TEXT, "x").forEditor()
        val e = f.editor
        longPress(e, e.offsetX(8))
        assertEquals("world", e.b().selectedText)
        assertTrue(e.actionsShown)
        assertEquals(listOf("Cut", "Copy", "Paste", "Select all"), f.actionViews.map { it.text.toString() })
        // Copy, then the row hides; the selection stays.
        f.actionViews[1].performClick()
        assertFalse(e.actionsShown)
        assertEquals("world", e.b().selectedText)
    }

    @Test fun aDoubleTapSelectsTheWordInEthiopic() {
        val e = field("ሰላም ዓለም").editor
        val x = e.offsetX(1)
        e.onTouchEvent(event(MotionEvent.ACTION_DOWN, x, firstLine(e)))
        e.onTouchEvent(event(MotionEvent.ACTION_UP, x, firstLine(e)))
        e.onTouchEvent(event(MotionEvent.ACTION_DOWN, x, firstLine(e)))
        e.onTouchEvent(event(MotionEvent.ACTION_UP, x, firstLine(e)))
        assertEquals("ሰላም", e.b().selectedText)
        assertTrue(e.actionsShown)
    }

    @Test fun aTapOnTheCaretOffersPasteAndTypingHidesTheRow() {
        val f = field("Hello", cursor = 5)
        f.clipboard = FakeClipboard(ClipAvailability.TEXT, "x").forEditor()
        val e = f.editor
        tap(e, e.width - 2f) // the caret is already at the end
        assertTrue(e.actionsShown)
        assertEquals(listOf("Paste", "Select all"), f.actionViews.map { it.text.toString() })
        e.b().apply(ComposerUpdate(commit = "!"))
        assertFalse(e.actionsShown)
    }

    @Test fun aTapRunsBeforeEditFirst() {
        val f = field("abc def", cursor = 7)
        val e = f.editor
        val order = ArrayList<String>()
        e.beforeEdit = { order += "commit:${e.b().cursor}" }
        e.onTap = { order += "tap:${e.b().cursor}" }
        tap(e, 1f)
        assertEquals(listOf("commit:7", "tap:0"), order)
    }

    @Test fun aDisplayOnlyFieldMovesTheCursorAndReportsTheTap() {
        val f = field("Abebe Kebede", cursor = 12)
        val e = f.editor
        e.editable = false
        var taps = 0
        e.onTap = { taps++ }
        tap(e, 1f)
        assertEquals(1, taps)
        assertEquals(0, e.b().cursor)
    }

    @Test fun talkBackSeesAnEditableTextWithItsSelection() {
        val f = field("Selam world")
        val e = f.editor
        e.b().setSelection(0, 5)
        e.describe = { "Text to translate: $it" }
        val info = AccessibilityNodeInfoCompat.wrap(e.createAccessibilityNodeInfo())
        assertEquals(EditText::class.java.name, info.className)
        assertTrue(info.isEditable)
        assertEquals("Selam world", info.text.toString())
        assertEquals(0, info.textSelectionStart)
        assertEquals(5, info.textSelectionEnd)
        assertEquals("Text to translate: Selam world", info.contentDescription.toString())
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_START_INT, 3)
            putInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_END_INT, 3)
        }
        assertTrue(e.performAccessibilityAction(AccessibilityNodeInfoCompat.ACTION_SET_SELECTION, args))
        assertEquals(3, e.b().cursor)
    }

    @Test fun longTextScrollsAndKeepsTheCaretVisible() {
        val f = field(List(40) { "word$it" }.joinToString(" "), widthDp = 200)
        f.editor.maxLines = 2
        layout(f, 200)
        assertTrue(f.editor.measuredHeight < 3 * 24 * d)
        // A tap at the top then reaches the start of a visible line, not the text's start (it is scrolled to the end).
        tap(f.editor, 1f)
        assertTrue(f.editor.b().cursor > 0)
    }

    /** The x of the caret before [offset] on the first line, through the same layout the view draws. */
    private fun EditorView.offsetX(offset: Int): Float {
        // Search the x that maps to the offset (the view exposes only the inverse).
        var best = 1f
        var x = 1f
        while (x < width) {
            if (offsetAt(x, firstLine(this)) == offset) {
                best = x
                break
            }
            x += 1f
        }
        return best
    }
}
