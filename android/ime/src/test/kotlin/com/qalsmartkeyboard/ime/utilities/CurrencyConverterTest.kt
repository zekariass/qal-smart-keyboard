package com.qalsmartkeyboard.ime.utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T8.3: cross rates through the snapshot base, minor-unit rounding, staleness and the offline cache. */
class CurrencyConverterTest {
    private val day = FxSnapshotCache.DAY_MILLIS
    private val asOf = 1_700_000_000_000L // 2023-11-14 UTC

    /** Fixture: base USD; 1 USD = 130 ETB = 0.92 EUR = 150 JPY = 0.31 KWD. */
    private val usd = FxSnapshot("USD", asOf, mapOf("ETB" to 130.0, "EUR" to 0.92, "JPY" to 150.0, "KWD" to 0.31))

    @Test fun baseToOtherAndBack() {
        assertEquals(1300.0, CurrencyConverter.convert(10.0, "USD", "ETB", usd)!!, 0.0)
        assertEquals(10.0, CurrencyConverter.convert(1300.0, "ETB", "USD", usd)!!, 1e-9)
    }

    @Test fun crossRateGoesThroughTheBase() {
        // 100 EUR = 100 / 0.92 USD = 108.6956... USD = 14130.43 ETB
        assertEquals(14130.43, CurrencyConverter.convert(100.0, "EUR", "ETB", usd)!!, 1e-9)
        assertEquals(7.08, CurrencyConverter.convert(1000.0, "ETB", "EUR", usd)!!, 1e-9)
    }

    @Test fun roundsToTheTargetMinorUnits() {
        assertEquals(1500.0, CurrencyConverter.convert(10.0, "USD", "JPY", usd)!!, 0.0)
        assertEquals("1500", CurrencyConverter.format(1500.0, "JPY"))
        assertEquals("3.100", CurrencyConverter.format(3.1, "KWD"))
        assertEquals("1300.00", CurrencyConverter.format(1300.0, "ETB"))
        assertEquals(0.31, CurrencyConverter.convert(1.0, "USD", "KWD", usd)!!, 1e-12)
        assertEquals(0, CurrencyConverter.minorUnits("JPY"))
        assertEquals(3, CurrencyConverter.minorUnits("KWD"))
        assertEquals(2, CurrencyConverter.minorUnits("ETB"))
    }

    @Test fun unknownCurrencyOrBadRateGivesNull() {
        assertNull(CurrencyConverter.convert(1.0, "USD", "XXX", usd))
        assertNull(CurrencyConverter.convert(1.0, "ABC", "ETB", usd))
        assertNull(CurrencyConverter.convert(1.0, "USD", "ETB", usd.copy(rates = mapOf("ETB" to 0.0))))
    }

    @Test fun currenciesListPreferredFirst() {
        assertEquals(listOf("ETB", "USD", "EUR", "JPY", "KWD"), usd.currencies)
    }

    @Test fun cacheMarksOldSnapshotsStaleAndKeepsThem() {
        var now = asOf + day / 2
        val cache = FxSnapshotCache(clock = { now })
        assertNull(cache.current())
        assertEquals(RatesNote.Unavailable, cache.note())
        cache.put(usd)
        assertFalse(cache.current()!!.stale)
        assertEquals(RatesNote.AsOf("2023-11-14", stale = false), cache.note())
        now = asOf + 3 * day
        assertTrue(cache.current()!!.stale)
        assertEquals(RatesNote.AsOf("2023-11-14", stale = true), cache.note())
        // The source's own stale flag survives.
        cache.put(usd.copy(stale = true, asOfMillis = now))
        assertTrue(cache.current()!!.stale)
    }

    @Test fun disabledSourceNeverChangesTheCache() {
        val cache = FxSnapshotCache(clock = { asOf })
        assertFalse(cache.refresh())
        assertNull(cache.current())
        cache.put(usd)
        assertFalse(cache.refresh())
        assertEquals(usd, cache.current())
    }

    @Test fun refreshKeepsNewerAndIgnoresOlderOrFailingSources() {
        val newer = usd.copy(asOfMillis = asOf + day, rates = mapOf("ETB" to 131.0))
        val cache = FxSnapshotCache(source = { newer }, clock = { asOf + day })
        cache.put(usd)
        assertTrue(cache.refresh())
        assertEquals(131.0, cache.current()!!.rates["ETB"]!!, 0.0)
        val older = FxSnapshotCache(source = { usd.copy(asOfMillis = asOf - day) }, clock = { asOf })
        older.put(usd)
        assertFalse(older.refresh())
        assertEquals(asOf, older.current()!!.asOfMillis)
        val throwing = FxSnapshotCache(source = { error("offline") }, clock = { asOf })
        throwing.put(usd)
        assertFalse(throwing.refresh())
        assertEquals(usd, throwing.current())
    }
}
