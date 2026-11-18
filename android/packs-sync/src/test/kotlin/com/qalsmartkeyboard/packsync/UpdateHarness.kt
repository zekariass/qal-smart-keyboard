package com.qalsmartkeyboard.packsync

import com.qalsmartkeyboard.api.ApiClients
import com.qalsmartkeyboard.api.ClientInfo
import com.qalsmartkeyboard.api.ClientSurface
import com.qalsmartkeyboard.api.InMemoryTokenStore
import com.qalsmartkeyboard.api.SessionManager
import com.qalsmartkeyboard.api.TokenPair
import com.qalsmartkeyboard.api.TransportConfig
import com.qalsmartkeyboard.ime.packs.PackFile
import com.qalsmartkeyboard.ime.packs.PackJournal
import com.qalsmartkeyboard.ime.packs.PackManager
import com.qalsmartkeyboard.ime.packs.PackOrigin
import com.qalsmartkeyboard.ime.packs.PackRuntime
import com.qalsmartkeyboard.ime.packs.TrustKey
import com.qalsmartkeyboard.ime.packs.VersionStore
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/** The id of the pack under test (one slot: am / DICTIONARY / SHARED). */
const val ID_A = "11111111-2222-4333-8444-555555555555"
const val ID_B = "aaaaaaaa-2222-4333-8444-555555555555"
const val SLOT = "am|DICTIONARY|SHARED"

/**
 * A fake backend (MockWebServer: discovery, signing keys, artifacts) plus the app's directories in
 * a temp dir. [updater] is the real [PackUpdater] over the real generated API client, the real
 * downloader and the real core (JNA).
 */
class UpdateHarness(
    val signer: TestSigner = TestSigner("test-key"),
    var appVersion: String = "1.0",
) {
    val dir: File = Files.createTempDirectory("qal-packsync").toFile()
    val root = File(dir, "packs")
    val installed = File(root, "installed").also { it.mkdirs() }
    val server = MockWebServer().also { it.start() }
    val logs = CopyOnWriteArrayList<String>()

    /** A real, parsable lexicon payload from the bundled seed pack. */
    val lexicon: ByteArray = TestPacks.artifactOf(File("../ime/src/main/assets/packs/am-dictionary.pack").readBytes())

    // ---- fake backend state
    val offers = CopyOnWriteArrayList<String>()
    val artifacts = ConcurrentHashMap<String, ByteArray>()
    val keyList = CopyOnWriteArrayList<String>()
    @Volatile var keysStatus = 200
    @Volatile var discoveryStatus = 200
    @Volatile var artifactDelayMs = 0L
    @Volatile var artifactHook: ((String) -> MockResponse?)? = null
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    fun artifactRequests() = requests.filter { it.path!!.startsWith("/artifacts/") }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.path!!
                return when {
                    path.startsWith("/api/v1/language-resources") -> {
                        if (discoveryStatus != 200) return MockResponse().setResponseCode(discoveryStatus).setBody("{}")
                        val lang = request.requestUrl!!.queryParameter("languageCode")
                        val items = offers.filter { lang == null || it.contains("\"languageCode\":\"$lang\"") }
                        json("""{"items":[${items.joinToString(",")}]}""")
                    }
                    path.startsWith("/api/v1/resource-signing-keys") ->
                        if (keysStatus != 200) MockResponse().setResponseCode(keysStatus).setBody("{}") else json("""{"items":[${keyList.joinToString(",")}]}""")
                    path.startsWith("/artifacts/") -> {
                        if (artifactDelayMs > 0) Thread.sleep(artifactDelayMs)
                        artifactHook?.invoke(path)?.let { return it }
                        val bytes = artifacts[path.removePrefix("/artifacts/")] ?: return MockResponse().setResponseCode(404)
                        MockResponse().setBody(okio.Buffer().write(bytes))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    private fun json(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    // ---- building packs and offers

    fun pack(version: String, by: TestSigner = signer, id: String = ID_A, artifact: ByteArray = lexicon) =
        by.pack("am", "DICTIONARY", version, artifact, resourceId = id)

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Publishes [container] and a discovery entry for it. The entry's hash, sizes and key come from
     * the container (what an honest server says) unless overridden.
     */
    fun publish(
        container: ByteArray,
        version: String,
        id: String = ID_A,
        servedBytes: ByteArray = container,
        sha256: String = sha(TestPacks.artifactOf(container)),
        containerSize: Long? = container.size.toLong(),
        keyId: String = signer.keyId,
        baseResourceId: String? = null,
        minAppVersion: String? = null,
        language: String = "am",
        type: String = "DICTIONARY",
        platform: String = "SHARED",
    ): String {
        val name = "$id-$version.pack"
        artifacts[name] = servedBytes
        val entry = buildString {
            append("""{"id":"$id","languageCode":"$language","resourceType":"$type","version":"$version","platform":"$platform",""")
            append(""""channel":"STABLE","artifactUrl":"http://localhost:${server.port}/artifacts/$name",""")
            append(""""sha256":"$sha256","sizeBytes":${TestPacks.artifactOf(container).size},""")
            containerSize?.let { append(""""containerSizeBytes":$it,""") }
            baseResourceId?.let { append(""""baseResourceId":"$it",""") }
            minAppVersion?.let { append(""""minAppVersion":"$it",""") }
            append(""""signatureKeyId":"$keyId"}""")
        }
        offers += entry
        return name
    }

    fun announceKey(keyId: String, status: String) {
        keyList += """{"keyId":"$keyId","algorithm":"Ed25519","publicKey":"AAAA","status":"$status"}"""
    }

    fun clearOffers() {
        offers.clear()
    }

    // ---- the app side

    val trust: List<TrustKey> get() = listOf(signer.trust)
    val journal = PackJournal(root, installed, log = { logs += it })
    val versionFile = File(root, "versions.txt")

    fun updater(
        downloader: ArtifactDownloader = HttpArtifactDownloader(),
        freeSpace: (File) -> Long = { Long.MAX_VALUE / 2 },
        trustKeys: List<TrustKey> = trust,
        signedIn: Boolean = false,
    ): PackUpdater {
        val session = SessionManager(InMemoryTokenStore(if (signedIn) TokenPair("access-token", "refresh-token") else null))
        val clients = ApiClients(TransportConfig("http://localhost:${server.port}", ClientInfo("1.0", ClientSurface.COMPANION)), session)
        return PackUpdater(
            root, installed, trustKeys, ApiPackDiscovery(clients), downloader,
            versions = { VersionStore(versionFile) }, journal = journal, freeSpace = freeSpace, log = { logs += it },
        )
    }

    fun run(updater: PackUpdater = updater()) = updater.runOnce(UpdateRequest(listOf("am"), "STABLE", appVersion))

    /** The IME side: the real pack manager + journal over the same directories, bundled am-dictionary 1.0.0. */
    fun runtime(maxUnconfirmed: Int = PackJournal.DEFAULT_MAX_UNCONFIRMED_STARTS): PackRuntime {
        val bundled = pack("1.0.0", id = ID_B)
        val versions = VersionStore(versionFile)
        val j = PackJournal(root, installed, maxUnconfirmed, log = { logs += it })
        return PackRuntime(
            PackManager(trust, { listOf(PackFile("am-dictionary.pack", PackOrigin.BUNDLED, { bundled })) }, installed, versions, log = { logs += it }),
            j,
            versions,
        )
    }

    fun floor(): String? = VersionStore(versionFile).getRaw(SLOT)

    fun installedPacks(): List<String> = installed.listFiles()!!.map { it.name }.sorted()

    fun close() {
        server.shutdown()
        dir.deleteRecursively()
    }
}
