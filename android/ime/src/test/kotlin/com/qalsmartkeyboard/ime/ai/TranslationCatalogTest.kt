package com.qalsmartkeyboard.ime.ai

import com.qalsmartkeyboard.ime.ComposerUpdate
import com.qalsmartkeyboard.ime.FakeEditor
import com.qalsmartkeyboard.ime.InputConnectionAdapter
import com.qalsmartkeyboard.ime.InputProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR 0040: the translation language catalogue in the keyboard: the bundled fallback, the server's list, search and
 * order of the long lists, RTL names, the direction with translation-only languages, the translate panel's search, and
 * the panels never staying in their loading state.
 */
class TranslationCatalogTest {
    companion object {
        fun lang(code: String, english: String, native: String, rtl: Boolean = false, typing: Boolean = false, source: Boolean = true, destination: Boolean = true) =
            TranslationLanguage(code, english, native, rtl = rtl, asSource = source, asDestination = destination, typing = typing)

        /** A server catalogue: the five keyboard languages and some translation-only ones. */
        val SERVER = TranslationCatalog.of(
            TranslationCatalog.BUNDLED.languages + listOf(
                lang("fr", "French", "Français"),
                lang("es", "Spanish", "Español"),
                lang("de", "German", "Deutsch"),
                lang("he", "Hebrew", "עברית", rtl = true),
                lang("zh-CN", "Chinese (Simplified)", "简体中文"),
                lang("sw", "Swahili", "Kiswahili", typing = true),
                lang("la", "Latin", "Latina", destination = false),
            ),
        )
    }

    @After fun reset() {
        TranslationLanguages.current = TranslationCatalog.BUNDLED
    }

    // ------------------------------------------------------------ catalogue

    @Test fun theBundledCatalogueIsTheFiveKeyboardLanguages() {
        val b = TranslationCatalog.BUNDLED
        assertEquals(listOf("am", "ti", "om", "en", "ar"), b.languages.map { it.code })
        assertFalse(b.fromServer)
        assertTrue(b.find("ar")!!.rtl)
        assertTrue(b.languages.all { it.typing && it.asSource && it.asDestination })
        assertFalse(b.isKnown("fr"))
    }

    @Test fun anEmptyServerListFallsBackToTheBundledOne() {
        assertSame(TranslationCatalog.BUNDLED, TranslationCatalog.of(emptyList()))
        assertTrue(SERVER.fromServer)
        assertTrue(SERVER.isSource("la"))
        assertFalse(SERVER.isDestination("la"))
        assertEquals(SERVER, TranslationCatalog.of(SERVER.languages))
    }

    // ------------------------------------------------------------ names and RTL

    @Test fun namesComeFromTheCatalogueAndTheUiLanguage() {
        TranslationLanguages.current = SERVER
        assertEquals("Amharic", AiLanguages.name("am")) // the keyboard's own label
        assertEquals("French", AiLanguages.name("fr")) // English UI: the catalogue's English name
        assertEquals("Chinese (Simplified)", AiLanguages.name("zh-CN"))
        assertEquals("Français", AiLanguages.selfName("fr"))
        assertEquals("አማርኛ", AiLanguages.selfName("am"))
        assertTrue(AiLanguages.isRtl("he"))
        assertTrue(AiLanguages.isRtl("ar"))
        assertFalse(AiLanguages.isRtl("fr"))
        assertEquals("⁧עברית⁩", AiLanguages.isolated("עברית", true))
        assertEquals("Français", AiLanguages.isolated("Français", false))
    }

    @Test fun thePlatformNameIsUsedInAnotherUiLanguage() {
        val german = PlatformLanguageNames.name("fr", java.util.Locale.GERMAN)
        assertEquals("Französisch", german)
        assertNull("English UI: the catalogue's names", PlatformLanguageNames.name("fr", java.util.Locale.ENGLISH))
        assertNull("no platform data: the English name instead", PlatformLanguageNames.name("bew", java.util.Locale.GERMAN))
    }

    // ------------------------------------------------------------ search and order

