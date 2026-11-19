package com.qalsmartkeyboard.ime.emoji

import java.io.File
import java.io.IOException

/**
 * [GlyphCache] in one small text file: the first line is [key] (the system build plus the data version), then one
 * unsupported emoji per line. A different key, or a damaged file, means the glyph check runs again. Holds emoji
 * names only; no user data.
 */
class FileGlyphCache(private val file: File, private val key: String) : GlyphCache {
    override fun load(): Set<String>? = try {
        if (!file.isFile) null
        else file.readLines(Charsets.UTF_8).let { lines -> if (lines.firstOrNull() == key) lines.drop(1).filter { it.isNotEmpty() }.toSet() else null }
    } catch (e: IOException) {
        null
    }

    override fun save(unsupported: Set<String>) {
        try {
            file.writeText((listOf(key) + unsupported.sorted()).joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        } catch (e: IOException) {
            // Not cached: the check simply runs again next time.
        }
    }
}
