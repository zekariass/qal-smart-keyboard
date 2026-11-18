package com.qalsmartkeyboard.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProblemParserTest {
    @Test
    fun parsesFullProblem() {
        val p = ProblemParser.parse(
            """{"type":"https://x/p","title":"T","status":409,"detail":"d","instance":"/i","code":"REVISION_CONFLICT",
               "correlationId":"c1","currentRevision":4,"errors":[{"field":"a","code":"X","message":"m"},{"nope":1}]}""",
            409,
            "hdr",
        )
        assertEquals(409, p.status)
        assertEquals("REVISION_CONFLICT", p.code)
        assertEquals("T", p.title)
        assertEquals("d", p.detail)
        assertEquals("c1", p.correlationId)
        assertEquals(listOf(FieldError("a", "X", "m")), p.fieldErrors)
        assertEquals(setOf("currentRevision"), p.extensions.keys)
    }

    @Test
    fun fallsBackToHeaderAndHttpStatus() {
        val p = ProblemParser.parse("""{"code":"NOT_FOUND"}""", 404, "hdr")
        assertEquals(404, p.status)
        assertEquals("hdr", p.correlationId)
        assertNull(p.title)
    }

    @Test
    fun garbageBecomesUnknown() {
        for (body in listOf(null, "", "   ", "<html>", "[1,2]", "\"str\"", "{")) {
            val p = ProblemParser.parse(body, 500, null)
            assertEquals(ProblemParser.UNKNOWN_CODE, p.code)
            assertEquals(500, p.status)
        }
    }

    @Test
    fun tokenPairNeverPrintsTokens() {
        val s = TokenPair("secret-access", "secret-refresh").toString()
        assertFalse(s.contains("secret"))
    }

    @Test
    fun cleartextOnlyForLocalHosts() {
        val info = ClientInfo("1.0", ClientSurface.COMPANION)
        TransportConfig("http://10.0.2.2:8080", info)
        TransportConfig("http://localhost:8080", info)
        TransportConfig("https://api.example.com", info)
        val e = runCatching { TransportConfig("http://api.example.com", info) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    @Test
    fun pinningIsOffByDefaultAndOnlyAppliesWhenConfigured() {
        val info = ClientInfo("1.0", ClientSurface.COMPANION)
        val store = InMemoryTokenStore()
        val off = ApiClients(TransportConfig("https://api.example.com", info), SessionManager(store))
        assertTrue(off.okHttpClient.certificatePinner.pins.isEmpty())
        val pin = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        val on = ApiClients(
            TransportConfig("https://api.example.com", info, certificatePins = mapOf("api.example.com" to listOf(pin))),
            SessionManager(store),
        )
        assertEquals(1, on.okHttpClient.certificatePinner.pins.size)
        // loopback never pins
        val local = ApiClients(
            TransportConfig("http://localhost:8080", info, certificatePins = mapOf("localhost" to listOf(pin))),
            SessionManager(store),
        )
        assertTrue(local.okHttpClient.certificatePinner.pins.isEmpty())
        val bad = runCatching {
            TransportConfig("https://api.example.com", info, certificatePins = mapOf("api.example.com" to listOf("md5/x")))
        }.exceptionOrNull()
        assertTrue(bad is IllegalArgumentException)
    }
}
