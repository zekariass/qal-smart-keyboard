package com.qalsmartkeyboard.ime.ai

import android.view.View
import com.qalsmartkeyboard.ime.ComposerUpdate
import com.qalsmartkeyboard.ime.FakeEditor
import com.qalsmartkeyboard.ime.InputConnectionAdapter
import com.qalsmartkeyboard.ime.InputProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** ADR 0040: the translate panel's long language list: search field, filtered rows, the order, RTL autonyms, the height. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class TranslateLanguagesViewTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val d = ctx.resources.displayMetrics.density

    private val actions = FakeAiActions().apply { catalog = TranslationCatalogTest.SERVER }
    private val ed = FakeEditor("")
    private val adapter = InputConnectionAdapter { ed }.also { it.startSession(false, 0, 0) }
    private val list = TranslateLanguagesView(ctx)
    private val c = TranslateController(
        actions = actions,
        editor = EditorAiAccess(adapter) { adapter.finishComposition() },
        post = { it() },
        copyToClipboard = {},
        keyboardLanguage = { "en" },
        onChanged = { list.render() },
    )

    @Before fun setUp() {
        TranslationLanguages.current = TranslationCatalogTest.SERVER
        list.controller = c
        list.haptics = { false }
        list.heightProvider = { (240 * d).toInt() }
        c.onFieldStarted(InputProfile.DEFAULT, "com.example.chat")
        c.open()
    }

    @After fun tearDown() {
        TranslationLanguages.current = TranslationCatalog.BUNDLED
    }

    private fun measure(): Int {
        list.measure(View.MeasureSpec.makeMeasureSpec((360 * d).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        return list.measuredHeight
    }

    @Test fun theDestinationListShowsTheCatalogueWithTheKeyboardLanguagesFirst() {
        c.openPicker(TranslatePicker.DESTINATION)
        val labels = list.rows.map { it.first }
        assertEquals(listOf("አማርኛ", "ትግርኛ", "Afaan Oromoo", "العربية"), labels.take(4)) // current (am), then the keyboard's
        assertTrue(labels.containsAll(listOf("Français", "Español", "עברית", "简体中文", "Mum")))
        assertFalse("Latin is a source only", "Latina" in labels)
        assertEquals(setOf("ar", "he"), list.rtlRows)
        assertEquals("French", list.rowView("Français")!!.contentDescription.toString())
    }

    @Test fun theSearchFieldTakesTheKeysAndFiltersTheRows() {
        c.openPicker(TranslatePicker.SOURCE)
        assertEquals((240 * d).toInt(), measure())
        assertEquals("Search languages", list.searchLabel)
        assertEquals(View.GONE, list.searchClearView.visibility)
        list.searchView.performClick()
        assertTrue(c.searching)
        assertTrue("the list makes room for the keys", measure() <= (TranslateLanguagesView.SEARCH_HEIGHT_DP * d).toInt())
        c.search.sink.apply(ComposerUpdate(commit = "deu"))
        assertEquals(listOf("Deutsch" to false), list.rows)
        assertEquals(View.VISIBLE, list.searchClearView.visibility)
        list.searchClearView.performClick()
        assertEquals("", c.search.text)
        c.search.sink.apply(ComposerUpdate(commit = "zzz"))
        assertTrue(list.rows.isEmpty())
        c.search.sink.apply(ComposerUpdate(deleteBefore = 3))
        c.search.sink.apply(ComposerUpdate(commit = "heb"))
        list.rowView("עברית")!!.performClick()
        assertEquals("he", c.direction.manualSource)
        assertFalse(c.searching)
        assertEquals(TranslatePicker.NONE, c.picker)
    }

    @Test fun withoutAServerCatalogueTheListIsTheFiveLanguagesAsBefore() {
        actions.catalog = TranslationCatalog.BUNDLED
        TranslationLanguages.current = TranslationCatalog.BUNDLED
        c.openPicker(TranslatePicker.SOURCE)
        assertEquals(listOf("Detect language", "English", "አማርኛ", "ትግርኛ", "Afaan Oromoo", "العربية"), list.rows.map { it.first })
        assertNotNull(list.rowView("العربية"))
    }
}
