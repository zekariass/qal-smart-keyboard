package com.qalsmartkeyboard.ime.utilities

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * A snapshot of exchange rates: [rates] maps an ISO 4217 code to the units of that currency per one unit of [base]
 * (so `rates["ETB"] = 130.0` with base USD means 1 USD = 130 ETB). The base itself is always 1. [asOfMillis] is when the
 * rates were published (UTC epoch milliseconds); [stale] is the source's own flag (for example the server says the
 * upstream feed is behind). The snapshot is data, not a request: reading it never touches the network.
 */
data class FxSnapshot(
    val base: String,
    val asOfMillis: Long,
    val rates: Map<String, Double>,
    val stale: Boolean = false,
    /** When this device last got the snapshot from its source (epoch ms; 0 when unknown); it drives the refresh throttle. */
    val fetchedAtMillis: Long = 0L,
) {
    /** Every currency the snapshot can convert, the base included, preferred codes first then alphabetical. */
    val currencies: List<String>
        get() {
            val all = (rates.keys + base).toSortedSet()
            val first = PREFERRED.filter { it in all }
            return first + all.filter { it !in first }
        }

    /** Units of [code] per one [base], or null when the snapshot does not list it. */
    fun rateOf(code: String): Double? = if (code == base) 1.0 else rates[code]?.takeIf { it > 0.0 && it.isFinite() }

    companion object {
        /** Offered first in the converter: the birr, then the currencies Ethiopians most often convert. */
        val PREFERRED = listOf("ETB", "USD", "EUR", "GBP", "SAR", "AED", "KES", "CNY")
    }
}

/**
 * Where rates come from. Implementations may do I/O, so they are called only off the key path, from the converter's
 * refresh; the key path reads [FxSnapshotCache.current] alone. The real client (the backend's rates endpoint) lives in
 * `:ai-client` and is handed over through [FxRateSourceHolder]; without one [DisabledFxRateSource] returns nothing and the
 * converter says "rates unavailable offline".
 */
fun interface FxRateSource {
    /** The newest snapshot, or null when none can be had (offline, disabled, upstream error). Never throws. */
    fun fetch(): FxSnapshot?
}

/** No network source yet: always null. */
object DisabledFxRateSource : FxRateSource {
    override fun fetch(): FxSnapshot? = null
}

/** Persistence hook for the cache (a file or DataStore later); the default keeps nothing across processes. */
interface FxSnapshotStore {
    fun load(): FxSnapshot?
    fun save(snapshot: FxSnapshot)
}

/**
 * Hands the keyboard the rates source built by the app (`:ime` has no HTTP code). The app registers a factory in
 * `Application.onCreate`; it is called on a background thread. Without one (tests, a build with no base URL) the source
 * stays [DisabledFxRateSource].
 */
object FxRateSourceHolder {
    @Volatile var factory: (() -> FxRateSource?)? = null

    @Volatile private var instance: FxRateSource? = null

    /** The source, built once; [DisabledFxRateSource] when the app registered none. Call off the main thread. */
    fun create(): FxRateSource = instance ?: synchronized(this) {
        instance ?: (factory?.invoke() ?: DisabledFxRateSource).also { instance = it }
    }

    internal fun resetForTest() {
        factory = null
        instance = null
    }
}

/** In-process store: the cache survives while the keyboard service lives. */
class MemoryFxSnapshotStore : FxSnapshotStore {
    @Volatile private var held: FxSnapshot? = null
    override fun load(): FxSnapshot? = held
    override fun save(snapshot: FxSnapshot) {
        held = snapshot
    }
}

/** How fresh the cached rates are, for the note under the converter. */
sealed interface RatesNote {
    /** No snapshot: the panel says rates are unavailable offline. */
    data object Unavailable : RatesNote

    /** Rates published on [asOf] (a "yyyy-MM-dd" UTC date); [stale] when older than the cache's age limit or flagged. */
    data class AsOf(val asOf: String, val stale: Boolean) : RatesNote
}

/**
 * The offline rates cache: [current] is what the converter reads on every key, with no I/O. [refresh] asks the
 * [source] (off the key path) and keeps the result when it is newer than what is held; a failed refresh keeps the old
 * snapshot, which then ages into "stale" rather than disappearing.
 */
