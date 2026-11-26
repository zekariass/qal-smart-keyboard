package com.qalsmartkeyboard.ime.draw

/**
 * One stored drawing as the backup port sees it (T8.15). [revision] differs after every save of the item (today it is the
 * file's modification time, kept strictly increasing by the store), so "revision != the one last uploaded" means dirty;
 * [updatedAt] and [createdAt] are epoch milliseconds, [sizeBytes] the size of the raw `.qdraw` bytes.
 */
data class DrawingItem(val id: String, val createdAt: Long, val updatedAt: Long, val sizeBytes: Long, val revision: Long)

/** The outcome of [DrawingBackupSource.writeRaw]. */
enum class RawWriteResult {
    WRITTEN,

    /** The bytes are not a drawing this build can read (damaged, not a `.qdraw`, or too large): nothing was written. */
    INVALID,

    /** The bytes are a drawing of a newer format version than this build reads: nothing was written. */
    UNSUPPORTED_VERSION,

    /** The gallery holds [DrawingStore.MAX_DRAWINGS] drawings and [id] is a new one: nothing was written. */
    FULL,

    /** The id is not a valid drawing id, or the file could not be written. */
    FAILED,
}

/**
 * The seam a later optional cloud backup of the gallery is written against (T8.15, step S2; no network code lives here and
 * [com.qalsmartkeyboard.ime] must never import the API, AI, sync or pack-sync modules, see `NoNetworkTest`). It exposes the
 * stored drawings as opaque items: their metadata, their raw saved bytes, and a restore write that is validated through
 * [DrawingCodec] before anything touches the disk. Implemented by [DrawingStore]. The raw bytes are the editable
 * `.qdraw` file as it is on the device (the client encrypts them before they leave it); the exported PNGs are derived and
 * are not part of the port.
 *
 * Threading: every method may be called from a background thread; a listener is called on the thread that made the change.
 * Changes made by another process (the companion app deleting a drawing) are not announced: a backup client reads
 * [listItems] when it starts a run and compares the revisions with what it uploaded last.
 */
interface DrawingBackupSource {
    /** Every stored drawing, newest first (not limited to the gallery's visible count). */
    fun listItems(): List<DrawingItem>

    /** The raw saved bytes of [id], or null when there is no such readable drawing. */
    fun readRaw(id: String): ByteArray?

    /**
     * Stores [bytes] as the drawing [id], replacing the one that has that id (a restore). The bytes must decode through
     * [DrawingCodec]; they are stored exactly as given, not re-encoded. [updatedAt] (when given) becomes the item's
     * modification time and revision, else "now".
     */
    fun writeRaw(id: String, bytes: ByteArray, updatedAt: Long? = null): RawWriteResult

    /** Deletes [id] and its exported image; false when there was nothing to delete. */
    fun deleteItem(id: String): Boolean

    /** Grows by one on every save, duplicate, restore write and delete made through this object. */
    val changeCounter: Long

    /** [listener] runs after each change; closing the result removes it. */
    fun addChangeListener(listener: () -> Unit): AutoCloseable
}
