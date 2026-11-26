package com.qalsmartkeyboard.app.creative.backup

/**
 * The ports and value types of the opt-in cloud backup of the Draw gallery (T8.15 S3, ADR 0050). Plain Kotlin on purpose: the
 * engine ([BackupEngine]) is written against these, so it runs on the JVM with fakes. Nothing here carries drawing content in
 * a message or a log line; failures are [BackupError]s, never the server's text.
 */

/** One cloud item's metadata (`CreativeBackupItemMetadata`): never content. [updatedAt] is the app's last-modified time, epoch ms. */
data class RemoteItem(val id: String, val revision: Long, val sizeBytes: Long, val updatedAt: Long)

/** One cloud item downloaded with its bytes (`CreativeBackupItem`). */
class RemoteBlob(val item: RemoteItem, val bytes: ByteArray)

/** `CreativeBackupSummary`: usage against the server's caps, and whether the consent is granted. */
data class RemoteSummary(
    val consentGranted: Boolean,
    val itemCount: Int,
    val totalBytes: Long,
    val maxItemBytes: Long,
    val maxItemsPerKind: Int,
    val maxTotalBytes: Long,
)

/** Why a cloud call failed, by cause. The screens map each to one plain sentence. */
sealed interface BackupError {
    /** No connection to the server (offline, refused, DNS). */
    data object Offline : BackupError

    data object Timeout : BackupError

    /** The session ended (401, signed out). */
    data object SignedOut : BackupError

    /** A guest account: the backup needs a registered account (403 `GUEST_NOT_ALLOWED`). */
    data object GuestNotAllowed : BackupError

    /** 403 `CONSENT_REQUIRED`: the `CREATIVE_BACKUP` consent is not granted. */
    data object ConsentRequired : BackupError

    /** 404 `NOT_FOUND`: the server switched the backup off. */
    data object FeatureOff : BackupError

    data object UpdateRequired : BackupError

    /** 429; [retryAfterSeconds] from `Retry-After` when the server sent it. */
    data class RateLimited(val retryAfterSeconds: Long?) : BackupError

    /** 409 `BACKUP_QUOTA_EXCEEDED`: [quota] is `ITEMS_PER_KIND` or `TOTAL_BYTES`, [limit] the cap (a count or bytes). */
    data class QuotaExceeded(val quota: String?, val limit: Long?) : BackupError

    /** 413 `BACKUP_ITEM_TOO_LARGE`: this one item can never fit ([maxItemBytes], decoded bytes). */
    data class TooLarge(val maxItemBytes: Long?) : BackupError

    /** 409 `REVISION_CONFLICT`: the cloud copy changed since it was read ([currentRevision], 0 when it does not exist). */
    data class RevisionConflict(val currentRevision: Long?) : BackupError

    /** 404 for one item (it vanished between the listing and the call). */
    data object NotFound : BackupError

    /** Anything else (a 5xx, an unreadable answer, bad base64). */
    data object Failed : BackupError

    /** Errors that make the rest of a run pointless: the run stops there and reports what is left. */
    val endsRun: Boolean
        get() = when (this) {
            is TooLarge, NotFound, Failed, is RevisionConflict -> false
            else -> true
        }
}

class BackupException(val error: BackupError) : RuntimeException("backup failed: ${error::class.simpleName}")

/**
 * The cloud side of the backup (`CreativeBackupApi`, kind `DRAWING`). Every failure is a [BackupException]. The real adapter
 * is [ApiBackupRemote]; the engine's tests use a fake.
 */
interface BackupRemote {
    suspend fun summary(): RemoteSummary

    /** Every item of kind `DRAWING` (all pages). */
    suspend fun list(): List<RemoteItem>

    suspend fun download(id: String): RemoteBlob

    /**
     * Creates or replaces [id]. [expectedRevision] is the compare-and-set of the contract (0 = must not exist, null =
     * overwrite). Returns the revision the server holds afterwards. An identical repeat is a no-op on the server.
     */
    suspend fun put(id: String, updatedAt: Long, bytes: ByteArray, expectedRevision: Long?): Long

    /** `DELETE /me/creative-backup`: every cloud item (idempotent). */
    suspend fun clear()
}
