package com.qalsmartkeyboard.app.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qalsmartkeyboard.app.R
import com.qalsmartkeyboard.app.languageName
import com.qalsmartkeyboard.data.KeyboardSettings
import com.qalsmartkeyboard.data.LanguageRules
import com.qalsmartkeyboard.data.NumeralStyle
import com.qalsmartkeyboard.ime.ai.TranslationCatalog
import com.qalsmartkeyboard.ime.ai.TranslationLanguageSearch
import kotlin.math.roundToInt

/** Applies a change to the stored settings (the host launches `SettingsRepository.update`). */
typealias SettingsUpdate = ((KeyboardSettings) -> KeyboardSettings) -> Unit

/** "Preferences": key feedback, key height, clipboard history, text correction (ADR 0039) and numerals. */
@Composable
fun PreferencesPage(settings: KeyboardSettings, onBack: () -> Unit, onUpdate: SettingsUpdate, modifier: Modifier = Modifier) {
    SettingsScaffold(stringResource(R.string.settings_preferences), onBack, modifier) {
        SettingsHeader(stringResource(R.string.settings_prefs_keys))
        SettingsSwitchRow(stringResource(R.string.settings_haptics), settings.hapticsEnabled, { on -> onUpdate { it.copy(hapticsEnabled = on) } })
        SettingsSwitchRow(stringResource(R.string.settings_key_preview), settings.keyPreviewEnabled, { on -> onUpdate { it.copy(keyPreviewEnabled = on) } })
        // ADR 0043: Gboard's "Number row".
        SettingsSwitchRow(
            stringResource(R.string.settings_number_row), settings.numberRow, { on -> onUpdate { it.copy(numberRow = on) } },
            summary = stringResource(R.string.settings_number_row_summary),
        )
        // The slider moves freely and is saved when let go, so a drag is one write (and one sync), not dozens.
        var height by remember(settings.keyHeightPercent) { mutableFloatStateOf(settings.keyHeightPercent.toFloat()) }
        val heightLabel = stringResource(R.string.settings_key_height, height.roundToInt())
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            Text(heightLabel, style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = height,
                onValueChange = { height = it },
                onValueChangeFinished = { val v = height.roundToInt(); onUpdate { it.copy(keyHeightPercent = v) } },
                valueRange = KeyboardSettings.KEY_HEIGHT_MIN.toFloat()..KeyboardSettings.KEY_HEIGHT_MAX.toFloat(),
                modifier = Modifier.semantics { contentDescription = heightLabel },
            )
        }
        // ADR 0035: the keyboard's clipboard panel keeps recent copies in memory only.
        SettingsSwitchRow(
            stringResource(R.string.settings_clipboard_history), settings.clipboardHistoryEnabled,
            { on -> onUpdate { it.copy(clipboardHistoryEnabled = on) } },
            summary = stringResource(R.string.settings_clipboard_history_summary),
        )
        // ADR 0039: prediction and learning switches (per-language "Show suggestions" stay on the language pages).
        SettingsHeader(stringResource(R.string.settings_prefs_text))
        SettingsSwitchRow(
            stringResource(R.string.settings_next_word), settings.nextWordPredictions,
            { on -> onUpdate { it.copy(nextWordPredictions = on) } },
            summary = stringResource(R.string.settings_next_word_summary),
        )
        SettingsSwitchRow(
            stringResource(R.string.settings_autocorrect_en), settings.autocorrectEnglish,
            { on -> onUpdate { it.copy(autocorrectEnglish = on) } },
            summary = stringResource(R.string.settings_autocorrect_summary),
        )
        SettingsSwitchRow(
            stringResource(R.string.settings_autocorrect_om), settings.autocorrectOromo,
            { on -> onUpdate { it.copy(autocorrectOromo = on) } },
        )
        SettingsSwitchRow(
            stringResource(R.string.settings_learn_words), settings.learnWords,
            { on -> onUpdate { it.copy(learnWords = on) } },
            summary = stringResource(R.string.settings_learn_words_summary),
        )
        SettingsHeader(stringResource(R.string.settings_numerals_title))
        Column(Modifier.selectableGroup()) {
            NumeralStyle.entries.forEach { n ->
                val ethiopic = n == NumeralStyle.ETHIOPIC
                SettingsRadioRow(
                    title = stringResource(if (ethiopic) R.string.settings_numerals_ethiopic else R.string.settings_numerals_western),
                    // ADR 0043 / 0044: the style in force (Western first until one is picked); picking one records the choice.
                    selected = settings.effectiveNumeralStyle == n,
                    onSelect = { onUpdate { it.copy(numeralStyle = n, numeralStyleChosen = true) } },
                    summary = stringResource(if (ethiopic) R.string.settings_numerals_ethiopic_sample else R.string.settings_numerals_western_sample),
                )
            }
        }
        SettingsNote(stringResource(R.string.settings_numerals_note))
    }
}

/** "Voice typing" (ADR 0029): the microphone in the keyboard bar, and where the audio goes. */
@Composable
fun VoiceTypingPage(settings: KeyboardSettings, onBack: () -> Unit, onUpdate: SettingsUpdate, modifier: Modifier = Modifier) {
    SettingsScaffold(stringResource(R.string.settings_voice_typing), onBack, modifier) {
        SettingsSwitchRow(stringResource(R.string.settings_voice_typing_switch), settings.voiceTypingEnabled, { on -> onUpdate { it.copy(voiceTypingEnabled = on) } })
        SettingsNote(stringResource(R.string.settings_voice_typing_body))
    }
}

