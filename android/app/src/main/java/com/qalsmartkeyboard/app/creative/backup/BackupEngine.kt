package com.qalsmartkeyboard.app.creative.backup

import com.qalsmartkeyboard.ime.draw.DrawingBackupSource
import com.qalsmartkeyboard.ime.draw.DrawingItem
import com.qalsmartkeyboard.ime.draw.RawWriteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

/**
 * What one run did, in counts only (no ids, no content). [stopped] is the error that ended the run early, null when it
 * went through; [notAttempted] is how many drawings were left when it stopped.
 */
data class RunReport(
    /** Backup: drawings sent to the cloud. */
    val uploaded: Int = 0,
    /** Restore: drawings written to the device. */
    val restored: Int = 0,
    /** Drawings that were already the same on both sides. */
    val upToDate: Int = 0,
    /** Backup: the cloud copy is newer than this device's (kept, restore it to take it). */
    val keptNewerCloud: Int = 0,
    /** Restore: this device's copy is newer (never overwritten). */
    val keptNewerLocal: Int = 0,
    /** Backup: over the per-item cap (can never be backed up). */
    val tooLarge: Int = 0,
    /** Backup: could not be read on the device. Restore: the cloud bytes are not a drawing. */
    val invalid: Int = 0,
    /** Restore: made by a newer app version than this one reads. */
    val unsupported: Int = 0,
    /** Restore: the gallery is at its cap, so this new drawing was not added. */
    val deviceFull: Int = 0,
    val failed: Int = 0,
    val notAttempted: Int = 0,
    val stopped: BackupError? = null,
) {
    /** Nothing at all was sent or written and nothing went wrong. */
    val nothingToDo: Boolean get() = stopped == null && failed == 0 && uploaded == 0 && restored == 0 && keptNewerCloud == 0 && keptNewerLocal == 0 &&
        tooLarge == 0 && invalid == 0 && unsupported == 0 && deviceFull == 0
}

/** The answer of a run request. */
sealed interface RunOutcome {
    /** Another run (or a clear) is in progress: nothing was started. */
    data object Busy : RunOutcome

    /** There is no signed-in account to key the record by. */
    data object NotSignedIn : RunOutcome

    data class Finished(val report: RunReport) : RunOutcome
}

/**
 * The additive, explicit sync of the Draw gallery with the cloud (ADR 0050, T8.15 S3). Plain Kotlin over two ports: the
 * device's [DrawingBackupSource] and the cloud's [BackupRemote]. It never runs by itself: the person's tap on "Back up now"
 * or "Restore from cloud" calls it, and only while the consent is granted (the server checks that again on every call).
 *
 * **Back up** lists both sides, then for each local drawing (newest first):
 *  - not in the cloud: upload with `expectedRevision = 0`;
 *  - the cloud holds the revision this device last saw: unchanged locally = nothing to do, changed = upload against that
 *    revision;
 *  - the cloud holds a copy this device has not seen (first backup, or another device wrote it): same time and size = already
 *    the same (recorded, nothing sent); the cloud copy newer = kept (restore takes it, so the newer copy is never lost);
 *    this device's copy newer or equal-time-different = upload against the cloud's revision;
 *  - a 409 `REVISION_CONFLICT` re-reads the cloud listing once and decides again, then counts as failed.
 *
 * Per-item problems (too large, unreadable, a server error) are counted and the run goes on; a problem with the whole account
 * (offline, rate limit, quota, consent, signed out, feature off) stops it and says how many were left. Each success is
 * recorded at once, so a stop or a cancellation loses nothing, and a repeat is cheap and safe (the server treats an identical
 * PUT as a no-op).
 *
 * **Restore** downloads the cloud items that are not on the device, or newer than the device's copy, and stores them through
 * [DrawingBackupSource.writeRaw] (validated there; the stored bytes are exactly the uploaded ones). A newer or equal local
 * drawing is never overwritten; a full gallery, damaged or too-new bytes are counted and skipped.
 *
 * Deletions are never propagated either way. One [Mutex] covers back up, restore and clearing the cloud: a second request
 * while one runs answers [RunOutcome.Busy]. The record belongs to one account: a record of another [userId] is ignored and
 * replaced.
 */
