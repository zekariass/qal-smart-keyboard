package com.qalsmartkeyboard.ime.toolbar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import com.qalsmartkeyboard.data.KeyboardSettings
import com.qalsmartkeyboard.ime.CandidateStripView
import com.qalsmartkeyboard.ime.CoreTestEnv
import com.qalsmartkeyboard.ime.KeyKind
import com.qalsmartkeyboard.ime.LatinLayout
import com.qalsmartkeyboard.ime.SettingsPreferences
import com.qalsmartkeyboard.ime.TypingMode
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
import com.qalsmartkeyboard.ime.ai.AiLanguagePrefs
import com.qalsmartkeyboard.ime.ai.AiPanelView
import com.qalsmartkeyboard.ime.ai.AiQuickAction
import com.qalsmartkeyboard.ime.ai.AiWorkspace
import com.qalsmartkeyboard.ime.ai.ClipAvailability
import com.qalsmartkeyboard.ime.ai.FakeAiEditor
import com.qalsmartkeyboard.ime.ai.FakeClipboard
import com.qalsmartkeyboard.ime.KeyboardInsets
import com.qalsmartkeyboard.ime.KeyboardMetrics
import com.qalsmartkeyboard.ime.PopupOverlay
import com.qalsmartkeyboard.ime.ai.AiAvailability
import com.qalsmartkeyboard.ime.ai.AiBlock
import com.qalsmartkeyboard.ime.ai.AiFailure
import com.qalsmartkeyboard.ime.ai.AiLanguages
import com.qalsmartkeyboard.ime.ai.AiOutcome
import com.qalsmartkeyboard.ime.ai.AiResult
import com.qalsmartkeyboard.ime.ai.AiTaskKind
import com.qalsmartkeyboard.ime.ai.AiToolSet
import com.qalsmartkeyboard.ime.ai.AiToolbarController
import com.qalsmartkeyboard.ime.ai.AiToolbarView
import com.qalsmartkeyboard.ime.ai.AiToolsPanelView
import com.qalsmartkeyboard.ime.ai.AiPeriod
import com.qalsmartkeyboard.ime.ai.AiQuotaHint
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
import java.util.Locale

