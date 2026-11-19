package com.qalsmartkeyboard.app.settings

import com.qalsmartkeyboard.app.themes.STRIP_SIZE
import com.qalsmartkeyboard.app.themes.stripThemeIds
import com.qalsmartkeyboard.ime.theme.ThemeCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The settings redesign's pure parts (ADR 0036): back stack, theme strip order, language names and search. */
class SettingsLogicTest {
    @Test fun theBackStackStartsAtHomeAndSurvivesSaving() {
        val nav = SettingsNavigator(emptyList())
        assertTrue(nav.isHome)
        assertNull(nav.current)
        nav.open(SettingsPage.LANGUAGES)
        nav.open(SettingsPage.LANGUAGE, "am")
        assertEquals(SettingsDestination(SettingsPage.LANGUAGE, "am"), nav.current)
        val restored = SettingsNavigator(nav.keys())
        assertEquals(SettingsDestination(SettingsPage.LANGUAGE, "am"), restored.current)
        assertTrue(restored.back())
        assertEquals(SettingsDestination(SettingsPage.LANGUAGES), restored.current)
        assertTrue(restored.back())
        assertFalse(restored.back())
        nav.reset()
        assertTrue(nav.isHome)
    }

    @Test fun anUnknownSavedPageIsDropped() {
        assertTrue(SettingsNavigator(listOf("NO_SUCH_PAGE")).isHome)
    }

    @Test fun theStripStartsWithTheAppliedTheme() {
        assertEquals(ThemeCatalog.SYSTEM_ID, stripThemeIds("system").first())
        val aurora = stripThemeIds("aurora")
        assertEquals("aurora", aurora.first())
        assertEquals(STRIP_SIZE, aurora.size)
        assertEquals(aurora.distinct(), aurora)
        assertEquals(ThemeCatalog.SYSTEM_ID, stripThemeIds("neon_future").first()) // unknown id = system
    }

    @Test fun languagesAreNamedInThemselves() {
        assertEquals("አማርኛ", nativeLanguageName("am"))
        assertEquals("ትግርኛ", nativeLanguageName("ti"))
        assertEquals("Afaan Oromoo", nativeLanguageName("om"))
        assertEquals("English", nativeLanguageName("en"))
        // A language added to LanguageRules.KNOWN later (e.g. Arabic) is named by the platform until it has a resource.
        assertEquals("العربية", nativeLanguageName("ar"))
        assertEquals("zz", nativeLanguageName("zz"))
    }

    @Test fun searchMatchesCodeSelfNameAndUiName() {
        assertTrue(languageMatches("", "am", "አማርኛ", "Amharic"))
        assertTrue(languageMatches("amh", "am", "አማርኛ", "Amharic"))
        assertTrue(languageMatches("ትግ", "ti", "ትግርኛ", "Tigrinya"))
        assertTrue(languageMatches(" OM ", "om", "Afaan Oromoo", "Afaan Oromoo"))
        assertFalse(languageMatches("tig", "am", "አማርኛ", "Amharic"))
    }
}
