package com.qalsmartkeyboard.aiclient

import com.qalsmartkeyboard.api.ApiError
import com.qalsmartkeyboard.api.ApiErrors
import com.qalsmartkeyboard.ime.ai.AiPeriod
import com.qalsmartkeyboard.ime.ai.AiQuotaHint
import com.qalsmartkeyboard.ime.ai.AiTaskKind
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Who is signed in, as far as the token checks need it. Read from the session; never logged. */
interface SessionInfo {
    fun signedIn(): Boolean

    /** The user id (`sub` of the access token), or null when unknown. */
    fun subject(): String?

    /** This device's id (`did` of the access token), or null when the session is not bound to one. */
    fun deviceId(): String?

    /**
     * [listener] runs (on any thread, possibly while the session is being changed) after every sign-in, sign-out,
     * account switch or token rotation. It must return at once. Close the handle to remove it.
     */
    fun addListener(listener: () -> Unit): AutoCloseable = AutoCloseable { }
}

/**
 * Plan hints and the offline-gate token (A2.5, ADR 0013), refreshed in the background and read
 * without blocking.
 *
 * - **Token:** `GET /me/entitlements/token` when there is none, it is within [REFRESH_BEFORE_EXPIRY_MS]
 *   of `exp`, or it no longer verifies. It is verified on device against the build's pinned keys
 *   ([EntitlementTokenVerifier]) before it is stored. A 401 or 403 drops the cached token (D4.6);
 *   a 429 or any network error keeps it. [gates] opens only the offline gates (on-device premium
 *   features); server AI features are decided by the server on every call and are not read from here.
 * - **Plan hints:** `GET /me/entitlements` for "3 of 10 translations left today", at most every
 *   [QUOTA_TTL_MS] and after each AI answer ([recordUsage]).
 *
 * [refresh] blocks: call it from a background thread. Everything else is in-memory and instant.
 */
