package com.qalsmartkeyboard.ime.toolbar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import com.qalsmartkeyboard.ime.CandidateStripView
import com.qalsmartkeyboard.ime.ComposerUpdate
import com.qalsmartkeyboard.ime.FakeEditor
import com.qalsmartkeyboard.ime.InputConnectionAdapter
import com.qalsmartkeyboard.ime.InputProfile
import com.qalsmartkeyboard.ime.KeyboardBar
import com.qalsmartkeyboard.ime.KeyboardController
import com.qalsmartkeyboard.ime.KeyboardSurface
import com.qalsmartkeyboard.ime.LayoutKind
import com.qalsmartkeyboard.ime.Suggestion
import com.qalsmartkeyboard.ime.SuggestionKind
import com.qalsmartkeyboard.ime.TextSink
import com.qalsmartkeyboard.ime.ai.AiAlternative
import com.qalsmartkeyboard.ime.ai.AiAvailability
import com.qalsmartkeyboard.ime.ai.AiBlock
import com.qalsmartkeyboard.ime.ai.AiFailure
import com.qalsmartkeyboard.ime.ai.AiLanguages
import com.qalsmartkeyboard.ime.ai.AiOutcome
import com.qalsmartkeyboard.ime.ai.AiResult
import com.qalsmartkeyboard.ime.ai.AiToolbarController
import com.qalsmartkeyboard.ime.ai.AiToolbarView
import com.qalsmartkeyboard.ime.ai.EditorAiAccess
import com.qalsmartkeyboard.ime.ai.FakeAiActions
import com.qalsmartkeyboard.ime.ai.TranslateController
import com.qalsmartkeyboard.ime.ai.TranslateLanguagesView
import com.qalsmartkeyboard.ime.ai.TranslatePanelView
import com.qalsmartkeyboard.ime.ai.forEditor
import com.qalsmartkeyboard.ime.ai.TranslatePicker
import com.qalsmartkeyboard.ime.theme.KeyboardTheme
import com.qalsmartkeyboard.ime.theme.ThemeCatalog
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A developer aid, not a check: renders the bar in its states, the tools and clipboard panels, the translate panel and
 * its language list (ADR 0037) and the keys to PNG files for a visual review (ADR 0035). Runs only when
 * `QAL_RENDER_DIR` names an output directory.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolbarRenderTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val d = ctx.resources.displayMetrics.density
    private val w = (411 * d).toInt()

    private class Sink : TextSink {
        override fun apply(update: ComposerUpdate) {}
        override fun sendEnterKey() {}
        override fun performEditorAction(actionId: Int) {}
        override fun wantsAutoCapital(capsFlags: Int) = false
    }

    private fun shoot(views: List<View>, out: File) {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        views.forEach { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        views.forEach { col.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }
        col.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        col.layout(0, 0, col.measuredWidth, col.measuredHeight)
        val bmp = Bitmap.createBitmap(w, maxOf(1, col.measuredHeight), Bitmap.Config.ARGB_8888)
        col.draw(Canvas(bmp))
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun bar(theme: KeyboardTheme, setup: (CandidateStripView, KeyboardBar) -> Unit): View {
        val actions = FakeAiActions()
        val ed = FakeEditor("Hello there.")
        val adapter = InputConnectionAdapter { ed }.also { it.startSession(false, 12, 12) }
        val strip = CandidateStripView(ctx).also { it.theme = theme }
        val ai = AiToolbarView(ctx).also { it.theme = theme }
        val controller = AiToolbarController(actions, EditorAiAccess(adapter) {}, { it() }, {}, onChanged = { ai.render() })
        ai.controller = controller
        controller.onFieldStarted(InputProfile.DEFAULT, "x")
        val bar = KeyboardBar(ctx, strip, ai)
        strip.setModes(listOf(LayoutKind.FIDEL, LayoutKind.PHONETIC, LayoutKind.ABC), LayoutKind.PHONETIC)
        strip.micShown = true
        setup(strip, bar)
        return bar
    }

    /** The translate panel (ADR 0037): empty, typed, working, a result (Arabic too), an error, signed out, and a list. */
    private fun translate(theme: KeyboardTheme, initial: String = "Hello there. How are you", setup: (TranslateController, FakeAiActions) -> Unit): TranslatePanelView {
        val actions = FakeAiActions()
        val ed = FakeEditor(initial)
        val adapter = InputConnectionAdapter { ed }.also { it.startSession(false, initial.length, initial.length) }
        val view = TranslatePanelView(ctx).also { it.theme = theme; it.visibility = View.VISIBLE }
        val c = TranslateController(actions, EditorAiAccess(adapter) {}, { it() }, {}, { "am" }, onChanged = { view.render() }, onInputChanged = { view.renderInput() })
        view.controller = c
        c.onFieldStarted(InputProfile.DEFAULT, "x")
        c.open()
        setup(c, actions)
        view.render()
        return view
    }

    private fun type(c: TranslateController, t: String) = c.input.sink.apply(ComposerUpdate(commit = t))

    private fun renderTranslate(theme: KeyboardTheme, keys: KeyboardSurface, out: File) {
        val active = { s: CandidateStripView, _: KeyboardBar -> s.setActiveTools(setOf(Tool.TRANSLATE)) }
        shoot(listOf(translate(theme) { _, _ -> }, bar(theme, active), keys), File(out, "translate-empty-${theme.id}.png"))
        shoot(
            listOf(
                translate(theme) { c, _ -> type(c, "ሰላም ነህ? ነገ እንገናኝ") },
                translate(theme) { c, _ -> type(c, "ሰላም ነህ?"); c.translate() },
                translate(theme) { c, a ->
                    type(c, "ሰላም ነህ? ነገ እንገናኝ")
                    c.translate()
                    a.answer(0, AiOutcome.Success(AiResult("Hi, how are you? See you tomorrow.", listOf(AiAlternative("formal", "Hello, how are you? I will see you tomorrow.")), sourceLanguage = "am")))
                },
                bar(theme, active),
            ),
            File(out, "translate-states-${theme.id}.png"),
        )
        shoot(
            listOf(
                translate(theme, initial = "") { c, a ->
                    c.chooseDestination(AiLanguages.plain("ar"))
                    type(c, "Good morning, my friend")
                    c.translate()
                    a.answer(0, AiOutcome.Success(AiResult("صباح الخير يا صديقي", language = "ar")))
                },
                translate(theme) { c, a -> type(c, "Hello"); c.translate(); a.answer(0, AiOutcome.Failure(AiFailure.Timeout)) },
                translate(theme) { _, a -> a.availability = AiAvailability(block = AiBlock.SIGNED_OUT) },
                bar(theme, active),
            ),
            File(out, "translate-more-${theme.id}.png"),
        )
        // ADR 0038: the box as an editor: a caret mid-text, a selection with the action row, a composition, Arabic.
        val clip = com.qalsmartkeyboard.ime.ai.FakeClipboard(com.qalsmartkeyboard.ime.ai.ClipAvailability.TEXT, "x").forEditor()
        val selecting = translate(theme) { c, _ ->
            type(c, "ሰላም ነህ? ነገ እንገናኝ")
            c.input.buffer.setSelection(9, 11)
        }.also {
            it.clipboard = clip
            it.inputEditor.setActions(true)
        }
        val strip = com.qalsmartkeyboard.ime.editor.EditorStrip(ctx).also {
            it.theme = theme
            it.micShown = true
            it.field.label = "Your text · 42 / 5,000"
            it.field.editor.buffer = com.qalsmartkeyboard.ime.editor.EditorBuffer().apply {
                setText("Please send me the report before Friday so I can review it.")
                setSelection(23)
                apply(ComposerUpdate(composing = "fin"))
            }
        }
        shoot(
            listOf(
                translate(theme) { c, _ -> type(c, "ሰላም ነህ? ነገ እንገናኝ"); c.input.buffer.setSelection(4) },
                selecting,
                translate(theme) { c, _ -> type(c, "Good "); c.input.buffer.apply(ComposerUpdate(composing = "ሰላ")) },
                translate(theme) { c, _ -> type(c, "مرحبا بالعالم"); c.input.buffer.setSelection(5) },
                bar(theme, active),
                strip,
                keys,
            ),
            File(out, "translate-editing-${theme.id}.png"),
        )
        val list = TranslateLanguagesView(ctx).also { it.theme = theme; it.visibility = View.VISIBLE; it.heightProvider = { keys.preferredHeightPx() } }
        translate(theme) { c, _ -> list.controller = c; c.openPicker(TranslatePicker.SOURCE) }
        list.render()
        shoot(listOf(bar(theme) { s, _ -> s.panelTitle = "Translate from" }, list), File(out, "translate-list-${theme.id}.png"))
    }

    @Test fun render() {
        val dir = System.getenv("QAL_RENDER_DIR")
        assumeTrue("set QAL_RENDER_DIR to render", !dir.isNullOrEmpty())
        val out = File(dir!!).also { it.mkdirs() }
        for (theme in listOf(ThemeCatalog.classicLight, ThemeCatalog.classicDark) + ThemeCatalog.all.filter { it.isGradient || it.hasBorder }.take(3)) {
            val controller = KeyboardController(Sink()) {}
            controller.startField(InputProfile.from(InputType.TYPE_CLASS_TEXT, 0, false, 0))
            val keys = KeyboardSurface(ctx).also { it.controller = controller; it.theme = theme }
            val tools = ToolsPanelView(ctx).also {
                it.theme = theme
                it.heightProvider = { keys.preferredHeightPx() }
                it.tiles = { ToolSet.tiles(ToolContext(InputProfile.DEFAULT, aiReady = true, aiBlocked = true, voiceOffered = true, writingModes = true)) }
                it.render()
            }
            val history = ClipboardHistory().apply { add("https://example.com/a/long/link"); add("ሰላም ነው? ነገ እንገናኝ።"); add("Meeting at 3pm") }
            val clips = ClipboardPanelView(ctx).also {
                it.theme = theme
                it.heightProvider = { keys.preferredHeightPx() }
                it.entries = { history.items() }
                it.render()
            }
            shoot(
                listOf(
                    bar(theme) { _, _ -> },
                    bar(theme) { s, _ -> s.show(listOf(Suggestion("ሰላም", SuggestionKind.TRANSLITERATION), Suggestion("ሰላማ", SuggestionKind.COMPLETION)), true) },
                    bar(theme) { _, b -> b.setAiOpen(true) },
                    bar(theme) { s, _ -> s.activate(CandidateStripView.Target.Pill) },
                    keys,
                ),
                File(out, "keys-${theme.id}.png"),
            )
            shoot(listOf(bar(theme) { s, _ -> s.panelTitle = "Tools" }, tools), File(out, "tools-${theme.id}.png"))
            renderTranslate(theme, keys, out)
            shoot(listOf(bar(theme) { s, _ -> s.panelTitle = "Clipboard" }, clips), File(out, "clipboard-${theme.id}.png"))
        }
    }
}
