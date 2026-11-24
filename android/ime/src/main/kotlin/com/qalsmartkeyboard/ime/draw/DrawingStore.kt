package com.qalsmartkeyboard.ime.draw

import java.io.File
import java.io.IOException
import java.security.SecureRandom

/** One entry of the on-device gallery. */
data class SavedDrawing(val id: String, val modifiedMillis: Long)

/**
 * The on-device gallery (T8.4): saved drawings in the app-private directory [root] (`filesDir/drawings/`), nothing else.
 *
 * - `<id>.qdraw`: the editable drawing ([DrawingCodec]).
 * - `png/<id>.png`: its exported image, shared through the FileProvider (only `png/` is exposed). `png/_current.png` is the
 *   image of the unsaved canvas when it is inserted or shared; it is overwritten by the next one.
 *
 * Pure `java.io`, so it is JVM-tested. No cloud, no analytics, nothing logged; ids are validated so a file name can never
 * leave the directory.
 */
class DrawingStore(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = ::randomId,
) {
    private val pngDir = File(root, "png")

    /** The newest [limit] drawings, newest first. */
    fun list(limit: Int = MAX_LISTED): List<SavedDrawing> =
        (root.listFiles { f -> f.isFile && f.name.endsWith(EXT) } ?: emptyArray())
            .mapNotNull { f -> f.name.removeSuffix(EXT).takeIf(::validId)?.let { SavedDrawing(it, f.lastModified()) } }
            .sortedWith(compareByDescending<SavedDrawing> { it.modifiedMillis }.thenByDescending { it.id })
            .take(limit)

    fun count(): Int = (root.listFiles { f -> f.isFile && f.name.endsWith(EXT) } ?: emptyArray()).size

    /** Writes [d] under [id] (a new id when null); returns the id, or null when it could not be written. */
    fun save(id: String?, d: Drawing): String? {
        val useId = id?.takeIf(::validId) ?: newId()
        if (!validId(useId)) return null
        return try {
            root.mkdirs()
            val target = File(root, useId + EXT)
            val tmp = File(root, useId + EXT + ".tmp")
            tmp.writeBytes(DrawingCodec.encode(d))
            tmp.setLastModified(clock())
            if (target.exists()) target.delete()
            if (tmp.renameTo(target)) useId else null
        } catch (e: IOException) {
            null
        }
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

    /** A copy of [id] as a new drawing; returns its id, or null when [id] cannot be read. */
    fun duplicate(id: String): String? {
        val d = (load(id) as? DecodeResult.Ok)?.drawing ?: return null
        return save(null, d)
    }

    fun delete(id: String): Boolean {
        if (!validId(id)) return false
        File(pngDir, "$id.png").delete()
        return File(root, id + EXT).delete()
    }

    /** Where the exported image of [id] lives (it may not exist yet). */
    fun pngFile(id: String): File = File(pngDir, "$id.png")

    /** Where the image of the unsaved canvas goes when it is inserted or shared. */
    fun currentPngFile(): File = File(pngDir, "_current.png")

    companion object {
        const val EXT = ".qdraw"

        /** The gallery shows this many, newest first. */
        const val MAX_LISTED = 60
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
