package com.qalsmartkeyboard.app.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.qalsmartkeyboard.api.SignedOutReason
import com.qalsmartkeyboard.app.R
import kotlinx.coroutines.delay

/** Everything the sign-in UI can ask for, as plain callbacks (so the composables are testable without a view model). */
class SignInActions(
    val onPhone: (String) -> Unit = {},
    val onSendCode: () -> Unit = {},
    val onCode: (String) -> Unit = {},
    val onVerify: () -> Unit = {},
    val onResend: () -> Unit = {},
    val onAgeConfirmed: (Boolean) -> Unit = {},
    val onGoogle: () -> Unit = {},
    val onGuest: () -> Unit = {},
    val onChangeNumber: () -> Unit = {},
    val onDone: () -> Unit = {},
)

@Composable
private fun messageText(m: SignInMessage): String = when (m) {
    SignInMessage.PhoneEmpty -> stringResource(R.string.si_err_phone_empty)
    SignInMessage.PhoneRegionNotSupported -> stringResource(R.string.si_err_phone_region)
    SignInMessage.PhoneCarrierNotSupported -> stringResource(R.string.si_err_phone_carrier)
    SignInMessage.PhoneNotEthiopianMobile -> stringResource(R.string.si_err_phone_mobile)
    SignInMessage.CodeIncomplete -> stringResource(R.string.si_err_code_incomplete)
    SignInMessage.OtpInvalid -> stringResource(R.string.si_err_otp_invalid)
    SignInMessage.OtpExpired -> stringResource(R.string.si_err_otp_expired)
    SignInMessage.OtpTooManyAttempts -> stringResource(R.string.si_err_otp_locked)
    is SignInMessage.RateLimited ->
        if (m.retryAfterSeconds != null) stringResource(R.string.si_err_rate_limited_in, m.retryAfterSeconds.toInt())
        else stringResource(R.string.si_err_rate_limited)
    SignInMessage.AgeRequired -> stringResource(R.string.si_err_age)
    SignInMessage.AccountSuspended -> stringResource(R.string.si_err_suspended)
    SignInMessage.Offline -> stringResource(R.string.si_err_offline)
    SignInMessage.Timeout -> stringResource(R.string.si_err_timeout)
    SignInMessage.GoogleUnavailable -> stringResource(R.string.si_err_google_unavailable)
    SignInMessage.GoogleFailed -> stringResource(R.string.si_err_google_failed)
    SignInMessage.GoogleRejected -> stringResource(R.string.si_err_google_rejected)
    SignInMessage.ProviderUnavailable -> stringResource(R.string.si_err_provider)
    SignInMessage.GuestNotAllowed -> stringResource(R.string.si_err_guest_not_allowed)
    SignInMessage.GuestUnavailable -> stringResource(R.string.si_err_guest_unavailable)
    SignInMessage.SessionEnded -> stringResource(R.string.si_err_session_ended)
    SignInMessage.Unexpected -> stringResource(R.string.si_err_unexpected)
}

/**
 * The sign-in content (phone, code, done). The caller provides scrolling and the surrounding
 * screen. [googleAvailable] and [guestAvailable] hide the buttons the build cannot serve;
 * [hasGuestSession] says a guest session exists (so signing in keeps its data).
 */
