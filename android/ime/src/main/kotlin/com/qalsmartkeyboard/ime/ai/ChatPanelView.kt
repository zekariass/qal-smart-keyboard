package com.qalsmartkeyboard.ime.ai

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.editor.EditorClipboard
import com.qalsmartkeyboard.ime.editor.EditorView
import com.qalsmartkeyboard.ime.editor.KeyboardTextField
import com.qalsmartkeyboard.ime.theme.KeyboardTheme
import com.qalsmartkeyboard.ime.theme.ThemeCatalog

/**
 * The Ask AI chat panel (plan T8.5, ADR 0048), in place of the keys at the AI panels' height ([heightProvider], the same
 * as AI Assist and the AI tools grid, so moving between them never changes the keyboard's height). Top to bottom:
 *
 * - **Header:** back, the title, New chat, back to the keyboard.
 * - **Options:** the reply language ("Reply in: Same as I write ▾", the translate panel's language catalogue) and the
 *   field text chip: "Use text in field" (the only way the host field is read, on the tap) or, once attached, "Field text
 *   attached ×".
 * - **Transcript:** bubbles (the user's on the right in the accent colour, the assistant's on the left on the key fill),
 *   scrolling, always at the newest message. An assistant bubble has Insert and Copy (and Continue when the reply was cut
 *   off). An empty chat shows the quick starters; a running request shows the typing indicator with Cancel.
 * - **Banner:** a plan lock, an error or a quota notice with its links (See plans, Buy credits, Sign in, Retry), or the
 *   remaining allowance.
 * - **Input row:** the message as the keyboard's own text field (a tap brings the keys up under the field bar, as in AI
 *   Assist) and Send.
 *
 * Plain Views on the theme's contrast-checked pairs (ADR 0031 §5). Re-rendered on state changes, never per key (the
 * panel is hidden while the keys type). A new assistant message is announced (live region); every control is named.
 * User text appears only in the TextViews while the panel is open; nothing here logs.
 */
class ChatPanelView(context: Context) : LinearLayout(context) {
    var controller: ChatController? = null

    /** The panel's height: the key area plus the AI extra (ADR 0044); 0 = as measured. */
    var heightProvider: () -> Int = { 0 }

    /** Open the companion app at [AiLink] (plans, credits, sign-in, update). */
    var onLink: (AiLink) -> Unit = {}

    /** An assistant message was put in the host field: the service closes the panel (FAILED: it stays and says why). */
    var onApplied: (AiApplied) -> Unit = {}

    /** The header's back arrow (the workspace's Back: it closes a language list or cancels first). */
    var onBack: () -> Unit = {}

    /** "Back to keyboard": the panel closes and the chat is dropped. */
    var onClose: () -> Unit = {}

    /** Whether a tap gives haptic feedback (the keys' setting). */
    var haptics: () -> Boolean = { true }
        set(value) {
            field = value
            inputField.haptics = value
            inputField.editor.haptics = value
        }

    /** Before the user moves the cursor or edits in the box: the service finishes the open composition into it. */
    var beforeEdit: () -> Unit
        get() = inputField.editor.beforeEdit
        set(value) {
            inputField.editor.beforeEdit = value
        }

    /** The clipboard of the box's action row (sensitive clips are never offered or read). */
    var clipboard: EditorClipboard
        get() = inputField.clipboard
        set(value) {
            inputField.clipboard = value
        }