class EntitlementManager(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val session: SessionInfo,
    private val verifier: EntitlementTokenVerifier,
    private val store: EntitlementTokenStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Called (on any thread) when the plan hints or the offline gates changed. */
    @Volatile var onChanged: () -> Unit = {}

    @Volatile private var verified: VerifiedToken? = null
    @Volatile private var newestSeen = 0L
    @Volatile private var lastTokenAttempt = 0L
    @Volatile private var lastQuotaFetch = 0L
    @Volatile private var plan: String? = null
    private val quotaByKind = ConcurrentHashMap<AiTaskKind, AiQuotaHint>()

    @Volatile private var entitledKinds: Set<AiTaskKind>? = null
    @Volatile private var entitledCodes: Set<String>? = null

    /** Every `accessEnabled` feature code of the last `/me/entitlements` (AI and platform), or null before the first answer. */
    @Volatile private var entitledCodes: Set<String>? = null

    /** The prepaid credit balance (`creditBalance` of `/me/entitlements`, whole credits rounded down), or null while unknown. */
    @Volatile var creditBalance: Long? = null
        private set

    val quota: Map<AiTaskKind, AiQuotaHint> get() = quotaByKind.toMap()
    val planLabel: String? get() = plan

    /**
     * The AI features the plan includes (`accessEnabled` in the last `/me/entitlements`), or null before the first
     * answer. Only a hint for the panels' "Premium" marks (ADR 0031); the server decides on every call.
     */
    val entitled: Set<AiTaskKind>? get() = entitledKinds

    /**
     * The platform (non-AI) feature codes the plan includes (T8.7 `STICKER_MAKER`): the last `/me/entitlements` answer's
     * `accessEnabled` features that are not AI tasks, plus every feature the cached verified token grants right now (its
     * offline gates). Null while both are unknown. Answers from memory.
     */
    fun platformFeatures(online: Boolean): Set<String>? {
        val server = entitledCodes?.filterTo(HashSet()) { code -> AiTaskKind.entries.none { AiWire.featureCode(it) == code } }
        val token = gates(online).features
        if (server == null && token.isEmpty()) return null
        return (server ?: emptySet()) + token
    }

    /**
     * Every feature code the plan includes (`accessEnabled` in the last `/me/entitlements`), or null before the first
     * answer (and again after sign-out). In memory only. The on-device creative tools (card maker, ...) read their
     * boolean gate from here when the signed token does not carry it (see [ServerFeatureAccess]).
     */
    val entitledFeatureCodes: Set<String>? get() = entitledCodes

    /** The offline gates right now (in-memory, no I/O). [online] decides whether a token in its grace period still counts. */
    fun gates(online: Boolean): OfflineGates = OfflineGates.evaluate(verified, maxOf(clock(), newestSeen), online)

    /** Loads and re-verifies the cached token (disk): call once on a background thread. */
    fun loadCached() {
        newestSeen = store.newestSeenMs()
        val jws = store.token()
        verified = if (jws == null) null else (verifier.verify(jws, session.subject(), session.deviceId(), maxOf(clock(), newestSeen)) as? TokenVerdict.Valid)?.token
        if (jws != null && verified == null) store.dropToken()
        onChanged()
    }

    /** The user signed out or switched account: nothing of the old account stays. */
    fun clear() {
        verified = null
        quotaByKind.clear()
        entitledKinds = null
        entitledCodes = null
        creditBalance = null
        plan = null
        lastQuotaFetch = 0
        lastTokenAttempt = 0
        store.dropToken()
        onChanged()
    }

    /** Blocking. [force] skips the throttles (not the 60-per-hour server limit). */
    fun refresh(force: Boolean = false) {
        if (!session.signedIn()) {
            if (verified != null) clear()
            return
        }
        val now = clock()
        val token = verified
        val stale = token == null || now >= token.expiresAtMs - REFRESH_BEFORE_EXPIRY_MS
        if ((stale || force) && now - lastTokenAttempt >= TOKEN_RETRY_MS) {
            lastTokenAttempt = now
            fetchToken()
        }
        if (force || now - lastQuotaFetch >= QUOTA_TTL_MS) {
            lastQuotaFetch = now
            fetchQuota()
        }
    }

    /**
     * Blocking. The plan changed on the server (a payment or store purchase it verified, ADR 0047): fetch the token and
     * the plan hints now, past the client-side throttles, so the keyboard's Premium gates open at once. The server's
     * own rate limit still applies; a failure keeps the old token and the next [refresh] tries again.
     */
    fun planChanged() {
        lastTokenAttempt = 0
        lastQuotaFetch = 0
        refresh(force = true)
    }

    /** The usage block of an AI answer (the freshest figure; no extra call). */
    fun recordUsage(kind: AiTaskKind, remaining: Int?, resetsAtMs: Long?) {
        if (remaining == null) return
        val old = quotaByKind[kind]
        quotaByKind[kind] = AiQuotaHint(remaining, old?.limit, old?.period ?: AiPeriod.NONE, resetsAtMs ?: old?.resetsAtMs)
        onChanged()
    }

    private fun fetchToken() {
        val req = Request.Builder().url("$base/me/entitlements/token").header("Accept", "application/json").get().build()
        try {
            http.newCall(req).execute().use { r ->
                noteServerTime(r.headers.getDate("Date")?.time)
                when {
                    r.code == 401 || r.code == 403 -> {
                        verified = null
                        store.dropToken()
                        onChanged()
                    }
                    r.isSuccessful -> {
                        val body = r.body?.string() ?: return
                        val jws = (Json.parseToJsonElement(body) as? JsonObject)?.let { AiWire.str(it, "jws") } ?: return
                        val v = verifier.verify(jws, session.subject(), session.deviceId(), maxOf(clock(), newestSeen))
                        if (v is TokenVerdict.Valid) {
                            val serverTime = r.headers.getDate("Date")?.time
                            store.save(jws, v.token.issuedAtMs, serverTime)
                            newestSeen = maxOf(newestSeen, v.token.issuedAtMs, serverTime ?: 0L)
                            verified = v.token
                            onChanged()
                        }
                    }
                }
            }
        } catch (e: IOException) {
            // Offline or interrupted: the cached token (if any) stays. A 401 whose refresh was refused ended
            // the session: that is the "drop the token on 401" rule (D4.6) as the authenticator reports it.
            if (ApiErrors.map(e) is ApiError.SignedOut) {
                verified = null
                store.dropToken()
                onChanged()
            }
        } catch (_: RuntimeException) {
        }
    }

    private fun fetchQuota() {
        val req = Request.Builder().url("$base/me/entitlements").header("Accept", "application/json").get().build()
        try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return
                val o = Json.parseToJsonElement(r.body?.string() ?: return) as? JsonObject ?: return
                plan = AiWire.str(o["plan"] as? JsonObject, "displayName") ?: AiWire.str(o["plan"] as? JsonObject, "planCode")
                val fresh = HashMap<AiTaskKind, AiQuotaHint>()
                val enabled = HashSet<AiTaskKind>()
                val codes = HashSet<String>()
                for (f in (o["features"] as? JsonArray).orEmpty()) {
                    val fo = f as? JsonObject ?: continue
                    val code = AiWire.str(fo, "featureCode")
                    if (code != null && (fo["accessEnabled"] as? JsonPrimitive)?.contentOrNull == "true") codes += code
                    val kind = AiTaskKind.entries.firstOrNull { AiWire.featureCode(it) == code } ?: continue
                    if ((fo["accessEnabled"] as? JsonPrimitive)?.contentOrNull != "true") continue
                    enabled += kind
                    fresh[kind] = AiQuotaHint(
                        remaining = (fo["remaining"] as? JsonPrimitive)?.intOrNull,
                        limit = (fo["includedUnits"] as? JsonPrimitive)?.intOrNull,
                        period = AiPeriod.entries.firstOrNull { it.name == AiWire.str(fo, "resetPeriod") } ?: AiPeriod.NONE,
                        resetsAtMs = AiWire.str(fo, "resetsAt")?.let(AiWire::parseInstantMs),
                    )
                }
                quotaByKind.clear()
                quotaByKind.putAll(fresh)
                creditBalance = (o["creditBalance"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it >= 0 }?.let { kotlin.math.floor(it + 1e-9).toLong() }
                entitledKinds = enabled
                entitledCodes = codes
                onChanged()
            }
        } catch (_: IOException) {
        } catch (_: RuntimeException) {
        }
    }

    private fun noteServerTime(ms: Long?) {
        // `Date` is a coarse server clock: it only ever moves the time floor forward.
        if (ms == null) return
        if (ms > newestSeen) {
            newestSeen = ms
            store.seen(ms)
        }
    }

    private val base = baseUrl.trimEnd('/') + "/api/v1"

    companion object {
        const val REFRESH_BEFORE_EXPIRY_MS = 6L * 3600_000
        const val TOKEN_RETRY_MS = 5L * 60_000
        const val QUOTA_TTL_MS = 5L * 60_000
    }
}
