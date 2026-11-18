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
import kotlinx.coroutines.launch

/**
 * Settings card for personal-dictionary sync (A2.4). The switch records the `DICTIONARY_SYNC`
 * consent (`PUT /me/consents/DICTIONARY_SYNC`, surface `settings`) and only then does the
 * dictionary sync run. Registered accounts only: guests can record consents but the server refuses
 * their dictionaries, so the switch is not offered to them. Nothing here shows or logs a word.
 */
@Composable
fun DictionarySyncSection(status: AccountStatus, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val engine = remember { SyncGraph.get(context).engine }
    var enabled by remember { mutableStateOf(engine.consent.enabled) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val registered = status == AccountStatus.Registered

    // A new device whose account already granted it: adopt the server's decision.
    LaunchedEffect(registered) {
        if (registered) {
            try {
                enabled = engine.consent.refresh()
            } catch (_: ApiError) {
                enabled = engine.consent.enabled
            }
        }
    }

    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.sync_dict_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.sync_dict_body), style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.sync_dict_switch), Modifier.weight(1f))
                Switch(
                    checked = enabled && registered,
                    enabled = registered && !busy,
                    onCheckedChange = { on ->
                        busy = true
                        failed = false
                        scope.launch {
                            try {
                                engine.consent.set(on)
                                enabled = on
                                if (on) SyncScheduler.requestSoon(context, delaySeconds = 0)
                            } catch (_: ApiError) {
                                failed = true
                            } finally {
                                busy = false
                            }
                        }
                    },
                )
            }
            if (!registered) Text(stringResource(R.string.sync_dict_needs_account), style = MaterialTheme.typography.bodySmall)
            if (failed) Text(stringResource(R.string.sync_dict_error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
