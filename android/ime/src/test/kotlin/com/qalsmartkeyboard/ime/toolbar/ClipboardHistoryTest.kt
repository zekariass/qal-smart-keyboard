package com.qalsmartkeyboard.ime.toolbar

import android.text.InputType as T
import android.view.inputmethod.EditorInfo as E
import com.qalsmartkeyboard.ime.InputProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR 0035: the clipboard history keeps at most 20 text clips for an hour, in memory only, and never sensitive ones. */
class ClipboardHistoryTest {
    private var now = 1_000_000L
    private val h = ClipboardHistory { now }

    @Test fun newestFirstAndDuplicatesMoveToTheTop() {
        h.add("one")
        now += 10
        h.add("two")
        now += 10
        h.add("one")
        assertEquals(listOf("one", "two"), h.items().map { it.text })
        assertEquals(now, h.items().first().atMs)
    }

    @Test fun keepsAtMostTwentyClips() {
        repeat(25) { h.add("clip $it") }
        val texts = h.items().map { it.text }
        assertEquals(ClipboardHistory.MAX_ITEMS, texts.size)
        assertEquals("clip 24", texts.first())
        assertEquals("clip 5", texts.last())
    }

    @Test fun clipsAreDroppedAfterAnHour() {
        h.add("old")
        now += ClipboardHistory.TTL_MS - 1
        h.add("new")
        assertEquals(listOf("new", "old"), h.items().map { it.text })
        now += 1
        assertEquals(listOf("new"), h.items().map { it.text })
        now += ClipboardHistory.TTL_MS
        assertTrue(h.isEmpty)
    }

    @Test fun aClockGoingBackwardsDropsTheClips() {
        h.add("x")
        now -= 5_000
        assertTrue(h.items().isEmpty())
    }

    @Test fun blankAndOversizedTextsAreRefused() {
        assertFalse(h.add(""))
        assertFalse(h.add("   \n"))
        assertFalse(h.add("x".repeat(ClipboardHistory.MAX_CHARS + 1)))
        assertTrue(h.add("x".repeat(ClipboardHistory.MAX_CHARS)))
        assertEquals(1, h.items().size)
    }

    @Test fun removeAndClear() {
        h.add("a"); h.add("b"); h.add("c")
        val b = h.items().first { it.text == "b" }
        assertTrue(h.remove(b.id))
        assertFalse(h.remove(b.id))
        assertNull(h.find(b.id))
        assertEquals(listOf("c", "a"), h.items().map { it.text })
        h.clear()
        assertTrue(h.isEmpty)
    }

    @Test fun idsStayStableAndUnique() {
        h.add("a"); h.add("b")
        val ids = h.items().map { it.id }
        assertEquals(ids.toSet().size, ids.size)
        val a = h.items().first { it.text == "a" }
        h.add("c")
        assertEquals("a", h.find(a.id)?.text)
    }

    @Test fun nothingIsPersisted() {
        h.add("secret-ish")
        // A new history (the keyboard process restarted) starts empty: there is no storage to read back from.
        assertTrue(ClipboardHistory { now }.isEmpty)
        // The class holds no context, file or store: its only dependency is the clock.
        assertTrue(ClipboardHistory::class.java.declaredFields.none { android.content.Context::class.java.isAssignableFrom(it.type) })
        assertTrue(ClipboardHistory::class.java.declaredFields.none { java.io.File::class.java.isAssignableFrom(it.type) || it.type.name.contains("SharedPreferences") || it.type.name.contains("DataStore") })
    }

    // ---------------------------------------------------------------- what may be kept

    private fun profile(type: Int, options: Int = 0) = InputProfile.from(type, options, false, 0)

    @Test fun sensitiveClipsAndNonTextAreNeverKept() {
        val text = profile(T.TYPE_CLASS_TEXT)
        assertTrue(ClipPolicy.mayRecord(historyOn = true, sensitive = false, isText = true, field = text))
        assertFalse(ClipPolicy.mayRecord(historyOn = true, sensitive = true, isText = true, field = text))
        assertFalse(ClipPolicy.mayRecord(historyOn = true, sensitive = false, isText = false, field = text))
        assertFalse(ClipPolicy.mayRecord(historyOn = false, sensitive = false, isText = true, field = text))
        assertTrue("no focused field yet", ClipPolicy.mayRecord(historyOn = true, sensitive = false, isText = true, field = null))
    }

    @Test fun nothingCopiedWhileAPasswordOrIncognitoFieldHasFocusIsKept() {
        for (p in listOf(
            profile(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD),
            profile(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_WEB_PASSWORD),
            profile(T.TYPE_CLASS_NUMBER or T.TYPE_NUMBER_VARIATION_PASSWORD),
            profile(T.TYPE_CLASS_TEXT, E.IME_FLAG_NO_PERSONALIZED_LEARNING),
            profile(T.TYPE_NULL),
        )) {
            assertFalse("$p", ClipPolicy.mayRecord(historyOn = true, sensitive = false, isText = true, field = p))
        }
    }
}
