package com.qalsmartkeyboard.app.creative.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qalsmartkeyboard.app.R
import java.util.Locale

/** What the card's buttons do. The screen wires them to the view model; the dialogs are the card's own. */
class BackupActions(
    val onSignIn: () -> Unit,
    val onTurnOn: () -> Unit,
    val onTurnOff: () -> Unit,
    val onBackUp: () -> Unit,
    val onRestore: () -> Unit,
    val onDeleteCloud: () -> Unit,
    val onRetry: () -> Unit,
    val onDismiss: () -> Unit,
)

/**
 * The Creative studio's "Cloud backup of your drawings" card (T8.15 S3): honest text first (an encrypted copy on our server,
 * NOT end-to-end, off by default, deletable), then the state's actions. Consent is turned on and off only through a
 * confirmation that says what it does; "Back up now" is a separate tap, so nothing is sent by turning the backup on. Stateless
 * apart from the three confirmation dialogs; [state] comes from [CreativeBackupModel].
 */
@Composable
fun CloudBackupCard(state: BackupUiState, actions: BackupActions, modifier: Modifier = Modifier) {
    var confirmOn by remember { mutableStateOf(false) }
    var confirmOff by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.backup_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            if (state.phase == BackupPhase.UNAVAILABLE) {
                Text(stringResource(R.string.backup_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(stringResource(R.string.backup_explain), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.backup_honest), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (state.phase) {
                BackupPhase.CHECKING -> Working(stringResource(R.string.backup_checking), null)
                BackupPhase.UNAVAILABLE -> Unit
                BackupPhase.SIGNED_OUT -> {
                    Text(stringResource(R.string.backup_signed_out))
                    Action(R.string.backup_sign_in, primary = true, onClick = actions.onSignIn)
                }
                BackupPhase.GUEST -> {
                    Text(stringResource(R.string.backup_guest))
                    Action(R.string.backup_sign_in, primary = true, onClick = actions.onSignIn)
                }
                BackupPhase.PROBLEM -> {
                    state.problem?.let { Text(backupErrorText(it, state.summary), color = MaterialTheme.colorScheme.error) }
                    Action(R.string.backup_try_again, primary = true, onClick = actions.onRetry)
                }
                BackupPhase.CONSENT_OFF -> {
                    val busy = state.working
                    Action(R.string.backup_turn_on, primary = true, enabled = !busy) { confirmOn = true }
                    // Withdrawing deletes the cloud copies, so there is normally nothing left to delete here.
                    if ((state.summary?.itemCount ?: 0) > 0) Action(R.string.backup_delete, enabled = !busy) { confirmDelete = true }
                    state.work?.let { WorkRow(it) }
                }
                BackupPhase.CONSENT_ON -> {
                    state.summary?.let { s ->
                        Text(
                            stringResource(R.string.backup_usage, s.itemCount, formatBackupSize(s.totalBytes), formatBackupSize(s.maxTotalBytes)),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    state.problem?.let { Text(backupErrorText(it, state.summary), color = MaterialTheme.colorScheme.error) }
                    val busy = state.working
                    state.work?.let { WorkRow(it) }
                    Action(R.string.backup_now, primary = true, enabled = !busy, onClick = actions.onBackUp)
                    Action(R.string.backup_restore, outlined = true, enabled = !busy, onClick = actions.onRestore)
                    Action(R.string.backup_delete, enabled = !busy) { confirmDelete = true }
                    Action(R.string.backup_turn_off, enabled = !busy) { confirmOff = true }
                }
            }
            state.result?.let { ResultBlock(it, state.summary, actions.onDismiss) }
        }
    }
    if (confirmOn) {
        ConfirmDialog(R.string.backup_turn_on_title, R.string.backup_turn_on_body, R.string.backup_turn_on_confirm, onDismiss = { confirmOn = false }) {
            confirmOn = false
            actions.onTurnOn()
        }
    }
    if (confirmOff) {
        ConfirmDialog(R.string.backup_turn_off_title, R.string.backup_turn_off_body, R.string.backup_turn_off, onDismiss = { confirmOff = false }) {
            confirmOff = false
            actions.onTurnOff()
        }
    }
    if (confirmDelete) {
        ConfirmDialog(R.string.backup_delete_title, R.string.backup_delete_body, R.string.backup_delete, onDismiss = { confirmDelete = false }) {
            confirmDelete = false
            actions.onDeleteCloud()
        }
    }
}

@Composable
private fun Action(label: Int, primary: Boolean = false, outlined: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    when {
        primary -> Button(onClick, modifier, enabled = enabled) { Text(stringResource(label)) }
        outlined -> OutlinedButton(onClick, modifier, enabled = enabled) { Text(stringResource(label)) }
        else -> TextButton(onClick, modifier, enabled = enabled) { Text(stringResource(label)) }
    }
}

@Composable
private fun WorkRow(work: BackupWork) {
    when (work) {
        is BackupWork.BackingUp -> Working(
            if (work.total > 0) stringResource(R.string.backup_progress_up, work.done, work.total) else stringResource(R.string.backup_checking),
            work.fraction,
        )
        is BackupWork.Restoring -> Working(
            if (work.total > 0) stringResource(R.string.backup_progress_restore, work.done, work.total) else stringResource(R.string.backup_checking),
            work.fraction,
        )
        BackupWork.Deleting -> Working(stringResource(R.string.backup_progress_delete), null)
        BackupWork.Consent -> Working(stringResource(R.string.backup_progress_consent), null)
    }
}

private val BackupWork.fraction: Float?
    get() = when (this) {
        is BackupWork.BackingUp -> if (total > 0) done.toFloat() / total else null
        is BackupWork.Restoring -> if (total > 0) done.toFloat() / total else null
        else -> null
    }

@Composable
private fun Working(text: String, fraction: Float?) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            Text(text, Modifier.weight(1f))
        }
        if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ResultBlock(result: BackupResult, summary: RemoteSummary?, onDismiss: () -> Unit) {
    val lines = resultLines(result, summary)
    val isError = result is BackupResult.Failed || result is BackupResult.Busy ||
        (result is BackupResult.BackedUp && result.report.stopped != null) ||
        (result is BackupResult.Restored && result.report.stopped != null)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (line in lines) {
                Text(line, style = MaterialTheme.typography.bodyMedium, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
        }
        TextButton(onDismiss, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.backup_r_dismiss)) }
    }
}

