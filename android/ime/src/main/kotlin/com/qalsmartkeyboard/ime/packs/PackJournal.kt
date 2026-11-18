package com.qalsmartkeyboard.ime.packs

import java.io.File
import java.io.IOException

/**
 * One installed pack that was swapped in by the updater and that the IME has not yet confirmed as
 * healthy (plan A2.3). A `<file>.prev` holds the previous pack until then.
 */
data class PendingSwap(
    /** Installed file name (a `.pack` in the installed directory). */
    val file: String,
    val resourceId: String,
    val version: String,
    /** Version-store slot of the pack. */
    val slot: String,
    /** The version floor of [slot] before the swap (null: none), restored on rollback. */
    val floorBefore: String?,
    /** True when a `<file>.prev` holds the previous pack. */
    val hadPrev: Boolean,
    /**
     * Loads started since the swap without a confirmation. [STAGED] (-1) while the updater is still
     * renaming files: the IME leaves such an entry alone.
     */
    val attempts: Int = 0,
) {
    val staged: Boolean get() = attempts == STAGED

    companion object {
        const val STAGED = -1
    }
}

/**
 * Health journal of installed pack updates (plan A2.3): the contract between the app-side updater
 * (`:packs-sync`, which has the network) and the IME (which has none). Both only touch files under
 * `<noBackupFilesDir>/packs`:
 *
 * - `update/pending.txt`: swaps not yet confirmed healthy. The updater records one before it
 *   swaps; the IME removes it on [confirm].
 * - `update/blacklist.txt`: `resourceId<TAB>version` pairs that were rolled back; the updater never
 *   downloads them again.
 * - `update/reload.flag`: a token the updater changes after a swap; the IME compares it with the
 *   one it last saw when the keyboard is shown and reloads the packs when it differs.
 *
 * Health signal: a swapped-in pack is healthy when (1) it loaded and won its slot ([afterLoad]) and
 * (2) the engine built from it survived to the first time the keyboard was shown ([confirm]).
 * A load that rejects it, or [maxUnconfirmedStarts] loads in a row that never reached (2) (the
 * process died: a native crash), roll it back: the new file is removed, `.prev` is restored, the
 * version floor goes back to its earlier value and the resource id + version is blacklisted. The
 * bundled packs are the final fallback; they are never touched.
 *
 * All state changes are serialised through one process-wide lock (the app and the IME share a
 * process) and written atomically. Contents of packs are never logged: only file names, resource
 * ids and reason codes.
 */
