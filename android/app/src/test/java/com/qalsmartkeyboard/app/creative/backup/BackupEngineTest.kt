package com.qalsmartkeyboard.app.creative.backup

import com.qalsmartkeyboard.ime.draw.RawWriteResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The additive sync engine against fakes of the device's gallery and of the server (ADR 0050, T8.15 S3). */
class BackupEngineTest {
    private val local = FakeLocal()
    private val remote = FakeRemote()
    private val records = MemoryRecords()
    private var user: String? = "user-a"
    private val engine = BackupEngine(local, remote, records, { user })

    private fun report(o: RunOutcome): RunReport = (o as RunOutcome.Finished).report

    private fun backUp(): RunReport = report(runBlocking { engine.backUp() })

    private fun restore(): RunReport = report(runBlocking { engine.restore() })

    private fun seed(vararg ids: String, base: Long = 100) {
        ids.forEachIndexed { i, id -> local.save(id, base + i) }
    }

    // ---- back up ----------------------------------------------------------------------------------------------------

    @Test fun theFirstBackupUploadsEveryDrawingThatIsNotInTheCloudWithExpectedZeroAndRecordsIt() {
        seed("aaaaaa", "bbbbbb", "cccccc")
        val r = backUp()
        assertEquals(3, r.uploaded)
        assertNull(r.stopped)
        assertEquals(setOf("aaaaaa", "bbbbbb", "cccccc"), remote.items.keys)
        assertTrue(remote.puts.all { it.expected == 0L })
        // the bytes are exactly the device's bytes, the time is the drawing's own
        assertTrue(remote.items["bbbbbb"]!!.bytes.contentEquals(local.items["bbbbbb"]!!.bytes))
        assertEquals(101L, remote.items["bbbbbb"]!!.updatedAt)
        val rec = records.record!!
        assertEquals("user-a", rec.userId)
        assertEquals(RecordEntry(local.items["aaaaaa"]!!.revision, 1), rec.items["aaaaaa"])
    }

    @Test fun aSecondRunWithNothingChangedSendsNothing() {
        seed("aaaaaa", "bbbbbb")
        backUp()
        remote.puts.clear()
        val r = backUp()
        assertEquals(0, r.uploaded)
        assertEquals(2, r.upToDate)
        assertTrue(remote.puts.isEmpty())
        assertTrue(r.nothingToDo)
    }

    @Test fun aChangedDrawingIsUploadedAgainAgainstTheRecordedRevision() {
        seed("aaaaaa", "bbbbbb")
        backUp()
        remote.puts.clear()
        local.save("aaaaaa", 500, byteArrayOf(7, 7, 7, 7))
        val r = backUp()
        assertEquals(1, r.uploaded)
        assertEquals(1, r.upToDate)
        assertEquals(listOf(FakeRemote.Put("aaaaaa", 500, 4, 1)), remote.puts)
        assertEquals(2L, remote.items["aaaaaa"]!!.revision)
        assertEquals(RecordEntry(local.items["aaaaaa"]!!.revision, 2), records.record!!.items["aaaaaa"])
    }

    @Test fun aFirstBackupOverAnIdenticalCloudCopySendsNothingAndRecordsIt() {
        seed("aaaaaa")
        remote.otherDevice("aaaaaa", 100, local.items["aaaaaa"]!!.bytes) // same time and size
        val r = backUp()
        assertEquals(1, r.upToDate)
        assertTrue(remote.puts.isEmpty())
        assertEquals(RecordEntry(local.items["aaaaaa"]!!.revision, 1), records.record!!.items["aaaaaa"])
    }

    @Test fun whenTheCloudCopyIsOlderThisDevicesNewerCopyWinsAndTheCloudRevisionIsUsed() {
        seed("aaaaaa", base = 900)
        remote.otherDevice("aaaaaa", 100)
        remote.otherDevice("aaaaaa", 200) // revision 2, still older than the device's 900
        val r = backUp()
        assertEquals(1, r.uploaded)
        assertEquals(2L, remote.puts.single().expected)
        assertEquals(900L, remote.items["aaaaaa"]!!.updatedAt)
        assertEquals(3L, remote.items["aaaaaa"]!!.revision)
    }

