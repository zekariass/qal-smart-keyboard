package com.qalsmartkeyboard.app.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.qalsmartkeyboard.app.ai.BaseUrlGuard
import com.qalsmartkeyboard.data.KeyboardSettings
import com.qalsmartkeyboard.ime.ai.TranslationCatalog
import com.qalsmartkeyboard.ime.ai.TranslationLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ADR 0040: "Translate into" lists the translation catalogue (searchable); the debug base-URL guard. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h3000dp")
class TranslateIntoPageTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val server = TranslationCatalog.of(
        TranslationCatalog.BUNDLED.languages + listOf(
            TranslationLanguage("fr", "French", "Français"),
            TranslationLanguage("he", "Hebrew", "עברית", rtl = true),
            TranslationLanguage("es", "Spanish", "Español"),
        ),
    )

    private var settings by mutableStateOf(KeyboardSettings.DEFAULT)

    private fun show(catalog: TranslationCatalog) = compose.setContent {
        TranslationPage(settings, onBack = {}, onUpdate = { t -> settings = t(settings) }, catalog = catalog)
    }

    @Test fun theServerCatalogueIsListedAndSearchable() {
        show(server)
        compose.onNodeWithText("Français").assertExists()
        compose.onNodeWithText("עברית").assertExists()
        compose.onNodeWithText("ትግርኛ").assertExists()
        compose.onNodeWithText("Search languages").performTextInput("fren")
        compose.onNodeWithText("Español").assertDoesNotExist()
        compose.onNodeWithText("ትግርኛ").assertDoesNotExist()
        compose.onNodeWithText("Français").performClick()
        compose.waitUntil(5_000) { settings.aiPreferredTarget == "fr" }
        assertEquals("fr", settings.aiPreferredTarget)
    }

    @Test fun nothingFoundSaysSo() {
        show(server)
        compose.onNodeWithText("Search languages").performTextInput("zzzz")
        compose.onNodeWithText("No language found").assertExists()
    }

    @Test fun offlineTheFiveKeyboardLanguagesAreOfferedWithANote() {
        show(TranslationCatalog.BUNDLED)
        compose.onNodeWithText("አማርኛ").assertExists()
        compose.onNodeWithText("Français").assertDoesNotExist()
        compose.onNodeWithText("More languages appear here once the app has been online.").assertExists()
    }

    @Test fun theDebugGuardWarnsOnlyForTheEmulatorHostOnAPhone() {
        val warnings = ArrayList<String>()
        assertTrue(BaseUrlGuard.check("http://10.0.2.2:8080", isEmulator = false) { warnings += it })
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("adb reverse"))
        assertFalse(BaseUrlGuard.check("http://10.0.2.2:8080", isEmulator = true) { warnings += it })
        assertFalse(BaseUrlGuard.check("http://localhost:8080", isEmulator = false) { warnings += it })
        assertEquals(1, warnings.size)
        assertTrue(BaseUrlGuard.isEmulator(fingerprint = "google/sdk_gphone64_x86_64/emu64x:14", model = "sdk_gphone64_x86_64", hardware = "ranchu", product = "sdk_gphone64_x86_64"))
        assertFalse(BaseUrlGuard.isEmulator(fingerprint = "samsung/a52qnsxx/a52q:14/UP1A", model = "SM-A525F", hardware = "qcom", product = "a52qnsxx"))
    }
}
