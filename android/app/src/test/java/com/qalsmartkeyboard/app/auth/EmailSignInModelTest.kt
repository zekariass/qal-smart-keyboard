package com.qalsmartkeyboard.app.auth

import com.qalsmartkeyboard.api.ApiClients
import com.qalsmartkeyboard.api.ClientInfo
import com.qalsmartkeyboard.api.ClientSurface
import com.qalsmartkeyboard.api.SessionManager
import com.qalsmartkeyboard.api.TokenPair
import com.qalsmartkeyboard.api.TransportConfig
import com.qalsmartkeyboard.data.secure.AesGcmCipher
import com.qalsmartkeyboard.data.secure.EncryptedFileStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.KeyGenerator

/**
 * E-mail and password sign-in (ADR 0033) through the real `:api` client and `MockWebServer`:
 * sign-up with and without server-side verification, sign-in, an unverified account, errors
 * (wrong password, lockout, weak password, taken address), resend, guest linking, and
 * "forgot password". Tokens land in the same Keystore-backed store as the phone flow.
 */
class EmailSignInModelTest {
    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private lateinit var files: EncryptedFileStore
    private lateinit var session: SessionManager
    private lateinit var repo: AuthRepository
    private lateinit var model: SignInModel
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var now = 1_000_000L
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val installId = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private var routes: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404) }

    private val challengeId = "0b7e8a56-1a79-4cfb-9a8e-7b8a1f4d2c10"
    private val userId = "7a0f0f64-1f1e-4c64-9d0b-0a0a0a0a0a0a"
    private val guestId = "9c9c9c9c-1f1e-4c64-9d0b-0a0a0a0a0a0a"
    private val password = "bunna and injera"

    @Before fun setUp() {
        dir = Files.createTempDirectory("qal-email").toFile()
        files = EncryptedFileStore(dir, AesGcmCipher { key })
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return routes(request)
            }
        }
        server.start()
        session = SessionManager(EncryptedTokenStore(files))
        val clients = ApiClients(
            TransportConfig(baseUrl = "http://localhost:${server.port}", client = ClientInfo("1.0", ClientSurface.COMPANION), readTimeoutMs = 5_000),
            session,
        )
        repo = AuthRepository(clients, AccountKindStore(files), { installId }, MockAttestationProvider, DeviceInfo("1.0", "Android 14 (API 34)"))
        model = SignInModel(repo, clock = { now })
    }

    @After fun tearDown() {
        runCatching { server.shutdown() }
        dir.deleteRecursively()
    }

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun problem(status: Int, code: String, extra: String = "", headers: Map<String, String> = emptyMap()) =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/problem+json").apply {
            headers.forEach { (k, v) -> setHeader(k, v) }
        }.setBody("""{"type":"about:blank","title":"t","status":$status,"code":"$code"$extra}""")

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    private fun challenge(id: String = challengeId) = json(
        202,
        """{"challengeId":"$id","expiresAt":"${iso(now + 900_000)}","resendAvailableAt":"${iso(now + 60_000)}","codeLength":6}""",
    )

    private fun session(code: Int = 200, access: String = "acc-1", id: String = userId, isNew: Boolean? = null, merged: String? = null, verified: Boolean = true) = json(
        code,
        """{"tokenType":"Bearer","accessToken":"$access","accessTokenExpiresAt":"${iso(now + 600_000)}",""" +
            """"refreshToken":"ref-1","refreshTokenExpiresAt":"${iso(now + 86_400_000)}",""" +
            (isNew?.let { """"isNewUser":$it,""" } ?: "") + (merged?.let { """"mergedGuestUserId":"$it",""" } ?: "") +
            """"user":{"id":"$id","accountType":"REGISTERED","accountStatus":"ACTIVE","email":"selam@example.com","emailVerified":$verified}}""",
    )

    private fun last(path: String) = requests.last { it.path == path }
    private fun body(r: RecordedRequest) = r.body.clone().readUtf8()
    private fun run(block: suspend () -> Unit) = runBlocking { block() }
    private fun st() = model.state.value
    private fun status() = runBlocking { repo.status().first() }

    private fun fillCreate(email: String = "Selam@example.com", pw: String = password, age: Boolean = true) {
        model.setMethod(SignInMethod.EMAIL)
        model.setEmailMode(EmailMode.CREATE)
        model.setEmail(email)
        model.setPassword(pw)
        model.setAgeConfirmed(age)
    }

    private fun fillSignIn(email: String = "selam@example.com", pw: String = password) {
        model.setMethod(SignInMethod.EMAIL)
        model.setEmail(email)
        model.setPassword(pw)
    }

    // --- sign-up ----------------------------------------------------------------------------

    @Test fun signUpWithoutVerificationSignsInAtOnce() = run {
        routes = { session(201, isNew = true, verified = false) }
        fillCreate()
        model.submitEmail()
        val sent = body(last("/api/v1/auth/email/register"))
        assertTrue(sent, sent.contains(""""email":"Selam@example.com""""))
        assertTrue(sent, sent.contains(""""ageConfirmed":true"""))
        assertTrue(sent, sent.contains(""""platform":"ANDROID""""))
        assertEquals(SignInStep.DONE, st().step)
        assertTrue(st().outcome!!.isNewUser)
        assertEquals("", st().password) // never kept after sign-in
        assertEquals(AccountStatus.Registered, status())
        assertEquals(TokenPair("acc-1", "ref-1"), session.tokens())
        assertFalse(st().toString().contains(password))
    }

    @Test fun signUpWithVerificationGoesThroughTheEmailedCode() = run {
        routes = { r ->
            when (r.path) {
                "/api/v1/auth/email/register" -> challenge()
                "/api/v1/auth/email/verify" -> session(isNew = true)
                else -> MockResponse().setResponseCode(404)
            }
        }
        fillCreate()
        model.submitEmail()
        assertEquals(SignInStep.EMAIL_CODE, st().step)
        assertEquals(UUID.fromString(challengeId), st().challenge!!.challengeId)
        assertEquals(60L, st().resendSecondsLeft(now))
        assertNull(session.tokens()) // nothing until the mailbox is proven
        model.setCode("482913")
        model.submitCode()
        val verify = body(last("/api/v1/auth/email/verify"))
        assertTrue(verify, verify.contains(""""challengeId":"$challengeId""""))
        assertTrue(verify, verify.contains(""""code":"482913""""))
        assertEquals(SignInStep.DONE, st().step)
        assertEquals(AccountStatus.Registered, status())
    }

    @Test fun wrongVerificationCodesCountDownAndResendStartsAgain() = run {
        routes = { r ->
            when (r.path) {
                "/api/v1/auth/email/register" -> challenge()
                "/api/v1/auth/email/verify" -> problem(401, "OTP_INVALID", ""","attemptsRemaining":4""")
                "/api/v1/auth/email/verify/resend" -> challenge()
                else -> MockResponse().setResponseCode(404)
            }
        }
        fillCreate()
        model.submitEmail()
        model.setCode("000000")
        model.submitCode()
        assertEquals(SignInMessage.OtpInvalid, st().message)
        assertEquals(4, st().attemptsLeft)
        model.resend() // cooldown: nothing sent
        assertTrue(requests.none { it.path == "/api/v1/auth/email/verify/resend" })
        now += 60_000
        model.resend()
        assertTrue(body(last("/api/v1/auth/email/verify/resend")).contains(challengeId))
        assertEquals(SignInModel.MAX_ATTEMPTS, st().attemptsLeft)
        assertEquals(SignInStep.EMAIL_CODE, st().step)
    }

    @Test fun createFormIsCheckedOnTheDeviceFirst() = run {
        fillCreate(pw = "short", age = true)
        model.submitEmail()
        assertEquals(SignInMessage.PasswordTooShort, st().message)
        fillCreate(age = false)
        model.submitEmail()
        assertEquals(SignInMessage.AgeRequired, st().message)
        fillCreate(email = "not-an-address")
        model.submitEmail()
        assertEquals(SignInMessage.EmailInvalid, st().message)
        assertTrue(requests.isEmpty())
    }

    @Test fun serverPasswordAndAddressRefusalsAreExplained() = run {
        routes = {
            problem(400, "VALIDATION_FAILED", ""","errors":[{"field":"password","code":"PASSWORD_TOO_COMMON","message":"m"}]""")
        }
        fillCreate(pw = "password1234")
        model.submitEmail()
        assertEquals(SignInMessage.PasswordTooCommon, st().message)
        routes = { problem(409, "EMAIL_ALREADY_REGISTERED") }
        fillCreate()
        model.submitEmail()
        assertEquals(SignInMessage.EmailAlreadyRegistered, st().message)
        assertEquals(SignInStep.OPTIONS, st().step)
        assertNull(st().busy)
    }

    // --- sign-in ----------------------------------------------------------------------------

    @Test fun signInStoresTheTokensLikeThePhoneFlow() = run {
        routes = { session(isNew = false) }
        fillSignIn()
        model.submitEmail()
        val sent = body(last("/api/v1/auth/email/login"))
        assertTrue(sent, sent.contains(""""email":"selam@example.com""""))
        assertTrue(sent, sent.contains(""""password":"$password""""))
        assertEquals(SignInStep.DONE, st().step)
        assertFalse(st().outcome!!.isNewUser)
        assertEquals(TokenPair("acc-1", "ref-1"), session.tokens())
    }

    @Test fun wrongPasswordAndLockoutHaveTheirOwnMessages() = run {
        routes = { problem(401, "INVALID_CREDENTIALS") }
        fillSignIn()
        model.submitEmail()
        assertEquals(SignInMessage.InvalidCredentials, st().message)
        assertEquals(AccountStatus.SignedOut(null), status())
        routes = { problem(429, "ACCOUNT_LOCKED", headers = mapOf("Retry-After" to "840")) }
        model.submitEmail()
        assertEquals(SignInMessage.AccountLocked(840), st().message)
        routes = { problem(503, "EMAIL_NOT_CONFIGURED") }
        model.submitEmail()
        assertEquals(SignInMessage.EmailUnavailable, st().message)
    }

    @Test fun anUnverifiedAccountIsSentToTheCodeStep() = run {
        routes = { r ->
            when (r.path) {
                "/api/v1/auth/email/login" -> problem(
                    403, "EMAIL_NOT_VERIFIED",
                    ""","challengeId":"$challengeId","expiresAt":"${iso(now + 900_000)}","resendAvailableAt":"${iso(now + 30_000)}","codeLength":6""",
                )
                "/api/v1/auth/email/verify" -> session(isNew = false)
                else -> MockResponse().setResponseCode(404)
            }
        }
        fillSignIn()
        model.submitEmail()
        assertEquals(SignInStep.EMAIL_CODE, st().step)
        assertEquals(30L, st().resendSecondsLeft(now))
        assertNull(st().message)
        model.setCode("123456")
        model.submitCode()
        assertEquals(SignInStep.DONE, st().step)
    }

    @Test fun aGuestBearerRidesAlongAndIsMerged() = run {
        session.signedIn(TokenPair("guest-acc", "guest-ref"))
        AccountKindStore(files).save(AccountKind.GUEST)
        routes = { session(isNew = false, merged = guestId) }
        fillSignIn()
        model.submitEmail()
        assertEquals("Bearer guest-acc", last("/api/v1/auth/email/login").getHeader("Authorization"))
        assertEquals(GuestLink.MERGED, st().outcome!!.guestLink)
    }

    @Test fun staleGuestTokenIsDroppedAndTheSignUpRetried() = run {
        session.signedIn(TokenPair("guest-acc", "guest-ref"))
        routes = { r ->
            if (r.getHeader("Authorization") != null) problem(401, "GUEST_SESSION_INVALID") else session(201, isNew = true)
        }
        fillCreate()
        model.submitEmail()
        val registers = requests.filter { it.path == "/api/v1/auth/email/register" }
        assertEquals(2, registers.size)
        assertNull(registers[1].getHeader("Authorization"))
        assertEquals(SignInStep.DONE, st().step)
    }

    // --- forgot / reset ---------------------------------------------------------------------

    @Test fun forgotAndResetChangeThePasswordAndReturnToSignIn() = run {
        routes = { r ->
            when (r.path) {
                "/api/v1/auth/password/forgot" -> MockResponse().setResponseCode(202)
                "/api/v1/auth/password/reset" -> MockResponse().setResponseCode(204)
                else -> MockResponse().setResponseCode(404)
            }
        }
        fillSignIn()
        model.forgotPassword()
        assertEquals(SignInStep.RESET_REQUEST, st().step)
        model.submitForgot()
        assertTrue(body(last("/api/v1/auth/password/forgot")).contains("selam@example.com"))
        assertEquals(SignInStep.RESET_CODE, st().step)
        assertEquals(SignInNotice.RESET_CODE_SENT, st().notice)
        model.setResetCode("12a3456")
        assertEquals("123456", st().code)
        model.setNewPassword("short")
        model.submitReset()
        assertEquals(SignInMessage.PasswordTooShort, st().message)
        model.setNewPassword("tej and tibs at noon")
        model.submitReset()
        val reset = body(last("/api/v1/auth/password/reset"))
        assertTrue(reset, reset.contains(""""token":"123456""""))
        assertTrue(reset, reset.contains(""""email":"selam@example.com""""))
        assertEquals(SignInStep.OPTIONS, st().step)
        assertEquals(SignInMethod.EMAIL, st().method)
        assertEquals(SignInNotice.PASSWORD_CHANGED, st().notice)
        assertEquals("", st().newPassword)
        assertEquals("", st().password)
        assertNull(session.tokens()) // a reset does not sign in
    }

    @Test fun aWrongResetCodeIsClearedAndCanBeRequestedAgain() = run {
        routes = { r ->
            when (r.path) {
                "/api/v1/auth/password/forgot" -> MockResponse().setResponseCode(202)
                else -> problem(400, "RESET_CODE_INVALID")
            }
        }
        fillSignIn()
        model.forgotPassword()
        model.submitForgot()
        model.setResetCode("000000")
        model.setNewPassword("tej and tibs at noon")
        model.submitReset()
        assertEquals(SignInMessage.ResetCodeInvalid, st().message)
        assertEquals("", st().code)
        assertFalse(st().canResend(now))
        now += 60_000
        model.resend()
        assertEquals(2, requests.count { it.path == "/api/v1/auth/password/forgot" })
    }

    @Test fun resetWithoutAnEmailServiceSaysSo() = run {
        routes = { problem(503, "EMAIL_NOT_CONFIGURED") }
        fillSignIn()
        model.forgotPassword()
        model.submitForgot()
        assertEquals(SignInMessage.EmailUnavailable, st().message)
        assertEquals(SignInStep.RESET_REQUEST, st().step)
    }
}
