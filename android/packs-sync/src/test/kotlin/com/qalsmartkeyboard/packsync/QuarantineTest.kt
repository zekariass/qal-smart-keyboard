package com.qalsmartkeyboard.packsync

import com.qalsmartkeyboard.ime.packs.PackOrigin
import com.qalsmartkeyboard.ime.packs.PackRuntime
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ADR 0007 D34: a key the server marks REVOKED (and the build trusts) quarantines the installed packs
 * it signed. Real core (JNA), real journal, real pack manager, MockWebServer backend; test keys only.
 */
class QuarantineTest {
    private lateinit var h: UpdateHarness
    private val loaded = ArrayList<PackRuntime.Loaded>()
    private val other = TestSigner("key-2")

    @Before
    fun setUp() {
        h = UpdateHarness()
    }

    @After
    fun tearDown() {
        loaded.forEach { it.result.engine.close() }
        h.close()
    }

    private fun load(rt: PackRuntime) = rt.load().also { loaded += it }

    private fun PackRuntime.Loaded.version() = result.active.single { it.info.languageCode == "am" }.info.version

    private val packName = "upd-am-dictionary-shared.pack"
    private fun quarantineDir() = File(h.root, "quarantine")

    /** The real sequence: IME starts on the bundled pack, the updater installs [version], the IME loads and confirms it. */
    private fun installConfirmed(rt: PackRuntime, version: String) {
        load(rt)
        h.publish(h.pack(version), version)
        assertEquals(1, h.run().installed.size)
        val l = load(rt)
        assertEquals(version, l.version())
        assertEquals(PackOrigin.INSTALLED, l.result.active.single().origin)
        rt.confirm(l)
        assertEquals(version, h.floor())
    }

    @Test
    fun revokedKeyMovesTheInstalledPackAsideAndTheSlotFallsBackToBundled() {
        val rt = h.runtime()
        installConfirmed(rt, "1.1.0")
        val tokenBefore = h.journal.reloadToken()

        h.announceKey(h.signer.keyId, "REVOKED")
        val r = h.run()

        assertEquals(listOf(packName), r.quarantined)
        assertEquals(emptyList<String>(), h.installedPacks()) // the IME can no longer see it
        assertEquals(1, quarantineDir().listFiles()!!.size) // kept aside, not in the installed directory
        assertTrue(quarantineDir().listFiles()!!.single().name.startsWith(packName))
        assertTrue(h.journal.isBlacklisted(ID_A, "1.1.0"))
        assertTrue(h.journal.pending().isEmpty())
        assertNotEquals(tokenBefore, h.journal.reloadToken()) // the IME reloads when the keyboard is shown
        assertNull(h.floor()) // the floor raised by the revoked key's pack is gone

        // The IME reload: bundled 1.0.0 is active again and raises the floor to its own version.
        val l = load(rt)
        assertEquals("1.0.0", l.version())
        assertEquals(PackOrigin.BUNDLED, l.result.active.single().origin)
        assertEquals("1.0.0", h.floor())

        // Not fetched again, not reinstalled: the offer is blacklisted (and its key is revoked anyway).
        val downloads = h.artifactRequests().size
        val r2 = h.run()
        assertTrue(r2.installed.isEmpty() && r2.quarantined.isEmpty())
        assertEquals(downloads, h.artifactRequests().size)
    }

    @Test
    fun theCachedRevocationApplies_whenTheKeyListIsUnreachable() {
        val rt = h.runtime()
        installConfirmed(rt, "1.1.0")
        File(h.root, "update").mkdirs()
        File(h.root, "update/revoked-keys.txt").writeText(h.signer.keyId + "\n") // what an earlier run cached
        h.keysStatus = 503

        val r = h.run()

        assertEquals(listOf(packName), r.quarantined)
        assertEquals(emptyList<String>(), h.installedPacks())
    }

    @Test
    fun aRevokedKeyTheBuildDoesNotTrustQuarantinesNothing() {
        val rt = h.runtime()
        installConfirmed(rt, "1.1.0")
        h.announceKey("some-unknown-key", "REVOKED")
        val r = h.run()
        assertTrue(r.quarantined.isEmpty())
        assertEquals(listOf(packName), h.installedPacks())
        assertEquals("1.1.0", h.floor())
    }

    @Test
    fun packsSignedByAnotherTrustedKeyStay() {
        val both = listOf(h.signer.trust, other.trust)
        h.publish(h.pack("1.1.0", by = other), "1.1.0", keyId = other.keyId)
        assertEquals(1, h.run(h.updater(trustKeys = both)).installed.size)

        h.announceKey(h.signer.keyId, "REVOKED") // not the key that signed the installed pack
        val r = h.run(h.updater(trustKeys = both))

        assertTrue(r.quarantined.isEmpty())
        assertEquals(listOf(packName), h.installedPacks())
        assertFalse(quarantineDir().exists())
    }

    @Test
    fun theSlotIsHeldUntilTheImeHasLoadedAgainThenAReplacementFromAGoodKeyInstalls() {
        val both = listOf(h.signer.trust, other.trust)
        val rt = h.runtime()
        installConfirmed(rt, "1.1.0")
        h.announceKey(h.signer.keyId, "REVOKED")
        assertEquals(listOf(packName), h.run(h.updater(trustKeys = both)).quarantined)
        assertNull(h.floor())

        // A replacement signed by a trusted, non-revoked key appears at once. With no floor it must not
        // be judged yet (a downgrade window): held until the IME reload re-establishes the floor.
        h.clearOffers()
        h.publish(h.pack("1.1.1", by = other), "1.1.1", keyId = other.keyId)
        val held = h.run(h.updater(trustKeys = both))
        assertTrue(held.installed.isEmpty() && held.failures.isEmpty())
        assertEquals(emptyList<String>(), h.installedPacks())

        assertEquals("1.0.0", load(rt).version()) // the IME reloads: bundled pack, floor 1.0.0
        val r = h.run(h.updater(trustKeys = both))
        assertEquals("1.1.1", r.installed.single().version)
        assertEquals(listOf(packName), h.installedPacks())
    }

    @Test
    fun anUnconfirmedSwapSignedByTheRevokedKeyIsQuarantinedWithItsPrev() {
        val rt = h.runtime()
        installConfirmed(rt, "1.1.0")
        // 1.2.0 is swapped in (the previous pack kept as .prev) but the IME has not confirmed it yet.
        h.clearOffers()
        h.publish(h.pack("1.2.0"), "1.2.0")
        assertEquals(1, h.run().installed.size)
        assertEquals(listOf(packName, "$packName.prev"), h.installedPacks())
        assertEquals(1, h.journal.pending().size)

        h.announceKey(h.signer.keyId, "REVOKED")
        val r = h.run()

        assertEquals(listOf(packName), r.quarantined)
        assertEquals(emptyList<String>(), h.installedPacks()) // the .prev is gone too: the slot goes to the bundled pack
        assertTrue(h.journal.pending().isEmpty())
        assertTrue(h.journal.isBlacklisted(ID_A, "1.2.0"))
        assertEquals("1.0.0", load(rt).version())
    }
}
