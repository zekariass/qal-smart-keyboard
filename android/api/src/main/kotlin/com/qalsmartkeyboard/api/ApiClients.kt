package com.qalsmartkeyboard.api

import com.qalsmartkeyboard.api.generated.apis.AuthApi
import com.qalsmartkeyboard.api.generated.apis.ConfigApi
import com.qalsmartkeyboard.api.generated.models.AuthSession
import okhttp3.Call
import okhttp3.CertificatePinner
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Transport settings. [certificatePins] is the optional pinning hook of ADR 0016: the decision
 * is NOT to pin the API (SD 18), so production config leaves it empty. When non-empty it maps a
 * host pattern to `sha256/...` SPKI pins (OkHttp [CertificatePinner]); it is ignored for
 * loopback hosts.
 */
data class TransportConfig(
    val baseUrl: String,
    val client: ClientInfo,
    val certificatePins: Map<String, List<String>> = emptyMap(),
    /** Separate from the call timeouts: an unreachable server fails fast instead of stalling the UI (ADR 0040, B). */
    val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    val readTimeoutMs: Long = 30_000,
    val writeTimeoutMs: Long = 30_000,
    val callTimeoutMs: Long = 60_000,
    val logger: CallLogger = CallLogger { _, _, _ -> },
) {
    init {
        val url = baseUrl.toHttpUrl()
        require(url.isHttps || url.host in LOCAL_HOSTS) { "plain http is only allowed for the local stack" }
        certificatePins.forEach { (host, pins) ->
            require(host.isNotBlank() && pins.isNotEmpty() && pins.all { it.startsWith("sha256/") }) {
                "pins must be sha256/ SPKI hashes"
            }
        }
    }

    companion object {
        /** Hosts that may use cleartext: the local stack (`10.0.2.2` is the emulator's host loopback). */
        val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

        /** The emulator's alias for the development machine: unreachable from a physical phone. */
        const val EMULATOR_HOST = "10.0.2.2"

        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000L

        /**
         * True when [baseUrl] points at the emulator-only host [EMULATOR_HOST]. A build like that on a physical phone
         * can never reach the server (every AI call used to hang until its timeout); see `android/README.md` for
         * building against a phone (`-Pqal.apiBaseUrl=http://localhost:8080` with `adb reverse`).
         */
        fun isEmulatorOnly(baseUrl: String): Boolean =
            try {
                baseUrl.toHttpUrl().host == EMULATOR_HOST
            } catch (e: IllegalArgumentException) {
                false
            }
    }
}

/**
 * Wires the OkHttp stack and the generated APIs for one process.
 *
 * Main client interceptors, in order: client headers, bearer, correlation-id capture; the
 * [TokenAuthenticator] handles 401. Refresh calls use a second client with the same headers and
 * logging but neither bearer nor authenticator, so a refresh can never recurse. [anonymousAuth] is the auth API over
 * that bearer-free client: sign-in calls that must not carry the stored session (ADR 0035).
 */
class ApiClients(val config: TransportConfig, val session: SessionManager) {

    private val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(config.readTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(config.writeTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(config.callTimeoutMs, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .apply {
            val url = config.baseUrl.toHttpUrl()
            if (config.certificatePins.isNotEmpty() && url.host !in TransportConfig.LOCAL_HOSTS) {
                val pinner = CertificatePinner.Builder()
                config.certificatePins.forEach { (host, pins) -> pinner.add(host, *pins.toTypedArray()) }
                certificatePinner(pinner.build())
            }
        }
        .build()

    private val refreshClient: OkHttpClient = base.newBuilder()
        .addInterceptor(ClientHeadersInterceptor(config.client))
        .addInterceptor(CorrelationIdInterceptor(config.logger))
        .build()

    val okHttpClient: OkHttpClient = base.newBuilder()
        .addInterceptor(ClientHeadersInterceptor(config.client))
        .addInterceptor(BearerInterceptor(session))
        .addInterceptor(CorrelationIdInterceptor(config.logger))
        .authenticator(TokenAuthenticator(session, AuthApi(config.baseUrl, refreshClient)))
        .build()

    /** Any generated API class over the main client: `clients.api(::AuthApi)`. */
    fun <A> api(create: (String, Call.Factory) -> A): A = create(config.baseUrl, okHttpClient)

    val auth: AuthApi by lazy { api(::AuthApi) }

    /**
     * The auth API without the session: no `Authorization` header and no refresh on 401. For sign-in calls that must
     * not present the stored token (ADR 0035: only a guest session meant for linking is sent on sign-in).
     */
    val anonymousAuth: AuthApi by lazy { AuthApi(config.baseUrl, refreshClient) }

    /** The client behind [anonymousAuth] (client headers and logging, no bearer, no refresh) for hand-written sign-in calls. */
    val anonymousClient: OkHttpClient get() = refreshClient
    val appConfig: ConfigApi by lazy { api(::ConfigApi) }
}

/** Stores the tokens of a sign-in, guest creation or refresh response and marks the session signed in. */
fun SessionManager.signedIn(session: AuthSession) = signedIn(TokenPair(session.accessToken, session.refreshToken))
