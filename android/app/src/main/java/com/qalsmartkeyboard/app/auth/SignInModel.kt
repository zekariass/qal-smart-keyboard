package com.qalsmartkeyboard.app.auth

import com.qalsmartkeyboard.api.ApiError
import com.qalsmartkeyboard.api.ProblemCodes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.security.SecureRandom

/**
 * OPTIONS: phone or e-mail entry. CODE: the SMS code. EMAIL_CODE: the e-mailed verification code
 * (sign-up with verification, or an unverified account signing in). RESET_REQUEST / RESET_CODE:
 * "forgot password" (address, then code + new password).
 */
enum class SignInStep { OPTIONS, CODE, EMAIL_CODE, RESET_REQUEST, RESET_CODE, DONE }

/** Phone or e-mail on the options step (Google stays a separate button). */
enum class SignInMethod { PHONE, EMAIL }

/** The e-mail form signs in to an existing account or creates one. */
enum class EmailMode { SIGN_IN, CREATE }

/** What is running (the buttons are disabled meanwhile). */
enum class Busy { PHONE, CODE, RESEND, GOOGLE, GUEST, EMAIL, FORGOT, RESET }

/** One user-visible problem; the UI maps each to a string (no server text is shown). */
sealed interface SignInMessage {
    data object PhoneEmpty : SignInMessage
    /** The server refused a non-Ethiopian number (`PHONE_REGION_NOT_SUPPORTED`). */
    data object PhoneRegionNotSupported : SignInMessage
    data object PhoneNotEthiopianMobile : SignInMessage

    /** Safaricom Ethiopia (07...): no sign-in codes yet (`PHONE_CARRIER_NOT_SUPPORTED`, or refused on the device). */
    data object PhoneCarrierNotSupported : SignInMessage
    data object CodeIncomplete : SignInMessage
    data object OtpInvalid : SignInMessage
    data object OtpExpired : SignInMessage
    data object OtpTooManyAttempts : SignInMessage
    data class RateLimited(val retryAfterSeconds: Long?) : SignInMessage
    data object AgeRequired : SignInMessage
    data object AccountSuspended : SignInMessage
    data object Offline : SignInMessage
    data object Timeout : SignInMessage
    data object GoogleUnavailable : SignInMessage
    data object GoogleFailed : SignInMessage
    data object GoogleRejected : SignInMessage
    data object ProviderUnavailable : SignInMessage
    data object GuestNotAllowed : SignInMessage
    data object GuestUnavailable : SignInMessage
    data object SessionEnded : SignInMessage
    data object Unexpected : SignInMessage

    // E-mail and password (ADR 0033)
    data object EmailEmpty : SignInMessage
    data object EmailInvalid : SignInMessage
    data object PasswordEmpty : SignInMessage
    data object PasswordTooShort : SignInMessage
    data object PasswordTooLong : SignInMessage
    data object PasswordTooCommon : SignInMessage
    data object PasswordMatchesEmail : SignInMessage

    /** Wrong address or password (the server does not say which). */
    data object InvalidCredentials : SignInMessage

    /** Too many wrong passwords; [retryAfterSeconds] from `Retry-After`. */
    data class AccountLocked(val retryAfterSeconds: Long?) : SignInMessage
    data object EmailAlreadyRegistered : SignInMessage

    /** The server cannot use e-mail right now (`EMAIL_NOT_CONFIGURED`). */
    data object EmailUnavailable : SignInMessage
    data object ResetCodeInvalid : SignInMessage
}

/** Good news shown above the form (not an error). */
enum class SignInNotice { RESET_CODE_SENT, PASSWORD_CHANGED }

