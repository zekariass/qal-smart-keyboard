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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
    val onMethod: (SignInMethod) -> Unit = {},
    val onEmail: (String) -> Unit = {},
    val onPassword: (String) -> Unit = {},
    val onTogglePassword: () -> Unit = {},
    val onEmailMode: (EmailMode) -> Unit = {},
    val onEmailSubmit: () -> Unit = {},
    val onForgot: () -> Unit = {},
    val onForgotSubmit: () -> Unit = {},
    val onResetCode: (String) -> Unit = {},
    val onNewPassword: (String) -> Unit = {},
    val onResetSubmit: () -> Unit = {},
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
    SignInMessage.EmailEmpty -> stringResource(R.string.si_err_email_empty)
    SignInMessage.EmailInvalid -> stringResource(R.string.si_err_email_invalid)
    SignInMessage.PasswordEmpty -> stringResource(R.string.si_err_password_empty)
    SignInMessage.PasswordTooShort -> stringResource(R.string.si_err_password_short, SignInModel.MIN_PASSWORD)
    SignInMessage.PasswordTooLong -> stringResource(R.string.si_err_password_long, SignInModel.MAX_PASSWORD)
    SignInMessage.PasswordTooCommon -> stringResource(R.string.si_err_password_common)
    SignInMessage.PasswordMatchesEmail -> stringResource(R.string.si_err_password_email)
    SignInMessage.InvalidCredentials -> stringResource(R.string.si_err_credentials)
    is SignInMessage.AccountLocked ->
        if (m.retryAfterSeconds != null) stringResource(R.string.si_err_locked_in, ((m.retryAfterSeconds + 59) / 60).toInt().coerceAtLeast(1))
        else stringResource(R.string.si_err_locked)
    SignInMessage.EmailAlreadyRegistered -> stringResource(R.string.si_err_email_taken)
    SignInMessage.EmailUnavailable -> stringResource(R.string.si_err_email_unavailable)
    SignInMessage.ResetCodeInvalid -> stringResource(R.string.si_err_reset_code)
}

@Composable
private fun noticeText(n: SignInNotice): String = when (n) {
    SignInNotice.RESET_CODE_SENT -> stringResource(R.string.si_reset_sent)
    SignInNotice.PASSWORD_CHANGED -> stringResource(R.string.si_reset_done)
}

/**
 * The sign-in content (phone or e-mail, code, password reset, done). The caller provides scrolling and the surrounding
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
            SignInStep.CODE, SignInStep.EMAIL_CODE -> CodeStep(state, actions)
            SignInStep.RESET_REQUEST -> ResetRequestStep(state, actions)
            SignInStep.RESET_CODE -> ResetCodeStep(state, actions)
            SignInStep.DONE -> DoneStep(state, actions)
        }
        state.notice?.let {
            Text(noticeText(it), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
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
    MethodSelector(state.method, actions.onMethod)
    if (state.method == SignInMethod.EMAIL) {
        EmailForm(state, actions)
    } else {
        PhoneForm(state, actions, googleAvailable)
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

/** "Phone | Email" (Google stays a separate button below). */
@Composable
private fun MethodSelector(method: SignInMethod, onMethod: (SignInMethod) -> Unit) {
    val options = listOf(SignInMethod.PHONE to R.string.si_method_phone, SignInMethod.EMAIL to R.string.si_method_email)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (m, label) ->
            SegmentedButton(
                selected = method == m,
                onClick = { onMethod(m) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
            ) { Text(stringResource(label)) }
        }
    }
}

@Composable
private fun PhoneForm(state: SignInState, actions: SignInActions, googleAvailable: Boolean) {
    val idle = state.busy == null
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
}

@Composable
private fun EmailField(value: String, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(stringResource(R.string.si_email_label)) },
        placeholder = { Text(stringResource(R.string.si_email_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.EmailAddress },
    )
}

/** Password with show/hide; [newPassword] marks it for password managers as a password being created. */
@Composable
private fun PasswordField(value: String, onValue: (String) -> Unit, visible: Boolean, onToggle: () -> Unit, label: Int, newPassword: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(stringResource(label)) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
        trailingIcon = {
            TextButton(onClick = onToggle) { Text(stringResource(if (visible) R.string.si_password_hide else R.string.si_password_show)) }
        },
        modifier = Modifier.fillMaxWidth().semantics { contentType = if (newPassword) ContentType.NewPassword else ContentType.Password },
    )
}

