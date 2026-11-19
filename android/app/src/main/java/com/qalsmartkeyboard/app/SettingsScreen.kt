package com.qalsmartkeyboard.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qalsmartkeyboard.data.InputMode
import com.qalsmartkeyboard.data.KeyboardSettings
import com.qalsmartkeyboard.data.LanguageRules
import com.qalsmartkeyboard.data.NumeralStyle
import com.qalsmartkeyboard.data.SettingsRepository
import com.qalsmartkeyboard.ime.theme.ThemeCatalog
import com.qalsmartkeyboard.ime.theme.ThemeNames
import com.qalsmartkeyboard.ime.R as ImeR
import kotlinx.coroutines.launch

/** A typing language's name in the UI language (ADR 0032; the keyboard's resources). */
@Composable
internal fun languageName(code: String): String = when (code) {
    "am" -> stringResource(ImeR.string.language_name_am)
    "ti" -> stringResource(ImeR.string.language_name_ti)
    "om" -> stringResource(ImeR.string.language_name_om)
    "en" -> stringResource(ImeR.string.language_name_en)
    else -> code
}

@Composable
private fun modeLabel(m: InputMode) = stringResource(
    when (m) {
        InputMode.DIRECT_SCRIPT_LAYOUT -> R.string.onb_mode_fidel
        InputMode.PHONETIC_LATIN_TO_NATIVE -> R.string.settings_mode_phonetic
        InputMode.LATIN_PASSTHROUGH -> R.string.settings_mode_latin
    },
)