/**
 * A developer aid, not a check: renders the bar in its states (the "AI" mark idle, active and locked, ADR 0041), the tools,
 * AI tools and clipboard panels, the translate panel and its language list (ADR 0037), the text field's action row at
 * 360 dp in Oromo, and the keys to PNG files for a visual review (ADR 0035). Runs only when `QAL_RENDER_DIR` names an
 * output directory.
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

    private fun shoot(views: List<View>, out: File, w: Int = this.w) {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        views.forEach { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        views.forEach { col.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }
        col.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        col.layout(0, 0, col.measuredWidth, col.measuredHeight)
        val bmp = Bitmap.createBitmap(w, maxOf(1, col.measuredHeight), Bitmap.Config.ARGB_8888)
        col.draw(Canvas(bmp))
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun bar(
        theme: KeyboardTheme,
        availability: AiAvailability = AiAvailability(),
        ui: android.content.Context = ctx,
        setup: (CandidateStripView, KeyboardBar) -> Unit,
    ): View {
        val actions = FakeAiActions().also { it.availability = availability }
        val ed = FakeEditor("Hello there.")
        val adapter = InputConnectionAdapter { ed }.also { it.startSession(false, 12, 12) }
        val strip = CandidateStripView(ui).also { it.theme = theme }
        val ai = AiToolbarView(ui).also { it.theme = theme }
        val controller = AiToolbarController(actions, EditorAiAccess(adapter) {}, { it() }, {}, onChanged = { ai.render() })
        ai.controller = controller
        controller.onFieldStarted(InputProfile.DEFAULT, "x")
        val bar = KeyboardBar(ui, strip, ai)
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
        shoot(listOf(translate(theme) { _, _ -> }, bar(theme, setup = active), keys), File(out, "translate-empty-${theme.id}.png"))
        shoot(
            listOf(
                translate(theme) { c, _ -> type(c, "ሰላም ነህ? ነገ እንገናኝ") },
                translate(theme) { c, _ -> type(c, "ሰላም ነህ?"); c.translate() },
                translate(theme) { c, a ->
                    type(c, "ሰላም ነህ? ነገ እንገናኝ")
                    c.translate()
                    a.answer(0, AiOutcome.Success(AiResult("Hi, how are you? See you tomorrow.", listOf(AiAlternative("formal", "Hello, how are you? I will see you tomorrow.")), sourceLanguage = "am")))
                },
                bar(theme, setup = active),
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
                bar(theme, setup = active),
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
                bar(theme, setup = active),
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

    private fun localized(lang: String): android.content.Context =
        ctx.createConfigurationContext(android.content.res.Configuration(ctx.resources.configuration).apply { setLocale(Locale(lang)) })

    /** The AI tools grid (ADR 0041) under its bar, at the keys' height. */
    private fun aiGrid(theme: KeyboardTheme, keys: KeyboardSurface, availability: AiAvailability, ui: android.content.Context = ctx): AiToolsPanelView =
        AiToolsPanelView(ui).also {
            it.theme = theme
            it.heightProvider = { keys.preferredHeightPx() }
            it.tiles = { AiToolSet.tiles(availability) }
            it.availability = { availability }
            it.render()
        }

    private fun renderAi(theme: KeyboardTheme, keys: KeyboardSurface, out: File) {
        val title = { s: CandidateStripView, _: KeyboardBar -> s.panelTitle = "AI tools" }
        shoot(listOf(bar(theme, setup = title), aiGrid(theme, keys, AiAvailability())), File(out, "ai-grid-${theme.id}.png"))
        val signedOut = AiAvailability(block = AiBlock.SIGNED_OUT)
        shoot(listOf(bar(theme, signedOut, setup = title), aiGrid(theme, keys, signedOut)), File(out, "ai-grid-signed-out-${theme.id}.png"))
        val plan = AiAvailability(
            entitled = setOf(AiTaskKind.TRANSLATE, AiTaskKind.GRAMMAR, AiTaskKind.REWRITE),
            quota = mapOf(AiTaskKind.TRANSLATE to AiQuotaHint(3, 10, AiPeriod.MONTHLY, null)),
        )
        shoot(listOf(bar(theme, plan, setup = title), aiGrid(theme, keys, plan)), File(out, "ai-grid-plan-${theme.id}.png"))
        if (theme === ThemeCatalog.classicLight || theme === ThemeCatalog.classicDark) {
            val narrow = (360 * d).toInt()
            for (lang in listOf("am", "ti", "om")) {
                val ui = localized(lang)
                val offline = AiAvailability(block = AiBlock.OFFLINE)
                shoot(
                    listOf(bar(theme, offline, ui) { s, _ -> s.panelTitle = ui.getString(com.qalsmartkeyboard.ime.R.string.top_bar_ai_open) }, aiGrid(theme, keys, offline, ui)),
                    File(out, "ai-grid-$lang-360-${theme.id}.png"),
                    narrow,
                )
            }
        }
    }

    /** The text field's action row at 360 dp in Oromo (the longest labels): the translate box and a typing strip. */
    private fun renderEditorRow(theme: KeyboardTheme, out: File) {
        val narrow = (360 * d).toInt()
        val ui = localized("om")
        val clip = com.qalsmartkeyboard.ime.ai.FakeClipboard(com.qalsmartkeyboard.ime.ai.ClipAvailability.TEXT, "x").forEditor()
        val ed = FakeEditor("")
        val adapter = InputConnectionAdapter { ed }.also { it.startSession(false, 0, 0) }
        val view = TranslatePanelView(ui).also { it.theme = theme; it.visibility = View.VISIBLE; it.clipboard = clip }
        val c = TranslateController(FakeAiActions(), EditorAiAccess(adapter) {}, { it() }, {}, { "om" }, onChanged = { view.render() }, onInputChanged = { view.renderInput() })
        view.controller = c
        c.onFieldStarted(InputProfile.DEFAULT, "x")
        c.open()
        type(c, "Akkam jirta? Boru wal argina")
        c.input.buffer.setSelection(6, 12)
        view.render()
        view.inputEditor.setActions(true)
        val strip = com.qalsmartkeyboard.ime.editor.EditorStrip(ui).also {
            it.theme = theme
            it.micShown = true
            it.field.clipboard = clip
            it.field.editor.buffer = com.qalsmartkeyboard.ime.editor.EditorBuffer().apply {
                setText("Please send me the report before Friday.")
                setSelection(7, 11)
            }
            it.field.editor.setActions(true)
        }
        shoot(listOf(view, strip), File(out, "editor-row-om-360-${theme.id}.png"), narrow)
    }

    /**
     * ADR 0043: keys with Ethiopic numerals (am Phonetic letters and ?123, Fidel ?123), the Latin arrangements, the number
     * row off, the long-press Fidel grid, and the Layout panel (Latin and Fidel) under the bar with its title.
     */
    private fun renderLayouts(theme: KeyboardTheme, out: File) {
        fun session(s: KeyboardSettings, mode: TypingMode? = null): CoreTestEnv.Session {
            val p = SettingsPreferences({ s })
            return CoreTestEnv.Session(languages = p, prefs = p).also { if (mode != null) it.mode(mode) }
        }
        fun keys(sess: CoreTestEnv.Session) = KeyboardSurface(ctx).also { it.controller = sess.c; it.theme = theme }
        val base = KeyboardSettings.DEFAULT
        val am = session(base)
        val amSym = session(base).also { it.tap(it.key(KeyKind.TO_SYMBOLS)) }
        val fidelSym = session(base, TypingMode("am", LayoutKind.FIDEL)).also { it.tap(it.key(KeyKind.TO_SYMBOLS)) }
        shoot(listOf(bar(theme) { _, _ -> }, keys(am), keys(amSym), keys(fidelSym)), File(out, "numerals-${theme.id}.png"))
        val latin = LatinLayout.entries.filter { it != LatinLayout.QWERTY }.map { l ->
            keys(session(base.copy(latinLayouts = mapOf("en" to l.name, "om" to l.name)), TypingMode(if (l == LatinLayout.DVORAK) "om" else "en", if (l == LatinLayout.DVORAK) LayoutKind.QUBEE else LayoutKind.ENGLISH)))
        }
        shoot(latin, File(out, "arrangements-${theme.id}.png"))
        val noRow = session(base.copy(numberRow = false, latinLayouts = mapOf("am" to "AZERTY")))
        val longPress = session(base.copy(fidelLayouts = mapOf("am" to "LONG_PRESS")), TypingMode("am", LayoutKind.FIDEL))
        shoot(listOf(keys(noRow), keys(longPress)), File(out, "numberrow-longpress-${theme.id}.png"))
        val panels = listOf(am, session(base, TypingMode("am", LayoutKind.FIDEL))).flatMap { sess ->
            val sizer = keys(sess)
            val panel = LayoutPanelView(ctx).also {
                it.theme = theme
                it.heightProvider = { sizer.preferredHeightPx() }
                it.options = { sess.c.layoutOptions() }
                it.render()
            }
            listOf(bar(theme) { st, _ -> st.panelTitle = "Layout · አማርኛ" }, panel)
        }
        shoot(panels, File(out, "layout-panel-${theme.id}.png"))
        val tools = ToolsPanelView(ctx).also {
            it.theme = theme
            it.heightProvider = { keys(am).preferredHeightPx() }
            it.tiles = { ToolSet.tiles(ToolContext(InputProfile.DEFAULT, aiReady = true, voiceOffered = true, writingModes = true, layouts = true)) }
            it.render()
        }
        shoot(listOf(bar(theme) { st, _ -> st.panelTitle = "Tools" }, tools), File(out, "tools-layout-${theme.id}.png"))
    }

    /**
     * ADR 0044 at 360 and 411 dp: am Phonetic with Shift (the q key shows ቕ), the digit row with Western digits and the
     * Ethiopic numerals in the corner and in the long-press popup, then the AI tools grid, AI Assist (compose and result)
     * at the AI height next to the keys at theirs, and the quick actions' review with every button visible.
     */
    private fun renderR3(theme: KeyboardTheme, out: File) {
        for ((wdp, hdp) in listOf(360 to 780, 411 to 891)) {
            val width = (wdp * d).toInt()
            val p = SettingsPreferences({ KeyboardSettings.DEFAULT })
            // With the phonetic key hints, as the service hands them over with the engine.
            val hints = com.qalsmartkeyboard.ime.CoreComposer.deriveHints(CoreTestEnv.freshEngine())
            fun hinted() = CoreTestEnv.Session(languages = p, prefs = p).also { it.c.composer = com.qalsmartkeyboard.ime.CoreComposer(CoreTestEnv.engine, hints) }
            val shifted = hinted().also { it.tap(it.key(KeyKind.SHIFT)) }
            val plain = hinted()
            fun keys(sess: CoreTestEnv.Session) = KeyboardSurface(ctx).also { it.controller = sess.c; it.theme = theme }
            fun popup(index: Int): View {
                val k = plain.c.layout.rows.first()[index]
                val cell = 48f * d
                return PopupOverlay(ctx).also {
                    it.theme = theme
                    it.minimumHeight = (60 * d).toInt()
                    val cols = minOf(k.popupColumns, k.popup.size)
                    val left = ((width / 10f) * index + width / 20f - cell * cols / 2).coerceIn(4 * d, width - cell * cols - 4 * d)
                    it.show(k.popup, cols, cell, cell, left, 6 * d, -1)
                }
            }
            shoot(listOf(bar(theme) { _, _ -> }, keys(shifted), popup(0), popup(9), keys(plain)), File(out, "r3-keys-$wdp-${theme.id}.png"), width)

            val sizer = keys(plain)
            val inset = KeyboardInsets.padding(intArrayOf(0, 0, 0, (24 * d).toInt()), intArrayOf(0, 0, 0, 0), density = d).bottom
            val ai = KeyboardMetrics.aiPanelHeightInWindowPx(sizer.preferredHeightPx(), d, width, (hdp * d).toInt(), inset)
            val grid = AiToolsPanelView(ctx).also {
                it.theme = theme
                it.heightProvider = { ai }
                it.tiles = { AiToolSet.tiles(AiAvailability()) }
                it.availability = { AiAvailability() }
                it.render()
            }
            fun assist(result: Boolean): View {
                val actions = FakeAiActions()
                val panel = AiPanelView(ctx).also { it.theme = theme; it.heightProvider = { ai } }
                val ws = AiWorkspace(
                    actions, FakeAiEditor("teh cat sat on the mat", "field"), FakeClipboard(ClipAvailability.NONE, null), AiLanguagePrefs.InMemory(),
                    post = { it() }, keyboardLanguage = { "en" }, translateDefault = { "en" }, onChanged = { panel.render() },
                )
                panel.workspace = ws
                panel.visibility = View.VISIBLE
                ws.openAssist()
                if (result) {
                    fun views(v: View): List<View> = if (v is android.view.ViewGroup) listOf(v) + (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else listOf(v)
                    views(panel).filterIsInstance<android.widget.TextView>().first { it.text.toString() == "Rewrite" }.performClick()
                    (panel.footerRow!!.getChildAt(0) as android.widget.TextView).performClick()
                    actions.answer(0, FakeAiActions.ok("The cat sat on the mat. ".repeat(12)))
                }
                return panel
            }
            val title = { t: String -> { st: CandidateStripView, _: KeyboardBar -> st.panelTitle = t } }
            shoot(listOf(bar(theme, setup = title("AI tools")), grid), File(out, "r3-ai-grid-$wdp-${theme.id}.png"), width)
            shoot(listOf(bar(theme, setup = title("AI Assist")), assist(false)), File(out, "r3-ai-assist-$wdp-${theme.id}.png"), width)
            shoot(listOf(bar(theme, setup = title("AI Assist")), assist(true)), File(out, "r3-ai-result-$wdp-${theme.id}.png"), width)
            shoot(listOf(bar(theme) { _, _ -> }, keys(plain)), File(out, "r3-keys-height-$wdp-${theme.id}.png"), width)
            for (lang in listOf("en", "am", "om")) {
                val ui = localized(lang)
                val actions = FakeAiActions()
                val ed = FakeEditor("Hello. How are you")
                val adapter = InputConnectionAdapter { ed }.also { it.startSession(false, ed.text.length, ed.text.length) }
                val strip = CandidateStripView(ui).also { it.theme = theme }
                val quick = AiToolbarView(ui).also { it.theme = theme }
                val c = AiToolbarController(actions, EditorAiAccess(adapter) {}, { it() }, {}, onChanged = { quick.render() })
                quick.controller = c
                c.onFieldStarted(InputProfile.DEFAULT, "x")
                c.run(AiQuickAction.REWRITE)
                actions.answer(0, AiOutcome.Success(AiResult("እንዴት ነህ? ዛሬ ምን ታደርጋለህ?", listOf(AiAlternative("formal", "እንዴት ነዎት?")))))
                // As in the input view: the quick actions' panel above the bar, the keys below.
                val bar = KeyboardBar(ui, strip, quick)
                shoot(listOf(quick, bar, keys(plain)), File(out, "r3-quick-result-$lang-$wdp-${theme.id}.png"), width)
            }
        }
    }

    @Test fun render() {
        val dir = System.getenv("QAL_RENDER_DIR")
        assumeTrue("set QAL_RENDER_DIR to render", !dir.isNullOrEmpty())
        val out = File(dir!!).also { it.mkdirs() }
        // QAL_RENDER_ONLY=r3 renders only the ADR 0044 set (light and dark).
        if (System.getenv("QAL_RENDER_ONLY") == "r3") {
            for (theme in listOf(ThemeCatalog.classicLight, ThemeCatalog.classicDark)) renderR3(theme, out)
            return
        }
        // QAL_RENDER_NEW=1 renders the 20 gallery themes (ADR 0042) as well: keys, tools and clipboard (no translate set).
        val gallery = if (System.getenv("QAL_RENDER_NEW") == "1") ThemeCatalog.all.drop(20) else emptyList()
        val base = listOf(ThemeCatalog.classicLight, ThemeCatalog.classicDark) + ThemeCatalog.all.take(20).filter { it.isGradient || it.hasBorder }.take(3)
        for (theme in base + gallery) {
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
                    bar(theme) { s, _ -> s.setActiveTools(setOf(Tool.AI)) },
                    bar(theme, AiAvailability(block = AiBlock.SIGNED_OUT)) { _, _ -> },
                    bar(theme) { s, _ -> s.activate(CandidateStripView.Target.Pill) },
                    keys,
                ),
                File(out, "keys-${theme.id}.png"),
            )
            shoot(listOf(bar(theme) { s, _ -> s.panelTitle = "Tools" }, tools), File(out, "tools-${theme.id}.png"))
            if (theme !in gallery) {
                renderAi(theme, keys, out)
                renderEditorRow(theme, out)
                renderTranslate(theme, keys, out)
            }
            shoot(listOf(bar(theme) { s, _ -> s.panelTitle = "Clipboard" }, clips), File(out, "clipboard-${theme.id}.png"))
            if (theme === ThemeCatalog.classicLight || theme === ThemeCatalog.classicDark) {
                renderLayouts(theme, out)
                renderR3(theme, out)
            }
        }
    }
}
