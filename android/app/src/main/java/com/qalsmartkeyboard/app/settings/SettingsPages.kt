package com.qalsmartkeyboard.app.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import kotlin.math.roundToInt

/** Applies a change to the stored settings (the host launches `SettingsRepository.update`). */
typealias SettingsUpdate = ((KeyboardSettings) -> KeyboardSettings) -> Unit

/** "Preferences": key feedback, key height and numerals. */
@Composable
fun PreferencesPage(settings: KeyboardSettings, onBack: () -> Unit, onUpdate: SettingsUpdate, modifier: Modifier = Modifier) {
    SettingsScaffold(stringResource(R.string.settings_preferences), onBack, modifier) {
        SettingsHeader(stringResource(R.string.settings_prefs_keys))
        SettingsSwitchRow(stringResource(R.string.settings_haptics), settings.hapticsEnabled, { on -> onUpdate { it.copy(hapticsEnabled = on) } })
        SettingsSwitchRow(stringResource(R.string.settings_key_preview), settings.keyPreviewEnabled, { on -> onUpdate { it.copy(keyPreviewEnabled = on) } })
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
        SettingsHeader(stringResource(R.string.settings_numerals_title))
        Column(Modifier.selectableGroup()) {
            NumeralStyle.entries.forEach { n ->
                val ethiopic = n == NumeralStyle.ETHIOPIC
                SettingsRadioRow(
                    title = stringResource(if (ethiopic) R.string.settings_numerals_ethiopic else R.string.settings_numerals_western),
                    selected = settings.numeralStyle == n,
                    onSelect = { onUpdate { it.copy(numeralStyle = n) } },
                    summary = stringResource(if (ethiopic) R.string.settings_numerals_ethiopic_sample else R.string.settings_numerals_western_sample),
                )
            }
        }
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

/** "Translate into" (ADR 0031): the keyboard's default translation destination; every language of `LanguageRules.KNOWN`. */
@Composable
fun TranslationPage(settings: KeyboardSettings, onBack: () -> Unit, onUpdate: SettingsUpdate, modifier: Modifier = Modifier) {
    SettingsScaffold(stringResource(R.string.settings_translate_title), onBack, modifier) {
        SettingsNote(stringResource(R.string.settings_translate_body))
        Column(Modifier.selectableGroup()) {
            SettingsRadioRow(
                stringResource(R.string.settings_translate_auto),
                selected = settings.aiPreferredTarget == null,
                onSelect = { onUpdate { it.copy(aiPreferredTarget = null) } },
                summary = stringResource(R.string.settings_translate_auto_hint),
            )
            LanguageRules.KNOWN.forEach { code ->
                val native = nativeLanguageName(code)
                val uiName = languageName(code)
                SettingsRadioRow(
                    native,
                    selected = settings.aiPreferredTarget == code,
                    onSelect = { onUpdate { it.copy(aiPreferredTarget = code) } },
                    summary = uiName.takeIf { !it.equals(native, ignoreCase = true) },
                )
            }
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
