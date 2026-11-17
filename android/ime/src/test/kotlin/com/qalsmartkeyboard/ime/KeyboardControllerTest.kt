package com.qalsmartkeyboard.ime

import android.text.InputType as T
import android.view.inputmethod.EditorInfo as E
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardControllerTest {
    private class Fake : TextSink {
        val out = StringBuilder()
        val events = ArrayList<String>()
        var autoCaps = false
        override fun commitText(text: String) { out.append(text) }
        override fun deleteBackward() { if (out.isNotEmpty()) out.setLength(out.length - 1); events += "DEL" }
        override fun sendEnterKey() { events += "ENTER" }
        override fun performEditorAction(actionId: Int) { events += "ACTION$actionId" }
        override fun wantsAutoCapital(capsFlags: Int) = autoCaps
    }

    private fun setup(type: Int = T.TYPE_CLASS_TEXT, opts: Int = 0, f: Fake = Fake()): Pair<KeyboardController, Fake> {
        val c = KeyboardController(f) {}
        c.startField(InputProfile.from(type, opts, false, 0))
        return c to f
    }

    private fun KeyboardController.tap(label: String) =
        onKey(layout.rows.flatten().first { !it.isSpacer && (it.label == label || it.text == label) })

    private fun KeyboardController.tapKind(kind: KeyKind) =
        onKey(layout.rows.flatten().first { it.kind == kind })

    @Test fun typesLowercaseAscii() {
        val (c, f) = setup()
        "hello".forEach { c.tap(it.toString()) }
        c.tap(" ")
        assertEquals("hello ", f.out.toString())
    }

    @Test fun shiftOnceThenLockThenOff() {
        val (c, f) = setup()
        c.tapKind(KeyKind.SHIFT); c.tap("a"); c.tap("b")
        assertEquals("Ab", f.out.toString())
        c.tapKind(KeyKind.SHIFT); c.tapKind(KeyKind.SHIFT)
        assertEquals(ShiftState.LOCKED, c.shift)
        c.tap("c"); c.tap("d")
        assertEquals("AbCD", f.out.toString())
        c.tapKind(KeyKind.SHIFT)
        assertEquals(ShiftState.OFF, c.shift)
    }

    @Test fun autoCapitalAtSentenceStart() {
        val f = Fake().also { it.autoCaps = true }
        val (c, _) = setup(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_FLAG_CAP_SENTENCES, f = f)
        assertEquals(ShiftState.ONCE, c.shift)
        f.autoCaps = false
        c.tap("h")
        assertEquals("H", f.out.toString())
        assertEquals(ShiftState.OFF, c.shift)
    }

    @Test fun backspaceDeletes() {
        val (c, f) = setup()
        c.tap("a"); c.tap("b"); c.tapKind(KeyKind.BACKSPACE)
        assertEquals("a", f.out.toString())
    }

    @Test fun symbolPagesAndBack() {
        val (c, f) = setup()
        c.tap("?123")
        assertEquals(Page.SYMBOLS, c.page)
        c.tap("@"); c.tap("#")
        c.tap("=\\<")
        assertEquals(Page.SYMBOLS_2, c.page)
        c.tap("?123"); c.tap("ABC")
        assertEquals(Page.LETTERS, c.page)
        assertEquals("@#", f.out.toString())
    }

    @Test fun symbolsAreNotUppercased() {
        val (c, f) = setup()
        c.tapKind(KeyKind.SHIFT)
        c.tap("?123"); c.tap("-")
        assertEquals("-", f.out.toString())
    }

    @Test fun enterBehaviour() {
        val (c, f) = setup(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_FLAG_MULTI_LINE)
        c.tapKind(KeyKind.ENTER)
        assertEquals("\n", f.out.toString())
        val (c2, f2) = setup(T.TYPE_CLASS_TEXT, E.IME_ACTION_GO)
        c2.tapKind(KeyKind.ENTER)
        assertEquals(listOf("ACTION${E.IME_ACTION_GO}"), f2.events)
        val (c3, f3) = setup(T.TYPE_CLASS_TEXT)
        c3.tapKind(KeyKind.ENTER)
        assertEquals(listOf("ENTER"), f3.events)
    }

    @Test fun emailAndUriLayoutsHaveTheirKeys() {
        val (e, _) = setup(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        assertTrue(e.layout.rows.flatten().any { it.text == "@" })
        val (u, _) = setup(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_URI)
        assertTrue(u.layout.rows.flatten().any { it.text == "/" })
        assertTrue(u.layout.rows.flatten().any { it.text == ".com" })
    }

    @Test fun numberFieldStartsOnDigitPad() {
        val (c, f) = setup(T.TYPE_CLASS_NUMBER)
        assertEquals(Page.NUMBER, c.page)
        c.tap("7"); c.tap("5")
        assertEquals("75", f.out.toString())
        assertTrue(c.layout.rows.flatten().none { it.isLetter })
    }

    @Test fun passwordUsesLettersWithoutLearning() {
        val (c, f) = setup(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD)
        assertEquals(Page.LETTERS, c.page)
        assertFalse(c.profile.learningAllowed)
        c.tap("p")
        assertEquals("p", f.out.toString())
    }
}
