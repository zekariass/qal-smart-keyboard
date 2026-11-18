package com.qalsmartkeyboard.aiclient

import com.qalsmartkeyboard.api.ApiError
import com.qalsmartkeyboard.api.ProblemCodes
import com.qalsmartkeyboard.ime.ai.AiAlternative
import com.qalsmartkeyboard.ime.ai.AiFailure
import com.qalsmartkeyboard.ime.ai.AiRequest
import com.qalsmartkeyboard.ime.ai.AiResult
import com.qalsmartkeyboard.ime.ai.AiTask
import com.qalsmartkeyboard.ime.ai.AiTaskKind
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Request bodies, response parsing and error mapping of `POST /ai/translate` and `POST /ai/transform` (B5.5). */
internal object AiWire {
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * The source language is left out so the server detects it (the typing language says nothing
     * about what the user wrote). `Idempotency-Key` is [AiRequest.requestId]: the same value on a
     * retry after a network error lets the server replay. User text only goes in the body.
     */
    fun request(baseUrl: String, r: AiRequest, saveToHistory: Boolean = false): Request {
        val (path, body) = when (val t = r.task) {
            is AiTask.Translate -> "/ai/translate" to buildJsonObject {
                put("text", r.text)
                put("destinationLanguage", t.destination.language)
                put("preserveNames", true)
                t.destination.style?.let { put("style", it) }
                t.destination.glossaryId?.let { put("glossaryId", it) }
                t.destination.serverProfileId?.let { put("destinationProfileId", it) }
                if (t.destination.formality != null || t.destination.addresseeGender != null) {
                    putJsonObject("addressee") {
                        t.destination.formality?.let { put("formality", it) }
                        t.destination.addresseeGender?.let { put("gender", it) }
                    }
                }
            }
            AiTask.Grammar -> "/ai/transform" to buildJsonObject {
                put("task", "GRAMMAR_CORRECTION")
                put("text", r.text)
                putJsonObject("options") { put("mode", "GRAMMAR") }
            }
            is AiTask.Rewrite -> "/ai/transform" to buildJsonObject {
                val tone = t.tone
                put("task", if (tone == null) "REWRITE" else "TONE_ADJUST")
                put("text", r.text)
                putJsonObject("options") { if (tone == null) put("mode", "NATURAL") else put("tone", tone.name) }
            }
        }
        // Sent only when the user turned history on (A2.6); the server still needs the preference, the consent and the plan.
        val sent = if (saveToHistory) JsonObject(body + ("saveToHistory" to JsonPrimitive(true))) else body
        return Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/v1" + path)
            .header("Accept", "application/json")
            .header("Idempotency-Key", r.requestId)
            .post(Json.encodeToString(JsonObject.serializer(), sent).toRequestBody(JSON))
            .build()
    }

    /** Null when the body is not a usable result (the caller reports [AiFailure.Other]). */
    fun result(task: AiTask, body: String): AiResult? {
        val o = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return null
        val text = str(o, if (task is AiTask.Translate) "translatedText" else "resultText")?.takeIf { it.isNotBlank() } ?: return null
        val alternatives = (o["alternatives"] as? JsonArray).orEmpty().mapNotNull {
            val a = it as? JsonObject ?: return@mapNotNull null
            AiAlternative(str(a, "label"), str(a, "text")?.takeIf { t -> t.isNotBlank() } ?: return@mapNotNull null)
        }
        val notes = (o["notes"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { n -> str(n, "message") } }
        val usage = o["usage"] as? JsonObject
        return AiResult(
            text = text,
            alternatives = alternatives,
            notes = notes,
            remaining = (usage?.get("remaining") as? JsonPrimitive)?.intOrNull,
            resetsAtMs = str(usage, "resetsAt")?.let(::parseInstantMs),
            replayed = (o["replayed"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }

    fun featureCode(kind: AiTaskKind) = when (kind) {
        AiTaskKind.TRANSLATE -> "TRANSLATE"
        AiTaskKind.GRAMMAR -> "GRAMMAR_CORRECTION"
        AiTaskKind.REWRITE -> "REWRITE"
    }

    /** Maps the client's [ApiError] to what the toolbar shows. Switches on `code`, never on a title. */
    fun failure(e: ApiError): AiFailure = when (e) {
        is ApiError.UpdateRequired -> AiFailure.UpdateRequired
        is ApiError.SignedOut -> AiFailure.SignInRequired
        is ApiError.Timeout -> AiFailure.Timeout
        is ApiError.Network -> AiFailure.Offline
        is ApiError.Unexpected -> AiFailure.Other
        is ApiError.Http -> http(e)
    }

    private fun http(e: ApiError.Http): AiFailure = when (e.code) {
        "QUOTA_EXCEEDED" -> AiFailure.QuotaExceeded(
            resetAtMs = (e.extensions["resetAt"] as? JsonPrimitive)?.contentOrNull?.let(::parseInstantMs),
            canUpgrade = (e.extensions["upgradeOptions"] as? JsonArray)?.isNotEmpty() == true,
        )
        "RATE_LIMITED" -> AiFailure.RateLimited((e as? ApiError.RateLimited)?.retryAfterSeconds)
        "FEATURE_NOT_ENTITLED" -> AiFailure.NotEntitled
        "ON_DEVICE_ONLY_PREFERRED" -> AiFailure.OnDeviceOnly
        "CONSENT_REQUIRED" -> AiFailure.ConsentRequired((e.extensions["consentCode"] as? JsonPrimitive)?.contentOrNull)
        "OUTPUT_GUARDRAIL_FAILED" -> AiFailure.GuardrailFailed
        "CONTENT_REJECTED" -> AiFailure.ContentRejected
        "LANGUAGE_NOT_SUPPORTED", "LANGUAGE_PAIR_UNAVAILABLE" -> AiFailure.LanguageUnsupported
        "REQUEST_IN_PROGRESS" -> AiFailure.Busy
        "INPUT_TOO_LONG" -> AiFailure.TooLong
        "UNAUTHENTICATED", "TOKEN_EXPIRED", "GUEST_SESSION_INVALID", "ATTESTATION_REQUIRED", "ATTESTATION_FAILED",
        "GUEST_NOT_ALLOWED", ProblemCodes.REFRESH_TOKEN_REUSED,
        -> AiFailure.SignInRequired
        "ACCOUNT_SUSPENDED", "DELETION_PENDING" -> AiFailure.AccountBlocked
        "PROVIDER_UNAVAILABLE", "PROVIDER_TIMEOUT" -> AiFailure.ServiceUnavailable
        else -> when {
            e.status == 401 -> AiFailure.SignInRequired
            e.status >= 500 -> AiFailure.ServiceUnavailable
            else -> AiFailure.Other
        }
    }

    fun parseInstantMs(s: String): Long? = try {
        OffsetDateTime.parse(s).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }

    fun str(o: JsonObject?, name: String): String? = (o?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
