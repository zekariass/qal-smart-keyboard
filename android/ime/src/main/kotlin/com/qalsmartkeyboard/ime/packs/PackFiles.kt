package com.qalsmartkeyboard.ime.packs

import java.io.File
import java.io.IOException

/** File helpers shared by the IME-side pack journal and the app-side updater (no network here). */
object PackFiles {
    /**
     * Moves [src] over [dst]. `rename()` replaces atomically on Android (POSIX); the delete-then-rename
     * fallback only runs on hosts that refuse to overwrite (JVM tests on Windows). Throws [IOException].
     */
    fun replace(src: File, dst: File) {
        if (src.renameTo(dst)) return
        if (dst.exists() && !dst.delete()) throw IOException("cannot replace ${dst.name}")
        if (!src.renameTo(dst)) throw IOException("cannot rename ${src.name}")
    }

    /** Writes [text] to [file] through a temp file in the same directory and a rename. */
    fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        replace(tmp, file)
    }

    fun readLines(file: File): List<String> = try {
        if (file.exists()) file.readLines() else emptyList()
    } catch (_: IOException) {
        emptyList()
    }
}
