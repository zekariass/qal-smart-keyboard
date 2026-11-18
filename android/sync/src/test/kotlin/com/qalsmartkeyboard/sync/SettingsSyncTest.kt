package com.qalsmartkeyboard.sync

import com.qalsmartkeyboard.data.ConversionTrigger
import com.qalsmartkeyboard.data.InputMode
import com.qalsmartkeyboard.data.NumeralStyle
import com.qalsmartkeyboard.data.ThemeMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A2.4 done-when, part 1: offline edit -> sync -> 409 -> merge, for preferences and typing languages. */
class SettingsSyncTest {
    private lateinit var backend: FakeBackend
    private val devices = ArrayList<TestDevice>()

    @Before fun setUp() { backend = FakeBackend() }

    @After fun tearDown() {
        devices.forEach { it.close() }
        backend.shutdown()
    }

    private fun device(guest: Boolean = false) = TestDevice(backend, isGuest = guest).also { devices += it }

    private fun prefs() = backend.docs.getValue("preferences")
    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull

    // ------------------------------------------------------------------ preferences

    @Test fun offlineEditMergesAfterRevisionConflict() = runBlocking {
        // The server already holds members this app does not edit; they must survive the full-replace PUT.
        backend.setDoc("preferences") { JsonObject(it + mapOf("translationMode" to JsonPrimitive("PROMPT"), "historyEnabled" to JsonPrimitive(false))) }
        val a = device()
        val b = device()
        assertEquals(StepResult.Synced, a.sync().steps[SyncStep.PREFERENCES])
        b.sync()
        assertEquals(StepResult.Unchanged, a.sync().steps[SyncStep.PREFERENCES]) // nothing changed anywhere

        // Device B (online) switches the theme to DARK and the haptics off.
        b.settings.update { it.copy(theme = ThemeMode.DARK, hapticsEnabled = false) }
        assertEquals(StepResult.Synced, b.sync().steps[SyncStep.PREFERENCES])
        val revAfterB = (prefs()["revision"] as JsonPrimitive).content

        // Device A is offline and edits numerals (a field B did not touch) and the theme (the same field B changed).
        a.settings.update { it.copy(numeralStyle = NumeralStyle.ETHIOPIC, theme = ThemeMode.LIGHT) }
        backend.requests.clear()
        assertEquals(StepResult.Synced, a.sync().steps[SyncStep.PREFERENCES])

        // PUT with the last known revision -> 409 -> GET -> merged PUT with the revision just read.
        val prefReqs = backend.requests.filter { it.path == "/me/preferences" }
        assertEquals(listOf("PUT", "GET", "PUT"), prefReqs.map { it.method })
        assertEquals(revAfterB.toInt() - 1, (Json.parseToJsonElement(prefReqs[0].body).jsonObject["revision"] as JsonPrimitive).content.toInt())
        assertEquals(revAfterB.toInt(), (Json.parseToJsonElement(prefReqs[2].body).jsonObject["revision"] as JsonPrimitive).content.toInt())

        // Rule: local edits since the last sync win (theme: both changed it; numerals), the server wins elsewhere (haptics), foreign members survive.
        val doc = prefs()
        assertEquals("ETHIOPIC", str(doc, "numeralStyle"))
        assertEquals("LIGHT", str(doc, "keyboardTheme"))
        assertEquals("false", str(doc, "hapticsEnabled"))
        assertEquals("PROMPT", str(doc, "translationMode"))
        val local = a.settings.current()
        assertEquals(ThemeMode.LIGHT, local.theme)
        assertEquals(NumeralStyle.ETHIOPIC, local.numeralStyle)
        assertFalse(local.hapticsEnabled) // adopted from the server

        // B pulls A's result and converges.
        b.sync()
        val bs = b.settings.current()
        assertEquals(NumeralStyle.ETHIOPIC, bs.numeralStyle)
        assertFalse(bs.hapticsEnabled)
        assertEquals(ThemeMode.LIGHT, bs.theme)
        // Device-local settings never travel.
        assertFalse(backend.bodiesOf("PUT").any { it.contains("keyHeight") || it.contains("packChannel") })
    }

