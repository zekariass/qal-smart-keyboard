package com.qalsmartkeyboard.api

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import okhttp3.Request
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reliability of the transport (ADR 0040, B): an unreachable server is told apart from a slow one, the connect timeout
 * is short and separate from the call timeout, and the emulator-only base URL is recognised.
 */
class TransportReliabilityTest {
    @Test fun connectFailuresAreUnreachableAndStillNetworkFailures() {
        for (e in listOf(ConnectException("refused"), UnknownHostException("no.such.host"), NoRouteToHostException("no route"),
            SocketTimeoutException("connect timed out"), SocketTimeoutException("failed to connect to /10.0.2.2 (port 8080) after 5000ms"))) {
            val mapped = ApiErrors.map(e)
            assertTrue(e.message, mapped is ApiError.Unreachable)
            assertTrue("callers that only know Network keep working", mapped is ApiError.Network)
        }
    }

    @Test fun aSlowAnswerIsATimeoutAndOtherIoIsNetwork() {
        assertTrue(ApiErrors.map(SocketTimeoutException("timeout")) is ApiError.Timeout)
        assertTrue(ApiErrors.map(SocketTimeoutException("Read timed out")) is ApiError.Timeout)
        assertTrue(ApiErrors.map(InterruptedIOException("timeout")) is ApiError.Timeout)
        val other = ApiErrors.map(IOException("unexpected end of stream"))
        assertTrue(other is ApiError.Network)
        assertFalse(other is ApiError.Unreachable)
    }

    @Test fun theConnectTimeoutIsShortAndSeparateFromTheCallTimeout() {
        val config = TransportConfig("http://localhost:8080", ClientInfo("2.0.3", ClientSurface.COMPANION))
        assertEquals(5_000L, config.connectTimeoutMs)
        assertEquals(60_000L, config.callTimeoutMs)
        val clients = ApiClients(config, SessionManager(InMemoryTokenStore()))
        assertEquals(5_000, clients.okHttpClient.connectTimeoutMillis)
        assertEquals(60_000, clients.okHttpClient.callTimeoutMillis)
    }

    @Test fun aRefusedConnectionFailsFastAsUnreachable() {
        val server = MockWebServer().also { it.start() }
        val port = server.port
        server.shutdown()
        val clients = ApiClients(TransportConfig("http://localhost:$port", ClientInfo("2.0.3", ClientSurface.COMPANION)), SessionManager(InMemoryTokenStore()))
        val t0 = System.nanoTime()
        val e = runCatching { clients.okHttpClient.newCall(Request.Builder().url("http://localhost:$port/x").build()).execute() }.exceptionOrNull()
        assertTrue(ApiErrors.map(e!!) is ApiError.Unreachable)
        assertTrue(System.nanoTime() - t0 < 5_000_000_000L)
    }

    @Test fun theEmulatorOnlyHostIsRecognised() {
        assertTrue(TransportConfig.isEmulatorOnly("http://10.0.2.2:8080"))
        assertTrue(TransportConfig.isEmulatorOnly("http://10.0.2.2:8080/"))
        assertFalse(TransportConfig.isEmulatorOnly("http://localhost:8080"))
        assertFalse(TransportConfig.isEmulatorOnly("https://api.qalsmartkeyboard.example"))
        assertFalse(TransportConfig.isEmulatorOnly("not a url"))
    }
}
