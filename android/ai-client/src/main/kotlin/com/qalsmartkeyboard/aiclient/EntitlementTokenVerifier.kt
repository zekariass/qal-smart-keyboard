package com.qalsmartkeyboard.aiclient

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okio.ByteString.Companion.decodeBase64
import uniffi.qal_keyboard_core.verifyEd25519

/**
 * The Ed25519 public keys of the entitlement token, **pinned in the app build** (ADR 0013 D4.2, D7):
 * a key is trusted because the build lists it, never because a server did. [byKid] maps `kid` to
 * the raw 32-byte key. An empty set (no keys configured) fails closed: every token is rejected and
 * no offline gate ever opens.
 */
class PinnedKeys(val byKid: Map<String, ByteArray>) {
    companion object {
        val NONE = PinnedKeys(emptyMap())

        /**
         * Parses an RFC 7517 JWK Set of Ed25519 keys (`kty OKP`, `crv Ed25519`, `x` base64url): the file
         * given with `-Pqal.entitlementJwksFile` at build time, in the format `/.well-known/jwks.json` publishes.
         * Entries that are not Ed25519 or whose key is not 32 bytes are ignored.
         */
        fun fromJwks(json: String): PinnedKeys {
            val keys = (Json.parseToJsonElement(json) as? JsonObject)?.get("keys") as? JsonArray ?: return NONE
            val out = LinkedHashMap<String, ByteArray>()
            for (k in keys) {
                val o = k as? JsonObject ?: continue
                fun s(n: String) = (o[n] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                if (s("kty") != "OKP" || s("crv") != "Ed25519") continue
                val kid = s("kid") ?: continue
                val raw = s("x")?.takeIf { B64URL.matches(it) }?.decodeBase64()?.toByteArray() ?: continue
                if (raw.size == 32) out[kid] = raw
            }
            return PinnedKeys(out)
        }
    }
}

/** The claims of a token that passed every check of ADR 0013 D4 (times in epoch milliseconds). */
class VerifiedToken(
    val keyId: String,
    val subject: String,
    val planCode: String,
    val features: Map<String, Boolean>,
    val issuedAtMs: Long,
    val expiresAtMs: Long,
    val offlineGraceUntilMs: Long,
) {
    /** Never prints claims. */
    override fun toString() = "VerifiedToken(redacted)"
}

enum class TokenRejection {
    MALFORMED, TOO_LARGE, ALGORITHM, TYPE, FORBIDDEN_HEADER, UNKNOWN_KEY, BAD_SIGNATURE, CLAIMS, SUBJECT, DEVICE, TIME_WINDOW,
}

sealed interface TokenVerdict {
    class Valid(val token: VerifiedToken) : TokenVerdict
    class Rejected(val reason: TokenRejection) : TokenVerdict
}

/**
 * Client side of ADR 0013 D4 for the entitlement token (rules 1 to 4; rule 5, the clock, is
 * [OfflineGates.evaluate]):
 *
 * 1. three base64url parts without padding, at most 8 KiB;
 * 2. `alg` exactly `EdDSA` and `typ` exactly `qal-ent+jwt` (checked before any key lookup); no
 *    `crit`, `jwk`, `jku`, `x5u`, `x5c`; `kid` in the **pinned** set;
 * 3. the strict Ed25519 signature (keyboard-core `verifyEd25519`) over `ASCII(header.payload)`;
 * 4. `iss`, `aud`, `ver = 1`; `sub` is the signed-in user; `deviceId`, when present, is this device;
 *    `iat < exp <= iat + 24 h`; `exp <= offlineGraceUntil <= exp + 72 h`; `features` an object of
 *    booleans; `planCode` and `jti` present; `iat` at most 5 minutes in the future.
 *
 * Fails closed: an unknown [expectedSubject] (null) rejects the token. Never logs a token.
 */
class EntitlementTokenVerifier(
    private val pinned: PinnedKeys,
    private val issuer: String = ISSUER,
    private val audience: String = AUDIENCE,
) {
    fun verify(jws: String, expectedSubject: String?, expectedDeviceId: String?, nowMs: Long): TokenVerdict {
        if (jws.length > MAX_BYTES) return reject(TokenRejection.TOO_LARGE)
        val parts = jws.split('.')
        if (parts.size != 3 || parts.any { it.isEmpty() || !B64URL.matches(it) }) return reject(TokenRejection.MALFORMED)
        val header = decodeObject(parts[0]) ?: return reject(TokenRejection.MALFORMED)
        if (str(header, "alg") != "EdDSA") return reject(TokenRejection.ALGORITHM)
        if (str(header, "typ") != TYP) return reject(TokenRejection.TYPE)
        if (FORBIDDEN_HEADERS.any { it in header }) return reject(TokenRejection.FORBIDDEN_HEADER)
        val kid = str(header, "kid") ?: return reject(TokenRejection.MALFORMED)
        val key = pinned.byKid[kid] ?: return reject(TokenRejection.UNKNOWN_KEY)

        val sig = parts[2].decodeBase64()?.toByteArray() ?: return reject(TokenRejection.MALFORMED)
        if (sig.size != 64) return reject(TokenRejection.BAD_SIGNATURE)
        val signed = "${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII)
        // Strict Ed25519 in keyboard-core (the same rules as pack signatures); a failure to call it fails closed.
        val ok = try {
            verifyEd25519(key, signed, sig)
        } catch (e: RuntimeException) {
            false
        } catch (e: UnsatisfiedLinkError) {
            false
        }
        if (!ok) return reject(TokenRejection.BAD_SIGNATURE)

        val c = decodeObject(parts[1]) ?: return reject(TokenRejection.MALFORMED)
        if (str(c, "iss") != issuer || !hasAudience(c["aud"]) || long(c, "ver") != 1L) return reject(TokenRejection.CLAIMS)
        val sub = str(c, "sub")?.takeIf { it.isNotEmpty() } ?: return reject(TokenRejection.CLAIMS)
        val plan = str(c, "planCode")?.takeIf { it.isNotEmpty() } ?: return reject(TokenRejection.CLAIMS)
        if (str(c, "jti").isNullOrEmpty()) return reject(TokenRejection.CLAIMS)
        val features = (c["features"] as? JsonObject)?.let { f ->
            f.mapValues { (it.value as? JsonPrimitive)?.takeIf { p -> !p.isString }?.booleanOrNull ?: return reject(TokenRejection.CLAIMS) }
        } ?: return reject(TokenRejection.CLAIMS)
        val iat = long(c, "iat") ?: return reject(TokenRejection.CLAIMS)
        val exp = long(c, "exp") ?: return reject(TokenRejection.CLAIMS)
        val grace = long(c, "offlineGraceUntil") ?: return reject(TokenRejection.CLAIMS)
        if (!(iat < exp && exp - iat <= VALIDITY_S && grace >= exp && grace - exp <= GRACE_S)) return reject(TokenRejection.TIME_WINDOW)
        if (iat * 1000 > nowMs + SKEW_MS) return reject(TokenRejection.TIME_WINDOW)

        if (expectedSubject == null || sub != expectedSubject) return reject(TokenRejection.SUBJECT)
        c["deviceId"]?.let { d ->
            val did = (d as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            if (did == null || expectedDeviceId == null || did != expectedDeviceId) return reject(TokenRejection.DEVICE)
        }
        return TokenVerdict.Valid(VerifiedToken(kid, sub, plan, features, iat * 1000, exp * 1000, grace * 1000))
    }

    private fun reject(r: TokenRejection) = TokenVerdict.Rejected(r)

    private fun hasAudience(a: JsonElement?): Boolean = when (a) {
        is JsonPrimitive -> a.isString && a.content == audience
        is JsonArray -> a.any { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content == audience }
        else -> false
    }

    private fun decodeObject(part: String): JsonObject? = try {
        part.decodeBase64()?.utf8()?.let { Json.parseToJsonElement(it) as? JsonObject }
    } catch (e: Exception) {
        null
    }

    private fun str(o: JsonObject, name: String): String? = (o[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun long(o: JsonObject, name: String): Long? = (o[name] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    companion object {
        const val ISSUER = "qal-smart-keyboard"
        const val AUDIENCE = "qal-keyboard"
        const val TYP = "qal-ent+jwt"
        const val MAX_BYTES = 8 * 1024
        const val VALIDITY_S = 24L * 3600
        const val GRACE_S = 72L * 3600
        const val SKEW_MS = 5L * 60_000
        private val FORBIDDEN_HEADERS = listOf("crit", "jwk", "jku", "x5u", "x5c")
    }
}

internal val B64URL = Regex("[A-Za-z0-9_-]+")

enum class GateState {
    /** Before `exp`: the gates apply. */
    VALID,

    /** `exp` to `offlineGraceUntil`: the gates apply only while offline; online the client fetches a new token. */
    OFFLINE_GRACE,

    /** No token, or past `offlineGraceUntil`: every gate is closed. */
    VOID,
}

/** The offline gates a token opens right now (ADR 0013 D3, D4.5). Only [onDeviceModels] exists today. */
class OfflineGates(val state: GateState, val onDeviceModels: Boolean) {
    companion object {
        val CLOSED = OfflineGates(GateState.VOID, false)

        /**
         * @param nowMs "now" is the later of the device clock and the newest `iat` or server `Date`
         *   seen ([EntitlementTokenStore.monotonicNow]), so turning the clock back cannot extend a token.
         */
        fun evaluate(token: VerifiedToken?, nowMs: Long, online: Boolean): OfflineGates {
            if (token == null || nowMs >= token.offlineGraceUntilMs) return CLOSED
            val state = if (nowMs < token.expiresAtMs) GateState.VALID else GateState.OFFLINE_GRACE
            val open = state == GateState.VALID || !online
            return OfflineGates(state, open && token.features["ON_DEVICE_AI_MODELS"] == true)
        }
    }
}
