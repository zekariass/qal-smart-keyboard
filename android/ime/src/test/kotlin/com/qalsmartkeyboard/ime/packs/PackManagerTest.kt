package com.qalsmartkeyboard.ime.packs

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uniffi.qal_keyboard_core.ResourceType

/** A1.6 on the real core (host build via JNA): verification, fallback, version floor. Synthetic packs, test keys only. */
class PackManagerTest {
    private val signer = TestSigner("test-key")
    private val other = TestSigner("other-key")
    private lateinit var dir: File
    private lateinit var installed: File
    private lateinit var storeFile: File
    private val logs = ArrayList<String>()
    private val opened = ArrayList<LoadResult>()
    private val slot = "am|DICTIONARY|SHARED"

    /** A real, parsable lexicon payload taken from the bundled seed pack. */
    private val lexicon = TestPacks.artifactOf(File("src/main/assets/packs/am-dictionary.pack").readBytes())

    private fun dict(v: String, by: TestSigner = signer, artifact: ByteArray = lexicon) = by.pack("am", "DICTIONARY", v, artifact)

    private var bundledPacks = mapOf("am-dictionary.pack" to dict("1.0.0"))

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("qal-packs").toFile()
        installed = File(dir, "installed").also { it.mkdirs() }
        storeFile = File(dir, "versions.txt")
    }

    @After
    fun tearDown() {
        opened.forEach { it.engine.close() }
        dir.deleteRecursively()
    }

    private fun manager(trust: List<TrustKey> = listOf(signer.trust), store: VersionStore = VersionStore(storeFile)) = PackManager(
        trust = trust,
        bundled = { bundledPacks.map { (n, b) -> PackFile(n, PackOrigin.BUNDLED, { b }) } },
        installedDir = installed,
        versions = store,
        log = { logs += it },
    )

    private fun load(m: PackManager = manager()) = m.load().also { opened += it }

    private fun install(name: String, bytes: ByteArray) = File(installed, name).also { it.writeBytes(bytes) }

    private fun LoadResult.dictionary() = active.single { it.info.resourceType == ResourceType.DICTIONARY }

    private fun floor() = VersionStore(storeFile).get(slot)

    @Test
    fun bundledOnlyLoadsWhenNothingIsInstalled() {
        val r = load()
        assertTrue(r.rejected.isEmpty())
        assertEquals(PackOrigin.BUNDLED, r.dictionary().origin)
        assertEquals("1.0.0", r.dictionary().info.version)
        assertEquals(SemVer.parse("1.0.0"), floor())
    }

    @Test
    fun realBundledSetLoadsWithTheBuildTrustConfig() {
        val files = com.qalsmartkeyboard.ime.CoreTestEnv.bundledFiles()
        val r = load(
            PackManager(PackTrust.configured(), { files }, installed, VersionStore(storeFile), log = { logs += it }),
        )
        assertEquals(13, files.size)
        assertEquals(emptyList<Rejection>(), r.rejected)
        assertEquals(13, r.active.size)
        assertEquals(13, r.engine.loadedPacks().size)
    }

    @Test
    fun newerValidInstalledPackWins() {
        install("am-dictionary-2.pack", dict("2.0.0"))
        val r = load()
        assertEquals(PackOrigin.INSTALLED, r.dictionary().origin)
        assertEquals("2.0.0", r.dictionary().info.version)
        assertEquals(listOf(RejectReason.SUPERSEDED), r.rejected.map { it.reason })
        assertEquals(SemVer.parse("2.0.0"), floor())
    }

    @Test
    fun tamperedInstalledPacksAreIgnoredDeletedAndBundledIsUsed() {
        val good = dict("2.0.0")
        val artifactStart = good.size - lexicon.size
        val cases = mapOf(
            "artifact.pack" to (TestPacks.flip(good, good.size - 1) to RejectReason.CHECKSUM_MISMATCH),
            "signature.pack" to (TestPacks.flip(good, 11 + "test-key".length + 5) to RejectReason.BAD_SIGNATURE),
            "manifest.pack" to (TestPacks.flip(good, artifactStart - 8 - 40) to RejectReason.BAD_SIGNATURE),
            "truncated.pack" to (good.copyOf(good.size - 3) to RejectReason.MALFORMED),
            "garbage.pack" to (ByteArray(50) { 7 } to RejectReason.MALFORMED),
        )
        for ((n, c) in cases) install(n, c.first)
        val r = load()
        assertEquals("1.0.0", r.dictionary().info.version)
        assertEquals(PackOrigin.BUNDLED, r.dictionary().origin)
        assertEquals(cases.mapValues { it.value.second }, r.rejected.associate { it.file to it.reason })
        assertTrue("corrupt files are removed", installed.listFiles()!!.isEmpty())
        assertEquals("a corrupt pack never raises the floor", SemVer.parse("1.0.0"), floor())
        // Log lines carry the file name and the reason code only.
        assertEquals(cases.size, logs.size)
        assertTrue(logs.all { l -> cases.keys.any { l == "pack $it rejected: ${cases.getValue(it).second}" } })
    }

    @Test
    fun unknownKeyPackIsIgnored() {
        install("foreign.pack", dict("9.0.0", by = other))
        val r = load()
        assertEquals("1.0.0", r.dictionary().info.version)
        assertEquals(RejectReason.UNKNOWN_KEY, r.rejected.single().reason)
        assertFalse(File(installed, "foreign.pack").exists())
    }

    @Test
    fun correctlySignedButUnparsableDictionaryIsRejectedAndKeepsTheBundledModel() {
        install("bad-payload.pack", dict("2.0.0", artifact = "not a lexicon".toByteArray()))
        val r = load()
        assertEquals("1.0.0", r.dictionary().info.version)
        assertEquals(RejectReason.PAYLOAD_UNUSABLE, r.rejected.single().reason)
        assertEquals(SemVer.parse("1.0.0"), floor())
        // The unusable version did not become the floor: a later good 1.5.0 is accepted.
        install("good.pack", dict("1.5.0"))
        assertEquals("1.5.0", load().dictionary().info.version)
    }

    @Test
    fun downgradeIsRefusedWithThePersistedVersionButBundledStaysAllowed() {
        install("v3.pack", dict("3.0.0"))
        assertEquals("3.0.0", load().dictionary().info.version)
        // v3 is lost (corrupted, then removed): the older bundled pack is still allowed as fallback.
        File(installed, "v3.pack").delete()
        val fallback = load()
        assertEquals(PackOrigin.BUNDLED, fallback.dictionary().origin)
        assertEquals("1.0.0", fallback.dictionary().info.version)
        // A validly signed older installed pack (a replayed download) is refused: the floor is 3.0.0.
        install("v2.pack", dict("2.0.0"))
        val r = load()
        assertEquals(RejectReason.DOWNGRADE, r.rejected.single { it.origin == PackOrigin.INSTALLED }.reason)
        assertEquals(PackOrigin.BUNDLED, r.dictionary().origin)
        assertFalse(File(installed, "v2.pack").exists())
        // The floor itself did not go down; v3 is accepted again.
        assertEquals(SemVer.parse("3.0.0"), floor())
        install("v3.pack", dict("3.0.0"))
        assertEquals("3.0.0", load().dictionary().info.version)
    }

    @Test
    fun persistedVersionsSurviveANewManagerAndStore() {
        install("v3.pack", dict("3.0.0"))
        load(manager(store = VersionStore(storeFile)))
        assertTrue(storeFile.readText().contains("$slot\t3.0.0"))
        install("v2.pack", dict("2.0.0"))
        val r = load(manager(store = VersionStore(storeFile))) // new instances over the same file
        assertEquals(RejectReason.DOWNGRADE, r.rejected.single { it.origin == PackOrigin.INSTALLED }.reason)
        assertEquals(SemVer.parse("3.0.0"), floor())
    }

    @Test
    fun newerBundledPackRaisesTheFloorAndOutranksAnOlderInstalledOne() {
        install("v2.pack", dict("2.0.0"))
        assertEquals("2.0.0", load().dictionary().info.version)
        bundledPacks = mapOf("am-dictionary.pack" to dict("2.5.0")) // an app update ships newer data
        val r = load()
        assertEquals(PackOrigin.BUNDLED, r.dictionary().origin)
        assertEquals("2.5.0", r.dictionary().info.version)
        assertEquals(SemVer.parse("2.5.0"), floor())
    }

    @Test
    fun sameVersionPrefersTheBundledPack() {
        install("same.pack", dict("1.0.0"))
        assertEquals(PackOrigin.BUNDLED, load().dictionary().origin)
    }

    @Test
    fun corruptBundledPackIsSkippedWithoutBreakingTheOthers() {
        bundledPacks = mapOf(
            "am-dictionary.pack" to TestPacks.flip(dict("1.0.0"), 100),
            "x.pack" to signer.pack("am", "OTHER", "1.0.0", byteArrayOf(1)),
        )
        val r = load()
        assertTrue(r.active.none { it.info.resourceType == ResourceType.DICTIONARY })
        assertEquals(1, r.active.size)
        assertEquals(RejectReason.BAD_SIGNATURE, r.rejected.single().reason)
    }

    @Test
    fun missingInstalledDirectoryAndCorruptStoreAreHarmless() {
        storeFile.writeText("garbage line\n$slot\tnot-a-version\n")
        installed.deleteRecursively()
        val r = load()
        assertEquals("1.0.0", r.dictionary().info.version)
        assertEquals(SemVer.parse("1.0.0"), floor())
    }

    @Test
    fun releaseTrustPolicy() {
        val dev = TrustKey("dev", "00".repeat(32), devOnly = true)
        val prod = TrustKey("prod", "00".repeat(32), devOnly = false)
        fun ok(keys: List<TrustKey>, debuggable: Boolean, allow: Boolean) = PackTrust.check(keys, debuggable, allow) == PackTrust.Decision.Ok
        assertTrue(ok(listOf(dev), debuggable = true, allow = false))
        assertFalse(ok(listOf(dev), debuggable = false, allow = false))
        assertTrue(ok(listOf(dev), debuggable = false, allow = true))
        assertFalse(ok(listOf(dev, prod), debuggable = false, allow = false))
        assertTrue(ok(listOf(prod), debuggable = false, allow = false))
        assertFalse(ok(emptyList(), debuggable = true, allow = true))
    }

    @Test
    fun debugBuildTrustsTheDevKeyFromSeedIndex() {
        val keys = PackTrust.configured()
        assertTrue(keys.single().devOnly)
        assertEquals("qal-dev-2fe9e4a0", keys.single().id)
    }

    @Test
    fun semverPrecedence() {
        val order = listOf("0.9.0", "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.10.0", "2.0.0")
        val parsed = order.map { SemVer.parse(it)!! }
        assertEquals(parsed, parsed.shuffled(java.util.Random(7)).sorted())
        assertTrue(SemVer.parseOrLowest("nope") < SemVer.parse("0.0.0")!!)
    }
}
