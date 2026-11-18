package com.qalsmartkeyboard.app.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.qalsmartkeyboard.app.R
import com.qalsmartkeyboard.app.auth.AccountStatus
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** The screens of the privacy area (A2.6). */
enum class PrivacyRoute { DASHBOARD, CONSENTS, CONSENT_HISTORY, HISTORY, EXPORT, DELETE }

private val spacing = Arrangement.spacedBy(12.dp)

private fun formatTime(ms: Long?): String =
    if (ms == null) "" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

private fun formatDay(ms: Long?): String = if (ms == null) "" else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })

@Composable
private fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
private fun Small(text: String) = Text(text, style = MaterialTheme.typography.bodySmall)

@Composable
private fun ErrorText(text: String) = Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)

@Composable
private fun messageText(m: PrivacyMessage): String = when (m) {
    PrivacyMessage.Offline -> stringResource(R.string.pv_err_offline)
    PrivacyMessage.Timeout -> stringResource(R.string.pv_err_timeout)
    PrivacyMessage.SignedOut -> stringResource(R.string.pv_err_signed_out)
    PrivacyMessage.UpdateRequired -> stringResource(R.string.pv_err_update)
    is PrivacyMessage.RateLimited -> m.retryAfterSeconds?.let { stringResource(R.string.pv_err_rate_limited_after, formatRetry(it)) }
        ?: stringResource(R.string.pv_err_rate_limited)
    PrivacyMessage.NotRevocable -> stringResource(R.string.pv_err_not_revocable)
    PrivacyMessage.ConsentRequired -> stringResource(R.string.pv_err_consent_required)
    PrivacyMessage.DeletionPending -> stringResource(R.string.pv_err_deletion_pending)
    PrivacyMessage.NotFound -> stringResource(R.string.pv_err_not_found)
    PrivacyMessage.Forbidden -> stringResource(R.string.pv_err_forbidden)
    PrivacyMessage.Failed -> stringResource(R.string.pv_err_failed)
    PrivacyMessage.SaveFailed -> stringResource(R.string.pv_err_save_failed)
    PrivacyMessage.WordMismatch -> stringResource(R.string.pv_err_word)
    PrivacyMessage.RetentionInvalid -> stringResource(R.string.pv_err_retention)
}

@Composable
private fun formatRetry(seconds: Long): String = when {
    seconds >= 3600 -> stringResource(R.string.pv_hours, (seconds + 3599) / 3600)
    seconds >= 60 -> stringResource(R.string.pv_minutes, (seconds + 59) / 60)
    else -> stringResource(R.string.pv_seconds, seconds)
}

@Composable
private fun BackButton(onBack: () -> Unit) = OutlinedButton(onClick = onBack) { Text(stringResource(R.string.onb_back)) }

@Composable
private fun SignInHint(account: AccountStatus, onSignIn: () -> Unit) {
    if (account is AccountStatus.SignedOut) {
        Section(stringResource(R.string.acct_title)) {
            Text(stringResource(R.string.pv_signed_out_note))
            Button(onClick = onSignIn) { Text(stringResource(R.string.acct_sign_in)) }
        }
    }
}

/** The settings screen's entry to the privacy area. */
@Composable
fun PrivacyEntryCard(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Section(stringResource(R.string.pv_entry_title), modifier) {
        Text(stringResource(R.string.pv_entry_body), style = MaterialTheme.typography.bodySmall)
        Button(onClick = onOpen) { Text(stringResource(R.string.pv_entry_button)) }
    }
}

// ------------------------------------------------------------------ dashboard

