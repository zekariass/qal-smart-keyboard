package com.qalsmartkeyboard.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qalsmartkeyboard.ime.userdict.UserDictionary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PAGE = 50

/**
 * "Personal dictionary" (A1.8): the words added or learned per language, each with a delete
 * button, and "Clear all". Reads and edits the same process-wide [UserDictionary] as the keyboard,
 * so a deletion is also applied to a running keyboard. Words are shown on screen only, never logged.
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

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.pd_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.pd_body), style = MaterialTheme.typography.bodySmall)
        val all = words
        if (all == null) {
            Text(stringResource(R.string.pd_loading))
        } else {
            UserDictionary.LANGUAGES.forEach { lang ->
                LanguageWords(lang, all[lang].orEmpty(), onDelete = { w ->
                    dictionary.forget(lang, w)
                    refresh()
                })
            }
            Button(onClick = { confirmClear = true }, enabled = all.values.any { it.isNotEmpty() }) {
                Text(stringResource(R.string.pd_clear_all))
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
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.pd_language_count, languageName(language), words.size),
                    Modifier.weight(1f),
                )
                if (words.isNotEmpty()) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(stringResource(if (expanded) R.string.pd_hide else R.string.pd_show))
                    }
                }
            }
            if (words.isEmpty()) Text(stringResource(R.string.pd_empty), style = MaterialTheme.typography.bodySmall)
            if (expanded) {
                words.take(shown).forEach { w ->
                    val description = stringResource(R.string.pd_delete_description, w)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(w, Modifier.weight(1f))
                        TextButton(onClick = { onDelete(w) }, modifier = Modifier.semantics { contentDescription = description }) {
                            Text(stringResource(R.string.pd_delete))
                        }
                    }
                }
                if (words.size > shown) TextButton(onClick = { shown += PAGE }) { Text(stringResource(R.string.pd_show_more)) }
            }
        }
    }
}
