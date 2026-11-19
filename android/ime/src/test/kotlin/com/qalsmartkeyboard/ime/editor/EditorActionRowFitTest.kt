package com.qalsmartkeyboard.ime.editor

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.view.View
import android.widget.TextView
import com.qalsmartkeyboard.ime.ComposerUpdate
import com.qalsmartkeyboard.ime.FakeEditor
import com.qalsmartkeyboard.ime.InputConnectionAdapter
import com.qalsmartkeyboard.ime.InputProfile
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.ai.ClipAvailability
import com.qalsmartkeyboard.ime.ai.EditorAiAccess
import com.qalsmartkeyboard.ime.ai.FakeAiActions
import com.qalsmartkeyboard.ime.ai.FakeClipboard
import com.qalsmartkeyboard.ime.ai.TranslateController
import com.qalsmartkeyboard.ime.ai.TranslatePanelView
import com.qalsmartkeyboard.ime.ai.forEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * QA fix (ADR 0041): the text field's action row (Cut, Copy, Paste, Select all; ADR 0038) always fits the field at phone
 * width in every UI language: no pill is cut by the field's edge or the translate box's clear (×) button. Each pill keeps
 * its name for TalkBack when only its icon fits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real text measurement
class EditorActionRowFitTest {
    private val app: Context = RuntimeEnvironment.getApplication()
    private val d = app.resources.displayMetrics.density
    private val widthPx = (360 * d).toInt()
    private val languages = listOf("en", "am", "ti", "om")

    private fun localized(lang: String): Context =
        app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale(lang)) })

    private fun View.measureAndLayout() {
        measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        layout(0, 0, measuredWidth, measuredHeight)
    }

    /** [v]'s bounds in the root's coordinates (the views are not attached to a window: walk the parents). */
    private fun onScreen(v: View): Rect {
        var x = 0
        var y = 0
        var cur: View = v
        while (true) {
            x += cur.left
            y += cur.top
            val p = cur.parent as? View ?: break
            x -= p.scrollX
            y -= p.scrollY
            cur = p
        }
        return Rect(x, y, x + v.width, y + v.height)
    }

    /** Every pill lies inside the row's visible area, whole, and is named; the row does not scroll. */
    private fun assertFits(field: KeyboardTextField, lang: String, ui: Context, others: List<View> = emptyList()) {
        val pills = field.actionViews
        assertEquals("$lang: Cut, Copy, Paste, Select all", 4, pills.size)
        val scroll = field.actionScrollView
        val row = onScreen(scroll)
        val names = pills.map { it.contentDescription.toString() }
        assertEquals(
            listOf(R.string.editor_cut, R.string.ai_copy, R.string.editor_paste, R.string.editor_select_all).map { ui.getString(it) },
            names,
        )
        assertTrue("$lang: the row's content fits (${scroll.getChildAt(0).width} <= ${scroll.width})", scroll.getChildAt(0).width <= scroll.width)
        for (p in pills) {
            val r = onScreen(p)
            assertTrue("$lang '${p.contentDescription}' ${r} inside $row", r.left >= row.left && r.right <= row.right)
            for (o in others.filter { it.visibility == View.VISIBLE }) {
                assertTrue("$lang '${p.contentDescription}' clear of ${o.javaClass.simpleName}", !Rect.intersects(r, onScreen(o)))
            }
            if (p.text.isNotEmpty()) {
                assertTrue(p.layout != null && p.layout.getEllipsisCount(0) == 0)
                assertTrue("$lang '${p.text}' is whole", p.width >= p.paint.measureText(p.text.toString()) + p.paddingLeft + p.paddingRight - 1)
            } else {
                assertTrue("$lang icon pill is a touch target", p.width >= (40 * d).toInt() - 1)
            }
        }
    }

    @Test fun theTranslateBoxRowFitsBesideTheClearButtonInEveryLanguage() {
        for (lang in languages) {
            val ui = localized(lang)
            val ed = FakeEditor("")
            val adapter = InputConnectionAdapter { ed }.also { it.startSession(false, 0, 0) }
            val view = TranslatePanelView(ui)
            val c = TranslateController(FakeAiActions(), EditorAiAccess(adapter) {}, { it() }, {}, { "am" }, onChanged = { view.render() }, onInputChanged = { view.renderInput() })
            view.controller = c
            view.clipboard = FakeClipboard(ClipAvailability.TEXT, "x").forEditor()
            c.onFieldStarted(InputProfile.DEFAULT, "x")
            c.open()
            c.input.sink.apply(ComposerUpdate(commit = "ሰላም ነህ? ነገ እንገናኝ"))
            c.input.buffer.setSelection(4, 7)
            view.visibility = View.VISIBLE
            view.render()
            view.inputEditor.setActions(true)
            view.measureAndLayout()
            assertEquals(View.VISIBLE, view.clearButtonView.visibility)
            assertFits(view.inputField0, lang, ui, listOf(view.clearButtonView, view.translateButtonView))
        }
    }

    @Test fun theTypingStripRowFitsBesideTheMicAndDoneInEveryLanguage() {
        for (lang in languages) {
            val ui = localized(lang)
            val strip = EditorStrip(ui).also {
                it.micShown = true
                it.field.clipboard = FakeClipboard(ClipAvailability.TEXT, "x").forEditor()
                it.field.editor.buffer = EditorBuffer().apply {
                    setText("Please send me the report before Friday.")
                    setSelection(7, 11)
                }
                it.field.editor.setActions(true)
            }
            strip.measureAndLayout()
            assertFits(strip.field, lang, ui, listOf(strip.micView, strip.doneView))
        }
    }

    @Test fun aWideFieldKeepsTheLabels() {
        val f = KeyboardTextField(app).also {
            it.clipboard = FakeClipboard(ClipAvailability.TEXT, "x").forEditor()
            it.editor.buffer = EditorBuffer().apply { setText("Hello world"); setSelection(6, 11) }
            it.editor.setActions(true)
        }
        f.measureAndLayout()
        assertEquals(KeyboardTextField.RowFit.LABELS, f.rowFit)
        assertEquals(listOf("Cut", "Copy", "Paste", "Select all"), f.actionViews.map { it.text.toString() })
    }

    @Test fun aNarrowFieldShrinksThenFallsBackToNamedIcons() {
        val f = KeyboardTextField(localized("om")).also {
            it.clipboard = FakeClipboard(ClipAvailability.TEXT, "x").forEditor()
            it.editor.buffer = EditorBuffer().apply { setText("Hello world"); setSelection(6, 11) }
            it.editor.setActions(true)
        }
        val narrow = (190 * d).toInt()
        f.measure(View.MeasureSpec.makeMeasureSpec(narrow, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        f.layout(0, 0, f.measuredWidth, f.measuredHeight)
        assertEquals(KeyboardTextField.RowFit.ICONS, f.rowFit)
        assertTrue(f.actionViews.all { it.text.isEmpty() && it.compoundDrawables[0] != null })
        assertEquals("Hunda filadhaa", (f.actionViews.last() as TextView).contentDescription.toString())
        assertTrue(f.actionScrollView.getChildAt(0).width <= f.actionScrollView.width)
        // Wider again: the labels come back.
        f.measure(View.MeasureSpec.makeMeasureSpec(widthPx * 2, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertEquals(KeyboardTextField.RowFit.LABELS, f.rowFit)
        assertTrue(f.actionViews.all { it.text.isNotEmpty() && it.compoundDrawables[0] == null })
    }
}
