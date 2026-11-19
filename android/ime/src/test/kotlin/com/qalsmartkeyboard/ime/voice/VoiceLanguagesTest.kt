package com.qalsmartkeyboard.ime.voice

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Keyboard language to recognition tags, tag normalisation and matching, display names (ADR 0029). */
class VoiceLanguagesTest {
    private val us = Locale.US

    @Test fun eachKeyboardLanguageMapsToItsTagsInPreferenceOrder() {
        assertEquals(listOf("am-ET"), VoiceLanguages.defaults("am", us))
        assertEquals(listOf("ti-ET", "ti-ER"), VoiceLanguages.defaults("ti", us))
        assertEquals(listOf("om-ET"), VoiceLanguages.defaults("om", us))
    }

    @Test fun englishFollowsAnEnglishDeviceLocaleThenFallsBackToUs() {
        assertEquals(listOf("en-GB", "en-US"), VoiceLanguages.defaults("en", Locale.UK))
        assertEquals(listOf("en-US"), VoiceLanguages.defaults("en", Locale.US))
        assertEquals(listOf("en-US"), VoiceLanguages.defaults("en", Locale.forLanguageTag("am-ET")))
        assertEquals(listOf("en-US"), VoiceLanguages.defaults("en", Locale.ENGLISH)) // no region: US
    }

    @Test fun aChosenTagComesFirstWithoutDuplicates() {
        assertEquals(listOf("ti-ER", "ti-ET"), VoiceLanguages.preference("ti", "ti_er", us))
        assertEquals(listOf("am-ET"), VoiceLanguages.preference("am", "am-ET", us))
        assertEquals(listOf("ti-ET", "ti-ER"), VoiceLanguages.preference("ti", null, us))
    }

    @Test fun tagsAreNormalisedAsRecognisersSpellThem() {
        assertEquals("am-ET", VoiceLanguages.normalize("am_et"))
        assertEquals("zh-Hant-TW", VoiceLanguages.normalize("ZH-hant-tw"))
        assertEquals("es-419", VoiceLanguages.normalize("es-419"))
        assertEquals("en", VoiceLanguages.normalize(" EN "))
        assertEquals("", VoiceLanguages.normalize(""))
    }

    @Test fun matchingIsExactFirstThenByLanguage() {
        val declared = setOf("am", "ti-ER", "en-US", "en-GB")
        assertEquals("en-GB", VoiceLanguages.exact("en_gb", declared))
        assertNull(VoiceLanguages.exact("am-ET", declared))
        assertEquals("am", VoiceLanguages.sameLanguage("am-ET", declared))
        assertEquals("ti-ER", VoiceLanguages.sameLanguage("ti-ET", declared))
        assertEquals("en-GB", VoiceLanguages.sameLanguage("en-AU", declared)) // stable: alphabetical
        assertNull(VoiceLanguages.sameLanguage("om-ET", declared))
    }

    @Test fun namesUseOurNamesForTheKeyboardLanguages() {
        assertEquals("Amharic (Ethiopia)", VoiceLanguages.displayName("am-ET"))
        assertEquals("Tigrinya (Eritrea)", VoiceLanguages.displayName("ti-ER"))
        assertEquals("Afaan Oromoo (Ethiopia)", VoiceLanguages.displayName("om-ET"))
        assertEquals("French (France)", VoiceLanguages.displayName("fr-FR"))
        assertEquals("Tigrinya", VoiceLanguages.languageName("ti-ER"))
        assertEquals("om", VoiceLanguages.keyboardLanguageOf("om-ET"))
        assertNull(VoiceLanguages.keyboardLanguageOf("fr-FR"))
    }
}
