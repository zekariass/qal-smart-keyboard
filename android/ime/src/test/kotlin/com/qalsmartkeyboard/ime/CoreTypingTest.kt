package com.qalsmartkeyboard.ime

import android.text.InputType
import java.io.File
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import uniffi.qal_keyboard_core.KeyboardEngine

/**
 * End to end on the JVM: the bundled seed packs (assets) loaded into the real Rust core (host
 * build via JNA), keys from the real layout through [KeyboardController], updates through
 * [InputConnectionAdapter] into a [FakeEditor]. DRAFT data, synthetic text.
 */
class CoreTypingTest {
    companion object {
        private val packDir = File("src/main/assets/packs")
        private lateinit var engine: KeyboardEngine
        private val log = ArrayList<String>()

        @BeforeClass @JvmStatic fun load() {
            val json = File(packDir, BundledPacks.INDEX_FILE).readText()
            fun field(name: String) = Regex("\"$name\"\\s*:\\s*\"([^\"]+)\"").find(json)!!.groupValues[1]
            val files = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toList()
            val index = BundledPacks.Index(field("signingKeyId"), BundledPacks.hexToBytes(field("signingPublicKeyHex")), files)
            engine = BundledPacks.createEngine(index, { File(packDir, it).readBytes() }, { log += it })
        }

        @AfterClass @JvmStatic fun close() = engine.close()
    }

    private class Session(type: Int = InputType.TYPE_CLASS_TEXT) {
        val ed = FakeEditor()
        val adapter = InputConnectionAdapter { ed }
        val c = KeyboardController(adapter) {}

        init {
            adapter.onCompositionAbandoned = { c.onCompositionAbandoned() }
            adapter.startSession(type == InputType.TYPE_NULL, 0, 0)
            c.composer = CoreComposer(engine)
            c.startField(InputProfile.from(type, 0, false, 0))
        }

        fun type(s: String) {
            for (ch in s) {
                val key = c.layout.rows.flatten().first {
                    !it.isSpacer && if (ch == ' ') it.kind == KeyKind.SPACE else it.text == ch.toString()
                }
                c.onKey(key)
                ed.deliver(adapter)
            }
        }

        fun mode(m: TypingMode) {
            while (c.effectiveMode != m) {
                c.onKey(c.layout.rows.flatten().first { it.kind == KeyKind.MODE })
            }
        }
    }

    @Test fun everyBundledPackLoads() {
        assertTrue("rejected: $log", log.isEmpty())
        val modes = CoreComposer(engine).availableModes
        assertEquals(listOf(TypingMode.ABC, TypingMode.AM_PHONETIC, TypingMode.TI_PHONETIC), modes)
        assertTrue(CoreComposer(engine).isDraft(TypingMode.AM_PHONETIC))
    }

    @Test fun selamTypesAsFidel() {
        val s = Session()
        s.mode(TypingMode.AM_PHONETIC)
        s.type("sela")
        assertEquals("ሰላ", s.ed.composing)
        assertTrue(s.c.showCandidateStrip && s.c.candidatesAreDraft)
        assertEquals("ሰላ", s.c.candidates.first().text)
        assertTrue("dictionary completion", s.c.candidates.any { it.text == "ሰላም" && it.kind == SuggestionKind.COMPLETION })
        s.type("m ")
        assertEquals("ሰላም ", s.ed.text.toString())
        assertEquals("", s.ed.composing)
    }

    @Test fun completionTapAndBigramContext() {
        val s = Session()
        s.mode(TypingMode.AM_PHONETIC)
        s.type("sela")
        s.c.selectCandidate(s.c.candidates.indexOfFirst { it.text == "ሰላም" })
        s.ed.deliver(s.adapter)
        s.type(" ne")
        assertEquals("ነው", s.c.candidates.first { it.kind == SuggestionKind.COMPLETION }.text)
        s.type("w ")
        assertEquals("ሰላም ነው ", s.ed.text.toString())
    }

    @Test fun backspaceEditsTheCompositionThenDeletesClusters() {
        val s = Session()
        s.mode(TypingMode.AM_PHONETIC)
        s.type("selam")
        val bs = s.c.layout.rows.flatten().first { it.kind == KeyKind.BACKSPACE }
        s.c.onKey(bs); s.ed.deliver(s.adapter)
        assertEquals("ሰላ", s.ed.composing)
        s.type(" ")
        s.c.onKey(bs); s.ed.deliver(s.adapter) // the space
        s.c.onKey(bs); s.ed.deliver(s.adapter) // one Fidel syllable, not a UTF-16 unit
        assertEquals("ሰ", s.ed.text.toString())
    }

    @Test fun rejectingHostStillGetsFidel() {
        val s = Session()
        s.ed.keepsComposingSpan = false
        s.mode(TypingMode.AM_PHONETIC)
        s.type("selam dehna ")
        assertEquals("ሰላም ደህና ", s.ed.text.toString())
        assertEquals(CompositionMode.DIRECT_COMMIT, s.adapter.mode)
    }

    @Test fun emailFieldIsNeverTransliterated() {
        val s = Session(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        s.type("selam")
        assertEquals("selam", s.ed.text.toString())
        assertTrue(s.c.candidates.isEmpty())
    }

    @Test fun tigrinyaModeUsesItsOwnProfile() {
        val s = Session()
        s.mode(TypingMode.TI_PHONETIC)
        s.type("selam ")
        assertEquals("ሰላም ", s.ed.text.toString())
    }
}
