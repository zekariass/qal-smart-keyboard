package com.qalsmartkeyboard.app.auth

import com.qalsmartkeyboard.api.ApiClients
import com.qalsmartkeyboard.api.CallLogger
import com.qalsmartkeyboard.api.ClientInfo
import com.qalsmartkeyboard.api.ClientSurface
import com.qalsmartkeyboard.api.SessionManager
import com.qalsmartkeyboard.api.SignedOutReason
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
import org.junit.Assert.assertNotEquals
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
 * The sign-in state machine end to end over the real `:api` client, the Keystore-backed token
 * store (injected key) and `MockWebServer`: OTP, Google and guest flows, guest linking, error
 * mapping, cooldown, and sign-out. (A2.2 "flow tested"; the Credential Manager UI and a real
 * Integrity token need a device.)
 */
class SignInModelTest {
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
    private val updates = CopyOnWriteArrayList<Int?>()
    private var attestation: AttestationProvider = MockAttestationProvider
    private var routes: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404) }
    private var nonceCounter = 0

    private val challengeId = "0b7e8a56-1a79-4cfb-9a8e-7b8a1f4d2c10"
    private val userId = "7a0f0f64-1f1e-4c64-9d0b-0a0a0a0a0a0a"
    private val guestId = "9c9c9c9c-1f1e-4c64-9d0b-0a0a0a0a0a0a"

    @Before fun setUp() {
        dir = Files.createTempDirectory("qal-signin").toFile()
        files = EncryptedFileStore(dir, AesGcmCipher { key })
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return routes(request)
            }
        }
        server.start()
        build()
    }

    @After fun tearDown() {
        runCatching { server.shutdown() }
        dir.deleteRecursively()
    }

    private fun build() {
        session = SessionManager(EncryptedTokenStore(files))
        val clients = ApiClients(
            TransportConfig(
                baseUrl = "http://localhost:${server.port}",
                client = ClientInfo("1.0", ClientSurface.COMPANION),
                readTimeoutMs = 5_000,
                logger = CallLogger { _, status, _ -> updates += status },
            ),
            session,
        )
        repo = AuthRepository(
            clients, AccountKindStore(files), { installId }, attestation, DeviceInfo("1.0", "Android 14 (API 34)"), AccountLabelStore(files),
        )
        model = SignInModel(repo, clock = { now }, nonces = { "nonce-${++nonceCounter}" })
    }

    // --- response helpers -------------------------------------------------------------------

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun problem(status: Int, code: String, extra: String = "", headers: Map<String, String> = emptyMap()) =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/problem+json").apply {
            headers.forEach { (k, v) -> setHeader(k, v) }
        }.setBody("""{"type":"about:blank","title":"t","status":$status,"code":"$code"$extra}""")

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    private fun challenge(resendInMs: Long? = 60_000, codeLength: Int = 6) = json(
        202,
        """{"challengeId":"$challengeId","expiresAt":"${iso(now + 300_000)}",""" +
            (resendInMs?.let { """"resendAvailableAt":"${iso(now + it)}",""" } ?: "") + """"codeLength":$codeLength}""",
    )

    private fun session(
        access: String = "acc-1", refresh: String = "ref-1", type: String = "REGISTERED", id: String = userId,
        isNew: Boolean? = null, merged: String? = null, user: String = "",
    ) = json(
        200,
        """{"tokenType":"Bearer","accessToken":"$access","accessTokenExpiresAt":"${iso(now + 600_000)}",""" +
            """"refreshToken":"$refresh","refreshTokenExpiresAt":"${iso(now + 86_400_000)}",""" +
            (isNew?.let { """"isNewUser":$it,""" } ?: "") + (merged?.let { """"mergedGuestUserId":"$it",""" } ?: "") +
            """"user":{"id":"$id","accountType":"$type","accountStatus":"ACTIVE"$user}}""",
    )

    private fun nonceResponse(nonce: String = "SERVER_NONCE_0123456789_abcdefghijklmnopqrs") =
        json(201, """{"nonce":"$nonce","expiresAt":"${iso(now + 300_000)}"}""")

    private fun paths() = requests.map { it.path }
    private fun last(path: String) = requests.last { it.path == path }
    private fun body(r: RecordedRequest) = r.body.clone().readUtf8()
    private fun run(block: suspend () -> Unit) = runBlocking { block() }
    private fun st() = model.state.value
    private fun status() = runBlocking { repo.status().first() }

    private suspend fun enterPhoneAndCode(phone: String = "0911 234 567", code: String = "123456") {
        model.setPhone(phone)
        model.submitPhone()
        model.setCode(code)
    }

    // --- phone OTP ---------------------------------------------------------------------------

    @Test fun phoneOtpHappyPath() = run {
        routes = { r ->
            when (r.path) {
                "/api/v1/auth/otp/start" -> challenge()
                "/api/v1/auth/otp/verify" -> session(isNew = true)
                else -> MockResponse().setResponseCode(404)
            }
        }
        enterPhoneAndCode()
        assertEquals(SignInStep.CODE, st().step)
        assertEquals("+251911234567", st().phoneE164)
        val start = body(last("/api/v1/auth/otp/start"))
        assertTrue(start, start.contains(""""phoneNumber":"+251911234567""""))
        assertTrue(start, start.contains(installId.toString()))
        assertNull(last("/api/v1/auth/otp/start").getHeader("Authorization")) // no session yet

        model.setAgeConfirmed(true)
        model.submitCode()
        val verify = body(last("/api/v1/auth/otp/verify"))
        assertTrue(verify, verify.contains(""""challengeId":"$challengeId""""))
        assertTrue(verify, verify.contains(""""code":"123456""""))
        assertTrue(verify, verify.contains(""""ageConfirmed":true"""))
        assertTrue(verify, verify.contains(""""platform":"ANDROID""""))

        assertEquals(SignInStep.DONE, st().step)
        assertEquals(AccountKind.REGISTERED, st().outcome!!.kind)
        assertTrue(st().outcome!!.isNewUser)
        assertEquals(GuestLink.NONE, st().outcome!!.guestLink)
        assertEquals(AccountStatus.Registered, status())
        assertEquals(TokenPair("acc-1", "ref-1"), session.tokens())
        // The tokens are on disk (encrypted) and a restarted process is still signed in.
        build()
        assertEquals(AccountStatus.Registered, status())
        assertEquals(TokenPair("acc-1", "ref-1"), session.tokens())
    }

    @Test fun badPhoneNumbersNeverReachTheServer() = run {
        model.setPhone("")
        model.submitPhone()
        assertEquals(SignInMessage.PhoneEmpty, st().message)
        model.setPhone("0111234567")
        model.submitPhone()
        assertEquals(SignInMessage.PhoneNotEthiopianMobile, st().message)
        model.setPhone("447700900123") // another country: no server call
        model.submitPhone()
        assertEquals(SignInMessage.PhoneNotEthiopianMobile, st().message)
        assertTrue(requests.isEmpty())
        assertEquals(SignInStep.OPTIONS, st().step)
    }

    @Test fun serverRegionRefusalShowsTheEthiopiaOnlyMessage() = run {
        routes = { problem(400, "PHONE_REGION_NOT_SUPPORTED") }
        model.setPhone("911234567")
        model.submitPhone()
        assertEquals(SignInMessage.PhoneRegionNotSupported, st().message)
        assertEquals(SignInStep.OPTIONS, st().step)
        assertNull(st().busy)
    }

    @Test fun serverCarrierRefusalShowsTheSafaricomMessage() = run {
        routes = { problem(400, "PHONE_CARRIER_NOT_SUPPORTED") }
        model.setPhone("911234567")
        model.submitPhone()
        assertEquals(SignInMessage.PhoneCarrierNotSupported, st().message)
        assertEquals(SignInStep.OPTIONS, st().step)
    }

    @Test fun safaricomAndInternationalNumbersAreRefusedOnTheDevice() = run {
        model.setPhone("0711234567")
        model.submitPhone()
        assertEquals(SignInMessage.PhoneCarrierNotSupported, st().message)
        model.setPhone("+447700900123")
        model.submitPhone()
        assertEquals(SignInMessage.PhoneRegionNotSupported, st().message)
        assertTrue(requests.isEmpty())
    }

    @Test fun resendCooldownComesFromTheServerAndGatesTheButton() = run {
        routes = { challenge(resendInMs = 45_000) }
        enterPhoneAndCode()
        assertEquals(45L, st().resendSecondsLeft(now))
        assertFalse(st().canResend(now))
        model.resend() // too early: nothing is sent
        assertEquals(1, requests.size)

        now += 44_000
        assertEquals(1L, st().resendSecondsLeft(now))
        model.resend()
        assertEquals(1, requests.size)

        now += 1_000
        assertTrue(st().canResend(now))
        model.resend()
        assertEquals(2, requests.size)
        assertEquals(45L, st().resendSecondsLeft(now)) // a new cooldown starts
        assertEquals(SignInModel.MAX_ATTEMPTS, st().attemptsLeft) // a new challenge: attempts reset
    }

    @Test fun cooldownIsClampedAgainstAWrongDeviceClock() = run {
        routes = { challenge(resendInMs = 3_600_000) } // would be an hour: a skewed clock must not lock the user out
        enterPhoneAndCode()
        assertEquals(120L, st().resendSecondsLeft(now))
    }

    @Test fun missingResendTimeFallsBackToSixtySeconds() = run {
        routes = { challenge(resendInMs = null) }
        enterPhoneAndCode()
        assertEquals(60L, st().resendSecondsLeft(now))
    }

    @Test fun wrongCodesCountDownAttemptsThenLockTheChallenge() = run {
        routes = { r -> if (r.path!!.endsWith("/start")) challenge() else problem(401, "OTP_INVALID") }
        enterPhoneAndCode()
        repeat(2) {
            model.setCode("000000")
            model.submitCode()
        }
        assertEquals(SignInMessage.OtpInvalid, st().message)
        assertEquals(3, st().attemptsLeft)
        assertEquals("", st().code) // cleared for the next try
        assertEquals(SignInStep.CODE, st().step)

        routes = { r -> if (r.path!!.endsWith("/start")) challenge() else problem(429, "OTP_TOO_MANY_ATTEMPTS", headers = mapOf("Retry-After" to "60")) }
        model.setCode("000000")
        model.submitCode()
        assertEquals(SignInMessage.OtpTooManyAttempts, st().message) // code wins over the generic 429 mapping
        assertTrue(st().challengeDead)
        assertTrue(st().canResend(now)) // a dead challenge can be replaced at once
        val before = requests.size
        model.setCode("123456")
        model.submitCode() // nothing is sent for a dead challenge
        assertEquals(before, requests.size)
    }

    @Test fun expiredCodeAsksForANewOne() = run {
        routes = { r -> if (r.path!!.endsWith("/start")) challenge() else problem(401, "OTP_EXPIRED") }
        enterPhoneAndCode()
        model.submitCode()
        assertEquals(SignInMessage.OtpExpired, st().message)
        assertTrue(st().challengeDead)
        routes = { challenge() }
        model.resend()
        assertFalse(st().challengeDead)
        assertEquals(SignInStep.CODE, st().step)
        assertNull(st().message)
    }

    @Test fun rateLimitedStartShowsRetryAfter() = run {
        routes = { problem(429, "RATE_LIMITED", headers = mapOf("Retry-After" to "30")) }
        model.setPhone("0911234567")
        model.submitPhone()
        assertEquals(SignInMessage.RateLimited(30), st().message)
        assertEquals(SignInStep.OPTIONS, st().step)
        assertNull(st().busy)
    }

    @Test fun incompleteCodeIsNotSent() = run {
        routes = { challenge() }
        enterPhoneAndCode(code = "123")
        model.submitCode()
        assertEquals(SignInMessage.CodeIncomplete, st().message)
        assertEquals(1, requests.size)
    }

    @Test fun codeFieldKeepsOnlyDigitsUpToTheChallengeLength() = run {
        routes = { challenge(codeLength = 4) }
        enterPhoneAndCode(code = "1a2b3c4d5")
        assertEquals("1234", st().code)
    }

    @Test fun newAccountWithoutAgeConfirmationAsksForIt() = run {
        var verifies = 0
        routes = { r ->
            when {
                r.path!!.endsWith("/start") -> challenge()
                ++verifies == 1 -> problem(400, "VALIDATION_FAILED", ""","errors":[{"field":"ageConfirmed","code":"REQUIRED","message":"x"}]""")
                else -> session(isNew = true)
            }
        }
        enterPhoneAndCode()
        model.submitCode()
        assertEquals(SignInMessage.AgeRequired, st().message)
        assertEquals(SignInStep.CODE, st().step)
        assertFalse(st().challengeDead) // the code was not consumed
        model.setCode("123456")
        model.setAgeConfirmed(true)
        model.submitCode()
        assertEquals(SignInStep.DONE, st().step)
        assertFalse(body(requests.first { it.path!!.endsWith("/verify") }).contains(""""ageConfirmed":true"""))
        assertTrue(body(last("/api/v1/auth/otp/verify")).contains(""""ageConfirmed":true"""))
    }

    @Test fun suspendedAccountAndOfflineAndTimeoutMessages() = run {
        routes = { r -> if (r.path!!.endsWith("/start")) challenge() else problem(403, "ACCOUNT_SUSPENDED") }
        enterPhoneAndCode()
        model.submitCode()
        assertEquals(SignInMessage.AccountSuspended, st().message)

        server.shutdown() // no connection
        model.setPhone("0911234567")
        model.backToOptions()
        model.setPhone("0911234567")
        model.submitPhone()
        assertEquals(SignInMessage.Offline, st().message)
        assertNull(st().busy)
    }

    @Test fun updateRequiredIsReportedByTheFlowAndTheCallLogger() = run {
        routes = { problem(426, "APP_VERSION_UNSUPPORTED", ""","minSupportedAppVersion":"2.0.0"""") }
        model.setPhone("0911234567")
        model.submitPhone()
        assertTrue(st().updateRequired)
        assertTrue(updates.contains(426)) // AuthGraph turns this status into the app-wide update screen
    }

    // --- guest --------------------------------------------------------------------------------

    @Test fun guestWithMockAttestationUsesTheServerNonceAndTheMockTokenFormat() = run {
        routes = { r ->
            when (r.path) {
                "/api/v1/auth/attestation-nonces" -> nonceResponse("SERVER_NONCE_0123456789_abcdefghijklmnopqrs")
                "/api/v1/auth/guest" -> session("guest-acc", "guest-ref", type = "GUEST", id = guestId)
                else -> MockResponse().setResponseCode(404)
            }
        }
        model.continueAsGuest()
        val guest = body(last("/api/v1/auth/guest"))
        assertTrue(guest, guest.contains(""""provider":"PLAY_INTEGRITY""""))
        assertTrue(guest, guest.contains(""""token":"mock-play-integrity.v1.SERVER_NONCE_0123456789_abcdefghijklmnopqrs.valid""""))
        assertTrue(guest, guest.contains(""""nonce":"SERVER_NONCE_0123456789_abcdefghijklmnopqrs""""))
        assertTrue(guest, guest.contains(installId.toString()))
        assertEquals(paths(), listOf("/api/v1/auth/attestation-nonces", "/api/v1/auth/guest"))
        assertEquals(AccountKind.GUEST, st().outcome!!.kind)
        assertEquals(AccountStatus.Guest, status())
        build() // restart: still a guest (the kind is stored next to the tokens)
        assertEquals(AccountStatus.Guest, status())
    }

    @Test fun everyAttestationFailureIs403AndShownAsOne() = run {
        routes = { r ->
            if (r.path!!.endsWith("/attestation-nonces")) nonceResponse() else problem(403, "GUEST_NOT_ALLOWED")
        }
        model.continueAsGuest()
        assertEquals(SignInMessage.GuestNotAllowed, st().message)
        assertEquals(AccountStatus.SignedOut(null), status())
        assertNull(st().busy)
    }

    @Test fun unavailableAttestationSkipsTheGuestCallAndFailedAttestationIsReported() = run {
        attestation = UnavailableAttestationProvider
        build()
        routes = { nonceResponse() }
        assertFalse(repo.guestSupported)
        model.continueAsGuest()
        assertEquals(SignInMessage.GuestUnavailable, st().message)
        assertEquals(paths(), listOf("/api/v1/auth/attestation-nonces")) // no /auth/guest without evidence

        attestation = object : AttestationProvider {
            override val isSupported = true
            override suspend fun attest(nonce: String) = AttestationResult.Failed
        }
        build()
        model.continueAsGuest()
        assertEquals(SignInMessage.GuestUnavailable, st().message)
    }

    private suspend fun becomeGuest() {
        routes = { r ->
            when (r.path) {
                "/api/v1/auth/attestation-nonces" -> nonceResponse()
                "/api/v1/auth/guest" -> session("guest-acc", "guest-ref", type = "GUEST", id = guestId)
                else -> MockResponse().setResponseCode(404)
            }
        }
        model.continueAsGuest()
        assertEquals(AccountStatus.Guest, status())
        model.reset()
        requests.clear()
    }

    @Test fun guestUpgradedInPlaceByANewPhoneNumber() = run {
        becomeGuest()
        routes = { r ->
            if (r.path!!.endsWith("/start")) challenge() else session("reg-acc", "reg-ref", id = guestId, isNew = true, merged = guestId)
        }
        enterPhoneAndCode()
        model.submitCode()
        // The guest bearer rode along on both calls, so the server could link it.
        assertEquals("Bearer guest-acc", last("/api/v1/auth/otp/start").getHeader("Authorization"))
        assertEquals("Bearer guest-acc", last("/api/v1/auth/otp/verify").getHeader("Authorization"))
        val o = st().outcome!!
        assertEquals(GuestLink.UPGRADED_IN_PLACE, o.guestLink)
        assertTrue(o.isNewUser)
        assertEquals(AccountKind.REGISTERED, o.kind)
        assertEquals(AccountStatus.Registered, status())
        assertEquals(TokenPair("reg-acc", "reg-ref"), session.tokens()) // the guest pair is replaced
    }

    @Test fun guestMergedIntoAnExistingAccount() = run {
        becomeGuest()
        routes = { r ->
            if (r.path!!.endsWith("/start")) challenge() else session("reg-acc", "reg-ref", id = userId, isNew = false, merged = guestId)
        }
        enterPhoneAndCode()
        model.submitCode()
        val o = st().outcome!!
        assertEquals(GuestLink.MERGED, o.guestLink)
        assertFalse(o.isNewUser)
        assertEquals(AccountStatus.Registered, status())
    }

    @Test fun staleGuestTokenIsDiscardedAndTheSameCodeRetriedWithoutIt() = run {
        becomeGuest()
        routes = { r ->
            when {
                r.path!!.endsWith("/start") -> challenge()
                r.getHeader("Authorization") != null -> problem(401, "GUEST_SESSION_INVALID")
                else -> session("reg-acc", "reg-ref", isNew = false)
            }
        }
        enterPhoneAndCode()
        model.submitCode()
        val verifies = requests.filter { it.path!!.endsWith("/verify") }
        assertEquals(2, verifies.size)
        assertEquals("Bearer guest-acc", verifies[0].getHeader("Authorization"))
        assertNull(verifies[1].getHeader("Authorization"))
        assertEquals(body(verifies[0]), body(verifies[1])) // the same code and challenge
        assertEquals(SignInStep.DONE, st().step)
        assertEquals(GuestLink.NONE, st().outcome!!.guestLink)
        assertEquals(AccountStatus.Registered, status())
    }

    // --- Google -------------------------------------------------------------------------------

    private class FakeGoogle(override val isConfigured: Boolean = true, var result: (String) -> GoogleResult) : GoogleIdTokenSource {
        val nonces = ArrayList<String>()
        override suspend fun getIdToken(nonce: String): GoogleResult {
            nonces += nonce
            return result(nonce)
        }
    }

    @Test fun googleSignInPassesAFreshNonceAndTheIdToken() = run {
        routes = { session("g-acc", "g-ref", isNew = true) }
        val google = FakeGoogle { GoogleResult.Token("ID.TOKEN.VALUE", "Abebe") }
        model.setAgeConfirmed(true)
        model.signInWithGoogle(google)
        val req = last("/api/v1/auth/oauth/GOOGLE")
        val b = body(req)
        assertTrue(b, b.contains(""""idToken":"ID.TOKEN.VALUE""""))
        assertTrue(b, b.contains(""""nonce":"${google.nonces.single()}""""))
        assertTrue(b, b.contains(""""displayName":"Abebe""""))
        assertTrue(b, b.contains(""""ageConfirmed":true"""))
        assertEquals(SignInStep.DONE, st().step)
        assertEquals(AccountStatus.Registered, status())
        assertEquals(TokenPair("g-acc", "g-ref"), session.tokens())

        // A second attempt uses a different nonce.
        model.reset()
        model.signInWithGoogle(google)
        assertEquals(2, google.nonces.distinct().size)
    }

    @Test fun googleCarriesTheGuestBearerAndReportsTheMerge() = run {
        becomeGuest()
        routes = { session("g-acc", "g-ref", id = userId, isNew = false, merged = guestId) }
        model.signInWithGoogle(FakeGoogle { GoogleResult.Token("T", null) })
        assertEquals("Bearer guest-acc", last("/api/v1/auth/oauth/GOOGLE").getHeader("Authorization"))
        assertEquals(GuestLink.MERGED, st().outcome!!.guestLink)
    }

    @Test fun googleCancelledShowsNothingAndCallsNothing() = run {
        model.signInWithGoogle(FakeGoogle { GoogleResult.Cancelled })
        assertNull(st().message)
        assertNull(st().busy)
        assertTrue(requests.isEmpty())
    }

    @Test fun googleUnavailableAndFailedAndNotConfigured() = run {
        model.signInWithGoogle(FakeGoogle { GoogleResult.Unavailable })
        assertEquals(SignInMessage.GoogleUnavailable, st().message)
        model.signInWithGoogle(FakeGoogle { GoogleResult.Failed })
        assertEquals(SignInMessage.GoogleFailed, st().message)
        model.signInWithGoogle(FakeGoogle(isConfigured = false) { error("must not be called") })
        assertEquals(SignInMessage.GoogleUnavailable, st().message)
        assertTrue(requests.isEmpty())
    }

    @Test fun googleServerErrorsAreMapped() = run {
        val token = FakeGoogle { GoogleResult.Token("T", null) }
        routes = { problem(401, "UNAUTHENTICATED") }
        model.signInWithGoogle(token)
        assertEquals(SignInMessage.GoogleRejected, st().message)
        routes = { problem(503, "PROVIDER_UNAVAILABLE") }
        model.signInWithGoogle(token)
        assertEquals(SignInMessage.ProviderUnavailable, st().message)
        routes = { problem(403, "ACCOUNT_SUSPENDED") }
        model.signInWithGoogle(token)
        assertEquals(SignInMessage.AccountSuspended, st().message)
        routes = { problem(400, "VALIDATION_FAILED", ""","errors":[{"field":"ageConfirmed","code":"REQUIRED","message":"x"}]""") }
        model.signInWithGoogle(token)
        assertEquals(SignInMessage.AgeRequired, st().message)
        assertNull(st().busy)
    }

    // --- session end and sign-out -------------------------------------------------------------

    @Test fun refreshTokenReuseSignsOutAndWipesTheTokens() = run {
        becomeGuest()
        routes = { r ->
            when {
                r.path == "/api/v1/auth/refresh" -> problem(401, "REFRESH_TOKEN_REUSED")
                r.getHeader("Authorization") != null -> problem(401, "TOKEN_EXPIRED")
                else -> challenge()
            }
        }
        model.setPhone("0911234567")
        model.submitPhone() // the guest bearer expired; the refresh is rejected as reuse: the session ends
        assertNull(session.tokens())
        assertEquals(AccountStatus.SignedOut(SignedOutReason.REFRESH_TOKEN_REUSED), status())
        // ... and the code is still sent, once more and without a bearer (ADR 0034).
        val starts = requests.filter { it.path == "/api/v1/auth/otp/start" }
        assertEquals(listOf("Bearer guest-acc", null), starts.map { it.getHeader("Authorization") })
        assertEquals(SignInStep.CODE, st().step)
        build() // restarted: nothing resurrects
        assertEquals(AccountStatus.SignedOut(null), status())
        assertTrue(dir.listFiles()!!.none { it.name.startsWith("tokens") })
    }

    // --- ADR 0034: the bearer policy of sign-in calls -----------------------------------------

    private val google = FakeGoogle { GoogleResult.Token("T", null) }

    private fun invalidToken() = problem(401, "UNAUTHENTICATED", headers = mapOf("WWW-Authenticate" to "Bearer error=\"invalid_token\""))

    private fun oauth() = requests.filter { it.path == "/api/v1/auth/oauth/GOOGLE" }

    @Test fun aRegisteredSessionIsNeverSentOnSignInAndIsReplaced() = run {
        routes = { session("first-acc", "first-ref") }
        model.signInWithGoogle(google)
        assertEquals(AccountStatus.Registered, status())
        model.reset()
        requests.clear()
        routes = { r ->
            if (r.getHeader("Authorization") != null) problem(401, "UNAUTHENTICATED") else session("second-acc", "second-ref", id = guestId)
        }
        model.signInWithGoogle(google)
        assertNull(oauth().single().getHeader("Authorization"))
        assertEquals(SignInStep.DONE, st().step)
        assertEquals(TokenPair("second-acc", "second-ref"), session.tokens())
        // OTP start goes out without it too.
        routes = { challenge() }
        model.reset()
        model.setPhone("0911234567")
        model.submitPhone()
        assertNull(last("/api/v1/auth/otp/start").getHeader("Authorization"))
    }

    @Test fun aKindlessStoredSessionIsNotSentEither() = run {
        session.signedIn(TokenPair("stale-acc", "stale-ref")) // e.g. tokens of a dev server that restarted
        routes = { r -> if (r.getHeader("Authorization") != null) invalidToken() else session("g-acc", "g-ref") }
        model.signInWithGoogle(google)
        assertNull(oauth().single().getHeader("Authorization"))
        assertEquals(SignInStep.DONE, st().step)
        assertEquals(AccountStatus.Registered, status())
    }

    @Test fun aGuestTokenTheServerCannotVerifyIsRefreshedAndStillLinks() = run {
        becomeGuest()
        routes = { r ->
            when {
                r.path == "/api/v1/auth/refresh" -> session("guest-acc-2", "guest-ref-2", type = "GUEST", id = guestId)
                r.getHeader("Authorization") == "Bearer guest-acc" -> invalidToken() // signed with a key the server lost
                else -> session("g-acc", "g-ref", id = userId, merged = guestId)
            }
        }
        model.signInWithGoogle(google)
        assertEquals(listOf("Bearer guest-acc", "Bearer guest-acc-2"), oauth().map { it.getHeader("Authorization") })
        assertEquals(GuestLink.MERGED, st().outcome!!.guestLink)
        assertEquals(AccountStatus.Registered, status())
    }

    @Test fun aDeadGuestSessionIsClearedAndTheSignInRetriedOnceWithoutIt() = run {
        becomeGuest()
        routes = { r ->
            when {
                r.path == "/api/v1/auth/refresh" -> problem(401, "UNAUTHENTICATED") // the guest's refresh token is gone too
                r.getHeader("Authorization") != null -> invalidToken()
                else -> session("g-acc", "g-ref", id = userId, isNew = true)
            }
        }
        model.signInWithGoogle(google)
        assertEquals(listOf("Bearer guest-acc", null), oauth().map { it.getHeader("Authorization") })
        assertEquals(body(oauth()[0]), body(oauth()[1])) // the same ID token and nonce
        assertEquals(SignInStep.DONE, st().step)
        assertEquals(GuestLink.NONE, st().outcome!!.guestLink)
        assertEquals(AccountStatus.Registered, status())
        assertEquals(TokenPair("g-acc", "g-ref"), session.tokens())
    }

    @Test fun theRetryWithoutBearerNeverLoops() = run {
        becomeGuest()
        routes = { r ->
            when {
                r.path == "/api/v1/auth/refresh" -> problem(401, "UNAUTHENTICATED")
                r.getHeader("Authorization") != null -> invalidToken()
                else -> problem(401, "UNAUTHENTICATED") // the ID token itself is refused
            }
        }
        model.signInWithGoogle(google)
        assertEquals(2, oauth().size)
        assertEquals(1, requests.count { it.path == "/api/v1/auth/refresh" })
        assertEquals(SignInMessage.GoogleRejected, st().message)
        assertNull(session.tokens()) // the dead guest stays gone
        assertNull(st().busy)
    }

    @Test fun aRefusedGoogleTokenDoesNotCostALiveGuestItsSession() = run {
        becomeGuest()
        routes = { problem(401, "UNAUTHENTICATED") } // no invalid_token: about the ID token, not the bearer
        model.signInWithGoogle(google)
        assertEquals(1, oauth().size)
        assertEquals(SignInMessage.GoogleRejected, st().message)
        assertEquals(AccountStatus.Guest, status())
        assertEquals(TokenPair("guest-acc", "guest-ref"), session.tokens())
    }

    @Test fun signedInAsShowsTheMaskedPhoneOrEmailAndSignOutForgetsIt() = run {
        routes = { session("g-acc", "g-ref", user = ""","phoneNumberMasked":"+251 9•• ••• 567"""") }
        model.signInWithGoogle(google)
        assertEquals("+251 9•• ••• 567", repo.accountLabel.value)
        build() // survives a restart, like the tokens
        assertEquals("+251 9•• ••• 567", repo.accountLabel.value)
        routes = { MockResponse().setResponseCode(204) }
        repo.signOut()
        assertNull(repo.accountLabel.value)
        build()
        assertNull(repo.accountLabel.value)

        routes = { session("g-acc", "g-ref", user = ""","email":"zemen@example.com"""") }
        model.signInWithGoogle(google)
        assertEquals("z•••@example.com", repo.accountLabel.value)
        assertNull(AccountLabels.maskEmail("no-at-sign"))
        assertNull(AccountLabels.maskEmail("@example.com"))
    }

    @Test fun signOutRevokesOnTheServerAndWipesLocally() = run {
        session.signedIn(TokenPair("acc", "ref"))
        routes = { MockResponse().setResponseCode(204) }
        repo.signOut()
        assertEquals("Bearer acc", last("/api/v1/auth/logout").getHeader("Authorization"))
        assertEquals(AccountStatus.SignedOut(SignedOutReason.SIGNED_OUT), status())
        assertNull(session.tokens())
        assertTrue(dir.listFiles()!!.none { it.name.startsWith("tokens") })
    }

    @Test fun signOutStillWipesWhenOfflineOrRejected() = run {
        session.signedIn(TokenPair("acc", "ref"))
        server.shutdown()
        repo.signOut()
        assertNull(session.tokens())
        assertEquals(AccountStatus.SignedOut(SignedOutReason.SIGNED_OUT), status())
    }

    @Test fun nonceGeneratorIsRandomAndLongEnough() {
        val a = SignInModel.randomNonce()
        val b = SignInModel.randomNonce()
        assertNotEquals(a, b)
        assertTrue(a.length >= 32)
    }
}