    @Test fun searchMatchesNamesAutonymsAndCodesIgnoringCaseAndAccents() {
        val fr = SERVER.find("fr")!!
        for (q in listOf("fr", "FRE", "francais", "Franç", "  french ")) assertTrue(q, TranslationLanguageSearch.matches(q, fr, "French"))
        assertFalse(TranslationLanguageSearch.matches("ger", fr, "French"))
        assertTrue(TranslationLanguageSearch.matches("simpl", SERVER.find("zh-CN")!!, "Chinese (Simplified)")) // a later word
        assertTrue(TranslationLanguageSearch.matches("zh", SERVER.find("zh-CN")!!, "Chinese (Simplified)")) // the code
        assertTrue(TranslationLanguageSearch.matches("עב", SERVER.find("he")!!, "Hebrew")) // the autonym
        assertTrue(TranslationLanguageSearch.matches("", fr, "French"))
    }

    @Test fun theOrderIsFirstThenTheKeyboardLanguagesThenByName() {
        val ordered = TranslationLanguageSearch.order(SERVER.languages, listOf("he", "ti", "xx")).map { it.code }
        assertEquals(listOf("he", "ti", "am", "om", "en", "ar"), ordered.take(6))
        assertEquals(listOf("zh-CN", "fr", "de", "la", "es", "sw"), ordered.drop(6)) // Chinese, French, German, Latin, Spanish, Swahili
    }

    @Test fun recentLanguagesComeFromTheRememberedPairsNewestFirst() {
        val prefs = AiLanguagePrefs.InMemory()
        prefs.rememberTarget("en", "lang:fr")
        prefs.rememberTarget("am", "p1")
        prefs.rememberTarget("en", "lang:de")
        assertEquals(listOf("de", "en", "am"), prefs.recentLanguages())
    }

    // ------------------------------------------------------------ direction

    private fun dir(prefs: AiLanguagePrefs = AiLanguagePrefs.InMemory(), catalog: TranslationCatalog = SERVER, keyboard: String = "am") =
        TranslationDirection(prefs, { catalog }) { emptyList() }.also { it.start(keyboard, null) }

    @Test fun translationOnlyLanguagesCanBeChosenButNotWithoutTheCatalogue() {
        val d = dir()
        d.chooseSource("fr")
        assertEquals("fr", d.manualSource)
        assertEquals("en", d.destination.language) // the default rules stay: English for any other source
        d.chooseDestination(AiLanguages.plain("zh-CN"))
        assertEquals("zh-CN", d.destination.language)
        d.chooseSource("xx") // not in the catalogue: back to Auto
        assertTrue(d.isAuto)
        val offline = dir(catalog = TranslationCatalog.BUNDLED)
        offline.chooseSource("fr")
        assertTrue("the bundled list has no French", offline.isAuto)
        // a remembered or preferred translation-only destination needs the catalogue
        assertEquals("he", dir(AiLanguagePrefs.InMemory(preferredTarget = "he")).destination.language)
        assertEquals("en", dir(AiLanguagePrefs.InMemory(preferredTarget = "he"), TranslationCatalog.BUNDLED).destination.language)
    }

    @Test fun theDefaultRulesAreUnchanged() {
        assertEquals("en", dir(keyboard = "am").destination.language)
        assertEquals("am", dir(keyboard = "en").destination.language)
    }

    @Test fun theListsPutTheCurrentKeyboardAndRecentLanguagesFirstAndSearch() {
        val prefs = AiLanguagePrefs.InMemory().apply { rememberTarget("am", "lang:de") }
        val d = dir(prefs, keyboard = "am")
        val sources = d.sourceChoices()
        assertNull(sources.first()) // Detect
        assertEquals(listOf("am", "de", "ti", "om", "en", "ar"), sources.drop(1).take(6))
        assertEquals(SERVER.sources.size + 1, sources.size)
        assertEquals(listOf("es"), d.sourceChoices("span"))
        assertTrue("Detect only without a query", null !in d.sourceChoices("a"))

        val dests = d.destinationChoices().map { it.language }
        assertEquals("de", dests.first()) // the current destination
        assertFalse("never the source language", "am" in dests)
        assertFalse("not a destination", "la" in dests)
        assertEquals(listOf("he"), d.destinationChoices("hebr").map { it.language })
        assertTrue(d.destinationChoices("nothing like it").isEmpty())
    }

