package com.qalsmartkeyboard.ime.draw

import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** One entry of the on-device gallery. */
data class SavedDrawing(val id: String, val modifiedMillis: Long)

/** The outcome of [DrawingStore.trySave]. */
sealed interface StoreResult {
    class Saved(val id: String) : StoreResult

    /** The gallery holds [DrawingStore.MAX_DRAWINGS] drawings: nothing is deleted behind the user's back; they delete one first. */
    object Full : StoreResult

    object Failed : StoreResult
}

/**
 * The on-device gallery (T8.4, T8.15): saved drawings in the app-private directory [root] (`filesDir/drawings/`), nothing else.
 *
 * - `<id>.qdraw`: the editable drawing ([DrawingCodec]).
 * - `png/<id>.png`: its exported image, shared through the FileProvider (only `png/` is exposed). `png/_current.png` is the
 *   image of the unsaved canvas when it is inserted or shared; it is overwritten by the next one.
 *
 * The gallery holds at most [MAX_DRAWINGS] drawings (a new one is refused at the cap with [StoreResult.Full]; saving over an
 * existing one always works) and shows the newest [MAX_LISTED]. It is also the [DrawingBackupSource] a later optional backup
 * reads. The directory is excluded from Auto Backup and device transfer (the app's backup rules), like the other creative
 * galleries: drawings stay on the device unless the user turns the opt-in backup on.
 *
 * Pure `java.io`, so it is JVM-tested. No cloud, no analytics, nothing logged; ids are validated so a file name can never
 * leave the directory.
 */
