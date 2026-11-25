package com.qalsmartkeyboard.ime.ai

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.theme.KeyboardTheme
import com.qalsmartkeyboard.ime.theme.ThemeCatalog

/**
 * The AI panels (ADR 0031): AI Assist and Write Email, shown in place of the keys, a little taller than them (ADR 0044:
 * [heightProvider] gives `KeyboardMetrics.aiPanelHeightPx`, the same height as the AI tools grid, so moving between them
 * never changes the keyboard's height). A fixed header (back, title, open full editor, back to keyboard), a scrolling body and a
 * fixed footer with the step's buttons, so small phones and landscape scroll instead of cramming. Plain Views, built
 * only when a panel is first opened (nothing on the keyboard's start-up path), re-rendered on state changes (taps,
 * results), never per key. State changes are announced to accessibility services.
 *
 * User text appears only in the TextViews while the panel is open; nothing here logs.
 */
class AiPanelView(context: Context) : LinearLayout(context) {
    var workspace: AiWorkspace? = null

    /** The panel's height: the key area plus the AI extra (ADR 0044); 0 = as measured. */
    var heightProvider: () -> Int = { 0 }

    /** Open the companion app at [AiLink] (plans, sign-in, settings, the full e-mail editor). */
    var onLink: (AiLink) -> Unit = {}

    /** The result was put in the host field: the service closes the panel and confirms. */
    var onApplied: (AiApplied) -> Unit = {}

    /** "Back to keyboard". */
    var onClose: () -> Unit = {}

    var theme: KeyboardTheme = ThemeCatalog.classic(
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES,
    )
        set(value) {
            if (field === value) return
            field = value
            if (visibility == VISIBLE) render()
        }