data class SignInState(
    val step: SignInStep = SignInStep.OPTIONS,
    val method: SignInMethod = SignInMethod.PHONE,
    val phoneInput: String = "",
    /** The normalised number the code was sent to (kept only in memory, never logged or saved). */
    val phoneE164: String? = null,
    /** E-mail form (kept only in memory, never logged or saved). */
    val email: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val emailMode: EmailMode = EmailMode.SIGN_IN,
    /** The new password of a reset. */
    val newPassword: String = "",
    val code: String = "",
    val ageConfirmed: Boolean = false,
    /** The SMS challenge, or the e-mail verification challenge on [SignInStep.EMAIL_CODE]. */
    val challenge: OtpChallengeInfo? = null,
    /** Wrong codes the server still accepts for this challenge (5 per challenge, B2.1). */
    val attemptsLeft: Int = SignInModel.MAX_ATTEMPTS,
    /** The challenge cannot take more codes (expired or locked): only a new code helps. */
    val challengeDead: Boolean = false,
    /** Earliest time (epoch ms, device clock) "send again" is allowed. */
    val resendAtMs: Long = 0,
    val busy: Busy? = null,
    val message: SignInMessage? = null,
    val notice: SignInNotice? = null,
    val outcome: SignInOutcome? = null,
    /** A 426 was seen: the app is below the platform minimum. */
    val updateRequired: Boolean = false,
) {
    fun resendSecondsLeft(nowMs: Long): Long = ((resendAtMs - nowMs + 999) / 1000).coerceAtLeast(0)
    fun canResend(nowMs: Long): Boolean = busy == null && (challengeDead || resendSecondsLeft(nowMs) == 0L)

    /** Never prints the address, password, number or code. */
    override fun toString(): String = "SignInState(step=$step, method=$method, busy=$busy, message=$message)"
}

/**
 * State machine of the sign-in screens: phone or e-mail entry -> code entry -> done, plus Google,
 * guest and "forgot password". Android-free (the Google chooser is a [GoogleIdTokenSource]
 * argument), so the flows are tested on the JVM against `MockWebServer`. Operations are suspend
 * functions; the view model launches them. Nothing sensitive (numbers, addresses, passwords,
 * codes, tokens) is logged.
 */
