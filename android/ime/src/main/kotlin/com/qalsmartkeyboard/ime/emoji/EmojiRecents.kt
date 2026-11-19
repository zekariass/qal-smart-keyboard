package com.qalsmartkeyboard.ime.emoji

import com.qalsmartkeyboard.data.KeyboardSettings

/**
 * Where the panel keeps what the user picked: the recently used emoji and the skin tone last chosen per
 * base emoji. Device-local (the settings store; never synced, never sent anywhere, never logged).
 */
interface EmojiStore {
    val recents: List<String>
    val tones: Map<String, String>
    fun saveRecents(recents: List<String>)
    fun saveTones(tones: Map<String, String>)

    object None : EmojiStore {
        override val recents: List<String> get() = emptyList()
        override val tones: Map<String, String> get() = emptyMap()
        override fun saveRecents(recents: List<String>) {}
        override fun saveTones(tones: Map<String, String>) {}
    }
}

/** [EmojiStore] over the cached settings snapshot (reads) and its fire-and-forget update (writes). */
class SettingsEmojiStore(
    private val settings: () -> KeyboardSettings,
    private val update: ((KeyboardSettings) -> KeyboardSettings) -> Unit,
) : EmojiStore {
    override val recents: List<String> get() = settings().recentEmoji
    override val tones: Map<String, String> get() = settings().emojiTones
    override fun saveRecents(recents: List<String>) = update { it.copy(recentEmoji = recents) }
    override fun saveTones(tones: Map<String, String>) = update { it.copy(emojiTones = tones) }
}

/** The most recently used emoji, newest first, at most [limit]. */
class RecentEmoji(private val store: EmojiStore, private val limit: Int = LIMIT) {
    var list: List<String> = store.recents.distinct().take(limit)
        private set

    /** Moves [text] to the front (adding it), and saves. */
    fun use(text: String) {
        list = (listOf(text) + list.filter { it != text }).take(limit)
        store.saveRecents(list)
    }

    companion object {
        const val LIMIT = 32
    }
}

/** The skin tone last chosen per base emoji: [resolve] gives the form to show and to type. */
class SkinToneMemory(private val store: EmojiStore) {
    private var map: Map<String, String> = store.tones

    fun resolve(entry: EmojiEntry): String = map[entry.text]?.takeIf { it in entry.variants } ?: entry.text

    /** Remembers [variant] (one of [entry]'s forms, the base included) for [entry]. */
    fun choose(entry: EmojiEntry, variant: String) {
        map = if (variant == entry.text) map - entry.text else map + (entry.text to variant)
        store.saveTones(map)
    }
}