    @Test fun whenTheCloudCopyIsNewerItIsKeptForARestoreAndNeverOverwritten() {
        seed("aaaaaa", base = 100)
        remote.otherDevice("aaaaaa", 900, byteArrayOf(5, 5, 5, 5, 5))
        val r = backUp()
        assertEquals(1, r.keptNewerCloud)
        assertEquals(0, r.uploaded)
        assertTrue(remote.puts.isEmpty())
        assertEquals(900L, remote.items["aaaaaa"]!!.updatedAt)
        assertNull("not recorded as synced", records.record?.items?.get("aaaaaa"))
        // a restore then takes the newer cloud copy
        val rr = restore()
        assertEquals(1, rr.restored)
        assertTrue(local.items["aaaaaa"]!!.bytes.contentEquals(byteArrayOf(5, 5, 5, 5, 5)))
        assertEquals(900L, local.items["aaaaaa"]!!.updatedAt)
    }

    @Test fun aConflictOnPutRereadsTheCloudAndDecidesAgain_newerLocalStillUploads() {
        seed("aaaaaa", base = 900)
        // known to be in sync at revision 1, then another device changes it (older than ours) between the listing and the PUT
        backUp()
        local.save("aaaaaa", 950, byteArrayOf(4, 4, 4, 4))
        var fired = false
        remote.beforePut = { if (!fired) { fired = true; remote.otherDevice("aaaaaa", 300) } }
        remote.puts.clear()
        val r = backUp()
        assertEquals(1, r.uploaded)
        assertEquals(0, r.failed)
        assertEquals(2, remote.puts.size)
        assertEquals(listOf<Long?>(1, 2), remote.puts.map { it.expected })
        assertEquals(950L, remote.items["aaaaaa"]!!.updatedAt)
    }

    @Test fun aConflictOnPutWhereTheCloudCopyBecameNewerKeepsTheCloudCopy() {
        seed("aaaaaa", base = 900)
        backUp()
        local.save("aaaaaa", 950, byteArrayOf(4, 4, 4, 4))
        var fired = false
        remote.beforePut = { if (!fired) { fired = true; remote.otherDevice("aaaaaa", 2_000, byteArrayOf(8, 8, 8, 8)) } }
        val r = backUp()
        assertEquals(1, r.keptNewerCloud)
        assertEquals(0, r.uploaded)
        assertEquals(2_000L, remote.items["aaaaaa"]!!.updatedAt)
        assertTrue(remote.items["aaaaaa"]!!.bytes.contentEquals(byteArrayOf(8, 8, 8, 8)))
    }

    @Test fun aConflictThatKeepsHappeningCountsAsFailedAndTheRunGoesOn() {
        seed("aaaaaa", "bbbbbb", base = 900)
        backUp()
        local.save("aaaaaa", 950, byteArrayOf(4, 4, 4, 4))
        local.save("bbbbbb", 951, byteArrayOf(4, 4, 4, 5))
        var n = 0
        remote.beforePut = { id -> if (id == "bbbbbb" && n++ < 5) remote.otherDevice("bbbbbb", 100L + n) }
        val r = backUp()
        assertEquals(1, r.uploaded)
        assertEquals(1, r.failed)
        assertNull(r.stopped)
    }

    @Test fun aCloudThatLostTheItemIsFilledAgainEvenWhenTheRecordSaysItWasUploaded() {
        seed("aaaaaa")
        backUp()
        remote.items.clear() // e.g. the consent was withdrawn and granted again elsewhere
        remote.puts.clear()
        val r = backUp()
        assertEquals(1, r.uploaded)
        assertEquals(0L, remote.puts.single().expected)
    }

    @Test fun localDeletionsAreNotPropagatedToTheCloud() {
        seed("aaaaaa", "bbbbbb")
        backUp()
        local.items.remove("aaaaaa")
        val r = backUp()
        assertEquals(setOf("aaaaaa", "bbbbbb"), remote.items.keys)
        assertEquals(1, r.upToDate)
        assertEquals(0, remote.clears)
    }

