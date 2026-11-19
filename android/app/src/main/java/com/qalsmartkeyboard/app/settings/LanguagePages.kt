package com.qalsmartkeyboard.app.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.qalsmartkeyboard.app.R
import com.qalsmartkeyboard.app.languageName
import com.qalsmartkeyboard.data.InputMode
import com.qalsmartkeyboard.data.KeyboardSettings
import com.qalsmartkeyboard.data.LanguageRules
import com.qalsmartkeyboard.data.TypingLanguageSetting
import com.qalsmartkeyboard.ime.AppLanguage
import java.util.Locale

/*
 * The language pages (ADR 0036). Every list comes from the settings (`KeyboardSettings.languages`, which always holds
 * every language of `LanguageRules.KNOWN`), never from a hard-coded list, so a language added to `KNOWN` shows up here
 * without a change. Each language is named in itself (አማርኛ, ትግርኛ, Afaan Oromoo) with its name in the UI language
 * underneath, so Amharic and Tigrinya are never told apart by a translated name alone.
 */

/**
 * A typing language's name in itself: the app's own self-names (AppLanguage), else the platform's name of the
 * language in that language (e.g. العربية), else the code.
 */
fun nativeLanguageName(code: String): String {
    val self = AppLanguage.selfName(code)
    if (self != code) return self
    val locale = Locale.forLanguageTag(code)
    val name = locale.getDisplayLanguage(locale)
    return if (name.isBlank() || name.equals(code, ignoreCase = true)) code else name.replaceFirstChar { it.titlecase(locale) }
}

/** The supporting line of a language: its UI-language name when that differs from the self-name, plus [extra]. */
private fun languageLine(native: String, uiName: String, extra: String? = null): String? =
    listOfNotNull(uiName.takeIf { !it.equals(native, ignoreCase = true) }, extra).joinToString(" · ").ifEmpty { null }

/** Whether [query] matches the language (self-name, UI name or code; case-insensitive). */
internal fun languageMatches(query: String, code: String, native: String, uiName: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return listOf(code, native, uiName).any { it.contains(q, ignoreCase = true) }
}

@Composable
internal fun modeLabel(m: InputMode): String = stringResource(
    when (m) {
        InputMode.DIRECT_SCRIPT_LAYOUT -> R.string.onb_mode_fidel
        InputMode.PHONETIC_LATIN_TO_NATIVE -> R.string.settings_mode_phonetic
        InputMode.LATIN_PASSTHROUGH -> R.string.settings_mode_latin
    },
)

@Composable
private fun modeHint(m: InputMode): String = stringResource(
    when (m) {
        InputMode.DIRECT_SCRIPT_LAYOUT -> R.string.onb_mode_fidel_hint
        InputMode.PHONETIC_LATIN_TO_NATIVE -> R.string.onb_mode_phonetic_hint
        InputMode.LATIN_PASSTHROUGH -> R.string.settings_mode_latin_hint
    },
)

/**
 * "Languages": the enabled languages in switching order (the globe key's order), each with Move up / Move down and
 * opening its own page, then "Add or remove languages".
 */
