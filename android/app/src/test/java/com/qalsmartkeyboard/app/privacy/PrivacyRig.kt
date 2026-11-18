package com.qalsmartkeyboard.app.privacy

import com.qalsmartkeyboard.api.ApiClients
import com.qalsmartkeyboard.api.ClientInfo
import com.qalsmartkeyboard.api.ClientSurface
import com.qalsmartkeyboard.api.InMemoryTokenStore
import com.qalsmartkeyboard.api.SessionManager
import com.qalsmartkeyboard.api.TokenPair
import com.qalsmartkeyboard.api.TransportConfig
import com.qalsmartkeyboard.sync.SyncHttp
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The real `:api` client (bearer, headers, authenticator) and [PrivacyApi] against a
 * `MockWebServer` whose answers a test sets with [route]. Requests are recorded in [requests].
 */
class PrivacyRig {
    val server = MockWebServer()
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    var route: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404) }

    lateinit var api: PrivacyApi
    lateinit var clients: ApiClients

    fun start(): PrivacyRig {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return route(request)
            }
        }
        server.start()
        clients = ApiClients(
            TransportConfig(
                baseUrl = "http://localhost:${server.port}",
                client = ClientInfo("1.0", ClientSurface.COMPANION),
                readTimeoutMs = 5_000,
            ),
            SessionManager(InMemoryTokenStore(TokenPair("access-token", "refresh-token"))),
        )
        api = PrivacyApi(SyncHttp(clients), clients)
        return this
    }

    fun stop() = runCatching { server.shutdown() }

    /** Requests as "METHOD path" (without the `/api/v1` prefix and the query). */
    fun calls(): List<String> = requests.map { it.method + " " + it.path!!.removePrefix("/api/v1").substringBefore('?') }

    fun bodyOf(method: String, path: String): String =
        requests.last { it.method == method && it.path!!.removePrefix("/api/v1").substringBefore('?') == path }.body.clone().readUtf8()

    companion object {
        fun json(code: Int, body: String, vararg headers: Pair<String, String>): MockResponse =
            MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
                .also { r -> headers.forEach { r.setHeader(it.first, it.second) } }

        fun problem(code: Int, errorCode: String, extra: String = "", vararg headers: Pair<String, String>): MockResponse =
            json(code, """{"type":"about:blank","title":"t","status":$code,"code":"$errorCode","correlationId":"c-1"$extra}""", *headers)

        fun consent(code: String, granted: Boolean, at: String = "2026-11-18T08:00:00Z") =
            """{"id":"0b7e8a56-1a79-4cfb-9a8e-7b8a1f4d2c10","consentCode":"$code","consentVersion":"1","isGranted":$granted,"recordedAt":"$at"}"""

        fun item(id: String, favorite: Boolean = false) =
            """{"id":"$id","featureCode":"TRANSLATE","sourceLanguage":"en","destinationLanguage":"am","inputText":"hello","outputText":"ሰላም","favorite":$favorite,"createdAt":"2026-11-18T08:00:00Z","expiresAt":"2026-12-18T08:00:00Z"}"""

        fun export(id: String, status: String, extra: String = "") =
            """{"id":"$id","status":"$status","requestedAt":"2026-11-18T08:00:00Z"$extra}"""

        const val EXPORT_ID = "5b5b5b5b-1a79-4cfb-9a8e-7b8a1f4d2c10"
        const val ITEM_1 = "11111111-1111-4111-8111-111111111111"
        const val ITEM_2 = "22222222-2222-4222-8222-222222222222"
    }
}
