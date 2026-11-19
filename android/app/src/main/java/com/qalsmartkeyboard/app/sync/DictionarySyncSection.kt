package com.qalsmartkeyboard.app.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qalsmartkeyboard.api.ApiError
import com.qalsmartkeyboard.app.R
import com.qalsmartkeyboard.app.auth.AccountStatus
import com.qalsmartkeyboard.app.settings.SettingsNote
import com.qalsmartkeyboard.app.settings.SettingsSwitchRow
import com.qalsmartkeyboard.sync.DictionaryConsent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The dictionary-sync switch (A2.4). The switch records the `DICTIONARY_SYNC` consent (`PUT /me/consents/DICTIONARY_SYNC`,
 * surface `settings`) and only then does the dictionary sync run. Registered accounts only: guests can record consents
 * but the server refuses their dictionaries, so the switch is not offered to them. Nothing here shows or logs a word.
 */
private class DictionarySync(private val consent: DictionaryConsent, val registered: Boolean) {
    var enabled by mutableStateOf(consent.enabled)
    var busy by mutableStateOf(false)
    var failed by mutableStateOf(false)
    val checked: Boolean get() = enabled && registered
    val usable: Boolean get() = registered && !busy

    fun set(on: Boolean, scope: CoroutineScope, onEnabled: () -> Unit) {
        busy = true
        failed = false
        scope.launch {
            try {
                consent.set(on)
                enabled = on
                if (on) onEnabled()
            } catch (_: ApiError) {
                failed = true
            } finally {
                busy = false
            }
        }
    }

    suspend fun refresh() {
        if (!registered) return
        enabled = try {
            consent.refresh()
        } catch (_: ApiError) {
            consent.enabled
        }
    }
}

@Composable
private fun rememberDictionarySync(status: AccountStatus): Pair<DictionarySync, () -> Unit> {
    val context = LocalContext.current
    val engine = remember { SyncGraph.get(context).engine }
    val registered = status == AccountStatus.Registered
    val sync = remember(registered) { DictionarySync(engine.consent, registered) }
    // A new device whose account already granted it: adopt the server's decision.
    LaunchedEffect(sync) { sync.refresh() }
    return sync to { SyncScheduler.requestSoon(context, delaySeconds = 0) }
}

/** The privacy area's card (consents screen): title, what sync does, the switch. */
@Composable
fun DictionarySyncSection(status: AccountStatus, modifier: Modifier = Modifier) {
    val (sync, requestNow) = rememberDictionarySync(status)
    val scope = rememberCoroutineScope()
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.sync_dict_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.sync_dict_body), style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.sync_dict_switch), Modifier.weight(1f))
                Switch(checked = sync.checked, enabled = sync.usable, onCheckedChange = { on -> sync.set(on, scope, requestNow) })
            }
            if (!sync.registered) Text(stringResource(R.string.sync_dict_needs_account), style = MaterialTheme.typography.bodySmall)
            if (sync.failed) Text(stringResource(R.string.sync_dict_error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The settings Dictionary page's row (ADR 0036): the switch as a list row, with a note only when it cannot be used. */
@Composable
fun DictionarySyncRow(status: AccountStatus, modifier: Modifier = Modifier) {
    val (sync, requestNow) = rememberDictionarySync(status)
    val scope = rememberCoroutineScope()
    Column(modifier) {
        SettingsSwitchRow(
            stringResource(R.string.sync_dict_switch),
            checked = sync.checked,
            enabled = sync.usable,
            onCheckedChange = { on -> sync.set(on, scope, requestNow) },
        )
        if (!sync.registered) SettingsNote(stringResource(R.string.sync_dict_needs_account))
        if (sync.failed) SettingsNote(stringResource(R.string.sync_dict_error))
    }
}
