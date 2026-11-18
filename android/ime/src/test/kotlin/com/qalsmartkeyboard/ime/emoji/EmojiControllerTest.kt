package com.qalsmartkeyboard.ime.emoji

import android.text.InputType as T
import com.qalsmartkeyboard.data.KeyboardSettings
import com.qalsmartkeyboard.ime.Composer
import com.qalsmartkeyboard.ime.ComposerKey
import com.qalsmartkeyboard.ime.ComposerUpdate
import com.qalsmartkeyboard.ime.FakeEditor
import com.qalsmartkeyboard.ime.FidelGrid
import com.qalsmartkeyboard.ime.InMemoryModePreferences
import com.qalsmartkeyboard.ime.InputConnectionAdapter
import com.qalsmartkeyboard.ime.InputProfile
import com.qalsmartkeyboard.ime.Key
import com.qalsmartkeyboard.ime.KeyKind
import com.qalsmartkeyboard.ime.KeyboardController
import com.qalsmartkeyboard.ime.PassthroughComposer
import com.qalsmartkeyboard.ime.PopupItem
import com.qalsmartkeyboard.ime.TextSink
import com.qalsmartkeyboard.ime.TypingMode
import com.qalsmartkeyboard.ime.userdict.WordLearningSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiControllerTest {
    private val grin = "😀"
    private val wave = "👋"
    private val family = "👨‍👩‍👧"
    private val ethiopia = "🇪🇹"
    private val thumbsMedium = "👍🏽"

    /** Host text as a string; deletes one UTF-16 code point cluster-naively (the adapter's grapheme logic has its own test). */
    private class Fake : TextSink {
        val out = StringBuilder()
        var composing = ""
        val events = ArrayList<String>()
        override fun apply(update: ComposerUpdate) {
            repeat(update.deleteBefore) { if (out.isNotEmpty()) out.setLength(out.length - 1) }
            out.append(update.commit)
            composing = update.composing
        }
        override fun sendEnterKey() { events += "ENTER" }
        override fun performEditorAction(actionId: Int) { events += "ACTION$actionId" }
        override fun wantsAutoCapital(capsFlags: Int) = false
    }

    /** Letters compose (shown as typed); Space and Commit flush; Backspace edits the composition first. */
    private class ComposingComposer : Composer {
        var buf = ""
        override fun onKey(key: ComposerKey): ComposerUpdate = when (key) {
            is ComposerKey.Text -> { buf += key.text; ComposerUpdate(composing = buf) }
            ComposerKey.Space -> flush(" ")
            ComposerKey.Enter -> flush("\n")
            ComposerKey.Commit -> flush("")
            ComposerKey.Backspace -> if (buf.isEmpty()) ComposerUpdate(deleteBefore = 1) else { buf = buf.dropLast(1); ComposerUpdate(composing = buf) }
            else -> ComposerUpdate.NONE
        }
        private fun flush(extra: String) = ComposerUpdate(commit = buf + extra).also { buf = "" }
        override fun selectCandidate(index: Int) = ComposerUpdate.NONE
        override fun reset() { buf = "" }
        override fun setMode(mode: TypingMode) = mode.isAbc
        override fun supports(mode: TypingMode) = mode.isAbc
        override fun isDraft(mode: TypingMode) = false
        override fun fidelGrid(language: String): FidelGrid? = null
    }

    private class MemoryStore : EmojiStore {
        override var recents: List<String> = emptyList()
        override var tones: Map<String, String> = emptyMap()
        override fun saveRecents(recents: List<String>) { this.recents = recents }
        override fun saveTones(tones: Map<String, String>) { this.tones = tones }
    }

    private class Setup(val c: KeyboardController, val sink: TextSink, val store: MemoryStore, val learned: ArrayList<String>)

    private fun setup(
        sink: TextSink = Fake(),
        composer: Composer = ComposingComposer(),
        type: Int = T.TYPE_CLASS_TEXT,
        loader: EmojiLoader? = EmojiLoader(EmojiDataTest.assets),
        executor: EmojiExecutor = EmojiExecutor.Inline,
        store: MemoryStore = MemoryStore(),
    ): Setup {
        val learned = ArrayList<String>()
        val c = KeyboardController(
            sink, prefs = InMemoryModePreferences(),
            dictionary = object : WordLearningSink {
                override fun learn(language: String, word: String, previous: String?) { learned += word }
                override fun add(language: String, word: String): Boolean { learned += word; return true }
            },
            emojiStore = store, emojiLoader = loader, emojiExecutor = executor,
        ) {}
        c.composer = composer
        c.startField(InputProfile.from(type, 0, false, 0))
        return Setup(c, sink, store, learned)
    }

    private fun KeyboardController.type(s: String) = s.forEach { ch -> onKey(Key(KeyKind.CHAR, ch.toString(), ch.toString())) }
    private fun KeyboardController.press(kind: KeyKind) = onKey(Key(kind, ""))

    // ------------------------------------------------------------------ opening and laziness

    @Test fun nothingLoadsUntilThePanelIsOpened() {
        var opened = 0
        val loader = EmojiLoader(EmojiSource { name -> opened++; EmojiDataTest.assets.open(name) })
        val s = setup(loader = loader)
        s.c.type("hello")
        assertEquals(0, opened)
        assertNull(s.c.emoji.catalog)
        s.c.press(KeyKind.EMOJI)
        assertTrue(opened > 0)
        assertNotNull(s.c.emoji.catalog)
    }

    @Test fun dataLoadsThroughTheExecutorNotOnTheCallingPath() {
        val queued = ArrayList<() -> Unit>()
        val exec = object : EmojiExecutor {
            override fun <T> run(work: () -> T, done: (T) -> Unit) { queued += { done(work()) } }
        }
        val s = setup(executor = exec)
        s.c.press(KeyKind.EMOJI)
        assertEquals(EmojiMode.PANEL, s.c.emoji.mode)
        assertNull(s.c.emoji.catalog) // still loading: the panel is up at once, empty
        queued.toList().also { queued.clear() }.forEach { it() }
        assertNotNull(s.c.emoji.catalog)
    }

    @Test fun panelDoesNotOpenWhereEmojiMakeNoSense() {
        for (type in listOf(
            T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD, T.TYPE_CLASS_NUMBER, T.TYPE_CLASS_PHONE,
            T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_URI, T.TYPE_NULL,
        )) {
            val s = setup(type = type)
            s.c.emoji.open(s.c.profile.allowsEmoji)
            assertEquals("type $type", EmojiMode.CLOSED, s.c.emoji.mode)
        }
    }

    @Test fun aNewFieldClosesThePanel() {
        val s = setup()
        s.c.press(KeyKind.EMOJI)
        s.c.startField(InputProfile.from(T.TYPE_CLASS_TEXT, 0, false, 0))
        assertEquals(EmojiMode.CLOSED, s.c.emoji.mode)
    }

    @Test fun backStepsOutOfSearchThenPanel() {
        val s = setup()
        assertFalse(s.c.emoji.back())
        s.c.press(KeyKind.EMOJI)
        s.c.emoji.openSearch()
        assertTrue(s.c.emoji.back())
        assertEquals(EmojiMode.PANEL, s.c.emoji.mode)
        assertTrue(s.c.emoji.back())
        assertEquals(EmojiMode.CLOSED, s.c.emoji.mode)
    }

    // ------------------------------------------------------------------ insertion

    @Test fun openingFinishesThePendingComposition() {
        val sink = Fake()
        val s = setup(sink)
        s.c.type("ab")
        assertEquals("ab", sink.composing)
        s.c.press(KeyKind.EMOJI)
        assertEquals("ab", sink.out.toString())
        assertEquals("", sink.composing)
        s.c.emoji.insert(grin)
        assertEquals("ab$grin", sink.out.toString())
    }

    @Test fun insertingCommitsAnyOpenCompositionFirst() {
        val sink = Fake()
        val s = setup(sink)
        s.c.press(KeyKind.EMOJI)
        s.c.type("xy") // keys while the panel is open (e.g. an accessibility click) leave a composition
        assertEquals("xy", sink.composing)
        s.c.emoji.insert(grin)
        assertEquals("xy$grin", sink.out.toString())
        assertEquals("", sink.composing)
    }

    @Test fun insertedEmojiAreNeverLearnedIntoTheDictionary() {
        val s = setup()
        s.c.press(KeyKind.EMOJI)
        s.c.emoji.insert(grin)
        s.c.emoji.insert(family)
        s.c.press(KeyKind.SPACE)
        assertTrue(s.learned.isEmpty())
    }

    @Test fun insertedEmojiCountAsRecent() {
        val s = setup()
        s.c.press(KeyKind.EMOJI)
        s.c.emoji.insert(grin)
        s.c.emoji.insert(wave)
        s.c.emoji.insert(grin)
        assertEquals(listOf(grin, wave), s.store.recents)
        assertEquals(listOf(grin, wave), s.c.emoji.recentCells().map { it.text })
    }

    @Test fun spaceAndEnterInThePanelReachTheHostField() {
        val sink = Fake()
        val s = setup(sink)
        s.c.press(KeyKind.EMOJI)
        s.c.emoji.insert(grin)
        s.c.press(KeyKind.SPACE)
        s.c.press(KeyKind.ENTER)
        assertEquals("$grin ", sink.out.toString())
        assertEquals(listOf("ENTER"), sink.events)
    }

    // ------------------------------------------------------------------ backspace over emoji

    private fun withEditor(): Triple<KeyboardController, FakeEditor, InputConnectionAdapter> {
        val editor = FakeEditor("")
        val adapter = InputConnectionAdapter { editor }
        adapter.startSession(isTypeNull = false, initialSelStart = 0, initialSelEnd = 0)
        val s = setup(sink = adapter, composer = PassthroughComposer())
        return Triple(s.c, editor, adapter)
    }

    @Test fun backspaceDeletesAWholeEmojiClusterInThePanel() {
        for (e in listOf(family, ethiopia, thumbsMedium, grin)) {
            val (c, editor, _) = withEditor()
            c.press(KeyKind.EMOJI)
            c.emoji.insert("a")
            c.emoji.insert(e)
            assertEquals("a$e", editor.text.toString())
            c.press(KeyKind.BACKSPACE)
            assertEquals("backspace over $e", "a", editor.text.toString())
        }
    }

    // ------------------------------------------------------------------ search

    @Test fun keysTypeIntoTheQueryNotTheHostField() {
        val sink = Fake()
        val s = setup(sink)
        s.c.press(KeyKind.EMOJI)
        s.c.emoji.openSearch()
        assertTrue(s.c.emoji.searching)
        s.c.type("grin")
        assertEquals("", sink.out.toString())
        assertEquals("", sink.composing)
        assertEquals("grin", s.c.emoji.query)
        assertTrue(s.c.emoji.results.any { it.entry.name == "grinning face" })
        // Backspace edits the query; Enter and the editor action never reach the host.
        s.c.press(KeyKind.BACKSPACE)
        assertEquals("gri", s.c.emoji.query)
        s.c.press(KeyKind.ENTER)
        assertTrue(sink.events.isEmpty())
        assertEquals("", sink.out.toString())
    }

    @Test fun insertingAResultGoesToTheHostAndKeepsTheSearchOpen() {
        val sink = Fake()
        val s = setup(sink)
        s.c.press(KeyKind.EMOJI)
        s.c.emoji.openSearch()
        s.c.type("grin")
        val result = s.c.emoji.results.first { it.entry.name == "grinning face" }
        s.c.emoji.insert(result.text)
        assertEquals(grin, sink.out.toString())
        assertTrue(s.c.emoji.searching)
        assertEquals("grin", s.c.emoji.query) // the half-typed query is not flushed into the host
        assertEquals(listOf(grin), s.store.recents)
    }

    @Test fun amharicTypedThroughTheLayoutsFindsEmoji() {
        val sink = Fake()
        val s = setup(sink)
        assertEquals("am", s.c.language)
        s.c.press(KeyKind.EMOJI)
        s.c.emoji.openSearch()
        // A Fidel / phonetic layout commits native text; the keyboard sends it to the composer as a Text key.
        for (item in "ፈገግታ".map { PopupItem(it.toString()) }) s.c.onPopupSelected(item)
        assertEquals("", sink.out.toString())
        assertEquals("ፈገግታ", s.c.emoji.query)
        assertTrue(s.c.emoji.results.any { it.entry.name == "grinning face" })
    }

    @Test fun backspaceInTheQueryDeletesWholeGraphemes() {
        val s = setup()
        s.c.press(KeyKind.EMOJI)
        s.c.emoji.openSearch()
        s.c.emoji.querySink.apply(ComposerUpdate(commit = "a$family"))
        s.c.emoji.querySink.apply(ComposerUpdate(deleteBefore = 1))
        assertEquals("a", s.c.emoji.query)
    }

    @Test fun searchTypingIsNeverLearned() {
        val s = setup()
        s.c.press(KeyKind.EMOJI)
        s.c.emoji.openSearch()
        s.c.type("happy")
        s.c.press(KeyKind.SPACE)
        s.c.type("happy")
        s.c.press(KeyKind.SPACE)
        assertTrue(s.learned.isEmpty())
    }

    @Test fun leavingTheSearchDropsTheQueryAndTheCompositionWithoutTouchingTheHost() {
        val sink = Fake()
        val s = setup(sink)
        s.c.press(KeyKind.EMOJI)
        s.c.emoji.openSearch()
        s.c.type("sm")
        s.c.press(KeyKind.EMOJI) // the emoji key returns to the panel
        assertEquals(EmojiMode.PANEL, s.c.emoji.mode)
        assertEquals("", s.c.emoji.query)
        assertEquals("", sink.out.toString())
        assertEquals("", sink.composing)
        // Typing again after returning to the keyboard reaches the host as usual.
        s.c.emoji.close()
        s.c.type("ok")
        assertEquals("ok", sink.composing)
    }

    @Test fun blankQueryShowsTheRecents() {
        val s = setup()
        s.c.press(KeyKind.EMOJI)
        s.c.emoji.insert(wave)
        s.c.emoji.openSearch()
        assertEquals(listOf(wave), s.c.emoji.results.map { it.text })
    }

    // ------------------------------------------------------------------ skin tones

    @Test fun chosenSkinToneIsRememberedPerBaseEmoji() {
        val s = setup()
        s.c.press(KeyKind.EMOJI)
        val entry = s.c.emoji.catalog!!.entryFor(wave)!!
        assertEquals(wave, s.c.emoji.formOf(entry))
        s.c.emoji.insertVariant(entry, entry.variants[2])
        assertEquals(entry.variants[2], s.c.emoji.formOf(entry))
        assertEquals(mapOf(wave to entry.variants[2]), s.store.tones)
        // A later session reads it back from the store.
        val again = setup(store = s.store)
        again.c.press(KeyKind.EMOJI)
        assertEquals(entry.variants[2], again.c.emoji.formOf(again.c.emoji.catalog!!.entryFor(wave)!!))
        // Choosing the base form again forgets the tone.
        again.c.emoji.insertVariant(entry, entry.text)
        assertTrue(again.store.tones.isEmpty())
    }
}
