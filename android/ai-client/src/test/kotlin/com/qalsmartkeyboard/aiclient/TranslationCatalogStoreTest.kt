package com.qalsmartkeyboard.aiclient

import com.qalsmartkeyboard.ime.ai.AiDestination
import com.qalsmartkeyboard.ime.ai.TranslationCatalog
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** ADR 0040: the device's copy of the translation language catalogue: fetch, ETag revalidation, cache, fallback. */
class TranslationCatalogStoreTest {
    private lateinit var server: MockWebServer
    private lateinit var rig: Rig
    private val dir: File = Files.createTempDirectory("qal-catalog").toFile().also { it.deleteOnExit() }
    private val file = File(dir, "translation-catalog.json")
    private var now = 1_000_000L

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        rig = Rig(server)
    }

    @After fun tearDown() = server.shutdown()

    private fun store() = TranslationCatalogStore(file, now = { now }, minIntervalMs = 60_000)

    private val body = """
        {"generatedAt":"2026-11-19T10:00:00Z","items":[{"featureCode":"TRANSLATE","qualityTier":"BETA","execution":"SERVER"}],
         "translationLanguages":[
          {"code":"am","englishName":"Amharic","nativeName":"አማርኛ","scriptCode":"Ethi","writingDirection":"LTR","asSource":true,"asDestination":true,"supportsAutoDetection":true,"qualityTier":"BETA","isTypingLanguage":true},
          {"code":"en","englishName":"English","nativeName":"English","writingDirection":"LTR","asSource":true,"asDestination":true,"supportsAutoDetection":true,"isTypingLanguage":true},
          {"code":"fr","englishName":"French","nativeName":"Français","writingDirection":"LTR","asSource":true,"asDestination":true,"supportsAutoDetection":true,"isTypingLanguage":false},
          {"code":"he","englishName":"Hebrew","nativeName":"עברית","scriptCode":"Hebr","writingDirection":"RTL","asSource":true,"asDestination":false},
          {"code":"BAD CODE","englishName":"x","asSource":true,"asDestination":true},
          {"code":"xx","englishName":"Nothing","asSource":false,"asDestination":false}
         ]}
    """.trimIndent()

    private fun ok(etag: String = "\"v1\"") = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setHeader("ETag", etag).setBody(body)

    @Test fun beforeAnyFetchTheBundledFiveLanguagesApply() {
        val s = store()
        s.load()
        assertSame(TranslationCatalog.BUNDLED, s.catalog)
        assertNull(s.etag)
    }

    @Test fun aFetchReplacesTheCatalogueAndIsKeptOnTheDevice() {
        server.enqueue(ok())
        val s = store()
        assertTrue(s.refresh(rig.clients.okHttpClient, rig.baseUrl))
        val r = server.takeRequest()
        assertEquals("/api/v1/ai/capabilities", r.path)
        assertNull("no tag yet", r.getHeader("If-None-Match"))
        assertEquals(listOf("am", "en", "fr", "he"), s.catalog.languages.map { it.code }) // invalid rows dropped
        assertTrue(s.catalog.fromServer)
        assertTrue(s.catalog.find("he")!!.rtl)
        assertFalse(s.catalog.isDestination("he"))
        assertEquals("Français", s.catalog.find("fr")!!.nativeName)
        assertFalse(s.catalog.find("fr")!!.typing)
        // a new process reads the same list (and the tag) from the file
        val again = store()
        again.load()
        assertEquals(s.catalog, again.catalog)
        assertEquals("\"v1\"", again.etag)
    }

    @Test fun refreshesAreThrottledAndRevalidateWithTheEtag() {
        server.enqueue(ok())
        val s = store()
        s.refresh(rig.clients.okHttpClient, rig.baseUrl)
        server.takeRequest()
        assertFalse("too soon: no request", s.refresh(rig.clients.okHttpClient, rig.baseUrl))
        assertEquals(1, server.requestCount)
        now += 120_000
        server.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", "\"v1\""))
        assertFalse("not modified", s.refresh(rig.clients.okHttpClient, rig.baseUrl))
        assertEquals("\"v1\"", server.takeRequest().getHeader("If-None-Match"))
        assertEquals(4, s.catalog.languages.size)
        // forced (another account): no tag, a fresh answer
        server.enqueue(ok("\"v2\"").setBody("""{"generatedAt":"2026-11-19T10:00:00Z","items":[],"translationLanguages":[]}"""))
        assertTrue(s.refresh(rig.clients.okHttpClient, rig.baseUrl, force = true))
        assertNull(server.takeRequest().getHeader("If-None-Match"))
        assertSame("an empty list: the bundled one", TranslationCatalog.BUNDLED, s.catalog)
    }

    @Test fun failuresKeepTheCopy() {
        server.enqueue(ok())
        val s = store()
        s.refresh(rig.clients.okHttpClient, rig.baseUrl)
        now += 120_000
        server.enqueue(MockResponse().setResponseCode(503))
        assertFalse(s.refresh(rig.clients.okHttpClient, rig.baseUrl))
        now += 120_000
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
        assertFalse(s.refresh(rig.clients.okHttpClient, rig.baseUrl))
        server.shutdown()
        now += 120_000
        assertFalse(s.refresh(rig.clients.okHttpClient, rig.baseUrl))
        assertEquals(4, s.catalog.languages.size)
        // a corrupt file is ignored
        file.writeText("{broken")
        val fresh = store()
        fresh.load()
        assertSame(TranslationCatalog.BUNDLED, fresh.catalog)
    }

    @Test fun theActionsHandTheCatalogueToTheKeyboardAndListItsDestinations() {
        server.enqueue(ok())
        val conn = FakeConnectivity()
        val store = store()
        val entitlements = EntitlementManager(rig.clients.okHttpClient, rig.baseUrl, FakeSession(), EntitlementTokenVerifier(PinnedKeys.NONE), EntitlementTokenStore(rig.files))
        val a = ServerAiActions(
            rig.clients.okHttpClient, rig.baseUrl, FakeSession(), conn, { emptyList<com.qalsmartkeyboard.sync.DestinationProfile>() to emptyList() },
            entitlements, { false }, Executor { it.run() }, catalogStore = store,
        )
        assertSame(TranslationCatalog.BUNDLED, a.translationCatalog())

        a.refreshCatalog()
        assertEquals(listOf("am", "en", "fr", "he"), a.translationCatalog().languages.map { it.code })
        // plain destinations: the keyboard's first, then the rest of the catalogue (not "he": no destination)
        assertEquals(listOf("en", "am", "fr"), a.destinations().map(AiDestination::language))
        conn.online = false
        now += 1_000_000
        a.refreshCatalog()
        assertEquals("offline: no request", 1, server.requestCount)
    }
}
