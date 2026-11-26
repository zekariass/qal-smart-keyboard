package com.qalsmartkeyboard.app.creative.backup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The backup card's state machine: consent flow, the three actions, what each error does to the card. */
class CreativeBackupModelTest {
    private val local = FakeLocal()
    private val remote = FakeRemote().also { it.consentGranted = false }
    private val records = MemoryRecords()
    private val engine = BackupEngine(local, remote, records, { "user-a" })

    /** The consent decision as the server does it: a grant flips the flag, a withdrawal also erases every cloud copy. */
    private inner class ServerConsent : BackupConsentPort {
        val calls = mutableListOf<Boolean>()
        var fail: BackupError? = null

        override suspend fun set(granted: Boolean) {
            calls += granted
            fail?.let { throw BackupException(it) }
            remote.consentGranted = granted
            if (!granted) remote.items.clear()
        }
    }

    private val consent = ServerConsent()
    private val model = CreativeBackupModel(engine, remote, consent, Dispatchers.Unconfined)
    private val state get() = model.state.value

    private fun refresh(account: BackupAccount = BackupAccount.REGISTERED) = runBlocking { model.refresh(account) }

    private fun on() {
        refresh()
        runBlocking { model.turnOn() }
        model.dismissResult()
    }

    // ---- the states -------------------------------------------------------------------------------------------------

    @Test fun signedOutAndGuestStatesMakeNoCall() {
        refresh(BackupAccount.SIGNED_OUT)
        assertEquals(BackupPhase.SIGNED_OUT, state.phase)
        refresh(BackupAccount.GUEST)
        assertEquals(BackupPhase.GUEST, state.phase)
        assertEquals(0, remote.lists)
        assertTrue(consent.calls.isEmpty())
    }

    @Test fun aRegisteredAccountWithoutTheConsentSeesTheOffState() {
        refresh()
        assertEquals(BackupPhase.CONSENT_OFF, state.phase)
        assertEquals(false, state.summary!!.consentGranted)
    }

    @Test fun aRegisteredAccountWithTheConsentSeesUsage() {
        remote.consentGranted = true
        remote.otherDevice("aaaaaa", 1)
        refresh()
        assertEquals(BackupPhase.CONSENT_ON, state.phase)
        assertEquals(1, state.summary!!.itemCount)
    }

    @Test fun aServerWithTheFeatureSwitchedOffMakesTheCardUnavailable() {
        remote.onSummary = { failing(BackupError.FeatureOff) }
        refresh()
        assertEquals(BackupPhase.UNAVAILABLE, state.phase)
        assertNull(state.problem)
    }

    @Test fun aFailedFirstReadIsAProblemWithARetry() {
        remote.onSummary = { failing(BackupError.Offline) }
        refresh()
        assertEquals(BackupPhase.PROBLEM, state.phase)
        assertEquals(BackupError.Offline, state.problem)
        remote.onSummary = {}
        refresh()
        assertEquals(BackupPhase.CONSENT_OFF, state.phase)
        assertNull(state.problem)
    }

    @Test fun aFailedRefreshKeepsWhatAlreadyShows() {
        on()
        remote.onSummary = { failing(BackupError.Timeout) }
        refresh()
        assertEquals(BackupPhase.CONSENT_ON, state.phase)
        assertEquals(BackupError.Timeout, state.problem)
    }

    // ---- consent ----------------------------------------------------------------------------------------------------

    @Test fun turningOnRecordsTheConsentAndUploadsNothing() {
        local.save("aaaaaa", 1)
        refresh()
        runBlocking { model.turnOn() }
        assertEquals(listOf(true), consent.calls)
        assertEquals(BackupPhase.CONSENT_ON, state.phase)
        assertEquals(BackupResult.TurnedOn, state.result)
        assertTrue("no drawing leaves the phone until Back up now", remote.puts.isEmpty())
        assertNull(state.work)
    }

    @Test fun aFailedGrantLeavesTheOffStateAndSaysWhy() {
        refresh()
        consent.fail = BackupError.Offline
        runBlocking { model.turnOn() }
        assertEquals(BackupPhase.CONSENT_OFF, state.phase)
        assertEquals(BackupResult.Failed(BackupError.Offline), state.result)
        assertFalse(remote.consentGranted)
    }

    @Test fun turningOffWithdrawsTheConsentThenForgetsTheRecordAndTheCloudIsEmpty() {
        local.save("aaaaaa", 1)
        on()
        runBlocking { model.backUp() }
        assertEquals(1, remote.items.size)
        assertNotNull(records.record)
        runBlocking { model.turnOff() }
        assertEquals(listOf(true, false), consent.calls)
        assertEquals(BackupPhase.CONSENT_OFF, state.phase)
        assertEquals(BackupResult.TurnedOff, state.result)
        assertTrue("the withdrawal deleted every cloud copy", remote.items.isEmpty())
        assertNull("and the device forgot its record", records.record)
        assertEquals(0, state.summary!!.itemCount)
        assertEquals(1, local.items.size)
        assertTrue(local.deletes.isEmpty())
    }

    @Test fun aFailedWithdrawalChangesNothing() {
        local.save("aaaaaa", 1)
        on()
        runBlocking { model.backUp() }
        consent.fail = BackupError.Offline
        runBlocking { model.turnOff() }
        assertEquals(BackupPhase.CONSENT_ON, state.phase)
        assertEquals(BackupResult.Failed(BackupError.Offline), state.result)
        assertNotNull("the record is kept until the server confirmed the withdrawal", records.record)
        assertEquals(1, remote.items.size)
    }

