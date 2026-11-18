package com.qalsmartkeyboard.packsync

import com.qalsmartkeyboard.api.ApiError
import com.qalsmartkeyboard.ime.packs.PackFiles
import com.qalsmartkeyboard.ime.packs.PackJournal
import com.qalsmartkeyboard.ime.packs.PackManager
import com.qalsmartkeyboard.ime.packs.PendingSwap
import com.qalsmartkeyboard.ime.packs.SemVer
import com.qalsmartkeyboard.ime.packs.TrustKey
import com.qalsmartkeyboard.ime.packs.VersionStore
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock

/**
 * Language-pack updater (plan A2.3). One [runOnce] is one check: discovery, planning, then per
 * candidate download, verification and an atomic swap into the IME's installed directory.
 *
 * Safety rules (each has a test in `TamperTest`):
 * - Nothing in the installed directory is touched until the whole container is on disk in a temp
 *   file next to it, within its announced size, and verified by the core engine on a throw-away
 *   engine against the **build-time** trust keys. Keys the server announces are never trusted;
 *   keys it announces as REVOKED (and that the build trusts) are refused.
 * - The signed manifest must match the discovery entry (id, version, slot, SHA-256, size, key) and
 *   must be a non-delta pack newer than the version floor and not above this app version.
 * - Swap: the journal records the swap (staged), the current pack moves to `<name>.prev`, the new
 *   file is renamed over `<name>`, the swap is activated and the IME is told to reload. Failures
 *   undo the first rename. The version floor is not touched here: the IME raises it when the pack
 *   loads (A1.6), and the journal restores it on rollback.
 * - Only one run at a time (a JVM lock plus a file lock); a second concurrent run returns BUSY.
 * - A candidate that failed verification is not fetched again for [cooldownMillis]; a rolled-back
 *   one is blacklisted by the [PackJournal].
 *
 * Blocking; call from a worker thread. Logs contain file names, resource ids and reason codes only.
 */
