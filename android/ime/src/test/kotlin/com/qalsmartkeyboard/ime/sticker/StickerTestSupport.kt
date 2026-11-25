package com.qalsmartkeyboard.ime.sticker

import com.qalsmartkeyboard.ime.share.ImageMimes
import com.qalsmartkeyboard.ime.share.ImageShare
import java.io.File

/** An [ImageShare] that takes any image type and records what was inserted and shared, with its MIME type. */
class FakeStickerShare(
    var acceptsMime: Set<String> = setOf(ImageMimes.WEBP),
    var insertAccepted: Boolean = true,
    var shareAccepted: Boolean = true,
) : ImageShare {
    val log = ArrayList<String>()
    val inserted = ArrayList<Pair<File, String>>()
    val shared = ArrayList<Pair<File, String>>()

    // The original PNG contract: a sticker never takes this path.
    override fun canInsert(): Boolean = canInsert(ImageMimes.PNG)
    override fun insert(png: File, description: String): Boolean = insert(png, ImageMimes.PNG, description)
    override fun share(png: File, title: String): Boolean = share(png, ImageMimes.PNG, title)

    override fun canInsert(mime: String): Boolean = mime in acceptsMime

    override fun insert(file: File, mime: String, description: String): Boolean {
        log += "insert:$mime"
        if (insertAccepted) inserted += file to mime
        return insertAccepted
    }

    override fun share(file: File, mime: String, title: String): Boolean {
        log += "share:$mime"
        if (shareAccepted) shared += file to mime
        return shareAccepted
    }
}

/** Writes a WebP-looking file; records the specs it was asked for. [ok] false = the render fails. */
class FakeStickerExporter(var ok: Boolean = true) : StickerExporter {
    val specs = ArrayList<StickerSpec>()
    val dests = ArrayList<File>()

    override fun exportWebp(spec: StickerSpec, dest: File): Boolean {
        specs += spec
        dests += dest
        if (!ok) return false
        dest.parentFile?.mkdirs()
        dest.writeBytes(WEBP_HEAD)
        return true
    }

    companion object {
        val WEBP_HEAD = byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(), 4, 0, 0, 0, 'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte())
    }
}
