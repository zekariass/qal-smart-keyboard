package com.qalsmartkeyboard.packsync

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import com.qalsmartkeyboard.ime.packs.TrustKey
import uniffi.qal_keyboard_core.KeyStatus

/** A throw-away Ed25519 signing key for tests (JCA), with its [TrustKey] and a pack signer (spec: pack-format-v1). */
class TestSigner(val keyId: String) {
    private val private: PrivateKey
    val trust: TrustKey

    init {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        private = pair.private
        val raw = pair.public.encoded.takeLast(32).toByteArray() // the X.509 prefix is 12 bytes
        trust = TrustKey(keyId, raw.joinToString("") { "%02x".format(it) }, devOnly = false, status = KeyStatus.ACTIVE)
    }

    fun pack(
        language: String,
        type: String,
        version: String,
        artifact: ByteArray,
        platform: String = "SHARED",
        resourceId: String = "11111111-2222-4333-8444-555555555555",
    ): ByteArray {
        val sha = MessageDigest.getInstance("SHA-256").digest(artifact).joinToString("") { "%02x".format(it) }
        val manifest = (
            "{\"resourceId\":\"$resourceId\",\"languageCode\":\"$language\",\"resourceType\":\"$type\"," +
                "\"version\":\"$version\",\"platform\":\"$platform\",\"releaseChannel\":\"INTERNAL\"," +
                "\"sha256\":\"$sha\",\"sizeBytes\":${artifact.size},\"licenseCode\":\"LicenseRef-Qal-Test\"," +
                "\"signatureKeyId\":\"$keyId\"}"
            ).toByteArray(Charsets.UTF_8)
        val sig = Signature.getInstance("Ed25519").run {
            initSign(private)
            update("QAL-PACK-MANIFEST-v1".toByteArray(Charsets.US_ASCII) + 0)
            update(manifest)
            sign()
        }
        check(sig.size == 64)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            write("QALPACK".toByteArray(Charsets.US_ASCII))
            writeByte(0)
            writeShort(1)
            writeByte(keyId.length)
            write(keyId.toByteArray(Charsets.US_ASCII))
            write(sig)
            writeInt(manifest.size)
            write(manifest)
            writeLong(artifact.size.toLong())
            write(artifact)
        }
        return out.toByteArray()
    }
}

object TestPacks {
    /** The artifact (payload) of a container, e.g. a real lexicon from a bundled pack. */
    fun artifactOf(pack: ByteArray): ByteArray {
        val k = pack[10].toInt() and 0xff
        var o = 11 + k + 64
        val m = java.nio.ByteBuffer.wrap(pack, o, 4).int
        o += 4 + m
        val a = java.nio.ByteBuffer.wrap(pack, o, 8).long.toInt()
        return pack.copyOfRange(o + 8, o + 8 + a)
    }

    fun flip(pack: ByteArray, index: Int): ByteArray = pack.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
}
