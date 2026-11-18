package com.qalsmartkeyboard.api

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Sets `X-Client-Platform`, `X-App-Version` and `X-Client-Surface` on every request, replacing
 * any value the generated call passed (the contract lists them as optional parameters on a few
 * operations; callers leave them unset).
 */
class ClientHeadersInterceptor(private val info: ClientInfo) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(
            chain.request().newBuilder()
                .header("X-Client-Platform", info.platform)
                .header("X-App-Version", info.appVersion)
                .header("X-Client-Surface", info.surface.headerValue)
                .build(),
        )
}

/** Adds `Authorization: Bearer` from the session when the request carries none. */
class BearerInterceptor(private val session: SessionManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Authorization") != null) return chain.proceed(request)
        val tokens = session.tokens() ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer ${tokens.accessToken}").build())
    }
}

/** Receives ids and status only: never URLs, headers, bodies or tokens. */
fun interface CallLogger {
    fun onResult(method: String, status: Int?, correlationId: String?)
}

/**
 * Captures the response `X-Correlation-Id` for logs and support (ids only). The path is left out
 * on purpose: some paths carry tokens (invitation links).
 */
class CorrelationIdInterceptor(private val logger: CallLogger) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val method = chain.request().method
        val response = try {
            chain.proceed(chain.request())
        } catch (e: java.io.IOException) {
            logger.onResult(method, null, null)
            throw e
        }
        logger.onResult(method, response.code, response.header("X-Correlation-Id"))
        return response
    }
}
