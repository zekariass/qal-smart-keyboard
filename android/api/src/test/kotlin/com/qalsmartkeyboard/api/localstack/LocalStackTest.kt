package com.qalsmartkeyboard.api.localstack

import com.qalsmartkeyboard.api.ApiClients
import com.qalsmartkeyboard.api.ApiError
import com.qalsmartkeyboard.api.ClientInfo
import com.qalsmartkeyboard.api.ClientSurface
import com.qalsmartkeyboard.api.InMemoryTokenStore
import com.qalsmartkeyboard.api.SessionManager
import com.qalsmartkeyboard.api.SessionState
import com.qalsmartkeyboard.api.TransportConfig
import com.qalsmartkeyboard.api.apiCall
import com.qalsmartkeyboard.api.generated.apis.PreferencesApi
import com.qalsmartkeyboard.api.generated.models.OtpStartRequest
import com.qalsmartkeyboard.api.generated.models.OtpVerifyRequest
import com.qalsmartkeyboard.api.generated.models.RefreshRequest
import com.qalsmartkeyboard.api.signedIn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Optional, never part of `test`: talks to a backend you started yourself.
 *
 *     ./gradlew :api:localStackTest -PlocalStack=true [-PlocalStackUrl=http://localhost:8080]
 *                                   [-PlocalStackOtpCode=123456]
 *
 * (Android emulators reach the host at http://10.0.2.2:8080; this JVM test uses localhost.)
 * Start the backend with infra/scripts/run-backend-dev.sh: its dev SMS capture (ADR 0007 D28) lets this test read
 * the code itself. `-PlocalStackOtpCode` overrides that for a backend with another SMS setup.
 * Flow: GET /config, OTP start, OTP verify with a wrong code (expects the OTP_INVALID Problem), the real code
 * (session tokens), an authenticated GET /me/preferences, a refresh (rotated tokens) and the same call again.
 * The phone number is a fixed test number; nothing here logs codes, numbers or tokens.
 */
class LocalStackTest {
    private val baseUrl = System.getProperty("qal.localStack.url") ?: "http://localhost:8080"
    private val otpCode: String? = System.getProperty("qal.localStack.otpCode")
    private lateinit var clients: ApiClients
    private lateinit var session: SessionManager

    @Before
    fun setUp() {
        session = SessionManager(InMemoryTokenStore())
        clients = ApiClients(
            TransportConfig(baseUrl, ClientInfo(appVersion = "99.0.0", surface = ClientSurface.COMPANION)),
            session,
        )
    }

    @Test
    fun configAndOtpFlow() = runBlocking {
        val config = apiCall { clients.appConfig.getClientConfig() }
        assertTrue(config.minSupportedAppVersion.isNotBlank())

        val challenge = apiCall {
            clients.auth.startPhoneOtp(OtpStartRequest(phoneNumber = PHONE))
        }

        val wrong = runCatching {
            apiCall {
                clients.auth.verifyPhoneOtp(
                    OtpVerifyRequest(challengeId = challenge.challengeId, code = "000000", ageConfirmed = true),
                )
            }
        }.exceptionOrNull()
        // 000000 is wrong with probability 1 - 1e-6; accept the (absurd) success too.
        if (wrong != null) {
            assertTrue("was $wrong", wrong is ApiError.Http)
            assertEquals("OTP_INVALID", (wrong as ApiError.Http).code)
        }

        val code = otpCode ?: capturedCode(PHONE)
        val s = apiCall {
            clients.auth.verifyPhoneOtp(
                OtpVerifyRequest(challengeId = challenge.challengeId, code = code, ageConfirmed = true),
            )
        }
        assertTrue(s.accessToken.isNotBlank() && s.refreshToken.isNotBlank())
        session.signedIn(s)
        assertEquals(SessionState.SignedIn, session.state.value)

        val preferences = clients.api(::PreferencesApi)
        assertNotNull(apiCall { preferences.getPreferences() })

        val refreshed = apiCall { clients.auth.refreshSession(RefreshRequest(refreshToken = s.refreshToken)) }
        assertNotEquals(s.refreshToken, refreshed.refreshToken)
        session.signedIn(refreshed)
        assertNotNull(apiCall { preferences.getPreferences() })
        assertEquals(SessionState.SignedIn, session.state.value)
    }

    /** Reads the last OTP SMS from the backend's dev-only capture endpoint (never part of the API contract). */
    private fun capturedCode(phone: String): String {
        val url = "$baseUrl/dev/sms/last?phone=" + java.net.URLEncoder.encode(phone, "UTF-8")
        OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { r ->
            assertTrue(
                "dev SMS capture answered ${r.code}: start the backend with infra/scripts/run-backend-dev.sh " +
                    "or pass -PlocalStackOtpCode",
                r.isSuccessful,
            )
            return Json.parseToJsonElement(r.body!!.string()).jsonObject["code"]!!.jsonPrimitive.content
        }
    }

    private companion object {
        const val PHONE = "+251911234567"
    }
}
