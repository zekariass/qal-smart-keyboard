package com.qalsmartkeyboard.app.privacy

import com.qalsmartkeyboard.api.ApiError

/**
 * What a failed privacy call means to the person, by cause. Screens map each to one plain
 * sentence; no server `title`, id or detail is ever shown (and none is logged).
 */
sealed interface PrivacyMessage {
    data object Offline : PrivacyMessage
    data object Timeout : PrivacyMessage
    data object SignedOut : PrivacyMessage
    data object UpdateRequired : PrivacyMessage

    /** 429. [retryAfterSeconds] from `Retry-After` when the server sent it. */
    data class RateLimited(val retryAfterSeconds: Long?) : PrivacyMessage

    /** 422 `CONSENT_NOT_REVOCABLE`: terms and privacy notice stay accepted (delete the account instead). */
    data object NotRevocable : PrivacyMessage

    /** 403 `CONSENT_REQUIRED`. */
    data object ConsentRequired : PrivacyMessage

    /** 409 `DELETION_PENDING`: the purge already started or a request is already pending. */
    data object DeletionPending : PrivacyMessage

    data object NotFound : PrivacyMessage
    data object Forbidden : PrivacyMessage
    data object Failed : PrivacyMessage

    /** The archive could not be written to the chosen place (storage full, document gone). */
    data object SaveFailed : PrivacyMessage

    /** The confirmation word was not typed. */
    data object WordMismatch : PrivacyMessage

    /** Retention outside 1..365 days. */
    data object RetentionInvalid : PrivacyMessage
}

internal fun ApiError.toPrivacyMessage(): PrivacyMessage = when (this) {
    is ApiError.UpdateRequired -> PrivacyMessage.UpdateRequired
    is ApiError.RateLimited -> PrivacyMessage.RateLimited(retryAfterSeconds)
    is ApiError.SignedOut -> PrivacyMessage.SignedOut
    is ApiError.Timeout -> PrivacyMessage.Timeout
    is ApiError.Network -> PrivacyMessage.Offline
    is ApiError.Unexpected -> PrivacyMessage.Failed
    is ApiError.Http -> when {
        status == 422 && fieldErrors.any { it.code == "CONSENT_NOT_REVOCABLE" } -> PrivacyMessage.NotRevocable
        status == 403 && code == "CONSENT_REQUIRED" -> PrivacyMessage.ConsentRequired
        status == 409 && code == "DELETION_PENDING" -> PrivacyMessage.DeletionPending
        status == 401 -> PrivacyMessage.SignedOut
        status == 403 -> PrivacyMessage.Forbidden
        status == 404 -> PrivacyMessage.NotFound
        else -> PrivacyMessage.Failed
    }
}
