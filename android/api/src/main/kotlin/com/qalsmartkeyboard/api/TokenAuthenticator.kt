package com.qalsmartkeyboard.api

import com.qalsmartkeyboard.api.generated.apis.AuthApi
import com.qalsmartkeyboard.api.generated.infrastructure.ClientError
import com.qalsmartkeyboard.api.generated.infrastructure.ServerError
import com.qalsmartkeyboard.api.generated.infrastructure.Success
import com.qalsmartkeyboard.api.generated.models.AuthSession
import com.qalsmartkeyboard.api.generated.models.RefreshRequest
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException

/** Thrown out of the authenticator when the session was ended; mapped to [ApiError.SignedOut]. */
internal class SessionEndedException(val reason: SignedOutReason, val details: ProblemDetails) :
    IOException("session ended: $reason")

/** Thrown when a refresh could not be completed but the session is intact (offline, 5xx, 429, 426). */
internal class RefreshUnavailableException(val error: ApiError) : IOException("refresh unavailable")

/** Calls `POST /auth/refresh` with the given refresh token. Blocking. */
internal fun interface Refresher {
    fun refresh(refreshToken: String): RefreshOutcome
}

internal sealed interface RefreshOutcome {
    class Renewed(val pair: TokenPair) : RefreshOutcome
    class Rejected(val status: Int, val details: ProblemDetails) : RefreshOutcome
    class Unavailable(val error: ApiError) : RefreshOutcome
}

/** The real refresher: the generated [AuthApi] over a client without authenticator or bearer. */
internal class AuthApiRefresher(private val api: AuthApi) : Refresher {
    override fun refresh(refreshToken: String): RefreshOutcome {
        val response = api.refreshSessionWithHttpInfo(RefreshRequest(refreshToken = refreshToken))
        return when (response) {
            is Success<*> -> {
                val s = response.data as? AuthSession ?: return RefreshOutcome.Unavailable(ApiError.Unexpected(response.statusCode))
                RefreshOutcome.Renewed(TokenPair(s.accessToken, s.refreshToken))
            }
            is ClientError<*> -> {
                val status = response.statusCode
                val body = response.body as? String
                val error = ApiErrorMapper.fromHttp(status, body, response.headers)
                // A rejected refresh token (401) or a blocked account (403) ends the session.
                // Anything else (426 update required, 429, other 4xx) leaves it intact.
                if ((status == 401 || status == 403) && error is ApiError.Http) {
                    RefreshOutcome.Rejected(status, error.details)
                } else {
                    RefreshOutcome.Unavailable(error)
                }
            }
            is ServerError<*> ->
                RefreshOutcome.Unavailable(ApiErrorMapper.fromHttp(response.statusCode, response.body as? String, response.headers))
            else -> RefreshOutcome.Unavailable(ApiError.Unexpected(response.statusCode))
        }
    }
}

/**
 * OkHttp [Authenticator] doing refresh-token rotation (ADR 0006, ADR 0007 D8).
 *
 * On 401 `TOKEN_EXPIRED` of a request that carried a bearer token:
 *  1. take the session lock (refreshes are serialised; concurrent requests wait on it);
 *  2. if the stored access token is no longer the one the failed request used, another request
 *     already refreshed: retry with the stored token and do not refresh again;
 *  3. otherwise call refresh with the stored refresh token, store the new pair atomically
 *     (only the newest refresh token is kept) and retry the request once.
 *
 * The same refresh runs on 401 `UNAUTHENTICATED` with `WWW-Authenticate: Bearer error="invalid_token"` (RFC 6750: the
 * bearer itself was refused, e.g. it was signed with a key the server no longer has after a key rotation or a
 * development server restart, ADR 0035): the refresh token may still be good. A 401 `UNAUTHENTICATED` without
 * `invalid_token` (an endpoint refusing something else, e.g. a Google ID token) is not about the session and is left
 * alone.
 *
 * `REFRESH_TOKEN_REUSED` or `GUEST_SESSION_INVALID` on a request, a 401/403 from the refresh
 * call itself, and an `invalid_token` refusal of the current token on the retry, clear the session and fail the call
 * with [ApiError.SignedOut]: the UI then shows "signed out" instead of a session no call can use. A refresh that
 * cannot complete for transient reasons (offline, 5xx, 429, 426) leaves the session intact and
 * fails the call with the matching typed error. A request is retried at most once.
 */
