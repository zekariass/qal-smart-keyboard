package com.qalsmartkeyboard.data.secure

import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EncryptedFileStoreTest {
    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("qal-store").toFile() }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun store(key: SecretKey = fixedKey) = EncryptedFileStore(dir, AesGcmCipher { key })

    private val fixedKey = newKey()
    private val secret = "synthetic-test-word-ሰላምታ".toByteArray(Charsets.UTF_8)

    private fun fileOf(name: String) = File(dir, "$name.qenc")

    @Test fun roundTrip() {
        store().write("a", secret)
        val r = store().read("a") as StoreRead.Ok
        assertArrayEquals(secret, r.bytes)
    }

    @Test fun missingFileIsMissing() {
        assertEquals(StoreRead.Missing, store().read("nothing"))
    }

    @Test fun fileDoesNotContainThePlaintext() {
        store().write("a", secret)
        val raw = fileOf("a").readBytes()
        assertEquals(-1, indexOf(raw, secret))
        assertEquals(-1, indexOf(raw, "synthetic".toByteArray()))
    }

    @Test fun twoWritesOfTheSamePlaintextDiffer() {
        store().write("a", secret)
        val first = fileOf("a").readBytes()
        store().write("a", secret)
        assertFalse(first.contentEquals(fileOf("a").readBytes())) // fresh IV each time
    }

    @Test fun overwriteLeavesNoTemporaryFile() {
        store().write("a", secret)
        store().write("a", "second".toByteArray())
        assertEquals(listOf("a.qenc"), dir.list()!!.toList())
        assertArrayEquals("second".toByteArray(), (store().read("a") as StoreRead.Ok).bytes)
    }

    @Test fun wrongKeyIsCorruptNotACrash() {
        store().write("a", secret)
        val r = store(newKey()).read("a")
        assertEquals(CorruptReason.NOT_DECRYPTABLE, (r as StoreRead.Corrupt).reason)
    }

    @Test fun tamperedOrTruncatedFilesAreCorrupt() {
        store().write("a", secret)
        val good = fileOf("a").readBytes()
        val flipped = good.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        fileOf("a").writeBytes(flipped)
        assertTrue(store().read("a") is StoreRead.Corrupt)
        fileOf("a").writeBytes(good.copyOf(5))
        assertTrue(store().read("a") is StoreRead.Corrupt)
        fileOf("a").writeBytes(ByteArray(0))
        assertTrue(store().read("a") is StoreRead.Corrupt)
    }

    @Test fun aFileCopiedUnderAnotherNameDoesNotOpen() {
        store().write("a", secret)
        fileOf("a").copyTo(fileOf("b"))
        assertTrue(store().read("b") is StoreRead.Corrupt)
    }

    @Test fun oversizeFileIsCorrupt() {
        fileOf("a").writeBytes(ByteArray(100))
        val r = EncryptedFileStore(dir, AesGcmCipher { fixedKey }, maxBytes = 50).read("a")
        assertEquals(CorruptReason.TOO_LARGE, (r as StoreRead.Corrupt).reason)
    }

    @Test(expected = IllegalArgumentException::class) fun namesAreRestricted() {
        store().write("../escape", secret)
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
