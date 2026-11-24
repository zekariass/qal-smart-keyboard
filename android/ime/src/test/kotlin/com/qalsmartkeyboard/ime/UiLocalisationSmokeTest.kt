package com.qalsmartkeyboard.ime

import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.InputType as T
import android.view.View
import com.qalsmartkeyboard.ime.ai.AiToolbarController
import com.qalsmartkeyboard.ime.ai.AiToolbarView
import com.qalsmartkeyboard.ime.ai.EditorAiAccess
import com.qalsmartkeyboard.ime.ai.FakeAiActions
import com.qalsmartkeyboard.ime.emoji.EmojiDataTest
import com.qalsmartkeyboard.ime.emoji.EmojiLoader
import com.qalsmartkeyboard.ime.emoji.EmojiPanelView
import com.qalsmartkeyboard.ime.theme.ThemeCatalog
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
        // The platform names other voice languages in the UI language too.
        assertNotEquals("French (France)", VoiceLanguages.displayName("fr-FR"))
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
