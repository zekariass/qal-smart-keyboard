package com.qalsmartkeyboard.ime.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.qalsmartkeyboard.ime.ComposerUpdate
import com.qalsmartkeyboard.ime.FakeEditor
import com.qalsmartkeyboard.ime.InputConnectionAdapter
import com.qalsmartkeyboard.ime.InputProfile
import com.qalsmartkeyboard.ime.theme.ThemeCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The translate panel and its language list (ADR 0037): what each state shows, the controls' roles and names, every theme. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class TranslatePanelViewTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val d = ctx.resources.displayMetrics.density
    private val widthPx = (360 * d).toInt()

    private inner class H(initial: String = "", val actions: FakeAiActions = FakeAiActions(), keyboard: String = "en") {
        val ed = FakeEditor(initial)
        val adapter = InputConnectionAdapter { ed }.also { it.startSession(false, initial.length, initial.length) }
        val view = TranslatePanelView(ctx)
        val list = TranslateLanguagesView(ctx)
        val links = ArrayList<AiLink>()
        val applied = ArrayList<AiApplied>()
        var closed = 0
        val c = TranslateController(
            actions = actions,
            editor = EditorAiAccess(adapter) { adapter.finishComposition() },
            post = { it() },
            copyToClipboard = {},
            keyboardLanguage = { keyboard },
            onChanged = {
                view.render()
                list.render()
            },
            onInputChanged = { view.renderInput() },
        )

        init {
            view.controller = c
            list.controller = c
            view.onLink = { links += it }
            view.onClose = { closed++ }
            view.onApplied = { applied += it }
            view.haptics = { false }
            list.haptics = { false }
            c.onFieldStarted(InputProfile.DEFAULT, "com.example.chat")
        }

        fun open(): H {
            c.open()
            view.visibility = View.VISIBLE
            layout()
            return this
        }

        fun type(t: String) = c.input.sink.apply(ComposerUpdate(commit = t))

        fun layout(): Int {
            view.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            val bmp = Bitmap.createBitmap(widthPx, maxOf(1, view.measuredHeight), Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
            return view.measuredHeight
        }

        fun texts(v: View = view): List<TextView> = when (v) {
            is TextView -> if (v.visibility == View.VISIBLE) listOf(v) else emptyList()
            is ViewGroup -> if (v.visibility == View.VISIBLE) (0 until v.childCount).flatMap { texts(v.getChildAt(it)) } else emptyList()
            else -> emptyList()
        }
        fun outputTexts() = texts(view.outputView).map { it.text.toString() }
        fun button(label: String): TextView = texts(view.outputView).first { it.text.toString() == label }
    }

    private val button = Button::class.java.name

    private fun roleOf(v: View) = v.createAccessibilityNodeInfo().className

    @Test fun theHeaderShowsBothSidesAndEveryControlIsANamedButton() {
        val h = H(keyboard = "am").open()
        assertEquals("Translate from Detect · Amharic. Change", h.view.sourceChipView.contentDescription.toString())
        assertEquals("Translate into English. Change", h.view.destinationChipView.contentDescription.toString())
        assertEquals("Swap languages", h.view.swapButtonView.contentDescription.toString())
        assertEquals("Translation settings", h.view.settingsButtonView.contentDescription.toString())
        assertEquals("Close translation", h.view.closeButtonView.contentDescription.toString())
        for (v in listOf(h.view.sourceChipView, h.view.destinationChipView, h.view.swapButtonView, h.view.settingsButtonView, h.view.closeButtonView, h.view.translateButtonView)) {
            assertEquals(button, roleOf(v))
            assertTrue(v.isClickable)
        }
        // Visible labels: the Detect caption and the two languages.
        val shown = h.texts().map { it.text.toString() }
        assertTrue(shown.containsAll(listOf("Detect", "Amharic", "English")))
        assertTrue(h.actions.submitted.isEmpty())
    }

    @Test fun theBoxShowsTheHintThenTheTextWithAClearButton() {
        val h = H("Hello there. How are you").open()
        assertEquals("Type to translate", h.view.inputTextView.text.toString())
        assertEquals(View.GONE, h.view.clearButtonView.visibility)
        assertEquals("the last sentence is offered while empty", View.VISIBLE, h.view.sentenceChipView.visibility)
        assertFalse(h.view.translateButtonView.isEnabled)
        h.type("Selam")
        assertEquals("Selam▏", h.view.inputTextView.text.toString())
        assertEquals(View.VISIBLE, h.view.clearButtonView.visibility)
        assertEquals(View.GONE, h.view.sentenceChipView.visibility)
        assertTrue(h.view.translateButtonView.isEnabled)
        assertEquals("Translate to Amharic", h.view.translateButtonView.contentDescription.toString())
        h.view.clearButtonView.performClick()
        assertEquals("", h.c.input.text)
        assertEquals("Type to translate", h.view.inputTextView.text.toString())
    }

    @Test fun useLastSentenceFillsTheBoxAndLabelsIt() {
        val h = H("One. Two three").open()
        h.view.sentenceChipView.performClick()
        assertEquals("Two three", h.c.input.text)
        assertEquals("Last sentence", h.view.inputLabelView.text.toString())
        assertEquals(View.VISIBLE, h.view.inputLabelView.visibility)
    }

    @Test fun translateShowsWorkingThenTheResultWithCopyAndInsert() {
        val h = H("Hi").open()
        h.type("Good morning")
        h.view.translateButtonView.performClick()
        assertTrue(h.c.step is TranslateStep.Working)
        assertTrue(h.outputTexts().contains("Cancel"))
        assertTrue(h.outputTexts().contains("Translating to Amharic…"))
        h.actions.answer(0, AiOutcome.Success(AiResult("እንደምን አደርክ", sourceLanguage = "en")))
        h.layout()
        assertEquals("እንደምን አደርክ", h.view.resultView!!.text.toString())
        assertTrue(h.outputTexts().containsAll(listOf("English → Amharic", "Copy", "Insert")))
        h.button("Copy").performClick()
        assertTrue("Copy turns into Copied", h.outputTexts().contains("Copied"))
        h.button("Insert").performClick()
        assertEquals(listOf(AiApplied.INSERTED), h.applied)
        assertEquals("Hi እንደምን አደርክ", h.ed.text.toString())
        assertNull("the output is cleared for the next one", h.view.resultView)
    }

    @Test fun cancelReturnsToTheBox() {
        val h = H().open()
        h.type("Hello")
        h.view.translateButtonView.performClick()
        h.button("Cancel").performClick()
        assertTrue(h.actions.submitted.single().cancelled)
        assertTrue(h.outputTexts().isEmpty())
        assertEquals("Hello", h.c.input.text)
    }

    @Test fun aSelectionReadsReplace() {
        val h = H("abc def")
        h.ed.selStart = 4
        h.ed.selEnd = 7
        h.open()
        assertEquals("Selected text", h.view.inputLabelView.text.toString())
        h.view.translateButtonView.performClick()
        h.actions.answer(0, FakeAiActions.ok("DEF"))
        h.button("Replace").performClick()
        assertEquals(listOf(AiApplied.REPLACED), h.applied)
        assertEquals("abc DEF", h.ed.text.toString())
    }

    @Test fun arabicResultsAreRightToLeft() {
        val h = H().open()
        h.c.chooseDestination(AiLanguages.plain("ar"))
        h.type("Hello")
        h.view.translateButtonView.performClick()
        h.actions.answer(0, AiOutcome.Success(AiResult("مرحبا", language = "ar")))
        assertTrue(h.view.resultRtl)
        h.c.chooseDestination(AiLanguages.plain("am"))
        h.view.translateButtonView.performClick()
        h.actions.answer(1, FakeAiActions.ok("ሰላም"))
        assertFalse(h.view.resultRtl)
    }

    @Test fun errorsShowTheMessageTheLinkAndRetryWhereItHelps() {
        val h = H().open()
        h.type("Hello")
        h.view.translateButtonView.performClick()
        h.actions.answer(0, FakeAiActions.fail(AiFailure.Timeout))
        assertTrue(h.outputTexts().contains("That took too long. Try again."))
        h.button("Retry").performClick()
        assertEquals(h.actions.submitted[0].request.requestId, h.actions.submitted[1].request.requestId)
        h.actions.answer(1, FakeAiActions.fail(AiFailure.QuotaExceeded(null, true)))
        assertFalse("no Retry for a used-up allowance", h.outputTexts().contains("Retry"))
        h.button("See plans").performClick()
        assertEquals(listOf(AiLink.UPGRADE), h.links)
    }

    @Test fun signedOutIsOneLineWithItsLinkBeforeAnythingIsSent() {
        val a = FakeAiActions().apply { availability = AiAvailability(block = AiBlock.SIGNED_OUT) }
        val h = H(actions = a).open()
        assertEquals(listOf("Sign in to use AI", "Sign in"), h.outputTexts())
        h.button("Sign in").performClick()
        assertEquals(listOf(AiLink.SIGN_IN), h.links)
        h.type("Hello")
        assertFalse(h.view.translateButtonView.isEnabled)
        assertTrue(a.submitted.isEmpty())
    }

    @Test fun settingsAndCloseUseTheirLinks() {
        val h = H().open()
        h.view.settingsButtonView.performClick()
        assertEquals(listOf(AiLink.TRANSLATION), h.links)
        h.view.closeButtonView.performClick()
        assertEquals(1, h.closed)
    }

    @Test fun theSourceListHasDetectAndSelfNamesWithTheCurrentChecked() {
        val h = H(keyboard = "am").open()
        h.view.sourceChipView.performClick()
        assertEquals(TranslatePicker.SOURCE, h.c.picker)
        assertEquals(
            listOf("Detect language" to true, "አማርኛ" to false, "ትግርኛ" to false, "Afaan Oromoo" to false, "English" to false, "العربية" to false),
            h.list.rows,
        )
        val row = h.list.rowView("ትግርኛ")!!
        val info = row.createAccessibilityNodeInfo()
        assertEquals(button, info.className)
        assertTrue(info.isCheckable)
        assertFalse(info.isChecked)
        assertEquals("Tigrinya", row.contentDescription.toString())
        row.performClick()
        assertEquals(TranslatePicker.NONE, h.c.picker)
        assertEquals("ti", h.c.direction.manualSource)
    }

    @Test fun theDestinationListEndsWithProfiles() {
        val h = H(keyboard = "en").open()
        h.view.destinationChipView.performClick()
        assertEquals(listOf("አማርኛ", "ትግርኛ", "Afaan Oromoo", "العربية", "Mum"), h.list.rows.map { it.first })
        assertTrue(h.list.rows.first().second)
        h.list.rowView("Mum")!!.performClick()
        assertEquals("Mum", h.c.destination.label)
    }

    @Test fun thePanelStaysCompactAboveTheKeys() {
        val h = H().open()
        val idle = h.layout()
        assertTrue("idle ${idle / d} dp", idle / d <= 120)
        h.type("A long text ".repeat(40))
        h.view.translateButtonView.performClick()
        h.actions.answer(0, FakeAiActions.ok("ረጅም ጽሑፍ ".repeat(80), AiAlternative("formal", "x")))
        val result = h.layout()
        assertTrue("with a result ${result / d} dp", result / d <= 330)
    }

    @Test fun everyThemeDrawsEveryState() {
        for (theme in ThemeCatalog.all) {
            val h = H("abc").open()
            h.view.theme = theme
            h.list.theme = theme
            h.layout()
            h.type("Hello")
            h.view.translateButtonView.performClick()
            h.layout()
            h.actions.answer(0, FakeAiActions.ok("ሰላም"))
            h.layout()
            h.c.openPicker(TranslatePicker.DESTINATION)
            h.list.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            h.list.layout(0, 0, h.list.measuredWidth, h.list.measuredHeight)
            h.list.draw(Canvas(Bitmap.createBitmap(widthPx, h.list.measuredHeight, Bitmap.Config.ARGB_8888)))
            assertNotNull(theme.id, h.list.rowView("አማርኛ"))
        }
    }
}
