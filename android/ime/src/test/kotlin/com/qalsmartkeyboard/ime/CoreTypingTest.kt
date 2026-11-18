package com.qalsmartkeyboard.ime

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End to end on the JVM: the bundled seed packs (assets) loaded into the real Rust core (host
 * build via JNA), keys from the real layout through [KeyboardController], updates through
 * [InputConnectionAdapter] into a [FakeEditor]. DRAFT data, synthetic text.
 */
class CoreTypingTest {
    private val engine get() = CoreTestEnv.engine
    private val log get() = CoreTestEnv.log

    private fun Session(type: Int = InputType.TYPE_CLASS_TEXT) = CoreTestEnv.Session(type, prefs = CoreTestEnv.FidelFirst())

    private val AM_PH = TypingMode("am", LayoutKind.PHONETIC)

    @Test fun everyBundledPackLoads() {
        engine // load the packs
        assertTrue("rejected: $log", log.isEmpty())
        val core = CoreComposer(engine)
        for (l in listOf("am", "ti")) {
            assertTrue(core.supports(TypingMode(l, LayoutKind.FIDEL)))
            assertTrue(core.supports(TypingMode(l, LayoutKind.PHONETIC)))
        }
        assertTrue(core.supports(TypingMode("om", LayoutKind.QUBEE)))
        assertTrue(core.supports(TypingMode("en", LayoutKind.ENGLISH)))
        assertTrue(core.isDraft(TypingMode("am", LayoutKind.PHONETIC)))
        assertTrue(core.isDraft(TypingMode("am", LayoutKind.FIDEL)))
    }

    @Test fun selamTypesAsFidel() {
        val s = Session()
        s.mode(AM_PH)
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
        s.mode(AM_PH)
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
        s.mode(AM_PH)
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
        s.mode(AM_PH)
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

    @Test fun digraphsReachShAndKhThroughTheRealKeys() {
        // User report: ሽ and ኽ "missing". Typed key by key through the layout, controller and adapter.
        val s = Session()
        s.mode(AM_PH)
        s.type("sh")
        assertEquals("ሽ", s.ed.composing)
        s.type("iro kh beteseb ")
        assertEquals("ሺሮ ኽ በተሰብ ", s.ed.text.toString())
    }

    @Test fun tigrinyaModeUsesItsOwnProfile() {
        val s = Session()
        s.mode(TypingMode("ti", LayoutKind.PHONETIC))
        s.type("selam ")
        assertEquals("ሰላም ", s.ed.text.toString())
    }
}
