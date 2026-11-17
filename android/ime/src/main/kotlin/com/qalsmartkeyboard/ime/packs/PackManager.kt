package com.qalsmartkeyboard.ime.packs

import java.io.File
import java.io.IOException
import uniffi.qal_keyboard_core.CoreException
import uniffi.qal_keyboard_core.EngineConfig
import uniffi.qal_keyboard_core.KeyboardEngine
import uniffi.qal_keyboard_core.PackInfo

/** Where a pack file came from. */
enum class PackOrigin { BUNDLED, INSTALLED }

/** Thrown by a [PackFile.read] whose file exceeds [PackManager.MAX_FILE_BYTES]. */
class PackTooLargeException : IOException()

/** A pack file offered to the manager. [delete] is null for read-only sources (bundled assets). */
class PackFile(
    val name: String,
    val origin: PackOrigin,
    val read: () -> ByteArray,
    val delete: (() -> Unit)? = null,
)

/** Why a candidate pack was not used. Only this code and the file name are ever logged (N2). */
enum class RejectReason {
    UNREADABLE,
    MALFORMED,
    TOO_LARGE,
    UNSUPPORTED_FORMAT_VERSION,
    UNKNOWN_KEY,
    REVOKED_KEY,
    BAD_SIGNATURE,
    INVALID_MANIFEST,
    UNSUPPORTED_RESOURCE_TYPE,
    KEY_MISMATCH,
    SIZE_MISMATCH,
    CHECKSUM_MISMATCH,
    DELTA_NOT_SUPPORTED,
    INVALID_ARTIFACT,

    /** Signed and intact, but a dictionary / bigram payload that does not parse. */
    PAYLOAD_UNUSABLE,

    /** Installed pack older than the highest version accepted so far for its slot. */
    DOWNGRADE,

    /** Valid, but another pack of the same slot is newer (or the bundled one is equal). */
    SUPERSEDED,

    /** Engine failure not covered above (kept generic on purpose). */
    OTHER,
}

data class Rejection(val file: String, val origin: PackOrigin, val reason: RejectReason)

/** What the manager activated. */
data class ActivePack(val file: String, val origin: PackOrigin, val info: PackInfo)

/** The loaded engine and what happened to each candidate. The caller owns (and closes) [engine]. */
class LoadResult(
    val engine: KeyboardEngine,
    val active: List<ActivePack>,
    val rejected: List<Rejection>,
)

/**
 * Pack manager (plan A1.6; SD 7.6): picks, per (language, resource type, platform), the newest
 * valid pack among the bundled set and the app-private installed directory, and loads the winners
 * into one core engine.
 *
 * - Trust: [trust] keys come from the build configuration ([PackTrust]).
 * - Corrupt packs (bad signature, malformed, unknown key, size / SHA mismatch, unparsable
 *   dictionary payload, ...) are skipped; an installed one is deleted, a bundled one cannot be.
 *   The bundled pack then wins by default, so the keyboard always has the shipped data.
 * - Downgrade protection ([VersionStore]): an installed pack older than the highest version
 *   accepted so far for its slot is refused. Bundled packs are exempt: they ship inside the signed
 *   APK, so they are an always-allowed floor and not a rollback vector. Only a valid winner raises
 *   the stored version, so a corrupt or unusable pack can never lock out a later good one.
 * - Each candidate is verified on a throw-away engine first (its manifest is needed to rank it),
 *   then the winners are loaded into the final engine, so a rejected pack never touches it. Cost:
 *   each winner is verified twice at start-up (background thread).
 *
 * Call [load] off the main thread. Not thread-safe; one manager per process.
 */
