package com.qalsmartkeyboard.app.creative.backup

import com.qalsmartkeyboard.api.ApiError
import com.qalsmartkeyboard.api.apiCall
import com.qalsmartkeyboard.api.generated.apis.CreativeBackupApi
import com.qalsmartkeyboard.api.generated.models.CreativeBackupItemKind
import com.qalsmartkeyboard.api.generated.models.CreativeBackupItemMetadata
import com.qalsmartkeyboard.api.generated.models.CreativeBackupItemPut
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/**
 * The cloud side of the backup over the generated `CreativeBackupApi` (shared client: bearer, refresh, client headers), kind
 * `DRAWING`. The content travels as standard base64 in JSON, exactly the bytes of the device's `.qdraw` file. Every failure is
 * a [BackupException] (see [toBackupError]); nothing is logged here, and no body, id or byte count leaves this class in a
 * message.
 */
class ApiBackupRemote(private val api: CreativeBackupApi) : BackupRemote {
    override suspend fun summary(): RemoteSummary {
        val s = call { api.getCreativeBackupSummary() }
        return RemoteSummary(s.consentGranted, s.itemCount, s.totalBytes, s.maxItemBytes, s.maxItemsPerKind, s.maxTotalBytes)
    }

    override suspend fun list(): List<RemoteItem> {
        val all = ArrayList<RemoteItem>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = call { api.listCreativeBackupItems(CreativeBackupItemKind.DRAWING, cursor, PAGE_SIZE) }
            page.items.mapTo(all) { it.toRemote() }
            cursor = page.nextCursor
        } while (cursor != null && ++pages < MAX_PAGES)
        return all
    }

    override suspend fun download(id: String): RemoteBlob {
        val item = call(notFound = BackupError.NotFound) { api.getCreativeBackupItem(id) }
        val bytes = item.content.decodeBase64()?.toByteArray() ?: throw BackupException(BackupError.Failed)
        return RemoteBlob(RemoteItem(item.itemId, item.revision, item.sizeBytes, item.updatedAt.toInstant().toEpochMilli()), bytes)
    }

    override suspend fun put(id: String, updatedAt: Long, bytes: ByteArray, expectedRevision: Long?): Long {
        val body = CreativeBackupItemPut(
            kind = CreativeBackupItemKind.DRAWING,
            updatedAt = OffsetDateTime.ofInstant(Instant.ofEpochMilli(updatedAt), ZoneOffset.UTC),
            content = bytes.toByteString().base64(),
            expectedRevision = expectedRevision,
        )
        return call { api.putCreativeBackupItem(id, body) }.revision
    }

    override suspend fun clear() {
        call { api.clearCreativeBackup() }
    }

    private fun CreativeBackupItemMetadata.toRemote() = RemoteItem(itemId, revision, sizeBytes, updatedAt.toInstant().toEpochMilli())

    private suspend fun <T> call(notFound: BackupError = BackupError.FeatureOff, block: () -> T): T = try {
        apiCall(block)
    } catch (e: ApiError) {
        throw BackupException(e.toBackupError(notFound))
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 20
    }
}
