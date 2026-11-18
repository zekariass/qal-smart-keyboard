package com.qalsmartkeyboard.ime.userdict

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.qalsmartkeyboard.data.secure.AesGcmCipher
import com.qalsmartkeyboard.data.secure.EncryptedFileStore
import com.qalsmartkeyboard.ime.CoreTestEnv
import com.qalsmartkeyboard.ime.LayoutKind
import com.qalsmartkeyboard.ime.SuggestionKind
import com.qalsmartkeyboard.ime.TypingMode
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A1.8 on the JVM: real core (host build), real [EncryptedFileStore] over a temp directory with an
 * injected AES key. "Restart" = a new [UserDictionary] and a new engine over the same directory.
 * Synthetic words only.
 */
class UserDictionaryTest {
    private lateinit var dir: File
    private val key: SecretKey = newKey()
    private val pools = ArrayList<ScheduledExecutorService>()
    private val log = ArrayList<String>()

    private val newWord = "ላላላ" // not in the seed lexicon
    private val newWordKeys = "lalala"

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Before fun setUp() { dir = Files.createTempDirectory("qal-userdict").toFile() }

    @After fun tearDown() {
        pools.forEach { it.shutdownNow() }
        dir.deleteRecursively()
    }

    private fun store(k: SecretKey = key) = EncryptedFileStore(File(dir, "userdict"), AesGcmCipher { k })

    private fun open(debounceMs: Long = 60_000, k: SecretKey = key): UserDictionary {
        val pool = Executors.newSingleThreadScheduledExecutor().also { pools += it }
        return UserDictionary(store(k), pool, debounceMs) { log += it }.also { it.load() }
    }

    private val amPhonetic = TypingMode("am", LayoutKind.PHONETIC)

    private fun file(lang: String = "am") = File(dir, "userdict/userdict-$lang.qenc")

    // ------------------------------------------------------------------ done-when

    @Test fun learnedWordSurvivesARestartAndIsSuggestedAgain() {
        val first = open()
        val engine1 = CoreTestEnv.freshEngine()
        first.attach(engine1)
        val s1 = CoreTestEnv.Session(prefs = CoreTestEnv.FidelFirst(), dictionary = first, engine = engine1)
        s1.mode(amPhonetic)
        s1.type("$newWordKeys ") // the word is finished by the space
        assertEquals("$newWord ", s1.ed.text.toString())
        assertEquals(listOf(newWord), first.words("am"))
        first.flush()

        // New process: new dictionary instance, new engine; nothing in memory survives.
        val second = open()
        val engine2 = CoreTestEnv.freshEngine()
        second.attach(engine2)
        val s2 = CoreTestEnv.Session(prefs = CoreTestEnv.FidelFirst(), dictionary = second, engine = engine2)
        s2.mode(amPhonetic)
        s2.type("lala")
        assertTrue(
            "completion offered: ${s2.c.candidates}",
            s2.c.candidates.any { it.text == newWord && it.kind == SuggestionKind.COMPLETION },
        )
        assertTrue(log.isEmpty())
    }

    @Test fun withoutTheStoredWordItIsNotSuggested() {
        val engine = CoreTestEnv.freshEngine()
        val s = CoreTestEnv.Session(prefs = CoreTestEnv.FidelFirst(), engine = engine)
        s.mode(amPhonetic)
        s.type("lala")
        assertFalse(s.c.candidates.any { it.text == newWord })
    }

    // ------------------------------------------------------------------ editing

    @Test fun addForgetAndClearPersist() {
        val d = open()
        assertTrue(d.add("am", "ሰላምታ"))
        assertTrue(d.add("en", "hello"))
        d.learn("am", "ቤተሰብ", "ሰላምታ")
        assertEquals(listOf("ሰላምታ", "ቤተሰብ").sorted(), d.words("am"))
        assertFalse(d.add("am", "two words"))
        d.flush()

        val again = open()
        assertEquals(listOf("ሰላምታ", "ቤተሰብ").sorted(), again.words("am"))
        assertEquals(listOf("hello"), again.words("en"))
        assertTrue(again.forget("am", "ሰላምታ"))
        assertFalse(again.forget("am", "ሰላምታ"))
        again.flush()
        assertEquals(listOf("ቤተሰብ"), open().words("am"))

        val last = open()
        last.clearAll()
        last.flush()
        val empty = open()
        assertTrue(UserDictionary.LANGUAGES.all { empty.words(it).isEmpty() })
    }

    @Test fun forgetAndClearAlsoReachTheRunningEngine() {
        val d = open()
        val engine = CoreTestEnv.freshEngine()
        d.attach(engine)
        d.add("am", newWord)
        assertTrue(engine.forgetUserWord("am", newWord)) // the engine got the word too
        d.add("am", newWord)
        d.forget("am", newWord)
        assertFalse(engine.forgetUserWord("am", newWord))
        d.add("am", newWord)
        d.clear("am")
        assertFalse(engine.forgetUserWord("am", newWord))
    }

    @Test fun changesBeforeLoadAreIgnored() {
        val pool = Executors.newSingleThreadScheduledExecutor().also { pools += it }
        val d = UserDictionary(store(), pool, 60_000) { log += it }
        assertFalse(d.add("am", newWord))
        assertTrue(d.words("am").isEmpty())
    }

