package com.qalsmartkeyboard.aiclient

import com.qalsmartkeyboard.ime.utilities.FxRateSource
import com.qalsmartkeyboard.ime.utilities.FxSnapshot
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The currency converter's rates source (T8.3): `GET /api/v1/tools/fx-rates`, the daily snapshot against USD. Read-only,
 * unmetered and identical for every caller, so the request carries no session: [http] is the transport's bearer-free
 * client (the endpoint takes an optional token, and a presented one must be valid, so none is the safe choice). Nothing
 * the user typed is sent.
 *
 * [fetch] is blocking (the keyboard calls it off the main thread, at most hourly) with a short call timeout of
 * [timeoutMs]. Any failure (no snapshot on the server `503`, another status, a network error, a timeout, a malformed
 * body) answers null, so the cache keeps its last snapshot. It never throws.
 */
class ServerFxRateSource(
    http: OkHttpClient,
    private val baseUrl: String,
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : FxRateSource {
    private val client: OkHttpClient = http.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()

    override fun fetch(): FxSnapshot? {
        val request = try {
            Request.Builder().url(baseUrl.trimEnd('/') + "/api/v1/tools/fx-rates").header("Accept", "application/json").get().build()
        } catch (e: IllegalArgumentException) {
            return null
        }
        return try {
            client.newCall(request).execute().use { r -> if (r.isSuccessful) parse(r.body?.string().orEmpty()) else null }
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    companion object {
        /** Short: a slow server must not hold the background thread (the converter keeps its last snapshot). */
        const val DEFAULT_TIMEOUT_MS = 6_000L

        /** The contract's `FxRates` body as a snapshot; null when it lacks a usable base, date or rate. */
        internal fun parse(body: String): FxSnapshot? {
            val o = try {
                Json.parseToJsonElement(body) as? JsonObject
            } catch (e: Exception) {
                null
            } ?: return null
            val base = AiWire.str(o, "base")?.takeIf { it.length == 3 } ?: return null
            val asOf = AiWire.str(o, "asOf")?.let(AiWire::parseInstantMs) ?: return null
            val raw = o["rates"] as? JsonObject ?: return null
            val rates = HashMap<String, Double>()
            for ((code, v) in raw) {
                val rate = (v as? JsonPrimitive)?.doubleOrNull ?: continue
                if (code.length == 3 && rate.isFinite() && rate > 0.0) rates[code] = rate
            }
            if (rates.isEmpty()) return null
            val stale = (o["stale"] as? JsonPrimitive)?.booleanOrNull ?: false
            return FxSnapshot(base, asOf, rates, stale)
        }
    }
}