    private val d = resources.displayMetrics.density
    private val header = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val bodyScroll = ScrollView(context).apply { isFillViewport = false }
    private val body = LinearLayout(context).apply { orientation = VERTICAL }
    private val footer = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        // Buttons that do not fit fade out at the edge, so the row reads as "scroll for more" (ADR 0044).
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength((32f * resources.displayMetrics.density).toInt())
    }

    /** The footer's sideways scroller (tests: its last button is reachable). */
    internal val footerScroll: HorizontalScrollView get() = footer

    /** Sideways scroll positions of the chip rows, kept across re-renders (keyed by row). */
    private val rowScroll = HashMap<String, Int>()
    private val rows = HashMap<String, HorizontalScrollView>()
    private var lastStepKey: String? = null

    init {
        orientation = VERTICAL
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, (52f * d).toInt()))
        bodyScroll.addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(bodyScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(footer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        body.setPadding((12f * d).toInt(), 0, (12f * d).toInt(), (8f * d).toInt())
        footer.setPadding((6f * d).toInt(), (2f * d).toInt(), (6f * d).toInt(), (4f * d).toInt())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = heightProvider()
        super.onMeasure(widthMeasureSpec, if (h > 0) MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY) else heightMeasureSpec)
    }

    /** Rebuilds the panel from the workspace's state. Main thread; called on state changes only. */
    fun render() {
        val w = workspace ?: return
        val ui = AiUi(context, theme)
        setBackgroundColor(theme.stripBg)
        rows.forEach { (k, v) -> rowScroll[k] = v.scrollX }
        rows.clear()
        val keepScroll = bodyScroll.scrollY
        header.removeAllViews()
        body.removeAllViews()
        val buttons = ArrayList<View>()
        val stepKey: String
        val announce: String?
        when (w.screen) {
            AiScreen.ASSIST -> {
                val r = renderAssist(ui, w.assist, buttons)
                stepKey = "assist:" + r.first
                announce = r.second
            }
            AiScreen.EMAIL -> {
                val r = renderEmail(ui, w.email, buttons)
                stepKey = "email:" + r.first
                announce = r.second
            }
            AiScreen.CHAT, AiScreen.CLOSED -> return // the chat has its own panel ([ChatPanelView])
        }
        footer.removeAllViews()
        footer.addView(LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; buttons.forEach { addView(it) } })
        footer.visibility = if (buttons.isEmpty()) GONE else VISIBLE
        val sameStep = stepKey == lastStepKey
        if (!sameStep) {
            rowScroll.clear()
            if (announce != null) announceForAccessibility(announce)
        }
        lastStepKey = stepKey
        val restoreY = if (sameStep) keepScroll else 0
        post {
            bodyScroll.scrollTo(0, restoreY)
            rows.forEach { (k, v) -> rowScroll[k]?.let { x -> v.scrollTo(x, 0) } }
        }
    }

    /** Called when the panel is hidden: the next open starts at the top. */
    fun reset() {
        lastStepKey = null
        rowScroll.clear()
    }

    // ------------------------------------------------------------------ shared pieces

    private fun header(ui: AiUi, title: String, onBack: () -> Unit, extra: View? = null) {
        header.addView(ui.iconButton(AiIcons.BACK, context.getString(R.string.ai_panel_back)) { onBack() })
        header.addView(
            ui.label(title, 16f, bold = true).apply {
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                ViewCompat.setAccessibilityHeading(this, true)
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        if (extra != null) header.addView(extra)
        header.addView(ui.iconButton(AiIcons.KEYBOARD, context.getString(R.string.ai_panel_close)) { onClose() })
    }

    private fun section(ui: AiUi, text: String) = ui.label(text, 12f, secondary = true, bold = true).apply {
        setPadding(0, (10f * d).toInt(), 0, (2f * d).toInt())
    }

    private fun chipRow(ui: AiUi, key: String, views: List<View>): HorizontalScrollView =
        ui.scrollRow(*views.toTypedArray()).also { rows[key] = it }

    private fun blockBanner(ui: AiUi, a: AiAvailability): View? {
        val (text, link) = when (a.block) {
            AiBlock.NONE -> return null
            AiBlock.OFFLINE -> context.getString(R.string.ai_err_offline) to null
            AiBlock.SIGNED_OUT -> context.getString(R.string.ai_status_signed_out) to AiLink.SIGN_IN
            AiBlock.UPDATE_REQUIRED -> context.getString(R.string.ai_err_update) to AiLink.UPDATE_APP
        }
        val box = ui.column(0f, 6f)
        box.addView(ui.label(text, 13f, warn = true).apply { ViewCompat.setAccessibilityLiveRegion(this, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE) })
        if (link != null) box.addView(ui.chip(AiMessages.link(context, link)) { onLink(link) })
        return box
    }

    private fun working(ui: AiUi, line: String, hint: String?, onCancel: () -> Unit, buttons: MutableList<View>) {
        val box = ui.column(0f, 24f).apply { gravity = Gravity.CENTER_HORIZONTAL }
        box.addView(ProgressBar(context).apply { isIndeterminate = true; contentDescription = line }, LayoutParams((36f * d).toInt(), (36f * d).toInt()))
        box.addView(
            ui.label(line, 15f, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, (10f * d).toInt(), 0, 0)
                ViewCompat.setAccessibilityLiveRegion(this, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
            },
        )
        if (hint != null) box.addView(ui.label(hint, 13f, secondary = true).apply { gravity = Gravity.CENTER })
        body.addView(box, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        buttons += ui.button(context.getString(R.string.ai_cancel), AiIcons.CLOSE) { onCancel() }
    }

    private fun failed(ui: AiUi, f: AiFailure, onRetry: () -> Unit, onBack: () -> Unit, buttons: MutableList<View>) {
        val msg = AiMessages.failure(context, f)
        body.addView(
            ui.label(msg, 15f).apply {
                setPadding(0, (16f * d).toInt(), 0, (8f * d).toInt())
                ViewCompat.setAccessibilityLiveRegion(this, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
            },
        )
        val link = f.link
        if (link != null) buttons += ui.button(AiMessages.link(context, link), primary = true) { onLink(link) }
        f.extraLink?.let { l -> buttons += ui.button(AiMessages.link(context, l)) { onLink(l) } }
        if (AiGate.canRetry(f)) buttons += ui.button(context.getString(R.string.ai_retry), AiIcons.REFRESH, primary = link == null) { onRetry() }
        buttons += ui.button(context.getString(R.string.ai_panel_edit_inputs), AiIcons.EDIT) { onBack() }
    }

    private fun notice(ui: AiUi, n: AiNotice) {
        val text = AiMessages.notice(context, n) ?: return
        body.addView(ui.label(text, 13f, secondary = n == AiNotice.COPIED, warn = n != AiNotice.COPIED).apply {
            setPadding(0, (6f * d).toInt(), 0, 0)
            ViewCompat.setAccessibilityLiveRegion(this, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
        })
    }

    // ------------------------------------------------------------------ AI Assist

    /** Returns the step key and the announcement for a step change. */
    private fun renderAssist(ui: AiUi, a: AiAssistController, buttons: MutableList<View>): Pair<String, String?> {
        header(ui, context.getString(R.string.ai_assist_title), onBack = { workspace?.back() })
        return when (val s = a.step) {
            AiAssistStep.Compose -> {
                assistCompose(ui, a, buttons)
                "compose" to context.getString(R.string.ai_assist_title)
            }
            is AiAssistStep.Working -> {
                val line = AiMessages.working(context, s.action, s.output)
                working(ui, line, AiMessages.waitHint(context, s.action.kind), { a.cancel() }, buttons)
                "working" to line
            }
            is AiAssistStep.Result -> {
                assistResult(ui, a, s, buttons)
                "result" to context.getString(R.string.ai_result_ready)
            }
            is AiAssistStep.Failed -> {
                failed(ui, s.failure, { a.retry() }, { a.backToEdit() }, buttons)
                "failed" to AiMessages.failure(context, s.failure)
            }
        }
    }

    private fun assistCompose(ui: AiUi, a: AiAssistController, buttons: MutableList<View>) {
        blockBanner(ui, a.availability)?.let(body::addView)
        // 1. The text to work on.
        if (a.sourceKind != AiSourceKind.NONE || !a.source.isBlank) {
            val label = context.getString(
                when (a.sourceKind) {
                    AiSourceKind.SELECTION -> R.string.ai_source_selection
                    AiSourceKind.CLIPBOARD -> R.string.ai_source_clipboard
                    else -> R.string.ai_source_typed
                },
            )
            val text = a.source.text
            val long = text.length > 160 || text.count { it == '\n' } >= 3
            body.addView(
                ui.field(
                    label, text, context.getString(R.string.ai_source_hint), AiMessages.count(a.source.length, a.source.limit), a.source.overLimit,
                    maxLines = if (a.previewExpanded) 14 else 3,
                    description = context.getString(R.string.ai_source_edit, label, a.source.length),
                ) { a.startTyping(AiAssistField.SOURCE) },
            )
            val tools = ArrayList<View>()
            if (long) tools += ui.chip(context.getString(if (a.previewExpanded) R.string.ai_show_less else R.string.ai_show_more)) { a.togglePreview() }
            tools += ui.chip(context.getString(R.string.ai_source_clear), AiIcons.CLOSE) { a.clearSource() }
            body.addView(chipRow(ui, "sourceTools", tools))
            if (a.source.overLimit) body.addView(ui.label(context.getString(R.string.ai_source_too_long, AiMessages.count(a.source.length, a.source.limit)), 13f, warn = true))
        } else {
            body.addView(ui.label(context.getString(R.string.ai_source_empty), 14f).apply { setPadding(0, (8f * d).toInt(), 0, (4f * d).toInt()) })
            val choices = ArrayList<View>()
            if (a.clip == ClipAvailability.TEXT) choices += ui.chip(context.getString(R.string.ai_paste_clipboard), AiIcons.PASTE) { a.pasteFromClipboard() }
            choices += ui.chip(context.getString(R.string.ai_type_text), AiIcons.KEYBOARD) { a.startTyping(AiAssistField.SOURCE) }
            body.addView(chipRow(ui, "sourceChoices", choices))
            if (a.clip == ClipAvailability.SENSITIVE) body.addView(ui.label(context.getString(R.string.ai_clipboard_sensitive), 13f, secondary = true))
        }
        // 2. The action.
        body.addView(section(ui, context.getString(R.string.ai_section_action)))
        val chips = AiAssistAction.entries.map { act ->
            val locked = a.isLocked(act)
            val name = AiMessages.action(context, act)
            ui.chip(
                name, AiIcons.of(act), selected = act == a.action, locked = locked,
                description = if (locked) context.getString(R.string.ai_action_locked, name) else name,
            ) { a.selectAction(act) }
        }
        body.addView(chipRow(ui, "actions", chips))
        // 3. The output language.
        val out = a.outputLanguage()
        val outRow = ui.row()
        outRow.addView(ui.label(context.getString(R.string.ai_output_label), 13f, secondary = true).apply { setPadding(0, 0, (6f * d).toInt(), 0) })
        if (a.canChooseOutput()) {
            outRow.addView(
                ui.chip(
                    AiMessages.language(context, out) + "  ▾", selected = a.outputPickerOpen,
                    description = context.getString(R.string.ai_output_change, AiMessages.language(context, out)),
                ) { a.toggleOutputPicker() },
            )
        } else {
            outRow.addView(ui.chip(context.getString(R.string.ai_same_as_text), enabled = false) {})
            outRow.addView(ui.label(context.getString(R.string.ai_output_grammar_fixed), 12f, secondary = true).apply { setPadding((6f * d).toInt(), 0, 0, 0) }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        body.addView(outRow.apply { setPadding(0, (8f * d).toInt(), 0, 0) })
        if (a.outputPickerOpen) {
            val langs = a.outputChoices().map { code ->
                val name = AiMessages.language(context, code)
                ui.chip(name, if (code == out) AiIcons.CHECK else null, selected = code == out) { a.chooseOutput(code) }
            }.toMutableList()
            // ADR 0040: Translate's list is the translation catalogue; the short list first, the rest on request.
            if (a.hasMoreOutputs()) langs += ui.chip(context.getString(R.string.ai_output_more_languages)) { a.moreOutputs() }
            body.addView(chipRow(ui, "outputs", langs))
        }
        // 4. Reply: a preset stance (optional) and what the user wants to say.
        if (a.action == AiAssistAction.REPLY) {
            body.addView(section(ui, context.getString(R.string.ai_reply_intent_label)))
            body.addView(
                chipRow(
                    ui, "replyIntents",
                    AiReplyIntent.entries.map { i ->
                        ui.chip(AiMessages.intent(context, i), selected = a.replyIntent == i) { a.chooseIntent(i) }
                    },
                ),
            )
            body.addView(
                ui.field(
                    context.getString(R.string.ai_reply_note_label), a.note.text, context.getString(R.string.ai_reply_note_hint),
                    if (a.note.isBlank) null else AiMessages.count(a.note.length, a.note.limit), a.note.overLimit, maxLines = 3,
                    description = context.getString(R.string.ai_reply_note_label),
                ) { a.startTyping(AiAssistField.NOTE) },
            )
        }
        // 5. Format: the shape the text becomes.
        if (a.action == AiAssistAction.FORMAT) {
            body.addView(section(ui, context.getString(R.string.ai_format_label)))
            body.addView(
                chipRow(
                    ui, "formats",
                    AiFormat.entries.map { f ->
                        ui.chip(AiMessages.format(context, f), selected = a.format == f) { a.chooseFormat(f) }
                    },
                ),
            )
        }
        notice(ui, a.notice)
        val locked = a.isLocked(a.action)
        val problem = a.problem
        val label = if (locked) context.getString(R.string.ai_run_locked, AiMessages.action(context, a.action)) else AiMessages.run(context, a.action, out)
        buttons += ui.button(label, if (locked) AiIcons.LOCK else AiIcons.of(a.action), primary = true, enabled = locked || problem == null) { a.run() }
    }

    private fun assistResult(ui: AiUi, a: AiAssistController, s: AiAssistStep.Result, buttons: MutableList<View>) {
        val name = AiMessages.action(context, s.action)
        val direction = when (s.action) {
            AiAssistAction.TRANSLATE -> {
                val from = s.result.sourceLanguage?.takeIf { TranslationLanguages.current.isKnown(it) }?.let(AiLanguages::name) ?: context.getString(R.string.translate_detect)
                "$from → ${AiLanguages.name(s.output ?: "en")}"
            }
            else -> if (s.output == null) context.getString(R.string.ai_same_language) else context.getString(R.string.ai_in_language, AiLanguages.name(s.output))
        }
        body.addView(ui.label("$name · $direction", 13f, secondary = true, bold = true).apply { setPadding(0, (6f * d).toInt(), 0, (4f * d).toInt()) })
        val suggestions = s.result.suggestions
        if (s.action == AiAssistAction.REPLY && suggestions.size > 1) {
            body.addView(ui.label(context.getString(R.string.ai_pick_reply), 13f, secondary = true))
            suggestions.forEachIndexed { i, text ->
                val card = ui.card(selected = i == s.selected).apply {
                    addView(ui.onCard(text, 15f))
                    isClickable = true
                    contentDescription = context.getString(R.string.ai_reply_option, i + 1, suggestions.size, text)
                    setOnClickListener { a.selectSuggestion(i) }
                    ui.asButton(this, i == s.selected)
                }
                body.addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { setMargins(0, (4f * d).toInt(), 0, (4f * d).toInt()) })
            }
        } else {
            val card = ui.card().apply { addView(ui.onCard(s.shownText, 16f)) }
            body.addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        s.result.notes.take(3).forEach { body.addView(ui.label(it, 12f, secondary = true).apply { setPadding(0, (4f * d).toInt(), 0, 0) }) }
        notice(ui, a.notice)
        if (a.canReplaceSelection) {
            buttons += ui.button(context.getString(R.string.ai_replace_selection), AiIcons.INSERT, primary = true) { onApplied(a.insert(replace = true)) }
            buttons += ui.button(context.getString(R.string.ai_insert_after)) { onApplied(a.insert(replace = false)) }
        } else {
            buttons += ui.button(context.getString(R.string.ai_insert), AiIcons.INSERT, primary = true) { onApplied(a.insert(replace = false)) }
        }
        buttons += ui.button(context.getString(R.string.ai_copy), AiIcons.COPY) { a.copy() }
        if (s.action.regenerable) buttons += ui.button(context.getString(R.string.ai_regenerate), AiIcons.REFRESH) { a.regenerate() }
        buttons += ui.button(context.getString(R.string.ai_panel_edit_inputs), AiIcons.EDIT) { a.backToEdit() }
    }

    // ------------------------------------------------------------------ Write Email

    private fun renderEmail(ui: AiUi, e: AiEmailController, buttons: MutableList<View>): Pair<String, String?> {
        val full = ui.iconButton(AiIcons.OPEN, context.getString(R.string.ai_email_open_full)) {
            e.handoff()
            onLink(AiLink.EMAIL_WRITER)
        }
        header(ui, context.getString(R.string.ai_email_title), onBack = { workspace?.back() }, extra = full)
        return when (val s = e.step) {
            AiEmailStep.Compose -> {
                emailCompose(ui, e, buttons)
                "compose" to context.getString(R.string.ai_email_title)
            }
            AiEmailStep.Working -> {
                val line = context.getString(R.string.ai_working_email)
                working(ui, line, AiMessages.waitHint(context, AiTaskKind.EMAIL), { e.cancel() }, buttons)
                "working" to line
            }
            AiEmailStep.Preview -> {
                emailPreview(ui, e, buttons)
                "preview" to context.getString(R.string.ai_email_ready)
            }
            is AiEmailStep.Failed -> {
                failed(ui, s.failure, { e.retry() }, { e.backToCompose() }, buttons)
                if (e.hasResult) buttons += ui.button(context.getString(R.string.ai_email_back_to_preview)) { e.backToPreview() }
                "failed" to AiMessages.failure(context, s.failure)
            }
        }
    }

    private fun emailCompose(ui: AiUi, e: AiEmailController, buttons: MutableList<View>) {
        blockBanner(ui, e.availability)?.let(body::addView)
        // The document kind (ADR 0048): E-mail by default; job application, CV, cover letter and business message too.
        body.addView(
            chipRow(
                ui, "docType",
                AiDocumentType.entries.map { t ->
                    ui.chip(AiMessages.documentType(context, t), selected = e.documentType == t) { e.chooseDocumentType(t) }
                },
            ).apply { setPadding(0, (4f * d).toInt(), 0, 0) },
        )
        // Mode: New · Reply · Improve.
        body.addView(
            chipRow(
                ui, "mode",
                AiEmailMode.entries.map { m -> ui.chip(AiMessages.mode(context, m), selected = e.mode == m) { e.setMode(m) } },
            ).apply { setPadding(0, (4f * d).toInt(), 0, 0) },
        )
        // The e-mail received (Reply) or the draft (Improve).
        when (e.mode) {
            AiEmailMode.REPLY -> sourceBlock(
                ui, e, AiEmailField.ORIGINAL, context.getString(R.string.ai_email_original_label), context.getString(R.string.ai_email_original_empty),
                buildList {
                    if (e.hasSelection) add(ui.chip(context.getString(R.string.ai_use_selection), AiIcons.INSERT) { e.useSelectionAsOriginal() })
                    if (e.clip == ClipAvailability.TEXT) add(ui.chip(context.getString(R.string.ai_paste_clipboard), AiIcons.PASTE) { e.pasteOriginal() })
                    add(ui.chip(context.getString(R.string.ai_type_text), AiIcons.KEYBOARD) { e.startTyping(AiEmailField.ORIGINAL) })
                },
            )
            AiEmailMode.IMPROVE -> sourceBlock(
                ui, e, AiEmailField.DRAFT, context.getString(R.string.ai_email_draft_label), context.getString(R.string.ai_email_draft_empty),
                buildList {
                    add(ui.chip(context.getString(R.string.ai_use_field_text), AiIcons.INSERT) { e.useFieldTextAsDraft() })
                    if (e.clip == ClipAvailability.TEXT) add(ui.chip(context.getString(R.string.ai_paste_clipboard), AiIcons.PASTE) { e.pasteDraft() })
                    add(ui.chip(context.getString(R.string.ai_type_text), AiIcons.KEYBOARD) { e.startTyping(AiEmailField.DRAFT) })
                },
            )
            AiEmailMode.NEW -> Unit
        }
        // What to say.
        val instrLabel = context.getString(
            when (e.mode) {
                AiEmailMode.NEW -> R.string.ai_email_instructions_new
                AiEmailMode.REPLY -> R.string.ai_email_instructions_reply
                AiEmailMode.IMPROVE -> R.string.ai_email_instructions_improve
            },
        )
        body.addView(
            ui.field(
                instrLabel, e.instructions.text, context.getString(R.string.ai_email_instructions_hint),
                if (e.instructions.isBlank) null else AiMessages.count(e.instructions.length, e.instructions.limit), e.instructions.overLimit,
                maxLines = 4, description = instrLabel,
            ) { e.startTyping(AiEmailField.INSTRUCTIONS) },
        )
        // Tone (with the chosen tone's explanation), length, language.
        body.addView(section(ui, context.getString(R.string.ai_email_tone)))
        body.addView(
            chipRow(
                ui, "tone",
                AiEmailTone.entries.map { t ->
                    ui.chip(AiMessages.tone(context, t), selected = e.tone == t, description = AiMessages.tone(context, t) + ". " + AiMessages.toneHint(context, t)) { e.chooseTone(t) }
                },
            ),
        )
        body.addView(ui.label(AiMessages.toneHint(context, e.tone), 12f, secondary = true))
        body.addView(section(ui, context.getString(R.string.ai_email_length_language)))
        body.addView(
            chipRow(
                ui, "lengthLanguage",
                AiLength.entries.map { l -> ui.chip(AiMessages.length(context, l), selected = e.length == l) { e.chooseLength(l) } } +
                    listOf(View(context).apply { layoutParams = LayoutParams((10f * d).toInt(), 1) }) +
                    AiLanguages.ALL.map { code ->
                        ui.chip(
                            AiLanguages.name(code), if (e.language == code) AiIcons.CHECK else null, selected = e.language == code,
                            description = context.getString(R.string.ai_email_write_in, AiLanguages.name(code)),
                        ) { e.chooseLanguage(code) }
                    },
            ),
        )
        // Names (optional).
        body.addView(
            ui.chip(context.getString(if (e.showDetails) R.string.ai_email_names_hide else R.string.ai_email_names_show), selected = e.showDetails) { e.toggleDetails() }
                .apply { (layoutParams as LayoutParams).topMargin = (8f * d).toInt() },
        )
        if (e.showDetails) {
            body.addView(
                ui.field(
                    context.getString(R.string.ai_email_recipient), e.recipient.text, context.getString(R.string.ai_email_recipient_hint), null, e.recipient.overLimit,
                    maxLines = 1, description = context.getString(R.string.ai_email_recipient),
                ) { e.startTyping(AiEmailField.RECIPIENT) },
            )
            body.addView(
                ui.field(
                    context.getString(R.string.ai_email_sender), e.sender.text, context.getString(R.string.ai_email_sender_hint), null, e.sender.overLimit,
                    maxLines = 1, description = context.getString(R.string.ai_email_sender),
                ) { e.startTyping(AiEmailField.SENDER) },
            )
        }
        e.problem?.let { body.addView(ui.label(AiMessages.problem(context, it), 13f, warn = true).apply { setPadding(0, (6f * d).toInt(), 0, 0) }) }
        notice(ui, e.notice)
        val locked = e.isLocked
        buttons += ui.button(
            context.getString(if (locked) R.string.ai_email_generate_locked else R.string.ai_email_generate),
            if (locked) AiIcons.LOCK else AiIcons.EMAIL, primary = true,
        ) { e.generate() }
    }

    private fun sourceBlock(ui: AiUi, e: AiEmailController, f: AiEmailField, label: String, empty: String, choices: List<View>) {
        val field = e.field(f)
        if (field.isBlank && e.typing != f) {
            body.addView(section(ui, label))
            body.addView(ui.label(empty, 13f, secondary = true))
            body.addView(chipRow(ui, "choices-$f", choices))
            if (e.clip == ClipAvailability.SENSITIVE) body.addView(ui.label(context.getString(R.string.ai_clipboard_sensitive), 12f, secondary = true))
        } else {
            body.addView(
                ui.field(
                    label, field.text, empty, AiMessages.count(field.length, field.limit), field.overLimit, maxLines = 3,
                    description = context.getString(R.string.ai_source_edit, label, field.length),
                ) { e.startTyping(f) },
            )
            body.addView(chipRow(ui, "clear-$f", listOf(ui.chip(context.getString(R.string.ai_source_clear), AiIcons.CLOSE) { e.clearField(f) })))
        }
    }

    private fun emailPreview(ui: AiUi, e: AiEmailController, buttons: MutableList<View>) {
        // Subject (tap to edit) with its own Copy: most mail apps have a separate subject field the keyboard cannot reach.
        val subjectText = e.subject.text
        val subjectCard = ui.card().apply {
            val head = ui.row()
            head.addView(ui.onCard(context.getString(R.string.ai_email_subject), 12f, secondary = true), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(head)
            addView(ui.onCard(highlight(subjectText.ifEmpty { context.getString(R.string.ai_email_no_subject) }), 16f, bold = true))
            isClickable = true
            contentDescription = context.getString(R.string.ai_email_subject_edit, subjectText)
            setOnClickListener { e.startTyping(AiEmailField.SUBJECT) }
            ui.asButton(this, null)
        }
        body.addView(subjectCard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { setMargins(0, (6f * d).toInt(), 0, (4f * d).toInt()) })
        if (subjectText.isNotBlank()) body.addView(chipRow(ui, "subjectTools", listOf(ui.chip(context.getString(R.string.ai_email_copy_subject), AiIcons.COPY) { e.copySubject() })))
        val bodyCard = ui.card().apply {
            addView(ui.onCard(highlight(e.body.text), 15f))
            isClickable = true
            contentDescription = context.getString(R.string.ai_email_body_edit)
            setOnClickListener { e.startTyping(AiEmailField.BODY) }
            ui.asButton(this, null)
        }
        body.addView(bodyCard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { setMargins(0, (4f * d).toInt(), 0, (4f * d).toInt()) })
        val fill = e.placeholders
        if (fill.isNotEmpty()) body.addView(ui.label(context.getString(R.string.ai_email_fill_in, fill.joinToString(", ")), 13f, warn = true))
        e.result?.notes?.take(3)?.forEach { body.addView(ui.label(it, 12f, secondary = true)) }
        body.addView(
            ui.chip(context.getString(R.string.ai_email_insert_subject), if (e.insertSubject) AiIcons.CHECK else null, selected = e.insertSubject) { e.toggleInsertSubject() }
                .apply { (layoutParams as LayoutParams).topMargin = (6f * d).toInt() },
        )
        body.addView(ui.label(context.getString(R.string.ai_email_insert_subject_hint), 12f, secondary = true))
        notice(ui, e.notice)
        buttons += ui.button(context.getString(R.string.ai_insert), AiIcons.INSERT, primary = true) { onApplied(e.insert()) }
        buttons += ui.button(context.getString(R.string.ai_email_copy_all), AiIcons.COPY) { e.copyAll() }
        buttons += ui.button(context.getString(R.string.ai_regenerate), AiIcons.REFRESH) { e.regenerate() }
        buttons += ui.button(context.getString(R.string.ai_email_edit_text), AiIcons.EDIT) { e.startTyping(AiEmailField.BODY) }
        buttons += ui.button(context.getString(R.string.ai_panel_edit_inputs)) { e.backToCompose() }
    }

    /** `[placeholders]` in bold on the pressed fill (key text on it is a contrast-tested pair). */
    private fun highlight(text: String): CharSequence {
        val ranges = AiEmailRules.placeholderRanges(text)
        if (ranges.isEmpty()) return text
        val s = SpannableString(text)
        for (r in ranges) {
            s.setSpan(BackgroundColorSpan(theme.pressed), r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(StyleSpan(Typeface.BOLD), r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return s
    }

    internal val bodyView: LinearLayout get() = body
    internal val footerRow: ViewGroup? get() = footer.getChildAt(0) as? ViewGroup
    internal val headerRow: ViewGroup get() = header
}
