package com.qalsmartkeyboard.aiclient

import com.qalsmartkeyboard.api.ApiError
import com.qalsmartkeyboard.api.ProblemCodes
import com.qalsmartkeyboard.ime.ai.AiAlternative
import com.qalsmartkeyboard.ime.ai.AiEmailMode
import com.qalsmartkeyboard.ime.ai.AiEmailSpec
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
     * about what the user wrote), unless the user picked one in the direction control. `Idempotency-Key` is
     * [AiRequest.requestId]: the same value on a retry after a network error lets the server replay. User text only
     * goes in the body.
     */
    fun request(baseUrl: String, r: AiRequest, saveToHistory: Boolean = false): Request {
        val (path, body) = when (val t = r.task) {
            is AiTask.Translate -> "/ai/translate" to buildJsonObject {
                put("text", r.text)
                t.sourceLanguage?.let { put("sourceLanguage", it) }
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
                t.outputLanguage?.let { put("outputLanguage", it) }
                putJsonObject("options") { if (tone == null) put("mode", t.mode.name) else put("tone", tone.name) }
                if (t.variation > 0) put("variation", t.variation)
            }
            is AiTask.Format -> "/ai/transform" to buildJsonObject {
                put("task", "FORMAT_TEXT")
                put("text", r.text)
                t.outputLanguage?.let { put("outputLanguage", it) }
                putJsonObject("options") { put("format", t.format.name) }
                if (t.variation > 0) put("variation", t.variation)
            }
            is AiTask.Simplify -> "/ai/transform" to buildJsonObject {
                put("task", "REWRITE")
                put("text", r.text)
                t.outputLanguage?.let { put("outputLanguage", it) }
                putJsonObject("options") { put("mode", "SIMPLIFY") }
                if (t.variation > 0) put("variation", t.variation)
            }
            is AiTask.Explain -> "/ai/transform" to buildJsonObject {
                put("task", "EXPLAIN_TEXT")
                put("text", r.text)
                put("outputLanguage", t.outputLanguage)
                if (t.variation > 0) put("variation", t.variation)
            }
            is AiTask.Summarize -> "/ai/transform" to buildJsonObject {
                put("task", "SUMMARIZE")
                put("text", r.text)
                put("outputLanguage", t.outputLanguage)
                putJsonObject("options") { put("targetLength", t.length.name) }
                if (t.variation > 0) put("variation", t.variation)
            }
            is AiTask.Reply -> "/ai/replies" to buildJsonObject {
                put("contextText", r.text)
                put("replyLanguage", t.replyLanguage)
                put("count", t.count)
                if (t.instructions.isNotBlank()) put("instructions", t.instructions)
                t.intent?.let { put("intent", it.name) }
                if (t.variation > 0) put("variation", t.variation)
            }
            is AiTask.Email -> "/ai/drafts" to emailBody(t.spec)
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

    /**
     * `DraftRequest` (ADR 0027): only the texts the mode uses are sent (`originalEmail` outside REPLY and `draft` outside
     * IMPROVE are refused by the server); empty optional texts and names are left out.
     */
    fun emailBody(s: AiEmailSpec): JsonObject = buildJsonObject {
        put("documentType", s.documentType.name)
        put("mode", s.mode.name)
        put("language", s.language)
        if (s.instructions.isNotBlank()) put("instructions", s.instructions)
        if (s.mode == AiEmailMode.REPLY) put("originalEmail", s.originalEmail)
        if (s.mode == AiEmailMode.IMPROVE) put("draft", s.draft)
        put("tone", s.tone.name)
        put("length", s.length.name)
        put("includeSubject", s.includeSubject)
        if (s.variation > 0) put("variation", s.variation)
        if (s.recipientName.isNotBlank()) putJsonObject("recipient") { put("name", s.recipientName) }
        if (s.senderName.isNotBlank()) put("senderName", s.senderName)
    }

    /** Null when the body is not a usable result (the caller reports [AiFailure.Other]). */
    fun result(task: AiTask, body: String): AiResult? {
        val o = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return null
        when (task) {
            is AiTask.Reply -> return replies(o)
            is AiTask.Email -> return draft(o)
            else -> Unit
        }
        val text = str(o, if (task is AiTask.Translate) "translatedText" else "resultText")?.takeIf { it.isNotBlank() } ?: return null
        val alternatives = (o["alternatives"] as? JsonArray).orEmpty().mapNotNull {
            val a = it as? JsonObject ?: return@mapNotNull null
            AiAlternative(str(a, "label"), str(a, "text")?.takeIf { t -> t.isNotBlank() } ?: return@mapNotNull null)
        }
        val usage = o["usage"] as? JsonObject
        return AiResult(
            text = text,
            alternatives = alternatives,
            notes = notes(o),
            remaining = (usage?.get("remaining") as? JsonPrimitive)?.intOrNull,
            resetsAtMs = str(usage, "resetsAt")?.let(::parseInstantMs),
            replayed = (o["replayed"] as? JsonPrimitive)?.booleanOrNull ?: false,
            language = str(o, if (task is AiTask.Translate) "destinationLanguage" else "language"),
            sourceLanguage = if (task is AiTask.Translate) str(o, "detectedSourceLanguage") ?: str(o, "sourceLanguage") else null,
        )
    }

    private fun notes(o: JsonObject): List<String> =
        (o["notes"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { n -> str(n, "message") } }

    /** `RepliesResponse`: the suggestions in order; the first one is also [AiResult.text]. Null without any. */
    private fun replies(o: JsonObject): AiResult? {
        val suggestions = (o["suggestions"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.let { s -> str(s, "text") }?.takeIf { t -> t.isNotBlank() } }
        if (suggestions.isEmpty()) return null
        val usage = o["usage"] as? JsonObject
        return AiResult(
            text = suggestions.first(),
            notes = notes(o),
            remaining = (usage?.get("remaining") as? JsonPrimitive)?.intOrNull,
            resetsAtMs = str(usage, "resetsAt")?.let(::parseInstantMs),
            replayed = (o["replayed"] as? JsonPrimitive)?.booleanOrNull ?: false,
            language = str(o, "language"),
            suggestions = suggestions,
        )
    }

    /** `DraftResponse`: the body (required), the subject when present, the placeholders to fill in. */
    private fun draft(o: JsonObject): AiResult? {
        val body = str(o, "body")?.takeIf { it.isNotBlank() } ?: return null
        val usage = o["usage"] as? JsonObject
        return AiResult(
            text = body,
            notes = notes(o),
            remaining = (usage?.get("remaining") as? JsonPrimitive)?.intOrNull,
            resetsAtMs = str(usage, "resetsAt")?.let(::parseInstantMs),
            replayed = (o["replayed"] as? JsonPrimitive)?.booleanOrNull ?: false,
            language = str(o, "language"),
            subject = str(o, "subject")?.takeIf { it.isNotBlank() },
            placeholders = (o["placeholders"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull },
        )
    }

    /** The contract `FeatureCode` a request is metered as (also the key of `/me/entitlements`). */
    fun featureCode(kind: AiTaskKind) = when (kind) {
        AiTaskKind.TRANSLATE -> "TRANSLATE"
        AiTaskKind.GRAMMAR -> "GRAMMAR_CORRECTION"
        AiTaskKind.REWRITE -> "REWRITE"
        AiTaskKind.TONE -> "TONE_ADJUST"
        AiTaskKind.EXPLAIN -> "EXPLAIN_TEXT"
        AiTaskKind.SUMMARIZE -> "SUMMARIZE"
        AiTaskKind.REPLY -> "SMART_REPLY"
        AiTaskKind.EMAIL -> "DRAFT_DOCUMENT"
        AiTaskKind.FORMAT -> "FORMAT_TEXT"
    }

    /** Maps the client's [ApiError] to what the toolbar shows. Switches on `code`, never on a title. */
    fun failure(e: ApiError): AiFailure = when (e) {
        is ApiError.UpdateRequired -> AiFailure.UpdateRequired
        is ApiError.SignedOut -> AiFailure.SignInRequired
        is ApiError.Timeout -> AiFailure.Timeout
        is ApiError.Unreachable -> AiFailure.Unreachable
        is ApiError.Network -> AiFailure.Offline
        is ApiError.Unexpected -> AiFailure.Other
        is ApiError.Http -> http(e)
    }

    private fun http(e: ApiError.Http): AiFailure = when (e.code) {
        "QUOTA_EXCEEDED" -> AiFailure.QuotaExceeded(
            resetAtMs = (e.extensions["resetAt"] as? JsonPrimitive)?.contentOrNull?.let(::parseInstantMs),
            // T8.10: PLAN options (or an untyped legacy entry) lead to the plans, CREDIT_PACK options to the credits.
            canUpgrade = upgradeTypes(e).any { it != "CREDIT_PACK" },
            canBuyCredits = upgradeTypes(e).any { it == "CREDIT_PACK" },
        )
        "RATE_LIMITED" -> AiFailure.RateLimited((e as? ApiError.RateLimited)?.retryAfterSeconds)
        "FEATURE_NOT_ENTITLED" -> AiFailure.NotEntitled
        "ON_DEVICE_ONLY_PREFERRED" -> AiFailure.OnDeviceOnly
        "CONSENT_REQUIRED" -> AiFailure.ConsentRequired((e.extensions["consentCode"] as? JsonPrimitive)?.contentOrNull)
        "OUTPUT_GUARDRAIL_FAILED" -> AiFailure.GuardrailFailed
        "CONTENT_REJECTED" -> AiFailure.ContentRejected
        "LANGUAGE_NOT_SUPPORTED", "LANGUAGE_PAIR_UNAVAILABLE" -> AiFailure.LanguageUnsupported
        "REQUEST_IN_PROGRESS" -> AiFailure.Busy
        "INPUT_TOO_LONG" -> AiFailure.InputTooLong(e.fieldErrors.firstOrNull()?.field)
        "VALIDATION_FAILED" -> AiFailure.Invalid(e.fieldErrors.firstOrNull()?.field)
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

    /** The `type` of each `upgradeOptions` entry (`PLAN` when it has none). */
    private fun upgradeTypes(e: ApiError.Http): List<String> =
        (e.extensions["upgradeOptions"] as? JsonArray).orEmpty().map { o -> str(o as? JsonObject, "type") ?: "PLAN" }

    fun parseInstantMs(s: String): Long? = try {
        OffsetDateTime.parse(s).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }

    fun str(o: JsonObject?, name: String): String? = (o?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
