package com.qalsmartkeyboard.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** One entry of the Problem `errors` array. */
data class FieldError(val field: String?, val code: String, val message: String)

/**
 * RFC 9457 Problem Details as the contract defines them. Clients switch on [code], never on
 * [title]. [extensions] holds every member that is not a standard one (`minSupportedAppVersion`,
 * `consentCode`, `currentRevision`, `featureCode`, `resetAt`, ...).
 */
data class ProblemDetails(
    val status: Int,
    val code: String,
    val title: String?,
    val detail: String?,
    val type: String?,
    val correlationId: String?,
    val fieldErrors: List<FieldError>,
    val extensions: Map<String, JsonElement>,
)

object ProblemParser {
    const val UNKNOWN_CODE = "UNKNOWN"

    private val standard = setOf("type", "title", "status", "detail", "instance", "code", "correlationId", "errors")

    /**
     * Parses an `application/problem+json` body. A body that is not a JSON object (an HTML error
     * page from a proxy, an empty body) still yields a [ProblemDetails] with code [UNKNOWN_CODE]
     * so callers have one path. [headerCorrelationId] (`X-Correlation-Id`) fills in when the
     * body has none.
     */
    fun parse(body: String?, httpStatus: Int, headerCorrelationId: String?): ProblemDetails {
        val obj = try {
            if (body.isNullOrBlank()) null else Json.parseToJsonElement(body) as? JsonObject
        } catch (e: Exception) {
            null
        }
        if (obj == null) {
            return ProblemDetails(
                status = httpStatus,
                code = UNKNOWN_CODE,
                title = null,
                detail = null,
                type = null,
                correlationId = headerCorrelationId,
                fieldErrors = emptyList(),
                extensions = emptyMap(),
            )
        }
        fun str(name: String): String? = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val errors = (obj["errors"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun s(n: String) = (o[n] as? JsonPrimitive)?.contentOrNull
            FieldError(s("field"), s("code") ?: return@mapNotNull null, s("message") ?: "")
        }
        return ProblemDetails(
            status = (obj["status"] as? JsonPrimitive)?.intOrNull ?: httpStatus,
            code = str("code") ?: UNKNOWN_CODE,
            title = str("title"),
            detail = str("detail"),
            type = str("type"),
            correlationId = str("correlationId") ?: headerCorrelationId,
            fieldErrors = errors,
            extensions = obj.filterKeys { it !in standard },
        )
    }
}

/** Contract error codes the client reacts to (the full list is open: unknown codes are fine). */
object ProblemCodes {
    const val TOKEN_EXPIRED = "TOKEN_EXPIRED"
    const val REFRESH_TOKEN_REUSED = "REFRESH_TOKEN_REUSED"
    const val GUEST_SESSION_INVALID = "GUEST_SESSION_INVALID"
    const val UNAUTHENTICATED = "UNAUTHENTICATED"
    const val ACCOUNT_SUSPENDED = "ACCOUNT_SUSPENDED"
    const val APP_VERSION_UNSUPPORTED = "APP_VERSION_UNSUPPORTED"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val QUOTA_EXCEEDED = "QUOTA_EXCEEDED"
}