    var theme: KeyboardTheme = ThemeCatalog.classic(
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES,
    )
        set(value) {
            if (field === value) return
            field = value
            if (visibility == VISIBLE) render()
        }

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).toInt()

    private val header = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val options = LinearLayout(context).apply { orientation = VERTICAL }
    private val transcriptScroll = ScrollView(context).apply { isFillViewport = false }
    private val transcript = LinearLayout(context).apply { orientation = VERTICAL }
    private val banner = LinearLayout(context).apply { orientation = VERTICAL }
    private val inputRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    /** The message box: the keyboard's own text field; a tap on it brings the keys up (the panel gives way). */
    private val inputField = KeyboardTextField(context).apply {
        editor.textSizeSp = 16f
        editor.minLines = 1
        editor.maxLines = 2
        editor.setPadding(dp(4f), dp(8f), dp(4f), dp(8f))
        editor.onTap = { controller?.startTyping() }
    }
    private val inputBox = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48f)
        setPadding(dp(10f), dp(2f), dp(8f), dp(2f))
        addView(inputField, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
    private var sendButton: View? = null

    private var lastEntryCount = -1
    private var lastSending = false
    private var announcedId = 0

    init {
        orientation = VERTICAL
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(56f)))
        addView(options, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        transcript.setPadding(dp(10f), dp(4f), dp(10f), dp(6f))
        transcriptScroll.addView(transcript, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(transcriptScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(banner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        inputRow.setPadding(dp(8f), dp(2f), dp(6f), dp(4f))
        addView(inputRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        visibility = GONE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = heightProvider()
        super.onMeasure(widthMeasureSpec, if (h > 0) MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY) else heightMeasureSpec)
    }

    /** Re-reads the controller and rebuilds the panel. Main thread; called on state changes, not per key. */
    fun render() {
        val c = controller ?: return
        setBackgroundColor(theme.stripBg)
        if (!c.isOpen) {
            transcript.removeAllViews()
            lastEntryCount = -1
            lastSending = false
            announcedId = 0
            return
        }
        val ui = AiUi(context, theme)
        renderHeader(ui, c)
        renderOptions(ui, c)
        renderTranscript(ui, c)
        renderBanner(ui, c)
        renderInput(ui, c)
    }

    // ------------------------------------------------------------------ header and options

    private fun renderHeader(ui: AiUi, c: ChatController) {
        header.removeAllViews()
        header.addView(ui.iconButton(AiIcons.BACK, context.getString(R.string.ai_panel_back)) { tap { onBack() } })
        header.addView(
            ui.label(context.getString(R.string.chat_title), 16f, bold = true).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                ViewCompat.setAccessibilityHeading(this, true)
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        val canClear = c.entries.isNotEmpty() || !c.input.isBlank || c.fieldText != null
        header.addView(ui.button(context.getString(R.string.chat_new), AiIcons.REFRESH, enabled = canClear) { tap { c.newChat() } }.also { newChatButton = it })
        header.addView(ui.iconButton(AiIcons.KEYBOARD, context.getString(R.string.ai_panel_close)) { tap { onClose() } })
    }

    private fun renderOptions(ui: AiUi, c: ChatController) {
        options.removeAllViews()
        val langName = AiMessages.chatLanguage(context, c.language)
        val language = ui.chip(
            context.getString(R.string.chat_reply_in) + ": " + langName + "  ▾",
            selected = c.languagePickerOpen,
            description = context.getString(R.string.chat_reply_in_desc, langName),
        ) { tap { c.toggleLanguagePicker() } }
        val field = if (c.fieldText == null) {
            ui.chip(context.getString(R.string.chat_use_field), AiIcons.PASTE, enabled = c.allowed && !c.busy) { tap { c.attachFieldText() } }
        } else {
            ui.chip(
                context.getString(R.string.chat_field_attached), AiIcons.CLOSE, selected = true,
                description = context.getString(R.string.chat_field_remove),
            ) { tap { c.removeFieldText() } }
        }
        languageChip = language
        fieldChip = field
        options.addView(ui.scrollRow(language, field).apply { setPadding(dp(6f), 0, dp(6f), 0) })
        if (c.languagePickerOpen) {
            val chips = ArrayList<View>()
            for (code in c.languageChoices()) {
                val name = AiMessages.chatLanguage(context, code)
                chips += ui.chip(name, selected = code == c.language, description = name) { tap { c.chooseLanguage(code) } }
            }
            if (c.hasMoreLanguages) chips += ui.chip(context.getString(R.string.ai_output_more_languages)) { tap { c.moreLanguages() } }
            options.addView(ui.scrollRow(*chips.toTypedArray()).apply { setPadding(dp(6f), 0, dp(6f), 0) })
        }
    }

    // ------------------------------------------------------------------ transcript

    private fun renderTranscript(ui: AiUi, c: ChatController) {
        val keepScroll = transcriptScroll.scrollY
        transcript.removeAllViews()
        assistantBubbles.clear()
        userBubbles.clear()
        copyButtons.clear()
        insertButtons.clear()
        continueButton = null
        starterChips.clear()
        indicator = null
        cancelButton = null
        val entries = c.entries
        if (entries.isEmpty() && c.step !is ChatStep.Sending) {
            transcript.addView(
                ui.label(context.getString(R.string.chat_empty), 14f, secondary = true).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(8f), dp(10f), dp(8f), dp(4f))
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
            val row = WrapRow(context).apply { setPadding(dp(4f), 0, dp(4f), 0) }
            for (s in ChatStarter.entries) {
                val chip = ui.chip(AiMessages.starter(context, s), description = AiMessages.starter(context, s)) {
                    tap { c.prefill(AiMessages.starterPrefill(context, s).trimEnd() + " ") }
                }
                starterChips[s] = chip
                row.addView(chip)
            }
            transcript.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        val lastAssistant = entries.lastOrNull { it.role == ChatRole.ASSISTANT }
        for (e in entries) {
            transcript.addView(bubble(ui, c, e, live = e === lastAssistant && e === entries.last()))
        }
        if (c.canContinue) {
            val row = ui.row().apply { setPadding(dp(2f), 0, dp(2f), 0) }
            row.addView(ui.label(context.getString(R.string.chat_cut_off), 13f, secondary = true), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            val button = ui.button(context.getString(R.string.chat_continue), AiIcons.REPLY) { tap { c.continueReply() } }
            continueButton = button
            row.addView(button)
            transcript.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        if (c.step is ChatStep.Sending) transcript.addView(indicator(ui, c))
        // Scroll to the newest message when something was added; keep the reader's place when only a notice changed.
        val sending = c.step is ChatStep.Sending
        val moved = entries.size != lastEntryCount || sending != lastSending
        lastEntryCount = entries.size
        lastSending = sending
        transcriptScroll.post { if (moved) transcriptScroll.fullScroll(View.FOCUS_DOWN) else transcriptScroll.scrollTo(0, keepScroll) }
        val newest = entries.lastOrNull()
        if (newest != null && newest.role == ChatRole.ASSISTANT && newest.id > announcedId) {
            announcedId = newest.id
            announceForAccessibility(context.getString(R.string.chat_message_ai_desc, newest.text))
        }
    }

    private fun bubble(ui: AiUi, c: ChatController, e: ChatEntry, live: Boolean): View {
        val mine = e.role == ChatRole.USER
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12f), dp(8f), dp(12f), dp(if (mine) 8f else 4f))
            background = GradientDrawable().apply {
                cornerRadius = 16f * d
                setColor(if (mine) theme.accent else theme.key)
                if (!mine && theme.hasBorder) setStroke(dp(1f), theme.border)
            }
        }
        val text = TextView(context).apply {
            this.text = e.text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(if (mine) theme.onAccent else theme.keyText)
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            contentDescription = context.getString(if (mine) R.string.chat_message_you_desc else R.string.chat_message_ai_desc, e.text)
            if (live) ViewCompat.setAccessibilityLiveRegion(this, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
        }
        box.addView(text, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        if (mine) {
            userBubbles += text
        } else {
            assistantBubbles += text
            val actions = ui.row(Gravity.CENTER_VERTICAL or Gravity.END)
            val copied = c.notice == AiNotice.COPIED && c.entries.lastOrNull { it.role == ChatRole.ASSISTANT }?.id == e.id
            val copy = ui.button(context.getString(if (copied) R.string.ai_copied else R.string.ai_copy), AiIcons.COPY) { tap { c.copy(e.id) } }
            val insert = ui.button(context.getString(R.string.ai_insert), AiIcons.INSERT, primary = true) { tap { onApplied(c.insert(e.id)) } }
            actions.addView(copy)
            actions.addView(insert)
            copyButtons += copy
            insertButtons += insert
            box.addView(actions, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
        }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = if (mine) Gravity.END else Gravity.START
            addView(
                box,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    if (mine) marginStart = dp(44f) else marginEnd = dp(24f)
                    topMargin = dp(4f)
                    bottomMargin = dp(2f)
                },
            )
        }
    }

    private fun indicator(ui: AiUi, c: ChatController): View {
        val line = context.getString(R.string.chat_thinking)
        val row = ui.row().apply { setPadding(dp(6f), dp(4f), dp(2f), 0) }
        row.addView(
            ProgressBar(context).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(theme.hintText)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LayoutParams(dp(22f), dp(22f)),
        )
        val col = ui.column().apply { setPadding(dp(10f), 0, dp(6f), 0) }
        col.addView(
            ui.label(line, 14f).apply { ViewCompat.setAccessibilityLiveRegion(this, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE) },
        )
        col.addView(ui.label(context.getString(R.string.ai_wait_long, 30), 12f, secondary = true))
        row.addView(col, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val cancel = ui.button(context.getString(R.string.ai_cancel), AiIcons.CLOSE) { tap { c.cancel() } }
        cancelButton = cancel
        row.addView(cancel)
        indicator = row
        return row
    }

    // ------------------------------------------------------------------ banner

    private fun renderBanner(ui: AiUi, c: ChatController) {
        banner.removeAllViews()
        bannerText = null
        bannerLinks.clear()
        retryButton = null
        val step = c.step
        // What stands in the way: a failure of the last send, else what blocks sending before it is tried.
        val failure = (step as? ChatStep.Failed)?.failure ?: c.blocker
        val lines = ArrayList<Pair<String, Boolean>>() // text, warn
        val links = ArrayList<AiLink>()
        if (failure != null) {
            lines += bannerMessage(failure) to true
            failure.link?.let { links += it }
            failure.extraLink?.let { links += it }
        } else {
            AiMessages.notice(context, c.notice)?.let { lines += it to (c.notice != AiNotice.COPIED) }
            if (lines.isEmpty()) AiMessages.chatNotice(context, c.availability)?.let { lines += it to false }
        }
        if (lines.isEmpty()) {
            banner.visibility = GONE
            return
        }
        banner.visibility = VISIBLE
        val (text, warn) = lines.first()
        val label = ui.label(text, 13f, secondary = !warn, warn = warn).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12f), dp(2f), dp(8f), dp(2f))
            ViewCompat.setAccessibilityLiveRegion(this, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
        }
        bannerText = label
        banner.addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val retry = step is ChatStep.Failed && step.retryable
        if (links.isNotEmpty() || retry) {
            val row = WrapRow(context).apply { setPadding(dp(6f), 0, dp(6f), 0) }
            for ((i, l) in links.withIndex()) {
                val b = ui.button(AiMessages.link(context, l), primary = i == 0) { tap { onLink(l) } }
                bannerLinks[l] = b
                row.addView(b)
            }
            if (retry) {
                val b = ui.button(context.getString(R.string.ai_retry), AiIcons.REFRESH, primary = links.isEmpty()) { tap { controller?.retry() } }
                retryButton = b
                row.addView(b)
            }
            banner.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    private fun bannerMessage(f: AiFailure): String = when (f) {
        AiFailure.Offline -> context.getString(R.string.ai_status_offline)
        AiFailure.SignInRequired -> context.getString(R.string.ai_status_signed_out)
        AiFailure.UpdateRequired -> context.getString(R.string.ai_status_update)
        else -> AiMessages.failure(context, f)
    }

    // ------------------------------------------------------------------ input

    private fun renderInput(ui: AiUi, c: ChatController) {
        inputRow.removeAllViews()
        val t = c.input.text
        val hint = context.getString(R.string.chat_hint)
        if (inputField.theme !== theme) inputField.theme = theme
        val e = inputField.editor
        e.buffer = c.input.buffer
        e.hint = hint
        e.describe = { s -> if (s.isEmpty()) hint else context.getString(R.string.chat_input_spoken, s) }
        e.contentDescription = if (t.isEmpty()) hint else context.getString(R.string.chat_input_spoken, t)
        inputBox.background = GradientDrawable().apply {
            cornerRadius = 24f * d
            setColor(theme.key)
            if (theme.hasBorder) setStroke(dp(1f), theme.border)
        }
        inputRow.addView(inputBox, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val send = ui.button(
            context.getString(R.string.chat_send), SEND_PATH, primary = true, enabled = c.canSend,
            description = context.getString(R.string.chat_send_desc),
        ) { tap { c.send() } }
        sendButton = send
        inputRow.addView(send)
    }

    // ------------------------------------------------------------------ helpers

    private fun tap(block: () -> Unit) {
        if (haptics()) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        block()
    }

    private val assistantBubbles = ArrayList<TextView>()
    private val userBubbles = ArrayList<TextView>()
    private val copyButtons = ArrayList<View>()
    private val insertButtons = ArrayList<View>()
    private val starterChips = LinkedHashMap<ChatStarter, View>()
    private val bannerLinks = LinkedHashMap<AiLink, View>()
    private var languageChip: View? = null
    private var fieldChip: View? = null
    private var newChatButton: View? = null
    private var continueButton: View? = null
    private var retryButton: View? = null
    private var cancelButton: View? = null
    private var indicator: View? = null
    private var bannerText: TextView? = null

    internal val assistantBubbleViews: List<TextView> get() = assistantBubbles.toList()
    internal val userBubbleViews: List<TextView> get() = userBubbles.toList()
    internal val copyButtonViews: List<View> get() = copyButtons.toList()
    internal val insertButtonViews: List<View> get() = insertButtons.toList()
    internal val starterViews: Map<ChatStarter, View> get() = starterChips.toMap()
    internal val bannerLinkViews: Map<AiLink, View> get() = bannerLinks.toMap()
    internal val languageChipView: View? get() = languageChip
    internal val fieldChipView: View? get() = fieldChip
    internal val newChatView: View? get() = newChatButton
    internal val continueView: View? get() = continueButton
    internal val retryView: View? get() = retryButton
    internal val cancelView: View? get() = cancelButton
    internal val indicatorView: View? get() = indicator
    internal val bannerTextView: TextView? get() = bannerText
    internal val sendView: View? get() = sendButton
    internal val inputRowView: View get() = inputRow
    internal val inputEditor: EditorView get() = inputField.editor
    internal val transcriptView: ScrollView get() = transcriptScroll

    companion object {
        /** The paper-plane mark of the Send button (a 24×24 Material path). */
        private const val SEND_PATH = "M2.01,21L23,12 2.01,3 2,10l15,2 -15,2z"
    }
}
