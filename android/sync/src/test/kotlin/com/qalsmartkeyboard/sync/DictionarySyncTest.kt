package com.qalsmartkeyboard.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A2.4 done-when, part 3: the personal dictionary converges across devices, deletes propagate as tombstones, consent and guests are respected. */
class DictionarySyncTest {
    private lateinit var backend: FakeBackend
    private val devices = ArrayList<TestDevice>()

    @Before fun setUp() { backend = FakeBackend() }

    @After fun tearDown() {
        devices.forEach { it.close() }
        backend.shutdown()
    }

    private fun device(guest: Boolean = false) = TestDevice(backend, isGuest = guest).also { devices += it }

    private fun TestDevice.enableDictionary() = runBlocking { engine.consent.set(true) }

    private fun dict(r: SyncReport) = r.steps.getValue(SyncStep.DICTIONARY)

    @Test fun wordsAddedOnTwoDevicesConverge() = runBlocking {
        val a = device().also { it.enableDictionary() }
        val b = device().also { it.enableDictionary() }
        a.dictionary.typeLearned("am", "ሰላም")
        a.dictionary.typeLearned("en", "hello")
        b.dictionary.typeLearned("am", "ሃሎ")
        b.dictionary.typeLearned("am", "ሰላም") // the same word on both: one entry, no duplicate

        assertEquals(StepResult.Synced, dict(a.sync()))
        assertEquals(StepResult.Synced, dict(b.sync()))
        a.sync()
        assertEquals(setOf("am|ሰላም", "am|ሃሎ", "en|hello"), backend.dictionaryWords)
        assertEquals(setOf("ሰላም", "ሃሎ"), a.dictionary.all("am"))
        assertEquals(setOf("ሰላም", "ሃሎ"), b.dictionary.all("am"))
        assertEquals(setOf("hello"), b.dictionary.all("en"))
        // D18: words from the server arrive as explicit adds (learned at once, no pending sighting).
        assertEquals(listOf("am|ሃሎ"), a.dictionary.addCalls)
        assertEquals(listOf("en|hello"), b.dictionary.addCalls)
        // Idempotent: nothing left to do.
        assertEquals(StepResult.Unchanged, dict(a.sync()))
        assertEquals(StepResult.Unchanged, dict(b.sync()))
    }

    @Test fun deleteTravelsAsATombstone() = runBlocking {
        val a = device().also { it.enableDictionary() }
        val b = device().also { it.enableDictionary() }
        a.dictionary.typeLearned("am", "ስህተት")
        a.dictionary.typeLearned("am", "ጥሩ")
        a.sync()
        b.sync()
        assertEquals(setOf("ስህተት", "ጥሩ"), b.dictionary.all("am"))

        a.dictionary.userDelete("am", "ስህተት") // the user removes a wrongly learned word
        a.sync()
        assertEquals(setOf("am|ጥሩ"), backend.dictionaryWords)
        assertEquals(setOf("am|ስህተት"), backend.tombstones)
        b.sync()
        assertEquals(setOf("ጥሩ"), b.dictionary.all("am"))
        // Not resurrected by the next round trip.
        a.sync()
        b.sync()
        assertEquals(setOf("am|ጥሩ"), backend.dictionaryWords)
        assertEquals(setOf("ጥሩ"), a.dictionary.all("am"))
    }

    @Test fun retryAfterALostResponseUsesTheSameChangeIdsAndDoesNotDuplicate() = runBlocking {
        val a = device().also { it.enableDictionary() }
        a.dictionary.typeLearned("en", "banana")
        backend.dropPushResponses = 2
        val first = dict(a.sync())
        assertTrue(first is StepResult.Failed && first.retryable)
        assertEquals(setOf("en|banana"), backend.dictionaryWords) // the server did apply it
        val firstId = Json.parseToJsonElement(backend.requests.last { it.method == "POST" }.body).jsonObject["changes"]!!.let { (it as JsonArray)[0].jsonObject["clientChangeId"] }
        assertEquals(StepResult.Synced, dict(a.sync()))
        val secondId = Json.parseToJsonElement(backend.requests.last { it.method == "POST" }.body).jsonObject["changes"]!!.let { (it as JsonArray)[0].jsonObject["clientChangeId"] }
        assertEquals(firstId, secondId)
        assertEquals(1, backend.changesApplied)
        assertEquals(StepResult.Unchanged, dict(a.sync()))
    }