    @Test fun serverOnlyChangeIsAdoptedWithoutAPut() = runBlocking {
        val a = device()
        a.sync()
        backend.setDoc("preferences") { JsonObject(it + ("keyboardTheme" to JsonPrimitive("LIGHT"))) }
        backend.requests.clear()
        a.sync()
        assertEquals(ThemeMode.LIGHT, a.settings.current().theme)
        assertEquals(0, backend.requests.count { it.method == "PUT" })
    }

    @Test fun boundedRetriesGiveUpOnPersistentConflict() = runBlocking {
        val a = device()
        a.sync()
        a.settings.update { it.copy(theme = ThemeMode.DARK) }
        backend.alwaysConflict = true
        backend.requests.clear()
        val r = a.sync().steps[SyncStep.PREFERENCES] as StepResult.Failed
        assertEquals("REVISION_CONFLICT", r.code)
        assertTrue(r.retryable)
        assertEquals(DocSync.MAX_ATTEMPTS, backend.requests.count { it.method == "PUT" && it.path == "/me/preferences" })
        // The local edit is not lost: once the conflict clears it goes through.
        backend.alwaysConflict = false
        a.sync()
        assertEquals("DARK", str(prefs(), "keyboardTheme"))
    }

    @Test fun signedOutDoesNothing() = runBlocking {
        val a = TestDevice(backend, signedIn = false).also { devices += it }
        val r = a.sync()
        assertEquals(RunStatus.SIGNED_OUT, r.status)
        assertTrue(backend.requests.isEmpty())
    }

    // ------------------------------------------------------------------ typing languages

    private fun typing() = backend.docs.getValue("typing")

    private fun typingEntries(): List<JsonObject> = (typing()["languages"] as JsonArray).map { it.jsonObject }

    @Test fun typingLanguagesConvergeAfterConflict() = runBlocking {
        val a = device()
        val b = device()
        a.sync()
        b.sync()
        // The first sync resolved the profile code through the profiles API (D21): never PHONETIC without a code.
        val am = typingEntries().first { str(it, "languageCode") == "am" }
        assertEquals("PHONETIC_LATIN_TO_NATIVE", str(am, "inputMode"))
        assertEquals("AM_PHONETIC_QWERTY_TO_ETHIOPIC", str(am, "transliterationProfileCode"))

        // B (online): am -> Fidel grid. A (offline): disables ti, moves en first, turns off candidates for am.
        b.settings.updateLanguage("am") { it.copy(inputMode = InputMode.DIRECT_SCRIPT_LAYOUT) }
        b.sync()
        a.settings.setLanguageEnabled("ti", false)
        a.settings.moveLanguage("en", 0)
        a.settings.updateLanguage("am") { it.copy(showTransliterationCandidates = false, conversionTrigger = ConversionTrigger.WORD_BOUNDARY) }
        backend.requests.clear()
        a.sync()
        assertEquals(listOf("PUT", "GET", "PUT"), backend.requests.filter { it.path == "/me/typing-languages" }.map { it.method })

        val entries = typingEntries()
        assertEquals(listOf("en", "am", "ti", "om"), entries.map { str(it, "languageCode") })
        assertEquals(listOf(10, 20, 30, 40), entries.map { (it["sortOrder"] as JsonPrimitive).content.toInt() })
        val amNow = entries.first { str(it, "languageCode") == "am" }
        assertEquals("DIRECT_SCRIPT_LAYOUT", str(amNow, "inputMode")) // B's change, A did not touch it
        assertEquals(null, str(amNow, "transliterationProfileCode"))
        assertEquals("false", str(amNow, "showTransliterationCandidates")) // A's change
        assertEquals("WORD_BOUNDARY", str(amNow, "conversionTrigger"))
        assertEquals("false", str(entries.first { str(it, "languageCode") == "ti" }, "enabled"))
        assertEquals("true", str(entries.first { str(it, "languageCode") == "en" }, "isPrimary"))

        val local = a.settings.current()
        assertEquals(InputMode.DIRECT_SCRIPT_LAYOUT, local.language("am")!!.inputMode)
        assertEquals(listOf("en", "am", "ti", "om"), local.languages.map { it.languageCode })

        b.sync()
        val bs = b.settings.current()
        assertEquals(listOf("en", "am", "ti", "om"), bs.languages.map { it.languageCode })
        assertFalse(bs.language("ti")!!.enabled)
        assertFalse(bs.language("am")!!.showTransliterationCandidates)
    }

