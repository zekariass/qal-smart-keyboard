package com.qalsmartkeyboard.ime.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spacing and capitalisation of dictated text (ADR 0029). */
class VoiceTextTest {
    private fun en(text: String, before: String?, cap: Boolean = false) = VoiceText.prepare(text, before, english = true, capitalize = cap)

    @Test fun aLeadingSpaceFollowsANonSpaceCharacter() {
        assertEquals(" how are you", en("how are you", "Hi,"))
        assertEquals("how are you", en("how are you", "Hi, "))
        assertEquals("how are you", en("how are you", ""))
        assertEquals("how are you", en("how are you", null)) // the editor did not answer
        assertEquals("how are you", en("how are you", "line\n"))
    }

    @Test fun noSpaceAfterAnOpenerOrBeforeClosingPunctuation() {
        assertEquals("quote", en("quote", "say ("))
        assertEquals("hello", en("hello", "#"))
        assertEquals(", then", en(", then", "first"))
        assertEquals("።", VoiceText.prepare("።", "ሰላም", english = false, capitalize = false))
    }

    @Test fun ethiopicTextGetsTheSameSpacingAndNoCapitalisation() {
        assertEquals(" እንዴት ነህ", VoiceText.prepare("እንዴት ነህ", "ሰላም", english = false, capitalize = true))
        assertEquals("እንዴት", VoiceText.prepare("እንዴት", "ሰላም፡", english = false, capitalize = false)) // Ethiopic wordspace
        assertEquals("nagaa", VoiceText.prepare("nagaa", "", english = false, capitalize = true)) // om: as recognised
    }

    @Test fun englishIsCapitalisedOnlyWhereAutoCapWould() {
        assertEquals("Hello there", en("hello there", "", cap = true))
        assertEquals(" Hello there", en("hello there", "Done.", cap = true))
        assertEquals(" hello there", en("hello there", "said", cap = false))
        assertEquals("iPhone", VoiceText.prepare("iPhone", "", english = false, capitalize = true))
    }

    @Test fun whitespaceIsTidiedAndEmptyTextInsertsNothing() {
        assertEquals(" a b", en("  a \n  b ", "x"))
        assertEquals("", en("   ", "x"))
    }

    @Test fun englishTagsAreRecognised() {
        assertTrue(VoiceText.isEnglish("en-GB"))
        assertFalse(VoiceText.isEnglish("am-ET"))
    }
}
