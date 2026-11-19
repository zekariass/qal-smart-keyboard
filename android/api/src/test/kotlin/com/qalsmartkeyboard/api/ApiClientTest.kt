package com.qalsmartkeyboard.api

import com.qalsmartkeyboard.api.generated.models.ClientConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val CONFIG_JSON =
    """{"configVersion":3,"minSupportedAppVersion":"1.0.0","flags":{"a":true},"values":{"k":1},"serverTime":"2026-11-18T00:00:00Z"}"""

private fun sessionJson(access: String, refresh: String) =
    """{"tokenType":"Bearer","accessToken":"$access","accessTokenExpiresAt":"2026-11-18T00:15:00Z",""" +
        """"refreshToken":"$refresh","refreshTokenExpiresAt":"2027-01-18T00:00:00Z",""" +
        """"user":{"id":"7a0f0f64-1f1e-4c64-9d0b-0a0a0a0a0a0a","accountType":"REGISTERED","accountStatus":"ACTIVE"}}"""

private fun problem(status: Int, code: String, extra: String = "") =
    MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/problem+json")
        .setHeader("X-Correlation-Id", "cid-$status-$code")
        .setBody(
            """{"type":"about:blank","title":"t","status":$status,"code":"$code","correlationId":"body-cid"$extra}""",
        )

private fun ok(json: String) =
    MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(json)

class ApiClientTest {
    private lateinit var server: MockWebServer
    private val store = InMemoryTokenStore(TokenPair("old-access", "old-refresh"))
    private lateinit var session: SessionManager
    private val seen = CopyOnWriteArrayList<Triple<String, Int?, String?>>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        session = SessionManager(store)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun clients(
        surface: ClientSurface = ClientSurface.COMPANION,
        readTimeoutMs: Long = 5_000,
    ) = ApiClients(
        TransportConfig(
            baseUrl = "http://localhost:${server.port}",
            client = ClientInfo("2.0.3", surface),
            readTimeoutMs = readTimeoutMs,
            logger = CallLogger { m, s, c -> seen += Triple(m, s, c) },
        ),
        session,
    )

    private fun getConfig(c: ApiClients): ClientConfig = runBlocking { apiCall { c.appConfig.getClientConfig() } }