@Composable
fun SignInPane(
    state: SignInState,
    actions: SignInActions,
    googleAvailable: Boolean,
    guestAvailable: Boolean,
    hasGuestSession: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (state.step) {
            SignInStep.OPTIONS -> OptionsStep(state, actions, googleAvailable, guestAvailable && !hasGuestSession, hasGuestSession)
            SignInStep.CODE -> CodeStep(state, actions)
            SignInStep.DONE -> DoneStep(state, actions)
        }
        state.message?.let {
            Text(messageText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        if (state.busy != null) Text(stringResource(R.string.si_working), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AgeConfirm(state: SignInState, actions: SignInActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = state.ageConfirmed, onCheckedChange = actions.onAgeConfirmed)
        Text(stringResource(R.string.si_age_confirm), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun OptionsStep(
    state: SignInState,
    actions: SignInActions,
    googleAvailable: Boolean,
    guestAvailable: Boolean,
    hasGuestSession: Boolean,
) {
    val idle = state.busy == null
    Text(stringResource(R.string.si_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    if (hasGuestSession) Text(stringResource(R.string.si_guest_keep))
    OutlinedTextField(
        value = state.phoneInput,
        onValueChange = actions.onPhone,
        label = { Text(stringResource(R.string.si_phone_label)) },
        placeholder = { Text(stringResource(R.string.si_phone_hint)) },
        singleLine = true,
        leadingIcon = { Text(PhoneInput.DEFAULT_PREFIX) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth(),
    )
    Text(stringResource(R.string.si_phone_region_help), style = MaterialTheme.typography.bodySmall)
    if (googleAvailable) {
        TextButton(onClick = actions.onGoogle, enabled = idle) { Text(stringResource(R.string.si_phone_use_google)) }
    }
    AgeConfirm(state, actions)
    Button(onClick = actions.onSendCode, enabled = idle, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.si_send_code))
    }
    if (googleAvailable || guestAvailable) Text(stringResource(R.string.si_or), Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    if (googleAvailable) {
        OutlinedButton(onClick = actions.onGoogle, enabled = idle, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.si_google))
        }
    }
    if (guestAvailable) {
        OutlinedButton(onClick = actions.onGuest, enabled = idle, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.si_guest))
        }
        Text(stringResource(R.string.si_guest_help), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CodeStep(state: SignInState, actions: SignInActions) {
    val length = state.challenge?.codeLength ?: SignInModel.MAX_CODE_LENGTH
    // Ticks once a second while the code step is visible, only to refresh the resend countdown.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    val idle = state.busy == null
    Text(stringResource(R.string.si_code_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    Text(stringResource(R.string.si_code_sent, length, state.phoneE164.orEmpty()))
    OutlinedTextField(
        value = state.code,
        onValueChange = actions.onCode,
        label = { Text(stringResource(R.string.si_code_label)) },
        singleLine = true,
        enabled = !state.challengeDead,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
    if (!state.challengeDead) Text(stringResource(R.string.si_attempts_left, state.attemptsLeft), style = MaterialTheme.typography.bodySmall)
    AgeConfirm(state, actions)
    Button(
        onClick = actions.onVerify,
        enabled = idle && !state.challengeDead && state.code.length == length,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.si_verify)) }
    val left = state.resendSecondsLeft(now)
    OutlinedButton(onClick = actions.onResend, enabled = state.canResend(now), modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.si_resend))
    }
    if (left > 0 && !state.challengeDead) Text(stringResource(R.string.si_resend_in, left.toInt()), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = actions.onChangeNumber, enabled = idle) { Text(stringResource(R.string.si_change_number)) }
}

@Composable
private fun DoneStep(state: SignInState, actions: SignInActions) {
    val outcome = state.outcome
    Text(
        stringResource(if (outcome?.kind == AccountKind.GUEST) R.string.si_done_guest else R.string.si_done_title),
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { heading() },
    )
    if (outcome != null && outcome.kind == AccountKind.REGISTERED) {
        Text(stringResource(if (outcome.isNewUser) R.string.si_done_new else R.string.si_done_existing))
    }
    when (outcome?.guestLink) {
        GuestLink.UPGRADED_IN_PLACE -> Text(stringResource(R.string.si_done_guest_upgraded))
        GuestLink.MERGED -> Text(stringResource(R.string.si_done_guest_merged))
        else -> Unit
    }
    Button(onClick = actions.onDone) { Text(stringResource(R.string.si_done_button)) }
}

/** "Account" card for the settings screen. */
@Composable
fun AccountSection(
    status: AccountStatus,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.acct_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            when (status) {
                is AccountStatus.SignedOut -> {
                    val ended = status.reason != null && status.reason != SignedOutReason.SIGNED_OUT
                    Text(stringResource(if (ended) R.string.acct_session_ended else R.string.acct_signed_out))
                    Button(onClick = onSignIn) { Text(stringResource(R.string.acct_sign_in)) }
                }
                AccountStatus.Guest -> {
                    Text(stringResource(R.string.acct_guest))
                    Button(onClick = onSignIn) { Text(stringResource(R.string.acct_sign_in)) }
                }
                AccountStatus.Registered -> {
                    Text(stringResource(R.string.acct_signed_in))
                    OutlinedButton(onClick = { confirming = true }) { Text(stringResource(R.string.acct_sign_out)) }
                }
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.acct_sign_out_title)) },
            text = { Text(stringResource(R.string.acct_sign_out_message)) },
            confirmButton = {
                TextButton(onClick = { confirming = false; onSignOut() }) { Text(stringResource(R.string.acct_sign_out)) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.pd_cancel)) } },
        )
    }
}

/** 426: the app is older than the platform minimum. Typing is unaffected; "Not now" returns to the app. */
@Composable
fun UpdateRequiredScreen(onOpenStore: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.upd_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.upd_body))
        Button(onClick = onOpenStore) { Text(stringResource(R.string.upd_button)) }
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.upd_dismiss)) }
    }
}

/** Opens the app's store page (Play Store app, or the web page when no store app exists). */
fun openStorePage(context: Context) {
    val pkg = context.packageName
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(market)
    } catch (_: android.content.ActivityNotFoundException) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
