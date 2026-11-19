package com.qalsmartkeyboard.ime.emoji

import com.qalsmartkeyboard.data.KeyboardSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiRecentsTest {
    private class MemoryStore(var r: List<String> = emptyList(), var t: Map<String, String> = emptyMap()) : EmojiStore {
        override val recents get() = r
        override val tones get() = t
        override fun saveRecents(recents: List<String>) { r = recents }
        override fun saveTones(tones: Map<String, String>) { t = tones }
    }

    @Test fun mostRecentFirstWithoutDuplicates() {
        val store = MemoryStore()
        val r = RecentEmoji(store)
        r.use("a"); r.use("b"); r.use("c"); r.use("a")
        assertEquals(listOf("a", "c", "b"), r.list)
        assertEquals(r.list, store.r)
    }

    @Test fun limitedToThirtyTwo() {
        val store = MemoryStore()
        val r = RecentEmoji(store)
        for (i in 1..50) r.use("e$i")
        assertEquals(32, r.list.size)
        assertEquals("e50", r.list.first())
        assertEquals("e19", r.list.last())
    }

    @Test fun survivesARestartThroughTheStore() {
        val store = MemoryStore()
        RecentEmoji(store).apply { use("x"); use("y") }
        assertEquals(listOf("y", "x"), RecentEmoji(store).list)
    }

    @Test fun settingsStoreKeepsThemInTheSettingsAndSanitisesThem() {
        var settings = KeyboardSettings.DEFAULT
        val store = SettingsEmojiStore({ settings }, { change -> settings = change(settings).sanitized() })
        val r = RecentEmoji(store)
        r.use("😀")
        r.use("👋")
        assertEquals(listOf("👋", "😀"), settings.recentEmoji)
        // Separators of the stored form, empty and over-long entries are dropped.
        val dirty = settings.copy(recentEmoji = listOf("a|b", "ok", "", "x".repeat(60), "line\nbreak")).sanitized()
        assertEquals(listOf("ok"), dirty.recentEmoji)
        val many = settings.copy(recentEmoji = (1..60).map { "e$it" }).sanitized()
        assertEquals(KeyboardSettings.EMOJI_RECENT_MAX, many.recentEmoji.size)
    }

    @Test fun skinToneMemoryIsPerBaseAndOnlyAcceptsItsOwnVariants() {
        val e = EmojiEntry("W", "waving hand", EmojiCategory.PEOPLE, listOf("W1", "W2", "W3", "W4", "W5"), emptyList(), 0)
        val other = EmojiEntry("T", "thumbs up", EmojiCategory.PEOPLE, listOf("T1", "T2", "T3", "T4", "T5"), emptyList(), 1)
        val store = MemoryStore()
        val m = SkinToneMemory(store)
        m.choose(e, "W3")
        assertEquals("W3", m.resolve(e))
        assertEquals("T", m.resolve(other))
        // A stale or foreign value in the store is ignored.
        assertEquals("T", SkinToneMemory(MemoryStore(t = mapOf("T" to "W3"))).resolve(other))
        m.choose(e, "W")
        assertEquals("W", m.resolve(e))
        assertTrue(store.t.isEmpty())
    }
}
