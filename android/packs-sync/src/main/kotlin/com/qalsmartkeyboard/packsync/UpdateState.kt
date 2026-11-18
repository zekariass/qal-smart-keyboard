package com.qalsmartkeyboard.packsync

import com.qalsmartkeyboard.ime.packs.PackFiles
import java.io.File
import java.io.IOException

/**
 * Small persistent state of the updater under `<packs>/update/`:
 * `revoked-keys.txt` (key ids the server last announced as REVOKED; cached so an offline run still
 * honours them) and `cooldown.txt` (`resourceId<TAB>version<TAB>untilEpochMs`: candidates that
 * failed verification are not fetched again until then). Rolled-back packs are blacklisted for
 * good by the shared `PackJournal`.
 */
class UpdateState(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val revokedFile = File(dir, "revoked-keys.txt")
    private val cooldownFile = File(dir, "cooldown.txt")

    fun revokedKeys(): Set<String> = PackFiles.readLines(revokedFile).filter { it.isNotBlank() }.toSet()

    fun saveRevokedKeys(ids: Set<String>) = write(revokedFile, ids.sorted().joinToString("") { it + "\n" })

    fun inCooldown(id: String, version: String): Boolean = cooldown().any { it.id == id && it.version == version && it.until > clock() }

    fun startCooldown(id: String, version: String, millis: Long) {
        val keep = cooldown().filter { it.until > clock() && !(it.id == id && it.version == version) }
        write(cooldownFile, (keep + Cooldown(id, version, clock() + millis)).joinToString("") { "${it.id}\t${it.version}\t${it.until}\n" })
    }

    private class Cooldown(val id: String, val version: String, val until: Long)

    private fun cooldown(): List<Cooldown> = PackFiles.readLines(cooldownFile).mapNotNull {
        val f = it.split('\t')
        if (f.size == 3) Cooldown(f[0], f[1], f[2].toLongOrNull() ?: return@mapNotNull null) else null
    }

    private fun write(file: File, text: String) {
        try {
            PackFiles.writeAtomic(file, text)
        } catch (_: IOException) {
            // Best effort: losing the cooldown only costs a re-download; the revoked cache is refreshed on the next check.
        }
    }
}
