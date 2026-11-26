package com.qalsmartkeyboard.app.creative.backup

import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** What the last successful sync of one drawing looked like: the local revision uploaded or restored, and the cloud revision it became. */
data class RecordEntry(val localRevision: Long, val serverRevision: Long)

/**
 * The device's memory of what is in the cloud (T8.15 S3): drawing id -> [RecordEntry], for ONE account ([userId], the
 * access token's `sub`). Ids and numbers only: no content, no names, no timestamps of drawings. A record of another account
 * is never used (see [BackupEngine]).
 */
data class BackupRecord(val userId: String, val items: Map<String, RecordEntry> = emptyMap())

/** The record's JSON: `{"v":1,"user":"...","items":{"<id>":[local,server]}}`. Anything unreadable decodes to null (start over). */
object BackupRecordCodec {
    private const val VERSION = 1

    fun encode(record: BackupRecord): String = Json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("v", VERSION)
            put("user", record.userId)
            put(
                "items",
                buildJsonObject {
                    for ((id, e) in record.items) put(id, buildJsonArray { add(JsonPrimitive(e.localRevision)); add(JsonPrimitive(e.serverRevision)) })
                },
            )
        },
    )

    fun decode(text: String): BackupRecord? = decodeOrNull(text)

    private fun decodeOrNull(text: String): BackupRecord? = try {
        val o = Json.parseToJsonElement(text) as? JsonObject
        val version = (o?.get("v") as? JsonPrimitive)?.longOrNull
        val user = (o?.get("user") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val items = o?.get("items") as? JsonObject
        if (version != VERSION.toLong() || user.isNullOrEmpty() || items == null) {
            null
        } else {
            val map = LinkedHashMap<String, RecordEntry>()
            for ((id, v) in items) {
                val pair = v as? JsonArray ?: return null
                val local = (pair.getOrNull(0) as? JsonPrimitive)?.longOrNull ?: return null
                val server = (pair.getOrNull(1) as? JsonPrimitive)?.longOrNull ?: return null
                map[id] = RecordEntry(local, server)
            }
            BackupRecord(user, map)
        }
    } catch (_: Exception) {
        null
    }
}

/** Where the [BackupRecord] lives. */
interface BackupRecordStore {
    fun load(): BackupRecord?

    /** True when it was stored. */
    fun save(record: BackupRecord): Boolean

    fun clear()
}

/**
 * The record in one small file (the app wires `noBackupFilesDir/creative-backup/record.json`: excluded from Auto Backup and
 * device transfer, so a restored phone starts with no record and compares with the cloud instead). Written through a temporary
 * file and a rename, so a crash leaves the old or the new record, never half of one.
 */
class FileBackupRecordStore(private val file: File) : BackupRecordStore {
    override fun load(): BackupRecord? = try {
        if (file.isFile && file.length() <= MAX_BYTES) BackupRecordCodec.decode(file.readText()) else null
    } catch (_: IOException) {
        null
    }

    override fun save(record: BackupRecord): Boolean = try {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(BackupRecordCodec.encode(record))
        if (!tmp.renameTo(file)) {
            // Some file systems refuse to rename over an existing file.
            file.delete()
            tmp.renameTo(file) || run { tmp.delete(); false }
        } else {
            true
        }
    } catch (_: IOException) {
        false
    }

    override fun clear() {
        file.delete()
    }

    private companion object {
        const val MAX_BYTES = 1L * 1024 * 1024
    }
}
