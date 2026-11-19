package com.qalsmartkeyboard.ime

import android.graphics.RectF
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Repeated-key bug (owner report): fast typing with two thumbs rolls over (the next key goes down before the previous
 * one is released). Keys with a long-press popup act on release, and the surface tracked a single such pointer, so the
 * second press overwrote the first and the first character was lost, or typed after the second. Here the real surface
 * gets multi-touch MotionEvents on the Arabic layout (alef has a popup, so it acts on release).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class KeyboardSurfaceTouchTest {
    private class Sink : TextSink {
        val text = StringBuilder()
        override fun apply(update: ComposerUpdate) {
            repeat(update.deleteBefore) { if (text.isNotEmpty()) text.setLength(text.length - 1) }
            text.append(update.commit)
        }
        override fun sendEnterKey() {}
        override fun performEditorAction(actionId: Int) {}
        override fun wantsAutoCapital(capsFlags: Int) = false
    }

    private class ArabicComposer : Composer by PassthroughComposer() {
        override fun supports(mode: TypingMode) = mode.kind == LayoutKind.ABC || mode.kind == LayoutKind.ARABIC && mode.language == "ar"
        override fun setMode(mode: TypingMode) = supports(mode)
    }

    private val sink = Sink()
    private lateinit var surface: KeyboardSurface
    private lateinit var controller: KeyboardController
    private val down = SystemClock.uptimeMillis()

    @Before fun setUp() {
        val c = RuntimeEnvironment.getApplication()
        KeyboardLabels.load(c)
        controller = KeyboardController(sink, { listOf("ar") }) {}
        controller.composer = ArabicComposer()
        controller.startField(InputProfile.from(InputType.TYPE_CLASS_TEXT, 0, false, 0))
        surface = KeyboardSurface(c).also { it.controller = controller }
        val w = (411 * c.resources.displayMetrics.density).toInt()
        surface.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        surface.layout(0, 0, surface.measuredWidth, surface.measuredHeight)
    }

    private fun rect(text: String): RectF = surface.keyBounds { it.kind == KeyKind.CHAR && it.text == text }!!

    /** One event with the given pointers (id to key centre); [action] already carries the pointer index. */
    private fun event(action: Int, pointers: List<Pair<Int, RectF>>): MotionEvent {
        val props = pointers.map { (id, _) -> MotionEvent.PointerProperties().also { it.id = id; it.toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = pointers.map { (_, r) -> MotionEvent.PointerCoords().also { it.x = r.centerX(); it.y = r.centerY(); it.pressure = 1f; it.size = 1f } }
        return MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, pointers.size, props.toTypedArray(), coords.toTypedArray(), 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    private fun pointer(action: Int, index: Int) = action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    private fun tapRolledOver(first: RectF, second: RectF) {
        surface.onTouchEvent(event(MotionEvent.ACTION_DOWN, listOf(0 to first)))
        surface.onTouchEvent(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), listOf(0 to first, 1 to second)))
        surface.onTouchEvent(event(pointer(MotionEvent.ACTION_POINTER_UP, 0), listOf(0 to first, 1 to second)))
        surface.onTouchEvent(event(MotionEvent.ACTION_UP, listOf(1 to second)))
    }

    @Test fun theSameKeyPressedTwiceWithRolloverTypesTwice() {
        val alef = rect("ا")
        tapRolledOver(alef, alef)
        assertEquals("اا", sink.text.toString())
    }

    @Test fun rolloverKeepsTheOrderOfAnActOnReleaseKeyAndAnActOnPressKey() {
        // Alef acts on release (popup), ب on press: the alef went down first, so it must come first.
        tapRolledOver(rect("ا"), rect("ب"))
        assertEquals("اب", sink.text.toString())
    }

    @Test fun plainRepeatedTapsAndDoubleTapsAllType() {
        val alef = rect("ا")
        val beh = rect("ب")
        repeat(3) {
            surface.onTouchEvent(event(MotionEvent.ACTION_DOWN, listOf(0 to alef)))
            surface.onTouchEvent(event(MotionEvent.ACTION_UP, listOf(0 to alef)))
        }
        repeat(2) {
            surface.onTouchEvent(event(MotionEvent.ACTION_DOWN, listOf(0 to beh)))
            surface.onTouchEvent(event(MotionEvent.ACTION_UP, listOf(0 to beh)))
        }
        assertEquals("ااابب", sink.text.toString())
    }
}