    @Test fun anUnreadableLocalDrawingIsCountedAndSkipped() {
        seed("aaaaaa", "bbbbbb")
        local.unreadable += "aaaaaa"
        val r = backUp()
        assertEquals(1, r.invalid)
        assertEquals(1, r.uploaded)
        assertEquals(setOf("bbbbbb"), remote.items.keys)
    }

    // ---- errors -----------------------------------------------------------------------------------------------------

    @Test fun aDrawingOverTheItemCapIsCountedAndTheRunContinues() {
        seed("aaaaaa", "bbbbbb", "cccccc")
        remote.maxItemBytes = 3
        local.save("bbbbbb", 200, ByteArray(10))
        val r = backUp()
        assertEquals(1, r.tooLarge)
        assertEquals(2, r.uploaded)
        assertNull(r.stopped)
        assertFalse("bbbbbb" in remote.items)
    }

    @Test fun theQuotaStopsTheRunWithAClearReasonAndCountsTheRest() {
        seed("aaaaaa", "bbbbbb", "cccccc")
        remote.maxItems = 1
        val r = backUp()
        assertEquals(1, r.uploaded)
        assertEquals(BackupError.QuotaExceeded("ITEMS_PER_KIND", 1), r.stopped)
        assertEquals("the drawing that hit the cap and the one after it", 2, r.notAttempted)
    }

    @Test fun theTotalBytesQuotaIsReportedWithItsLimit() {
        seed("aaaaaa")
        remote.maxTotalBytes = 2
        val r = backUp()
        assertEquals(BackupError.QuotaExceeded("TOTAL_BYTES", 2), r.stopped)
        assertEquals(0, r.uploaded)
    }

    @Test fun aRateLimitStopsTheRunAndKeepsWhatWasUploaded() {
        seed("aaaaaa", "bbbbbb", "cccccc")
        remote.beforePut = { id -> if (id == "bbbbbb") failing(BackupError.RateLimited(90)) }
        val r = backUp()
        assertEquals(BackupError.RateLimited(90), r.stopped)
        assertEquals(1, r.uploaded)
        assertEquals(2, r.notAttempted)
        assertEquals(1, records.record!!.items.size)
    }

    @Test fun accountLevelErrorsStopTheRunAtOnce() {
        for (e in listOf(BackupError.ConsentRequired, BackupError.FeatureOff, BackupError.SignedOut, BackupError.GuestNotAllowed, BackupError.Offline, BackupError.Timeout, BackupError.UpdateRequired)) {
            val l = FakeLocal().also { it.save("aaaaaa", 1); it.save("bbbbbb", 2) }
            val rm = FakeRemote().also { r -> r.beforePut = { failing(e) } }
            val engine = BackupEngine(l, rm, MemoryRecords(), { "u" })
            val r = report(runBlocking { engine.backUp() })
            assertEquals(e, r.stopped)
            assertEquals(1, rm.puts.size)
            assertEquals(2, r.notAttempted)
        }
    }

    @Test fun theListingFailingStopsBeforeAnythingIsSent() {
        seed("aaaaaa")
        remote.onList = { failing(BackupError.FeatureOff) }
        val r = backUp()
        assertEquals(BackupError.FeatureOff, r.stopped)
        assertTrue(remote.puts.isEmpty())
    }

    @Test fun oneFailedDrawingDoesNotStopTheOthers() {
        seed("aaaaaa", "bbbbbb", "cccccc")
        remote.beforePut = { id -> if (id == "bbbbbb") failing(BackupError.Failed) }
        val r = backUp()
        assertEquals(2, r.uploaded)
        assertEquals(1, r.failed)
        assertNull(r.stopped)
        assertEquals(setOf("aaaaaa", "cccccc"), records.record!!.items.keys)
        // the repeat sends only the one that failed
        remote.beforePut = {}
        remote.puts.clear()
        val again = backUp()
        assertEquals(1, again.uploaded)
        assertEquals(listOf("bbbbbb"), remote.puts.map { it.id })
    }

