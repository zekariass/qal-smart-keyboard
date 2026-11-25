package com.qalsmartkeyboard.app.creative.cards

import java.io.File
import java.io.IOException
import java.security.SecureRandom

/** One entry of the personal gallery's cards. */
data class SavedCard(val id: String, val modifiedMillis: Long, val image: File)

sealed interface SaveResult {
    data class Saved(val id: String) : SaveResult

    /** The gallery holds [CardStore.MAX_ITEMS] cards: nothing is deleted behind the user's back; they delete one first. */
    data object Full : SaveResult

    data object Failed : SaveResult
}

/**
 * The personal gallery's card storage (T8.7), behind a small interface so the full gallery management (T8.15) can swap or
 * extend it. Nothing here touches a network; everything stays in the app-private directory.
 */
interface CardStore {
    /** The saved cards, newest first. */
    fun list(): List<SavedCard>

    fun count(): Int

    /**
     * Saves [doc] (a new card when [id] is null, else replaces that card) with its rendered [image] bytes and, when the
     * background is a picked photo, its downscaled copy [photoJpeg] (null keeps the card's existing copy).
     */
    fun save(id: String?, doc: CardDoc, image: ByteArray, photoJpeg: ByteArray?): SaveResult

    /** The editable card [id], or null when it is missing or damaged. */
    fun load(id: String): CardDoc?

    /** The photo background copy of [id], when it has one. */
    fun photoFile(id: String): File?

    fun delete(id: String): Boolean

    companion object {
        /** A gallery holds this many cards. */
        const val MAX_ITEMS = 100
    }
}

/**
 * [CardStore] over a directory ([root], e.g. `noBackupFilesDir/cards/`): `<id>.json` the editable card
 * ([CardCodec]), `<id>.png` or `<id>.jpg` its rendered image, `<id>.bg.jpg` the picked background photo (downscaled
 * copy). The JSON is written last and is what makes a card exist, so an interrupted save leaves no half card. Ids are
 * validated so a file name can never leave the directory. Pure `java.io`.
 */
class FileCardStore(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = ::randomId,
    private val maxItems: Int = CardStore.MAX_ITEMS,
) : CardStore {
    private fun jsonFiles(): List<File> = (root.listFiles { f -> f.isFile && f.name.endsWith(JSON) } ?: emptyArray()).toList()

    override fun count(): Int = jsonFiles().count { validId(it.name.removeSuffix(JSON)) }

    override fun list(): List<SavedCard> = jsonFiles()
        .mapNotNull { f ->
            val id = f.name.removeSuffix(JSON).takeIf(::validId) ?: return@mapNotNull null
            val image = imageFile(id) ?: return@mapNotNull null
            SavedCard(id, f.lastModified(), image)
        }
        .sortedWith(compareByDescending<SavedCard> { it.modifiedMillis }.thenByDescending { it.id })

    override fun save(id: String?, doc: CardDoc, image: ByteArray, photoJpeg: ByteArray?): SaveResult {
        val useId = id?.takeIf(::validId) ?: newId()
        if (!validId(useId)) return SaveResult.Failed
        val exists = File(root, useId + JSON).isFile
        if (!exists && count() >= maxItems) return SaveResult.Full
        return try {
            root.mkdirs()
            val ext = doc.format.extension
            writeAtomic(File(root, "$useId.$ext"), image)
            // The other format's image of an earlier save of this card goes (a card has one image).
            ExportFormat.entries.filter { it.extension != ext }.forEach { File(root, "$useId.${it.extension}").delete() }
            val photoName = "$useId$PHOTO"
            val bg = doc.background
            val stored = when {
                bg is CardBackground.Photo && photoJpeg != null -> {
                    writeAtomic(File(root, photoName), photoJpeg)
                    bg.copy(file = photoName)
                }
                bg is CardBackground.Photo && File(root, photoName).isFile -> bg.copy(file = photoName)
                bg is CardBackground.Photo -> return SaveResult.Failed // a photo card needs its photo
                else -> {
                    File(root, photoName).delete()
                    bg
                }
            }
            val json = File(root, useId + JSON)
            writeAtomic(json, CardCodec.encode(doc.copy(background = stored)).toByteArray(Charsets.UTF_8))
            json.setLastModified(clock())
            SaveResult.Saved(useId)
        } catch (e: IOException) {
            SaveResult.Failed
        }
    }

    override fun load(id: String): CardDoc? {
        if (!validId(id)) return null
        val f = File(root, id + JSON)
        if (!f.isFile || f.length() > MAX_JSON_BYTES) return null
        return try {
            CardCodec.decode(f.readText(Charsets.UTF_8))
        } catch (e: IOException) {
            null
        }
    }

    override fun photoFile(id: String): File? = File(root, id + PHOTO).takeIf { validId(id) && it.isFile }

    override fun delete(id: String): Boolean {
        if (!validId(id)) return false
        ExportFormat.entries.forEach { File(root, "$id.${it.extension}").delete() }
        File(root, id + PHOTO).delete()
        return File(root, id + JSON).delete()
    }

    private fun imageFile(id: String): File? =
        ExportFormat.entries.map { File(root, "$id.${it.extension}") }.firstOrNull { it.isFile }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (target.exists() && !target.delete()) throw IOException("cannot replace ${target.name}")
        if (!tmp.renameTo(target)) throw IOException("cannot write ${target.name}")
    }

    companion object {
        const val JSON = ".json"
        const val PHOTO = ".bg.jpg"
        private const val MAX_JSON_BYTES = 256L * 1024
        private val ID = Regex("[a-z0-9]{6,32}")
        private val rng = SecureRandom()

        fun validId(id: String) = ID.matches(id)

        fun randomId(): String {
            val b = ByteArray(8)
            rng.nextBytes(b)
            return "c" + b.joinToString("") { "%02x".format(it) }
        }
    }
}