/** What stays on the device, what goes to the server and when, the current decisions, and the way to the other screens. */
@Composable
fun PrivacyDashboardScreen(
    account: AccountStatus,
    consents: ConsentsState,
    deletion: DeletionState,
    historyOn: Boolean,
    onOpen: (PrivacyRoute) -> Unit,
    onSignIn: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** ADR 0024: the local crash-report switch (on by default; works signed out too). */
    crashReportsOn: Boolean = true,
    onCrashReports: (Boolean) -> Unit = {},
) {
    val uri = LocalUriHandler.current
    val privacyUrl = stringResource(R.string.privacy_notice_url)
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = spacing) {
        Title(stringResource(R.string.pv_title))
        if (deletion.phase == DeletionPhase.PENDING || deletion.phase == DeletionPhase.CANCELLING) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.pv_deletion_banner, formatDay(deletion.info?.scheduledPurgeAtMs)), color = MaterialTheme.colorScheme.error)
                    Button(onClick = { onOpen(PrivacyRoute.DELETE) }) { Text(stringResource(R.string.pv_delete_cancel_button)) }
                }
            }
        }
        SignInHint(account, onSignIn)
        Section(stringResource(R.string.pv_device_title)) {
            Text(stringResource(R.string.pv_device_keystrokes))
            Text(stringResource(R.string.pv_device_dictionary))
            Text(stringResource(R.string.pv_device_offline))
        }
        Section(stringResource(R.string.pv_server_title)) {
            Text(stringResource(R.string.pv_server_ai))
            Text(stringResource(R.string.pv_server_account))
            Text(stringResource(R.string.pv_server_never))
        }
        Section(stringResource(R.string.pv_choices_title)) {
            for (code in listOf(ConsentCatalog.AI_PROCESSING, ConsentCatalog.SAVE_AI_HISTORY, ConsentCatalog.DICTIONARY_SYNC, ConsentCatalog.ANALYTICS)) {
                val def = ConsentCatalog.of(code) ?: continue
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(def.title), Modifier.weight(1f))
                    Text(consentStatus(consents.granted(code), account), fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.pv_history_row), Modifier.weight(1f))
                Text(stringResource(if (historyOn) R.string.pv_on else R.string.pv_off))
            }
            Button(onClick = { onOpen(PrivacyRoute.CONSENTS) }) { Text(stringResource(R.string.pv_manage_consents)) }
            OutlinedButton(onClick = { onOpen(PrivacyRoute.HISTORY) }) { Text(stringResource(R.string.pv_open_history)) }
            OutlinedButton(onClick = { onOpen(PrivacyRoute.CONSENT_HISTORY) }) { Text(stringResource(R.string.pv_open_consent_history)) }
        }
        Section(stringResource(R.string.pv_crash_title)) {
            // One toggleable row, so TalkBack reads the label with the state.
            Row(
                Modifier.fillMaxWidth().toggleable(value = crashReportsOn, role = Role.Switch, onValueChange = onCrashReports),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.pv_crash_switch), Modifier.weight(1f))
                Switch(checked = crashReportsOn, onCheckedChange = null)
            }
            Small(stringResource(R.string.pv_crash_body))
        }
        Section(stringResource(R.string.pv_data_title)) {
            Text(stringResource(R.string.pv_data_body))
            if (account == AccountStatus.Guest) Small(stringResource(R.string.pv_guest_note))
            OutlinedButton(onClick = { onOpen(PrivacyRoute.EXPORT) }) { Text(stringResource(R.string.pv_export_button)) }
            OutlinedButton(onClick = { onOpen(PrivacyRoute.DELETE) }) { Text(stringResource(R.string.pv_delete_button)) }
        }
        OutlinedButton(onClick = { uri.openUri(privacyUrl) }) { Text(stringResource(R.string.onb_privacy_link)) }
        BackButton(onBack)
    }
}

@Composable
private fun consentStatus(granted: Boolean?, account: AccountStatus): String = when {
    account is AccountStatus.SignedOut -> stringResource(R.string.pv_needs_account_short)
    granted == true -> stringResource(R.string.pv_on)
    granted == false -> stringResource(R.string.pv_off)
    else -> stringResource(R.string.pv_not_set)
}

// ------------------------------------------------------------------ consents

