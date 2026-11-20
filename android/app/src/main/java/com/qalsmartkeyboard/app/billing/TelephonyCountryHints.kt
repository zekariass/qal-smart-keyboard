package com.qalsmartkeyboard.app.billing

import android.content.Context
import android.os.SystemClock
import android.telephony.TelephonyManager
import com.qalsmartkeyboard.api.CountryHintSource
import com.qalsmartkeyboard.api.CountryHints

/**
 * The phone's country hints for billing calls (ADR 0045, ADR 0047), read from [TelephonyManager] without any permission:
 * `networkCountryIso` (the mobile network the phone is registered on) and `simCountryIso` (the SIM operator's
 * country). Nothing else about the device is read, and the user is never asked for a country.
 *
 * Quirks: a Wi-Fi-only tablet or a phone without SIM or network gives empty strings (no hint); on CDMA the network
 * country is documented as unreliable and is left out; a SIM that is not ready gives no SIM hint; any exception from
 * the telephony service gives no hints. The values are validated (ISO 3166-1 alpha-2) by `CountryHintsInterceptor`.
 * Read on an OkHttp thread and kept for [CACHE_MS] so a burst of billing calls does not repeat the binder calls.
 */
class TelephonyCountryHints(
    private val context: Context,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : CountryHintSource {
    @Volatile private var cached: CountryHints? = null
    @Volatile private var readAt = 0L

    override fun hints(): CountryHints {
        val now = clock()
        cached?.let { if (now - readAt < CACHE_MS) return it }
        return read().also {
            cached = it
            readAt = now
        }
    }

    private fun read(): CountryHints {
        val tm = try {
            context.getSystemService(TelephonyManager::class.java)
        } catch (e: RuntimeException) {
            null
        } ?: return CountryHints.NONE
        return try {
            val network = if (tm.phoneType == TelephonyManager.PHONE_TYPE_CDMA) null else tm.networkCountryIso
            val sim = if (tm.simState == TelephonyManager.SIM_STATE_READY) tm.simCountryIso else null
            CountryHints(network?.takeIf { it.isNotBlank() }, sim?.takeIf { it.isNotBlank() })
        } catch (e: RuntimeException) {
            CountryHints.NONE
        }
    }

    companion object {
        const val CACHE_MS = 60_000L
    }
}