class PackUpdater(
    /** `<noBackupFilesDir>/packs`. */
    private val root: File,
    private val installedDir: File,
    private val trust: List<TrustKey>,
    private val discovery: PackDiscovery,
    private val downloader: ArtifactDownloader,
    private val versions: () -> VersionStore = { VersionStore(File(root, "versions.txt")) },
    private val journal: PackJournal = PackJournal(root, installedDir),
    private val state: UpdateState = UpdateState(File(root, "update")),
    private val freeSpace: (File) -> Long = { it.usableSpace },
    private val cooldownMillis: Long = 6 * 60 * 60 * 1000L,
    private val log: (String) -> Unit = {},
) {
    private val verifier = PackVerifier(trust)

    fun runOnce(request: UpdateRequest): UpdateReport {
        if (trust.isEmpty()) return UpdateReport(RunStatus.NO_TRUST)
        return exclusive { check(request) } ?: UpdateReport(RunStatus.BUSY)
    }

    private fun check(request: UpdateRequest): UpdateReport {
        installedDir.mkdirs()
        journal.recoverStaged()
        cleanTemps()

        // Revocations first: the freshest list wins, the cache covers an offline run.
        val revoked = try {
            discovery.signingKeys().filter { it.status == "REVOKED" }.map { it.keyId }.toSet().also { state.saveRevokedKeys(it) }
        } catch (e: ApiError) {
            log("signing keys unavailable: ${e.javaClass.simpleName}")
            state.revokedKeys()
        } catch (e: IOException) {
            log("signing keys unavailable: ${e.javaClass.simpleName}")
            state.revokedKeys()
        }

        val offers = ArrayList<ResourceOffer>()
        try {
            for (lang in request.languages.distinct()) offers += discovery.offers(lang, request.channel, request.appVersion)
        } catch (e: ApiError) {
            log("discovery failed: ${e.javaClass.simpleName} ${e.status ?: ""}")
            return UpdateReport(RunStatus.DISCOVERY_FAILED, retryable = e.isTransient())
        } catch (e: IOException) {
            log("discovery failed: ${e.javaClass.simpleName}")
            return UpdateReport(RunStatus.DISCOVERY_FAILED, retryable = true)
        }

        val store = versions()
        val ctx = PlanContext(
            languages = request.languages.toSet(),
            appVersion = AppVersion.parse(request.appVersion),
            floor = store::get,
            isBlacklisted = journal::isBlacklisted,
            pendingSlots = journal.pending().map { it.slot }.toSet(),
        )
        val installed = ArrayList<Installed>()
        val failures = ArrayList<Failure>()
        var retryable = false
        val done = HashSet<String>()
        for (offer in UpdatePlanner.plan(offers, ctx)) {
            if (offer.slot in done) continue // a newer candidate of this slot already installed
            if (state.inCooldown(offer.id, offer.version)) {
                failures += Failure(offer.id, offer.version, FailReason.COOLDOWN)
                continue
            }
            when (val r = attempt(offer, ctx, store, revoked)) {
                is Attempt.Installed -> {
                    installed += r.pack
                    done += offer.slot
                }
                is Attempt.Failed -> {
                    failures += r.failure
                    if (r.failure.reason == FailReason.NETWORK || r.failure.reason == FailReason.HTTP_STATUS) retryable = true
                    if (r.failure.reason in COOLDOWN_REASONS) state.startCooldown(offer.id, offer.version, cooldownMillis)
                }
            }
        }
        if (installed.isNotEmpty()) journal.requestReload()
        return UpdateReport(RunStatus.DONE, installed, failures, retryable)
    }

    private sealed interface Attempt {
        class Installed(val pack: com.qalsmartkeyboard.packsync.Installed) : Attempt
        class Failed(val failure: Failure) : Attempt
    }

    private fun attempt(offer: ResourceOffer, ctx: PlanContext, store: VersionStore, revoked: Set<String>): Attempt {
        fun fail(reason: FailReason, detail: String? = null): Attempt {
            log("pack ${offer.id} ${offer.version} not installed: $reason${detail?.let { " $it" } ?: ""}")
            return Attempt.Failed(Failure(offer.id, offer.version, reason, detail))
        }

        val container = offer.containerSizeBytes
        val cap = container ?: PackManager.MAX_FILE_BYTES
        if (cap > PackManager.MAX_FILE_BYTES) return fail(FailReason.TOO_LARGE)
        if (freeSpace(installedDir) < cap + SPACE_MARGIN) return fail(FailReason.NOT_ENOUGH_SPACE)

        // 1. Download to a temp file next to the installed packs (never a `.pack` name: the IME ignores it).
        val tmp = File(installedDir, ".dl-${UUID.randomUUID()}.tmp")
        try {
            try {
                downloader.download(offer.artifactUrl, tmp, container, cap)
            } catch (e: DownloadException) {
                return fail(e.reason)
            }

            // 2. Verify with the core on a throw-away engine, against the build-time keys.
            val bytes = try {
                tmp.readBytes()
            } catch (_: IOException) {
                return fail(FailReason.DISK_WRITE)
            }
            val info = when (val v = verifier.verify(bytes, revoked)) {
                is PackVerifier.Verdict.Ok -> v.info
                is PackVerifier.Verdict.Rejected -> return fail(FailReason.VERIFY_FAILED, v.code)
            }

            // 3. The signed manifest must be the one discovery announced, and acceptable here.
            if (info.packId != offer.id || info.version != offer.version || info.languageCode != offer.languageCode ||
                info.resourceType.name != offer.resourceType || info.platform.name != offer.platform ||
                info.sha256 != offer.sha256 || info.sizeBytes.toLong() != offer.sizeBytes || info.keyId != offer.signatureKeyId
            ) {
                return fail(FailReason.MANIFEST_MISMATCH)
            }
            if (info.isDelta) return fail(FailReason.DELTA_NOT_SUPPORTED)
            if (!info.payloadUsable) return fail(FailReason.PAYLOAD_UNUSABLE)
            if (!UpdatePlanner.meetsMinApp(info.minAppVersion, ctx.appVersion)) return fail(FailReason.APP_TOO_OLD)
            val floor = ctx.floor(offer.slot)
            if (floor != null && SemVer.parseOrLowest(info.version) <= floor) return fail(FailReason.DOWNGRADE)

            // 4. Atomic swap.
            return swap(offer, tmp, store)
        } finally {
            tmp.delete()
        }
    }

    private fun swap(offer: ResourceOffer, tmp: File, store: VersionStore): Attempt {
        val name = "upd-${offer.languageCode}-${offer.resourceType.lowercase().replace('_', '-')}-${offer.platform.lowercase()}.pack"
        val target = File(installedDir, name)
        val prev = File(installedDir, "$name.prev")
        val hadPrev = target.exists()
        prev.delete() // no unconfirmed swap exists for this slot (the planner checked): a leftover is stale
        try {
            journal.recordSwap(
                PendingSwap(name, offer.id, offer.version, offer.slot, store.getRaw(offer.slot), hadPrev, attempts = PendingSwap.STAGED),
            )
        } catch (_: IOException) {
            return Attempt.Failed(Failure(offer.id, offer.version, FailReason.SWAP_FAILED, "journal"))
        }
        try {
            if (hadPrev) PackFiles.replace(target, prev)
            try {
                PackFiles.replace(tmp, target)
            } catch (e: IOException) {
                if (hadPrev && !target.exists()) PackFiles.replace(prev, target) // undo: the old pack stays installed
                throw e
            }
            journal.activateSwap(name)
        } catch (_: IOException) {
            runCatching { journal.dropSwap(name) }
            log("pack $name swap failed")
            return Attempt.Failed(Failure(offer.id, offer.version, FailReason.SWAP_FAILED))
        }
        log("pack $name installed: ${offer.id} ${offer.version}")
        return Attempt.Installed(Installed(name, offer.id, offer.version))
    }

    private fun cleanTemps() {
        installedDir.listFiles { f -> f.isFile && f.name.startsWith(".dl-") && f.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    private fun ApiError.isTransient(): Boolean = when (this) {
        is ApiError.Network, is ApiError.Timeout, is ApiError.RateLimited -> true
        is ApiError.UpdateRequired, is ApiError.SignedOut -> false
        else -> (status ?: 0) >= 500
    }

    /** Runs [block] if no other check holds the lock (this process or, via the lock file, another). */
    private fun <T> exclusive(block: () -> T): T? {
        if (!PROCESS_LOCK.tryLock()) return null
        try {
            val dir = File(root, "update").also { it.mkdirs() }
            RandomAccessFile(File(dir, "lock"), "rw").use { raf ->
                val lock = try {
                    raf.channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                } ?: return null
                try {
                    return block()
                } finally {
                    lock.release()
                }
            }
        } finally {
            PROCESS_LOCK.unlock()
        }
    }

    companion object {
        private val PROCESS_LOCK = ReentrantLock()
        private const val SPACE_MARGIN = 1L * 1024 * 1024

        /** Failures that point at the candidate itself (not the network or the disk): do not retry at once. */
        private val COOLDOWN_REASONS = setOf(
            FailReason.VERIFY_FAILED, FailReason.MANIFEST_MISMATCH, FailReason.DELTA_NOT_SUPPORTED,
            FailReason.PAYLOAD_UNUSABLE, FailReason.TRUNCATED, FailReason.TOO_LARGE, FailReason.DOWNGRADE,
        )
    }
}