@Composable
fun ConsentsScreen(
    account: AccountStatus,
    state: ConsentsState,
    onSet: (code: String, granted: Boolean) -> Unit,
    onDismissWithdrawn: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenConsentHistory: () -> Unit,
    onSignIn: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** The A2.4 sync card (owns the `DICTIONARY_SYNC` switch and the local sync flag). */
    syncCard: @Composable () -> Unit = {},
) {
    val uri = LocalUriHandler.current
    val privacyUrl = stringResource(R.string.privacy_notice_url)
    val termsUrl = stringResource(R.string.terms_url)
    var confirming by rememberSaveable { mutableStateOf<String?>(null) }
    val usable = account !is AccountStatus.SignedOut
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = spacing) {
        Title(stringResource(R.string.pv_consents_title))
        Text(stringResource(R.string.pv_consents_intro))
        SignInHint(account, onSignIn)
        if (state.loading) Text(stringResource(R.string.pv_loading))
        state.message?.let { ErrorText(messageText(it)) }
        state.justWithdrawn?.let { code ->
            val def = ConsentCatalog.of(code)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.pv_withdrawn_title), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    def?.effect?.let { Text(stringResource(it)) }
                    if (code == ConsentCatalog.SAVE_AI_HISTORY) {
                        OutlinedButton(onClick = onOpenHistory) { Text(stringResource(R.string.pv_open_history)) }
                    }
                    TextButton(onClick = onDismissWithdrawn) { Text(stringResource(R.string.pv_ok)) }
                }
            }
        }
        for (def in ConsentCatalog.all) {
            when (def.kind) {
                ConsentKind.SYNC_CARD -> syncCard()
                ConsentKind.REQUIRED -> Section(stringResource(def.title)) {
                    Text(stringResource(def.body))
                    val record = state.current[def.code]
                    Text(
                        if (record?.granted == true) stringResource(R.string.pv_accepted, formatDay(record.recordedAtMs)) else stringResource(R.string.pv_required_state),
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    )
                    Small(stringResource(R.string.pv_not_revocable))
                    TextButton(onClick = { uri.openUri(if (def.code == ConsentCatalog.TERMS) termsUrl else privacyUrl) }) { Text(stringResource(R.string.pv_read_text)) }
                }
                ConsentKind.INFO -> Section(stringResource(def.title)) {
                    Text(stringResource(def.body))
                    Text(consentStatus(state.granted(def.code), account), fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                    def.effect?.let { Small(stringResource(it)) }
                }
                ConsentKind.SWITCH -> Section(stringResource(def.title)) {
                    Text(stringResource(def.body))
                    val granted = state.granted(def.code)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(consentStatus(granted, account), Modifier.weight(1f))
                        Switch(
                            checked = granted == true,
                            enabled = usable && state.busy == null && state.loaded,
                            onCheckedChange = { on -> if (on) onSet(def.code, true) else confirming = def.code },
                        )
                    }
                }
            }
        }
        OutlinedButton(onClick = onOpenConsentHistory) { Text(stringResource(R.string.pv_open_consent_history)) }
        BackButton(onBack)
    }
    confirming?.let { code ->
        val def = ConsentCatalog.of(code)
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.pv_withdraw_title, def?.title?.let { stringResource(it) }.orEmpty())) },
            text = { Text(def?.effect?.let { stringResource(it) }.orEmpty()) },
            confirmButton = { TextButton(onClick = { confirming = null; onSet(code, false) }) { Text(stringResource(R.string.pv_withdraw)) } },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.pd_cancel)) } },
        )
    }
}

@Composable
fun ConsentHistoryScreen(
    state: ConsentsState,
    onMore: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = spacing, modifier = Modifier.padding(top = 16.dp)) {
                Title(stringResource(R.string.pv_consent_history_title))
                Text(stringResource(R.string.pv_consent_history_intro))
                state.message?.let { ErrorText(messageText(it)) }
                if (state.historyLoading && state.history.isEmpty()) Text(stringResource(R.string.pv_loading))
                if (state.historyLoaded && state.history.isEmpty()) Text(stringResource(R.string.pv_consent_history_empty))
            }
        }
        items(state.history) { r ->
            val def = ConsentCatalog.of(r.code)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(def?.title?.let { stringResource(it) } ?: r.code, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(if (r.granted) R.string.pv_history_granted else R.string.pv_history_withdrawn, formatTime(r.recordedAtMs)))
                }
            }
        }
        item {
            Column(verticalArrangement = spacing, modifier = Modifier.padding(bottom = 16.dp)) {
                if (state.historyNext != null) OutlinedButton(onClick = onMore, enabled = !state.historyLoading) { Text(stringResource(R.string.pv_load_more)) }
                BackButton(onBack)
            }
        }
    }
}