    // ------------------------------------------------------------ the translate panel's search

    private class H(val actions: FakeAiActions = FakeAiActions().apply { catalog = SERVER }) {
        val ed = FakeEditor("")
        val adapter = InputConnectionAdapter { ed }.also { it.startSession(false, 0, 0) }
        var changes = 0
        val c = TranslateController(
            actions = actions,
            editor = EditorAiAccess(adapter) { adapter.finishComposition() },
            post = { it() },
            copyToClipboard = {},
            keyboardLanguage = { "en" },
            onChanged = { changes++ },
        ).also {
            it.onFieldStarted(InputProfile.DEFAULT, "com.example.chat")
            it.open()
        }

        fun search(t: String) = c.search.sink.apply(ComposerUpdate(commit = t))
    }

    @Test fun theListSearchTakesTheKeysAndFiltersUntilAChoice() {
        val h = H()
        h.c.openPicker(TranslatePicker.DESTINATION)
        assertNull("no typing target while the list shows", h.c.fieldTyping())
        h.c.startSearch()
        assertTrue(h.c.searching)
        val typing = h.c.fieldTyping()!!
        assertSame(h.c.search.sink, typing.sink)
        h.search("fre")
        assertEquals(listOf("fr"), h.c.destinationChoices().map { it.language })
        typing.submit() // Enter: the keys give way, the query stays
        assertFalse(h.c.searching)
        assertEquals("fre", h.c.search.text)
        h.c.chooseDestination(h.c.destinationChoices().single())
        assertEquals("fr", h.c.destination.language)
        assertEquals(TranslatePicker.NONE, h.c.picker)
        assertEquals("", h.c.search.text)
        assertSame("the box gets the keys again", h.c.input.sink, h.c.fieldTyping()!!.sink)
    }

    @Test fun reopeningAListStartsWithAnEmptySearch() {
        val h = H()
        h.c.openPicker(TranslatePicker.SOURCE)
        h.c.startSearch()
        h.search("he")
        assertEquals(listOf("he"), h.c.sourceChoices())
        h.c.clearSearch()
        assertEquals("", h.c.search.text)
        h.search("ger")
        h.c.closePicker()
        assertFalse(h.c.searching)
        h.c.openPicker(TranslatePicker.SOURCE)
        assertEquals("", h.c.search.text)
        assertNull(h.c.sourceChoices().first())
    }

    @Test fun aDetectedTranslationOnlyLanguageIsShown() {
        val h = H()
        h.c.input.sink.apply(ComposerUpdate(commit = "Bonjour"))
        h.c.translate()
        h.actions.answer(0, AiOutcome.Success(AiResult("Hello", language = "en", sourceLanguage = "fr")))
        assertEquals("fr", h.c.detected)
    }

    // ------------------------------------------------------------ no stuck loading

    @Test fun anImplementationThatThrowsStillEndsTheRequest() {
        val h = H()
        h.actions.submitThrows = IllegalStateException("broken")
        h.c.input.sink.apply(ComposerUpdate(commit = "Hello"))
        h.c.translate()
        assertFalse(h.c.busy)
        assertEquals(AiFailure.Other, (h.c.step as TranslateStep.Failed).failure)
    }

    @Test fun anUnreachableServerEndsTheRequestAndRetriesWithTheSameKey() {
        val h = H()
        h.c.input.sink.apply(ComposerUpdate(commit = "Hello"))
        h.c.translate()
        assertTrue(h.c.busy)
        h.c.translate() // a double tap sends nothing more
        assertEquals(1, h.actions.submitted.size)
        val key = h.actions.submitted[0].request.requestId
        h.actions.answer(0, AiOutcome.Failure(AiFailure.Unreachable))
        assertFalse(h.c.busy)
        assertEquals(AiFailure.Unreachable, (h.c.step as TranslateStep.Failed).failure)
        assertEquals("the text stays", "Hello", h.c.input.text)
        h.c.retry()
        assertEquals(key, h.actions.submitted[1].request.requestId)
    }
}
