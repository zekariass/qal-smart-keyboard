package com.qalsmartkeyboard.sync

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A2.4 done-when, part 2: destination profiles converge (last writer wins) and app rules (hashed, 409 retry). */
class DestinationSyncTest {
    private lateinit var backend: FakeBackend
    private val devices = ArrayList<TestDevice>()

    private val pkgSecret = "com.secret.bank.app"
    private val pkgChat = "org.example.chatty"
    private val pkgMail = "net.example.mailer"

    @Before fun setUp() { backend = FakeBackend() }

    @After fun tearDown() {
        devices.forEach { it.close() }
        backend.shutdown()
    }

    private fun device() = TestDevice(backend).also { devices += it }

    private fun hmac(pkg: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(backend.ruleKey, "HmacSHA256"))
        return mac.doFinal(pkg.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun profile(label: String, lang: String = "am") =
        DestinationProfile(localId = "", label = label, destinationLanguage = lang, formality = "FORMAL", addresseeGender = "FEMALE")

    private fun serverLabels() = backend.profiles.values.map { (it["label"] as JsonPrimitive).content }.sorted()

    private fun serverRules(): List<JsonObject> = (backend.docs.getValue("rules")["rules"] as JsonArray).map { it.jsonObject }

    // ------------------------------------------------------------------ profiles

    @Test fun profilesCreateEditDeleteConverge() = runBlocking {
        val a = device()
        val b = device()
        val idA = a.dest.addProfile(profile("Mum"))!!
        a.dest.addProfile(profile("Boss", "en"))
        a.sync()
        assertEquals(listOf("Boss", "Mum"), serverLabels())
        assertTrue(a.dest.allProfiles().all { it.serverId != null && !it.dirty })

        b.sync()
        assertEquals(listOf("Boss", "Mum"), b.dest.profiles().map { it.label }.sorted())

        // B renames Mum, A (offline) deletes Boss and renames Mum differently: both synced, last writer (A, later) wins on Mum.
        val mumOnB = b.dest.profiles().first { it.label == "Mum" }
        b.dest.updateProfile(mumOnB.localId) { it.copy(label = "Mama") }
        b.sync()
        assertEquals(listOf("Boss", "Mama"), serverLabels())
        a.dest.updateProfile(idA) { it.copy(label = "Amma") }
        val bossA = a.dest.profiles().first { it.label == "Boss" }
        a.dest.deleteProfile(bossA.localId)
        a.sync()
        assertEquals(listOf("Amma"), serverLabels())

        b.sync()
        assertEquals(listOf("Amma"), b.dest.profiles().map { it.label })
        a.sync()
        assertEquals(listOf("Amma"), a.dest.profiles().map { it.label })
        // The tombstone is gone once the server delete is done.
        assertEquals(1, a.dest.allProfiles().size)
    }

    @Test fun editOfAProfileDeletedElsewhereRecreatesIt() = runBlocking {
        val a = device()
        val b = device()
        val id = a.dest.addProfile(profile("Aunt"))!!
        a.sync()
        b.sync()
        b.dest.deleteProfile(b.dest.profiles().single().localId)
        b.sync()
        assertTrue(backend.profiles.isEmpty())
        a.dest.updateProfile(id) { it.copy(label = "Aunt Sara") } // local edit made after the delete: the later writer wins
        a.sync()
        assertEquals(listOf("Aunt Sara"), serverLabels())
    }

    @Test fun deletingAUnsyncedProfileSendsNothing() = runBlocking {
        val a = device()
        val id = a.dest.addProfile(profile("Temp"))!!
        a.dest.deleteProfile(id)
        a.sync()
        assertTrue(backend.requests.none { it.path.startsWith("/me/destination-profiles") && it.method != "GET" })
    }

    // ------------------------------------------------------------------ app rules

    @Test fun appRulesAreHashedOnDeviceAndNoPackageNameLeaves() = runBlocking {
        val a = device()
        a.dest.setRule(pkgSecret, null, "am")
        a.dest.setRule(pkgChat, null, "en")
        a.sync()

        // The key is fetched once, then kept (encrypted) for later runs.
        assertEquals(1, backend.ruleKeyFetches)
        a.dest.setRule(pkgMail, null, "ti")
        a.sync()
        assertEquals(1, backend.ruleKeyFetches)

        val hashes = serverRules().map { (it["appPackageHmac"] as JsonPrimitive).content }.toSet()
        assertEquals(setOf(hmac(pkgSecret), hmac(pkgChat), hmac(pkgMail)), hashes)
        assertTrue(hashes.all { Regex("[0-9a-f]{64}").matches(it) })

        // Nothing sent anywhere contains a plaintext package name, in any request body, path or query.
        for (r in backend.requests) {
            for (p in listOf(pkgSecret, pkgChat, pkgMail, "secret", "chatty", "mailer")) {
                assertFalse("package name in ${r.method} ${r.path}", r.body.contains(p) || r.path.contains(p))
            }
        }
        // Every rule body member that looks like an app id is a 64-hex hash.
        val ruleBodies = backend.requests.filter { it.method == "PUT" && it.path == "/me/app-destination-rules" }
        assertTrue(ruleBodies.isNotEmpty())
        for (body in ruleBodies) {
            for (rule in (Json.parseToJsonElement(body.body).jsonObject["rules"] as JsonArray)) {
                assertTrue(Regex("[0-9a-f]{64}").matches((rule.jsonObject["appPackageHmac"] as JsonPrimitive).content))
            }
        }
        // The key itself never appears in a request.
        val keyB64 = Base64.getEncoder().encodeToString(backend.ruleKey)
        assertTrue(backend.requests.none { it.body.contains(keyB64) })
    }

    @Test fun appRulesRetryAfterConflictAndMergeByHash() = runBlocking {
        val a = device()
        val b = device()
        a.dest.setRule(pkgChat, null, "en")
        a.sync()
        b.sync()
        assertEquals(1, b.dest.rules().size)
        assertNull(b.dest.rules().single().packageName) // only the hash arrived: package names stay on their device

        // B (online) adds a rule for the mailer; A (offline) adds the bank app and removes the chat rule.
        b.dest.setRule(pkgMail, null, "ti")
        b.sync()
        a.dest.setRule(pkgSecret, null, "am")
        a.dest.removeRule(pkgChat)
        backend.requests.clear()
        a.sync()
        assertEquals(listOf("PUT", "GET", "PUT"), backend.requests.filter { it.path == "/me/app-destination-rules" }.map { it.method })

        assertEquals(setOf(hmac(pkgMail), hmac(pkgSecret)), serverRules().map { (it["appPackageHmac"] as JsonPrimitive).content }.toSet())
        // A adopted B's rule (hash only), B converges on A's changes.
        assertTrue(a.dest.rules().any { it.hmac == hmac(pkgMail) && it.packageName == null && it.language == "ti" })
        b.sync()
        assertEquals(setOf(hmac(pkgMail), hmac(pkgSecret)), b.dest.rules().mapNotNull { r -> r.packageName?.let { hmac(it) } ?: r.hmac }.toSet())
    }

    @Test fun ruleTargetingAProfileGoesOutAfterTheProfile() = runBlocking {
        val a = device()
        val b = device()
        val pid = a.dest.addProfile(profile("Mum"))!!
        a.dest.setRule(pkgChat, pid, null)
        a.sync() // one run: the profile first (gets its server id), then the rule that points at it
        val serverId = backend.profiles.keys.single()
        val rule = serverRules().single()
        assertEquals(serverId, (rule["destinationProfileId"] as JsonPrimitive).content)
        assertNull(rule["destinationLanguage"])

        b.sync()
        val r = b.dest.rules().single()
        assertEquals(b.dest.profiles().single().localId, r.profileLocalId)
        assertNotNull(r.hmac)
    }

    @Test fun mergeRulesPrefersLocalChangesAndDropsRemoteDeletes() {
        fun w(h: String, lang: String) = WireRule(h.padEnd(64, '0'), null, lang, true)
        val base = mapOf("a".padEnd(64, '0') to w("a", "am"), "b".padEnd(64, '0') to w("b", "am"), "c".padEnd(64, '0') to w("c", "am"))
        val local = mapOf("a".padEnd(64, '0') to w("a", "en"), "b".padEnd(64, '0') to w("b", "am"), "c".padEnd(64, '0') to w("c", "am")) // a edited
        val server = mapOf("a".padEnd(64, '0') to w("a", "ti"), "c".padEnd(64, '0') to w("c", "ti"), "d".padEnd(64, '0') to w("d", "om")) // b deleted, c edited, d added
        val m = DestinationSync.mergeRules(local, base, server)
        assertEquals("en", m["a".padEnd(64, '0')]!!.language) // local edit wins over the server edit
        assertNull(m["b".padEnd(64, '0')]) // unchanged here, deleted there
        assertEquals("ti", m["c".padEnd(64, '0')]!!.language) // unchanged here, server edit wins
        assertEquals("om", m["d".padEnd(64, '0')]!!.language) // new there
    }
}
