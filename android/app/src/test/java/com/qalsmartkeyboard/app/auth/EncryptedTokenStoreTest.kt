package com.qalsmartkeyboard.app.auth

import com.qalsmartkeyboard.api.SessionManager
import com.qalsmartkeyboard.api.SessionState
import com.qalsmartkeyboard.api.SignedOutReason
import com.qalsmartkeyboard.api.TokenPair
import com.qalsmartkeyboard.data.secure.AesGcmCipher
import com.qalsmartkeyboard.data.secure.EncryptedFileStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** The Keystore token store over an injected key (the Android Keystore itself needs a device). */
class EncryptedTokenStoreTest {
    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("qal-tokens").toFile() }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val key = newKey()

    private fun files(k: SecretKey = key, d: File = dir) = EncryptedFileStore(d, AesGcmCipher { k })
    private fun store(k: SecretKey = key) = EncryptedTokenStore(files(k))
    private val tokenFile get() = File(dir, "tokens.qenc")

    private val pair = TokenPair("access.SECRET-ACCESS-123", "refresh.SECRET-REFRESH-456")

    @Test fun savedPairSurvivesAProcessRestart() {
        store().save(pair)
        val reloaded = store().load() // a new instance reads the file
        assertEquals(pair, reloaded)
    }

    @Test fun nothingSavedMeansSignedOut() {
        assertNull(store().load())
    }

    @Test fun fileHoldsNoPlaintextToken() {
        store().save(pair)
        val raw = String(tokenFile.readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains("SECRET-ACCESS"))
        assertFalse(raw.contains("SECRET-REFRESH"))
    }

    @Test fun newerPairReplacesTheOlderOneAtomically() {
        val s = store()
        s.save(pair)
        val newer = TokenPair("a2", "r2")
        s.save(newer)
        assertEquals(newer, store().load())
        assertEquals(listOf("tokens.qenc"), dir.list()!!.toList()) // no temporary file left behind
    }

    @Test fun clearWipesMemoryAndDisk() {
        val s = store()
        s.save(pair)
        s.clear()
        assertNull(s.load())
        assertFalse(tokenFile.exists())
        assertNull(store().load())
    }

    @Test fun wrongKeyReadsAsSignedOutAndRemovesTheFile() {
        store().save(pair)
        assertNull(store(newKey()).load()) // key lost (restore on another device, Keystore reset)
        assertFalse(tokenFile.exists())
    }

    @Test fun tamperedFileReadsAsSignedOut() {
        store().save(pair)
        val bytes = tokenFile.readBytes()
        bytes[bytes.size - 3] = (bytes[bytes.size - 3].toInt() xor 0x55).toByte()
        tokenFile.writeBytes(bytes)
        assertNull(store().load())
        assertFalse(tokenFile.exists())
    }

    @Test fun fileCopiedUnderAnotherNameDoesNotOpen() {
        store().save(pair)
        tokenFile.copyTo(File(dir, "other.qenc"))
        assertNull(EncryptedTokenStore(files(), name = "other").load())
    }

    @Test fun tokensWithAnyCharactersRoundTrip() {
        val odd = TokenPair("a\nb\n3:x", "r\r\n\u0000ሰላም")
        store().save(odd)
        assertEquals(odd, store().load())
    }

    @Test fun failedWriteKeepsTheSessionInMemoryButLeavesNoStaleFile() {
        store().save(pair)
        // The directory becomes a regular file: the next write cannot succeed.
        val broken = File(dir.parentFile, dir.name + "-file").apply { writeText("x") }
        try {
            val s = EncryptedTokenStore(files(d = broken))
            s.save(TokenPair("a3", "r3"))
            assertEquals(TokenPair("a3", "r3"), s.load()) // usable in this process
            assertNull(EncryptedTokenStore(files(d = broken)).load()) // but nothing persisted
        } finally {
            broken.delete()
        }
        assertNotNull(store().load()) // the original directory is untouched
    }

    @Test fun toStringNeverShowsTokens() {
        assertFalse(pair.toString().contains("SECRET"))
        assertTrue(pair.toString().contains("redacted"))
    }

    @Test fun sessionManagerOverTheStoreSignsInAndOut() {
        val first = SessionManager(store())
        assertEquals(SessionState.SignedOut(null), first.state.value)
        first.signedIn(pair)
        // A new process: the session comes back signed in.
        val second = SessionManager(store())
        assertEquals(SessionState.SignedIn, second.state.value)
        assertEquals(pair, second.tokens())
        second.signedOut(SignedOutReason.REFRESH_TOKEN_REUSED)
        assertFalse(tokenFile.exists()) // wiped
        assertEquals(SessionState.SignedOut(SignedOutReason.REFRESH_TOKEN_REUSED), second.state.value)
        assertEquals(SessionState.SignedOut(null), SessionManager(store()).state.value)
    }
}
