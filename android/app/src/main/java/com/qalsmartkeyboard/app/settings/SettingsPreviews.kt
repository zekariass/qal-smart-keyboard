package com.qalsmartkeyboard.app.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.qalsmartkeyboard.app.ui.theme.QalSmartKeyboardTheme
import com.qalsmartkeyboard.data.KeyboardSettings

// Previews of the main settings pages (ADR 0036): light, dark, and a small phone with large text.

private val sample = KeyboardSettings.DEFAULT.sanitized().let { s ->
    s.copy(languages = s.languages.map { if (it.languageCode == "om") it.copy(enabled = false) else it })
}

@Composable
private fun Themed(dark: Boolean, content: @Composable () -> Unit) = QalSmartKeyboardTheme(darkTheme = dark, dynamicColor = false, content = content)

@Preview(name = "Home, light", widthDp = 360, heightDp = 1100)
@Composable
private fun HomeLightPreview() = Themed(false) {
    SettingsHomePage(sample, SettingsHomeState(keyboardReady = false, accountSummary = "Not signed in", appLanguageSummary = "System default"), SettingsHomeActions())
}

@Preview(name = "Home, dark", widthDp = 360, heightDp = 1100)
@Composable
private fun HomeDarkPreview() = Themed(true) {
    SettingsHomePage(sample, SettingsHomeState(systemDark = true, accountSummary = "Signed in", appLanguageSummary = "አማርኛ"), SettingsHomeActions())
}

@Preview(name = "Home, large text", widthDp = 360, heightDp = 1300, fontScale = 1.3f)
@Composable
private fun HomeLargeTextPreview() = Themed(false) {
    SettingsHomePage(sample, SettingsHomeState(accountSummary = "Guest", appLanguageSummary = "System default", fromKeyboard = true), SettingsHomeActions())
}

@Preview(name = "Languages", widthDp = 360, heightDp = 640)
@Composable
private fun LanguagesPreview() = Themed(false) {
    LanguagesPage(sample, onBack = {}, onOpenLanguage = {}, onMove = { _, _ -> }, onManage = {})
}

@Preview(name = "All languages, dark", widthDp = 360, heightDp = 640)
@Composable
private fun AllLanguagesPreview() = Themed(true) {
    AllLanguagesPage(sample, onBack = {}, onEnable = { _, _ -> })
}

@Preview(name = "Amharic, large text", widthDp = 360, heightDp = 760, fontScale = 1.3f)
@Composable
private fun LanguagePreview() = Themed(false) {
    LanguagePage(sample.language("am")!!, onBack = {}, onMode = {}, onShowSuggestions = {})
}

@Preview(name = "Preferences", widthDp = 360, heightDp = 640)
@Composable
private fun PreferencesPreview() = Themed(false) {
    PreferencesPage(sample, onBack = {}, onUpdate = {})
}
