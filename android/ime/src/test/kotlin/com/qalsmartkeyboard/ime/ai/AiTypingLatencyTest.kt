package com.qalsmartkeyboard.ime.ai

import com.qalsmartkeyboard.ime.CoreComposer
import com.qalsmartkeyboard.ime.CoreTestEnv
import com.qalsmartkeyboard.ime.FakeEditor
import com.qalsmartkeyboard.ime.InMemoryModePreferences
import com.qalsmartkeyboard.ime.InputConnectionAdapter
import com.qalsmartkeyboard.ime.InputProfile
import com.qalsmartkeyboard.ime.Key
import com.qalsmartkeyboard.ime.KeyKind
import com.qalsmartkeyboard.ime.KeyboardController
import com.qalsmartkeyboard.ime.DefaultEnabledLanguages
import com.qalsmartkeyboard.ime.userdict.WordLearningSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A2.5 done-when: typing latency is unaffected by the AI toolbar, and typing never reaches the network.
 *
 * The toolbar is not on the key path by construction: [KeyboardController] knows nothing of it, the
 * service repaints the toolbar from [AiToolbarController]'s own change callback (taps and answers),
 * and the controller reads no editor state on keys. This test measures it anyway: the same key
 * stream through the real [KeyboardController] and [InputConnectionAdapter] over a fake editor,
 * with the toolbar (a controller on a recording fake [AiActions], a request in flight, and a view
 * refresh that reads the toolbar state on every key, the worst case) wired versus not wired.
 *
 * Budget (SD 17.1): key-down to glyph or composing update, p95 <= 30 ms mid-range (<= 50 ms
 * low-end). The JVM figure covers the controller, adapter and (second test) the real Rust core
 * only, so it is far below the budget; what matters is that wired is not slower than unwired.
 */
class AiTypingLatencyTest {
    private class Rig(val wired: Boolean, val real: Boolean) {
        val ed = FakeEditor()
        val adapter = InputConnectionAdapter { ed }
        val actions = FakeAiActions()
        var painted = 0

        private var toolbar: AiToolbarController? = null

        val c = KeyboardController(adapter, DefaultEnabledLanguages, InMemoryModePreferences(), WordLearningSink.None) {
            // What the service's refreshViews would do for the toolbar if it ran per key (it does not): read its state.
            toolbar?.let { if (it.visible && it.panel !is AiPanel.Closed) painted++ }
        }

        init {
            adapter.onCompositionAbandoned = { c.onCompositionAbandoned() }
            adapter.startSession(false, 0, 0)
            if (real) c.composer = CoreComposer(CoreTestEnv.engine)
            val profile = InputProfile.DEFAULT
            c.startField(profile)
            if (wired) {
                toolbar = AiToolbarController(actions, EditorAiAccess(adapter) { c.commitComposition() }, { it() }, {}).also {
                    it.onFieldStarted(profile, "com.example")
                    it.onKeyboardShown()
                    ed.commitText("Hello world")
                    it.onTask(AiTaskKind.TRANSLATE) // a request stays in flight while the user types
                }
                ed.text.setLength(0)
                ed.selStart = 0
                ed.selEnd = 0
                adapter.startSession(false, 0, 0)
            }
        }

        private val keys: List<Key> = c.layout.rows.flatten().filter { it.kind == KeyKind.CHAR && it.isLetter }
        private val space: Key = c.layout.rows.flatten().first { it.kind == KeyKind.SPACE }

        /** Words of five letters and a space, like typing. */
        fun typeOne(i: Int) {
            c.onKey(if (i % 6 == 5) space else keys[i % keys.size])
            ed.deliver(adapter)
        }
    }

    private class Stats(val p50: Double, val p95: Double, val p99: Double, val mean: Double) {
        override fun toString() = "p50=%.4f ms p95=%.4f ms p99=%.4f ms mean=%.4f ms".format(p50, p95, p99, mean)
    }

    private fun measure(rig: Rig, n: Int): Stats {
        val t = LongArray(n)
        for (i in 0 until n) {
            val s = System.nanoTime()
            rig.typeOne(i)
            t[i] = System.nanoTime() - s
        }
        t.sort()
        fun p(q: Double) = t[minOf(n - 1, (n * q).toInt())] / 1e6
        return Stats(p(0.5), p(0.95), p(0.99), t.average() / 1e6)
    }

    private fun compare(real: Boolean, label: String) {
        val n = 4000
        // Warm up the JIT on both, then alternate so drift hits both equally; keep the best of 3 rounds each.
        repeat(2) { measure(Rig(false, real), 1500); measure(Rig(true, real), 1500) }
        val off = ArrayList<Stats>()
        val on = ArrayList<Stats>()
        repeat(3) {
            off += measure(Rig(false, real), n)
            on += measure(Rig(true, real), n)
        }
        val bestOff = off.minBy { it.p95 }
        val bestOn = on.minBy { it.p95 }
        println("A2.5 latency [$label] toolbar NOT wired: $bestOff")
        println("A2.5 latency [$label] toolbar wired + request in flight: $bestOn")
        println("A2.5 latency [$label] budget (SD 17.1): p95 <= 30 ms mid-range, <= 50 ms low-end")
        assertTrue("p95 under the SD 17.1 mid-range budget", bestOn.p95 <= 30.0)
        // "No meaningful regression": wired p95 within 0.25 ms (or 50 %) of unwired.
        assertTrue("wired p95 ${bestOn.p95} vs ${bestOff.p95}", bestOn.p95 <= maxOf(bestOff.p95 * 1.5, bestOff.p95 + 0.25))
        assertTrue("wired mean ${bestOn.mean} vs ${bestOff.mean}", bestOn.mean <= maxOf(bestOff.mean * 1.5, bestOff.mean + 0.25))
    }

    @Test fun passthroughKeyPathIsUnaffectedByTheToolbar() = compare(real = false, label = "controller+adapter")

    @Test fun realCoreKeyPathIsUnaffectedByTheToolbar() = compare(real = true, label = "controller+adapter+Rust core")

    @Test fun typingNeverCallsTheAiPort() {
        for (real in listOf(false, true)) {
            val rig = Rig(wired = true, real = real)
            val submittedBefore = rig.actions.submitted.size // the one explicit tap in the rig
            val refreshBefore = rig.actions.refreshCount
            val availabilityBefore = rig.actions.availabilityReads
            val editorReadsBefore = rig.ed.calls.count { it == "getSelectedText" }
            repeat(1000) { rig.typeOne(it) }
            // Space, backspace, enter, shift and mode keys too.
            for (kind in listOf(KeyKind.SPACE, KeyKind.BACKSPACE, KeyKind.SHIFT, KeyKind.ENTER)) {
                rig.c.layout.rows.flatten().firstOrNull { it.kind == kind }?.let { rig.c.onKey(it); rig.ed.deliver(rig.adapter) }
            }
            assertEquals(1, submittedBefore)
            assertEquals("no request on keystrokes", submittedBefore, rig.actions.submitted.size)
            assertEquals("no refresh on keystrokes", refreshBefore, rig.actions.refreshCount)
            assertEquals("no port reads on keystrokes", availabilityBefore, rig.actions.availabilityReads)
            assertEquals("no selection reads on keystrokes", editorReadsBefore, rig.ed.calls.count { it == "getSelectedText" })
        }
    }
}