class PackManager(
    private val trust: List<TrustKey>,
    private val bundled: () -> List<PackFile>,
    private val installedDir: File?,
    private val versions: VersionStore,
    private val maxCandidates: UInt = 8u,
    private val log: (String) -> Unit = {},
) {
    private class Candidate(val file: PackFile, val bytes: ByteArray, val info: PackInfo) {
        val slot = VersionStore.slot(info.languageCode, info.resourceType.name, info.platform.name)
        val version = SemVer.parseOrLowest(info.version)
    }

    private fun config() = EngineConfig(maxCandidates, trust.map { it.toCore() }, null)

    fun load(): LoadResult {
        val rejected = ArrayList<Rejection>()
        fun reject(f: PackFile, r: RejectReason) {
            rejected += Rejection(f.name, f.origin, r)
            log("pack ${f.name} rejected: $r")
            if (f.origin == PackOrigin.INSTALLED) {
                try {
                    f.delete?.invoke()
                } catch (_: IOException) {
                    log("pack ${f.name} not removable")
                }
            }
        }
        if (!versions.readable) log("version store unreadable: no downgrade floor this run")

        // 1. Verify every candidate on its own engine.
        val valid = ArrayList<Candidate>()
        for (f in bundled() + installedFiles()) {
            val bytes = try {
                f.read()
            } catch (_: PackTooLargeException) {
                reject(f, RejectReason.TOO_LARGE)
                continue
            } catch (_: IOException) {
                reject(f, RejectReason.UNREADABLE)
                continue
            }
            when (val p = probe(bytes)) {
                is Probe.Failed -> reject(f, p.reason)
                is Probe.Ok -> {
                    val c = Candidate(f, bytes, p.info)
                    val floor = versions.get(c.slot)
                    when {
                        !p.info.payloadUsable -> reject(f, RejectReason.PAYLOAD_UNUSABLE)
                        f.origin == PackOrigin.INSTALLED && floor != null && c.version < floor ->
                            reject(f, RejectReason.DOWNGRADE)
                        else -> valid += c
                    }
                }
            }
        }

        // 2. Newest valid pack per slot; on a tie the bundled (immutable) one wins.
        val winners = ArrayList<Candidate>()
        for (group in valid.groupBy { it.slot }.values) {
            val best = group.maxWith(compareBy<Candidate> { it.version }.thenBy { it.file.origin == PackOrigin.BUNDLED })
            winners += best
            for (c in group) if (c !== best) reject(c.file, RejectReason.SUPERSEDED)
        }

        // 3. Load the winners into the final engine and remember their versions.
        val engine = KeyboardEngine(config())
        val active = ArrayList<ActivePack>()
        try {
            for (w in winners.sortedBy { it.file.name }) {
                try {
                    active += ActivePack(w.file.name, w.file.origin, engine.loadPack(w.bytes))
                    versions.raise(w.slot, w.info.version)
                } catch (e: CoreException) {
                    reject(w.file, reasonOf(e))
                }
            }
            try {
                versions.save()
            } catch (_: IOException) {
                log("version store not writable")
            }
        } catch (t: Throwable) {
            engine.close()
            throw t
        }
        return LoadResult(engine, active, rejected)
    }

    private sealed interface Probe {
        class Ok(val info: PackInfo) : Probe
        class Failed(val reason: RejectReason) : Probe
    }

    private fun probe(bytes: ByteArray): Probe {
        val e = KeyboardEngine(config())
        return try {
            Probe.Ok(e.loadPack(bytes))
        } catch (ex: CoreException) {
            Probe.Failed(reasonOf(ex))
        } finally {
            e.close()
        }
    }

    private fun reasonOf(e: CoreException): RejectReason = when (e) {
        is CoreException.PackRejected -> RejectReason.entries.firstOrNull { it.name == e.reason.name } ?: RejectReason.OTHER
        else -> RejectReason.OTHER
    }

    private fun installedFiles(): List<PackFile> {
        val dir = installedDir ?: return emptyList()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".pack") } ?: return emptyList()
        return files.sortedBy { it.name }.take(MAX_INSTALLED_FILES).map { f ->
            PackFile(
                f.name,
                PackOrigin.INSTALLED,
                read = {
                    if (f.length() > MAX_FILE_BYTES) throw PackTooLargeException()
                    f.readBytes()
                },
                delete = { f.delete() },
            )
        }
    }

    companion object {
        const val MAX_FILE_BYTES = 64L * 1024 * 1024
        const val MAX_INSTALLED_FILES = 256
    }
}
