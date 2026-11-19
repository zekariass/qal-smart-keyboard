package com.qalsmartkeyboard.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qalsmartkeyboard.app.settings.SettingsNote
import com.qalsmartkeyboard.app.settings.SettingsRow
import com.qalsmartkeyboard.ime.userdict.UserDictionary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PAGE = 50

/**
 * "Personal dictionary" (A1.8): one row per language with its word count, which opens the words (each with a delete
 * button), and "Clear all". Reads and edits the same process-wide [UserDictionary] as the keyboard, so a deletion is
 * also applied to a running keyboard. Words are shown on screen only, never logged.
 */
@Composable
fun PersonalDictionarySection(modifier: Modifier = Modifier) {
    val dictionary = UserDictionary.get(LocalContext.current)
    var words by remember { mutableStateOf<Map<String, List<String>>?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        words = UserDictionary.LANGUAGES.associateWith { dictionary.words(it) }
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { dictionary.load() }
        refresh()
    }

    Column(modifier) {
        SettingsNote(stringResource(R.string.pd_body))
        val all = words
        if (all == null) {
            SettingsNote(stringResource(R.string.pd_loading))
        } else {
            UserDictionary.LANGUAGES.forEach { lang ->
                LanguageWords(lang, all[lang].orEmpty(), onDelete = { w ->
                    dictionary.forget(lang, w)
                    refresh()
                })
            }
            if (all.values.any { it.isNotEmpty() }) {
                SettingsRow(stringResource(R.string.pd_clear_all), onClick = { confirmClear = true }, titleColor = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.pd_clear_title)) },
            text = { Text(stringResource(R.string.pd_clear_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        dictionary.clearAll()
                        refresh()
                    }
                }) { Text(stringResource(R.string.pd_clear_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.pd_cancel)) } },
        )
    }
}

@Composable
private fun LanguageWords(language: String, words: List<String>, onDelete: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(PAGE) }
    if (words.isEmpty()) {
        ListItem(
            headlineContent = { Text(languageName(language)) },
            supportingContent = { Text(stringResource(R.string.pd_empty)) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        )
        return
    }
    SettingsRow(
        title = languageName(language),
        summary = pluralStringResource(R.plurals.pd_word_count, words.size, words.size),
        onClick = { expanded = !expanded },
        trailing = {
            Icon(
                painterResource(if (expanded) R.drawable.ic_settings_collapse else R.drawable.ic_settings_expand),
                contentDescription = stringResource(if (expanded) R.string.pd_hide else R.string.pd_show),
            )
        },
    )
    if (expanded) {
        words.take(shown).forEach { w ->
            ListItem(
                headlineContent = { Text(w) },
                trailingContent = {
                    IconButton(onClick = { onDelete(w) }) {
                        Icon(painterResource(R.drawable.ic_settings_close), contentDescription = stringResource(R.string.pd_delete_description, w))
                    }
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        if (words.size > shown) TextButton(onClick = { shown += PAGE }, modifier = Modifier.padding(start = 16.dp)) { Text(stringResource(R.string.pd_show_more)) }
    }
}