    // ------------------------------------------------------------------ encryption and corruption

    @Test fun theFileDoesNotContainThePlaintextWord() {
        val d = open()
        d.add("am", newWord)
        d.add("en", "synthetic")
        d.flush()
        val raw = file().readBytes()
        assertTrue(raw.isNotEmpty())
        assertEquals(-1, indexOf(raw, newWord.toByteArray(Charsets.UTF_8)))
        assertEquals(-1, indexOf(file("en").readBytes(), "synthetic".toByteArray()))
        assertEquals(-1, indexOf(raw, "QUD1".toByteArray())) // not even the format marker
    }

    @Test fun aWrongKeyStartsEmptyAndLogsOnlyTheReason() {
        open().apply { add("am", newWord); flush() }
        val d = open(k = newKey())
        assertTrue(d.words("am").isEmpty())
        assertTrue(log.any { it.contains("am") })
        assertTrue(log.none { it.contains(newWord) })
    }

    @Test fun garbageFilesStartEmptyAndAreRepairedOnTheNextSave() {
        open().apply { add("am", newWord); flush() }
        file().writeBytes(ByteArray(37) { it.toByte() })
        val d = open()
        assertTrue(d.words("am").isEmpty())
        assertTrue(log.any { it.contains("NOT_DECRYPTABLE") })
        d.add("am", "ሰላም")
        d.flush()
        assertEquals(listOf("ሰላም"), open().words("am"))
    }

    @Test fun anAuthenticFileWithBadContentStartsEmpty() {
        // Valid encryption, but the plaintext is not a user-dictionary container.
        store().write("userdict-am", "not a container".toByteArray())
        val d = open()
        assertTrue(d.words("am").isEmpty())
        assertTrue(log.any { it.contains("INVALID_DATA") })
        assertTrue(log.none { it.contains("container") })
    }

    // ------------------------------------------------------------------ saving

    @Test fun savesInTheBackgroundAfterTheDebounce() {
        val d = open(debounceMs = 50)
        d.add("am", newWord)
        waitFor { file().exists() }
        assertTrue("background save happened", file().exists())
        assertEquals(listOf(newWord), open().words("am"))
    }

    @Test fun noSaveHappensWithoutAChange() {
        open().flush()
        assertFalse(file().exists())
    }

    @Test fun saveSoonWritesPromptly() {
        val d = open(debounceMs = 60_000)
        d.add("am", newWord)
        assertFalse(file().exists()) // debounced
        d.saveSoon()
        waitFor { file().exists() }
        assertTrue(file().exists())
    }

    private fun waitFor(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!cond() && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    // ------------------------------------------------------------------ field rules

    private class Recorder : WordLearningSink {
        val learned = ArrayList<String>()
        val added = ArrayList<String>()
        override fun learn(language: String, word: String, previous: String?) { learned += word }
        override fun add(language: String, word: String): Boolean { added += word; return true }
    }

    @Test fun passwordFieldDoesNotLearn() {
        val r = Recorder()
        val s = CoreTestEnv.Session(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, prefs = CoreTestEnv.FidelFirst(), dictionary = r)
        s.type("hello world ")
        assertTrue(r.learned.isEmpty())
        assertTrue(s.c.candidates.isEmpty())
    }

    @Test fun emailAndUriFieldsDoNotLearn() {
        for (type in listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
        )) {
            val r = Recorder()
            CoreTestEnv.Session(type, dictionary = r).apply { type("hello world ") }
            assertTrue("type $type", r.learned.isEmpty())
        }
    }

    @Test fun noPersonalizedLearningFieldDoesNotLearn() {
        val r = Recorder()
        val s = CoreTestEnv.Session(imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, prefs = CoreTestEnv.FidelFirst(), dictionary = r)
        s.mode(amPhonetic)
        s.type("$newWordKeys ")
        assertEquals("$newWord ", s.ed.text.toString())
        assertTrue(r.learned.isEmpty())
        assertFalse(s.c.addCandidateToDictionary(0))
        assertTrue(r.added.isEmpty())
    }

    @Test fun plainTextFieldLearnsThePhoneticWord() {
        val r = Recorder()
        val s = CoreTestEnv.Session(prefs = CoreTestEnv.FidelFirst(), dictionary = r)
        s.mode(amPhonetic)
        s.type("$newWordKeys ")
        assertEquals(listOf(newWord), r.learned)
    }

    @Test fun latinTypedInAbcIsNotLearnedForAnEthiopicLanguage() {
        val r = Recorder()
        val s = CoreTestEnv.Session(dictionary = r)
        s.mode(TypingMode.abc("am"))
        s.type("hello world ")
        assertTrue(r.learned.isEmpty())
    }

    @Test fun explicitAddFromTheStrip() {
        val r = Recorder()
        val s = CoreTestEnv.Session(prefs = CoreTestEnv.FidelFirst(), dictionary = r)
        s.mode(amPhonetic)
        s.type("lala")
        assertTrue(s.c.addCandidateToDictionary(0))
        assertEquals(listOf("ላላ"), r.added)
        assertFalse(s.c.addCandidateToDictionary(99))
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
