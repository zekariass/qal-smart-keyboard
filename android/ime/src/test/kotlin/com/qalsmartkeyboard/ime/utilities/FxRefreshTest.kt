package com.qalsmartkeyboard.ime.utilities

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** T8.3: the persistent rates store and the hourly refresh throttle. */
@RunWith(RobolectricTestRunner::class)
class FxRefreshTest {
    private val hour = FxSnapshotCache.HOUR_MILLIS
    private val t0 = 1_700_000_000_000L
    private val snap = FxSnapshot("USD", t0 - hour, mapOf("ETB" to 130.5, "EUR" to 0.9), stale = true, fetchedAtMillis = t0)
    private val dir: File = Files.createTempDirectory("qal-fx").toFile().also { it.deleteOnExit() }

    private fun store(file: File = File(dir, "fx.json")) = FileFxSnapshotStore(file, io = { it.run() })

    @Test fun theStoreRoundTripsThroughItsFile() {
        val file = File(dir, "a.json")
        store(file).save(snap)
        assertTrue(file.isFile)
        val again = store(file)
        assertNull("nothing is read until warm()", again.load())
        again.warm()
        assertEquals(snap, again.load())
    }

    @Test fun aNewerSaveIsNotReplacedByTheOlderFile() {
        val file = File(dir, "b.json")
        store(file).save(snap)
        val s = store(file)
        val newer = snap.copy(asOfMillis = t0, stale = false)
        s.save(newer)
        s.warm()
        assertEquals(newer, s.load())
    }

    @Test fun aMissingOrCorruptFileIsIgnored() {
        val s = store(File(dir, "missing.json"))
        s.warm()
        assertNull(s.load())
        val bad = File(dir, "bad.json").also { it.writeText("{ nope") }
        store(bad).also { it.warm() }.let { assertNull(it.load()) }
        val empty = File(dir, "empty.json").also { it.writeText("""{"base":"USD","asOf":1,"rates":{"ETB":-2}}""") }
        store(empty).also { it.warm() }.let { assertNull(it.load()) }
    }

    @Test fun refreshRunsAtMostOncePerHourEvenWhenItFails() {
        var now = t0
        var fetches = 0
        var answer: FxSnapshot? = null
        val cache = FxSnapshotCache(source = { fetches++; answer }, clock = { now })
        assertTrue(cache.refreshDue())
        assertFalse(cache.refreshIfDue()) // failed: nothing cached
        assertEquals(1, fetches)
        now += hour / 2
        assertFalse(cache.refreshDue())
        cache.refreshIfDue()
        assertEquals("a failed attempt is not retried within the hour", 1, fetches)
        now += hour / 2
        answer = FxSnapshot("USD", now - 5_000, mapOf("ETB" to 130.0))
        assertTrue(cache.refreshIfDue())
        assertEquals(2, fetches)
        assertEquals(now, cache.current()!!.fetchedAtMillis)
        now += hour - 1
        cache.refreshIfDue()
        assertEquals(2, fetches)
        now += 2
        cache.refreshIfDue()
        assertEquals(3, fetches)
    }

    @Test fun aSnapshotFetchedRecentlyBlocksARefreshAfterRestart() {
        var fetches = 0
        val s = store(File(dir, "c.json")).also { it.save(snap) } // fetched at t0
        val restarted = FileFxSnapshotStore(File(dir, "c.json"), io = { it.run() }).also { it.warm() }
        val cache = FxSnapshotCache(store = restarted, source = { fetches++; null }, clock = { t0 + hour / 2 })
        assertFalse(cache.refreshIfDue())
        assertEquals(0, fetches)
        val later = FxSnapshotCache(store = restarted, source = { fetches++; null }, clock = { t0 + hour })
        later.refreshIfDue()
        assertEquals(1, fetches)
        assertEquals(s.load(), restarted.load())
    }

    @Test fun theHolderGivesTheDisabledSourceWithoutAFactory() {
        FxRateSourceHolder.resetForTest()
        assertNull(FxRateSourceHolder.create().fetch())
        FxRateSourceHolder.resetForTest()
        FxRateSourceHolder.factory = { FxRateSource { snap } }
        assertEquals(snap, FxRateSourceHolder.create().fetch())
        FxRateSourceHolder.resetForTest()
    }
}
