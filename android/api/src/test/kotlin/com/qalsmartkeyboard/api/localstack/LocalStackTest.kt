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
import com.qalsmartkeyboard.api.generated.models.OtpStartRequest
import com.qalsmartkeyboard.api.generated.models.OtpVerifyRequest
import com.qalsmartkeyboard.api.signedIn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
 * Flow: GET /config, OTP start (needs the SMS mock, see android/api/README.md), OTP verify with
 * a wrong code (expects the OTP_INVALID Problem), and with the real code when one is passed.
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
            clients.auth.startPhoneOtp(OtpStartRequest(phoneNumber = "+251911234567"))
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

        otpCode?.let { code ->
            val s = apiCall {
                clients.auth.verifyPhoneOtp(
                    OtpVerifyRequest(challengeId = challenge.challengeId, code = code, ageConfirmed = true),
                )
            }
            session.signedIn(s)
            assertEquals(SessionState.SignedIn, session.state.value)
        }
    }
}
