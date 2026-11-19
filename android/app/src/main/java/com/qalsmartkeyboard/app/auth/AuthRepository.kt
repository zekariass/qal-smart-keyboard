package com.qalsmartkeyboard.app.auth

import com.qalsmartkeyboard.api.ApiClients
import com.qalsmartkeyboard.api.ApiError
import com.qalsmartkeyboard.api.ApiErrors
import com.qalsmartkeyboard.api.ProblemCodes
import com.qalsmartkeyboard.api.SessionState
import com.qalsmartkeyboard.api.SignedOutReason
import com.qalsmartkeyboard.api.apiCall
import com.qalsmartkeyboard.api.generated.apis.AuthApi
import com.qalsmartkeyboard.api.generated.models.AttestationEvidence
import com.qalsmartkeyboard.api.generated.models.AuthSession
import com.qalsmartkeyboard.api.generated.models.ClientPlatformEnum
import com.qalsmartkeyboard.api.generated.models.DeviceRegistration
import com.qalsmartkeyboard.api.generated.models.EmailLoginRequest
import com.qalsmartkeyboard.api.generated.models.EmailRegisterRequest
import com.qalsmartkeyboard.api.generated.models.EmailResendRequest
import com.qalsmartkeyboard.api.generated.models.EmailVerificationChallenge
import com.qalsmartkeyboard.api.generated.models.EmailVerifyRequest
import com.qalsmartkeyboard.api.generated.models.PasswordForgotRequest
import com.qalsmartkeyboard.api.generated.models.PasswordResetRequest
import com.qalsmartkeyboard.api.generated.infrastructure.Serializer
import com.qalsmartkeyboard.api.generated.models.GuestSessionRequest
import com.qalsmartkeyboard.api.generated.models.LogoutRequest
import com.qalsmartkeyboard.api.generated.models.OAuthExchangeRequest
import com.qalsmartkeyboard.api.generated.models.OtpStartRequest
import com.qalsmartkeyboard.api.generated.models.OtpVerifyRequest
import com.qalsmartkeyboard.api.generated.models.UserProfile
import com.qalsmartkeyboard.api.signedIn
import com.qalsmartkeyboard.data.secure.EncryptedFileStore
import com.qalsmartkeyboard.data.secure.StoreRead
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.UUID

enum class AccountKind { GUEST, REGISTERED }

/** What the UI shows as "who am I": derived from the session state and the stored kind. */
sealed interface AccountStatus {
    data class SignedOut(val reason: SignedOutReason?) : AccountStatus
    data object Guest : AccountStatus
    data object Registered : AccountStatus
}

/** What happened to a guest's data when a sign-in carried the guest bearer (B2.6). */
enum class GuestLink {
    /** No guest was linked (no guest session, or it could not link and was discarded). */
    NONE,

    /** New identity: the guest account itself became the registered account (same user id). */
    UPGRADED_IN_PLACE,

    /** Existing account: the guest's data was merged into it. */
    MERGED,
}

class SignInOutcome(val kind: AccountKind, val isNewUser: Boolean, val guestLink: GuestLink)

class OtpChallengeInfo(val challengeId: UUID, val codeLength: Int, val expiresAtMs: Long, val resendAtMs: Long?)

/** Result of an e-mail sign-up (ADR 0033): signed in at once, or a code was e-mailed first. */
sealed interface EmailSignUp {
    class SignedIn(val outcome: SignInOutcome) : EmailSignUp
    class VerificationNeeded(val challenge: OtpChallengeInfo) : EmailSignUp
}

/**
 * The verification challenge a 403 `EMAIL_NOT_VERIFIED` carries (`challengeId`, `expiresAt`,
 * `resendAvailableAt`, `codeLength`), or null for any other error.
 */