/** Minimal settings screen (A1.5): app language (ADR 0032), languages (enable, order, mode), numerals, theme, haptics, key height. */
@Composable
fun SettingsScreen(
    settings: KeyboardSettings,
    repository: SettingsRepository,
    modifier: Modifier = Modifier,
    onRunSetup: () -> Unit = {},
    /** The account card (A2.2), supplied by the host that owns the sign-in state. */
    accountContent: @Composable () -> Unit = {},
    /** The sync card (A2.4: personal-dictionary sync consent), supplied by the host that owns the account state. */
    syncContent: @Composable () -> Unit = {},
    /** Opens the theme picker (`ThemesScreen`), owned by the host. */
    onOpenThemes: () -> Unit = {},
    /** Opens the Word Art screen, owned by the host. */
    onOpenWordArt: () -> Unit = {},
    /** Opens the "Write Email with AI" screen, owned by the host (ADR 0031). */
    onOpenEmail: () -> Unit = {},
    /** The personal dictionary (A1.8); a slot so JVM tests can render the screen without the native core. */
    dictionaryContent: @Composable () -> Unit = { PersonalDictionarySection() },
) {
    val scope = rememberCoroutineScope()
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.settings_setup_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_setup_body))
                Button(onClick = onRunSetup) { Text(stringResource(R.string.settings_setup_button)) }
            }
        }
        AppLanguageCard()
        accountContent()
        syncContent()
        Text(stringResource(R.string.settings_languages_title), style = MaterialTheme.typography.titleMedium)
        val onlyOneEnabled = settings.enabledLanguages.size <= 1
        settings.languages.forEachIndexed { i, l ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = l.enabled,
                            enabled = !(l.enabled && onlyOneEnabled),
                            onCheckedChange = { on -> scope.launch { repository.setLanguageEnabled(l.languageCode, on) } },
                        )
                        Text(languageName(l.languageCode), Modifier.weight(1f).padding(start = 12.dp))
                        TextButton(enabled = i > 0, onClick = { scope.launch { repository.moveLanguage(l.languageCode, i - 1) } }) { Text(stringResource(R.string.onb_lang_up)) }
                        TextButton(enabled = i < settings.languages.lastIndex, onClick = { scope.launch { repository.moveLanguage(l.languageCode, i + 1) } }) { Text(stringResource(R.string.onb_lang_down)) }
                    }
                    val modes = LanguageRules.allowedModes(l.languageCode)
                    if (modes.size > 1) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            modes.forEach { m ->
                                FilterChip(
                                    selected = l.inputMode == m,
                                    onClick = { scope.launch { repository.updateLanguage(l.languageCode) { it.copy(inputMode = m) } } },
                                    label = { Text(modeLabel(m)) },
                                )
                            }
                        }
                    }
                    if (l.inputMode == InputMode.PHONETIC_LATIN_TO_NATIVE) {
                        Text(stringResource(R.string.settings_translit_profile), style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.settings_show_candidates), Modifier.weight(1f))
                            Switch(
                                checked = l.showTransliterationCandidates,
                                onCheckedChange = { on -> scope.launch { repository.updateLanguage(l.languageCode) { it.copy(showTransliterationCandidates = on) } } },
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        Text(stringResource(R.string.settings_numerals_title), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumeralStyle.entries.forEach { n ->
                FilterChip(
                    selected = settings.numeralStyle == n,
                    onClick = { scope.launch { repository.update { it.copy(numeralStyle = n) } } },
                    label = { Text(stringResource(if (n == NumeralStyle.ETHIOPIC) R.string.settings_numerals_ethiopic else R.string.settings_numerals_western)) },
                )
            }
        }
        Text(stringResource(R.string.settings_theme_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.settings_theme_current, ThemeCatalog.find(settings.theme)?.let { ThemeNames.name(LocalContext.current, it) } ?: stringResource(R.string.themes_system)))
        Button(onClick = onOpenThemes) { Text(stringResource(R.string.settings_theme_button)) }
        // ADR 0031: the e-mail writer, and the default "translate into" language of the keyboard's direction control.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.settings_email_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_email_body))
                Button(onClick = onOpenEmail) { Text(stringResource(R.string.settings_email_button)) }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.settings_translate_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_translate_body))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.aiPreferredTarget == null,
                        onClick = { scope.launch { repository.update { it.copy(aiPreferredTarget = null) } } },
                        label = { Text(stringResource(R.string.settings_translate_auto)) },
                    )
                    LanguageRules.KNOWN.forEach { code ->
                        FilterChip(
                            selected = settings.aiPreferredTarget == code,
                            onClick = { scope.launch { repository.update { it.copy(aiPreferredTarget = code) } } },
                            label = { Text(languageName(code)) },
                        )
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.settings_wordart_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_wordart_body))
                Button(onClick = onOpenWordArt) { Text(stringResource(R.string.settings_wordart_button)) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_haptics), Modifier.weight(1f))
            Switch(checked = settings.hapticsEnabled, onCheckedChange = { on -> scope.launch { repository.update { it.copy(hapticsEnabled = on) } } })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_key_preview), Modifier.weight(1f))
            Switch(checked = settings.keyPreviewEnabled, onCheckedChange = { on -> scope.launch { repository.update { it.copy(keyPreviewEnabled = on) } } })
        }
        // ADR 0029: the microphone in the keyboard bar. The privacy dashboard explains where the audio goes.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_voice_typing))
                Text(stringResource(R.string.settings_voice_typing_body), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = settings.voiceTypingEnabled, onCheckedChange = { on -> scope.launch { repository.update { it.copy(voiceTypingEnabled = on) } } })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_pack_mobile_data), Modifier.weight(1f))
            Switch(
                checked = settings.packUpdatesOnMobileData,
                onCheckedChange = { on -> scope.launch { repository.update { it.copy(packUpdatesOnMobileData = on) } } },
            )
        }
        Text(stringResource(R.string.settings_key_height, settings.keyHeightPercent), style = MaterialTheme.typography.titleMedium)
        Slider(
            value = settings.keyHeightPercent.toFloat(),
            onValueChange = { v -> scope.launch { repository.update { it.copy(keyHeightPercent = v.toInt()) } } },
            valueRange = KeyboardSettings.KEY_HEIGHT_MIN.toFloat()..KeyboardSettings.KEY_HEIGHT_MAX.toFloat(),
        )
        // A privacy decision is not a keyboard preference: "Reset to defaults" never turns crash reports back on.
        Button(onClick = { scope.launch { repository.update { KeyboardSettings.DEFAULT.copy(crashReports = it.crashReports) } } }) { Text(stringResource(R.string.settings_reset)) }
        HorizontalDivider()
        dictionaryContent()
    }
}
