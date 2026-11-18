package com.qalsmartkeyboard.aiclient

import com.qalsmartkeyboard.api.ApiClients
import com.qalsmartkeyboard.api.ClientInfo
import com.qalsmartkeyboard.api.ClientSurface
import com.qalsmartkeyboard.api.InMemoryTokenStore
import com.qalsmartkeyboard.api.SessionManager
import com.qalsmartkeyboard.api.TokenPair
import com.qalsmartkeyboard.api.TransportConfig
import com.qalsmartkeyboard.data.secure.AesGcmCipher
import com.qalsmartkeyboard.data.secure.EncryptedFileStore
import com.qalsmartkeyboard.ime.ai.AiOutcome
import com.qalsmartkeyboard.sync.AppRule
import com.qalsmartkeyboard.sync.DestinationProfile
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.KeyGenerator
import okhttp3.mockwebserver.MockWebServer

class FakeConnectivity(var online: Boolean = true) : Connectivity {
    private val listeners = ArrayList<() -> Unit>()
    override fun isOnline() = online
    override fun addListener(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }
    fun set(value: Boolean) { online = value; listeners.toList().forEach { it() } }
}

class FakeSession(var signedIn: Boolean = true, var sub: String? = TestTokens.SUB, var did: String? = TestTokens.DEVICE) : SessionInfo {
    override fun signedIn() = signedIn
    override fun subject() = sub
    override fun deviceId() = did
}

/** Real [ApiClients] (bearer, client headers, authenticator) against a [MockWebServer]. */
class Rig(
    val server: MockWebServer,
    surface: ClientSurface = ClientSurface.KEYBOARD,
    callTimeoutMs: Long = 8_000,
) {
    val session = SessionManager(InMemoryTokenStore(TokenPair("access-token", "refresh-token")))
    val clients = ApiClients(
        TransportConfig(
            baseUrl = "http://localhost:${server.port}",
            client = ClientInfo("2.0.3", surface),
            callTimeoutMs = callTimeoutMs,
        ),
        session,
    )
    val baseUrl: String get() = "http://localhost:${server.port}"
    val dir: File = Files.createTempDirectory("qal-ai").toFile().also { it.deleteOnExit() }
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    val files = EncryptedFileStore(File(dir, "enc"), AesGcmCipher { key })
}

/** Collects the one outcome of a call. */
class Waiter {
    private val latch = CountDownLatch(1)
    private val ref = AtomicReference<AiOutcome>()
    val callback: (AiOutcome) -> Unit = { ref.set(it); latch.countDown() }
    fun await(ms: Long = 5_000): AiOutcome {
        check(latch.await(ms, TimeUnit.MILLISECONDS)) { "no outcome" }
        return ref.get()
    }
    fun none(ms: Long = 600): Boolean = !latch.await(ms, TimeUnit.MILLISECONDS)
}

fun profile(id: String, label: String, lang: String, sort: Int = 100, serverId: String? = null) =
    DestinationProfile(localId = id, serverId = serverId, label = label, destinationLanguage = lang, formality = "HONORIFIC", sortOrder = sort)

fun rule(pkg: String, profileId: String? = null, lang: String? = null) = AppRule(pkg, null, profileId, lang)
