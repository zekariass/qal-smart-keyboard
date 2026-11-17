package com.qalsmartkeyboard.data.secure

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException

/** Result of [EncryptedFileStore.read]. */
sealed interface StoreRead {
    /** No file yet: a first run, or nothing was ever saved. */
    data object Missing : StoreRead

    class Ok(val bytes: ByteArray) : StoreRead

    /** The file exists but cannot be used; only the [reason] is ever logged, never content. */
    class Corrupt(val reason: CorruptReason) : StoreRead
}

enum class CorruptReason { UNREADABLE, TOO_LARGE, NOT_DECRYPTABLE }

/**
 * Named blobs in one directory, each encrypted with [cipher] and written atomically (A1.8).
 *
 * - The file name is bound into the ciphertext as AAD, so a file copied under another name does
 *   not open.
 * - Writes go to a temporary file that is synced and then renamed over the target, so a crash
 *   leaves either the old or the new file, never half of one. (On Android `rename` replaces
 *   atomically; where it cannot replace an existing file, e.g. Windows JVM tests, the old file is
 *   deleted first.)
 * - Names are restricted to `[a-z0-9_-]{1,32}`.
 */
class EncryptedFileStore(
    private val dir: File,
    private val cipher: BlobCipher,
    private val maxBytes: Long = MAX_BYTES,
) {
    fun read(name: String): StoreRead {
        val f = file(name)
        if (!f.exists()) return StoreRead.Missing
        if (f.length() > maxBytes) return StoreRead.Corrupt(CorruptReason.TOO_LARGE)
        val blob = try {
            f.readBytes()
        } catch (_: IOException) {
            return StoreRead.Corrupt(CorruptReason.UNREADABLE)
        }
        return try {
            StoreRead.Ok(cipher.decrypt(blob, aad(name)))
        } catch (_: GeneralSecurityException) {
            StoreRead.Corrupt(CorruptReason.NOT_DECRYPTABLE)
        } catch (_: RuntimeException) {
            StoreRead.Corrupt(CorruptReason.NOT_DECRYPTABLE)
        }
    }

    /** Throws [IOException] (or a [GeneralSecurityException] from the cipher) if the data could not be saved. */
    fun write(name: String, plain: ByteArray) {
        val target = file(name)
        val blob = cipher.encrypt(plain, aad(name))
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("cannot create directory")
        val tmp = File(dir, target.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(blob)
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw IOException("rename failed")
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    fun delete(name: String) {
        file(name).delete()
    }

    private fun file(name: String): File {
        require(NAME.matches(name)) { "bad store name" }
        return File(dir, "$name.qenc")
    }

    private fun aad(name: String) = "qal.store.v1:$name".toByteArray(Charsets.UTF_8)

    companion object {
        const val MAX_BYTES = 16L * 1024 * 1024
        private val NAME = Regex("[a-z0-9_-]{1,32}")
    }
}
