package com.qalsmartkeyboard.data.secure

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The Android Keystore AES-256 key that encrypts local user data at rest (A1.8). The key never
 * leaves the Keystore, needs no user authentication (the keyboard must work on a locked-to-unlocked
 * device without prompts) and is bound to this app. It is created on first use. If the key is
 * lost (app data restored on another device, Keystore reset) the files it protected cannot be
 * opened; callers treat that as a corrupt file and start empty.
 */
object KeystoreKeys {
    private const val PROVIDER = "AndroidKeyStore"

    /** Returns a [BlobCipher] over the Keystore key [alias]; the key is looked up (or created) lazily and cached. */
    fun aesGcm(alias: String): BlobCipher {
        var cached: SecretKey? = null
        val lock = Any()
        return AesGcmCipher {
            synchronized(lock) {
                cached ?: loadOrCreate(alias).also { cached = it }
            }
        }
    }

    private fun loadOrCreate(alias: String): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }
}
