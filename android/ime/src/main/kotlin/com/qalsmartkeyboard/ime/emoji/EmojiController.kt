package com.qalsmartkeyboard.ime.emoji

import com.qalsmartkeyboard.ime.ComposerUpdate
import com.qalsmartkeyboard.ime.TextSink
import com.qalsmartkeyboard.ime.wordart.WordArtController
import com.qalsmartkeyboard.ime.wordart.WordArtHost
import java.text.BreakIterator

enum class EmojiMode {
    /** The keyboard is showing. */
    CLOSED,

    /** The panel replaces the key area. */
    PANEL,

    /** The keys are back, typing into the search query; results fill the strip above the keys. */
    SEARCH,

    /** The Word Art panel (ADR 0030, a tab of this panel) replaces the key area: text field, categories, style previews. */
    WORD_ART,

    /** The keys are back, typing into Word Art's text field; the bar shows the field. */
    WORD_ART_TYPING,
}

/** Runs [work] off the main thread and delivers its result on the main thread ([Inline] runs both at once, for tests). */
interface EmojiExecutor {
    fun <T> run(work: () -> T, done: (T) -> Unit)

    object Inline : EmojiExecutor {
        override fun <T> run(work: () -> T, done: (T) -> Unit) = done(work())
    }
}

/** What the emoji panel needs from the keyboard controller. */
interface EmojiHost {
    /** Ends the open composition as typed (so an emoji never lands inside a half-typed word). */
    fun commitComposition()

    /** Drops the composer's state without committing (the search query is discarded). */
    fun resetComposition()

    /** Commits [text] to the host field (an `InputConnection` commit; never learned into the dictionary). */
    fun commitToField(text: String)

    /** The keyboard language, for localised search. */
    val language: String

    /** The text selected in the host field (Word Art starts from it), or null. */
    fun selectedText(): String? = null

    /** Puts [text] on the clipboard; false when that is not possible. */
    fun copyToClipboard(text: String): Boolean = false
}

/**
 * The emoji panel's state and logic; no View code. Opening it, searching, inserting, recents and skin tones.
 *
 * Nothing loads until the panel is first opened ([open]); the data is parsed on [executor]'s background thread
 * ([EmojiLoader]). While searching, the keyboard controller routes the composer's output to [querySink] instead of
 * the host field, so every layout (Fidel, phonetic, Qubee, English) can type the query. The query is never logged
 * or stored; recents and skin tones go to the local [EmojiStore] only when an emoji is inserted.
 */
