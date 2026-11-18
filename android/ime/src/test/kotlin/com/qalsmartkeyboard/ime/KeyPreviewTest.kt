package com.qalsmartkeyboard.ime

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Key press preview rules and bubble geometry. */
class KeyPreviewTest {
    private fun text(k: Key, shift: ShiftState = ShiftState.OFF, page: Page = Page.LETTERS, face: OrderFace? = null) =
        KeyPreview.textFor(k, shift, page, face)

    // ------------------------------------------------------------------ which keys, what text

    @Test fun characterKeysPreviewTheirExactOutput() {
        val q = Key(KeyKind.CHAR, "q", "q")
        assertEquals("q", text(q))
        assertEquals("Q", text(q, ShiftState.ONCE))
        assertEquals("Q", text(q, ShiftState.LOCKED))
        assertEquals("1", text(Key(KeyKind.CHAR, "1", "1"), ShiftState.ONCE, Page.SYMBOLS)) // shift only affects the letters page
        assertEquals("።", text(Key(KeyKind.CHAR, "።", "።")))
        assertEquals(".com", text(Key(KeyKind.CHAR, ".com", ".com")))
    }

    @Test fun fidelKeysPreviewTheirGlyph() {
        assertEquals("ለ", text(Key(KeyKind.FIDEL_CONSONANT, "ለ", "L")))
        val order = Key(KeyKind.FIDEL_VOWEL, "ሀ", order = 4)
        assertEquals("ላ", text(order, face = OrderFace(4, "ላ", true)))
        assertNull(text(order, face = OrderFace(4, "ሃ", false))) // inactive order keys type nothing
        assertNull(text(order, face = OrderFace(4, "", true)))
        assertNull(text(order))
    }

    @Test fun otherKeysNeverPreview() {
        listOf(
            Key(KeyKind.SPACE, " ", " "), Key(KeyKind.ENTER, ""), Key(KeyKind.SHIFT, "⇧"), Key(KeyKind.BACKSPACE, "⌫"),
            Key(KeyKind.GLOBE, "🌐"), Key(KeyKind.ABC_TOGGLE, "ABC"), Key(KeyKind.TO_SYMBOLS, "?123"),
            Key(KeyKind.TO_SYMBOLS_2, "=<"), Key(KeyKind.TO_LETTERS, "ABC"), Key(KeyKind.CHAR, "", ""),
        ).forEach { assertNull(it.kind.name, text(it)) }
    }

    @Test fun everyLayoutHasPreviewsOnCharacterKeysOnly() {
        for (profile in listOf(InputProfile.DEFAULT, InputProfile.from(InputType.TYPE_CLASS_NUMBER, 0, false, 0))) {
            for (page in Page.entries) {
                val layout = Layouts.forPage(page, profile)
                val keys = layout.rows.flatten()
                for (k in keys) {
                    val t = text(k, page = page)
                    when (k.kind) {
                        KeyKind.CHAR -> assertEquals(k.text.ifEmpty { null }, t)
                        else -> assertNull(t)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ geometry

    private val w = 60f
    private val h = 56f
    private val gap = 6f

    @Test fun bubbleIsCentredAboveTheKey() {
        val p = KeyPreview.place(100f, 140f, 300f, w, h, gap, 400f, 0f)
        assertEquals(90f, p.left, 0.001f)
        assertEquals(300f - h - gap, p.top, 0.001f)
    }

    @Test fun bubbleIsClampedAtTheLeftAndRightEdges() {
        val left = KeyPreview.place(0f, 36f, 300f, w, h, gap, 400f, 0f)
        assertEquals(0f, left.left, 0.001f)
        val right = KeyPreview.place(364f, 400f, 300f, w, h, gap, 400f, 0f)
        assertEquals(340f, right.left, 0.001f)
        assertTrue(right.left + w <= 400f)
    }

    @Test fun topRowBubbleRisesIntoTheHeadroomButStaysInsideTheArea() {
        // Surface top at 190 in overlay coordinates (the headroom above): the top row's bubble fits above its key.
        val top = KeyPreview.place(100f, 140f, 190f + 3f, w, h, gap, 400f, 0f)
        assertEquals(190f + 3f - h - gap, top.top, 0.001f)
        assertTrue(top.top >= 0f)
        // Headroom shrunk to nothing (landscape): clamped to the area top instead of being clipped.
        val tight = KeyPreview.place(100f, 140f, 20f, w, h, gap, 400f, 0f)
        assertEquals(0f, tight.top, 0.001f)
    }

    @Test fun bubbleWidthIsWiderThanTheKeyWithAMinimum() {
        assertEquals(52f, KeyPreview.width(20f, 52f), 0.001f)
        assertEquals(65f, KeyPreview.width(50f, 52f), 0.001f)
    }

    // ------------------------------------------------------------------ setting and password fields

    private class Prefs(private val on: Boolean) : InMemoryModePreferences() {
        override val keyPreviewEnabled get() = on
    }

    private fun controller(on: Boolean, type: Int): KeyboardController {
        val sink = object : TextSink {
            override fun apply(update: ComposerUpdate) {}
            override fun sendEnterKey() {}
            override fun performEditorAction(actionId: Int) {}
            override fun wantsAutoCapital(capsFlags: Int) = false
        }
        return KeyboardController(sink, prefs = Prefs(on)) {}.also { it.startField(InputProfile.from(type, 0, false, 0)) }
    }

    @Test fun previewsFollowTheSettingAndAreOffInPasswordFields() {
        assertTrue(controller(true, InputType.TYPE_CLASS_TEXT).keyPreviewsActive)
        assertFalse(controller(false, InputType.TYPE_CLASS_TEXT).keyPreviewsActive)
        val textPassword = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val webPassword = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        val visiblePassword = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        val numberPassword = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        for (t in listOf(textPassword, webPassword, visiblePassword, numberPassword)) {
            assertFalse("type $t", controller(true, t).keyPreviewsActive)
        }
        assertTrue(controller(true, InputType.TYPE_CLASS_NUMBER).keyPreviewsActive)
    }
}
