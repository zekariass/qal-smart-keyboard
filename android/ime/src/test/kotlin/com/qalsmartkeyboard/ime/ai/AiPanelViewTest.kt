package com.qalsmartkeyboard.ime.ai

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.text.InputType
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
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

/** The AI panels' views (ADR 0031) on a small phone: what each step shows, roles and labels, the clipboard rules. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-xhdpi")
class AiPanelViewTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val d = ctx.resources.displayMetrics.density

    private inner class H(selection: String? = null, clip: ClipAvailability = ClipAvailability.NONE, clipText: String? = null) {
        val actions = FakeAiActions()
        val editor = FakeAiEditor(selection, "field text")
        val clipboard = FakeClipboard(clip, clipText)
        val view = AiPanelView(ctx)
        val links = ArrayList<AiLink>()
        val applied = ArrayList<AiApplied>()
        val ws = AiWorkspace(
            actions, editor, clipboard, AiLanguagePrefs.InMemory(), post = { it() },
            keyboardLanguage = { "am" }, translateDefault = { "en" }, onChanged = { view.render() },
        )

        init {
            view.workspace = ws
            view.heightProvider = { (260 * d).toInt() }
            view.onLink = { links += it }
            view.onApplied = { applied += it }
        }

        fun layout(): View {
            view.measure(View.MeasureSpec.makeMeasureSpec((360 * d).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            return view
        }

        fun texts(): List<String> = all(view).filterIsInstance<TextView>().map { it.text.toString() }
        fun click(text: String) = all(view).filterIsInstance<TextView>().first { it.text.toString() == text }.performClick()
        fun clickFooter(text: String) = view.footerRow!!.let { r -> (0 until r.childCount).map { r.getChildAt(it) as TextView }.first { it.text.toString() == text } }.performClick()
        fun footer(): List<String> = view.footerRow?.let { r -> (0 until r.childCount).map { (r.getChildAt(it) as TextView).text.toString() } }.orEmpty()
    }

    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)

    @Test fun assistOpensAtTheKeyboardHeightWithTheSelectionAndNineActions() {
        val h = H(selection = "ሰላም ነው")
        h.ws.openAssist()
        assertEquals((260 * d).toInt(), h.layout().measuredHeight)
        val t = h.texts()
        assertTrue(t.contains("AI Assist"))
        assertTrue(t.contains("Selected text"))
        assertTrue(t.contains("ሰላም ነው"))
        for (a in listOf("Translate", "Explain", "Fix grammar", "Rewrite", "Make professional", "Simplify", "Reply", "Summarise", "Format")) {
            assertTrue("chip $a", t.contains(a))
        }
        assertEquals(listOf("Translate to English"), h.footer())
    }

    @Test fun replyShowsTheIntentChipsAndFormatTheShapeChips() {
        val h = H(selection = "Can you come?")
        h.ws.openAssist()
        h.click("Reply")
        var t = h.texts()
        assertTrue(t.contains("Reply stance (optional)"))
        for (i in listOf("Agree", "Disagree politely", "Ask to clarify", "Accept invitation", "Decline politely", "Thank them")) {
            assertTrue("intent $i", t.contains(i))
        }
        h.click("Format")
        t = h.texts()
        assertTrue(t.contains("Shape into"))
        for (f in listOf("Bullet points", "Numbered list", "Paragraphs", "Report", "Meeting notes")) {
            assertTrue("format $f", t.contains(f))
        }
        assertEquals(listOf("Format text"), h.footer())
    }

    @Test fun thePasteChipShowsOnlyForTextAndASensitiveClipSaysSo() {
        val none = H()
        none.ws.openAssist()
        assertFalse(none.texts().contains("Paste from clipboard"))
        assertTrue(none.texts().contains("Type it"))

        val text = H(clip = ClipAvailability.TEXT, clipText = "copied words")
        text.ws.openAssist()
        assertTrue(text.texts().contains("Paste from clipboard"))
        assertEquals(0, text.clipboard.contentReads)
        text.click("Paste from clipboard")
        assertTrue(text.texts().contains("copied words"))
        assertTrue(text.texts().contains("From clipboard"))

        val sensitive = H(clip = ClipAvailability.SENSITIVE, clipText = "secret")
        sensitive.ws.openAssist()
        assertFalse(sensitive.texts().contains("Paste from clipboard"))
        assertTrue(sensitive.texts().any { it.startsWith("Copied item is marked sensitive") })
    }

    @Test fun grammarExplainsWhyItsOutputLanguageIsFixed() {
        val h = H(selection = "x")
        h.ws.openAssist()
        h.click("Fix grammar")
        assertTrue(h.texts().contains("Grammar keeps the language of the text."))
        assertEquals(listOf("Fix grammar"), h.footer())
    }

    @Test fun workingShowsCancelAndResultShowsReplaceInsertCopyRegenerate() {
        val h = H(selection = "teh cat")
        h.ws.openAssist()
        h.click("Rewrite")
        h.clickFooter("Rewrite") // the Run button
        assertTrue(h.ws.assist.step is AiAssistStep.Working)
        assertEquals(listOf("Cancel"), h.footer())
        h.actions.answer(0, FakeAiActions.ok("the cat"))
        assertEquals(listOf("Replace selection", "Insert after", "Copy", "Regenerate", "Edit"), h.footer())
        assertTrue(h.texts().contains("the cat"))
        h.clickFooter("Replace selection")
        assertEquals(listOf(AiApplied.REPLACED), h.applied)
    }

    @Test fun aLockedActionIsMarkedAndOffersThePlans() {
        val h = H(selection = "x")
        h.actions.availability = AiAvailability(entitled = setOf(AiTaskKind.TRANSLATE))
        h.ws.openAssist()
        val reply = all(h.view).filterIsInstance<TextView>().first { it.text.toString() == "Reply" }
        assertEquals("Reply, Premium", reply.contentDescription.toString())
        reply.performClick()
        assertEquals(listOf("Premium: unlock Reply"), h.footer())
        h.clickFooter("Premium: unlock Reply")
        assertTrue(h.actions.submitted.isEmpty())
        assertEquals(listOf("See plans", "Edit"), h.footer())
        h.clickFooter("See plans")
        assertEquals(listOf(AiLink.UPGRADE), h.links)
    }

    @Test fun buttonsAndChipsHaveTheButtonRoleAndTouchTargets() {
        val h = H(selection = "x")
        h.ws.openAssist()
        h.layout()
        val run = all(h.view).filterIsInstance<TextView>().first { it.text.toString() == "Translate to English" }
        assertEquals(Button::class.java.name, run.createAccessibilityNodeInfo().className)
        assertTrue(run.minHeight >= (48 * d).toInt())
        val chip = all(h.view).filterIsInstance<TextView>().first { it.text.toString() == "Explain" }
        assertTrue(chip.minHeight >= (40 * d).toInt())
        assertTrue(chip.createAccessibilityNodeInfo().isCheckable)
    }

    @Test fun emailComposeThenPreviewWithSubjectPlaceholdersAndInsert() {
        val h = H()
        h.ws.openEmail()
        val t = h.texts()
        for (s in listOf("E-mail", "Job application", "CV", "Cover letter", "Business message", "New", "Reply", "Improve", "What do you want to say?", "Professional", "Persuasive", "Short", "English")) assertTrue(s, t.contains(s))
        assertEquals(listOf("Generate email"), h.footer())
        h.clickFooter("Generate email")
        assertTrue(h.texts().contains("Say what the e-mail should say."))
        h.ws.email.instructions.set("ask for Friday off")
        h.clickFooter("Generate email")
        assertTrue(h.texts().contains("Writing your e-mail…"))
        assertTrue(h.texts().contains("This can take up to 30 seconds."))
        h.actions.answer(0, AiOutcome.Success(AiResult("Dear [name],\nI would like Friday off.", subject = "Day off on [date]")))
        val body = all(h.view).filterIsInstance<TextView>().first { it.text.toString().startsWith("Dear [name]") }
        val spans = (body.text as Spanned).getSpans(0, body.text.length, BackgroundColorSpan::class.java)
        assertEquals("the placeholder is highlighted", 1, spans.size)
        assertTrue(h.texts().contains("Fill in before sending: [date], [name]"))
        assertTrue(h.texts().contains("Copy subject"))
        assertEquals(listOf("Insert", "Copy", "Regenerate", "Edit text", "Edit"), h.footer())
        h.click("Copy subject")
        assertEquals(listOf("Day off on [date]"), h.clipboard.copies)
        h.clickFooter("Insert")
        assertEquals(listOf("Dear [name],\nI would like Friday off."), h.editor.inserted)
        assertEquals(listOf(AiApplied.INSERTED), h.applied)
    }

    @Test fun openFullEditorHandsTheInputsOverInMemory() {
        val h = H()
        h.ws.openEmail()
        h.ws.email.instructions.set("hand me over")
        val open = all(h.view).first { it.contentDescription?.toString() == "Open full editor" }
        open.performClick()
        assertEquals(listOf(AiLink.EMAIL_WRITER), h.links)
        assertEquals("hand me over", AiEmailHandoff.take()?.instructions)
    }

    @Test fun everyFailureHasAMessageAndLinkedOnesALabel() {
        val failures = listOf(
            AiFailure.Offline, AiFailure.Timeout, AiFailure.Busy, AiFailure.QuotaExceeded(null, true), AiFailure.RateLimited(5),
            AiFailure.NotEntitled, AiFailure.OnDeviceOnly, AiFailure.ConsentRequired(null), AiFailure.GuardrailFailed,
            AiFailure.ContentRejected, AiFailure.LanguageUnsupported, AiFailure.SignInRequired, AiFailure.AccountBlocked,
            AiFailure.UpdateRequired, AiFailure.ServiceUnavailable, AiFailure.InputTooLong("originalEmail"), AiFailure.Invalid("outputLanguage"),
            AiFailure.Invalid(null), AiFailure.Other, AiFailure.NothingToProcess, AiFailure.TooLong,
        )
        val messages = failures.map { AiMessages.failure(ctx, it) }
        assertTrue(messages.all { it.isNotBlank() })
        assertEquals(failures.size - 0, messages.size)
        assertEquals("The e-mail you received is too long for this action. Shorten it and try again.", AiMessages.failure(ctx, AiFailure.InputTooLong("originalEmail")))
        assertTrue(AiMessages.failure(ctx, AiFailure.Invalid("outputLanguage")).contains("Same as text"))
        for (f in failures) f.link?.let { assertTrue(AiMessages.link(ctx, it).isNotBlank()) }
        assertEquals("See plans", AiMessages.link(ctx, AiFailure.NotEntitled.link!!))
    }

    @Test fun theSystemClipboardHonoursTheSensitiveFlagAndReadsOnlyOnRequest() {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = SystemAiClipboard(ctx)
        cm.setPrimaryClip(ClipData.newPlainText("", "hello"))
        assertEquals(ClipAvailability.TEXT, clip.availability())
        assertEquals("hello", clip.readText())
        val secret = ClipData.newPlainText("", "123456")
        secret.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        cm.setPrimaryClip(secret)
        assertEquals(ClipAvailability.SENSITIVE, clip.availability())
        assertNull(clip.readText())
        assertTrue(clip.copy("result"))
        assertEquals(ClipAvailability.TEXT, clip.availability())
        assertNotNull(cm.primaryClip)
    }

    @Test fun emailBodyFieldsAreRecognisedConservatively() {
        val body = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        assertTrue(AiEmailHints.looksLikeEmailBody("com.google.android.gm", body, null))
        assertFalse(AiEmailHints.looksLikeEmailBody("com.google.android.gm", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT, null))
        assertFalse(AiEmailHints.looksLikeEmailBody("com.google.android.gm", InputType.TYPE_CLASS_TEXT, null))
        assertFalse(AiEmailHints.looksLikeEmailBody("com.whatsapp", body, "Message"))
        assertTrue(AiEmailHints.looksLikeEmailBody("com.example.crm", body, "Compose email"))
    }
}
