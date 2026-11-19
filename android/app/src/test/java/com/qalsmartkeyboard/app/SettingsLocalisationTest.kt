package com.qalsmartkeyboard.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
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
 * ADR 0032: the main settings screen renders in Amharic, Tigrinya and Afaan Oromoo with no missing resource; the App
 * language card names every option in its own language.
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
        val title = c.getString(R.string.settings_title)
        assertNotEquals("Keyboard settings", title)
        compose.setContent { SettingsScreen(KeyboardSettings.DEFAULT, repository, dictionaryContent = {}) } // no native core on the JVM
        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithText(c.getString(R.string.settings_app_language_title)).assertExists()
        compose.onNodeWithText(c.getString(R.string.settings_app_language_system)).assertExists()
        // A self-name can also be a typing language's name on this screen (ትግርኛ in Tigrinya): the first match is enough.
        for (self in listOf("English", "አማርኛ", "ትግርኛ", "Afaan Oromoo")) compose.onAllNodesWithText(self).onFirst().performScrollTo().assertExists()
        compose.onNodeWithText(c.getString(R.string.settings_key_height, KeyboardSettings.DEFAULT.keyHeightPercent)).performScrollTo().assertExists()
        compose.onNodeWithText(c.getString(R.string.settings_reset)).performScrollTo().assertExists()
    }

    @Test @Config(qualifiers = "am-w411dp-h3000dp")
    fun amharic() = renders()

    @Test @Config(qualifiers = "ti-w411dp-h3000dp")
    fun tigrinya() = renders()

    @Test @Config(qualifiers = "om-w411dp-h3000dp")
    fun oromo() = renders()
}