fun ApiError.emailVerificationChallenge(): OtpChallengeInfo? {
    val http = this as? ApiError.Http ?: return null
    if (http.code != ProblemCodes.EMAIL_NOT_VERIFIED) return null
    fun str(name: String) = (http.extensions[name] as? JsonPrimitive)?.contentOrNull
    val id = str("challengeId")?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
    val length = (http.extensions["codeLength"] as? JsonPrimitive)?.intOrNull ?: 6
    fun ms(name: String) = str(name)?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
    return OtpChallengeInfo(id, length, ms("expiresAt") ?: 0, ms("resendAvailableAt"))
}

/** Device facts sent with a sign-in (`device`); no hardware identifiers. */
class DeviceInfo(val appVersion: String, val osVersion: String)

/** Raised by [AuthRepository.continueAsGuest] when no attestation token could be produced. */
class AttestationException(val unavailable: Boolean) : RuntimeException(if (unavailable) "attestation unavailable" else "attestation failed")

/**
 * Sign-in operations of the companion app over the typed client (A2.1). Failures are
 * [ApiError]s (see `ApiError`), plus [AttestationException] for guests. No operation logs or
 * stores anything but the token pair (in the Keystore-backed [EncryptedTokenStore] behind the session) and
 * the account kind.
 *
 * **Guest linking (B2.6):** when a guest session exists, the bearer interceptor already sends
 * its token on `otp/start`, `otp/verify`, `oauth/GOOGLE` and the e-mail register, login and verify
 * calls, so the server links the guest. If the
 * server says the guest can no longer link (401 `GUEST_SESSION_INVALID`; the authenticator then
 * clears the tokens), the same call is repeated once without it, as the contract prescribes.
 */
