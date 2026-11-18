package com.qalsmartkeyboard.app.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR 0024: crash reporting starts only with a DSN in the build AND the privacy switch on. */
class CrashReportingControllerTest {
    private class FakeReporter : CrashReporter {
        val calls = mutableListOf<String>()
        override fun start(dsn: String) { calls += "start $dsn" }
        override fun stop() { calls += "stop" }
        override fun discardQueued() { calls += "discard" }
    }

    private val fakeDsn = "https://public@sentry.invalid/1"

    @Test fun noDsnNeverStartsWhateverTheSwitch() {
        for (dsn in listOf("", "   ")) {
            val r = FakeReporter()
            val c = CrashReportingController(dsn, r)
            c.apply(true)
            c.apply(false)
            c.apply(true)
            assertFalse(c.running)
            assertEquals(emptyList<String>(), r.calls)
        }
    }

    @Test fun switchOffNeverStartsAndDiscardsWhatIsQueued() {
        val r = FakeReporter()
        val c = CrashReportingController(fakeDsn, r)
        c.apply(false)
        assertFalse(c.running)
        assertEquals(listOf("discard"), r.calls)
    }

    @Test fun switchOnStartsOnceAndOffStopsAtRuntime() {
        val r = FakeReporter()
        val c = CrashReportingController(fakeDsn, r)
        c.apply(true)
        c.apply(true)
        assertTrue(c.running)
        c.apply(false)
        assertFalse(c.running)
        c.apply(true)
        assertTrue(c.running)
        assertEquals(listOf("start $fakeDsn", "stop", "discard", "start $fakeDsn"), r.calls)
    }

    @Test fun gate() {
        assertTrue(CrashReportingController.shouldRun(fakeDsn, true))
        assertFalse(CrashReportingController.shouldRun(fakeDsn, false))
        assertFalse(CrashReportingController.shouldRun("", true))
    }
}