class DrawingStore(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = ::randomId,
) : DrawingBackupSource {
    private val pngDir = File(root, "png")
    private val counter = AtomicLong()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private fun files(): Array<File> = root.listFiles { f -> f.isFile && f.name.endsWith(EXT) && validId(f.name.removeSuffix(EXT)) } ?: emptyArray()

    /** The newest [limit] drawings, newest first. */
    fun list(limit: Int = MAX_LISTED): List<SavedDrawing> =
        files()
            .map { f -> SavedDrawing(f.name.removeSuffix(EXT), f.lastModified()) }
            .sortedWith(compareByDescending<SavedDrawing> { it.modifiedMillis }.thenByDescending { it.id })
            .take(limit)

    fun count(): Int = files().size

    /** True when no new drawing can be saved. */
    val full: Boolean get() = count() >= MAX_DRAWINGS

    /** Writes [d] under [id] (a new id when null); returns the id, or null when it could not be written or the gallery is full. */
    fun save(id: String?, d: Drawing): String? = (trySave(id, d) as? StoreResult.Saved)?.id

    /** Like [save], but says why it failed. */
    fun trySave(id: String?, d: Drawing): StoreResult {
        val useId = id?.takeIf(::validId) ?: newId()
        if (!validId(useId)) return StoreResult.Failed
        val target = File(root, useId + EXT)
        val exists = target.isFile
        if (!exists && count() >= MAX_DRAWINGS) return StoreResult.Full
        val before = if (exists) target.lastModified() else 0L
        val created = d.meta[META_CREATED]?.toLongOrNull() ?: (if (exists) before else clock())
        val stamped = d.copy(meta = d.meta + (META_CREATED to created.toString()))
        return if (writeFile(useId, DrawingCodec.encode(stamped), null)) StoreResult.Saved(useId) else StoreResult.Failed
    }

    /** Writes [bytes] to `<id>.qdraw` through a temporary file; the modification time is [at], else strictly after the old one. */
    private fun writeFile(id: String, bytes: ByteArray, at: Long?): Boolean = try {
        root.mkdirs()
        val target = File(root, id + EXT)
        val tmp = File(root, id + EXT + ".tmp")
        val previous = if (target.isFile) target.lastModified() else 0L
        tmp.writeBytes(bytes)
        tmp.setLastModified(at ?: maxOf(clock(), previous + 1))
        if (target.exists()) target.delete()
        val ok = tmp.renameTo(target)
        if (ok) changed() else tmp.delete()
        ok
    } catch (e: IOException) {
        false
    }

    fun load(id: String): DecodeResult {
        if (!validId(id)) return DecodeResult.Corrupt
        val f = File(root, id + EXT)
        if (!f.isFile || f.length() > MAX_FILE_BYTES) return DecodeResult.Corrupt
        return try {
            DrawingCodec.decode(f.readBytes())
        } catch (e: IOException) {
            DecodeResult.Corrupt
        }
    }

    /** A copy of [id] as a new drawing; returns its id, or null when [id] cannot be read or the gallery is full. */
    fun duplicate(id: String): String? = (tryDuplicate(id) as? StoreResult.Saved)?.id

    fun tryDuplicate(id: String): StoreResult {
        val d = (load(id) as? DecodeResult.Ok)?.drawing ?: return StoreResult.Failed
        return trySave(null, d.copy(meta = d.meta - META_CREATED))
    }

    fun delete(id: String): Boolean {
        if (!validId(id)) return false
        File(pngDir, "$id.png").delete()
        val ok = File(root, id + EXT).delete()
        if (ok) changed()
        return ok
    }

    /** Where the exported image of [id] lives (it may not exist yet). */
    fun pngFile(id: String): File = File(pngDir, "$id.png")

    /** Where the image of the unsaved canvas goes when it is inserted or shared. */
    fun currentPngFile(): File = File(pngDir, "_current.png")

    // ---- the backup port -----------------------------------------------------------------------------------------

    override fun listItems(): List<DrawingItem> = files().mapNotNull { f ->
        val id = f.name.removeSuffix(EXT)
        val at = f.lastModified()
        DrawingItem(id, createdAtOf(f, at), at, f.length(), at)
    }.sortedWith(compareByDescending<DrawingItem> { it.updatedAt }.thenByDescending { it.id })

    override fun readRaw(id: String): ByteArray? {
        if (!validId(id)) return null
        val f = File(root, id + EXT)
        if (!f.isFile || f.length() > MAX_FILE_BYTES) return null
        return try {
            f.readBytes()
        } catch (e: IOException) {
            null
        }
    }

    override fun writeRaw(id: String, bytes: ByteArray, updatedAt: Long?): RawWriteResult {
        if (!validId(id)) return RawWriteResult.FAILED
        if (bytes.size > MAX_FILE_BYTES) return RawWriteResult.INVALID
        when (DrawingCodec.decode(bytes)) {
            is DecodeResult.Ok -> Unit
            DecodeResult.Corrupt -> return RawWriteResult.INVALID
            is DecodeResult.Unsupported -> return RawWriteResult.UNSUPPORTED_VERSION
        }
        if (!File(root, id + EXT).isFile && count() >= MAX_DRAWINGS) return RawWriteResult.FULL
        File(pngDir, "$id.png").delete() // the exported image is derived; it is made again when needed
        return if (writeFile(id, bytes, updatedAt)) RawWriteResult.WRITTEN else RawWriteResult.FAILED
    }

    override fun deleteItem(id: String): Boolean = delete(id)

    override val changeCounter: Long get() = counter.get()

    override fun addChangeListener(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners.remove(listener) }
    }

    private fun changed() {
        counter.incrementAndGet()
        for (l in listeners) l()
    }

    /** When [f] was first saved: the stamp in the drawing (saved by this version), else its modification time. */
    private fun createdAtOf(f: File, fallback: Long): Long {
        if (f.length() > MAX_FILE_BYTES) return fallback
        val d = try {
            (DrawingCodec.decode(f.readBytes()) as? DecodeResult.Ok)?.drawing
        } catch (e: IOException) {
            null
        }
        return d?.meta?.get(META_CREATED)?.toLongOrNull() ?: fallback
    }

    companion object {
        const val EXT = ".qdraw"

        /** The gallery shows this many, newest first. */
        const val MAX_LISTED = 60

        /** The gallery holds this many; a new drawing is refused beyond it. */
        const val MAX_DRAWINGS = MAX_LISTED

        /** The drawing's own record of when it was first saved (epoch milliseconds, in its free [Drawing.meta]). */
        const val META_CREATED = "qal.created"
        private const val MAX_FILE_BYTES = 32L * 1024 * 1024
        private val ID = Regex("[a-z0-9]{6,32}")
        private val rng = SecureRandom()

        fun validId(id: String) = ID.matches(id)

        fun randomId(): String {
            val b = ByteArray(8)
            rng.nextBytes(b)
            return "d" + b.joinToString("") { "%02x".format(it) }
        }
    }
}