class AuthRepository(
    private val clients: ApiClients,
    private val kinds: AccountKindStore,
    private val installationId: () -> UUID,
    private val attestation: AttestationProvider,
    private val device: DeviceInfo,
) {
    private val session get() = clients.session
    private val auth: AuthApi get() = clients.auth
    private val kind = MutableStateFlow(kinds.load())

    val guestSupported: Boolean get() = attestation.isSupported

    fun status(): Flow<AccountStatus> = combine(session.state, kind) { s, k -> toStatus(s, k) }

    fun currentStatus(): AccountStatus = toStatus(session.state.value, kind.value)

    private fun toStatus(s: SessionState, k: AccountKind?): AccountStatus = when (s) {
        is SessionState.SignedOut -> AccountStatus.SignedOut(s.reason)
        SessionState.SignedIn -> if (k == AccountKind.GUEST) AccountStatus.Guest else AccountStatus.Registered
    }

    suspend fun startOtp(phoneE164: String): OtpChallengeInfo {
        val request = OtpStartRequest(
            phoneNumber = phoneE164,
            channel = OtpStartRequest.Channel.SMS,
            installationId = installationId(),
        )
        val c = linking { apiCall { auth.startPhoneOtp(request) } }
        return OtpChallengeInfo(c.challengeId, c.codeLength, c.expiresAt.toInstant().toEpochMilli(), c.resendAvailableAt?.toInstant()?.toEpochMilli())
    }

    suspend fun verifyOtp(challengeId: UUID, code: String, ageConfirmed: Boolean): SignInOutcome {
        val request = OtpVerifyRequest(
            challengeId = challengeId,
            code = code,
            ageConfirmed = ageConfirmed.takeIf { it },
            device = deviceRegistration(),
        )
        return accept(linking { apiCall { auth.verifyPhoneOtp(request) } })
    }

    suspend fun signInWithGoogle(idToken: String, nonce: String, displayName: String?, ageConfirmed: Boolean): SignInOutcome {
        val request = OAuthExchangeRequest(
            idToken = idToken,
            nonce = nonce,
            displayName = displayName?.take(120),
            ageConfirmed = ageConfirmed.takeIf { it },
            device = deviceRegistration(),
        )
        return accept(linking { apiCall { auth.exchangeOAuthIdToken(AuthApi.ProviderExchangeOAuthIdToken.GOOGLE, request) } })
    }

    /**
     * E-mail sign-up (ADR 0033). The server answers 201 with a session when it does not require
     * verification, or 202 with a challenge (a code was e-mailed; no account exists yet). The
     * generated client types one success body per operation, so this call is made by hand over the
     * same OkHttp client (bearer for guest linking, client headers, error mapping). Neither the
     * address nor the password is logged.
     */
    suspend fun registerWithEmail(email: String, password: String, ageConfirmed: Boolean, displayName: String? = null): EmailSignUp {
        val request = EmailRegisterRequest(
            email = email.trim(),
            password = password,
            ageConfirmed = ageConfirmed,
            displayName = displayName?.trim()?.take(120)?.takeIf { it.isNotEmpty() },
            device = deviceRegistration(),
        )
        return linking {
            apiCall {
                val json = Serializer.kotlinxSerializationJson
                val http = Request.Builder()
                    .url(clients.config.baseUrl.trimEnd('/') + "/api/v1/auth/email/register")
                    .post(json.encodeToString(EmailRegisterRequest.serializer(), request).toRequestBody(JSON))
                    .header("Accept", "application/json")
                    .build()
                clients.okHttpClient.newCall(http).execute().use { r ->
                    val text = r.body?.string()
                    if (!r.isSuccessful) throw ApiErrors.fromHttp(r.code, text, r.headers.toMultimap())
                    try {
                        when (r.code) {
                            201 -> RegisterResponse.Session(json.decodeFromString(AuthSession.serializer(), text.orEmpty()))
                            202 -> RegisterResponse.Challenge(json.decodeFromString(EmailVerificationChallenge.serializer(), text.orEmpty()))
                            else -> throw ApiError.Unexpected(r.code)
                        }
                    } catch (e: SerializationException) {
                        throw ApiError.Unexpected(r.code, e)
                    } catch (e: IllegalArgumentException) {
                        throw ApiError.Unexpected(r.code, e)
                    }
                }
            }
        }.let {
            when (it) {
                is RegisterResponse.Session -> EmailSignUp.SignedIn(accept(it.session))
                is RegisterResponse.Challenge -> EmailSignUp.VerificationNeeded(it.challenge.toInfo())
            }
        }
    }

    /** E-mail sign-in. A 403 `EMAIL_NOT_VERIFIED` carries the challenge ([emailVerificationChallenge]). */
    suspend fun loginWithEmail(email: String, password: String): SignInOutcome {
        val request = EmailLoginRequest(email = email.trim(), password = password, device = deviceRegistration())
        return accept(linking { apiCall { auth.loginWithEmail(request) } })
    }

    /** Proves the mailbox with the e-mailed code; creates the account (sign-up) or verifies it, and signs in. */
    suspend fun verifyEmail(challengeId: UUID, code: String): SignInOutcome {
        val request = EmailVerifyRequest(challengeId = challengeId, code = code, device = deviceRegistration())
        return accept(linking { apiCall { auth.verifyEmailCode(request) } })
    }

    suspend fun resendEmailCode(challengeId: UUID): OtpChallengeInfo =
        apiCall { auth.resendEmailCode(EmailResendRequest(challengeId = challengeId)) }.toInfo()

    /** Always succeeds for a well-formed address (the server never says whether an account exists). */
    suspend fun forgotPassword(email: String) {
        apiCall { auth.forgotPassword(PasswordForgotRequest(email = email.trim())) }
    }

    /** Sets a new password with the e-mailed code; the server ends every session of the account. */
    suspend fun resetPassword(email: String, code: String, newPassword: String) {
        apiCall { auth.resetPassword(PasswordResetRequest(email = email.trim(), token = code, newPassword = newPassword)) }
    }

    private fun EmailVerificationChallenge.toInfo() = OtpChallengeInfo(
        challengeId, codeLength, expiresAt.toInstant().toEpochMilli(), resendAvailableAt?.toInstant()?.toEpochMilli(),
    )

    private sealed interface RegisterResponse {
        class Session(val session: AuthSession) : RegisterResponse
        class Challenge(val challenge: EmailVerificationChallenge) : RegisterResponse
    }

    /** Attested guest session: server nonce, then the Integrity (or mock) token over it, then `/auth/guest`. */
    suspend fun continueAsGuest(): SignInOutcome {
        val nonce = apiCall { auth.createAttestationNonce() }.nonce
        val token = when (val r = attestation.attest(nonce)) {
            is AttestationResult.Token -> r.token
            AttestationResult.Unavailable -> throw AttestationException(unavailable = true)
            AttestationResult.Failed -> throw AttestationException(unavailable = false)
        }
        val request = GuestSessionRequest(
            installationId = installationId(),
            platform = ClientPlatformEnum.ANDROID,
            attestation = AttestationEvidence(provider = AttestationEvidence.Provider.PLAY_INTEGRITY, token = token, nonce = nonce),
        )
        return accept(apiCall { auth.createGuestSession(request) })
    }

    /**
     * Revokes the session on the server (best effort: offline or an already ended session must not
     * keep the user signed in locally) and wipes the tokens and the account kind.
     */
    suspend fun signOut() {
        if (session.state.value == SessionState.SignedIn) {
            try {
                apiCall { auth.logout(LogoutRequest(allDevices = false)) }
            } catch (_: ApiError) {
                // local sign-out proceeds; the refresh token expires on its own if the call never got through
            }
        }
        session.signedOut(SignedOutReason.SIGNED_OUT)
        forgetKind()
    }

    private fun forgetKind() {
        kind.value = null
        kinds.clear()
    }

    private suspend fun <T> linking(block: suspend () -> T): T = try {
        block()
    } catch (e: ApiError.SignedOut) {
        if (e.reason != SignedOutReason.GUEST_SESSION_INVALID) throw e
        forgetKind() // the stale guest token is gone (authenticator); retry as a plain sign-in
        block()
    }

    private fun accept(s: AuthSession): SignInOutcome {
        val k = if (s.user.accountType == UserProfile.AccountType.GUEST) AccountKind.GUEST else AccountKind.REGISTERED
        kinds.save(k)
        kind.value = k
        session.signedIn(s)
        val merged = s.mergedGuestUserId
        val link = when {
            merged == null -> GuestLink.NONE
            merged == s.user.id -> GuestLink.UPGRADED_IN_PLACE
            else -> GuestLink.MERGED
        }
        return SignInOutcome(k, s.isNewUser == true, link)
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }

    private fun deviceRegistration() = DeviceRegistration(
        installationId = installationId(),
        platform = ClientPlatformEnum.ANDROID,
        appVersion = device.appVersion.take(40),
        osVersion = device.osVersion.take(80),
    )
}

