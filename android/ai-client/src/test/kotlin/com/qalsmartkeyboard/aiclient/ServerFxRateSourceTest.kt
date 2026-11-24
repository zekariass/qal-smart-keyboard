package com.qalsmartkeyboard.aiclient

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** T8.3: the currency converter's source reads `GET /tools/fx-rates` and maps it, or answers null and lets the cache keep its copy. */
class ServerFxRateSourceTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After fun tearDown() = server.shutdown()

    private fun source(timeoutMs: Long = 2_000) = ServerFxRateSource(OkHttpClient(), server.url("/").toString(), timeoutMs)

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private val body = """{"base":"USD","asOf":"2026-11-23T06:00:00Z","rates":{"ETB":131.5,"EUR":0.92,"JPY":150,"BAD":-1,"XX":0,"TOOLONG":2},"source":"seed","stale":false}"""

    @Test fun mapsTheSnapshotAndSendsNoCredentials() {
        server.enqueue(json(body))
        val s = source().fetch()!!
        assertEquals("USD", s.base)
        assertEquals(java.time.OffsetDateTime.parse("2026-11-23T06:00:00Z").toInstant().toEpochMilli(), s.asOfMillis)
        assertEquals(mapOf("ETB" to 131.5, "EUR" to 0.92, "JPY" to 150.0), s.rates)
        assertFalse(s.stale)
        val r = server.takeRequest()
        assertEquals("GET", r.method)
        assertEquals("/api/v1/tools/fx-rates", r.path)
        assertNull(r.getHeader("Authorization"))
    }

    @Test fun theServersStaleFlagIsKept() {
        server.enqueue(json(body.replace("\"stale\":false", "\"stale\":true")))
        assertTrue(source().fetch()!!.stale)
    }

    @Test fun noSnapshotOnTheServerIsNull() {
        server.enqueue(json("""{"code":"PROVIDER_UNAVAILABLE","message":"no snapshot"}""", 503))
        assertNull(source().fetch())
    }

    @Test fun otherStatusesAndMalformedBodiesAreNull() {
        server.enqueue(json("{}", 401))
        assertNull(source().fetch())
        server.enqueue(json("not json"))
        assertNull(source().fetch())
        server.enqueue(json("""{"base":"USD","asOf":"yesterday","rates":{"ETB":1}}"""))
        assertNull(source().fetch())
        server.enqueue(json("""{"base":"USD","asOf":"2026-11-23T06:00:00Z","rates":{}}"""))
        assertNull(source().fetch())
    }

    @Test fun aNetworkErrorIsNull() {
        val url = server.url("/").toString()
        server.shutdown()
        assertNull(ServerFxRateSource(OkHttpClient(), url, 1_000).fetch())
    }

    @Test fun aSlowServerTimesOutToNull() {
        server.enqueue(json(body).setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val t0 = System.currentTimeMillis()
        assertNull(source(timeoutMs = 400).fetch())
        assertTrue("took ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 3_000)
    }

    @Test fun anInvalidBaseUrlIsNull() {
        assertNull(ServerFxRateSource(OkHttpClient(), "not a url").fetch())
    }
}
