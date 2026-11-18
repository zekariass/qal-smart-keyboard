package com.qalsmartkeyboard.ime.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiTextTargetTest {
    private fun s(before: String) = AiTextTarget.sentenceBeforeCursor(before)

    @Test fun takesTheSentenceBeingTyped() {
        val r = s("Hello there. How are you")!!
        assertEquals("How are you", r.text)
        assertEquals("", r.trailing)
    }

    @Test fun aSentenceThatJustEndedIsTheTarget() {
        assertEquals("How are you?", s("Hello there. How are you?")!!.text)
        assertEquals("Wait...", s("Ok. Wait...")!!.text)
    }

    @Test fun trailingWhitespaceIsKeptOutOfTheTextAndReported() {
        val r = s("One. Two three  ")!!
        assertEquals("Two three", r.text)
        assertEquals("  ", r.trailing)
    }

    @Test fun lineBreakAndEthiopicTerminatorsEndSentences() {
        assertEquals("second line", s("first line\nsecond line")!!.text)
        assertEquals("ሰላም እንዴት ነህ", s("እሺ። ሰላም እንዴት ነህ")!!.text)
        assertEquals("እንዴት ነህ፧", s("ሰላም። እንዴት ነህ፧")!!.text)
    }

    @Test fun nothingToActOnIsNull() {
        assertNull(s(""))
        assertNull(s("   "))
        assertNull(s("..."))
        assertNull(s("\n\n"))
    }

    @Test fun wholeTextWhenThereIsNoTerminator() {
        assertEquals("just some words", s("just some words")!!.text)
    }

    @Test fun aFullLookbackWindowDropsTheCutWord() {
        val window = ("ord " + "word ".repeat(500)).take(AiTextTarget.LOOKBACK)
        assertEquals(AiTextTarget.LOOKBACK, window.length)
        assert(s(window)!!.text.startsWith("word "))
    }
}