class SignInModel(
    private val repository: AuthRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val nonces: () -> String = ::randomNonce,
) {
    private val _state = MutableStateFlow(SignInState())
    val state: StateFlow<SignInState> = _state.asStateFlow()

    fun setPhone(text: String) = _state.update { it.copy(phoneInput = PhoneInput.sanitize(text), message = null) }
    fun setAgeConfirmed(on: Boolean) = _state.update { it.copy(ageConfirmed = on, message = null) }
    fun setCode(text: String) {
        val max = _state.value.challenge?.codeLength ?: MAX_CODE_LENGTH
        _state.update { it.copy(code = text.filter { c -> c in '0'..'9' }.take(max), message = null) }
    }

    fun setMethod(method: SignInMethod) = _state.update { it.copy(method = method, message = null, notice = null) }
    fun setEmail(text: String) = _state.update { it.copy(email = text.filterNot { c -> c.isWhitespace() }.take(MAX_EMAIL), message = null) }
    fun setPassword(text: String) = _state.update { it.copy(password = text.take(MAX_PASSWORD_CHARS), message = null) }
    fun setNewPassword(text: String) = _state.update { it.copy(newPassword = text.take(MAX_PASSWORD_CHARS), message = null) }
    fun togglePasswordVisible() = _state.update { it.copy(passwordVisible = !it.passwordVisible) }
    fun setEmailMode(mode: EmailMode) = _state.update { it.copy(emailMode = mode, message = null, notice = null) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    /** Back from a code or reset step to the options (the challenge is dropped; what was typed stays). */
    fun backToOptions() = _state.update {
        it.copy(
            step = SignInStep.OPTIONS, code = "", challenge = null, challengeDead = false, message = null,
            attemptsLeft = MAX_ATTEMPTS, newPassword = "", notice = null,
        )
    }

    /** Fresh start (opening the screen again after a finished or abandoned attempt). */
    fun reset() = _state.update { SignInState(updateRequired = it.updateRequired) }

    suspend fun submitPhone() {
        val s = _state.value
        if (s.busy != null) return
        val e164 = when (val r = PhoneInput.normalize(s.phoneInput)) {
            is PhoneResult.Valid -> r.e164
            PhoneResult.Empty -> return fail(SignInMessage.PhoneEmpty)
            PhoneResult.NotEthiopianMobile -> return fail(SignInMessage.PhoneNotEthiopianMobile)
            PhoneResult.CarrierNotSupported -> return fail(SignInMessage.PhoneCarrierNotSupported)
            PhoneResult.OutsideEthiopia -> return fail(SignInMessage.PhoneRegionNotSupported)
        }
        sendCode(e164, Busy.PHONE)
    }

    /** "Send again"; allowed after the server's cooldown (or at once when the challenge is dead). */
    suspend fun resend() {
        val s = _state.value
        if (!s.canResend(clock())) return
        when (s.step) {
            SignInStep.EMAIL_CODE -> resendEmailCode()
            SignInStep.RESET_CODE -> requestReset(Busy.RESEND)
            else -> sendCode(s.phoneE164 ?: return, Busy.RESEND)
        }
    }

    private suspend fun sendCode(e164: String, busy: Busy) {
        _state.update { it.copy(busy = busy, message = null) }
        try {
            val c = repository.startOtp(e164)
            _state.update {
                it.copy(
                    busy = null, step = SignInStep.CODE, phoneE164 = e164, challenge = c, code = "",
                    attemptsLeft = MAX_ATTEMPTS, challengeDead = false, resendAtMs = cooldownEnd(c.resendAtMs),
                )
            }
        } catch (e: ApiError) {
            failWith(e)
        }
    }

    suspend fun submitCode() {
        val s = _state.value
        val c = s.challenge ?: return
        if (s.busy != null || s.challengeDead) return
        if (s.code.length != c.codeLength) return fail(SignInMessage.CodeIncomplete)
        _state.update { it.copy(busy = Busy.CODE, message = null) }
        try {
            val outcome = if (s.step == SignInStep.EMAIL_CODE) {
                repository.verifyEmail(c.challengeId, s.code)
            } else {
                repository.verifyOtp(c.challengeId, s.code, s.ageConfirmed)
            }
            done(outcome)
        } catch (e: ApiError) {
            val m = map(e)
            _state.update {
                when (m) {
                    SignInMessage.OtpInvalid -> it.copy(busy = null, message = m, code = "", attemptsLeft = (it.attemptsLeft - 1).coerceAtLeast(0))
                    SignInMessage.OtpExpired, SignInMessage.OtpTooManyAttempts ->
                        it.copy(busy = null, message = m, code = "", challengeDead = true, attemptsLeft = 0)
                    else -> it.copy(busy = null, message = m, updateRequired = it.updateRequired || e is ApiError.UpdateRequired)
                }
            }
        }
    }

    // --- e-mail and password (ADR 0033) -------------------------------------------------------

    /** "Sign in" or "Create account" on the e-mail form. */
    suspend fun submitEmail() {
        val s = _state.value
        if (s.busy != null) return
        checkEmail(s.email)?.let { return fail(it) }
        if (s.password.isEmpty()) return fail(SignInMessage.PasswordEmpty)
        val create = s.emailMode == EmailMode.CREATE
        if (create) {
            checkNewPassword(s.password)?.let { return fail(it) }
            if (!s.ageConfirmed) return fail(SignInMessage.AgeRequired)
        }
        _state.update { it.copy(busy = Busy.EMAIL, message = null, notice = null) }
        try {
            if (create) {
                when (val r = repository.registerWithEmail(s.email, s.password, s.ageConfirmed)) {
                    is EmailSignUp.SignedIn -> done(r.outcome)
                    is EmailSignUp.VerificationNeeded -> toEmailCode(r.challenge)
                }
            } else {
                done(repository.loginWithEmail(s.email, s.password))
            }
        } catch (e: ApiError) {
            val pending = e.emailVerificationChallenge()
            if (pending != null) toEmailCode(pending) else failWith(e)
        }
    }

    private fun toEmailCode(c: OtpChallengeInfo) = _state.update {
        it.copy(
            busy = null, step = SignInStep.EMAIL_CODE, challenge = c, code = "", attemptsLeft = MAX_ATTEMPTS,
            challengeDead = false, resendAtMs = cooldownEnd(c.resendAtMs), message = null,
        )
    }

    private suspend fun resendEmailCode() {
        val c = _state.value.challenge ?: return
        _state.update { it.copy(busy = Busy.RESEND, message = null) }
        try {
            toEmailCode(repository.resendEmailCode(c.challengeId))
        } catch (e: ApiError) {
            // an unknown, used or too old challenge cannot be resent: start again from the form
            if (map(e) == SignInMessage.OtpInvalid) {
                _state.update {
                    it.copy(busy = null, step = SignInStep.OPTIONS, challenge = null, code = "", message = SignInMessage.OtpExpired)
                }
            } else {
                failWith(e)
            }
        }
    }

    /** "Forgot password?": the reset form, with the address typed so far. */
    fun forgotPassword() = _state.update {
        it.copy(step = SignInStep.RESET_REQUEST, method = SignInMethod.EMAIL, message = null, notice = null, code = "", newPassword = "")
    }

    /** Sends the reset code (the server answers the same whether or not the address has an account). */
    suspend fun submitForgot() {
        val s = _state.value
        if (s.busy != null) return
        checkEmail(s.email)?.let { return fail(it) }
        requestReset(Busy.FORGOT)
    }

    private suspend fun requestReset(busy: Busy) {
        _state.update { it.copy(busy = busy, message = null) }
        try {
            repository.forgotPassword(_state.value.email)
            _state.update {
                it.copy(
                    busy = null, step = SignInStep.RESET_CODE, code = "", challenge = null, challengeDead = false,
                    notice = SignInNotice.RESET_CODE_SENT, resendAtMs = clock() + DEFAULT_COOLDOWN_MS,
                )
            }
        } catch (e: ApiError) {
            failWith(e)
        }
    }

    fun setResetCode(text: String) = _state.update {
        it.copy(code = text.filter { c -> c in '0'..'9' }.take(RESET_CODE_LENGTH), message = null)
    }

    suspend fun submitReset() {
        val s = _state.value
        if (s.busy != null) return
        if (s.code.length != RESET_CODE_LENGTH) return fail(SignInMessage.CodeIncomplete)
        checkNewPassword(s.newPassword)?.let { return fail(it) }
        _state.update { it.copy(busy = Busy.RESET, message = null, notice = null) }
        try {
            repository.resetPassword(s.email, s.code, s.newPassword)
            _state.update {
                it.copy(
                    busy = null, step = SignInStep.OPTIONS, method = SignInMethod.EMAIL, emailMode = EmailMode.SIGN_IN,
                    password = "", newPassword = "", code = "", passwordVisible = false, notice = SignInNotice.PASSWORD_CHANGED,
                )
            }
        } catch (e: ApiError) {
            val m = map(e)
            _state.update {
                it.copy(
                    busy = null, message = m, code = if (m == SignInMessage.ResetCodeInvalid) "" else it.code,
                    updateRequired = it.updateRequired || e is ApiError.UpdateRequired,
                )
            }
        }
    }

    // --- Google and guest ---------------------------------------------------------------------

    suspend fun signInWithGoogle(source: GoogleIdTokenSource) {
        val s = _state.value
        if (s.busy != null) return
        if (!source.isConfigured) return fail(SignInMessage.GoogleUnavailable)
        _state.update { it.copy(busy = Busy.GOOGLE, message = null) }
        val nonce = nonces()
        when (val r = source.getIdToken(nonce)) {
            GoogleResult.Cancelled -> _state.update { it.copy(busy = null) }
            GoogleResult.Unavailable -> failWith(SignInMessage.GoogleUnavailable)
            GoogleResult.Failed -> failWith(SignInMessage.GoogleFailed)
            is GoogleResult.Token -> try {
                done(repository.signInWithGoogle(r.idToken, nonce, r.displayName, s.ageConfirmed))
            } catch (e: ApiError) {
                failWith(e, google = true)
            }
        }
    }

    suspend fun continueAsGuest() {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = Busy.GUEST, message = null) }
        try {
            done(repository.continueAsGuest())
        } catch (e: AttestationException) {
            failWith(SignInMessage.GuestUnavailable)
        } catch (e: ApiError) {
            // Every attestation failure is 403 GUEST_NOT_ALLOWED (B2.3); the server never says why.
            if (e.status == 403) failWith(SignInMessage.GuestNotAllowed) else failWith(e)
        }
    }

    private fun done(outcome: SignInOutcome) = _state.update {
        it.copy(
            busy = null, step = SignInStep.DONE, outcome = outcome, code = "", message = null, notice = null,
            password = "", newPassword = "", passwordVisible = false,
        )
    }

    private fun fail(m: SignInMessage) = _state.update { it.copy(message = m) }

    private fun failWith(m: SignInMessage) = _state.update { it.copy(busy = null, message = m) }

    private fun failWith(e: ApiError, google: Boolean = false) =
        _state.update { it.copy(busy = null, message = map(e, google), updateRequired = it.updateRequired || e is ApiError.UpdateRequired) }

    private fun cooldownEnd(serverResendAtMs: Long?): Long {
        val now = clock()
        val delta = if (serverResendAtMs == null) DEFAULT_COOLDOWN_MS else (serverResendAtMs - now).coerceIn(0, MAX_COOLDOWN_MS)
        return now + delta
    }

    private fun map(e: ApiError, google: Boolean = false): SignInMessage {
        val fields = (e as? ApiError.Http)?.fieldErrors.orEmpty()
        val ageField = fields.any { it.field == "ageConfirmed" }
        val passwordCode = fields.firstOrNull { it.field == "password" || it.field == "newPassword" }?.code
        return when {
            e is ApiError.UpdateRequired -> SignInMessage.Unexpected // the update screen takes over (updateRequired flag)
            e.code == ProblemCodes.PHONE_REGION_NOT_SUPPORTED -> SignInMessage.PhoneRegionNotSupported
            e.code == ProblemCodes.PHONE_CARRIER_NOT_SUPPORTED -> SignInMessage.PhoneCarrierNotSupported
            e.code == ProblemCodes.OTP_INVALID -> SignInMessage.OtpInvalid
            e.code == ProblemCodes.OTP_EXPIRED -> SignInMessage.OtpExpired
            e.code == ProblemCodes.OTP_TOO_MANY_ATTEMPTS -> SignInMessage.OtpTooManyAttempts
            e.code == ProblemCodes.ACCOUNT_LOCKED -> SignInMessage.AccountLocked((e as? ApiError.RateLimited)?.retryAfterSeconds)
            e.code == ProblemCodes.INVALID_CREDENTIALS -> SignInMessage.InvalidCredentials
            e.code == ProblemCodes.EMAIL_ALREADY_REGISTERED -> SignInMessage.EmailAlreadyRegistered
            e.code == ProblemCodes.EMAIL_NOT_CONFIGURED -> SignInMessage.EmailUnavailable
            e.code == ProblemCodes.RESET_CODE_INVALID -> SignInMessage.ResetCodeInvalid
            e is ApiError.RateLimited -> SignInMessage.RateLimited(e.retryAfterSeconds)
            e.code == ProblemCodes.ACCOUNT_SUSPENDED -> SignInMessage.AccountSuspended
            e.status == 400 && ageField -> SignInMessage.AgeRequired
            e.status == 400 && passwordCode != null -> passwordMessage(passwordCode)
            e.status == 400 && fields.any { it.field == "email" } -> SignInMessage.EmailInvalid
            e.code == ProblemCodes.PROVIDER_UNAVAILABLE -> SignInMessage.ProviderUnavailable
            google && e.status == 401 -> SignInMessage.GoogleRejected
            e is ApiError.SignedOut -> SignInMessage.SessionEnded
            e is ApiError.Network -> SignInMessage.Offline
            e is ApiError.Timeout -> SignInMessage.Timeout
            else -> SignInMessage.Unexpected
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        const val MAX_CODE_LENGTH = 8
        const val RESET_CODE_LENGTH = 6
        const val DEFAULT_COOLDOWN_MS = 60_000L
        const val MAX_COOLDOWN_MS = 120_000L

        /** The server's password rules (ADR 0033): 10 to 128 characters (code points). */
        const val MIN_PASSWORD = 10
        const val MAX_PASSWORD = 128

        /** The field accepts a little more than allowed so the "too long" message can be shown. */
        private const val MAX_PASSWORD_CHARS = 2 * MAX_PASSWORD + 2
        const val MAX_EMAIL = 254
        private val EMAIL_SHAPE = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

        /** 256 random bits as 64 hex characters: the Google `nonce` claim (the server only compares it). */
        fun randomNonce(): String {
            val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return raw.joinToString("") { "%02x".format(it) }
        }

        /** Shape only (the server applies the full rules). */
        fun checkEmail(email: String): SignInMessage? = when {
            email.isBlank() -> SignInMessage.EmailEmpty
            !EMAIL_SHAPE.matches(email.trim()) -> SignInMessage.EmailInvalid
            else -> null
        }

        /** Length only; the common-password list is the server's. */
        fun checkNewPassword(password: String): SignInMessage? {
            val length = password.codePointCount(0, password.length)
            return when {
                password.isEmpty() -> SignInMessage.PasswordEmpty
                length < MIN_PASSWORD -> SignInMessage.PasswordTooShort
                length > MAX_PASSWORD -> SignInMessage.PasswordTooLong
                else -> null
            }
        }

        private fun passwordMessage(code: String): SignInMessage = when (code) {
            "PASSWORD_TOO_SHORT" -> SignInMessage.PasswordTooShort
            "PASSWORD_TOO_LONG" -> SignInMessage.PasswordTooLong
            "PASSWORD_MATCHES_EMAIL" -> SignInMessage.PasswordMatchesEmail
            else -> SignInMessage.PasswordTooCommon
        }
    }
}
