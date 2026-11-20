package com.qalsmartkeyboard.api

import okhttp3.Interceptor
import okhttp3.Response
import java.util.Locale

/**
 * The two country hints of ADR 0045, as the phone reports them: the country of the mobile network it is on
 * (`TelephonyManager.networkCountryIso`) and of the SIM's operator (`simCountryIso`). Either is null when unknown
 * (Wi-Fi-only tablet, no SIM, airplane mode, CDMA). Raw values: [CountryHintsInterceptor] validates them.
 */
data class CountryHints(val network: String?, val sim: String?) {
    companion object {
        val NONE = CountryHints(null, null)
    }
}

/** Reads [CountryHints]. Called on an OkHttp thread for billing requests only; must be quick and never throw. */
fun interface CountryHintSource {
    fun hints(): CountryHints
}

/**
 * Adds `X-Qal-Network-Country` and `X-Qal-Sim-Country` (ADR 0045) to billing requests (`/api/v1/billing/…`) only:
 * the server resolves the billing country for options and new orders and nowhere else, so no other call carries
 * anything about where the phone is. Each hint is sent only when it is a valid ISO 3166-1 alpha-2 code (trimmed,
 * upper-cased); anything else is left out, never guessed. The hints are supporting evidence only: the server never
 * unlocks a country-specific payment method from them alone. Values a caller set are replaced (or removed).
 */
class CountryHintsInterceptor(private val source: CountryHintSource) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.encodedPath.contains(BILLING_PATH)) return chain.proceed(request)
        val hints = try {
            source.hints()
        } catch (e: RuntimeException) {
            CountryHints.NONE
        }
        val builder = request.newBuilder().removeHeader(NETWORK_HEADER).removeHeader(SIM_HEADER)
        normalise(hints.network)?.let { builder.header(NETWORK_HEADER, it) }
        normalise(hints.sim)?.let { builder.header(SIM_HEADER, it) }
        return chain.proceed(builder.build())
    }

    companion object {
        const val NETWORK_HEADER = "X-Qal-Network-Country"
        const val SIM_HEADER = "X-Qal-Sim-Country"
        const val BILLING_PATH = "/api/v1/billing/"

        private val ISO_COUNTRIES: Set<String> = Locale.getISOCountries().toSet()

        /** The upper-cased ISO 3166-1 alpha-2 code of [raw], or null when it is not one. */
        fun normalise(raw: String?): String? {
            val v = raw?.trim()?.uppercase(Locale.ROOT) ?: return null
            return v.takeIf { it.length == 2 && it in ISO_COUNTRIES }
        }
    }
}
