package com.qalsmartkeyboard.ime.userdict

import com.qalsmartkeyboard.ime.ComposerUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Learning rules (SD 7.4). Synthetic words only. */
class WordLearnerTest {
    private data class Learned(val lang: String, val word: String, val previous: String?)

    private val got = ArrayList<Learned>()
    private val learner = WordLearner { l, w, p -> got += Learned(l, w, p) }

    private fun commit(s: String, lang: String = "am", learning: Boolean = true) =
        learner.observe(ComposerUpdate(commit = s), lang, learning)

    @Test fun aWordIsLearnedOnlyAfterItsBoundary() {
        commit("ሰላም")
        assertTrue(got.isEmpty())
        commit(" ")
        assertEquals(listOf(Learned("am", "ሰላም", null)), got)
    }

    @Test fun keyByKeyCommitsAndTheWordspaceWork() {
        for (c in "ቤት") commit(c.toString())
        commit("፡")
        assertEquals("ቤት", got.single().word)
    }

    @Test fun previousWordFollowsAPlainSpaceOnly() {
        commit("ሰላም ነው ")
        assertEquals(listOf(Learned("am", "ሰላም", null), Learned("am", "ነው", "ሰላም")), got)
        got.clear()
        commit("ቤት። ሰው ")
        assertEquals(listOf(Learned("am", "ቤት", "ነው"), Learned("am", "ሰው", null)), got) // the full stop clears the context
    }

    @Test fun punctuationAroundAWordIsStripped() {
        commit("(ሰላም), ")
        assertEquals("ሰላም", got.single().word)
    }

    @Test fun nothingIsLearnedWhenLearningIsOff() {
        commit("ሰላም ", learning = false)
        commit("ነው ")
        assertEquals(listOf(Learned("am", "ነው", null)), got) // nothing was buffered from the off period
    }

    @Test fun turningLearningOffDropsTheOpenWord() {
        commit("ሰላ")
        commit("ም ", learning = false)
        assertTrue(got.isEmpty())
    }

    @Test fun deletingTextDropsTheOpenWordAndContext() {
        commit("ሰላም ነ")
        learner.observe(ComposerUpdate(deleteBefore = 1), "am", true)
        commit("ው ")
        assertEquals(listOf(Learned("am", "ሰላም", null)), got)
    }

    @Test fun resetDropsTheOpenWord() {
        commit("ሰላም")
        learner.reset()
        commit(" ")
        assertTrue(got.isEmpty())
    }

    @Test fun scriptRules() {
        assertTrue(WordLearner.isLearnable("am", "ሰላም"))
        assertFalse(WordLearner.isLearnable("am", "hello"))
        assertFalse(WordLearner.isLearnable("am", "ሰላምa"))
        assertTrue(WordLearner.isLearnable("en", "hello"))
        assertTrue(WordLearner.isLearnable("om", "ba'e"))
        // ADR 0039: Arabic words (letters and harakat) are learnable; Latin is not Arabic.
        assertTrue(WordLearner.isLearnable("ar", "كتاب"))
        assertTrue(WordLearner.isLearnable("ar", "كَتَبَ"))
        assertFalse(WordLearner.isLearnable("ar", "kitab"))
        assertFalse(WordLearner.isLearnable("en", "كتاب"))
        assertFalse(WordLearner.isLearnable("en", "ሰላም"))
        assertFalse(WordLearner.isLearnable("en", "'tis"))
        assertFalse(WordLearner.isLearnable("en", "NASA"))
        assertFalse(WordLearner.isLearnable("en", "a"))
        assertFalse(WordLearner.isLearnable("en", "abc123"))
        assertFalse(WordLearner.isLearnable("en", "well-known"))
        assertFalse(WordLearner.isLearnable("en", "a".repeat(WordLearner.MAX_WORD + 1)))
    }

    @Test fun addressesAndNumbersAreNotLearnedPiecewise() {
        commit("user@example.com 12345 3pm ", lang = "en")
        assertTrue(got.isEmpty())
        commit("hello ", lang = "en")
        assertEquals("hello", got.single().word)
    }

    @Test fun veryLongTokensAreIgnored() {
        commit("a".repeat(500) + " ", lang = "en")
        assertTrue(got.isEmpty())
    }
}
