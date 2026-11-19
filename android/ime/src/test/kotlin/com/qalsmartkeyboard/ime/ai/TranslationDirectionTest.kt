package com.qalsmartkeyboard.ime.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The translation direction control (ADR 0031): defaults, preferred destination, per-source memory, swap. */
class TranslationDirectionTest {
    private val mum = AiDestination("p1", "Mum", "am", isProfile = true)

    private fun dir(prefs: AiLanguagePrefs = AiLanguagePrefs.InMemory(), keyboard: String = "am", rule: AiDestination? = null) =
        TranslationDirection(prefs) { listOf(mum) }.also { it.start(keyboard, rule) }

    @Test fun defaultsAreEnglishForEthiopianLanguagesAndAmharicForEnglish() {
        for (src in listOf("am", "ti", "om")) assertEquals(src, "en", dir(keyboard = src).destination.language)
        assertEquals("am", dir(keyboard = "en").destination.language)
    }

    @Test fun theSourceIsAutoWithTheKeyboardLanguageShown() {
        val d = dir(keyboard = "ti")
        assertTrue(d.isAuto)
        assertNull(d.manualSource)
        assertEquals("Auto · Tigrinya", d.sourceLabel("Auto"))
        d.keyboardLanguage = "om"
        assertEquals("Auto · Afaan Oromoo", d.sourceLabel("Auto"))
    }

    @Test fun thePreferredDestinationFromSettingsComesBeforeTheBuiltInDefault() {
        val d = dir(AiLanguagePrefs.InMemory(preferredTarget = "ti"), keyboard = "am")
        assertEquals("ti", d.destination.language)
        // The preferred language is skipped for text in that same language.
        assertEquals("en", dir(AiLanguagePrefs.InMemory(preferredTarget = "ti"), keyboard = "ti").destination.language)
    }

    @Test fun theLastPairIsRememberedPerSourceLanguage() {
        val prefs = AiLanguagePrefs.InMemory()
        val d = dir(prefs, keyboard = "am")
        d.chooseDestination(AiLanguages.plain("om"))
        assertEquals(mapOf("am" to "lang:om"), prefs.pairs)
        // Another field, same source: the pair is back. Another source: its own default.
        assertEquals("om", dir(prefs, keyboard = "am").destination.language)
        assertEquals("am", dir(prefs, keyboard = "en").destination.language)
        // A remembered profile is found again by id.
        dir(prefs, keyboard = "en").chooseDestination(mum)
        assertEquals("Mum", dir(prefs, keyboard = "en").destination.label)
    }

    @Test fun anAppRuleComesFirst() {
        val prefs = AiLanguagePrefs.InMemory(preferredTarget = "ti").apply { pairs["am"] = "lang:om" }
        assertEquals("Mum", dir(prefs, keyboard = "en", rule = mum).destination.label)
        assertEquals("a rule into the source language itself is skipped", "om", dir(prefs, keyboard = "am", rule = mum).destination.language)
    }

    @Test fun swapTurnsTheDirectionAroundAndRemembersIt() {
        val prefs = AiLanguagePrefs.InMemory()
        val d = dir(prefs, keyboard = "am")
        assertEquals("en", d.destination.language)
        d.swap()
        assertFalse(d.isAuto)
        assertEquals("en", d.source)
        assertEquals("am", d.destination.language)
        assertEquals("lang:am", prefs.pairs["en"])
        d.swap()
        assertEquals("am", d.source)
        assertEquals("en", d.destination.language)
    }

    @Test fun choosingTheDestinationsLanguageAsSourceFallsBackToItsDefault() {
        val d = dir(keyboard = "am")
        d.chooseDestination(AiLanguages.plain("ti"))
        d.chooseSource("ti")
        assertEquals("en", d.destination.language)
        d.chooseSource(null)
        assertTrue(d.isAuto)
        assertEquals("am", d.source)
    }

    @Test fun aNewFieldStartsFromAutoAndTheDefaults() {
        val d = dir(keyboard = "am")
        d.chooseSource("en")
        d.start("am", null)
        assertTrue(d.isAuto)
        assertEquals("en", d.destination.language)
    }

    // ------------------------------------------------------------ ADR 0037: the translate panel's lists and the detected source

    @Test fun theSourceListStartsWithDetectThenTheCurrentAndKeyboardLanguages() {
        val d = dir(keyboard = "ti")
        assertEquals(listOf(null, "ti", "am", "om", "en", "ar"), d.sourceChoices())
        d.chooseSource("ar")
        assertEquals(listOf(null, "ar", "ti", "am", "om", "en"), d.sourceChoices())
    }

    @Test fun theDestinationListHasTheCurrentFirstThenLanguagesThenProfiles() {
        val d = dir(keyboard = "am")
        // The source language is left out; the profile (Amharic) too, since the source is Amharic.
        assertEquals(listOf("lang:en", "lang:ti", "lang:om", "lang:ar"), d.destinationChoices().map { it.id })
        val e = dir(keyboard = "en")
        assertEquals(listOf("lang:am", "lang:ti", "lang:om", "lang:ar", "p1"), e.destinationChoices().map { it.id })
        e.chooseDestination(mum)
        assertEquals(listOf("p1", "lang:am", "lang:ti", "lang:om", "lang:ar"), e.destinationChoices().map { it.id })
    }

    @Test fun theDetectedSourceIsShownWhileAutoOnly() {
        val d = dir(keyboard = "en")
        assertEquals("en", d.likelySource(null))
        assertEquals("om", d.likelySource("om"))
        assertEquals("Detect · Afaan Oromoo", d.sourceLabel("Detect", "om"))
        assertEquals("an unknown code is ignored", "Detect · English", d.sourceLabel("Detect", "xx"))
        d.chooseSource("am")
        assertEquals("an explicit source wins", "Amharic", d.sourceLabel("Detect", "om"))
    }

    @Test fun swapUsesTheDetectedSourceWhileAuto() {
        val prefs = AiLanguagePrefs.InMemory()
        val d = dir(prefs, keyboard = "en")
        assertEquals("am", d.destination.language)
        d.swap(detected = "ti")
        assertEquals("am", d.source)
        assertEquals("ti", d.destination.language)
        assertEquals("lang:ti", prefs.pairs["am"])
    }

    @Test fun selfNamesAndDirection() {
        assertEquals("አማርኛ", AiLanguages.selfName("am"))
        assertEquals("العربية", AiLanguages.selfName("ar"))
        assertTrue(AiLanguages.isRtl("ar"))
        assertFalse(AiLanguages.isRtl("am"))
        assertFalse(AiLanguages.isRtl(null))
    }
}