class FxSnapshotCache(
    private val store: FxSnapshotStore = MemoryFxSnapshotStore(),
    private val source: FxRateSource = DisabledFxRateSource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAgeMillis: Long = DAY_MILLIS,
    /** [refreshIfDue] asks the source at most this often (the endpoint's `max-age` is one hour). */
    private val minRefreshMillis: Long = HOUR_MILLIS,
) {
    @Volatile private var lastAttempt = 0L

    /** The cached snapshot, or null. */
    fun current(): FxSnapshot? = store.load()?.let { it.copy(stale = it.stale || isOld(it)) }

    fun put(snapshot: FxSnapshot) = store.save(snapshot)

    /** Fetches from the source and caches a newer snapshot; returns whether the cache changed. */
    fun refresh(): Boolean {
        val fresh = runCatching { source.fetch() }.getOrNull() ?: return false
        val held = store.load()
        if (held != null && held.asOfMillis > fresh.asOfMillis) return false
        store.save(fresh.copy(fetchedAtMillis = clock()))
        return true
    }

    /**
     * Whether a background refresh may run now: the last attempt (kept even when it failed, so a dead network is not
     * retried on every panel open) and the held snapshot's fetch time are both over [minRefreshMillis] old. Memory only.
     */
    fun refreshDue(): Boolean {
        val last = maxOf(lastAttempt, store.load()?.fetchedAtMillis ?: 0L)
        return clock() - last >= minRefreshMillis || clock() < last
    }

    /** [refresh] when [refreshDue] (blocking: call it off the main thread). Returns whether the cache changed. */
    @Synchronized
    fun refreshIfDue(): Boolean {
        if (!refreshDue()) return false
        lastAttempt = clock()
        return refresh()
    }

    fun note(): RatesNote {
        val s = current() ?: return RatesNote.Unavailable
        return RatesNote.AsOf(isoDate(s.asOfMillis), s.stale)
    }

    private fun isOld(s: FxSnapshot) = clock() - s.asOfMillis > maxAgeMillis

    companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
        const val HOUR_MILLIS = 60L * 60 * 1000

        /** "yyyy-MM-dd" of a UTC instant (API 24 safe, no java.time). */
        fun isoDate(millis: Long): String {
            val c = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis }
            return "%04d-%02d-%02d".format(
                java.util.Locale.ROOT,
                c.get(java.util.Calendar.YEAR),
                c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH),
            )
        }
    }
}

object CurrencyConverter {
    /** Minor-unit digits of currencies that are not 2 (ISO 4217). */
    private val MINOR_UNITS = mapOf(
        "JPY" to 0, "KRW" to 0, "VND" to 0, "UGX" to 0, "RWF" to 0, "XOF" to 0, "XAF" to 0, "DJF" to 0, "CLP" to 0,
        "BHD" to 3, "KWD" to 3, "OMR" to 3, "JOD" to 3, "TND" to 3, "LYD" to 3, "IQD" to 3,
    )

    /** Digits after the point in [code]'s smallest unit (2 for most currencies, 0 for JPY, 3 for KWD). */
    fun minorUnits(code: String): Int = MINOR_UNITS[code] ?: 2

    /**
     * [amount] of [from] in [to], through the snapshot's base (cross rate = rate(to) / rate(from)), rounded half-even
     * to [to]'s minor units. Null when the snapshot does not list one of the currencies.
     */
    fun convert(amount: Double, from: String, to: String, snapshot: FxSnapshot): Double? {
        val rf = snapshot.rateOf(from) ?: return null
        val rt = snapshot.rateOf(to) ?: return null
        val raw = amount / rf * rt
        if (!raw.isFinite()) return null
        return BigDecimal(raw).setScale(minorUnits(to), RoundingMode.HALF_EVEN).toDouble()
    }

    /** [convert]'s result as text with exactly the currency's minor-unit digits and no grouping. */
    fun format(value: Double, code: String): String =
        BigDecimal(value).setScale(minorUnits(code), RoundingMode.HALF_EVEN).toPlainString()
}