    @Test fun aServerThatKeepsFailingIsNotHammered() {
        seed("aaaaaa", "bbbbbb", "cccccc", "dddddd", "eeeeee")
        remote.beforePut = { failing(BackupError.Failed) }
        val r = backUp()
        assertEquals(3, remote.puts.size)
        assertEquals(3, r.failed)
        assertEquals(BackupError.Failed, r.stopped)
        assertEquals(2, r.notAttempted)
    }

    @Test fun aRepeatAfterALostAnswerIsHarmless() {
        seed("aaaaaa")
        // the first run: the server stored it but the answer was lost (a timeout), so nothing was recorded
        remote.beforePut = {}
        runBlocking { remote.put("aaaaaa", 100, local.items["aaaaaa"]!!.bytes, 0) }
        val r = backUp()
        assertEquals(1, r.upToDate)
        assertEquals(1, remote.items["aaaaaa"]!!.revision)
    }

    // ---- cancellation and the mutex ----------------------------------------------------------------------------------

    @Test fun cancellingARunKeepsWhatWasDoneAndReleasesTheLock() = runBlocking {
        seed("aaaaaa", "bbbbbb", "cccccc")
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val slow = object : BackupRemote by remote {
            override suspend fun put(id: String, updatedAt: Long, bytes: ByteArray, expectedRevision: Long?): Long {
                if (id == "bbbbbb") {
                    entered.complete(Unit)
                    gate.await()
                }
                return remote.put(id, updatedAt, bytes, expectedRevision)
            }
        }
        val e = BackupEngine(local, slow, records, { user })
        val job = launch { e.backUp() }
        entered.await()
        assertTrue(e.running)
        job.cancelAndJoin()
        assertFalse(e.running)
        assertEquals("only the first (newest) drawing was done", setOf("cccccc"), records.record!!.items.keys)
        // a new run completes the rest
        gate.complete(Unit)
        val r = report(e.backUp())
        assertEquals(2, r.uploaded)
        assertEquals(1, r.upToDate)
    }

    @Test fun aSecondRunWhileOneIsRunningAnswersBusyForBackupRestoreAndClear() = runBlocking {
        seed("aaaaaa")
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val slow = object : BackupRemote by remote {
            override suspend fun put(id: String, updatedAt: Long, bytes: ByteArray, expectedRevision: Long?): Long {
                entered.complete(Unit)
                gate.await()
                return remote.put(id, updatedAt, bytes, expectedRevision)
            }
        }
        val e = BackupEngine(local, slow, records, { user })
        val first = async(start = CoroutineStart.UNDISPATCHED) { e.backUp() }
        entered.await()
        assertEquals(RunOutcome.Busy, e.backUp())
        assertEquals(RunOutcome.Busy, e.restore())
        assertEquals(RunOutcome.Busy, e.clearCloud())
        assertEquals(0, remote.clears)
        gate.complete(Unit)
        assertEquals(1, report(first.await()).uploaded)
        assertFalse(e.running)
    }

    // ---- accounts ----------------------------------------------------------------------------------------------------

    @Test fun nothingRunsWithoutASignedInAccount() {
        seed("aaaaaa")
        user = null
        assertEquals(RunOutcome.NotSignedIn, runBlocking { engine.backUp() })
        assertEquals(RunOutcome.NotSignedIn, runBlocking { engine.restore() })
        assertEquals(RunOutcome.NotSignedIn, runBlocking { engine.clearCloud() })
        assertEquals(0, remote.lists)
        assertTrue(remote.puts.isEmpty())
    }

    @Test fun anotherAccountNeverUsesThePreviousAccountsRecord() {
        seed("aaaaaa", "bbbbbb")
        backUp() // account A: record says both are in the cloud at revision 1
        user = "user-b"
        remote.items.clear() // B's cloud is empty
        remote.puts.clear()
        val r = backUp()
        assertEquals(2, r.uploaded)
        assertTrue("every upload is a create: A's revisions were not used", remote.puts.all { it.expected == 0L })
        assertEquals("user-b", records.record!!.userId)
    }