class TokenAuthenticator internal constructor(
    private val session: SessionManager,
    private val refresher: Refresher,
) : Authenticator {

    constructor(session: SessionManager, refreshApi: AuthApi) : this(session, AuthApiRefresher(refreshApi))

    override fun authenticate(route: Route?, response: Response): Request? {
        val sent = response.request.header("Authorization") ?: return null
        if (!sent.startsWith("Bearer ")) return null

        val body = try {
            response.peekBody(MAX_PROBLEM_BYTES).string()
        } catch (e: IOException) {
            null
        }
        val details = ProblemParser.parse(body, response.code, response.header("X-Correlation-Id"))
        val sentToken = sent.removePrefix("Bearer ")
        val invalidToken = details.code == ProblemCodes.UNAUTHENTICATED &&
            response.headers("WWW-Authenticate").any { it.contains("invalid_token") }

        val ending = when (details.code) {
            ProblemCodes.REFRESH_TOKEN_REUSED -> SignedOutReason.REFRESH_TOKEN_REUSED
            ProblemCodes.GUEST_SESSION_INVALID -> SignedOutReason.GUEST_SESSION_INVALID
            ProblemCodes.TOKEN_EXPIRED -> null
            else -> if (invalidToken) null else return null
        }
        val retried = alreadyRetried(response)

        if (ending != null || retried) {
            synchronized(session.lock) {
                val current = session.tokens()
                if (current != null && current.accessToken != sentToken) {
                    // The refusal is about a token this session no longer holds (a sign-in or a refresh replaced it while
                    // the request was in flight): never end the new session for it.
                    return if (retried) null else withBearer(response.request, current.accessToken)
                }
                if (ending != null) {
                    if (current == null) throw SessionEndedException(signedOutReason() ?: ending, details) // already ended
                    throw end(ending, details)
                }
                // The retry carried the newest token and the server still refuses the token itself: nothing can use
                // this session any more.
                if (invalidToken && current != null) throw end(SignedOutReason.ACCESS_TOKEN_REJECTED, details)
                return null
            }
        }

        synchronized(session.lock) {
            val current = session.tokens()
                ?: throw SessionEndedException(signedOutReason() ?: SignedOutReason.SIGNED_OUT, details)
            if (current.accessToken != sentToken) {
                return withBearer(response.request, current.accessToken)
            }
            when (val outcome = refresher.refresh(current.refreshToken)) {
                is RefreshOutcome.Renewed -> {
                    session.signedIn(outcome.pair)
                    return withBearer(response.request, outcome.pair.accessToken)
                }
                is RefreshOutcome.Rejected -> {
                    val reason = when {
                        outcome.details.code == ProblemCodes.REFRESH_TOKEN_REUSED -> SignedOutReason.REFRESH_TOKEN_REUSED
                        outcome.status == 403 -> SignedOutReason.ACCOUNT_BLOCKED
                        else -> SignedOutReason.REFRESH_REJECTED
                    }
                    throw end(reason, outcome.details)
                }
                is RefreshOutcome.Unavailable -> throw RefreshUnavailableException(outcome.error)
            }
        }
    }

    private fun signedOutReason(): SignedOutReason? = (session.state.value as? SessionState.SignedOut)?.reason

    private fun end(reason: SignedOutReason, details: ProblemDetails): SessionEndedException {
        synchronized(session.lock) { session.signedOut(reason) }
        return SessionEndedException(reason, details)
    }

    private fun alreadyRetried(response: Response): Boolean {
        var prior = response.priorResponse
        while (prior != null) {
            if (prior.code == 401) return true
            prior = prior.priorResponse
        }
        return false
    }

    private fun withBearer(request: Request, accessToken: String): Request =
        request.newBuilder().header("Authorization", "Bearer $accessToken").build()

    private companion object {
        const val MAX_PROBLEM_BYTES = 64L * 1024
    }
}