class PackJournal(
    private val root: File,
    private val installedDir: File,
    private val maxUnconfirmedStarts: Int = DEFAULT_MAX_UNCONFIRMED_STARTS,
    private val log: (String) -> Unit = {},
) {
    private val dir = File(root, "update")
    private val pendingFile = File(dir, "pending.txt")
    private val blacklistFile = File(dir, "blacklist.txt")
    private val reloadFile = File(dir, "reload.flag")

    // ------------------------------------------------------------------ queries

    fun pending(): List<PendingSwap> = synchronized(LOCK) { readPending() }

    fun isBlacklisted(resourceId: String, version: String): Boolean = synchronized(LOCK) {
        PackFiles.readLines(blacklistFile).any {
            val f = it.split('\t')
            f.getOrNull(0) == resourceId && f.getOrNull(1) == version
        }
    }

    // ------------------------------------------------------------------ updater side

    /** Records a swap that is about to happen (call before the rename). Replaces an entry of the same file. */
    fun recordSwap(swap: PendingSwap) = synchronized(LOCK) {
        val rest = readPending().filter { it.file != swap.file }
        writePending(rest + swap)
    }

    /** The renames are done: from now on the IME counts and judges this swap. */
    fun activateSwap(file: String) = synchronized(LOCK) {
        writePending(readPending().map { if (it.file == file && it.staged) it.copy(attempts = 0) else it })
    }

    /**
     * Undoes swaps the updater began but never finished (the process died between the renames).
     * Call while holding the updater's exclusive lock. No blacklist: the pack was never judged.
     */
    fun recoverStaged() = synchronized(LOCK) {
        val all = readPending()
        for (p in all.filter { it.staged }) {
            val target = File(installedDir, p.file)
            val prev = prevOf(p.file)
            try {
                if (prev.exists()) PackFiles.replace(prev, target) else target.delete()
            } catch (_: IOException) {
                log("pack ${p.file} staged swap not undone")
            }
            log("pack ${p.file} staged swap undone")
        }
        if (all.any { it.staged }) writePending(all.filter { !it.staged })
    }

    /** Forgets a recorded swap whose rename failed and was undone. */
    fun dropSwap(file: String) = synchronized(LOCK) { writePending(readPending().filter { it.file != file }) }

    /** Tells the IME to reload its packs the next time the keyboard is shown. */
    fun requestReload() = synchronized(LOCK) {
        try {
            PackFiles.writeAtomic(reloadFile, "${System.nanoTime()}-${++reloadCounter}\n")
        } catch (_: IOException) {
            log("reload flag not written")
        }
    }

    /**
     * ADR 0007 D34: quarantines the installed pack [file] because the key that signed it was revoked
     * (the updater found out from `/resource-signing-keys`; the build trusts that key, so the pack
     * still verified). The pack is moved to `<packs>/quarantine/` (outside the installed directory,
     * so the IME never sees it; the newest [MAX_QUARANTINED] are kept for diagnosis, signed packs hold
     * no user data), its `.prev` is dropped (the slot falls back to the **bundled** pack, the IME's
     * final fallback), a pending swap entry for it is removed, the resource id + version is blacklisted
     * and the IME is told to reload. The slot's version floor is removed: a floor raised by a pack
     * from a revoked key must not lock out its legitimate replacement; the bundled pack raises it
     * again when the IME next loads (the updater holds the slot until then, see `UpdateState`).
     * Returns false only if the pack could be neither moved aside nor deleted.
     */
    fun quarantine(file: String, resourceId: String, version: String, slot: String, versions: VersionStore, reason: String): Boolean =
        synchronized(LOCK) {
            log("pack $file ($resourceId $version) quarantined: $reason")
            val target = File(installedDir, file)
            val qdir = File(root, QUARANTINE_DIR)
            var gone = !target.exists()
            if (!gone) {
                try {
                    qdir.mkdirs()
                    val dest = File(qdir, "$file.${System.currentTimeMillis()}")
                    gone = target.renameTo(dest) || (runCatching { target.copyTo(dest, overwrite = true) }.isSuccess && target.delete())
                } catch (_: IOException) {
                }
                if (!gone) gone = target.delete()
            }
            prevOf(file).delete()
            trimQuarantine(qdir)
            try {
                writePending(readPending().filter { it.file != file })
            } catch (_: IOException) {
                // Logged by writePending; the next load drops an entry whose pack is gone.
            }
            versions.restore(slot, null)
            try {
                versions.save()
            } catch (_: IOException) {
                log("version store not writable")
            }
            addToBlacklist(resourceId, version, reason)
            requestReload()
            gone
        }

    private fun trimQuarantine(dir: File) {
        val files = dir.listFiles { f -> f.isFile }?.sortedBy { it.lastModified() } ?: return
        for (f in files.dropLast(MAX_QUARANTINED)) f.delete()
    }

    // ------------------------------------------------------------------ IME side

    /** The current reload token, or null if none was ever requested. */
    fun reloadToken(): String? = synchronized(LOCK) { PackFiles.readLines(reloadFile).firstOrNull() }

    /**
     * Call before loading the packs. Counts this load against every unconfirmed swap and rolls back
     * those that already had [maxUnconfirmedStarts] unconfirmed loads (the process died before the
     * keyboard was shown, e.g. a native crash on first use). Returns the swaps rolled back.
     */
    fun beforeLoad(versions: VersionStore): List<PendingSwap> = synchronized(LOCK) {
        val kept = ArrayList<PendingSwap>()
        val rolled = ArrayList<PendingSwap>()
        for (p in readPending()) {
            if (p.staged) {
                kept += p
            } else if (p.attempts >= maxUnconfirmedStarts) {
                rollbackLocked(p, versions, "CRASH_LOOP", restoreFloor = true)
                rolled += p
            } else {
                kept += p.copy(attempts = p.attempts + 1)
            }
        }
        writePending(kept)
        rolled
    }

    /** What [afterLoad] decided. */
    class AfterLoad(
        /** True when a swap was rolled back: load again to pick up the restored pack. */
        val needsReload: Boolean,
        /** Swaps whose pack is active in the engine; pass to [confirm] once the engine proved healthy. */
        val awaitingConfirmation: List<PendingSwap>,
    )

    /**
     * Call after a load. A pending swap whose pack is not among the active packs (the manager
     * rejected and deleted it, or another pack won the slot) is rolled back.
     */
    fun afterLoad(result: LoadResult, versions: VersionStore): AfterLoad = synchronized(LOCK) {
        val awaiting = ArrayList<PendingSwap>()
        var rolled = false
        for (p in readPending().filter { !it.staged }) {
            val ok = result.active.any {
                it.origin == PackOrigin.INSTALLED && it.file == p.file && it.info.packId == p.resourceId && it.info.version == p.version
            }
            if (ok) {
                awaiting += p
            } else {
                rollbackLocked(p, versions, "LOAD_FAILED", restoreFloor = false)
                rolled = true
            }
        }
        AfterLoad(rolled, awaiting)
    }

    /** The swapped-in packs proved healthy: drop their `.prev` files and journal entries. */
    fun confirm(swaps: List<PendingSwap>) = synchronized(LOCK) {
        if (swaps.isEmpty()) return@synchronized
        val current = readPending()
        val done = current.filter { c -> swaps.any { it.file == c.file && it.resourceId == c.resourceId && it.version == c.version } }
        for (p in done) prevOf(p.file).delete()
        writePending(current - done.toSet())
    }

    /** Rolls back [swap] explicitly (e.g. a health check outside the load path failed). */
    fun rollback(swap: PendingSwap, versions: VersionStore, reason: String) = synchronized(LOCK) {
        rollbackLocked(swap, versions, reason, restoreFloor = true)
    }

    // ------------------------------------------------------------------ internals

    private fun prevOf(file: String) = File(installedDir, "$file.prev")

    private fun rollbackLocked(p: PendingSwap, versions: VersionStore, reason: String, restoreFloor: Boolean) {
        log("pack ${p.file} (${p.resourceId} ${p.version}) rolled back: $reason")
        val target = File(installedDir, p.file)
        val prev = prevOf(p.file)
        try {
            if (prev.exists()) {
                // Replace whatever is there (the bad pack, or nothing) with the previous one.
                PackFiles.replace(prev, target)
            } else if (target.exists() && !target.delete()) {
                log("pack ${p.file} not removable")
            }
        } catch (_: IOException) {
            log("pack ${p.file} previous version not restorable")
            target.delete()
        }
        if (restoreFloor) {
            // The pack was loaded, so it raised the floor; undo that. A pack that failed to load never did.
            versions.restore(p.slot, p.floorBefore)
            try {
                versions.save()
            } catch (_: IOException) {
                log("version store not writable")
            }
        }
        addToBlacklist(p.resourceId, p.version, reason)
        try {
            writePending(readPending().filter { it.file != p.file })
        } catch (_: IOException) {
            // Already logged; the next load retries the rollback bookkeeping.
        }
    }

    private fun addToBlacklist(id: String, version: String, reason: String) {
        val lines = PackFiles.readLines(blacklistFile)
        if (lines.any { it.split('\t').let { f -> f.getOrNull(0) == id && f.getOrNull(1) == version } }) return
        try {
            PackFiles.writeAtomic(blacklistFile, (lines + "$id\t$version\t$reason").joinToString("") { it + "\n" })
        } catch (_: IOException) {
            log("blacklist not writable")
        }
    }

    private fun readPending(): List<PendingSwap> = PackFiles.readLines(pendingFile).mapNotNull { line ->
        val f = line.split('\t')
        if (f.size != 7) return@mapNotNull null
        PendingSwap(f[0], f[1], f[2], f[3], f[4].ifEmpty { null }, f[5] == "1", f[6].toIntOrNull() ?: return@mapNotNull null)
    }

    private fun writePending(list: List<PendingSwap>) {
        try {
            PackFiles.writeAtomic(
                pendingFile,
                list.joinToString("") {
                    "${it.file}\t${it.resourceId}\t${it.version}\t${it.slot}\t${it.floorBefore.orEmpty()}\t${if (it.hadPrev) 1 else 0}\t${it.attempts}\n"
                },
            )
        } catch (e: IOException) {
            log("journal not writable")
            throw e
        }
    }

    companion object {
        const val DEFAULT_MAX_UNCONFIRMED_STARTS = 2
        const val QUARANTINE_DIR = "quarantine"
        const val MAX_QUARANTINED = 8
        private val LOCK = Any()
        private var reloadCounter = 0L
    }
}