    private fun dispatch(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = handler(request)
        }
    }

    private fun failure(block: () -> Unit): ApiError =
        try {
            block()
            throw AssertionError("expected an ApiError")
        } catch (e: ApiError) {
            e
        }

    // ------------------------------------------------------------------ headers and bearer

    @Test
    fun sendsClientHeadersAndBearer() {
        server.enqueue(ok(CONFIG_JSON))
        val config = getConfig(clients(ClientSurface.KEYBOARD))
        assertEquals(3, config.configVersion)
        assertEquals(1, config.propertyValues.size)
        val r = server.takeRequest()
        assertEquals("/api/v1/config", r.path)
        assertEquals("ANDROID", r.getHeader("X-Client-Platform"))
        assertEquals("2.0.3", r.getHeader("X-App-Version"))
        assertEquals("KEYBOARD", r.getHeader("X-Client-Surface"))
        assertEquals("Bearer old-access", r.getHeader("Authorization"))
    }

    @Test
    fun noAuthorizationHeaderWhenSignedOut() {
        store.clear()
        server.enqueue(ok(CONFIG_JSON))
        getConfig(clients())
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun callerSuppliedClientHeadersAreReplaced() {
        server.enqueue(ok(CONFIG_JSON))
        val c = clients()
        runBlocking { apiCall { c.appConfig.getClientConfig(xAppVersion = "0.0.1") } }
        assertEquals("2.0.3", server.takeRequest().getHeader("X-App-Version"))
    }

    @Test
    fun correlationIdIsCapturedWithoutPathOrTokens() {
        server.enqueue(ok(CONFIG_JSON).setHeader("X-Correlation-Id", "abc-123"))
        getConfig(clients())
        assertEquals(listOf(Triple("GET", 200, "abc-123")), seen.toList())
    }

    // ------------------------------------------------------------------ refresh rotation

    @Test
    fun expiredTokenRefreshesOnceAndRetries() {
        dispatch { r ->
            when {
                r.path == "/api/v1/auth/refresh" -> ok(sessionJson("new-access", "new-refresh"))
                r.getHeader("Authorization") == "Bearer new-access" -> ok(CONFIG_JSON)
                else -> problem(401, "TOKEN_EXPIRED")
            }
        }
        getConfig(clients())
        val first = server.takeRequest()
        assertEquals("Bearer old-access", first.getHeader("Authorization"))
        val refresh = server.takeRequest()
        assertEquals("/api/v1/auth/refresh", refresh.path)
        assertEquals("""{"refreshToken":"old-refresh"}""", refresh.body.readUtf8())
        assertNull(refresh.getHeader("Authorization"))
        assertEquals("ANDROID", refresh.getHeader("X-Client-Platform"))
        val retry = server.takeRequest()
        assertEquals("Bearer new-access", retry.getHeader("Authorization"))
        // newest pair stored, signed in
        assertEquals(TokenPair("new-access", "new-refresh"), store.load())
        assertEquals(SessionState.SignedIn, session.state.value)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun concurrentExpiredRequestsShareOneRefresh() {
        val refreshes = AtomicInteger()
        val arrived = CountDownLatch(4)
        dispatch { r ->
            when {
                r.path == "/api/v1/auth/refresh" -> {
                    arrived.await(10, TimeUnit.SECONDS) // all four requests are in flight before the refresh answers
                    refreshes.incrementAndGet()
                    ok(sessionJson("new-access", "new-refresh"))
                }
                r.getHeader("Authorization") == "Bearer new-access" -> ok(CONFIG_JSON)
                else -> {
                    arrived.countDown()
                    problem(401, "TOKEN_EXPIRED")
                }
            }
        }
        val c = clients()
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val results = (1..4).map {
            pool.submit<ClientConfig> {
                start.await()
                getConfig(c)
            }
        }
        start.countDown()
        results.forEach { assertEquals(3, it.get(20, TimeUnit.SECONDS).configVersion) }
        pool.shutdown()
        assertEquals("exactly one refresh for concurrent 401s", 1, refreshes.get())
        assertEquals(TokenPair("new-access", "new-refresh"), store.load())
    }

    @Test
    fun retriesOnlyOnceWhenStillExpired() {
        val refreshes = AtomicInteger()
        dispatch { r ->
            if (r.path == "/api/v1/auth/refresh") {
                refreshes.incrementAndGet()
                ok(sessionJson("new-access", "new-refresh"))
            } else {
                problem(401, "TOKEN_EXPIRED")
            }
        }
        val e = failure { getConfig(clients()) }
        assertEquals("TOKEN_EXPIRED", e.code)
        assertEquals(1, refreshes.get())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun refreshTokenReuseSignsOut() {
        dispatch { r ->
            if (r.path == "/api/v1/auth/refresh") problem(401, "REFRESH_TOKEN_REUSED") else problem(401, "TOKEN_EXPIRED")
        }
        val e = failure { getConfig(clients()) }
        assertTrue(e is ApiError.SignedOut)
        assertEquals(SignedOutReason.REFRESH_TOKEN_REUSED, (e as ApiError.SignedOut).reason)
        assertNull(store.load())
        assertEquals(SessionState.SignedOut(SignedOutReason.REFRESH_TOKEN_REUSED), session.state.value)
    }

    @Test
    fun refreshRejectedSignsOut() {
        dispatch { r ->
            if (r.path == "/api/v1/auth/refresh") problem(401, "UNAUTHENTICATED") else problem(401, "TOKEN_EXPIRED")
        }
        val e = failure { getConfig(clients()) } as ApiError.SignedOut
        assertEquals(SignedOutReason.REFRESH_REJECTED, e.reason)
        assertNull(store.load())
    }

    @Test
    fun suspendedAccountOnRefreshSignsOut() {
        dispatch { r ->
            if (r.path == "/api/v1/auth/refresh") problem(403, "ACCOUNT_SUSPENDED") else problem(401, "TOKEN_EXPIRED")
        }
        val e = failure { getConfig(clients()) } as ApiError.SignedOut
        assertEquals(SignedOutReason.ACCOUNT_BLOCKED, e.reason)
    }

    @Test
    fun guestSessionInvalidSignsOutWithoutRefreshing() {
        dispatch { problem(401, "GUEST_SESSION_INVALID") }
        val e = failure { getConfig(clients()) } as ApiError.SignedOut
        assertEquals(SignedOutReason.GUEST_SESSION_INVALID, e.reason)
        assertEquals("body-cid", e.correlationId)
        assertNull(store.load())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun queuedRequestsAfterSignOutSeeSignedOut() {
        val arrived = CountDownLatch(2)
        dispatch { r ->
            if (r.path == "/api/v1/auth/refresh") {
                arrived.await(10, TimeUnit.SECONDS) // both requests are in flight before the refresh answers
                problem(401, "REFRESH_TOKEN_REUSED")
            } else {
                arrived.countDown()
                problem(401, "TOKEN_EXPIRED")
            }
        }
        val c = clients()
        val pool = Executors.newFixedThreadPool(2)
        val futures = (1..2).map { pool.submit<ApiError> { failure { getConfig(c) } } }
        futures.forEach { assertTrue(it.get(20, TimeUnit.SECONDS) is ApiError.SignedOut) }
        pool.shutdown()
        assertEquals("two requests and one refresh", 3, server.requestCount)
    }

    @Test
    fun unauthenticatedWithoutExpiryIsNotRefreshed() {
        dispatch { problem(401, "UNAUTHENTICATED") }
        val e = failure { getConfig(clients()) }
        assertEquals("UNAUTHENTICATED", e.code)
        assertEquals(1, server.requestCount)
        assertNotNull(store.load())
    }

    @Test
    fun refreshServerErrorKeepsSessionAndSurfacesTypedError() {
        dispatch { r ->
            if (r.path == "/api/v1/auth/refresh") problem(503, "PROVIDER_UNAVAILABLE") else problem(401, "TOKEN_EXPIRED")
        }
        val e = failure { getConfig(clients()) }
        assertTrue(e is ApiError.Http)
        assertEquals(503, e.status)
        assertEquals(TokenPair("old-access", "old-refresh"), store.load())
        assertEquals(SessionState.SignedIn, session.state.value)
    }

    @Test
    fun lostRefreshResponseKeepsOldTokenAndRetriesWithIt() {
        // First refresh: the server gets the request but the response is lost. The client keeps
        // the old refresh token and sends it again on the next call (server grace window, B2.4).
        val refreshBodies = CopyOnWriteArrayList<String>()
        val refreshCalls = AtomicInteger()
        dispatch { r ->
            when {
                r.path == "/api/v1/auth/refresh" -> {
                    refreshBodies += r.body.readUtf8()
                    if (refreshCalls.incrementAndGet() == 1) {
                        MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    } else {
                        ok(sessionJson("new-access", "new-refresh"))
                    }
                }
                r.getHeader("Authorization") == "Bearer new-access" -> ok(CONFIG_JSON)
                else -> problem(401, "TOKEN_EXPIRED")
            }
        }
        val c = clients()
        val e = failure { getConfig(c) }
        assertTrue(e is ApiError.Network)
        assertEquals(TokenPair("old-access", "old-refresh"), store.load())
        getConfig(c)
        assertEquals(listOf("""{"refreshToken":"old-refresh"}""", """{"refreshToken":"old-refresh"}"""), refreshBodies.toList())
        assertEquals(TokenPair("new-access", "new-refresh"), store.load())
    }

    @Test
    fun anotherThreadsRefreshIsReusedWhenStoredTokenChanged() {
        // The failed request used "old-access" but the store already holds a newer pair.
        store.save(TokenPair("newer-access", "newer-refresh"))
        dispatch { r ->
            when {
                r.path == "/api/v1/auth/refresh" -> throw AssertionError("must not refresh")
                r.getHeader("Authorization") == "Bearer newer-access" -> ok(CONFIG_JSON)
                else -> problem(401, "TOKEN_EXPIRED")
            }
        }
        // Build a request that carries the stale token explicitly.
        val c = clients()
        val request = okhttp3.Request.Builder()
            .url("http://localhost:${server.port}/api/v1/config")
            .header("Authorization", "Bearer old-access")
            .build()
        c.okHttpClient.newCall(request).execute().use { assertEquals(200, it.code) }
        assertEquals(TokenPair("newer-access", "newer-refresh"), store.load())
    }

    @Test
    fun signInStoresPairAndRequestsUseIt() {
        store.clear()
        dispatch { r ->
            if (r.path == "/api/v1/auth/otp/verify") ok(sessionJson("a1", "r1")) else ok(CONFIG_JSON)
        }
        val c = clients()
        val s = runBlocking {
            apiCall {
                c.auth.verifyPhoneOtp(
                    com.qalsmartkeyboard.api.generated.models.OtpVerifyRequest(
                        challengeId = java.util.UUID.randomUUID(),
                        code = "123456",
                    ),
                )
            }
        }
        session.signedIn(s)
        getConfig(c)
        server.takeRequest()
        assertEquals("Bearer a1", server.takeRequest().getHeader("Authorization"))
    }

    // ------------------------------------------------------------------ error mapping

    @Test
    fun mapsProblemDetailsWithFieldErrorsAndExtensions() {
        dispatch {
            problem(
                400,
                "VALIDATION_FAILED",
                ""","errors":[{"field":"/phoneNumber","code":"PATTERN","message":"bad"}],"consentCode":"AI_PROCESSING","currentRevision":7""",
            )
        }
        val e = failure { getConfig(clients()) } as ApiError.Http
        assertEquals(400, e.status)
        assertEquals("VALIDATION_FAILED", e.code)
        assertEquals("t", e.title)
        assertEquals("body-cid", e.correlationId)
        assertEquals(listOf(FieldError("/phoneNumber", "PATTERN", "bad")), e.fieldErrors)
        assertEquals(setOf("consentCode", "currentRevision"), e.extensions.keys)
        assertFalse(e.message!!.contains("bad"))
    }

    @Test
    fun maps426ToUpdateRequired() {
        dispatch { problem(426, "APP_VERSION_UNSUPPORTED", ""","minSupportedAppVersion":"1.4.0"""") }
        val e = failure { getConfig(clients()) } as ApiError.UpdateRequired
        assertEquals("1.4.0", e.minSupportedAppVersion)
        assertEquals(426, e.status)
        assertEquals("APP_VERSION_UNSUPPORTED", e.code)
        assertEquals(1, server.requestCount) // never refreshed
    }

    @Test
    fun maps429WithRetryAfter() {
        dispatch { problem(429, "RATE_LIMITED").setHeader("Retry-After", "42") }
        val e = failure { getConfig(clients()) } as ApiError.RateLimited
        assertEquals(42L, e.retryAfterSeconds)
        assertEquals("RATE_LIMITED", e.code)
    }

    @Test
    fun maps429WithoutRetryAfter() {
        dispatch { problem(429, "QUOTA_EXCEEDED", ""","featureCode":"TRANSLATE"""") }
        val e = failure { getConfig(clients()) } as ApiError.RateLimited
        assertNull(e.retryAfterSeconds)
        assertTrue(e.extensions.containsKey("featureCode"))
    }

    @Test
    fun nonProblemErrorBodyBecomesUnknownCodeWithHeaderCorrelationId() {
        dispatch {
            MockResponse().setResponseCode(502).setHeader("Content-Type", "text/html")
                .setHeader("X-Correlation-Id", "edge-1").setBody("<html>bad gateway</html>")
        }
        val e = failure { getConfig(clients()) } as ApiError.Http
        assertEquals(502, e.status)
        assertEquals(ProblemParser.UNKNOWN_CODE, e.code)
        assertEquals("edge-1", e.correlationId)
    }

    @Test
    fun readTimeoutIsATimeoutError() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val e = failure { getConfig(clients(readTimeoutMs = 200)) }
        assertTrue("was $e", e is ApiError.Timeout)
    }

    @Test
    fun connectionFailureIsANetworkError() {
        val c = clients()
        server.shutdown()
        val e = failure { getConfig(c) }
        assertTrue("was $e", e is ApiError.Network)
    }

    @Test
    fun unreadableSuccessBodyIsUnexpected() {
        server.enqueue(ok("""{"not":"a config"}"""))
        val e = failure { getConfig(clients()) }
        assertTrue("was $e", e is ApiError.Unexpected)
    }

    // ------------------------------------------------------------------ ADR 0035: refused tokens, listeners, sign-in without the session

    /** The security chain's refusal of the bearer itself (signature, key id, closed account): RFC 6750 `invalid_token`. */
    private fun invalidToken() = problem(401, "UNAUTHENTICATED").setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"")

    @Test
    fun invalidTokenRefreshesOnceAndRetries() {
        dispatch { r ->
            when {
                r.path == "/api/v1/auth/refresh" -> ok(sessionJson("new-access", "new-refresh"))
                r.getHeader("Authorization") == "Bearer old-access" -> invalidToken()
                else -> ok(CONFIG_JSON)
            }
        }
        assertEquals(3, getConfig(clients()).configVersion)
        assertEquals(TokenPair("new-access", "new-refresh"), store.load())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun invalidTokenWithRejectedRefreshSignsOut() {
        dispatch { r -> if (r.path == "/api/v1/auth/refresh") problem(401, "UNAUTHENTICATED") else invalidToken() }
        val e = failure { getConfig(clients()) } as ApiError.SignedOut
        assertEquals(SignedOutReason.REFRESH_REJECTED, e.reason)
        assertNull(store.load())
        assertEquals(SessionState.SignedOut(SignedOutReason.REFRESH_REJECTED), session.state.value)
    }

    @Test
    fun invalidTokenAgainAfterRefreshSignsOutWithoutLooping() {
        val refreshes = AtomicInteger()
        dispatch { r ->
            if (r.path == "/api/v1/auth/refresh") {
                refreshes.incrementAndGet()
                ok(sessionJson("new-access", "new-refresh"))
            } else {
                invalidToken()
            }
        }
        val e = failure { getConfig(clients()) } as ApiError.SignedOut
        assertEquals(SignedOutReason.ACCESS_TOKEN_REJECTED, e.reason)
        assertEquals(1, refreshes.get())
        assertEquals(3, server.requestCount)
        assertNull(store.load())
    }

    @Test
    fun guestRefusalOfAReplacedTokenKeepsTheNewSession() {
        dispatch { r ->
            if (r.getHeader("Authorization") == "Bearer old-access") {
                // A sign-in replaced the guest pair while this request was in flight.
                session.signedIn(TokenPair("account-access", "account-refresh"))
                problem(401, "GUEST_SESSION_INVALID")
            } else {
                ok(CONFIG_JSON)
            }
        }
        assertEquals(3, getConfig(clients()).configVersion)
        assertEquals(TokenPair("account-access", "account-refresh"), store.load())
        assertEquals(SessionState.SignedIn, session.state.value)
        server.takeRequest()
        assertEquals("Bearer account-access", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun listenersSeeEverySessionChangeIncludingAccountSwitches() {
        val calls = AtomicInteger()
        val handle = session.addListener { calls.incrementAndGet() }
        session.signedIn(TokenPair("a", "b"))
        session.signedIn(TokenPair("c", "d")) // SignedIn -> SignedIn: the StateFlow conflates it, the listener does not
        session.signedOut(SignedOutReason.SIGNED_OUT)
        assertEquals(3, calls.get())
        handle.close()
        session.signedIn(TokenPair("e", "f"))
        assertEquals(3, calls.get())
    }

    @Test
    fun anonymousAuthSendsNoBearerAndNeverRefreshes() {
        dispatch { invalidToken() }
        val c = clients()
        val e = failure { runBlocking { apiCall { c.anonymousAuth.createAttestationNonce() } } }
        assertEquals(401, e.status)
        assertNull(server.takeRequest().getHeader("Authorization"))
        assertEquals(1, server.requestCount)
        assertEquals(TokenPair("old-access", "old-refresh"), store.load())
    }
}
