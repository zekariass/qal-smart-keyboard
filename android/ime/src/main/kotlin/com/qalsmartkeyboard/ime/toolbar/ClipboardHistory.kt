package com.qalsmartkeyboard.ime.toolbar

/** One remembered clip. [id] is stable while it is kept (delete and paste refer to it); [atMs] when it was copied. */
class Clip(val id: Long, val text: String, val atMs: Long)

/**
 * The clipboard panel's history (ADR 0035): text the user copied while the keyboard process is alive.
 *
 * Privacy rules, all enforced here or by its only caller ([SystemClipboardSource]):
 * - **Memory only.** Nothing is written to disk, synced, logged or sent; the history dies with the process, and
 *   [clear] (Clear all, the setting turned off) drops it at once.
 * - **Short-lived.** A clip is dropped [TTL_MS] after it was copied; at most [MAX_ITEMS] are kept, newest first.
 * - **Never sensitive.** The caller skips clips marked `EXTRA_IS_SENSITIVE` and clips copied while a password or
 *   incognito field is focused; here empty, blank and oversized ([MAX_CHARS]) texts are refused.
 * - Copying the same text again moves it to the top instead of adding a duplicate.
 */
class ClipboardHistory(private val clock: () -> Long = System::currentTimeMillis) {
    private val items = ArrayList<Clip>()
    private var nextId = 1L

    /** Remembers [text] as the newest clip. False when it is refused (blank or too long). */
    fun add(text: String): Boolean {
        if (text.isBlank() || text.length > MAX_CHARS) return false
        val now = clock()
        prune(now)
        items.removeAll { it.text == text }
        items.add(0, Clip(nextId++, text, now))
        while (items.size > MAX_ITEMS) items.removeAt(items.lastIndex)
        return true
    }

    /** The kept clips, newest first (expired ones are dropped first). */
    fun items(): List<Clip> {
        prune(clock())
        return items.toList()
    }

    fun find(id: Long): Clip? = items().firstOrNull { it.id == id }

    fun remove(id: Long): Boolean = items.removeAll { it.id == id }

    /** Clear all (and the setting turned off). */
    fun clear() = items.clear()

    /** Drops the clips older than [TTL_MS]. Also called when the keyboard is shown or hidden. */
    fun prune(now: Long = clock()) {
        items.removeAll { now - it.atMs >= TTL_MS || now < it.atMs }
    }

    val isEmpty: Boolean get() = items().isEmpty()

    companion object {
        const val MAX_ITEMS = 20
        const val TTL_MS = 60L * 60_000

        /** Longer clips are not kept (they would be impractical to show and costly to hold); pasting them works from the app. */
        const val MAX_CHARS = 20_000
    }
}