/**
 * "Translate into" (ADR 0031): the keyboard's default translation destination. ADR 0040: every destination of the
 * translation catalogue ([catalog]: the server's list as cached on the device, else the five keyboard languages), the
 * chosen one and the keyboard languages first, then by name, with a search over the UI-language name, the English name,
 * the autonym and the code.
 */
@Composable
fun TranslationPage(
    settings: KeyboardSettings,
    onBack: () -> Unit,
    onUpdate: SettingsUpdate,
    modifier: Modifier = Modifier,
    catalog: TranslationCatalog = TranslationCatalog.BUNDLED,
) {
    var query by rememberSaveable { mutableStateOf("") }
    SettingsScaffold(stringResource(R.string.settings_translate_title), onBack, modifier) {
        SettingsNote(stringResource(R.string.settings_translate_body))
        if (!catalog.fromServer) SettingsNote(stringResource(R.string.settings_translate_offline_note))
        val searchLabel = stringResource(R.string.settings_translate_search)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(40) },
            singleLine = true,
            label = { Text(searchLabel) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        val chosen = settings.aiPreferredTarget
        val languages = TranslationLanguageSearch.order(catalog.destinations, listOfNotNull(chosen))
        val names = languages.associate { it.code to languageName(it.code) }
        val shown = languages.filter { TranslationLanguageSearch.matches(query, it, names.getValue(it.code)) }
        Column(Modifier.selectableGroup()) {
            if (query.isBlank()) {
                SettingsRadioRow(
                    stringResource(R.string.settings_translate_auto),
                    selected = chosen == null,
                    onSelect = { onUpdate { it.copy(aiPreferredTarget = null) } },
                    summary = stringResource(R.string.settings_translate_auto_hint),
                )
            }
            shown.forEach { language ->
                val code = language.code
                val native = if (code in LanguageRules.KNOWN) nativeLanguageName(code) else language.nativeName
                val uiName = names.getValue(code)
                SettingsRadioRow(
                    native,
                    selected = chosen == code,
                    onSelect = { onUpdate { it.copy(aiPreferredTarget = code) } },
                    summary = uiName.takeIf { !it.equals(native, ignoreCase = true) },
                )
            }
            if (shown.isEmpty()) SettingsNote(stringResource(R.string.settings_translate_none_found))
        }
    }
}

/** The automatic or chosen destination, for the home row's summary. */
@Composable
internal fun translationSummary(settings: KeyboardSettings): String =
    settings.aiPreferredTarget?.let { languageName(it) } ?: stringResource(R.string.settings_translate_auto)

/** "Dictionary": dictionary sync (A2.4), then the personal dictionary (A1.8). Both are slots the host supplies. */
@Composable
fun DictionaryPage(
    onBack: () -> Unit,
    syncContent: @Composable () -> Unit,
    dictionaryContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(stringResource(R.string.settings_dictionary), onBack, modifier) {
        syncContent()
        SettingsHeader(stringResource(R.string.pd_title))
        dictionaryContent()
    }
}

/** "Account": the account section (sign in, sign out), owned by the host that owns the sign-in state. */
@Composable
fun AccountPage(onBack: () -> Unit, content: @Composable () -> Unit, modifier: Modifier = Modifier) {
    SettingsScaffold(stringResource(R.string.acct_title), onBack, modifier) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

/** "Advanced": language-pack updates on mobile data, and "Reset to defaults" (after a confirmation). */
@Composable
fun AdvancedPage(settings: KeyboardSettings, onBack: () -> Unit, onUpdate: SettingsUpdate, modifier: Modifier = Modifier) {
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    SettingsScaffold(stringResource(R.string.settings_advanced), onBack, modifier) {
        SettingsSwitchRow(
            stringResource(R.string.settings_pack_mobile_data),
            settings.packUpdatesOnMobileData,
            { on -> onUpdate { it.copy(packUpdatesOnMobileData = on) } },
        )
        SettingsRow(stringResource(R.string.settings_reset), onClick = { confirmReset = true })
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.settings_reset_title)) },
            text = { Text(stringResource(R.string.settings_reset_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    // A privacy decision is not a keyboard preference: "Reset to defaults" never turns crash reports back on.
                    onUpdate { KeyboardSettings.DEFAULT.copy(crashReports = it.crashReports) }
                }) { Text(stringResource(R.string.settings_reset_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.pd_cancel)) } },
        )
    }
}

/** "About": the app and core versions (the core version proves the native binding, A1.1) and the privacy notice. */
@Composable
fun AboutPage(appVersion: String, coreVersion: String?, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    val privacyUrl = stringResource(R.string.privacy_notice_url)
    SettingsScaffold(stringResource(R.string.settings_about), onBack, modifier) {
        SettingsValueRow(stringResource(R.string.settings_about_version), appVersion)
        if (coreVersion != null) SettingsValueRow(stringResource(R.string.settings_about_core), coreVersion)
        SettingsRow(stringResource(R.string.settings_about_privacy_notice), onClick = { uri.openUri(privacyUrl) })
    }
}