class EmojiController(
    private val host: EmojiHost,
    store: EmojiStore = EmojiStore.None,
    private val loader: EmojiLoader? = null,
    private val executor: EmojiExecutor = EmojiExecutor.Inline,
    private val onChanged: () -> Unit,
) {
    var mode: EmojiMode = EmojiMode.CLOSED
        private set

    val searching: Boolean get() = mode == EmojiMode.SEARCH

    /** The keys type into Word Art's text field, not the host field (see `KeyboardController.apply`). */
    val typingWordArt: Boolean get() = mode == EmojiMode.WORD_ART_TYPING

    /** Where the composer's output goes instead of the host field, or null when it is the host field. */
    val redirect: TextSink? get() = when (mode) {
        EmojiMode.SEARCH -> querySink
        EmojiMode.WORD_ART_TYPING -> wordArt.sink
        else -> null
    }

    /** Word Art (ADR 0030): its text, category and previews; only used while the mode is [EmojiMode.WORD_ART] or typing. */
    val wordArt: WordArtController = WordArtController(
        object : WordArtHost {
            override fun commitToField(text: String) = host.commitToField(text)
            override fun selectedText(): String? = host.selectedText()
            override fun copyToClipboard(text: String): Boolean = host.copyToClipboard(text)
            override val language: String get() = host.language
        },
    ) { onChanged() }

    var catalog: EmojiCatalog? = null
        private set

    private var loading = false
    private val loadingLanguages = HashSet<String>()
    // Lazy: the settings snapshot may still hold defaults when the keyboard starts; the user's data is read on first use.
    private val recents by lazy { RecentEmoji(store) }
    private val tones by lazy { SkinToneMemory(store) }

    /** The recently used emoji texts, newest first (as inserted, tone included). */
    val recentTexts: List<String> get() = recents.list

    /** Opens the panel in a field that allows it; finishes any pending composition first. */
    fun open(allowed: Boolean) {
        if (!allowed || loader == null || mode != EmojiMode.CLOSED) return
        host.commitComposition()
        mode = EmojiMode.PANEL
        ensureLoaded()
    }

    private fun ensureLoaded() {
        val l = loader ?: return
        if (catalog != null || loading) return
        loading = true
        executor.run({ try { l.load() } catch (e: Exception) { EmojiCatalog(emptyList(), emptyList()) } }) { c ->
            catalog = c
            loading = false
            onChanged()
            ensureLanguage()
        }
    }

    private fun ensureLanguage() {
        val l = loader ?: return
        val c = catalog ?: return
        val lang = host.language
        if (lang == "en" || c.hasLanguage(lang) || !loadingLanguages.add(lang)) return
        executor.run({ try { l.loadLanguage(lang) } catch (e: Exception) { null } }) { a ->
            if (a != null) c.putLanguage(lang, a)
            if (mode == EmojiMode.SEARCH) refreshResults()
            onChanged()
        }
    }

    /** Back to the keyboard. */
    fun close() {
        if (mode == EmojiMode.CLOSED) return
        if (mode == EmojiMode.SEARCH) leaveSearch()
        if (mode == EmojiMode.WORD_ART_TYPING) host.resetComposition()
        if (mode == EmojiMode.WORD_ART || mode == EmojiMode.WORD_ART_TYPING) wordArt.end()
        mode = EmojiMode.CLOSED
        onChanged()
    }

    /** The system back action: search to panel, panel to keyboard. False when the panel was not open. */
    fun back(): Boolean {
        when (mode) {
            EmojiMode.CLOSED -> return false
            EmojiMode.SEARCH -> closeSearch()
            EmojiMode.PANEL -> close()
            EmojiMode.WORD_ART_TYPING -> endWordArtTyping()
            EmojiMode.WORD_ART -> closeWordArt()
        }
        return true
    }

    // ------------------------------------------------------------------ search

    private val queryBuffer = StringBuilder()
    private var queryComposing = ""

    /** What the search box shows: the committed query plus the composition in progress. */
    val query: String get() = queryBuffer.toString() + queryComposing

    var results: List<EmojiCell> = emptyList()
        private set

    /** The composer's output goes here while [searching] (see `KeyboardController`). */
    val querySink: TextSink = object : TextSink {
        override fun apply(update: ComposerUpdate) {
            if (update.deleteBefore > 0) deleteGraphemes(update.deleteBefore)
            queryBuffer.append(update.commit)
            queryComposing = update.composing
            refreshResults()
        }

        override fun sendEnterKey() {}
        override fun performEditorAction(actionId: Int) {}
        override fun wantsAutoCapital(capsFlags: Int) = false
    }

    private fun deleteGraphemes(n: Int) {
        repeat(n) {
            if (queryBuffer.isEmpty()) return
            val it = BreakIterator.getCharacterInstance()
            it.setText(queryBuffer.toString())
            val start = it.preceding(queryBuffer.length)
            queryBuffer.setLength(if (start == BreakIterator.DONE) 0 else start)
        }
    }

    fun openSearch() {
        if (mode != EmojiMode.PANEL) return
        mode = EmojiMode.SEARCH
        clearQuery()
        ensureLanguage()
        refreshResults()
        onChanged()
    }

    /** Back from search to the panel; the query is dropped. */
    fun closeSearch() {
        if (mode != EmojiMode.SEARCH) return
        leaveSearch()
        mode = EmojiMode.PANEL
        onChanged()
    }

    // ------------------------------------------------------------------ word art

    /** The Word Art tab: from the panel to the Word Art screen, starting from the host field's selected text. */
    fun openWordArt() {
        if (mode != EmojiMode.PANEL) return
        host.commitComposition()
        wordArt.begin()
        mode = EmojiMode.WORD_ART
        onChanged()
    }

    /** Back from Word Art to the emoji panel. */
    fun closeWordArt() {
        if (mode != EmojiMode.WORD_ART) return
        wordArt.end()
        mode = EmojiMode.PANEL
        onChanged()
    }

    /** The text field was tapped: the keys come back and type into it. */
    fun startWordArtTyping() {
        if (mode != EmojiMode.WORD_ART) return
        mode = EmojiMode.WORD_ART_TYPING
        onChanged()
    }

    /** Done typing: the composition in progress becomes part of the text and the Word Art screen is back. */
    fun endWordArtTyping() {
        if (mode != EmojiMode.WORD_ART_TYPING) return
        host.commitComposition() // still routed to the Word Art text: the open composition is appended to it
        mode = EmojiMode.WORD_ART
        onChanged()
    }

    private fun leaveSearch() {
        host.resetComposition()
        clearQuery()
    }

    private fun clearQuery() {
        queryBuffer.setLength(0)
        queryComposing = ""
        results = emptyList()
    }

    /** Called when the keyboard language changes while the panel is open. */
    fun onLanguageChanged() {
        if (catalog == null) return
        ensureLanguage()
        if (searching) refreshResults()
    }

    private fun refreshResults() {
        val c = catalog
        results = when {
            c == null -> emptyList()
            query.isBlank() -> recentCells().take(EmojiSearch.MAX_RESULTS)
            else -> EmojiSearch.search(c, query, host.language).map { EmojiCell(formOf(it), it) }
        }
    }

    // ------------------------------------------------------------------ inserting

    /** The form of [entry] to show and insert: its remembered skin tone, else the base. */
    fun formOf(entry: EmojiEntry): String = tones.resolve(entry)

    /**
     * Inserts [text] (an emoji or one of its skin-tone forms) into the host field and counts it as recent. In the
     * panel the open composition is committed first; while searching the composition belongs to the query and is
     * left alone. Emoji never reach the personal dictionary (they bypass the composer).
     */
    fun insert(text: String) {
        if (!searching) host.commitComposition()
        host.commitToField(text)
        recents.use(text)
        if (searching) refreshResults()
        onChanged()
    }

    /** The user picked [variant] of [entry] in the skin-tone popup: insert it and remember the tone. */
    fun insertVariant(entry: EmojiEntry, variant: String) {
        tones.choose(entry, variant)
        insert(variant)
    }

    /** Recents as panel cells (those the device can draw), shown in the form they were inserted. */
    fun recentCells(): List<EmojiCell> {
        val c = catalog ?: return emptyList()
        return recents.list.mapNotNull { t -> c.entryFor(t)?.let { EmojiCell(t, it) } }
    }
}