// ------------------------------------------------------------------ AI history

@Composable
fun HistoryScreen(
    account: AccountStatus,
    state: HistoryState,
    onEnable: (days: Int) -> Unit,
    onDisable: () -> Unit,
    onRetention: (days: Int) -> Unit,
    onMore: () -> Unit,
    onFavorite: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDeleteAll: () -> Unit,
    onPlans: () -> Unit,
    onSignIn: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var warnEnable by rememberSaveable { mutableStateOf(false) }
    var confirmAll by rememberSaveable { mutableStateOf(false) }
    var days by rememberSaveable(state.retentionDays) { mutableStateOf(state.retentionDays.toString()) }
    val typedDays = days.toIntOrNull()
    val validDays = typedDays != null && typedDays in PrivacyApi.RETENTION_RANGE
    val usable = account !is AccountStatus.SignedOut
    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = spacing, modifier = Modifier.padding(top = 16.dp)) {
                Title(stringResource(R.string.pv_history_title))
                Text(stringResource(R.string.pv_history_intro))
                SignInHint(account, onSignIn)
                if (state.loading) Text(stringResource(R.string.pv_loading))
                state.message?.let { ErrorText(messageText(it)) }
                Section(stringResource(R.string.pv_history_setting)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(if (state.enabled) R.string.pv_history_is_on else R.string.pv_history_is_off), Modifier.weight(1f))
                        Switch(
                            checked = state.enabled,
                            enabled = usable && !state.busy && state.loaded,
                            onCheckedChange = { on ->
                                if (!on) onDisable() else if (state.planLacksHistory) warnEnable = true else onEnable(typedDays?.takeIf { validDays } ?: state.retentionDays)
                            },
                        )
                    }
                    Small(stringResource(R.string.pv_history_switch_note))
                    if (state.enabled && state.planLacksHistory) {
                        Text(stringResource(R.string.pv_history_plan_warning), color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = onPlans) { Text(stringResource(R.string.pv_see_plans)) }
                    }
                    if (state.enabled && state.consentGranted != true) Small(stringResource(R.string.pv_history_no_consent))
                }
                Section(stringResource(R.string.pv_retention_title)) {
                    Text(stringResource(R.string.pv_retention_body, state.retentionDays))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (preset in listOf(7, 30, 90, 365)) {
                            FilterChip(
                                selected = state.retentionDays == preset,
                                enabled = usable && !state.busy,
                                onClick = { days = preset.toString(); onRetention(preset) },
                                label = { Text(stringResource(R.string.pv_days, preset)) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = days,
                        onValueChange = { days = it.filter(Char::isDigit).take(3) },
                        label = { Text(stringResource(R.string.pv_retention_field)) },
                        isError = days.isNotEmpty() && !validDays,
                        supportingText = { Text(stringResource(R.string.pv_retention_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    Button(enabled = usable && validDays && typedDays != state.retentionDays && !state.busy, onClick = { onRetention(typedDays!!) }) {
                        Text(stringResource(R.string.pv_retention_save))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.pv_saved_items), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (state.items.isNotEmpty()) {
                        TextButton(enabled = !state.busy, onClick = { confirmAll = true }) { Text(stringResource(R.string.pv_delete_all)) }
                    }
                }
                if (state.itemsLoaded && state.items.isEmpty()) Text(stringResource(R.string.pv_history_empty))
            }
        }
        items(state.items, key = { it.id }) { item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Small(listOfNotNull(item.featureCode, formatTime(item.createdAtMs).ifEmpty { null }).joinToString(" · "))
                    Text(item.inputText, maxLines = 4)
                    Text(item.outputText, maxLines = 6, style = MaterialTheme.typography.bodyLarge)
                    if (item.favorite) Small(stringResource(R.string.pv_favourite_note)) else item.expiresAtMs?.let { Small(stringResource(R.string.pv_expires, formatDay(it))) }
                    Row {
                        TextButton(onClick = { onFavorite(item.id) }) {
                            Text(stringResource(if (item.favorite) R.string.pv_unfavourite else R.string.pv_favourite))
                        }
                        TextButton(onClick = { onDelete(item.id) }) { Text(stringResource(R.string.pv_delete_item)) }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = spacing, modifier = Modifier.padding(bottom = 16.dp)) {
                if (state.nextCursor != null) OutlinedButton(onClick = onMore, enabled = !state.loadingMore) { Text(stringResource(R.string.pv_load_more)) }
                BackButton(onBack)
            }
        }
    }
    if (warnEnable) {
        AlertDialog(
            onDismissRequest = { warnEnable = false },
            title = { Text(stringResource(R.string.pv_history_warn_title)) },
            text = { Text(stringResource(R.string.pv_history_plan_warning)) },
            confirmButton = {
                TextButton(onClick = { warnEnable = false; onEnable(typedDays?.takeIf { validDays } ?: state.retentionDays) }) { Text(stringResource(R.string.pv_turn_on_anyway)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { warnEnable = false; onPlans() }) { Text(stringResource(R.string.pv_see_plans)) }
                    TextButton(onClick = { warnEnable = false }) { Text(stringResource(R.string.pd_cancel)) }
                }
            },
        )
    }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text(stringResource(R.string.pv_delete_all_title)) },
            text = { Text(stringResource(R.string.pv_delete_all_body)) },
            confirmButton = { TextButton(onClick = { confirmAll = false; onDeleteAll() }) { Text(stringResource(R.string.pv_delete_all)) } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text(stringResource(R.string.pd_cancel)) } },
        )
    }
}

