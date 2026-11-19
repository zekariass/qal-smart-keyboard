package com.qalsmartkeyboard.ime.wordart

import android.text.InputType as T
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.qalsmartkeyboard.ime.FakeEditor
import com.qalsmartkeyboard.ime.InMemoryModePreferences
import com.qalsmartkeyboard.ime.InputConnectionAdapter
import com.qalsmartkeyboard.ime.InputProfile
import com.qalsmartkeyboard.ime.Key
import com.qalsmartkeyboard.ime.KeyKind
import com.qalsmartkeyboard.ime.KeyboardController
import com.qalsmartkeyboard.ime.PassthroughComposer
import com.qalsmartkeyboard.ime.emoji.EmojiDataTest
import com.qalsmartkeyboard.ime.emoji.EmojiLoader
import com.qalsmartkeyboard.ime.emoji.EmojiMode
import com.qalsmartkeyboard.ime.emoji.EmojiPanelView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Word Art in the keyboard: the tab, the text field's input routing, insert over a selection, copy, and the views. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class WordArtKeyboardTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val d = ctx.resources.displayMetrics.density
    private val widthPx = (411 * d).toInt()
    private val heightPx = (250 * d).toInt()

    private inner class Rig(initial: String = "", selStart: Int = initial.length, selEnd: Int = initial.length, type: Int = T.TYPE_CLASS_TEXT) {
        val editor = FakeEditor(initial).also { it.selStart = selStart; it.selEnd = selEnd }
        val adapter = InputConnectionAdapter { editor }
        val copied = ArrayList<String>()
        var copyWorks = true
        val controller = KeyboardController(
            adapter, prefs = InMemoryModePreferences(), emojiLoader = EmojiLoader(EmojiDataTest.assets),
            clipboard = { if (copyWorks) { copied += it; true } else false },
        ) {}.also {
            it.composer = PassthroughComposer()
            adapter.startSession(false, selStart, selEnd)
            it.startField(InputProfile.from(type, 0, false, 0))
        }
        val w get() = controller.emoji.wordArt

        fun openWordArt() {
            controller.emoji.open(true)
            controller.emoji.openWordArt()
        }

        fun typeInField(s: String) {
            if (controller.emoji.mode == EmojiMode.WORD_ART) controller.emoji.startWordArtTyping()
            s.forEach { controller.onKey(Key(KeyKind.CHAR, it.toString(), it.toString())) }
        }
    }

    // ------------------------------------------------------------------ the tab

    @Test fun theWordArtTabIsInTheEmojiTabRowAndOpensIt() {
        val r = Rig()
        r.controller.emoji.open(true)
        val panel = EmojiPanelView(ctx).also { it.controller = r.controller; it.heightProvider = { heightPx } }
        panel.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
        panel.onOpened()
        val provider = panel.accessibilityNodeProvider!!
        val tabs = panel.accessibilityIds().map { it to provider.createAccessibilityNodeInfo(it)!!.contentDescription?.toString() }
        val tab = tabs.firstOrNull { it.second == "Word Art" }
        assertNotNull("tabs: $tabs", tab)
        assertTrue(provider.performAction(tab!!.first, AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(EmojiMode.WORD_ART, r.controller.emoji.mode)
    }

    @Test fun tappingTheLastSlotOfTheTabRowOpensWordArt() {
        val r = Rig()
        r.controller.emoji.open(true)
        val panel = EmojiPanelView(ctx).also { it.controller = r.controller; it.heightProvider = { heightPx } }
        panel.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
        panel.onOpened()
        val t = android.os.SystemClock.uptimeMillis()
        val x = widthPx - 6f * d
        panel.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, 10f * d, 0))
        panel.dispatchTouchEvent(MotionEvent.obtain(t, t + 20, MotionEvent.ACTION_UP, x, 10f * d, 0))
        assertEquals(EmojiMode.WORD_ART, r.controller.emoji.mode)
    }

    @Test fun wordArtDoesNotOpenWhereEmojiDoNot() {
        val r = Rig(type = T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD)
        r.controller.emoji.open(r.controller.profile.allowsEmoji)
        r.controller.emoji.openWordArt()
        assertEquals(EmojiMode.CLOSED, r.controller.emoji.mode)
    }

    // ------------------------------------------------------------------ input routing

    @Test fun keysTypeIntoTheWordArtFieldNotTheHostField() {
        val r = Rig("hello ")
        r.openWordArt()
        r.typeInField("Abebe")
        assertEquals(EmojiMode.WORD_ART_TYPING, r.controller.emoji.mode)
        assertEquals("Abebe", r.w.text)
        assertEquals("hello ", r.editor.text.toString())
        r.controller.onKey(Key(KeyKind.BACKSPACE, ""))
        assertEquals("Abeb", r.w.text)
        assertEquals("hello ", r.editor.text.toString())
        r.controller.onKey(Key(KeyKind.SPACE, " ", " "))
        assertEquals("Abeb ", r.w.text)
    }

    @Test fun backspaceRemovesWholeGraphemeClusters() {
        val r = Rig()
        r.openWordArt()
        r.typeInField("a")
        r.w.sink.apply(com.qalsmartkeyboard.ime.ComposerUpdate(commit = "👨‍👩‍👧"))
        assertEquals("a👨‍👩‍👧", r.w.text)
        r.w.sink.apply(com.qalsmartkeyboard.ime.ComposerUpdate(deleteBefore = 1))
        assertEquals("a", r.w.text)
    }

    @Test fun enterAndTheEmojiKeyAndBackLeaveTheFieldWithTheTextKept() {
        val r = Rig()
        r.openWordArt()
        r.typeInField("Selam")
        r.controller.onKey(Key(KeyKind.ENTER, ""))
        assertEquals(EmojiMode.WORD_ART, r.controller.emoji.mode)
        assertEquals("Selam", r.w.text)
        assertEquals("", r.editor.text.toString()) // Enter never reaches the host
        r.controller.emoji.startWordArtTyping()
        r.controller.onKey(Key(KeyKind.EMOJI, ""))
        assertEquals(EmojiMode.WORD_ART, r.controller.emoji.mode)
        r.controller.emoji.startWordArtTyping()
        assertTrue(r.controller.emoji.back())
        assertEquals(EmojiMode.WORD_ART, r.controller.emoji.mode)
        assertTrue(r.controller.emoji.back())
        assertEquals(EmojiMode.PANEL, r.controller.emoji.mode)
        assertTrue(r.controller.emoji.back())
        assertEquals(EmojiMode.CLOSED, r.controller.emoji.mode)
    }

    @Test fun nothingTypedInTheFieldIsLearnedOrAutoCapitalised() {
        val r = Rig(type = T.TYPE_CLASS_TEXT or T.TYPE_TEXT_FLAG_CAP_SENTENCES)
        r.openWordArt()
        r.typeInField("hello")
        assertEquals("hello", r.w.text)
    }

    // ------------------------------------------------------------------ prefill, insert, copy

    @Test fun theFieldStartsFromTheSelectedTextElseEmpty() {
        val sel = Rig("say Abebe now", 4, 9)
        sel.openWordArt()
        assertEquals("Abebe", sel.w.text)
        val none = Rig("say Abebe now")
        none.openWordArt()
        assertEquals("", none.w.text)
    }

    @Test fun insertReplacesTheSelectionAndTheCardCloses() {
        val r = Rig("say Abebe now", 4, 9)
        r.openWordArt()
        val bold = WordArt.style("bold")!!
        assertTrue(r.w.select(bold))
        assertTrue(r.w.insert(bold))
        assertEquals("say 𝐀𝐛𝐞𝐛𝐞 now", r.editor.text.toString())
        assertEquals(WordArtNotice.INSERTED, r.w.notice)
        assertEquals(null, r.w.selected)
    }

    @Test fun insertWithoutASelectionGoesAtTheCursor() {
        val r = Rig("hi ")
        r.openWordArt()
        r.typeInField("Selam")
        r.controller.onKey(Key(KeyKind.ENTER, ""))
        val s = WordArt.style("sparkles")!!
        assertTrue(r.w.insert(s))
        assertEquals("hi ✨ Selam ✨", r.editor.text.toString())
    }

    @Test fun copyGoesToTheClipboardAndConfirms() {
        val r = Rig()
        r.openWordArt()
        r.typeInField("Abebe")
        r.controller.onKey(Key(KeyKind.ENTER, ""))
        assertTrue(r.w.copy(WordArt.style("script")!!))
        assertEquals(listOf("𝒜𝒷ℯ𝒷ℯ"), r.copied)
        assertEquals(WordArtNotice.COPIED, r.w.notice)
        assertEquals("", r.editor.text.toString())
        r.copyWorks = false
        assertFalse(r.w.copy(WordArt.style("script")!!))
        assertEquals(WordArtNotice.COPY_FAILED, r.w.notice)
    }

    @Test fun nothingIsInsertedOrCopiedWhileOnlyTheSampleIsShown() {
        val r = Rig()
        r.openWordArt()
        val s = WordArt.style("sparkles")!!
        assertFalse(r.w.insert(s))
        assertFalse(r.w.copy(s))
        assertEquals(WordArtNotice.NOTHING_TO_USE, r.w.notice)
        assertEquals("", r.editor.text.toString())
        assertTrue(r.copied.isEmpty())
        // The sample is what the previews show.
        assertEquals("✨ ${WordArt.sample(r.controller.language)} ✨", r.w.render(s).text)
    }

    @Test fun greetingAloneCanBeInsertedAndFollowsTheKeyboardLanguage() {
        assertTrue(Greetings.phrase(Occasion.BIRTHDAY, "am")!!.text != Greetings.phrase(Occasion.BIRTHDAY, "en")!!.text)
        val r = Rig()
        r.openWordArt()
        r.w.toggleGreeting()
        val s = WordArt.style("bday_cake")!!
        assertTrue(r.w.insert(s))
        assertEquals("🎂 ${Greetings.phrase(Occasion.BIRTHDAY, r.controller.language)!!.text} 🎂", r.editor.text.toString())
    }

    @Test fun styleThatCannotServeTheTextCannotBeOpened() {
        val r = Rig()
        r.openWordArt()
        r.typeInField("ሰላም")
        r.controller.onKey(Key(KeyKind.ENTER, ""))
        assertFalse(r.w.select(WordArt.style("bold")!!))
        assertTrue(r.w.select(WordArt.style("geez_ornament")!!))
        assertFalse(r.w.insert(WordArt.style("bold")!!))
    }

    @Test fun closingTheKeyboardForgetsTheText() {
        val r = Rig("abc", 0, 3)
        r.openWordArt()
        assertEquals("abc", r.w.text)
        r.controller.startField(InputProfile.DEFAULT)
        assertEquals(EmojiMode.CLOSED, r.controller.emoji.mode)
        assertEquals("", r.w.text)
    }

    // ------------------------------------------------------------------ views

    private fun layout(v: View) {
        v.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    private fun panelFor(r: Rig): WordArtPanelView {
        val p = WordArtPanelView(ctx).also { it.controller = r.controller; it.heightProvider = { heightPx } }
        layout(p)
        p.refresh()
        layout(p)
        return p
    }

    @Test fun panelTakesTheKeyAreaHeightAndShowsTheCategoriesAndSampleStyles() {
        val r = Rig()
        r.openWordArt()
        val p = panelFor(r)
        assertEquals(heightPx, p.measuredHeight)
        for (c in WordArtCategory.entries) assertNotNull(p.chipFor(c))
        assertTrue(p.listView.adapter.count >= 8)
        assertEquals("Tap to type your text", p.fieldView.hint.toString())
    }

    @Test fun previewRowsReadThePlainTextAndStyleNameNotTheDecoratedLetters() {
        val r = Rig()
        r.openWordArt()
        r.typeInField("Abebe")
        r.controller.onKey(Key(KeyKind.ENTER, ""))
        r.w.selectCategory(WordArtCategory.LETTERS)
        val p = panelFor(r)
        val boldIndex = (0 until p.listView.adapter.count).first { i ->
            val v = p.listView.adapter.getView(i, null, p.listView)
            v.contentDescription?.toString()?.startsWith("Bold:") == true
        }
        val row = p.listView.adapter.getView(boldIndex, null, p.listView)
        assertEquals("Bold: Abebe", row.contentDescription.toString())
        assertFalse(row.contentDescription.toString().contains("𝐀"))
    }

    @Test fun unsupportedStylesAreDisabledAndSortedLastWithTheReason() {
        val r = Rig()
        r.openWordArt()
        r.typeInField("ሰላም")
        r.controller.onKey(Key(KeyKind.ENTER, ""))
        r.w.selectCategory(WordArtCategory.LETTERS)
        val p = panelFor(r)
        val adapter = p.listView.adapter
        val first = adapter.getView(0, null, p.listView).contentDescription.toString()
        assertTrue(first, first.startsWith("Spaced letters: ሰላም") || first.startsWith("Thin spaced") || first.startsWith("Dotted"))
        val last = adapter.getView(adapter.count - 1, null, p.listView)
        assertTrue(last.contentDescription.toString(), last.contentDescription.toString().endsWith("Latin letters only, unavailable"))
        assertFalse(adapter.isEnabled(adapter.count - 1))
        assertTrue(adapter.isEnabled(0))
    }

    @Test fun cardShowsTheLargePreviewWithInsertCopyAndAConfirmation() {
        val r = Rig("x", 0, 1)
        r.openWordArt()
        val s = WordArt.style("sparkles")!!
        val p = panelFor(r)
        r.w.select(s)
        p.refresh()
        assertEquals(View.VISIBLE, p.cardView.visibility)
        // The selected "x" was prefilled.
        assertTrue(p.copyButton.isEnabled)
        p.copyButton.performClick()
        assertEquals(listOf("✨ x ✨"), r.copied)
        p.refresh()
        assertTrue(p.cardView.findViewWithText("Copied"))
        p.insertButton.performClick()
        assertEquals("✨ x ✨", r.editor.text.toString())
        p.refresh()
        assertEquals(View.GONE, p.cardView.visibility)
    }

    private fun View.findViewWithText(t: String): Boolean {
        val out = ArrayList<View>()
        findViewsWithText(out, t, View.FIND_VIEWS_WITH_TEXT)
        return out.isNotEmpty()
    }

    @Test fun cardPreviewDescriptionIsPlainTextPlusStyleName() {
        val r = Rig("Abebe", 0, 5)
        r.openWordArt()
        val p = panelFor(r)
        r.w.select(WordArt.style("fraktur")!!)
        p.refresh()
        val preview = p.previewView
        assertEquals("Fraktur style preview: Abebe", preview.contentDescription.toString())
    }

    @Test fun fieldTapStartsTypingAndTheBarShowsTheText() {
        val r = Rig()
        r.openWordArt()
        val p = panelFor(r)
        p.fieldView.performClick()
        assertEquals(EmojiMode.WORD_ART_TYPING, r.controller.emoji.mode)
        r.typeInField("Abe")
        val bar = WordArtBar(ctx).also { it.controller = r.controller }
        layout(bar)
        bar.render()
        assertTrue(bar.fieldView.text.toString().startsWith("Abe"))
        assertEquals("Word Art text: Abe", bar.fieldView.contentDescription.toString())
        bar.doneView.performClick()
        assertEquals(EmojiMode.WORD_ART, r.controller.emoji.mode)
        assertEquals("Abe", r.w.text)
    }

    @Test fun greetingSwitchAndAbcButtonWork() {
        val r = Rig()
        r.openWordArt()
        val p = panelFor(r)
        p.greetingView.performClick()
        assertTrue(r.w.greeting)
        p.abcView.performClick()
        assertEquals(EmojiMode.CLOSED, r.controller.emoji.mode)
    }

    @Test fun categoryChipSwitchesTheList() {
        val r = Rig()
        r.openWordArt()
        val p = panelFor(r)
        p.chipFor(WordArtCategory.BIRTHDAY).performClick()
        assertEquals(WordArtCategory.BIRTHDAY, r.w.category)
        p.refresh()
        assertEquals(WordArt.stylesIn(WordArtCategory.BIRTHDAY).size, p.listView.adapter.count)
    }
}