    @Test fun aRecordOfAnotherAccountIsReplacedEvenWhenTheCloudLooksTheSame() {
        seed("aaaaaa")
        backUp() // A: (rev, 1)
        user = "user-b"
        // B's cloud has the item at revision 1 as well (a different copy); A's record must not make it "up to date" by itself
        remote.items["aaaaaa"] = FakeRemote.Stored(1, 50, byteArrayOf(1, 2, 3, 4))
        remote.puts.clear()
        val r = backUp()
        assertEquals("the device's newer copy is uploaded against the cloud's revision", 1, r.uploaded)
        assertEquals(1L, remote.puts.single().expected)
    }

    @Test fun signingOutDuringARunStopsItWithoutWritingTheRecordOfTheOtherAccount() {
        seed("aaaaaa", "bbbbbb")
        remote.beforePut = { user = null }
        val r = backUp()
        assertEquals(BackupError.SignedOut, r.stopped)
        assertEquals(1, remote.puts.size)
        assertEquals(1, r.notAttempted)
    }

    @Test fun forgettingTheRecordClearsItAndTheNextRunComparesWithTheCloud() {
        seed("aaaaaa")
        backUp()
        engine.forgetRecord()
        assertNull(records.record)
        remote.puts.clear()
        val r = backUp() // the cloud copy is identical (same time and size): nothing is sent
        assertEquals(1, r.upToDate)
        assertTrue(remote.puts.isEmpty())
    }

    @Test fun clearingTheCloudDeletesEverythingThereAndNothingOnThePhone() {
        seed("aaaaaa", "bbbbbb")
        backUp()
        val r = report(runBlocking { engine.clearCloud() })
        assertNull(r.stopped)
        assertTrue(remote.items.isEmpty())
        assertEquals(2, local.items.size)
        assertTrue(local.deletes.isEmpty())
        assertTrue(records.record!!.items.isEmpty())
        // the next backup sends everything again
        assertEquals(2, backUp().uploaded)
    }

    @Test fun clearingCanFailAndSaysWhy() {
        seed("aaaaaa")
        backUp()
        remote.onClear = { failing(BackupError.Offline) }
        val r = report(runBlocking { engine.clearCloud() })
        assertEquals(BackupError.Offline, r.stopped)
        assertEquals(1, records.record!!.items.size)
    }