// ------------------------------------------------------------------ export

@Composable
fun ExportScreen(
    account: AccountStatus,
    state: ExportState,
    onRequest: () -> Unit,
    onSave: (suggestedName: String) -> Unit,
    onSignIn: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    now: () -> Long = System::currentTimeMillis,
) {
    var clock by remember { mutableLongStateOf(now()) }
    val retryAt = state.retryAtMs
    LaunchedEffect(retryAt) {
        while (retryAt != null && clock < retryAt) {
            delay(1_000)
            clock = now()
        }
    }
    val limited = retryAt != null && clock < retryAt
    val usable = account !is AccountStatus.SignedOut
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = spacing) {
        Title(stringResource(R.string.pv_export_title))
        Text(stringResource(R.string.pv_export_intro))
        Small(stringResource(R.string.pv_export_contents))
        if (account == AccountStatus.Guest) Small(stringResource(R.string.pv_guest_note))
        SignInHint(account, onSignIn)
        state.message?.let { ErrorText(messageText(it)) }
        if (limited) Text(stringResource(R.string.pv_export_limited, formatTime(retryAt)))
        when (state.phase) {
            ExportPhase.IDLE, ExportPhase.FAILED, ExportPhase.EXPIRED, ExportPhase.SAVED -> {
                if (state.phase == ExportPhase.EXPIRED) Text(stringResource(R.string.pv_export_expired))
                if (state.phase == ExportPhase.SAVED) Text(stringResource(R.string.pv_export_saved))
                Button(enabled = usable && !limited, onClick = onRequest) { Text(stringResource(R.string.pv_export_request)) }
            }
            ExportPhase.REQUESTING -> Text(stringResource(R.string.pv_export_requesting))
            ExportPhase.IN_PROGRESS -> {
                if (state.alreadyRunning) Text(stringResource(R.string.pv_export_already))
                Text(stringResource(R.string.pv_export_progress, formatTime(state.export?.requestedAtMs)))
                Small(stringResource(R.string.pv_export_progress_note))
            }
            ExportPhase.READY, ExportPhase.DOWNLOADING -> {
                Text(stringResource(R.string.pv_export_ready))
                state.export?.downloadExpiresAtMs?.let { Text(stringResource(R.string.pv_export_expires, formatTime(it))) }
                Small(stringResource(R.string.pv_export_file_warning))
                Button(enabled = state.phase == ExportPhase.READY, onClick = { onSave(exportFileName(state)) }) { Text(stringResource(R.string.pv_export_save)) }
                if (state.phase == ExportPhase.DOWNLOADING) Text(stringResource(R.string.pv_export_downloading))
            }
        }
        BackButton(onBack)
    }
}