/** The lines a result shows: counts of what happened, then why a run stopped. Never a name, an id or a server text. */
@Composable
private fun resultLines(result: BackupResult, summary: RemoteSummary?): List<String> = when (result) {
    is BackupResult.BackedUp -> reportLines(result.report, summary, R.string.backup_r_nothing_backup)
    is BackupResult.Restored -> reportLines(result.report, summary, R.string.backup_r_nothing_restore)
    BackupResult.CloudDeleted -> listOf(stringResource(R.string.backup_r_cleared))
    BackupResult.TurnedOn -> listOf(stringResource(R.string.backup_r_turned_on))
    BackupResult.TurnedOff -> listOf(stringResource(R.string.backup_r_turned_off))
    is BackupResult.Failed -> listOf(backupErrorText(result.error, summary))
    BackupResult.Busy -> listOf(stringResource(R.string.backup_r_busy))
}

@Composable
private fun reportLines(r: RunReport, summary: RemoteSummary?, nothing: Int): List<String> {
    val lines = ArrayList<String>()
    if (r.uploaded > 0) lines += stringResource(R.string.backup_r_uploaded, r.uploaded)
    if (r.restored > 0) lines += stringResource(R.string.backup_r_restored, r.restored)
    if (r.keptNewerCloud > 0) lines += stringResource(R.string.backup_r_cloud_newer, r.keptNewerCloud)
    if (r.keptNewerLocal > 0) lines += stringResource(R.string.backup_r_local_newer, r.keptNewerLocal)
    if (r.tooLarge > 0) lines += stringResource(R.string.backup_r_too_large, r.tooLarge)
    if (r.invalid > 0) lines += stringResource(R.string.backup_r_invalid, r.invalid)
    if (r.unsupported > 0) lines += stringResource(R.string.backup_r_unsupported, r.unsupported)
    if (r.deviceFull > 0) lines += stringResource(R.string.backup_r_full, r.deviceFull)
    if (r.failed > 0) lines += stringResource(R.string.backup_r_failed, r.failed)
    r.stopped?.let { lines += backupErrorText(it, summary) }
    if (r.notAttempted > 0) lines += stringResource(R.string.backup_r_left, r.notAttempted)
    if (r.upToDate > 0 && (r.uploaded > 0 || r.restored > 0 || lines.isNotEmpty())) lines += stringResource(R.string.backup_r_uptodate, r.upToDate)
    if (lines.isEmpty()) lines += stringResource(nothing)
    return lines
}

