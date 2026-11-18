package com.qalsmartkeyboard.app.auth

import com.qalsmartkeyboard.api.TokenPair
import com.qalsmartkeyboard.api.TokenStore
import com.qalsmartkeyboard.data.secure.EncryptedFileStore
import com.qalsmartkeyboard.data.secure.StoreRead
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Keystore-backed [TokenStore] (A2.2). The access/refresh pair is one blob in an
 * [EncryptedFileStore] (AES-256-GCM, non-exportable Android Keystore key in production, an
 * injected key in JVM tests), written atomically, so a crash leaves the old or the new pair and a
 * reader never sees halves of two rotations.
 *
 * - A copy is kept in memory: OkHttp threads read the tokens on every request and must not hit
 *   the disk or the Keystore each time.
 * - A missing, tampered or undecryptable file (key lost after a restore or a Keystore reset) reads
 *   as "signed out" and the file is deleted.
 * - If a write fails the pair stays usable in memory for this process, and the stale file is
 *   deleted: a restart then signs out instead of resurrecting an already rotated (revoked)
 *   refresh token.
 * - Tokens are never logged; nothing here has a logger.
 */
class EncryptedTokenStore(
    private val files: EncryptedFileStore,
    private val name: String = NAME,
) : TokenStore {
    private val lock = Any()
    private var loaded = false
    private var cache: TokenPair? = null

    override fun load(): TokenPair? = synchronized(lock) {
        if (!loaded) {
            cache = readFromDisk()
            loaded = true
        }
        cache
    }

    override fun save(pair: TokenPair) = synchronized(lock) {
        cache = pair
        loaded = true
        try {
            files.write(name, encode(pair))
        } catch (_: IOException) {
            files.delete(name)
        } catch (_: GeneralSecurityException) {
            files.delete(name)
        } catch (_: RuntimeException) {
            files.delete(name)
        }
    }

    /** Wipes the tokens from memory and disk (sign-out, ended session). */
    override fun clear() = synchronized(lock) {
        cache = null
        loaded = true
        files.delete(name)
    }

    private fun readFromDisk(): TokenPair? = when (val r = files.read(name)) {
        StoreRead.Missing -> null
        is StoreRead.Corrupt -> {
            files.delete(name)
            null
        }
        is StoreRead.Ok -> decode(r.bytes) ?: run {
            files.delete(name)
            null
        }
    }

    companion object {
        const val NAME = "tokens"

        /** `v1\n<accessLength>\n<access><refresh>`: length-prefixed, so any token characters are safe. */
        internal fun encode(pair: TokenPair): ByteArray =
            "v1\n${pair.accessToken.length}\n${pair.accessToken}${pair.refreshToken}".toByteArray(Charsets.UTF_8)

        internal fun decode(bytes: ByteArray): TokenPair? {
            val text = String(bytes, Charsets.UTF_8)
            val first = text.indexOf('\n')
            if (first < 0 || text.substring(0, first) != "v1") return null
            val second = text.indexOf('\n', first + 1)
            if (second < 0) return null
            val accessLength = text.substring(first + 1, second).toIntOrNull() ?: return null
            val body = text.substring(second + 1)
            if (accessLength <= 0 || accessLength >= body.length) return null
            return TokenPair(body.substring(0, accessLength), body.substring(accessLength))
        }
    }
}
