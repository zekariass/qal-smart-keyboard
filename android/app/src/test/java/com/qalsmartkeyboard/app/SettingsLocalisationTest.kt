package com.qalsmartkeyboard.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferencesSerializer
import com.qalsmartkeyboard.data.KeyboardSettings
import com.qalsmartkeyboard.data.SettingsRepository
import com.qalsmartkeyboard.ime.KeyboardLabels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * ADR 0032: the settings (home, App language, Languages, Preferences, Advanced; ADR 0036) render in Amharic, Tigrinya
 * and Afaan Oromoo with no missing resource; App language names every option in its own language.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h3000dp")
class SettingsLocalisationTest {
    @get:Rule val compose = createComposeRule()

    private val dir: File = Files.createTempDirectory("settings-l10n").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository = SettingsRepository(
        DataStoreFactory.create(
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { File(dir, "s.preferences_pb").absolutePath.toPath() },
            scope = scope,
        ),
    )

    @After fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
        KeyboardLabels.reset()
    }

    private fun renders() {
        val c = RuntimeEnvironment.getApplication()
        val languages = c.getString(R.string.settings_languages_title)
        assertNotEquals("Languages", languages)
        compose.setContent { SettingsScreen(KeyboardSettings.DEFAULT, repository, dictionaryContent = {}) } // no native core on the JVM
        // Home: the theme strip, then the groups.
        for (id in listOf(R.string.settings_theme_title, R.string.settings_theme_more, R.string.settings_group_keyboard, R.string.settings_preferences)) {
            compose.onNodeWithText(c.getString(id)).performScrollTo().assertExists()
        }
        // App language: every option in its own language.
        compose.onNodeWithText(c.getString(R.string.settings_app_language_title)).performScrollTo().performClick()
        compose.onNodeWithText(c.getString(R.string.settings_app_language_system)).assertExists()
        for (self in listOf("English", "አማርኛ", "ትግርኛ", "Afaan Oromoo")) compose.onNodeWithText(self).assertExists()
        compose.onNodeWithContentDescription(c.getString(R.string.onb_back)).performClick()
        // Languages: self-names, each with its name in the UI language.
        compose.onNodeWithText(languages).performScrollTo().performClick()
        compose.onNodeWithText(c.getString(R.string.settings_languages_order)).assertExists()
        for (self in listOf("አማርኛ", "ትግርኛ", "Afaan Oromoo", "English")) compose.onNodeWithText(self).assertExists()
        compose.onNodeWithContentDescription(c.getString(R.string.onb_back)).performClick()
        compose.onNodeWithText(c.getString(R.string.settings_preferences)).performScrollTo().performClick()
        compose.onNodeWithText(c.getString(R.string.settings_key_height, KeyboardSettings.DEFAULT.keyHeightPercent)).assertExists()
        compose.onNodeWithContentDescription(c.getString(R.string.onb_back)).performClick()
        compose.onNodeWithText(c.getString(R.string.settings_advanced)).performScrollTo().performClick()
        compose.onNodeWithText(c.getString(R.string.settings_reset)).assertExists()
    }

    @Test @Config(qualifiers = "am-w411dp-h3000dp")
    fun amharic() = renders()

    @Test @Config(qualifiers = "ti-w411dp-h3000dp")
    fun tigrinya() = renders()

    @Test @Config(qualifiers = "om-w411dp-h3000dp")
    fun oromo() = renders()
}