@Composable
fun LanguagesPage(
    settings: KeyboardSettings,
    onBack: () -> Unit,
    onOpenLanguage: (String) -> Unit,
    onMove: (code: String, toIndex: Int) -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(stringResource(R.string.settings_languages_title), onBack, modifier) {
        SettingsNote(stringResource(R.string.settings_languages_order))
        val enabled = settings.languages.filter { it.enabled }
        enabled.forEachIndexed { pos, l ->
            val code = l.languageCode
            val native = nativeLanguageName(code)
            val uiName = languageName(code)
            // Moves are between enabled languages; a disabled one keeps its place in the stored list.
            val upTo = if (pos > 0) settings.languages.indexOfFirst { it.languageCode == enabled[pos - 1].languageCode } else -1
            val downTo = if (pos < enabled.lastIndex) settings.languages.indexOfFirst { it.languageCode == enabled[pos + 1].languageCode } else -1
            ListItem(
                headlineContent = { Text(native) },
                supportingContent = languageLine(native, uiName, modeLabel(l.inputMode))?.let { { Text(it) } },
                trailingContent = {
                    Row {
                        IconButton(onClick = { onMove(code, upTo) }, enabled = upTo >= 0) {
                            Icon(painterResource(R.drawable.ic_settings_arrow_up), stringResource(R.string.onb_lang_up_description, uiName))
                        }
                        IconButton(onClick = { onMove(code, downTo) }, enabled = downTo >= 0) {
                            Icon(painterResource(R.drawable.ic_settings_arrow_down), stringResource(R.string.onb_lang_down_description, uiName))
                        }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.clickable(role = Role.Button) { onOpenLanguage(code) },
            )
        }
        SettingsRow(stringResource(R.string.settings_languages_manage), onManage, icon = R.drawable.ic_settings_add)
    }
}

/**
 * "All languages": every known language with a checkbox, and a search field. The last enabled language cannot be
 * turned off (the keyboard needs one).
 */
@Composable
fun AllLanguagesPage(
    settings: KeyboardSettings,
    onBack: () -> Unit,
    onEnable: (code: String, enabled: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    SettingsScaffold(stringResource(R.string.settings_languages_all), onBack, modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.settings_languages_search)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_settings_search), contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(painterResource(R.drawable.ic_settings_close), stringResource(R.string.settings_languages_search_clear))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val onlyOne = settings.enabledLanguages.size <= 1
        // The default order of LanguageRules.KNOWN reads better here than the user's switching order.
        val all = LanguageRules.KNOWN.mapNotNull(settings::language) + settings.languages.filter { !LanguageRules.isKnown(it.languageCode) }
        val shown = all.map { l -> Triple(l, nativeLanguageName(l.languageCode), languageName(l.languageCode)) }
            .filter { (l, native, uiName) -> languageMatches(query, l.languageCode, native, uiName) }
        Column {
            shown.forEach { (l, native, uiName) ->
                val locked = l.enabled && onlyOne
                SettingsCheckboxRow(
                    title = native,
                    checked = l.enabled,
                    onCheckedChange = { on -> onEnable(l.languageCode, on) },
                    summary = languageLine(native, uiName, if (locked) stringResource(R.string.settings_languages_last) else null),
                    enabled = !locked,
                )
            }
        }
        if (shown.isEmpty()) SettingsNote(stringResource(R.string.settings_languages_no_match))
    }
}

/** One language: its layout (radio rows with a one-line description) and, for phonetic typing, the suggestion strip. */
@Composable
fun LanguagePage(
    language: TypingLanguageSetting,
    onBack: () -> Unit,
    onMode: (InputMode) -> Unit,
    onShowSuggestions: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val code = language.languageCode
    SettingsScaffold(nativeLanguageName(code), onBack, modifier) {
        val native = nativeLanguageName(code)
        languageLine(native, languageName(code))?.let { SettingsNote(it) }
        val modes = LanguageRules.allowedModes(code)
        if (modes.isNotEmpty()) {
            SettingsHeader(stringResource(R.string.settings_layout_title))
            Column(Modifier.selectableGroup()) {
                modes.forEach { m ->
                    SettingsRadioRow(modeLabel(m), selected = language.inputMode == m, onSelect = { onMode(m) }, summary = modeHint(m))
                }
            }
        }
        if (language.inputMode == InputMode.PHONETIC_LATIN_TO_NATIVE) {
            SettingsHeader(stringResource(R.string.settings_phonetic_title))
            SettingsSwitchRow(stringResource(R.string.settings_show_candidates), language.showTransliterationCandidates, onShowSuggestions)
            SettingsNote(stringResource(R.string.settings_translit_profile))
        }
    }
}
