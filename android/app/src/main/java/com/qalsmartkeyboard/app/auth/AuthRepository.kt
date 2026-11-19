package com.qalsmartkeyboard.app.auth

import com.qalsmartkeyboard.api.ApiClients
import com.qalsmartkeyboard.api.ApiError
import com.qalsmartkeyboard.api.SessionState
import com.qalsmartkeyboard.api.SignedOutReason
import com.qalsmartkeyboard.api.apiCall
import com.qalsmartkeyboard.api.generated.apis.AuthApi
import com.qalsmartkeyboard.api.generated.models.AttestationEvidence
import com.qalsmartkeyboard.api.generated.models.AuthSession
import com.qalsmartkeyboard.api.generated.models.ClientPlatformEnum
import com.qalsmartkeyboard.api.generated.models.DeviceRegistration
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
 * **Bearer policy of sign-in calls (ADR 0034):** `otp/start`, `otp/verify` and `oauth/GOOGLE` carry a bearer only when
 * the stored session is a guest's, so the server links the guest (B2.6). Any other stored session (a registered
 * account, or one whose kind is unknown) is never presented: the call goes out without it ([ApiClients.anonymousAuth])
 * and a successful sign-in replaces it locally. If the guest bearer is refused and the session ends
 * ([ApiError.SignedOut]: `GUEST_SESSION_INVALID`, or a token the server cannot verify whose refresh failed; the
 * authenticator has cleared the tokens), the same call is repeated exactly once without a bearer: the guest's data is
 * unreachable for this device anyway, and the user still gets signed in.
 */
class AuthRepository(
    private val clients: ApiClients,
    private val kinds: AccountKindStore,
    private val installationId: () -> UUID,
    private val attestation: AttestationProvider,
    private val device: DeviceInfo,
    private val labels: AccountLabelStore? = null,
) {
    private val session get() = clients.session
    private val auth: AuthApi get() = clients.auth
    private val kind = MutableStateFlow(kinds.load())
    private val label = MutableStateFlow(labels?.load())

    /**
     * Who is signed in, for "Signed in as ...": the masked phone number or the masked e-mail address of the last
     * sign-in, or null (unknown, a guest, or signed out). Only meaningful while [status] is [AccountStatus.Registered].
     */
    val accountLabel: StateFlow<String?> = label.asStateFlow()

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
        val c = signInCall { it.startPhoneOtp(request) }
        return OtpChallengeInfo(c.challengeId, c.codeLength, c.expiresAt.toInstant().toEpochMilli(), c.resendAvailableAt?.toInstant()?.toEpochMilli())
    }

    suspend fun verifyOtp(challengeId: UUID, code: String, ageConfirmed: Boolean): SignInOutcome {
        val request = OtpVerifyRequest(
            challengeId = challengeId,
            code = code,
            ageConfirmed = ageConfirmed.takeIf { it },
            device = deviceRegistration(),
        )
        return accept(signInCall { it.verifyPhoneOtp(request) })
    }

    suspend fun signInWithGoogle(idToken: String, nonce: String, displayName: String?, ageConfirmed: Boolean): SignInOutcome {
        val request = OAuthExchangeRequest(
            idToken = idToken,
            nonce = nonce,
            displayName = displayName?.take(120),
            ageConfirmed = ageConfirmed.takeIf { it },
            device = deviceRegistration(),
        )
        return accept(signInCall { it.exchangeOAuthIdToken(AuthApi.ProviderExchangeOAuthIdToken.GOOGLE, request) })
    }

    /** Attested guest session: server nonce, then the Integrity (or mock) token over it, then `/auth/guest` (no bearer). */
    suspend fun continueAsGuest(): SignInOutcome {
        val nonce = apiCall { clients.anonymousAuth.createAttestationNonce() }.nonce
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
        return accept(apiCall { clients.anonymousAuth.createGuestSession(request) })
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
        label.value = null
        labels?.clear()
    }

    /** True when the stored session is a guest's that a sign-in should link (B2.6). */
    private fun linksGuest(): Boolean = kind.value == AccountKind.GUEST && session.tokens() != null

    /** One sign-in call under the bearer policy (class doc): at most one retry, and that one never carries a bearer. */
    private suspend fun <T> signInCall(call: (AuthApi) -> T): T {
        if (!linksGuest()) return apiCall { call(clients.anonymousAuth) }
        return try {
            apiCall { call(auth) }
        } catch (e: ApiError.SignedOut) {
            // The authenticator ended the dead guest session; make sure nothing of it is presented again.
            if (session.tokens() != null) session.signedOut(e.reason)
            forgetKind()
            apiCall { call(clients.anonymousAuth) }
        }
    }

    private fun accept(s: AuthSession): SignInOutcome {
        val k = if (s.user.accountType == UserProfile.AccountType.GUEST) AccountKind.GUEST else AccountKind.REGISTERED
        kinds.save(k)
        kind.value = k
        val who = if (k == AccountKind.REGISTERED) AccountLabels.of(s.user) else null
        if (who != null) labels?.save(who) else labels?.clear()
        label.value = who
        session.signedIn(s)
        val merged = s.mergedGuestUserId
        val link = when {
            merged == null -> GuestLink.NONE
            merged == s.user.id -> GuestLink.UPGRADED_IN_PLACE
            else -> GuestLink.MERGED
        }
        return SignInOutcome(k, s.isNewUser == true, link)
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

/** "Signed in as ..." without showing a full identifier: the server's masked phone number, else a masked e-mail. */
object AccountLabels {
    fun of(user: UserProfile): String? = user.phoneNumberMasked?.takeIf { it.isNotBlank() } ?: user.email?.let(::maskEmail)

    /** Keeps the first character and the domain (`zemen@example.com` gives `z` + three bullets + `@example.com`); no usable `@` gives null. */
    fun maskEmail(email: String): String? {
        val at = email.lastIndexOf('@')
        if (at < 1 || at == email.lastIndex) return null
        return email.first() + "\u2022\u2022\u2022" + email.substring(at)
    }
}

/** The masked account label next to the tokens (encrypted, excluded from backups like them). */
class AccountLabelStore(private val files: EncryptedFileStore) {
    fun load(): String? = (files.read(NAME) as? StoreRead.Ok)?.let { String(it.bytes, Charsets.UTF_8) }?.takeIf { it.isNotBlank() }

    fun save(label: String) {
        try {
            files.write(NAME, label.toByteArray(Charsets.UTF_8))
        } catch (_: IOException) {
        } catch (_: GeneralSecurityException) {
        }
    }

    fun clear() = files.delete(NAME)

    private companion object {
        const val NAME = "account-label"
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
