package com.qalsmartkeyboard.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Everything a call through this module can fail with. Messages contain only the status and the
 * error code: never tokens, phone numbers, user text or server titles.
 */
sealed class ApiError(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    open val status: Int? get() = null
    open val code: String? get() = null
    open val correlationId: String? get() = null

    /** A non-2xx answer with a Problem Details body (or a body that is none: code `UNKNOWN`). */
    open class Http(val details: ProblemDetails) :
        ApiError("HTTP ${details.status} ${details.code}") {
        override val status: Int get() = details.status
        override val code: String get() = details.code
        override val correlationId: String? get() = details.correlationId
        val title: String? get() = details.title
        val fieldErrors: List<FieldError> get() = details.fieldErrors
        val extensions: Map<String, JsonElement> get() = details.extensions
    }

    /** 426 `APP_VERSION_UNSUPPORTED`: the app is below the platform minimum and must be updated. */
    class UpdateRequired(details: ProblemDetails, val minSupportedAppVersion: String?) : Http(details)

    /** 429 (`RATE_LIMITED` or `QUOTA_EXCEEDED`); [retryAfterSeconds] from `Retry-After` when present. */
    class RateLimited(details: ProblemDetails, val retryAfterSeconds: Long?) : Http(details)

    /**
     * The session ended and the tokens were cleared (refresh reuse, guest session invalid,
     * refresh rejected). The UI shows the signed-out state ([SessionManager.state]).
     */
    class SignedOut(details: ProblemDetails, val reason: SignedOutReason) : Http(details)

    /** Connect/read/write/call timeout. */
    class Timeout(cause: Throwable) : ApiError("request timed out", cause)

    /** No connectivity, DNS, TLS or other I/O failure, including a refresh that could not be completed. */
    open class Network(cause: Throwable, message: String = "network failure") : ApiError(message, cause)

    /**
     * No connection to the server could be made at all: refused, unknown host, no route, or the connect timeout. Usually
     * the wrong server address (a phone build with the emulator's `10.0.2.2`) or a server that is down; distinct from
     * [Timeout], where the server accepted the request and answered too slowly.
     */
    class Unreachable(cause: Throwable) : Network(cause, "server unreachable")

    /** A non-Problem answer: unreadable success body, redirect, or an unsupported response type. */
    class Unexpected(override val status: Int?, cause: Throwable? = null) :
        ApiError("unexpected response" + (status?.let { " ($it)" } ?: ""), cause)
}

internal object ApiErrorMapper {
    private val sessionEndingCodes = mapOf(
        ProblemCodes.REFRESH_TOKEN_REUSED to SignedOutReason.REFRESH_TOKEN_REUSED,
        ProblemCodes.GUEST_SESSION_INVALID to SignedOutReason.GUEST_SESSION_INVALID,
    )

    fun fromHttp(status: Int, body: String?, headers: Map<String, List<String>>): ApiError {
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
        val details = ProblemParser.parse(body, status, header("X-Correlation-Id"))
        if (status == 401) sessionEndingCodes[details.code]?.let { return ApiError.SignedOut(details, it) }
        return when {
            status == 426 || details.code == ProblemCodes.APP_VERSION_UNSUPPORTED ->
                ApiError.UpdateRequired(
                    details,
                    (details.extensions["minSupportedAppVersion"] as? JsonPrimitive)?.contentOrNull,
                )
            status == 429 -> ApiError.RateLimited(details, header("Retry-After")?.trim()?.toLongOrNull())
            else -> ApiError.Http(details)
        }
    }
}