@Composable
private fun EmailForm(state: SignInState, actions: SignInActions) {
    val idle = state.busy == null
    val create = state.emailMode == EmailMode.CREATE
    EmailField(state.email, actions.onEmail)
    PasswordField(
        state.password, actions.onPassword, state.passwordVisible, actions.onTogglePassword,
        if (create) R.string.si_password_new_label else R.string.si_password_label, newPassword = create,
    )
    if (create) {
        Text(stringResource(R.string.si_password_rules, SignInModel.MIN_PASSWORD), style = MaterialTheme.typography.bodySmall)
        AgeConfirm(state, actions)
    }
    Button(onClick = actions.onEmailSubmit, enabled = idle, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(if (create) R.string.si_email_create else R.string.si_email_sign_in))
    }
    if (!create) {
        TextButton(onClick = actions.onForgot, enabled = idle) { Text(stringResource(R.string.si_forgot)) }
    }
    TextButton(onClick = { actions.onEmailMode(if (create) EmailMode.SIGN_IN else EmailMode.CREATE) }, enabled = idle) {
        Text(stringResource(if (create) R.string.si_email_have_account else R.string.si_email_new_here))
    }
}

@Composable
private fun ResetRequestStep(state: SignInState, actions: SignInActions) {
    val idle = state.busy == null
    Text(stringResource(R.string.si_reset_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    Text(stringResource(R.string.si_reset_body))
    EmailField(state.email, actions.onEmail)
    Button(onClick = actions.onForgotSubmit, enabled = idle, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.si_send_code))
    }
    TextButton(onClick = actions.onChangeNumber, enabled = idle) { Text(stringResource(R.string.si_back_to_sign_in)) }
}

@Composable
private fun ResetCodeStep(state: SignInState, actions: SignInActions) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    val idle = state.busy == null
    Text(stringResource(R.string.si_reset_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    Text(stringResource(R.string.si_reset_code_sent, SignInModel.RESET_CODE_LENGTH, state.email))
    OutlinedTextField(
        value = state.code,
        onValueChange = actions.onResetCode,
        label = { Text(stringResource(R.string.si_code_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.SmsOtpCode },
    )
    PasswordField(state.newPassword, actions.onNewPassword, state.passwordVisible, actions.onTogglePassword, R.string.si_password_new_label, newPassword = true)
    Text(stringResource(R.string.si_password_rules, SignInModel.MIN_PASSWORD), style = MaterialTheme.typography.bodySmall)
    Button(
        onClick = actions.onResetSubmit,
        enabled = idle && state.code.length == SignInModel.RESET_CODE_LENGTH && state.newPassword.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.si_reset_button)) }
    val left = state.resendSecondsLeft(now)
    OutlinedButton(onClick = actions.onResend, enabled = state.canResend(now), modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.si_resend))
    }
    if (left > 0) Text(stringResource(R.string.si_resend_in, left.toInt()), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = actions.onChangeNumber, enabled = idle) { Text(stringResource(R.string.si_back_to_sign_in)) }
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
    val email = state.step == SignInStep.EMAIL_CODE
    Text(stringResource(R.string.si_code_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    if (email) {
        Text(stringResource(R.string.si_email_code_sent, length, state.email))
    } else {
        Text(stringResource(R.string.si_code_sent, length, state.phoneE164.orEmpty()))
    }
    OutlinedTextField(
        value = state.code,
        onValueChange = actions.onCode,
        label = { Text(stringResource(R.string.si_code_label)) },
        singleLine = true,
        enabled = !state.challengeDead,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.SmsOtpCode },
    )
    if (!state.challengeDead) Text(stringResource(R.string.si_attempts_left, state.attemptsLeft), style = MaterialTheme.typography.bodySmall)
    // the e-mail sign-up confirmed the age on the form; phone confirms it here (new accounts only)
    if (!email) AgeConfirm(state, actions)
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
    TextButton(onClick = actions.onChangeNumber, enabled = idle) {
        Text(stringResource(if (email) R.string.si_change_email else R.string.si_change_number))
    }
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
