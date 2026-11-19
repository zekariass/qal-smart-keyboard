package com.qalsmartkeyboard.aiclient

import com.qalsmartkeyboard.data.secure.AesGcmCipher
import com.qalsmartkeyboard.data.secure.EncryptedFileStore
import com.qalsmartkeyboard.ime.InputProfile
import com.qalsmartkeyboard.ime.ai.AiEditor
import com.qalsmartkeyboard.ime.ai.AiPicker
import com.qalsmartkeyboard.ime.ai.AiTaskKind
import com.qalsmartkeyboard.ime.ai.AiToolbarController
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import javax.crypto.KeyGenerator
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** ADR 0007 D40: no network on keyboard show, and the last-used destination per app. */
class ToolbarFollowUpsTest {
    private lateinit var server: MockWebServer
    private lateinit var rig: Rig
    private val direct = Executor { it.run() }
    private val paths = ArrayList<String>()

    @Before fun setUp() {
        server = MockWebServer().also { s ->
            s.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    synchronized(paths) { paths += request.path.orEmpty() }
                    return MockResponse().setResponseCode(500)
                }
            }
            s.start()
        }
        rig = Rig(server)
    }

    @After fun tearDown() = server.shutdown()

    private fun store(dir: File = Files.createTempDirectory("qal-last").toFile().also { it.deleteOnExit() }): Pair<File, EncryptedFileStore> {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return dir to EncryptedFileStore(dir, AesGcmCipher { key })
    }

    private fun actions(
        profiles: List<com.qalsmartkeyboard.sync.DestinationProfile> = listOf(profile("p1", "Mum", "am"), profile("p2", "Boss", "en", sort = 200)),
        rules: List<com.qalsmartkeyboard.sync.AppRule> = emptyList(),
        last: LastDestinationStore? = null,
    ): ServerAiActions {
        val client = rig.clients.okHttpClient
        val entitlements = EntitlementManager(client, rig.baseUrl, FakeSession(), EntitlementTokenVerifier(PinnedKeys.NONE), EntitlementTokenStore(rig.files))
        return ServerAiActions(client, rig.baseUrl, FakeSession(), FakeConnectivity(), { profiles to rules }, entitlements, { false }, direct, last)
    }

    private class NoEditor : AiEditor {
        override fun commitComposition() {}
        override fun selectedText(): String? = null
        override fun textBeforeCursor(n: Int): String? = null
        override fun replaceSelection(expected: String, replacement: String) = false
        override fun replaceBeforeCursor(expected: String, replacement: String) = false
        override fun insertAtCursor(text: String) = false
    }

    private fun controller(a: ServerAiActions) = AiToolbarController(a, NoEditor(), { it() }, {})

    // ---------------------------------------------------------------- no network on keyboard show

    @Test fun showingAndHidingTheKeyboardMakesZeroNetworkCalls() {
        val toolbar = controller(actions())
        repeat(50) { i ->
            toolbar.onFieldStarted(InputProfile.DEFAULT, "com.example.app$i") // the keyboard appears
            toolbar.onKeyboardHidden()
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun openingTheToolbarRefreshesAtMostOnceWithinTheCacheWindow() {
        val toolbar = controller(actions())
        toolbar.onFieldStarted(InputProfile.DEFAULT, "com.example.app")
        assertEquals(0, server.requestCount)
        toolbar.openPicker(AiPicker.DESTINATION)
        val afterFirst = server.requestCount
        assertTrue("plan state and token are fetched", afterFirst in 1..2)
        repeat(5) {
            toolbar.openPicker(AiPicker.TONE)
            toolbar.onTask(AiTaskKind.GRAMMAR) // no text in the editor: a local failure, no call
            toolbar.onKeyboardHidden()
            toolbar.onFieldStarted(InputProfile.DEFAULT, "com.example.app")
        }
        assertEquals(afterFirst, server.requestCount)
        assertFalse(paths.any { it.contains("/ai/") })
    }

    @Test fun theBackgroundWorkerRefreshIsThrottledTooAndSendsNoText() {
        val a = actions()
        a.refreshNow()
        val first = server.requestCount
        assertTrue(first in 1..2)
        a.refreshNow()
        assertEquals(first, server.requestCount) // quota TTL and token retry windows
    }

    // ---------------------------------------------------------------- last-used destination

    @Test fun rememberedDestinationIsTheDefaultOnlyForThatApp() {
        val (_, files) = store()
        val last = LastDestinationStore(files)
        val a = actions(last = last)
        assertEquals("p1", a.defaultDestination("com.chat").id) // first profile
        a.rememberDestination("com.chat", a.destinations().first { it.id == "p2" })
        assertEquals("p2", a.defaultDestination("com.chat").id)
        assertEquals("p1", a.defaultDestination("com.other").id)
        assertEquals("p1", a.defaultDestination(null).id)
        a.rememberDestination("com.mail", a.destinations().first { it.id == "lang:ti" })
        assertEquals("lang:ti", a.defaultDestination("com.mail").id)
    }

    @Test fun anAppRuleTakesPrecedenceOverTheRememberedDestination() {
        val (_, files) = store()
        val a = actions(rules = listOf(rule("com.chat", profileId = "p1"), rule("com.mail", lang = "ti")), last = LastDestinationStore(files))
        a.rememberDestination("com.chat", a.destinations().first { it.id == "p2" })
        a.rememberDestination("com.mail", a.destinations().first { it.id == "lang:en" })
        assertEquals("p1", a.defaultDestination("com.chat").id)
        assertEquals("lang:ti", a.defaultDestination("com.mail").id)
    }

    @Test fun aDisabledOrBrokenRuleFallsThroughToTheRememberedDestination() {
        val (_, files) = store()
        val rules = listOf(com.qalsmartkeyboard.sync.AppRule("com.chat", null, "p1", null, enabled = false), rule("com.mail", profileId = "gone"))
        val a = actions(rules = rules, last = LastDestinationStore(files))
        a.rememberDestination("com.chat", a.destinations().first { it.id == "p2" })
        a.rememberDestination("com.mail", a.destinations().first { it.id == "p2" })
        assertEquals("p2", a.defaultDestination("com.chat").id)
        assertEquals("p2", a.defaultDestination("com.mail").id)
    }

    @Test fun aRememberedProfileThatNoLongerExistsIsIgnored() {
        val (_, files) = store()
        val last = LastDestinationStore(files)
        last.remember("com.chat", "deleted-profile")
        last.remember("com.mail", "lang:xx") // not a language the keyboard offers
        val a = actions(last = last)
        assertEquals("p1", a.defaultDestination("com.chat").id)
        assertEquals("p1", a.defaultDestination("com.mail").id)
    }

    @Test fun theChoiceSurvivesARestartAndNoPackageNameIsWrittenInPlainText() {
        val (dir, files) = store()
        LastDestinationStore(files).remember("com.secret.dating.app", "p2")
        val reloaded = LastDestinationStore(files).also { it.load() }
        assertEquals("p2", reloaded.lastFor("com.secret.dating.app"))
        assertEquals(null, reloaded.lastFor("com.other"))
        val raw = dir.walkTopDown().filter { it.isFile }.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }
        assertTrue(raw.isNotEmpty())
        assertFalse(raw.contains("secret.dating"))
    }

    @Test fun theKeyIsDeviceLocalSoTheSameAppHashesDifferentlyOnAnotherStore() {
        val (_, f1) = store()
        val (_, f2) = store()
        val a = LastDestinationStore(f1).also { it.remember("com.chat", "p1") }
        val b = LastDestinationStore(f2).also { it.remember("com.chat", "p1") }
        // Different random keys: each store only answers for its own file.
        assertEquals("p1", a.lastFor("com.chat"))
        assertEquals("p1", b.lastFor("com.chat"))
        assertEquals(null, LastDestinationStore(f2).also { it.load() }.lastFor("com.other"))
    }

    @Test fun theStoreIsCapped() {
        val (_, files) = store()
        val last = LastDestinationStore(files)
        repeat(LastDestinationStore.MAX_APPS + 5) { last.remember("com.app$it", "p1") }
        assertEquals(null, last.lastFor("com.app0"))
        assertEquals("p1", last.lastFor("com.app${LastDestinationStore.MAX_APPS + 4}"))
    }

    @Test fun choosingADestinationInTheToolbarIsRememberedForTheSourceLanguage() {
        // ADR 0031: the direction control remembers the pair per source language (device-local settings), in every app;
        // an enabled app rule still comes first.
        val (_, files) = store()
        val a = actions(rules = listOf(rule("com.mail", lang = "ti")), last = LastDestinationStore(files))
        val prefs = com.qalsmartkeyboard.ime.ai.AiLanguagePrefs.InMemory()
        val toolbar = AiToolbarController(a, NoEditor(), { it() }, {}, keyboardLanguage = { "en" }, prefs = prefs)
        toolbar.onFieldStarted(InputProfile.DEFAULT, "com.chat")
        assertEquals("English text goes into Amharic by default", "lang:am", toolbar.destination.id)
        toolbar.chooseDestination(a.destinations().first { it.id == "p1" })
        toolbar.onKeyboardHidden()
        toolbar.onFieldStarted(InputProfile.DEFAULT, "com.chat")
        assertEquals("p1", toolbar.destination.id)
        toolbar.onFieldStarted(InputProfile.DEFAULT, "com.other")
        assertEquals("p1", toolbar.destination.id)
        toolbar.onFieldStarted(InputProfile.DEFAULT, "com.mail")
        assertEquals("lang:ti", toolbar.destination.id)
    }
}
