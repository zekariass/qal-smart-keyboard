package com.qalsmartkeyboard.app.auth

import com.qalsmartkeyboard.api.ApiError
import com.qalsmartkeyboard.api.ProblemCodes
import com.qalsmartkeyboard.api.SignedOutReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.security.SecureRandom

enum class SignInStep { OPTIONS, CODE, DONE }

/** What is running (the buttons are disabled meanwhile). */
enum class Busy { PHONE, CODE, RESEND, GOOGLE, GUEST }

/** One user-visible problem; the UI maps each to a string (no server text is shown). */
sealed interface SignInMessage {
    data object PhoneEmpty : SignInMessage
    data object PhoneInvalid : SignInMessage
    data object PhoneNotEthiopianMobile : SignInMessage
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
}

data class SignInState(
    val step: SignInStep = SignInStep.OPTIONS,
    val phoneInput: String = "",
    /** The normalised number the code was sent to (kept only in memory, never logged or saved). */
    val phoneE164: String? = null,
    val code: String = "",
    val ageConfirmed: Boolean = false,
    val challenge: OtpChallengeInfo? = null,
    /** Wrong codes the server still accepts for this challenge (5 per challenge, B2.1). */
    val attemptsLeft: Int = SignInModel.MAX_ATTEMPTS,
    /** The challenge cannot take more codes (expired or locked): only a new code helps. */
    val challengeDead: Boolean = false,
    /** Earliest time (epoch ms, device clock) "send again" is allowed. */
    val resendAtMs: Long = 0,
    val busy: Busy? = null,
    val message: SignInMessage? = null,
    val outcome: SignInOutcome? = null,
    /** A 426 was seen: the app is below the platform minimum. */
    val updateRequired: Boolean = false,
) {
    fun resendSecondsLeft(nowMs: Long): Long = ((resendAtMs - nowMs + 999) / 1000).coerceAtLeast(0)
    fun canResend(nowMs: Long): Boolean = busy == null && (challengeDead || resendSecondsLeft(nowMs) == 0L)
}

/**
 * State machine of the sign-in screens: phone entry -> code entry -> done, plus Google and guest.
 * Android-free (the Google chooser is a [GoogleIdTokenSource] argument), so the flows are tested on
 * the JVM against `MockWebServer`. Operations are suspend functions; the view model launches them.
 * Nothing sensitive (numbers, codes, tokens) is logged.
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

    fun dismissMessage() = _state.update { it.copy(message = null) }

    /** Back from the code step to the options (the challenge is dropped; the number stays typed). */
    fun backToOptions() = _state.update {
        it.copy(step = SignInStep.OPTIONS, code = "", challenge = null, challengeDead = false, message = null, attemptsLeft = MAX_ATTEMPTS)
    }

    /** Fresh start (opening the screen again after a finished or abandoned attempt). */
    fun reset() = _state.update { SignInState(updateRequired = it.updateRequired) }

    suspend fun submitPhone() {
        val s = _state.value
        if (s.busy != null) return
        val e164 = when (val r = PhoneInput.normalize(s.phoneInput)) {
            is PhoneResult.Valid -> r.e164
            PhoneResult.Empty -> return fail(SignInMessage.PhoneEmpty)
            PhoneResult.Invalid -> return fail(SignInMessage.PhoneInvalid)
            PhoneResult.NotEthiopianMobile -> return fail(SignInMessage.PhoneNotEthiopianMobile)
        }
        sendCode(e164, Busy.PHONE)
    }

    /** "Send again"; allowed after the server's cooldown (or at once when the challenge is dead). */
    suspend fun resend() {
        val s = _state.value
        val e164 = s.phoneE164 ?: return
        if (!s.canResend(clock())) return
        sendCode(e164, Busy.RESEND)
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
            done(repository.verifyOtp(c.challengeId, s.code, s.ageConfirmed))
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

    private fun done(outcome: SignInOutcome) =
        _state.update { it.copy(busy = null, step = SignInStep.DONE, outcome = outcome, code = "", message = null) }

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
        val field = (e as? ApiError.Http)?.fieldErrors?.any { it.field == "ageConfirmed" } == true
        return when {
            e is ApiError.UpdateRequired -> SignInMessage.Unexpected // the update screen takes over (updateRequired flag)
            e.code == ProblemCodes.OTP_INVALID -> SignInMessage.OtpInvalid
            e.code == ProblemCodes.OTP_EXPIRED -> SignInMessage.OtpExpired
            e.code == ProblemCodes.OTP_TOO_MANY_ATTEMPTS -> SignInMessage.OtpTooManyAttempts
            e is ApiError.RateLimited -> SignInMessage.RateLimited(e.retryAfterSeconds)
            e.code == ProblemCodes.ACCOUNT_SUSPENDED -> SignInMessage.AccountSuspended
            e.status == 400 && field -> SignInMessage.AgeRequired
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
        const val DEFAULT_COOLDOWN_MS = 60_000L
        const val MAX_COOLDOWN_MS = 120_000L

        /** 256 random bits as 64 hex characters: the Google `nonce` claim (the server only compares it). */
        fun randomNonce(): String {
            val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return raw.joinToString("") { "%02x".format(it) }
        }
    }
}