    @Test fun progressIsReportedPerDrawing() {
        seed("aaaaaa", "bbbbbb")
        val seen = mutableListOf<Pair<Int, Int>>()
        runBlocking { engine.backUp { d, t -> seen += d to t } }
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), seen)
    }

    @Test fun anUnexpectedExceptionBecomesAFailedStopNotACrash() {
        seed("aaaaaa")
        remote.onList = { throw IllegalStateException("boom") }
        val r = backUp()
        assertEquals(BackupError.Failed, r.stopped)
        assertFalse(engine.running)
    }

    // ---- restore ----------------------------------------------------------------------------------------------------

    @Test fun restoreWritesTheDrawingsThatAreNotOnThePhoneAndRecordsTheDeviceRevision() {
        remote.otherDevice("aaaaaa", 100)
        remote.otherDevice("bbbbbb", 200)
        val r = restore()
        assertEquals(2, r.restored)
        assertEquals(setOf("aaaaaa", "bbbbbb"), local.items.keys)
        assertEquals(200L, local.items["bbbbbb"]!!.updatedAt)
        assertTrue(local.items["aaaaaa"]!!.bytes.contentEquals(remote.items["aaaaaa"]!!.bytes))
        assertEquals(RecordEntry(local.items["aaaaaa"]!!.revision, 1), records.record!!.items["aaaaaa"])
        // so the next backup sends nothing for them
        val b = backUp()
        assertEquals(2, b.upToDate)
        assertTrue(remote.puts.isEmpty())
    }

    @Test fun restoreTakesANewerCloudCopyButNeverOverwritesANewerOrEqualLocalOne() {
        local.save("aaaaaa", 100) // older than the cloud: replaced
        local.save("bbbbbb", 900) // newer than the cloud: kept
        local.save("cccccc", 300) // equal: nothing to do
        remote.otherDevice("aaaaaa", 500, byteArrayOf(1, 1, 1, 1))
        remote.otherDevice("bbbbbb", 400, byteArrayOf(2, 2, 2, 2))
        remote.otherDevice("cccccc", 300, local.items["cccccc"]!!.bytes)
        val r = restore()
        assertEquals(1, r.restored)
        assertEquals(1, r.keptNewerLocal)
        assertEquals(1, r.upToDate)
        assertTrue(local.items["aaaaaa"]!!.bytes.contentEquals(byteArrayOf(1, 1, 1, 1)))
        assertEquals(900L, local.items["bbbbbb"]!!.updatedAt)
        assertEquals(listOf("aaaaaa"), remote.downloads)
        assertEquals(listOf("aaaaaa"), local.writes)
    }

    @Test fun aFullGalleryRefusesNewDrawingsWithACountButStillUpdatesExistingOnes() {
        local.cap = 2
        local.save("aaaaaa", 100)
        local.save("bbbbbb", 150)
        remote.otherDevice("aaaaaa", 900, byteArrayOf(1, 1, 1, 1)) // update of an existing one
        remote.otherDevice("cccccc", 800) // new: the gallery is full
        remote.otherDevice("dddddd", 700) // new
        val r = restore()
        assertEquals(1, r.restored)
        assertEquals(2, r.deviceFull)
        assertEquals(setOf("aaaaaa", "bbbbbb"), local.items.keys)
        assertNull(r.stopped)
        // after the first FULL the remaining new ones are not even downloaded
        assertEquals(listOf("aaaaaa", "cccccc"), remote.downloads)
    }

    @Test fun invalidUnsupportedAndFailedWritesAreCountedAndSkipped() {
        remote.otherDevice("aaaaaa", 100)
        remote.otherDevice("bbbbbb", 200)
        remote.otherDevice("cccccc", 300)
        remote.otherDevice("dddddd", 400)
        local.writeResults["aaaaaa"] = RawWriteResult.INVALID
        local.writeResults["bbbbbb"] = RawWriteResult.UNSUPPORTED_VERSION
        local.writeResults["cccccc"] = RawWriteResult.FAILED
        val r = restore()
        assertEquals(1, r.invalid)
        assertEquals(1, r.unsupported)
        assertEquals(1, r.failed)
        assertEquals(1, r.restored)
        assertEquals(setOf("dddddd"), local.items.keys)
        assertEquals(setOf("dddddd"), records.record!!.items.keys)
    }

    @Test fun anItemThatVanishedFromTheCloudIsCountedFailedAndTheRestGoesOn() {
        remote.otherDevice("aaaaaa", 100)
        remote.otherDevice("bbbbbb", 200)
        remote.onDownload = { id -> if (id == "bbbbbb") remote.items.remove(id) }
        val r = restore()
        assertEquals(1, r.failed)
        assertEquals(1, r.restored)
    }

    @Test fun restoreStopsOnAnAccountLevelErrorAndKeepsWhatWasRestored() {
        remote.otherDevice("aaaaaa", 300)
        remote.otherDevice("bbbbbb", 200)
        remote.otherDevice("cccccc", 100)
        remote.onDownload = { id -> if (id == "bbbbbb") failing(BackupError.Offline) }
        val r = restore()
        assertEquals(BackupError.Offline, r.stopped)
        assertEquals(1, r.restored)
        assertEquals(2, r.notAttempted)
        assertEquals(setOf("aaaaaa"), local.items.keys)
        assertEquals(setOf("aaaaaa"), records.record!!.items.keys)
    }

    @Test fun restoreWithNothingInTheCloudDoesNothing() {
        val r = restore()
        assertTrue(r.nothingToDo)
        assertTrue(local.items.isEmpty())
    }

    @Test fun restoreNeverSendsAnythingToTheCloud() {
        seed("aaaaaa")
        remote.otherDevice("bbbbbb", 50)
        restore()
        assertTrue(remote.puts.isEmpty())
        assertEquals(0, remote.clears)
    }

    @Test fun yieldingBetweenRunsLeavesNoLock() = runBlocking {
        seed("aaaaaa")
        engine.backUp()
        yield()
        assertFalse(engine.running)
    }
}
