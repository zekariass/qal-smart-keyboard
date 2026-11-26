package com.qalsmartkeyboard.app.creative.backup

import com.qalsmartkeyboard.ime.draw.DrawingBackupSource
import com.qalsmartkeyboard.ime.draw.DrawingItem
import com.qalsmartkeyboard.ime.draw.RawWriteResult

/** The device's gallery in memory. [cap] mirrors the store's 60-drawing cap; [revisionOf] makes restore write a revision of its own. */
class FakeLocal(var cap: Int = 60) : DrawingBackupSource {
    class Entry(var updatedAt: Long, var bytes: ByteArray, var revision: Long)

    val items = LinkedHashMap<String, Entry>()
    var counter = 0L
    private var nextRevision = 1_000L
    val writes = mutableListOf<String>()
    val deletes = mutableListOf<String>()
    val unreadable = mutableSetOf<String>()

    /** What [writeRaw] answers for an id (default WRITTEN). */
    val writeResults = mutableMapOf<String, RawWriteResult>()

    /** A save made on the phone (the keyboard): a new revision every time. */
    fun save(id: String, updatedAt: Long, bytes: ByteArray = byteArrayOf(id.hashCode().toByte(), updatedAt.toByte(), 1)) {
        items[id] = Entry(updatedAt, bytes, nextRevision++)
    }

    override fun listItems(): List<DrawingItem> =
        items.map { (id, e) -> DrawingItem(id, e.updatedAt, e.updatedAt, e.bytes.size.toLong(), e.revision) }
            .sortedWith(compareByDescending<DrawingItem> { it.updatedAt }.thenByDescending { it.id })

    override fun readRaw(id: String): ByteArray? = if (id in unreadable) null else items[id]?.bytes

    override fun writeRaw(id: String, bytes: ByteArray, updatedAt: Long?): RawWriteResult {
        writeResults[id]?.let { if (it != RawWriteResult.WRITTEN) return it }
        if (id !in items && items.size >= cap) return RawWriteResult.FULL
        // Like the real store, restore assigns a revision of its own (not the cloud's, not the updatedAt).
        items[id] = Entry(updatedAt ?: 0L, bytes, nextRevision++)
        writes += id
        counter++
        return RawWriteResult.WRITTEN
    }

    override fun deleteItem(id: String): Boolean {
        deletes += id
        return items.remove(id) != null
    }

    override val changeCounter: Long get() = counter

    override fun addChangeListener(listener: () -> Unit): AutoCloseable = AutoCloseable { }
}

/** The cloud in memory, behaving like the server: revisions, compare-and-set, idempotent identical PUT, caps. */
class FakeRemote : BackupRemote {
    class Stored(var revision: Long, var updatedAt: Long, var bytes: ByteArray)

    val items = LinkedHashMap<String, Stored>()
    var consentGranted = true
    var maxItemBytes = 2L * 1024 * 1024
    var maxItems = 100
    var maxTotalBytes = 32L * 1024 * 1024

    /** Failures by operation: take effect on the next call of it (id is empty for summary, list and clear). */
    var onList: () -> Unit = {}
    var onSummary: () -> Unit = {}
    var onDownload: (String) -> Unit = {}
    var beforePut: (String) -> Unit = {}
    var onClear: () -> Unit = {}

    data class Put(val id: String, val updatedAt: Long, val size: Int, val expected: Long?)

    val puts = mutableListOf<Put>()
    val downloads = mutableListOf<String>()
    var lists = 0
    var clears = 0

    override suspend fun summary(): RemoteSummary {
        onSummary()
        return RemoteSummary(consentGranted, items.size, items.values.sumOf { it.bytes.size.toLong() }, maxItemBytes, maxItems, maxTotalBytes)
    }

    override suspend fun list(): List<RemoteItem> {
        lists++
        onList()
        return items.map { (id, s) -> RemoteItem(id, s.revision, s.bytes.size.toLong(), s.updatedAt) }.sortedBy { it.id }
    }

    override suspend fun download(id: String): RemoteBlob {
        downloads += id
        onDownload(id)
        val s = items[id] ?: throw BackupException(BackupError.NotFound)
        return RemoteBlob(RemoteItem(id, s.revision, s.bytes.size.toLong(), s.updatedAt), s.bytes)
    }

    override suspend fun put(id: String, updatedAt: Long, bytes: ByteArray, expectedRevision: Long?): Long {
        puts += Put(id, updatedAt, bytes.size, expectedRevision)
        beforePut(id)
        if (bytes.size > maxItemBytes) throw BackupException(BackupError.TooLarge(maxItemBytes))
        val current = items[id]
        if (current != null && current.updatedAt == updatedAt && current.bytes.contentEquals(bytes)) return current.revision // idempotent
        if (expectedRevision != null && expectedRevision != (current?.revision ?: 0L)) {
            throw BackupException(BackupError.RevisionConflict(current?.revision ?: 0L))
        }
        if (current == null && items.size >= maxItems) throw BackupException(BackupError.QuotaExceeded("ITEMS_PER_KIND", maxItems.toLong()))
        val total = items.filterKeys { it != id }.values.sumOf { it.bytes.size.toLong() } + bytes.size
        if (total > maxTotalBytes) throw BackupException(BackupError.QuotaExceeded("TOTAL_BYTES", maxTotalBytes))
        if (current == null) {
            items[id] = Stored(1, updatedAt, bytes)
            return 1
        }
        current.revision++
        current.updatedAt = updatedAt
        current.bytes = bytes
        return current.revision
    }

    override suspend fun clear() {
        clears++
        onClear()
        items.clear()
    }

    /** Another device's upload. */
    fun otherDevice(id: String, updatedAt: Long, bytes: ByteArray = byteArrayOf(9, 9, 9, updatedAt.toByte())) {
        val s = items[id]
        if (s == null) {
            items[id] = Stored(1, updatedAt, bytes)
        } else {
            s.revision++
            s.updatedAt = updatedAt
            s.bytes = bytes
        }
    }
}

class MemoryRecords : BackupRecordStore {
    var record: BackupRecord? = null
    var saves = 0

    override fun load(): BackupRecord? = record

    override fun save(record: BackupRecord): Boolean {
        this.record = record
        saves++
        return true
    }

    override fun clear() {
        record = null
    }
}

fun failing(error: BackupError): Nothing = throw BackupException(error)