    @Test fun phoneticIsNeverSentWithoutACode() = runBlocking {
        backend.profilesApiStatus = 500
        val a = device()
        val r = a.sync().steps[SyncStep.TYPING_LANGUAGES]
        assertTrue(r is StepResult.Deferred)
        // Nothing was sent for the phonetic languages: no entry (and so no code-less PHONETIC) on the server.
        assertTrue(backend.requests.none { it.path == "/me/typing-languages" && it.method == "PUT" && it.body.contains("PHONETIC_LATIN_TO_NATIVE") && !it.body.contains("transliterationProfileCode") })
        assertFalse(typingEntries().any { str(it, "inputMode") == "PHONETIC_LATIN_TO_NATIVE" })
        // The other steps are not blocked by the deferral (defaults equal the empty server document: nothing to send).
        assertTrue(a.sync().steps[SyncStep.PREFERENCES] !is StepResult.Failed)

        // The profile API comes back: the next run sends the canonical code (the legacy derived code is replaced).
        backend.profilesApiStatus = 200
        a.settings.updateLanguage("am") { it.copy(transliterationProfileCode = "AM_PHONETIC") }
        a.state.put("profiles", null)
        a.sync()
        val am = typingEntries().first { str(it, "languageCode") == "am" }
        assertEquals("AM_PHONETIC_QWERTY_TO_ETHIOPIC", str(am, "transliterationProfileCode"))
        // The local value follows the backend's canonical code (D21).
        assertEquals("AM_PHONETIC_QWERTY_TO_ETHIOPIC", a.settings.current().language("am")!!.transliterationProfileCode)
        // A language the server holds is never re-sent as PHONETIC without a code afterwards either.
        assertTrue(backend.bodiesOf("PUT").filter { it.contains("languages") }.all { body ->
            (Json.parseToJsonElement(body).jsonObject["languages"] as JsonArray).map { it.jsonObject }
                .filter { str(it, "inputMode") == "PHONETIC_LATIN_TO_NATIVE" }.all { str(it, "transliterationProfileCode") != null }
        })
    }

    @Test fun retiredProfileAlreadyOnTheServerIsKept() = runBlocking {
        // The server row holds a RETIRED profile (F14): a reorder must not fail or silently switch it.
        backend.setDoc("typing") {
            JsonObject(
                it + ("languages" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "languageCode" to JsonPrimitive("am"), "enabled" to JsonPrimitive(true), "isPrimary" to JsonPrimitive(true),
                                "sortOrder" to JsonPrimitive(10), "inputMode" to JsonPrimitive("PHONETIC_LATIN_TO_NATIVE"),
                                "transliterationProfileCode" to JsonPrimitive("AM_OLD"),
                            ),
                        ),
                    ),
                )),
            )
        }
        val a = device()
        a.sync()
        assertEquals("AM_OLD", a.settings.current().language("am")!!.transliterationProfileCode) // adopted from the server
        a.settings.moveLanguage("ti", 0)
        a.sync()
        assertEquals("AM_OLD", str(typingEntries().first { str(it, "languageCode") == "am" }, "transliterationProfileCode"))
        assertEquals("ti", str(typingEntries().first(), "languageCode"))
    }
}