    @Test fun afterTurningOffAndOnAgainTheDrawingsAreUploadedAgain() {
        local.save("aaaaaa", 1)
        on()
        runBlocking { model.backUp() }
        runBlocking { model.turnOff() }
        runBlocking { model.turnOn() }
        remote.puts.clear()
        runBlocking { model.backUp() }
        assertEquals(1, remote.puts.size)
        assertEquals(0L, remote.puts.single().expected)
    }

    // ---- the actions ------------------------------------------------------------------------------------------------

    @Test fun backUpNowReportsWhatItDidAndRefreshesTheUsage() {
        local.save("aaaaaa", 1)
        local.save("bbbbbb", 2)
        on()
        runBlocking { model.backUp() }
        val result = state.result as BackupResult.BackedUp
        assertEquals(2, result.report.uploaded)
        assertEquals(2, state.summary!!.itemCount)
        assertNull(state.work)
    }

    @Test fun progressIsVisibleWhileARunIsGoingAndASecondTapIsRefused() = runBlocking {
        local.save("aaaaaa", 1)
        local.save("bbbbbb", 2)
        on()
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val slow = object : BackupRemote by remote {
            override suspend fun put(id: String, updatedAt: Long, bytes: ByteArray, expectedRevision: Long?): Long {
                if (id == "aaaaaa") {
                    entered.complete(Unit)
                    gate.await()
                }
                return remote.put(id, updatedAt, bytes, expectedRevision)
            }
        }
        val m = CreativeBackupModel(BackupEngine(local, slow, records, { "user-a" }), remote, consent, Dispatchers.Unconfined)
        m.refresh(BackupAccount.REGISTERED)
        val run = async(start = CoroutineStart.UNDISPATCHED) { m.backUp() }
        entered.await()
        assertEquals(BackupWork.BackingUp(1, 2), m.state.value.work)
        m.backUp()
        assertEquals(BackupResult.Busy, m.state.value.result)
        m.turnOff()
        assertEquals("nothing changes while a run is going", listOf(true), consent.calls)
        gate.complete(Unit)
        run.await()
        assertNull(m.state.value.work)
        assertTrue(m.state.value.result is BackupResult.BackedUp)
    }

    @Test fun restoreReportsAndLeavesTheDevicesNewerDrawings() {
        local.save("aaaaaa", 900)
        remote.consentGranted = true
        remote.otherDevice("aaaaaa", 100)
        remote.otherDevice("bbbbbb", 200)
        refresh()
        runBlocking { model.restore() }
        val r = (state.result as BackupResult.Restored).report
        assertEquals(1, r.restored)
        assertEquals(1, r.keptNewerLocal)
        assertEquals(setOf("aaaaaa", "bbbbbb"), local.items.keys)
    }

    @Test fun deletingTheCloudCopyKeepsTheConsentAndThePhone() {
        local.save("aaaaaa", 1)
        on()
        runBlocking { model.backUp() }
        runBlocking { model.deleteCloud() }
        assertEquals(BackupResult.CloudDeleted, state.result)
        assertEquals(BackupPhase.CONSENT_ON, state.phase)
        assertTrue(remote.items.isEmpty())
        assertEquals(1, local.items.size)
        assertEquals(0, state.summary!!.itemCount)
        assertEquals(listOf(true), consent.calls)
    }

    @Test fun aFailedDeleteIsReportedAsAFailure() {
        on()
        remote.onClear = { failing(BackupError.Timeout) }
        runBlocking { model.deleteCloud() }
        assertEquals(BackupResult.Failed(BackupError.Timeout), state.result)
    }

    @Test fun actionsDoNothingWhileTheConsentIsOffOrTheAccountIsNotRegistered() {
        local.save("aaaaaa", 1)
        refresh()
        runBlocking {
            model.backUp()
            model.restore()
        }
        assertEquals(0, remote.lists)
        assertTrue(remote.puts.isEmpty())
        refresh(BackupAccount.SIGNED_OUT)
        runBlocking { model.backUp() }
        assertEquals(0, remote.lists)
    }

    // ---- what the errors do to the card -------------------------------------------------------------------------------

    @Test fun aServerThatSwitchedTheFeatureOffDuringARunMakesTheCardUnavailable() {
        local.save("aaaaaa", 1)
        on()
        remote.beforePut = { failing(BackupError.FeatureOff) }
        runBlocking { model.backUp() }
        assertEquals(BackupPhase.UNAVAILABLE, state.phase)
        assertEquals(BackupError.FeatureOff, (state.result as BackupResult.BackedUp).report.stopped)
    }

    @Test fun aConsentRefusalDuringARunReturnsToTheOffState() {
        local.save("aaaaaa", 1)
        on()
        remote.beforePut = { failing(BackupError.ConsentRequired) }
        runBlocking { model.backUp() }
        assertEquals(BackupPhase.CONSENT_OFF, state.phase)
    }

    @Test fun quotaAndRateLimitStayInTheOnStateWithTheirReason() {
        local.save("aaaaaa", 1)
        on()
        remote.maxItems = 0
        runBlocking { model.backUp() }
        assertEquals(BackupPhase.CONSENT_ON, state.phase)
        assertEquals(BackupError.QuotaExceeded("ITEMS_PER_KIND", 0), (state.result as BackupResult.BackedUp).report.stopped)
        remote.maxItems = 100
        remote.beforePut = { failing(BackupError.RateLimited(120)) }
        runBlocking { model.backUp() }
        assertEquals(BackupError.RateLimited(120), (state.result as BackupResult.BackedUp).report.stopped)
        assertEquals(BackupPhase.CONSENT_ON, state.phase)
    }

    @Test fun dismissingTheResultClearsIt() {
        on()
        runBlocking { model.backUp() }
        assertNotNull(state.result)
        model.dismissResult()
        assertNull(state.result)
    }
}
