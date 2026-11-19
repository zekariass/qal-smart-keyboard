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
import com.qalsmartkeyboard.ime.ai.AiToolbarController
import com.qalsmartkeyboard.ime.ai.AiToolbarView
import com.qalsmartkeyboard.ime.ai.EditorAiAccess
import com.qalsmartkeyboard.ime.ai.FakeAiActions
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
 * A developer aid, not a check: renders the bar in its states, the tools and clipboard panels and the keys to PNG files
 * for a visual review (ADR 0035). Runs only when `QAL_RENDER_DIR` names an output directory.
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
            shoot(listOf(bar(theme) { s, _ -> s.panelTitle = "Clipboard" }, clips), File(out, "clipboard-${theme.id}.png"))
        }
    }
}
