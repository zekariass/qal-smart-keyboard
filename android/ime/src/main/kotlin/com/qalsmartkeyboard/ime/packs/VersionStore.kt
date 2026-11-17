package com.qalsmartkeyboard.ime.packs

import java.io.File
import java.io.IOException

/**
 * The highest pack version ever accepted per (language, resource type, platform), kept in
 * app-private storage so downgrade protection survives restarts (pack format spec: the engine's
 * rollback slots are per instance, hosts must persist installed versions).
 *
 * File format: one `slot<TAB>version` line per entry, written atomically (temp file + rename).
 * A missing or unreadable file means no floor (logged by the caller as `VersionStoreUnreadable`);
 * a corrupt line is ignored. Versions are never secret and never contain user text.
 */
class VersionStore(private val file: File) {
    private val highest = LinkedHashMap<String, SemVer>()
    private val raw = LinkedHashMap<String, String>()

    /** False if the file existed but could not be read. */
    var readable = true
        private set

    init {
        try {
            if (file.exists()) {
                for (line in file.readLines()) {
                    val parts = line.split('\t')
                    if (parts.size != 2) continue
                    val v = SemVer.parse(parts[1]) ?: continue
                    highest[parts[0]] = v
                    raw[parts[0]] = parts[1]
                }
            }
        } catch (_: IOException) {
            readable = false
        }
    }

    fun get(slot: String): SemVer? = highest[slot]

    /** Raises the floor of [slot] to [version] if it is higher. Returns true if it changed. */
    fun raise(slot: String, version: String): Boolean {
        val v = SemVer.parse(version) ?: return false
        val cur = highest[slot]
        if (cur != null && cur >= v) return false
        highest[slot] = v
        raw[slot] = version
        return true
    }

    /** Writes the store atomically. Throws [IOException] if the directory is not writable. */
    fun save() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(raw.entries.joinToString("") { "${it.key}\t${it.value}\n" })
        // rename() replaces atomically on Android (POSIX); the fallback covers hosts where it
        // refuses to overwrite (JVM tests on Windows).
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("cannot replace version store")
        }
    }

    companion object {
        fun slot(language: String, resourceType: String, platform: String) = "$language|$resourceType|$platform"
    }
}
