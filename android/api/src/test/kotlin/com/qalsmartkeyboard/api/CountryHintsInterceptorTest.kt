package com.qalsmartkeyboard.api

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** ADR 0045 client hints: valid ISO codes only, billing paths only, nothing else about the device. */
class CountryHintsInterceptorTest {
    private lateinit var server: MockWebServer
    private var hints = CountryHints.NONE

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After fun tearDown() = server.shutdown()

    private fun clients(source: CountryHintSource? = CountryHintSource { hints }, channel: DistributionChannel? = DistributionChannel.DIRECT_DOWNLOAD) =
        ApiClients(
            TransportConfig(
                baseUrl = "http://localhost:${server.port}",
                client = ClientInfo("2.0.3", ClientSurface.COMPANION, distributionChannel = channel),
                countryHints = source,
            ),
            SessionManager(InMemoryTokenStore(TokenPair("access-token", "refresh-token"))),
        )

    private fun get(path: String, c: ApiClients = clients(), vararg headers: Pair<String, String>): RecordedRequest {
        server.enqueue(MockResponse().setBody("{}"))
        val b = Request.Builder().url("http://localhost:${server.port}$path")
        headers.forEach { b.header(it.first, it.second) }
        c.okHttpClient.newCall(b.build()).execute().close()
        return server.takeRequest()
    }

    @Test fun bothHintsAreSentUpperCasedOnBillingCalls() {
        hints = CountryHints(network = " et ", sim = "et")
        val r = get("/api/v1/billing/options?clientPlatform=ANDROID&distributionChannel=DIRECT_DOWNLOAD")
        assertEquals("ET", r.getHeader("X-Qal-Network-Country"))
        assertEquals("ET", r.getHeader("X-Qal-Sim-Country"))
        // The client headers are still there; nothing else about the device is added.
        assertEquals("ANDROID", r.getHeader("X-Client-Platform"))
        assertEquals("COMPANION", r.getHeader("X-Client-Surface"))
        assertEquals(
            setOf("x-qal-network-country", "x-qal-sim-country", "x-client-platform", "x-app-version", "x-client-surface", "authorization"),
            r.headers.names().map { it.lowercase() }.toSet() - setOf("host", "connection", "accept-encoding", "user-agent"),
        )
    }

    @Test fun aMissingHintIsLeftOut() {
        hints = CountryHints(network = null, sim = "ke") // Wi-Fi only, no network registration
        val r = get("/api/v1/billing/orders")
        assertNull(r.getHeader("X-Qal-Network-Country"))
        assertEquals("KE", r.getHeader("X-Qal-Sim-Country"))
        hints = CountryHints(network = "", sim = "")      // no SIM, airplane mode
        val none = get("/api/v1/billing/orders")
        assertNull(none.getHeader("X-Qal-Network-Country"))
        assertNull(none.getHeader("X-Qal-Sim-Country"))
    }

    @Test fun invalidHintsAreNeverSent() {
        for (bad in listOf("XX", "e", "eth", "E1", "ZZ", "  ", "ét", "310")) {
            hints = CountryHints(network = bad, sim = bad)
            val r = get("/api/v1/billing/options")
            assertNull(bad, r.getHeader("X-Qal-Network-Country"))
            assertNull(bad, r.getHeader("X-Qal-Sim-Country"))
        }
    }

    @Test fun aCallerValueIsReplacedOrRemoved() {
        hints = CountryHints(network = "us", sim = null)
        val r = get("/api/v1/billing/options", clients(), "X-Qal-Network-Country" to "ET", "X-Qal-Sim-Country" to "ET")
        assertEquals("US", r.getHeader("X-Qal-Network-Country"))
        assertNull(r.getHeader("X-Qal-Sim-Country"))
    }

    @Test fun otherCallsCarryNoHints() {
        hints = CountryHints(network = "ET", sim = "ET")
        for (path in listOf("/api/v1/config", "/api/v1/me/entitlements", "/api/v1/me/subscriptions", "/api/v1/ai/translate")) {
            val r = get(path)
            assertNull(path, r.getHeader("X-Qal-Network-Country"))
            assertNull(path, r.getHeader("X-Qal-Sim-Country"))
        }
    }

    @Test fun aFailingSourceSendsNoHintsAndTheCallGoesThrough() {
        val r = get("/api/v1/billing/options", clients(CountryHintSource { throw SecurityException("no telephony") }))
        assertNull(r.getHeader("X-Qal-Network-Country"))
        assertEquals("ANDROID", r.getHeader("X-Client-Platform"))
    }

    @Test fun withoutASourceNothingIsAdded() {
        val r = get("/api/v1/billing/options", clients(source = null))
        assertNull(r.getHeader("X-Qal-Network-Country"))
        assertNull(r.getHeader("X-Qal-Sim-Country"))
    }

    @Test fun normaliseAcceptsOnlyIsoAlpha2() {
        assertEquals("ET", CountryHintsInterceptor.normalise("et"))
        assertEquals("GB", CountryHintsInterceptor.normalise(" gb\n"))
        assertNull(CountryHintsInterceptor.normalise(null))
        assertNull(CountryHintsInterceptor.normalise("UK")) // not ISO 3166-1
        assertNull(CountryHintsInterceptor.normalise("ETH"))
    }

    @Test fun theFlavourChannelMapsToTheBackendValues() {
        assertEquals(DistributionChannel.GOOGLE_PLAY, DistributionChannel.ofFlavor("play"))
        assertEquals(DistributionChannel.DIRECT_DOWNLOAD, DistributionChannel.ofFlavor("direct"))
        assertNull(DistributionChannel.ofFlavor("other"))
        assertEquals("GOOGLE_PLAY" to "ANDROID_GOOGLE_PLAY", DistributionChannel.GOOGLE_PLAY.let { it.apiValue to it.salesChannel })
        assertEquals("DIRECT_DOWNLOAD" to "ANDROID_DIRECT", DistributionChannel.DIRECT_DOWNLOAD.let { it.apiValue to it.salesChannel })
    }
}
