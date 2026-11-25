package com.qalsmartkeyboard.ime.sticker

import java.io.File
import java.io.IOException
import java.security.SecureRandom

/** One sticker of the personal pack. */
data class SavedSticker(val id: String, val kind: StickerKind, val createdMillis: Long)

/** The result of [StickerStore.add]. */
sealed interface AddResult {
    data class Added(val id: String) : AddResult

    /** The personal pack is at [StickerStore.max]. */
    data object Full : AddResult

    /** The sticker could not be produced or written. */
    data object Failed : AddResult
}

/**
 * The personal sticker pack (T8.7): the user's own stickers in the app-private directory [root] (`filesDir/stickers/`),
 * on the device only (R-8), nothing else.
 *
 * - `webp/<id>.webp`: one sticker (512 x 512 WebP). Only `webp/` (and `pack/`, the bundled packs' rendered files) is exposed
 *   through the FileProvider, only by a URI grant given on the user's tap.
 * - `index.tsv`: the index, one line per sticker (`id`, kind letter, created time, tab separated), newest last; it is
 *   rewritten whole through a temporary file. A sticker file the index does not list (or the reverse) is ignored, and a lost
 *   index is rebuilt from the files, so a crash can not leave the pack unusable.
 * - `pack/`: bundled-pack stickers rendered on first use (see [StickerPacks.fileName]); [clearPackCache] removes them.
 *
 * Pure `java.io`, JVM-tested. Bounded: at most [max] stickers. Ids are validated so a name can never leave the directory.
 * Nothing is logged.
 */
class StickerStore(
    private val root: File,
    val max: Int = StickerFormat.MAX_STICKERS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = ::randomId,
) {
    private val webpDir = File(root, "webp")
    private val packDir = File(root, "pack")
    private val indexFile = File(root, "index.tsv")

    /** The personal stickers, newest first. */
    fun list(): List<SavedSticker> = readIndex().sortedWith(compareByDescending<SavedSticker> { it.createdMillis }.thenByDescending { it.id })

    fun count(): Int = readIndex().size

    val isFull: Boolean get() = count() >= max

    /**
     * Makes room for one more sticker and writes it: [write] must produce a non-empty WebP at the file it is given (a
     * temporary file next to its final place). A full pack refuses before anything is produced.
     */
    fun add(kind: StickerKind, write: (File) -> Boolean): AddResult {
        val entries = readIndex()
        if (entries.size >= max) return AddResult.Full
        val id = newId().takeIf(::validId) ?: return AddResult.Failed
        if (entries.any { it.id == id }) return AddResult.Failed
        return try {
            webpDir.mkdirs()
            val target = File(webpDir, id + StickerFormat.EXT)
            val tmp = File(webpDir, id + StickerFormat.EXT + ".tmp")
            val ok = write(tmp) && tmp.isFile && tmp.length() > 0
            if (!ok) {
                tmp.delete()
                return AddResult.Failed
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return AddResult.Failed
            }
            writeIndex(entries + SavedSticker(id, kind, clock()))
            AddResult.Added(id)
        } catch (e: IOException) {
            AddResult.Failed
        }
    }

    fun delete(id: String): Boolean {
        if (!validId(id)) return false
        val entries = readIndex()
        File(webpDir, id + StickerFormat.EXT).delete()
        if (entries.none { it.id == id }) return false
        return try {
            writeIndex(entries.filterNot { it.id == id })
            true
        } catch (e: IOException) {
            false
        }
    }

    /** The WebP of a personal sticker (it may not exist: check [File.isFile]). */
    fun file(id: String): File = File(webpDir, id + StickerFormat.EXT)

    /** Where a bundled pack's sticker is rendered to when first used. */
    fun packFile(pack: StickerPackId, stickerId: String): File = File(packDir, StickerPacks.fileName(pack, stickerId))

    /** Forgets the rendered bundled stickers (they are drawn again on the next use). */
    fun clearPackCache() {
        packDir.listFiles()?.forEach { it.delete() }
    }

    private fun readIndex(): List<SavedSticker> {
        val onDisk = (webpDir.listFiles { f -> f.isFile && f.name.endsWith(StickerFormat.EXT) && f.length() > 0 } ?: emptyArray())
            .mapNotNull { f -> f.name.removeSuffix(StickerFormat.EXT).takeIf(::validId)?.let { it to f.lastModified() } }
            .toMap()
        if (onDisk.isEmpty()) return emptyList()
        val listed = LinkedHashMap<String, SavedSticker>()
        try {
            if (indexFile.isFile) {
                indexFile.readLines(Charsets.UTF_8).forEach { line ->
                    val p = line.split('\t')
                    if (p.size < 3) return@forEach
                    val id = p[0]
                    val created = p[2].toLongOrNull() ?: return@forEach
                    if (validId(id) && id in onDisk && p[1].length == 1) listed[id] = SavedSticker(id, StickerKind.fromCode(p[1][0]), created)
                }
            }
        } catch (e: IOException) {
            // a damaged index is rebuilt from the files below
        }
        // Files the index does not know (a lost or half-written index): kept, by their file time.
        for ((id, modified) in onDisk) if (id !in listed) listed[id] = SavedSticker(id, StickerKind.OTHER, modified)
        return listed.values.toList()
    }

    private fun writeIndex(entries: List<SavedSticker>) {
        root.mkdirs()
        val tmp = File(root, "index.tsv.tmp")
        tmp.writeText(entries.joinToString("") { "${it.id}\t${it.kind.code}\t${it.createdMillis}\n" }, Charsets.UTF_8)
        if (indexFile.exists()) indexFile.delete()
        if (!tmp.renameTo(indexFile)) throw IOException("index not replaced")
    }

    companion object {
        private val ID = Regex("[a-z0-9]{6,32}")
        private val rng = SecureRandom()

        fun validId(id: String) = ID.matches(id)

        fun randomId(): String {
            val b = ByteArray(8)
            rng.nextBytes(b)
            return "s" + b.joinToString("") { "%02x".format(it) }
        }
    }
}
