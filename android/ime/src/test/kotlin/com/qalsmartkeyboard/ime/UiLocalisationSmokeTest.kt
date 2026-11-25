package com.qalsmartkeyboard.ime

import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.InputType as T
import android.view.View
import com.qalsmartkeyboard.ime.ai.AiLanguagePrefs
import com.qalsmartkeyboard.ime.ai.AiMessages
import com.qalsmartkeyboard.ime.ai.AiOutcome
import com.qalsmartkeyboard.ime.ai.AiResult
import com.qalsmartkeyboard.ime.ai.AiToolbarController
import com.qalsmartkeyboard.ime.ai.AiTool
import com.qalsmartkeyboard.ime.ai.AiWorkspace
import com.qalsmartkeyboard.ime.ai.ChatPanelView
import com.qalsmartkeyboard.ime.ai.ChatStarter
import com.qalsmartkeyboard.ime.ai.FakeAiEditor
import com.qalsmartkeyboard.ime.ai.FakeClipboard
import com.qalsmartkeyboard.ime.ai.AiToolbarView
import com.qalsmartkeyboard.ime.ai.EditorAiAccess
import com.qalsmartkeyboard.ime.ai.FakeAiActions
import com.qalsmartkeyboard.ime.emoji.EmojiDataTest
import com.qalsmartkeyboard.ime.emoji.EmojiLoader
import com.qalsmartkeyboard.ime.emoji.EmojiPanelView
import com.qalsmartkeyboard.ime.theme.ThemeCatalog
import com.qalsmartkeyboard.ime.toolbar.Tool
import com.qalsmartkeyboard.ime.toolbar.calendarNames
import com.qalsmartkeyboard.ime.toolbar.converterModeLabel
import com.qalsmartkeyboard.ime.toolbar.toolLabel
import com.qalsmartkeyboard.ime.utilities.ConverterMode
import com.qalsmartkeyboard.ime.theme.ThemeNames
import com.qalsmartkeyboard.ime.voice.VoiceLanguages
import com.qalsmartkeyboard.ime.wordart.WordArtCatalog
import com.qalsmartkeyboard.ime.wordart.WordArtStyleNames
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * ADR 0032: the keyboard's bar, keys and emoji panel build and draw with the am, ti and om resources (no missing
 * resource, no bad format argument), and the labels the pure code uses follow the UI language.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UiLocalisationSmokeTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @After fun resetLabels() = KeyboardLabels.reset()

    private class Sink : TextSink {
        override fun apply(update: ComposerUpdate) {}
        override fun sendEnterKey() {}
        override fun performEditorAction(actionId: Int) {}
        override fun wantsAutoCapital(capsFlags: Int) = false
    }

    private fun View.measureAndDraw(widthPx: Int) {
        measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        layout(0, 0, measuredWidth, maxOf(1, measuredHeight))
        draw(Canvas(Bitmap.createBitmap(widthPx, maxOf(1, measuredHeight), Bitmap.Config.ARGB_8888)))
    }

    /** Builds and draws the bar, the keys and the emoji panel; returns every spoken key description. */
    private fun renderKeyboard(): List<String> {
        val c = ctx
        KeyboardLabels.load(c)
        val w = (411 * c.resources.displayMetrics.density).toInt()
        val actions = FakeAiActions()
        val ed = FakeEditor("Hello there.")
        val adapter = InputConnectionAdapter { ed }.also { it.startSession(false, 12, 12) }
        val strip = CandidateStripView(c)
        val ai = AiToolbarView(c)
        ai.controller = AiToolbarController(actions, EditorAiAccess(adapter) { adapter.finishComposition() }, { it() }, {}, onChanged = { ai.render() })
            .also { it.onFieldStarted(InputProfile.DEFAULT, "com.example.chat") }
        strip.setModes(listOf(LayoutKind.PHONETIC, LayoutKind.FIDEL, LayoutKind.ABC), LayoutKind.PHONETIC)
        KeyboardBar(c, strip, ai).measureAndDraw(w)

        val controller = KeyboardController(Sink(), prefs = InMemoryModePreferences(), emojiLoader = EmojiLoader(EmojiDataTest.assets)) {}
        controller.startField(InputProfile.from(T.TYPE_CLASS_TEXT, 0, false, 0))
        val surface = KeyboardSurface(c).also { it.controller = controller }
        surface.measureAndDraw(w)
        val spoken = controller.layout.rows.flatten().filter { !it.isSpacer }.map { surface.spokenFor(it) }

        controller.emoji.open(true)
        EmojiPanelView(c).also { it.controller = controller; it.heightProvider = { (250 * c.resources.displayMetrics.density).toInt() } }.measureAndDraw(w)
        return spoken
    }

    private fun checkLocale(amharicName: String) {
        val c = ctx
        val spoken = renderKeyboard()
        val l = KeyboardLabels.current
        assertEquals(amharicName, l.languageAm)
        assertNotEquals(KeyboardLabels.ENGLISH.keyDelete, l.keyDelete)
        assertTrue(spoken.toString(), l.keyDelete in spoken)
        assertTrue(spoken.toString(), spoken.none { it.isBlank() })
        // Every theme and Word Art style has a name in this language, and every format string formats.
        ThemeCatalog.all.forEach { assertTrue(it.id, ThemeNames.name(c, it).isNotBlank()) }
        WordArtCatalog.all.forEach { assertTrue(it.id, WordArtStyleNames.label(c, it).isNotBlank()) }
        c.getString(R.string.ai_reply_option, 1, 3, "x")
        c.getString(R.string.wordart_row_disabled, "a", "b", "c")
        c.getString(R.string.kb_key_vowel_active, "ሀ", 2)
        checkUtilityStrings()
        checkChatStrings()
        // The platform names other voice languages in the UI language too.
        assertNotEquals("French (France)", VoiceLanguages.displayName("fr-FR"))
    }

    /** T8.3: the utilities' tiles, month arrays and format strings resolve in this language. */
    private fun checkUtilityStrings() {
        val c = ctx
        for (t in listOf(Tool.CALCULATOR, Tool.UNIT_CONVERTER, Tool.CURRENCY, Tool.CALENDAR, Tool.QR, Tool.DRAW)) {
            assertTrue("$t", toolLabel(c, t).isNotBlank())
        }
        val names = calendarNames(c)
        assertEquals(13, names.ethiopianMonths.size)
        assertEquals(12, names.gregorianMonths.size)
        assertTrue((names.ethiopianMonths + names.gregorianMonths + listOf(names.ethiopianEra, names.gregorianEra)).all { it.isNotBlank() })
        assertEquals(13, names.ethiopianMonths.toSet().size)
        c.getString(R.string.calc_expression_description, "1+1")
        c.getString(R.string.calc_result_description, "2")
        c.getString(R.string.conv_from, "km")
        c.getString(R.string.conv_to, "mi")
        c.getString(R.string.conv_rates_as_of, "2024-09-11")
        c.getString(R.string.conv_rates_stale, "2024-09-11")
        c.getString(R.string.cal_next, "x")
        c.getString(R.string.cal_previous, "x")
        c.getString(R.string.cal_date_description, "a", "b")
        ConverterMode.entries.forEach { assertTrue("$it", converterModeLabel(c, it).isNotBlank()) }
        checkDrawStrings()
    }

    /** T8.5: the Ask AI chat's strings resolve and format in this language, differ from the English ones, and the panel draws. */
    private fun checkChatStrings() {
        val c = ctx
        val ids = R.string::class.java.fields.filter { it.name.startsWith("chat_") || it.name == "tool_ask_ai" }
        assertTrue(ids.size >= 30)
        for (f in ids) assertTrue(f.name, c.getString(f.getInt(null)).isNotBlank())
        c.getString(R.string.chat_input_spoken, "x")
        c.getString(R.string.chat_reply_in_desc, "x")
        c.getString(R.string.chat_message_you_desc, "x")
        c.getString(R.string.chat_message_ai_desc, "x")
        for (s in ChatStarter.entries) {
            assertTrue("$s", AiMessages.starter(c, s).isNotBlank() && AiMessages.starterPrefill(c, s).isNotBlank())
            assertNotEquals("$s is translated", ENGLISH_STARTERS.getValue(s), AiMessages.starter(c, s))
        }
        assertNotEquals("Ask AI", c.getString(R.string.chat_title))
        assertNotEquals("Send", c.getString(R.string.chat_send))
        assertNotEquals("Ask AI", AiMessages.tool(c, AiTool.CHAT))
        val actions = FakeAiActions()
        lateinit var panel: ChatPanelView
        val ws = AiWorkspace(
            actions, FakeAiEditor("selected", "text"), FakeClipboard(), AiLanguagePrefs.InMemory(), post = { it() },
            keyboardLanguage = { "am" }, translateDefault = { "en" }, onChanged = { panel.render() },
        )
        panel = ChatPanelView(c).also { it.controller = ws.chat; it.heightProvider = { (316 * c.resources.displayMetrics.density).toInt() } }
        ws.openChat()
        panel.visibility = View.VISIBLE
        val w = (411 * c.resources.displayMetrics.density).toInt()
        panel.measureAndDraw(w) // the empty chat with its starters
        ws.chat.attachFieldText()
        ws.chat.input.set("Hi")
        ws.chat.send()
        actions.answer(0, AiOutcome.Success(AiResult("Hello", truncated = true)))
        panel.measureAndDraw(w) // a conversation, a cut-off reply, the field chip
        ws.chat.input.set("More")
        ws.chat.send()
        actions.answer(1, FakeAiActions.fail(com.qalsmartkeyboard.ime.ai.AiFailure.QuotaExceeded(null, true, true)))
        panel.measureAndDraw(w) // the quota banner with its two links
        val texts = ArrayList<String>()
        fun collect(v: View) { if (v is android.widget.TextView) texts += v.text.toString(); if (v is android.view.ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i)) }
        collect(panel)
        assertTrue(texts.toString(), texts.none { it == "Send" || it == "Insert" || it == "Reply in" })
    }

    private val ENGLISH_STARTERS = mapOf(
        ChatStarter.WRITE to "Write a message", ChatStarter.EXPLAIN_WORD to "Explain a word",
        ChatStarter.IMPROVE to "Improve my text", ChatStarter.BRAINSTORM to "Brainstorm ideas",
    )

    /** T8.4: every Draw & Sketch string resolves and its format arguments format in this language. */
    private fun checkDrawStrings() {
        val c = ctx
        val ids = R.string::class.java.fields.filter { it.name.startsWith("draw_") || it.name == "tool_draw" || it.name.startsWith("qr_insert_image") || it.name.startsWith("qr_share_image") }
        assertTrue(ids.size >= 50)
        for (f in ids) assertTrue(f.name, c.getString(f.getInt(null)).isNotBlank())
        c.getString(R.string.draw_bg_desc, "x")
        c.getString(R.string.draw_colour_desc, "x")
        c.getString(R.string.draw_gallery_item, 1, 2)
    }

    @Test @Config(qualifiers = "am-w411dp-h891dp-xxhdpi")
    fun amharicMonthNamesMatchTheCoreCalendar() {
        val am = calendarNames(ctx).ethiopianMonths
        for (m in 1..13) assertEquals("month $m", uniffi.qal_keyboard_core.ethiopianMonthName(m.toUInt()), am[m - 1])
    }

    @Test @Config(qualifiers = "am-w411dp-h891dp-xxhdpi")
    fun amharic() = checkLocale("አማርኛ")

    @Test @Config(qualifiers = "ti-w411dp-h891dp-xxhdpi")
    fun tigrinya() = checkLocale("ኣምሓርኛ")

    @Test @Config(qualifiers = "om-w411dp-h891dp-xxhdpi")
    fun oromo() = checkLocale("Afaan Amaaraa")

    @Test fun englishResourcesEqualTheBuiltInDefaults() {
        val l = KeyboardLabels.from(ctx)
        assertEquals("en", l.locale.language)
        assertEquals(KeyboardLabels.ENGLISH, l.copy(locale = KeyboardLabels.ENGLISH.locale))
        ThemeCatalog.all.forEach { assertEquals(it.name, ThemeNames.name(ctx, it)) }
        WordArtCatalog.all.forEach { assertEquals(it.label, WordArtStyleNames.label(ctx, it)) }
    }

    @Test fun everyThemeAndStyleHasAResource() {
        assertEquals(ThemeCatalog.all.map { it.id }.toSet(), ThemeNames.RES.keys)
        assertEquals(WordArtCatalog.all.map { it.id }.toSet(), WordArtStyleNames.RES.keys)
    }
}