class BackupEngine(
    private val local: DrawingBackupSource,
    private val remote: BackupRemote,
    private val records: BackupRecordStore,
    /** The signed-in account's id (the access token's `sub`), or null when nobody is signed in. */
    private val userId: () -> String?,
) {
    private val lock = Mutex()

    val running: Boolean get() = lock.isLocked

    suspend fun backUp(progress: (done: Int, total: Int) -> Unit = { _, _ -> }): RunOutcome = guarded { uid -> BackUpRun(uid, progress).run() }

    suspend fun restore(progress: (done: Int, total: Int) -> Unit = { _, _ -> }): RunOutcome = guarded { uid -> RestoreRun(uid, progress).run() }

    /** Deletes every cloud copy (after the person confirmed) and forgets what this device recorded. Drawings on the device stay. */
    suspend fun clearCloud(): RunOutcome = guarded { uid ->
        remote.clear()
        records.save(BackupRecord(uid))
        RunReport()
    }

    /** Sign-out, or the consent was withdrawn (the server erased the cloud copies): the device forgets what it uploaded. */
    fun forgetRecord() = records.clear()

    private suspend fun guarded(block: suspend (String) -> RunReport): RunOutcome {
        val uid = userId() ?: return RunOutcome.NotSignedIn
        if (!lock.tryLock()) return RunOutcome.Busy
        try {
            return RunOutcome.Finished(block(uid))
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupException) {
            return RunOutcome.Finished(RunReport(stopped = e.error))
        } catch (_: Exception) {
            return RunOutcome.Finished(RunReport(stopped = BackupError.Failed))
        } finally {
            lock.unlock()
        }
    }

    /** The record of [uid] (an empty one when the stored record is another account's or unreadable), as a mutable map. */
    private fun loadEntries(uid: String): MutableMap<String, RecordEntry> =
        records.load()?.takeIf { it.userId == uid }?.items?.toMutableMap() ?: mutableMapOf()

    private enum class Step { UPLOADED, UP_TO_DATE, CLOUD_NEWER, UNREADABLE }

    private inner class BackUpRun(private val uid: String, private val progress: (Int, Int) -> Unit) {
        private val entries = loadEntries(uid)
        private var cloud: Map<String, RemoteItem> = emptyMap()

        private fun persist() {
            records.save(BackupRecord(uid, entries.toMap()))
        }

        suspend fun run(): RunReport {
            val items = local.listItems()
            cloud = remote.list().associateBy { it.id }
            var report = RunReport()
            var failuresInARow = 0
            for ((index, item) in items.withIndex()) {
                progress(index, items.size)
                if (userId() != uid) return report.copy(stopped = BackupError.SignedOut, notAttempted = items.size - index)
                try {
                    report = when (backUpOne(item)) {
                        Step.UPLOADED -> report.copy(uploaded = report.uploaded + 1)
                        Step.UP_TO_DATE -> report.copy(upToDate = report.upToDate + 1)
                        Step.CLOUD_NEWER -> report.copy(keptNewerCloud = report.keptNewerCloud + 1)
                        Step.UNREADABLE -> report.copy(invalid = report.invalid + 1)
                    }
                    failuresInARow = 0
                } catch (e: BackupException) {
                    val error = e.error
                    if (error.endsRun) return report.copy(stopped = error, notAttempted = items.size - index)
                    report = if (error is BackupError.TooLarge) {
                        report.copy(tooLarge = report.tooLarge + 1)
                    } else {
                        failuresInARow++
                        report.copy(failed = report.failed + 1)
                    }
                    // A server that fails again and again is not worth hammering: stop and say how many were left.
                    if (failuresInARow >= MAX_FAILURES_IN_A_ROW) return report.copy(stopped = BackupError.Failed, notAttempted = items.size - index - 1)
                }
            }
            progress(items.size, items.size)
            return report
        }

        private suspend fun backUpOne(item: DrawingItem): Step {
            var attempt = 0
            while (true) {
                val theirs = cloud[item.id]
                val known = entries[item.id]
                val expected: Long
                when {
                    theirs == null -> expected = 0
                    known != null && known.serverRevision == theirs.revision -> {
                        if (known.localRevision == item.revision) return Step.UP_TO_DATE
                        expected = theirs.revision
                    }
                    else -> {
                        if (theirs.updatedAt == item.updatedAt && theirs.sizeBytes == item.sizeBytes) {
                            entries[item.id] = RecordEntry(item.revision, theirs.revision)
                            persist()
                            return Step.UP_TO_DATE
                        }
                        if (theirs.updatedAt > item.updatedAt) return Step.CLOUD_NEWER
                        expected = theirs.revision
                    }
                }
                val bytes = local.readRaw(item.id) ?: return Step.UNREADABLE
                try {
                    val revision = remote.put(item.id, item.updatedAt, bytes, expected)
                    entries[item.id] = RecordEntry(item.revision, revision)
                    cloud = cloud + (item.id to RemoteItem(item.id, revision, bytes.size.toLong(), item.updatedAt))
                    persist()
                    return Step.UPLOADED
                } catch (e: BackupException) {
                    if (e.error !is BackupError.RevisionConflict || attempt++ > 0) throw e
                    // Somebody else changed it since the listing: read the cloud again and decide once more.
                    cloud = remote.list().associateBy { it.id }
                }
            }
        }
    }

    private inner class RestoreRun(private val uid: String, private val progress: (Int, Int) -> Unit) {
        private val entries = loadEntries(uid)

        suspend fun run(): RunReport {
            val theirs = remote.list().sortedByDescending { it.updatedAt }
            val ours = local.listItems().associateBy { it.id }
            var report = RunReport()
            var deviceFull = false
            val restored = mutableMapOf<String, Long>() // id -> cloud revision
            var failuresInARow = 0
            for ((index, item) in theirs.withIndex()) {
                progress(index, theirs.size)
                if (userId() != uid) {
                    report = report.copy(stopped = BackupError.SignedOut, notAttempted = theirs.size - index)
                    break
                }
                val mine = ours[item.id]
                if (mine != null && mine.updatedAt >= item.updatedAt) {
                    if (mine.updatedAt == item.updatedAt) {
                        if (mine.sizeBytes == item.sizeBytes) entries[item.id] = RecordEntry(mine.revision, item.revision)
                        report = report.copy(upToDate = report.upToDate + 1)
                    } else {
                        report = report.copy(keptNewerLocal = report.keptNewerLocal + 1)
                    }
                    continue
                }
                if (mine == null && deviceFull) {
                    report = report.copy(deviceFull = report.deviceFull + 1)
                    continue
                }
                val blob = try {
                    remote.download(item.id)
                } catch (e: BackupException) {
                    if (e.error.endsRun) {
                        report = report.copy(stopped = e.error, notAttempted = theirs.size - index)
                        break
                    }
                    report = report.copy(failed = report.failed + 1)
                    if (++failuresInARow >= MAX_FAILURES_IN_A_ROW) {
                        report = report.copy(stopped = BackupError.Failed, notAttempted = theirs.size - index - 1)
                        break
                    }
                    continue
                }
                failuresInARow = 0
                report = when (local.writeRaw(item.id, blob.bytes, item.updatedAt)) {
                    RawWriteResult.WRITTEN -> {
                        restored[item.id] = item.revision
                        report.copy(restored = report.restored + 1)
                    }
                    RawWriteResult.INVALID -> report.copy(invalid = report.invalid + 1)
                    RawWriteResult.UNSUPPORTED_VERSION -> report.copy(unsupported = report.unsupported + 1)
                    RawWriteResult.FULL -> {
                        deviceFull = true
                        report.copy(deviceFull = report.deviceFull + 1)
                    }
                    RawWriteResult.FAILED -> report.copy(failed = report.failed + 1)
                }
            }
            if (restored.isNotEmpty()) {
                // The store assigns each restored drawing its own revision: record the one that is really there, so the next
                // "Back up now" does not send them again.
                val now = local.listItems().associateBy { it.id }
                for ((id, serverRevision) in restored) now[id]?.let { entries[id] = RecordEntry(it.revision, serverRevision) }
            }
            records.save(BackupRecord(uid, entries.toMap()))
            if (report.stopped == null) progress(theirs.size, theirs.size)
            return report
        }
    }

    private companion object {
        const val MAX_FAILURES_IN_A_ROW = 3
    }
}
