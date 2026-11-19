package com.qalsmartkeyboard.app.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import com.qalsmartkeyboard.app.themes.THEME_STRIP_TAG
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferencesSerializer
import com.qalsmartkeyboard.app.SettingsScreen
import com.qalsmartkeyboard.data.InputMode
import com.qalsmartkeyboard.data.KeyboardSettings
import com.qalsmartkeyboard.data.SettingsRepository
import com.qalsmartkeyboard.ime.theme.PremiumThemeAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * The redesigned settings (ADR 0036) on the JVM (Robolectric), backed by a real [SettingsRepository]: the theme strip
 * at the top, navigation and Back, the language pages (enable / disable with the last-language rule, reorder, layout,
 * search) and the keyboard-gear return.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h3000dp")
class SettingsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val dir: File = Files.createTempDirectory("settings-ui").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository = SettingsRepository(
        DataStoreFactory.create(
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { File(dir, "s.preferences_pb").absolutePath.toPath() },
            scope = scope,
        ),
    )

    private val themesOpened = ArrayList<String?>()
    private var backToTyping = 0
    private var setupRuns = 0

    @After fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun show(
        access: PremiumThemeAccess = PremiumThemeAccess.NOT_ON_PLAN,
        keyboardReady: Boolean = true,
        fromKeyboard: Boolean = false,
    ) = compose.setContent {
        val settings by repository.settings.collectAsState(initial = KeyboardSettings.DEFAULT)
        SettingsScreen(
            settings, repository,
            themeAccess = access,
            keyboardReady = keyboardReady,
            fromKeyboard = fromKeyboard,
            onBackToTyping = { backToTyping++ },
            onRunSetup = { setupRuns++ },
            onOpenThemes = { themesOpened += it },
            dictionaryContent = {}, // no native core on the JVM
            appVersion = "1.0",
            coreVersion = "0.1.0",
        )
    }

    private fun stored(): KeyboardSettings = runBlocking { repository.current() }

    private fun waitFor(condition: (KeyboardSettings) -> Boolean) = compose.waitUntil(5_000) { condition(stored()) }

    /** The strip is a lazy row: a tile far along is not composed until the row is scrolled to it. */
    private fun stripTile(description: String) = compose.onNodeWithTag(THEME_STRIP_TAG).performScrollToNode(hasContentDescription(description)).let {
        compose.onNodeWithContentDescription(description)
    }

    private fun systemBack() = compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }

    // ---------------------------------------------------------------- home and theme strip

    @Test fun theThemeSectionComesFirstWithMiniaturesAndViewMore() {
        show()
        val theme = compose.onNodeWithText("Theme").getUnclippedBoundsInRoot()
        val languages = compose.onNodeWithText("Languages").getUnclippedBoundsInRoot()
        assertTrue("Theme heading above the rows", theme.bottom <= languages.top)
        compose.onNodeWithText("Theme").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        // The applied theme first and marked; free themes after it.
        compose.onNodeWithContentDescription("System, Selected").assertIsSelected()
        compose.onAllNodesWithContentDescription("Selected", substring = true).assertCountEquals(1)
        stripTile("Midnight").assertExists()
        compose.onNodeWithText("View more").performClick()
        assertEquals(listOf<String?>(null), themesOpened)
    }

    @Test fun theStripScrollsToEveryThemeAndAppliesANewOne() {
        show()
        // The last free (new) theme is far along the row; it is reachable and applies.
        stripTile("Pastel Rainbow").performClick()
        waitFor { it.theme == "pastel_rainbow" }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Pastel Rainbow, Selected").assertIsSelected()
    }

    @Test fun tappingAFreeThemeAppliesIt() {
        show()
        stripTile("Midnight").performClick()
        waitFor { it.theme == "midnight" }
        compose.waitForIdle()
        // The applied theme moves to the front and carries the mark.
        compose.onNodeWithContentDescription("Midnight, Selected").assertIsSelected()
        assertTrue(themesOpened.isEmpty())
    }

    @Test fun aLockedPremiumThemeOpensThePickerInsteadOfApplying() {
        runBlocking { repository.update { it.copy(theme = "aurora") } }
        show(access = PremiumThemeAccess.NOT_ON_PLAN)
        // The chosen premium theme stays first, with its lock, so the way to unlock it is one tap away.
        compose.onNodeWithContentDescription("Aurora, Premium theme, locked, Selected").performClick()
        assertEquals(listOf<String?>("aurora"), themesOpened)
        stripTile("Midnight").performClick()
        waitFor { it.theme == "midnight" } // a free theme still applies
        assertEquals(listOf<String?>("aurora"), themesOpened)
    }

    @Test fun anEntitledAccountAppliesItsPremiumThemeFromTheStrip() {
        runBlocking { repository.update { it.copy(theme = "midnight") } }
        show(access = PremiumThemeAccess.ALLOWED)
        compose.onNodeWithContentDescription("Midnight, Selected").assertExists()
        compose.onNodeWithContentDescription("System").performClick()
        waitFor { it.theme == "system" }
        assertTrue(themesOpened.isEmpty())
    }

    @Test fun theSetupPromptShowsOnlyWhenTheKeyboardIsNotReady() {
        show(keyboardReady = false)
        compose.onNodeWithText("Finish setting up the keyboard").performClick()
        assertEquals(1, setupRuns)
        compose.onAllNodesWithText("Keyboard setup").assertCountEquals(0)
    }

    @Test fun whenReadySetupIsAnOrdinaryRow() {
        show(keyboardReady = true)
        compose.onAllNodesWithText("Finish setting up the keyboard").assertCountEquals(0)
        compose.onNodeWithText("Keyboard setup").performClick()
        assertEquals(1, setupRuns)
    }

    @Test fun homeRowsSummariseTheirValues() {
        show()
        compose.onNodeWithText("Amharic, Tigrinya, Afaan Oromoo, English").assertExists()
        compose.onNodeWithText("Not signed in").assertExists()
        compose.onNodeWithText("Automatic").assertExists()
    }

    // ---------------------------------------------------------------- navigation

    @Test fun subPagesOpenAndBackReturnsHome() {
        show()
        compose.onNodeWithText("Preferences").performClick()
        compose.onNodeWithText("Haptic feedback").assertIsOn()
        compose.onNodeWithText("Haptic feedback").performClick() // the whole row toggles
        waitFor { !it.hapticsEnabled }
        systemBack()
        compose.onNodeWithText("View more").assertExists()

        compose.onNodeWithText("Advanced").performClick()
        compose.onNodeWithText("Update language packs on mobile data").assertIsOff()
        compose.onNodeWithContentDescription("Back").performClick() // the bar's arrow
        compose.onNodeWithText("View more").assertExists()

        compose.onNodeWithText("About").performClick()
        compose.onNodeWithText("0.1.0").assertExists()
        systemBack()
        compose.onNodeWithText("Translate into").performClick()
        compose.onNodeWithText("English, or Amharic for English text").assertExists()
        compose.onNodeWithText("ትግርኛ").performClick()
        waitFor { it.aiPreferredTarget == "ti" }
        systemBack()
        compose.onNodeWithText("Tigrinya").assertExists() // the row's summary
    }

    /** ADR 0035: the keyboard's clipboard history is a Preferences switch, on by default. */
    @Test fun clipboardHistorySwitchIsOnByDefaultAndToggles() {
        show()
        compose.onNodeWithText("Preferences").performClick()
        compose.onNodeWithText("Clipboard history").performScrollTo().assertIsOn()
        compose.onNodeWithText("Recent copies, kept for 1 hour in memory only").assertExists()
        compose.onNodeWithText("Clipboard history").performClick()
        waitFor { !it.clipboardHistoryEnabled }
    }

    @Test fun fromTheKeyboardTheArrowAndBackReturnToTyping() {
        show(fromKeyboard = true)
        compose.onNodeWithContentDescription("Back to typing").performClick()
        assertEquals(1, backToTyping)
        compose.onNodeWithText("Languages").performClick()
        systemBack() // a page first goes back to the home page
        assertEquals(1, backToTyping)
        systemBack()
        assertEquals(2, backToTyping)
    }

    @Test fun resetAsksFirst() {
        runBlocking { repository.update { it.copy(hapticsEnabled = false, crashReports = false) } }
        show()
        compose.onNodeWithText("Advanced").performClick()
        compose.onNodeWithText("Reset to defaults").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(false, stored().hapticsEnabled)
        compose.onNodeWithText("Reset to defaults").performClick()
        compose.onNodeWithText("Reset").performClick()
        waitFor { it.hapticsEnabled }
        assertEquals(false, stored().crashReports) // a privacy decision survives the reset
    }

    // ---------------------------------------------------------------- languages

    @Test fun languagesListsTheEnabledLanguagesWithSelfNames() {
        show()
        compose.onNodeWithText("Languages").performClick()
        compose.onNodeWithText("አማርኛ").assertExists()
        compose.onNodeWithText("ትግርኛ").assertExists()
        compose.onNodeWithText("Amharic · Phonetic QWERTY").assertExists()
        compose.onNodeWithText("Tigrinya · Phonetic QWERTY").assertExists()
        compose.onNodeWithText("Afaan Oromoo").assertExists()
        compose.onNodeWithContentDescription("Move Amharic up").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Move English down").assertIsNotEnabled()
    }

    @Test fun reorderMovesBetweenEnabledLanguages() {
        show()
        compose.onNodeWithText("Languages").performClick()
        compose.onNodeWithContentDescription("Move Tigrinya up").performClick()
        waitFor { it.enabledLanguages == listOf("ti", "am", "om", "en") }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Move Tigrinya up").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Move Amharic down").performClick()
        waitFor { it.enabledLanguages == listOf("ti", "om", "am", "en") }
    }

    @Test fun reorderSkipsADisabledLanguage() {
        runBlocking { repository.setLanguageEnabled("ti", false) }
        show()
        compose.onNodeWithText("Languages").performClick()
        compose.onAllNodesWithText("ትግርኛ").assertCountEquals(0)
        compose.onNodeWithContentDescription("Move Afaan Oromoo up").performClick()
        waitFor { it.enabledLanguages == listOf("om", "am", "en") }
    }

    @Test fun enableAndDisableKeepAtLeastOneLanguage() {
        show()
        compose.onNodeWithText("Languages").performClick()
        compose.onNodeWithText("Add or remove languages").performClick()
        compose.onNodeWithText("All languages").assertExists()
        compose.onNodeWithText("አማርኛ").assertIsOn().performClick()
        compose.onNodeWithText("ትግርኛ").performClick()
        compose.onNodeWithText("Afaan Oromoo").performClick()
        waitFor { it.enabledLanguages == listOf("en") }
        compose.waitForIdle()
        compose.onNodeWithText("At least one language stays on").assertExists()
        compose.onNodeWithText("English").assertIsOn().assertIsNotEnabled().performClick()
        assertEquals(listOf("en"), stored().enabledLanguages)
        compose.onNodeWithText("ትግርኛ").assertIsOff().performClick()
        waitFor { it.enabledLanguages == listOf("ti", "en") }
        systemBack()
        compose.onNodeWithText("ትግርኛ").assertExists()
        compose.onAllNodesWithText("አማርኛ").assertCountEquals(0)
    }

    @Test fun searchFiltersTheLanguages() {
        show()
        compose.onNodeWithText("Languages").performClick()
        compose.onNodeWithText("Add or remove languages").performClick()
        compose.onNodeWithText("Search languages").performTextInput("tig")
        compose.onNodeWithText("ትግርኛ").assertExists()
        compose.onAllNodesWithText("አማርኛ").assertCountEquals(0)
        compose.onAllNodesWithText("English").assertCountEquals(0)
        compose.onNodeWithText("tig").performTextReplacement("አማ") // the self-name matches too
        compose.onNodeWithText("አማርኛ").assertExists()
        compose.onNodeWithText("አማ").performTextReplacement("xyz")
        compose.onNodeWithText("No languages found").assertExists()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithText("English").assertExists()
    }

    @Test fun aLanguagePageChoosesTheLayout() {
        show()
        compose.onNodeWithText("Languages").performClick()
        compose.onNodeWithText("አማርኛ").performClick()
        compose.onNodeWithText("Layout").assertExists()
        compose.onNodeWithText("Phonetic QWERTY").assertIsSelected()
        compose.onNodeWithText("Show suggestions").assertIsOn().performClick()
        waitFor { it.language("am")?.showTransliterationCandidates == false }
        compose.onNodeWithText("Fidel grid").performClick()
        waitFor { it.language("am")?.inputMode == InputMode.DIRECT_SCRIPT_LAYOUT }
        compose.waitForIdle()
        compose.onNodeWithText("Fidel grid").assertIsSelected()
        compose.onAllNodesWithText("Show suggestions").assertCountEquals(0) // phonetic only
        systemBack()
        compose.onNodeWithText("Amharic · Fidel grid").assertExists()
    }

    @Test fun aSingleLayoutLanguageNamesItsOwnLayoutNotLatin() {
        show()
        compose.onNodeWithText("Languages").performClick()
        compose.onNodeWithText("Qubee").assertExists() // Afaan Oromoo's row
        compose.onNodeWithText("QWERTY").assertExists() // English's row
        compose.onNodeWithText("Afaan Oromoo").performClick()
        compose.onNodeWithText("Layout").assertExists()
        compose.onNodeWithText("Qubee").assertExists()
        compose.onAllNodesWithText("Latin").assertCountEquals(0)
        compose.onAllNodesWithText("Fidel grid").assertCountEquals(0)
    }

    @Test fun arabicIsOptInAndShownWithItsOwnLayout() {
        assertTrue(stored().languages.any { it.languageCode == "ar" && !it.enabled })
        runBlocking { repository.setLanguageEnabled("ar", true) }
        show()
        compose.onNodeWithText("Languages").performClick()
        compose.onNodeWithText("العربية").assertExists()
        compose.onNodeWithText("Arabic").assertExists() // UI-language name; the layout name is not repeated
        compose.onAllNodesWithText("Arabic · Latin").assertCountEquals(0)
        compose.onNodeWithText("العربية").performClick()
        compose.onNodeWithText("Layout").assertExists()
        compose.onAllNodesWithText("Latin").assertCountEquals(0)
    }
}
