package com.qalsmartkeyboard.app.creative.annotate

import com.qalsmartkeyboard.app.creative.cards.ExportFormat
import com.qalsmartkeyboard.app.creative.cards.FileCardStore
import com.qalsmartkeyboard.app.creative.cards.SaveResult
import java.io.File
import java.io.IOException

/** One annotated picture of the personal gallery: a flattened image, not editable. */
data class SavedAnnotation(val id: String, val modifiedMillis: Long, val image: File) {
    val format: ExportFormat? get() = ExportFormat.entries.firstOrNull { image.name.endsWith("." + it.extension) }
}

/**
 * The personal gallery's annotated pictures (T8.7), behind a small interface so the full gallery management (T8.15)
 * can swap or extend it. A saved picture is the flattened export only (redactions burnt into the pixels, crop
 * applied); there is no editable source next to it, so a saved or shared picture can never be "un-redacted".
 */
interface AnnotationStore {
    /** Newest first. */
    fun list(): List<SavedAnnotation>

    fun count(): Int

    fun save(image: ByteArray, format: ExportFormat): SaveResult

    fun delete(id: String): Boolean

    companion object {
        const val MAX_ITEMS = 100
    }
}

/**
 * [AnnotationStore] over a directory ([root], e.g. `noBackupFilesDir/annotations/`): `<id>.png` or `<id>.jpg`. Written
 * through a temporary file and renamed, so an interrupted save leaves no half picture. Ids are validated so a file name
 * can never leave the directory. Pure `java.io`, no network.
 */
class FileAnnotationStore(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = ::randomId,
    private val maxItems: Int = AnnotationStore.MAX_ITEMS,
) : AnnotationStore {
    private fun images(): List<Pair<String, File>> = (root.listFiles { f -> f.isFile } ?: emptyArray()).mapNotNull { f ->
        val ext = ExportFormat.entries.firstOrNull { f.name.endsWith("." + it.extension) } ?: return@mapNotNull null
        val id = f.name.removeSuffix("." + ext.extension)
        if (FileCardStore.validId(id)) id to f else null
    }

    override fun count(): Int = images().size

    override fun list(): List<SavedAnnotation> = images()
        .map { (id, f) -> SavedAnnotation(id, f.lastModified(), f) }
        .sortedWith(compareByDescending<SavedAnnotation> { it.modifiedMillis }.thenByDescending { it.id })

    override fun save(image: ByteArray, format: ExportFormat): SaveResult {
        if (count() >= maxItems) return SaveResult.Full
        val id = newId()
        if (!FileCardStore.validId(id)) return SaveResult.Failed
        return try {
            root.mkdirs()
            val target = File(root, "$id.${format.extension}")
            val tmp = File(root, target.name + ".tmp")
            tmp.writeBytes(image)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IOException("cannot write")
            }
            target.setLastModified(clock())
            SaveResult.Saved(id)
        } catch (e: IOException) {
            SaveResult.Failed
        }
    }

    override fun delete(id: String): Boolean {
        if (!FileCardStore.validId(id)) return false
        var any = false
        ExportFormat.entries.forEach { if (File(root, "$id.${it.extension}").delete()) any = true }
        return any
    }

    companion object {
        fun randomId(): String = "a" + FileCardStore.randomId().drop(1)
    }
}

/**
 * The one editable draft (T8.7): a small JSON file in the app's no-backup storage ([file], e.g.
 * `noBackupFilesDir/annotator/draft.json`) with the objects and a reference to the picked picture. It never holds
 * pixels: the unredacted picture is never written anywhere by the app. [delete] is the user's "Discard draft".
 */
class AnnotationDraftStore(private val file: File) {
    fun exists(): Boolean = file.isFile

    fun load(): AnnotDraft? {
        if (!file.isFile || file.length() > MAX_BYTES) return null
        return try {
            AnnotationCodec.decode(file.readText(Charsets.UTF_8))
        } catch (e: IOException) {
            null
        }
    }

    fun save(d: AnnotDraft): Boolean = try {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(AnnotationCodec.encode(d), Charsets.UTF_8)
        if (file.exists() && !file.delete()) throw IOException("cannot replace")
        tmp.renameTo(file)
    } catch (e: IOException) {
        false
    }

    fun delete(): Boolean {
        File(file.parentFile, file.name + ".tmp").delete()
        return file.delete() || !file.exists()
    }

    companion object {
        /** A draft is at most this big (400 objects of 4000 points fit comfortably). */
        const val MAX_BYTES = 8L * 1024 * 1024
    }
}
