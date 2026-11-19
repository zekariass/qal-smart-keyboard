package com.qalsmartkeyboard.api

import com.qalsmartkeyboard.api.generated.infrastructure.ClientError
import com.qalsmartkeyboard.api.generated.infrastructure.ClientException
import com.qalsmartkeyboard.api.generated.infrastructure.ServerError
import com.qalsmartkeyboard.api.generated.infrastructure.ServerException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Runs a blocking generated-client call on [Dispatchers.IO] and maps every failure to
 * [ApiError]:
 *
 * ```
 * val config = apiCall { configApi.getClientConfig() }
 * ```
 *
 * Success returns the generated model. Problem Details answers become [ApiError.Http] (with
 * [ApiError.UpdateRequired] for 426, [ApiError.RateLimited] for 429 and [ApiError.SignedOut]
 * when the authenticator ended the session), a server that cannot be reached at all (refused, unknown host, connect
 * timeout) [ApiError.Unreachable], other timeouts [ApiError.Timeout], other I/O failures [ApiError.Network].
 */
suspend fun <T> apiCall(block: () -> T): T = withContext(Dispatchers.IO) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw ApiErrors.map(e)
    }
}

/** Like [apiCall] but returns the failure instead of throwing it. */
suspend fun <T> apiResult(block: () -> T): Result<T> =
    try {
        Result.success(apiCall(block))
    } catch (e: ApiError) {
        Result.failure(e)
    }

object ApiErrors {
    /** Maps any throwable from a generated call to an [ApiError]; programming errors are returned unchanged wrapped as [ApiError.Unexpected]. */
    fun map(e: Throwable): ApiError = when (e) {
        is ApiError -> e
        is ClientException -> fromResponse(e.statusCode, e.response as? ClientError<*>)
        is ServerException -> fromServer(e.statusCode, e.response as? ServerError<*>)
        is SessionEndedException -> ApiError.SignedOut(e.details, e.reason)
        is RefreshUnavailableException -> e.error
        is ConnectException, is UnknownHostException, is NoRouteToHostException -> ApiError.Unreachable(e)
        // The JVM says "connect timed out", Android "failed to connect to ... after 5000ms": no connection was made.
        is SocketTimeoutException -> if (e.message.orEmpty().contains("connect", ignoreCase = true)) ApiError.Unreachable(e) else ApiError.Timeout(e)
        is InterruptedIOException -> ApiError.Timeout(e)
        is IOException -> ApiError.Network(e)
        is SerializationException, is UnsupportedOperationException, is IllegalArgumentException ->
            ApiError.Unexpected(null, e)
        else -> ApiError.Unexpected(null, e)
    }

    /**
     * Maps a non-2xx answer read by hand (hand-written JSON calls such as `:sync`'s, where the
     * response must keep members the generated models would drop) to the same [ApiError] the
     * generated client produces. [headers] is the response header map.
     */
    fun fromHttp(status: Int, body: String?, headers: Map<String, List<String>>): ApiError =
        ApiErrorMapper.fromHttp(status, body, headers)

    private fun fromResponse(status: Int, r: ClientError<*>?): ApiError =
        ApiErrorMapper.fromHttp(status, r?.body as? String, r?.headers.orEmpty())

    private fun fromServer(status: Int, r: ServerError<*>?): ApiError =
        ApiErrorMapper.fromHttp(status, r?.body as? String, r?.headers.orEmpty())
}