/** `qal-data-export-<requested date>.json` (the day is in UTC so the name is the same on every device). */
internal fun exportFileName(state: ExportState): String {
    val ms = state.export?.requestedAtMs ?: System.currentTimeMillis()
    val day = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate()
    return "qal-data-export-$day.json"
}

// ------------------------------------------------------------------ delete account

@Composable
fun DeleteAccountScreen(
    account: AccountStatus,
    state: DeletionState,
    onRequest: (typed: String) -> Unit,
    onCancelDeletion: () -> Unit,
    onExport: () -> Unit,
    onSignIn: () -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var typed by rememberSaveable { mutableStateOf("") }
    val word = stringResource(R.string.pv_delete_word)
    val matches = typed.trim().equals(word, ignoreCase = true)
    val info = state.info
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = spacing) {
        Title(stringResource(R.string.pv_delete_title))
        state.message?.let { ErrorText(messageText(it)) }
        when (state.phase) {
            DeletionPhase.SCHEDULED_SIGNED_OUT -> {
                Text(stringResource(R.string.pv_delete_scheduled, formatDay(info?.scheduledPurgeAtMs)))
                Text(stringResource(R.string.pv_delete_signed_out))
                if ((info?.activeStoreSubscriptions ?: 0) > 0) Text(stringResource(R.string.pv_delete_store_subs), color = MaterialTheme.colorScheme.error)
                Button(onClick = onDone) { Text(stringResource(R.string.pv_ok)) }
            }
            DeletionPhase.PENDING, DeletionPhase.CANCELLING -> {
                Text(stringResource(R.string.pv_delete_pending, formatDay(info?.scheduledPurgeAtMs)), color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.pv_delete_pending_note))
                if ((info?.activeStoreSubscriptions ?: 0) > 0) Text(stringResource(R.string.pv_delete_store_subs), color = MaterialTheme.colorScheme.error)
                if (account == AccountStatus.Guest) Small(stringResource(R.string.pv_delete_guest_note))
                if (info?.cancellable != false) {
                    Button(enabled = state.phase == DeletionPhase.PENDING, onClick = onCancelDeletion) { Text(stringResource(R.string.pv_delete_cancel_button)) }
                } else {
                    Text(stringResource(R.string.pv_delete_started))
                }
                OutlinedButton(onClick = onExport) { Text(stringResource(R.string.pv_export_button)) }
            }
            else -> {
                if (account is AccountStatus.SignedOut) {
                    Text(stringResource(R.string.pv_delete_signed_out_info))
                    Button(onClick = onSignIn) { Text(stringResource(R.string.acct_sign_in)) }
                } else {
                    Text(stringResource(R.string.pv_delete_what))
                    Text(stringResource(R.string.pv_delete_grace))
                    Text(stringResource(R.string.pv_delete_device))
                    if (account == AccountStatus.Guest) Text(stringResource(R.string.pv_delete_guest_note), color = MaterialTheme.colorScheme.error)
                    Section(stringResource(R.string.pv_delete_export_first_title)) {
                        Text(stringResource(R.string.pv_delete_export_first))
                        OutlinedButton(onClick = onExport) { Text(stringResource(R.string.pv_export_button)) }
                    }
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        label = { Text(stringResource(R.string.pv_delete_type, word)) },
                        singleLine = true,
                        enabled = state.phase != DeletionPhase.REQUESTING,
                    )
                    Button(
                        enabled = matches && state.phase == DeletionPhase.IDLE,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                        onClick = { onRequest(typed) },
                    ) { Text(stringResource(R.string.pv_delete_confirm_button)) }
                    if (state.phase == DeletionPhase.REQUESTING) Text(stringResource(R.string.pv_loading))
                }
            }
        }
        BackButton(onBack)
    }
}
