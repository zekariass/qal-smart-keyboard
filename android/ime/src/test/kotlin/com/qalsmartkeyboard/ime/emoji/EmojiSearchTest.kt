package com.qalsmartkeyboard.ime.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiSearchTest {
    private val catalog = EmojiDataTest.catalog.also { c ->
        EmojiLoader(EmojiDataTest.assets).let { l -> for (lang in listOf("am", "ti", "om")) c.putLanguage(lang, l.loadLanguage(lang)!!) }
    }

    private fun names(q: String, lang: String? = "en") = EmojiSearch.search(catalog, q, lang).map { it.name }

    @Test fun blankQueryFindsNothing() {
        assertTrue(names("").isEmpty())
        assertTrue(names("   ").isEmpty())
    }

    @Test fun englishKeywordFindsTheEmoji() {
        assertTrue("grinning face" in names("grin"))
        assertTrue("red heart" in names("heart"))
        assertTrue(names("zzzzqq").isEmpty())
    }

    @Test fun exactAndPrefixMatchesComeFirst() {
        val r = EmojiSearch.search(catalog, "cat", "en")
        // The cat comes before anything that only contains "cat" inside a longer word.
        assertEquals("cat", r.first().name)
        val firstSubstring = r.indexOfFirst { e -> catalog.englishTerms(e).none { t -> t == "cat" || t.startsWith("cat") || t.contains(" cat") } }
        if (firstSubstring >= 0) {
            val lastPrefix = r.indexOfLast { e -> catalog.englishTerms(e).any { t -> t == "cat" || t.startsWith("cat") || t.contains(" cat") } }
            assertTrue(lastPrefix < firstSubstring)
        }
    }

    @Test fun prefixOfAKeywordMatches() {
        assertTrue("grinning face" in names("grinn"))
    }

    @Test fun everyWordOfTheQueryMustMatch() {
        val r = names("red heart")
        assertTrue("red heart" in r)
        assertTrue(names("red heart zzzz").isEmpty())
    }

    @Test fun searchIsCaseInsensitive() {
        assertEquals(names("grin"), names("GRIN"))
    }

    @Test fun amharicKeywordsFindEmojiWhenTheLanguageIsLoaded() {
        // "ፈገግታ" (smile) is a CLDR Amharic keyword of the grinning face.
        assertTrue("grinning face" in names("ፈገግታ", "am"))
        // Without the Amharic annotations nothing matches an Amharic query.
        assertTrue(names("ፈገግታ", "en").isEmpty())
        // English still works while typing in Amharic.
        assertTrue("grinning face" in names("grin", "am"))
    }

    @Test fun tigrinyaAndOromoUseTheirOwnAnnotations() {
        assertTrue(names("ፍሽኽታ", "ti").isNotEmpty())
        assertTrue(names("kolfa", "om").isNotEmpty())
    }

    @Test fun languageWithoutLoadedAnnotationsFallsBackToEnglish() {
        val bare = EmojiLoader(EmojiDataTest.assets).load()
        assertTrue("grinning face" in EmojiSearch.search(bare, "grin", "ti").map { it.name })
    }

    @Test fun resultsAreLimited() {
        assertTrue(EmojiSearch.search(catalog, "a", "en", limit = 10).size <= 10)
    }
}
