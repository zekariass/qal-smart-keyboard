package com.qalsmartkeyboard.app.creative.backup

import com.qalsmartkeyboard.api.ApiError
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Maps a failed call to the cause the card shows. [notFound] is what a 404 means for this call: the server answers 404
 * `NOT_FOUND` on EVERY operation when it switched the backup off ([BackupError.FeatureOff]), and also for one unknown item
 * (a download: [BackupError.NotFound]). Only the contract's error codes and extension members are read; no server text is kept.
 */
internal fun ApiError.toBackupError(notFound: BackupError = BackupError.FeatureOff): BackupError = when (this) {
    is ApiError.UpdateRequired -> BackupError.UpdateRequired
    is ApiError.RateLimited -> BackupError.RateLimited(retryAfterSeconds)
    is ApiError.SignedOut -> BackupError.SignedOut
    is ApiError.Timeout -> BackupError.Timeout
    is ApiError.Network -> BackupError.Offline
    is ApiError.Unexpected -> BackupError.Failed
    is ApiError.Http -> {
        fun number(name: String) = (extensions[name] as? JsonPrimitive)?.longOrNull
        when {
            status == 401 -> BackupError.SignedOut
            status == 403 && code == "CONSENT_REQUIRED" -> BackupError.ConsentRequired
            status == 403 && code == "GUEST_NOT_ALLOWED" -> BackupError.GuestNotAllowed
            status == 404 -> notFound
            status == 409 && code == "REVISION_CONFLICT" -> BackupError.RevisionConflict(number("currentRevision"))
            status == 409 && code == "BACKUP_QUOTA_EXCEEDED" ->
                BackupError.QuotaExceeded((extensions["quota"] as? JsonPrimitive)?.contentOrNull, number("limit"))
            status == 413 -> BackupError.TooLarge(number("maxItemBytes"))
            else -> BackupError.Failed
        }
    }
}