/** One plain sentence per cause (rate limit with its retry time, quota with the limit, too large with the cap). */
@Composable
internal fun backupErrorText(e: BackupError, summary: RemoteSummary?): String = when (e) {
    BackupError.Offline -> stringResource(R.string.backup_e_offline)
    BackupError.Timeout -> stringResource(R.string.backup_e_timeout)
    BackupError.SignedOut -> stringResource(R.string.backup_e_signed_out)
    BackupError.GuestNotAllowed -> stringResource(R.string.backup_e_guest)
    BackupError.ConsentRequired -> stringResource(R.string.backup_e_consent)
    BackupError.FeatureOff -> stringResource(R.string.backup_e_feature)
    BackupError.UpdateRequired -> stringResource(R.string.backup_e_update)
    is BackupError.RateLimited ->
        e.retryAfterSeconds?.let { stringResource(R.string.backup_e_rate, retryMinutes(it)) } ?: stringResource(R.string.backup_e_rate_unknown)
    is BackupError.QuotaExceeded -> when {
        e.quota == "ITEMS_PER_KIND" && e.limit != null -> stringResource(R.string.backup_e_quota_items, e.limit)
        e.quota == "TOTAL_BYTES" && e.limit != null -> stringResource(R.string.backup_e_quota_bytes, formatBackupSize(e.limit))
        else -> stringResource(R.string.backup_e_quota_unknown)
    }
    is BackupError.TooLarge -> (e.maxItemBytes ?: summary?.maxItemBytes)?.let { stringResource(R.string.backup_e_too_large, formatBackupSize(it)) }
        ?: stringResource(R.string.backup_e_too_large_unknown)
    is BackupError.RevisionConflict -> stringResource(R.string.backup_e_conflict)
    BackupError.NotFound -> stringResource(R.string.backup_e_not_found)
    BackupError.Failed -> stringResource(R.string.backup_e_failed)
}

/** Whole minutes to wait, at least one (a `Retry-After` of 20 s still says "about 1 min"). */
internal fun retryMinutes(seconds: Long): Int = ((seconds + 59) / 60).coerceIn(1, 24 * 60).toInt()

/** "512 KB", "1.5 MB", "32 MB": binary megabytes, one decimal only when it is not whole. */
internal fun formatBackupSize(bytes: Long, locale: Locale = Locale.getDefault()): String {
    if (bytes < MIB) return String.format(locale, "%d KB", ((bytes + 1023) / 1024).coerceAtLeast(if (bytes > 0) 1 else 0))
    val mib = bytes / MIB.toDouble()
    return if (mib == Math.floor(mib)) String.format(locale, "%d MB", mib.toLong()) else String.format(locale, "%.1f MB", mib)
}

private const val MIB = 1024L * 1024

@Composable
private fun ConfirmDialog(title: Int, body: Int, confirm: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body)) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.pd_cancel)) } },
    )
}