    @Test fun pushHappensBeforePullSoALocalAddIsTheLaterWrite() = runBlocking {
        // An older delete of the same word by another device is on the server; this device adds it afterwards.
        backend.consentGranted = true
        backend.serverWrite("am", "ቃል", add = true)
        backend.serverWrite("am", "ቃል", add = false)
        val a = device().also { it.enableDictionary() }
        a.dictionary.typeLearned("am", "ቃል")
        a.sync()
        assertEquals(setOf("am|ቃል"), backend.dictionaryWords) // last writer wins: our ADD arrived last
        assertEquals(setOf("ቃል"), a.dictionary.all("am"))
    }

    @Test fun guestIsRefusedGracefully() = runBlocking {
        val g = device(guest = true).also { it.enableDictionary() }
        g.dictionary.typeLearned("am", "ቃል")
        val r = g.sync()
        assertEquals(StepResult.Skipped("GUEST"), dict(r))
        assertTrue(backend.requests.none { it.path == "/me/dictionary/changes" })
        assertEquals(StepResult.Synced, r.steps[SyncStep.PREFERENCES]) // preferences are allowed for guests

        // A guest flag the app did not know about: the server's GUEST_NOT_ALLOWED is handled the same way.
        val h = device().also { it.enableDictionary() }
        backend.guest = true
        assertEquals(StepResult.Skipped("GUEST"), dict(h.sync()))
    }

    @Test fun noConsentNoDictionarySyncAndServerWithdrawalTurnsTheSwitchOff() = runBlocking {
        val a = device()
        a.dictionary.typeLearned("am", "ቃል")
        assertEquals(StepResult.Skipped("CONSENT_OFF"), dict(a.sync()))
        assertTrue(backend.requests.none { it.path == "/me/dictionary/changes" })

        a.engine.consent.set(true)
        assertTrue(backend.consentGranted)
        assertTrue(a.engine.consent.enabled)
        assertEquals(StepResult.Synced, dict(a.sync()))
        val put = backend.requests.last { it.path == "/me/consents/DICTIONARY_SYNC" }
        assertEquals("settings", Json.parseToJsonElement(put.body).jsonObject["details"]!!.jsonObject["surface"]!!.let { (it as JsonPrimitive).content })

        // Withdrawn on another device: the server answers CONSENT_REQUIRED and the local switch follows.
        backend.consentGranted = false
        a.dictionary.typeLearned("am", "ሌላ")
        assertEquals(StepResult.Skipped("CONSENT_REQUIRED"), dict(a.sync()))
        assertFalse(a.engine.consent.enabled)
        assertEquals(setOf("am|ቃል"), backend.dictionaryWords)

        // Switching off records the withdrawal.
        a.engine.consent.set(false)
        assertFalse(backend.consentGranted)
        // A new device adopts what the account already granted.
        backend.consentGranted = true
        val c = device()
        assertTrue(runBlocking { c.engine.consent.refresh() })
        assertTrue(c.engine.consent.enabled)
    }

    @Test fun resyncRequiredTriggersAFullDownload() = runBlocking {
        val a = device().also { it.enableDictionary() }
        a.dictionary.typeLearned("am", "አንድ")
        a.dictionary.typeLearned("am", "ሁለት")
        a.sync()
        // Another device deleted "አንድ" long ago; the tombstone was purged (180 days) and a new word exists.
        backend.serverWrite("am", "ሦስት", add = true)
        backend.serverWrite("am", "አንድ", add = false)
        backend.purge("am", "አንድ")
        backend.resyncOnNextPull = true
        a.dictionary.typeLearned("en", "local-only") // never synced: must survive and be pushed
        val r = DictionarySync(a.http, a.state, a.dictionary).run()
        assertTrue(r.fullResync)
        assertEquals(setOf("ሁለት", "ሦስት"), a.dictionary.all("am")) // full download: new word added, the one deleted elsewhere removed
        a.sync()
        assertTrue("en|local-only" in backend.dictionaryWords)
    }

    @Test fun invalidWordsAreNeverSent() = runBlocking {
        val a = device().also { it.enableDictionary() }
        a.dictionary.typeLearned("en", "two words")
        a.dictionary.typeLearned("en", "x".repeat(65))
        a.dictionary.typeLearned("en", "fine")
        a.sync()
        assertEquals(setOf("en|fine"), backend.dictionaryWords)
    }

    @Test fun signedOutAccountSwitchForgetsTheState() = runBlocking {
        val a = device().also { it.enableDictionary() }
        a.dictionary.typeLearned("en", "kept")
        a.sync()
        a.engine.accountChanged()
        assertFalse(a.engine.consent.enabled)
        assertEquals(StepResult.Skipped("CONSENT_OFF"), dict(a.sync()))
    }
}
