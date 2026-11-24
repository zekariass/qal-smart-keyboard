package com.qalsmartkeyboard.ime.ai

import com.qalsmartkeyboard.ime.ComposerUpdate
import com.qalsmartkeyboard.ime.KeyKind
import com.qalsmartkeyboard.ime.KeyboardController
import com.qalsmartkeyboard.ime.LongAction
import com.qalsmartkeyboard.ime.TextSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The AI panels' workspace and the keys typing into a panel field (the keyboard controller's redirect, ADR 0031). */
class AiWorkspaceTest {
    private class Host : TextSink {
        val out = StringBuilder()
        override fun apply(update: ComposerUpdate) {
            repeat(update.deleteBefore) { if (out.isNotEmpty()) out.setLength(out.length - 1) }
            out.append(update.commit)
        }
        override fun sendEnterKey() { out.append("<ENTER>") }
        override fun performEditorAction(actionId: Int) { out.append("<ACTION>") }
        override fun wantsAutoCapital(capsFlags: Int) = false
    }

    private class H {
        val host = Host()
        val actions = FakeAiActions()
        val ws = AiWorkspace(
            actions, FakeAiEditor(), FakeClipboard(), AiLanguagePrefs.InMemory(), post = { it() },
            keyboardLanguage = { "en" }, translateDefault = { "am" },
        )
        val kc = KeyboardController(host) {}.also { it.fieldTyping = { ws.fieldTyping() } }

        fun tap(label: String) = kc.onKey(kc.layout.rows.flatten().first { !it.isSpacer && (it.label == label || it.text == label) })
        fun tapKind(kind: KeyKind) = kc.onKey(kc.layout.rows.flatten().first { it.kind == kind })
    }

    @Test fun keysTypeIntoTheAssistTextBoxNotTheHostField() {
        val h = H()
        h.ws.openAssist()
        assertTrue(h.ws.showsPanel)
        h.ws.assist.startTyping(AiAssistField.SOURCE)
        assertTrue(h.ws.typing)
        assertFalse(h.ws.showsPanel)
        h.tap("h")
        h.tap("i")
        h.tapKind(KeyKind.ENTER) // a multi-line field: a line break
        h.tap("x")
        assertEquals("hi\nx", h.ws.assist.source.text)
        assertEquals("nothing reaches the host field", "", h.host.out.toString())
        h.tapKind(KeyKind.BACKSPACE)
        assertEquals("hi\n", h.ws.assist.source.text)
        h.kc.onLongPress(h.kc.layout.rows.flatten().first { it.longAction == LongAction.EMOJI }) // the comma's long-press ends typing, back to the panel
        assertFalse(h.ws.typing)
        assertTrue(h.ws.showsPanel)
        h.tap("z")
        assertEquals("after typing ends the keys type into the host again", "z", h.host.out.toString())
    }

    @Test fun enterEndsTypingInAOneLineField() {
        val h = H()
        h.ws.openEmail()
        h.ws.email.startTyping(AiEmailField.RECIPIENT)
        h.tap("a")
        h.tapKind(KeyKind.ENTER)
        assertEquals("a", h.ws.email.recipient.text)
        assertNull(h.ws.email.typing)
        assertEquals("", h.host.out.toString())
    }

    @Test fun backStepsOutOneLevelAtATime() {
        val h = H()
        h.ws.openAssist()
        h.ws.assist.startTyping(AiAssistField.SOURCE)
        h.tap("q")
        assertTrue(h.ws.back())
        assertFalse(h.ws.typing)
        h.ws.assist.run()
        h.actions.answer(0, FakeAiActions.ok("ቅ"))
        assertTrue(h.ws.assist.step is AiAssistStep.Result)
        assertTrue(h.ws.back())
        assertEquals(AiAssistStep.Compose, h.ws.assist.step)
        assertTrue(h.ws.back())
        assertFalse(h.ws.isOpen)
        assertFalse(h.ws.back())
    }

    @Test fun openingOnePanelClosesTheOtherAndItsTexts() {
        val h = H()
        h.ws.openEmail()
        h.ws.email.instructions.set("private")
        h.ws.openAssist()
        assertEquals(AiScreen.ASSIST, h.ws.screen)
        assertTrue(h.ws.email.instructions.isBlank)
        h.ws.close()
        assertEquals(AiScreen.CLOSED, h.ws.screen)
        assertNull(h.ws.fieldTyping())
    }

    @Test fun theTextFieldCountsCodePointsAndDeletesGraphemes() {
        val f = AiTextField(3)
        f.sink.apply(ComposerUpdate(commit = "ሰላም"))
        assertEquals(3, f.length)
        assertFalse(f.overLimit)
        f.sink.apply(ComposerUpdate(commit = "👍🏽"))
        assertTrue(f.overLimit)
        f.sink.apply(ComposerUpdate(deleteBefore = 1))
        assertEquals("one backspace removes the whole emoji with its skin tone", "ሰላም", f.text)
        f.sink.apply(ComposerUpdate(composing = "ነ"))
        assertEquals("ሰላምነ", f.text)
        assertFalse(f.toString().contains("ሰ"))
    }
}
