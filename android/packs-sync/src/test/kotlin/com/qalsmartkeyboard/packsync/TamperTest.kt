package com.qalsmartkeyboard.packsync

import com.qalsmartkeyboard.ime.packs.PackOrigin
import com.qalsmartkeyboard.ime.packs.PackRuntime
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A2.3 done-when: the tamper test. A fake backend (MockWebServer) serves the discovery API and the
 * artifacts; the real generated client, downloader, journal, pack manager and Rust core (JNA) do
 * the rest. Test keys only (the build's trust config is not involved: the trust list is injected).
 */
class TamperTest {
    private lateinit var h: UpdateHarness
    private val engines = ArrayList<PackRuntime.Loaded>()

    @Before
    fun setUp() {
        h = UpdateHarness()
    }

    @After
    fun tearDown() {
        engines.forEach { it.result.engine.close() }
        h.close()
    }

    private fun load(rt: PackRuntime) = rt.load().also { engines += it }

    private fun PackRuntime.Loaded.version() = result.active.single { it.info.languageCode == "am" }.info.version

    /** Publishes [version], lets the updater install it and the IME load and confirm it (the real sequence). */
    private fun installAndConfirm(rt: PackRuntime, version: String) {
        h.clearOffers()
        h.publish(h.pack(version), version)
        assertEquals(1, h.run().installed.size)
        val l = load(rt)
        assertEquals(version, l.version())
        rt.confirm(l)
    }

    private fun failureOf(r: UpdateReport): Failure = r.failures.single()

    private fun assertNothingInstalled() {
        assertEquals(emptyList<String>(), h.installedPacks())
    }

    // ------------------------------------------------------------------ happy path

    @Test
    fun validUpdateInstallsSwapsAndIsConfirmed() {
        // The IME starts on the bundled 1.0.0 (this also sets the version floor).
        val rt = h.runtime()
        assertEquals("1.0.0", load(rt).version())
        assertEquals("1.0.0", h.floor())

        // 1.1.0 is published: installed next to the IME's packs, floor untouched until the IME loads it.
        h.publish(h.pack("1.1.0"), "1.1.0")
        val r1 = h.run(h.updater(signedIn = true))
        assertEquals(RunStatus.DONE, r1.status)
        assertEquals("1.1.0", r1.installed.single().version)
        assertEquals(listOf("upd-am-dictionary-shared.pack"), h.installedPacks())
        assertEquals("1.0.0", h.floor())
        assertNotNull(h.journal.reloadToken())
        assertEquals(0, h.journal.pending().single().attempts)
        // The bearer goes to the API, never to the artifact host.
        assertEquals("Bearer access-token", h.requests.first { it.path!!.contains("language-resources") }.getHeader("Authorization"))
        assertNull(h.artifactRequests().single().getHeader("Authorization"))
        assertNull(h.artifactRequests().single().getHeader("X-App-Version"))

        // The IME reloads: the new pack wins, the floor rises, first use confirms it.
        val loaded = load(rt)
        assertEquals("1.1.0", loaded.version())
        assertEquals(PackOrigin.INSTALLED, loaded.result.active.single().origin)
        assertEquals("1.1.0", h.floor())
        rt.confirm(loaded)
        assertTrue(h.journal.pending().isEmpty())

        // 1.2.0 replaces it; the previous file is kept as .prev until the IME confirms.
        h.publish(h.pack("1.2.0"), "1.2.0")
        assertEquals("1.2.0", h.run().installed.single().version)
        assertEquals(listOf("upd-am-dictionary-shared.pack", "upd-am-dictionary-shared.pack.prev"), h.installedPacks())
        val l2 = load(rt)
        assertEquals("1.2.0", l2.version())
        rt.confirm(l2)
        assertEquals(listOf("upd-am-dictionary-shared.pack"), h.installedPacks())

        // Nothing newer: the next check downloads nothing.
        val before = h.artifactRequests().size
        val r3 = h.run()
        assertTrue(r3.installed.isEmpty() && r3.failures.isEmpty())
        assertEquals(before, h.artifactRequests().size)
    }

    // ------------------------------------------------------------------ tampering

    @Test
    fun tamperedArtifactBytesAreRejectedAndNothingIsReplaced() {
        val rt = h.runtime()
        installAndConfirm(rt, "1.1.0")
        h.clearOffers()
        val before = File(h.installed, "upd-am-dictionary-shared.pack").readBytes()

        val good = h.pack("1.2.0")
        val tampered = TestPacks.flip(good, good.size - 5) // inside the artifact: the signed SHA-256 no longer matches
        h.publish(good, "1.2.0", servedBytes = tampered)
        val r = h.run()
        assertTrue(r.installed.isEmpty())
        assertEquals(FailReason.VERIFY_FAILED, failureOf(r).reason)
        assertEquals("CHECKSUM_MISMATCH", failureOf(r).detail)
        assertEquals(listOf("upd-am-dictionary-shared.pack"), h.installedPacks())
        assertArrayEquals(before, File(h.installed, "upd-am-dictionary-shared.pack").readBytes())

        // A bit flip anywhere in the container is caught (here: the signed manifest).
        h.clearOffers()
        val good2 = h.pack("1.3.0")
        h.publish(good2, "1.3.0", servedBytes = TestPacks.flip(good2, 40))
        val r2 = h.run()
        assertTrue(r2.installed.isEmpty())
        assertArrayEquals(before, File(h.installed, "upd-am-dictionary-shared.pack").readBytes())
    }

    @Test
    fun discoveryHashThatDisagreesWithTheSignedManifestIsRejected() {
        val p = h.pack("1.1.0")
        h.publish(p, "1.1.0", sha256 = "0".repeat(64))
        val r = h.run()
        assertEquals(FailReason.MANIFEST_MISMATCH, failureOf(r).reason)
        assertNothingInstalled()
    }

    @Test
    fun validShaButBadSignatureIsRejected() {
        // Same key id as the trusted key, signed with a different private key.
        val impostor = TestSigner(h.signer.keyId)
        h.publish(h.pack("1.1.0", by = impostor), "1.1.0")
        val r = h.run()
        assertEquals(FailReason.VERIFY_FAILED, failureOf(r).reason)
        assertEquals("BAD_SIGNATURE", failureOf(r).detail)
        assertNothingInstalled()
    }

    @Test
    fun unknownKeyIsRejectedEvenWhenTheServerAnnouncesIt() {
        val other = TestSigner("other-key")
        h.announceKey("other-key", "ACTIVE") // the server may list it; the build does not trust it
        h.publish(h.pack("1.1.0", by = other), "1.1.0", keyId = "other-key")
        val r = h.run()
        assertEquals(FailReason.VERIFY_FAILED, failureOf(r).reason)
        assertEquals("UNKNOWN_KEY", failureOf(r).detail)
        assertNothingInstalled()
    }

    @Test
    fun keyTheServerMarksRevokedIsRejectedAndRememberedOffline() {
        h.announceKey(h.signer.keyId, "REVOKED")
        h.publish(h.pack("1.1.0"), "1.1.0")
        val r = h.run()
        assertEquals("REVOKED_KEY", failureOf(r).detail)
        assertNothingInstalled()

        // Later the key list is unreachable: the cached revocation still applies (new candidate, no cooldown).
        h.keysStatus = 503
        h.clearOffers()
        h.publish(h.pack("1.2.0"), "1.2.0")
        val r2 = h.run()
        assertEquals("REVOKED_KEY", failureOf(r2).detail)
        assertNothingInstalled()
    }

    @Test
    fun downgradeIsRejected() {
        // Installed and loaded 2.0.0.
        val rt = h.runtime()
        installAndConfirm(rt, "2.0.0")
        assertEquals("2.0.0", h.floor())

        // An older version is never even downloaded.
        h.publish(h.pack("1.5.0"), "1.5.0")
        val before = h.artifactRequests().size
        val r = h.run()
        assertTrue(r.installed.isEmpty() && r.failures.isEmpty())
        assertEquals(before, h.artifactRequests().size)

        // A server that announces 3.0.0 but serves the old 1.5.0 container is caught by the signed manifest.
        h.clearOffers()
        h.publish(h.pack("1.5.0"), "3.0.0")
        val r2 = h.run()
        assertEquals(FailReason.MANIFEST_MISMATCH, failureOf(r2).reason)
        assertEquals("2.0.0", load(rt).version())
    }

    @Test
    fun deltaAndTooNewForThisAppAreSkippedWithoutDownloading() {
        h.publish(h.pack("1.1.0", id = ID_B), "1.1.0", id = ID_B, baseResourceId = ID_A)
        h.publish(h.pack("1.2.0"), "1.2.0", minAppVersion = "9.0.0")
        val r = h.run()
        assertTrue(r.installed.isEmpty() && r.failures.isEmpty())
        assertEquals(0, h.artifactRequests().size)

        // Newer app: the 1.2.0 pack is acceptable.
        h.appVersion = "9.0"
        assertEquals("1.2.0", h.run().installed.single().version)
    }

    @Test
    fun truncatedDownloadsAreRejected() {
        val p = h.pack("1.1.0")
        // (a) the server sends fewer bytes than the discovery entry announces, with a consistent Content-Length.
        h.publish(p, "1.1.0", servedBytes = p.copyOf(p.size / 2))
        val r = h.run()
        assertEquals(FailReason.TRUNCATED, failureOf(r).reason)
        assertNothingInstalled()

        // (b) the connection drops in the middle of the body.
        h.clearOffers()
        h.publish(p, "1.2.0", servedBytes = p)
        h.artifactHook = { MockResponse().setBody(okio.Buffer().write(p)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY) }
        val r2 = h.run()
        assertTrue(r2.installed.isEmpty())
        assertTrue(failureOf(r2).reason == FailReason.NETWORK || failureOf(r2).reason == FailReason.TRUNCATED)
        assertNothingInstalled()
    }

    @Test
    fun containerLargerThanAnnouncedIsRejected() {
        val p = h.pack("1.1.0")
        h.publish(p, "1.1.0", containerSize = p.size - 10L)
        val r = h.run()
        assertEquals(FailReason.TOO_LARGE, failureOf(r).reason)
        assertNothingInstalled()
    }

    @Test
    fun artifactOnPlainHttpToARemoteHostIsRejected() {
        val p = h.pack("1.1.0")
        h.publish(p, "1.1.0")
        h.offers[0] = h.offers[0].replace("http://localhost:${h.server.port}", "http://cdn.example.org")
        val r = h.run()
        assertEquals(FailReason.URL_REJECTED, failureOf(r).reason)
        assertEquals(0, h.artifactRequests().size)
    }

    // ------------------------------------------------------------------ rollback

    @Test
    fun packThatFailsToLoadInTheImeIsRolledBackToPrevAndBlacklisted() {
        val rt = h.runtime()
        installAndConfirm(rt, "1.1.0")
        h.clearOffers()
        h.publish(h.pack("1.2.0"), "1.2.0")
        h.run()
        // The file is damaged after the swap (disk error): the IME's load rejects it.
        val target = File(h.installed, "upd-am-dictionary-shared.pack")
        target.writeBytes(TestPacks.flip(target.readBytes(), target.length().toInt() - 3))
        val l2 = load(rt)
        assertEquals("1.1.0", l2.version()) // .prev restored and loaded in the same call
        assertEquals(listOf("upd-am-dictionary-shared.pack"), h.installedPacks())
        assertTrue(h.journal.pending().isEmpty())
        assertTrue(h.journal.isBlacklisted(ID_A, "1.2.0"))

        // The blacklisted version is not downloaded again.
        val before = h.artifactRequests().size
        val r = h.run()
        assertTrue(r.installed.isEmpty())
        assertEquals(before, h.artifactRequests().size)
    }

    @Test
    fun repeatedUnconfirmedStartsAreACrashAndRollBack() {
        val rt = h.runtime()
        installAndConfirm(rt, "1.1.0")
        h.clearOffers()
        h.publish(h.pack("1.2.0"), "1.2.0")
        h.run()
        assertEquals("1.1.0", h.floor())

        // The engine with 1.2.0 loads fine but the process dies before the keyboard is shown, twice.
        assertEquals("1.2.0", load(rt).version())
        assertEquals("1.2.0", h.floor())
        assertEquals("1.2.0", load(rt).version())
        // Third start: the update is judged unhealthy before loading.
        val l = load(rt)
        assertEquals("1.1.0", l.version())
        assertEquals("1.1.0", h.floor()) // the floor goes back, so .prev is not a "downgrade"
        assertTrue(h.journal.isBlacklisted(ID_A, "1.2.0"))
        assertTrue(h.journal.pending().isEmpty())
        assertEquals(listOf("upd-am-dictionary-shared.pack"), h.installedPacks())
    }

    @Test
    fun rollbackWithoutAPreviousPackFallsBackToTheBundledOne() {
        val rt = h.runtime()
        h.publish(h.pack("1.1.0"), "1.1.0")
        h.run()
        File(h.installed, "upd-am-dictionary-shared.pack").writeBytes(ByteArray(40))
        val l = load(rt)
        assertEquals(PackOrigin.BUNDLED, l.result.active.single().origin)
        assertEquals("1.0.0", l.version())
        assertNothingInstalled()
        assertTrue(h.journal.isBlacklisted(ID_A, "1.1.0"))
    }

    @Test
    fun aSwapThatWasInterruptedBetweenTheRenamesIsUndoneOnTheNextCheck() {
        installAndConfirm(h.runtime(), "1.1.0")
        // Simulate a crash after "current -> .prev" and journal entry, before "tmp -> current".
        val target = File(h.installed, "upd-am-dictionary-shared.pack")
        val old = target.readBytes()
        h.journal.recordSwap(
            com.qalsmartkeyboard.ime.packs.PendingSwap("upd-am-dictionary-shared.pack", ID_A, "1.2.0", SLOT, null, true, com.qalsmartkeyboard.ime.packs.PendingSwap.STAGED),
        )
        target.renameTo(File(h.installed, "upd-am-dictionary-shared.pack.prev"))
        // The IME must not judge the staged entry...
        val rt = h.runtime()
        assertEquals("1.0.0", load(rt).version())
        assertTrue(h.journal.pending().single().staged)
        // ...and the next check restores the old pack.
        h.clearOffers()
        h.run()
        assertTrue(h.journal.pending().isEmpty())
        assertArrayEquals(old, target.readBytes())
    }

    // ------------------------------------------------------------------ disk, trust, concurrency

    @Test
    fun diskFullLeavesTheOldPackIntact() {
        installAndConfirm(h.runtime(), "1.1.0")
        val target = File(h.installed, "upd-am-dictionary-shared.pack")
        val before = target.readBytes()

        h.clearOffers()
        h.publish(h.pack("1.2.0"), "1.2.0")
        // The disk fills up while the temp file is written.
        val full = HttpArtifactDownloader(openOut = { f ->
            object : OutputStream() {
                private val real = f.outputStream()
                private var n = 0
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                override fun write(b: ByteArray, off: Int, len: Int) {
                    n += len
                    if (n > 100) throw IOException("No space left on device")
                    real.write(b, off, len)
                }
                override fun close() = real.close()
            }
        })
        val r = h.run(h.updater(downloader = full))
        assertEquals(FailReason.DISK_WRITE, failureOf(r).reason)
        assertEquals(listOf("upd-am-dictionary-shared.pack"), h.installedPacks())
        assertArrayEquals(before, target.readBytes())
        assertTrue(h.journal.pending().isEmpty())

        // Free space check up front: not even a download is started.
        val n = h.artifactRequests().size
        h.clearOffers()
        h.publish(h.pack("1.3.0"), "1.3.0")
        val r2 = h.run(h.updater(freeSpace = { 1000L }))
        assertEquals(FailReason.NOT_ENOUGH_SPACE, failureOf(r2).reason)
        assertEquals(n, h.artifactRequests().size)
        assertArrayEquals(before, target.readBytes())
    }

    @Test
    fun noTrustKeysMeansNoUpdates() {
        h.publish(h.pack("1.1.0"), "1.1.0")
        val r = h.run(h.updater(trustKeys = emptyList()))
        assertEquals(RunStatus.NO_TRUST, r.status)
        assertEquals(0, h.requests.size)
    }

    @Test
    fun discoveryFailureIsReportedAndRetryable() {
        h.discoveryStatus = 503
        val r = h.run()
        assertEquals(RunStatus.DISCOVERY_FAILED, r.status)
        assertTrue(r.retryable)
        h.discoveryStatus = 400
        assertFalse(h.run().retryable)
    }

    @Test
    fun concurrentChecksDoNotCorruptState() {
        h.publish(h.pack("1.1.0"), "1.1.0")
        h.artifactDelayMs = 400
        val started = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit<UpdateReport> { started.await(); h.run(h.updater()) }
            val b = pool.submit<UpdateReport> { started.await(); h.run(h.updater()) }
            started.countDown()
            val results = listOf(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS))
            assertEquals(1, results.count { it.status == RunStatus.DONE && it.installed.size == 1 })
            assertEquals(1, results.count { it.status == RunStatus.BUSY })
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, h.artifactRequests().size)
        assertEquals(listOf("upd-am-dictionary-shared.pack"), h.installedPacks())
        assertEquals(1, h.journal.pending().size)
        // And the result loads.
        assertEquals("1.1.0", load(h.runtime()).version())
    }
}