/** The account kind survives a restart next to the tokens (the session itself does not say it). */
class AccountKindStore(private val files: EncryptedFileStore) {
    fun load(): AccountKind? = (files.read(NAME) as? StoreRead.Ok)
        ?.let { String(it.bytes, Charsets.UTF_8) }
        ?.let { v -> AccountKind.entries.firstOrNull { it.name == v } }

    fun save(kind: AccountKind) {
        try {
            files.write(NAME, kind.name.toByteArray(Charsets.UTF_8))
        } catch (_: IOException) {
        } catch (_: GeneralSecurityException) {
        }
    }

    fun clear() = files.delete(NAME)

    private companion object {
        const val NAME = "account-kind"
    }
}

/** A random per-install id (not a hardware identifier), created once and kept with the tokens. */
class InstallationIdStore(private val files: EncryptedFileStore) {
    private var cached: UUID? = null

    @Synchronized
    fun get(): UUID {
        cached?.let { return it }
        val stored = (files.read(NAME) as? StoreRead.Ok)?.let { runCatching { UUID.fromString(String(it.bytes, Charsets.UTF_8)) }.getOrNull() }
        val id = stored ?: UUID.randomUUID().also {
            try {
                files.write(NAME, it.toString().toByteArray(Charsets.UTF_8))
            } catch (_: IOException) {
            } catch (_: GeneralSecurityException) {
            }
        }
        cached = id
        return id
    }

    private companion object {
        const val NAME = "install-id"
    }
}
