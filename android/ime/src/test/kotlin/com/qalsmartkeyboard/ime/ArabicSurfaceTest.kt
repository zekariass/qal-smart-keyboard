package com.qalsmartkeyboard.ime

import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.InputType
import android.view.View
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * ADR 0034: the Arabic letter and symbols pages build and draw in every UI language, and every key has a spoken
 * description (TalkBack) from the resources. Glyph shaping itself (lam-alef, marks) needs a device (on-device QA).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ArabicSurfaceTest {
    @After fun resetLabels() = KeyboardLabels.reset()

    private class Sink : TextSink {
        override fun apply(update: ComposerUpdate) {}
        override fun sendEnterKey() {}
        override fun performEditorAction(actionId: Int) {}
        override fun wantsAutoCapital(capsFlags: Int) = false
    }

    /** Passthrough with the Arabic layout available (the real core is covered by ArabicKeyboardTest). */
    private class ArabicComposer : Composer by PassthroughComposer() {
        override fun supports(mode: TypingMode) = mode.kind == LayoutKind.ABC || mode.kind == LayoutKind.ARABIC && mode.language == "ar"
        override fun setMode(mode: TypingMode) = supports(mode)
    }

    private fun View.measureAndDraw(widthPx: Int) {
        measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        layout(0, 0, measuredWidth, maxOf(1, measuredHeight))
        draw(Canvas(Bitmap.createBitmap(widthPx, maxOf(1, measuredHeight), Bitmap.Config.ARGB_8888)))
    }

    private fun check(arabicName: String) {
        val c = RuntimeEnvironment.getApplication()
        KeyboardLabels.load(c)
        assertEquals(arabicName, LanguageCatalog.spokenName("ar"))
        val controller = KeyboardController(Sink(), { listOf("ar", "en") }) {}
        controller.composer = ArabicComposer()
        controller.startField(InputProfile.from(InputType.TYPE_CLASS_TEXT, 0, false, 0))
        assertEquals(TypingMode("ar", LayoutKind.ARABIC), controller.effectiveMode)
        val surface = KeyboardSurface(c).also { it.controller = controller }
        val w = (411 * c.resources.displayMetrics.density).toInt()
        fun drawAndCheck() {
            surface.measureAndDraw(w)
            val spoken = controller.layout.rows.flatten().filter { !it.isSpacer }.map { surface.spokenFor(it) }
            assertTrue(spoken.toString(), spoken.none { it.isBlank() })
        }
        fun tap(kind: KeyKind) = controller.onKey(controller.layout.rows.flatten().first { it.kind == kind })
        drawAndCheck()
        tap(KeyKind.TO_SYMBOLS)
        assertEquals(Page.SYMBOLS, controller.page)
        drawAndCheck()
        tap(KeyKind.TO_LETTERS)
        assertEquals(Page.LETTERS, controller.page)
    }

    @Test fun english() = check("Arabic")

    @Test @Config(qualifiers = "am-w411dp-h891dp-xxhdpi")
    fun amharic() = check("ዓረብኛ")

    @Test @Config(qualifiers = "ti-w411dp-h891dp-xxhdpi")
    fun tigrinya() = check("ዓረብኛ")

    @Test @Config(qualifiers = "om-w411dp-h891dp-xxhdpi")
    fun oromo() = check("Afaan Arabaa")
}
