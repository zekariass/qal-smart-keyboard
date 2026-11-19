package com.qalsmartkeyboard.ime.ai

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.editor.EditorClipboard
import com.qalsmartkeyboard.ime.editor.EditorView
import com.qalsmartkeyboard.ime.editor.KeyboardTextField
import com.qalsmartkeyboard.ime.theme.KeyboardTheme
import com.qalsmartkeyboard.ime.theme.ThemeCatalog
import com.qalsmartkeyboard.ime.toolbar.Motion
import com.qalsmartkeyboard.ime.toolbar.ToolIcon
import com.qalsmartkeyboard.ime.toolbar.ToolIconDrawable
import com.qalsmartkeyboard.ime.toolbar.ToolIcons

/**
 * The translate panel (ADR 0037, Gboard's translate mode as the benchmark), above the bar while translate mode is on; the
 * keys stay below it and type into its box. Three parts:
 *
 * - **Header:** `[Detect · Amharic ▾]  ⇄  [English ▾]  ⚙  ×`. Each side opens its language list (in place of the keys,
 *   [TranslateLanguagesView]); ⇄ swaps with a short turn of the icon (none with "Remove animations"); ⚙ opens the app's
 *   "Translate into" page; × leaves translate mode.
 * - **Box:** the text to translate ("Type to translate"), what it came from (the selection, the last sentence), a clear ×
 *   or "Use last sentence" while empty, and the Translate button (the only thing that sends; Enter on the keys too).
 *   The box is the keyboard's own text field (ADR 0038, [KeyboardTextField]): a tap anywhere in it moves the cursor
 *   (bidi-aware for Arabic), a long press or double tap selects a word with handles and the Cut/Copy/Paste/Select all
 *   row, and the keys insert and delete at the cursor.
 * - **Output:** nothing; a one-line reason with its link (signed out, update, plan, offline); the spinner with Cancel;
 *   the translation (in the destination's direction: right-to-left for Arabic) with its direction line, alternatives,
 *   Copy and Insert (Replace when it came from the field); or an error with its link and Retry where it helps.
 *
 * Plain Views on the theme's contrast-checked pairs (ADR 0031 §5) and the keyboard's icon family (ADR 0035). [render] runs
 * on state changes, [renderInput] on keys (it only touches the box). The working line, the translation, "Copied" and
 * errors are announced once each (announceForAccessibility; no live regions, so nothing is read twice). Holds user text
 * only in its TextViews while open.
 */
class TranslatePanelView(context: Context) : LinearLayout(context) {
    var controller: TranslateController? = null

    /** Open the companion app at [AiLink] (sign-in, plans, update, the "Translate into" page). */
    var onLink: (AiLink) -> Unit = {}

    /** Leave translate mode (the × button). */
    var onClose: () -> Unit = {}

    /** The Translate button, after the service finished the open composition into the box. */
    var onTranslate: () -> Unit = { controller?.translate() }

    /** What Insert did (the service shows "inserted instead" when the field had changed). */
    var onApplied: (AiApplied) -> Unit = {}

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
            render()
        }

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).toInt()

    // ---------------------------------------------------------------- header
    private val sourceCaption = text(11f)
    private val sourceName = text(14f).apply { setTypeface(typeface, Typeface.BOLD) }
    private val destName = text(14f).apply { setTypeface(typeface, Typeface.BOLD) }
    private val sourceCaret = IconView(context, ToolIcon.EXPAND, 18f)
    private val destCaret = IconView(context, ToolIcon.EXPAND, 18f)
    private val sourceChip = languageChip(sourceCaption, sourceName, sourceCaret) { tap { controller?.openPicker(TranslatePicker.SOURCE) } }
    private val destChip = languageChip(null, destName, destCaret) { tap { controller?.openPicker(TranslatePicker.DESTINATION) } }
    private val swapButton = IconView(context, ToolIcon.SWAP, 22f).also { button(it, R.string.ai_direction_swap) { swap() } }
    private val settingsButton = IconView(context, ToolIcon.SETTINGS, 20f).also { button(it, R.string.translate_settings) { onLink(AiLink.TRANSLATION) } }
    private val closeButton = IconView(context, ToolIcon.CLOSE, 20f).also { button(it, R.string.translate_close) { onClose() } }

    // ---------------------------------------------------------------- box
    /** The text to translate: an editable field with a cursor and a selection (ADR 0038). */
    private val inputField = KeyboardTextField(context).apply {
        editor.textSizeSp = 16f
        editor.minLines = 1
        editor.maxLines = 3
        editor.setPadding(dp(4f), dp(8f), dp(4f), dp(8f))
    }
    private val clearButton = IconView(context, ToolIcon.CLOSE, 18f).also { button(it, R.string.ai_source_clear) { tap { controller?.clearInput() } } }
    private val sentenceChip = text(13f).apply {
        text = context.getString(R.string.translate_use_sentence)
        gravity = Gravity.CENTER
        maxLines = 1 // one line without horizontal scrolling, so a centred label stays centred
        setPadding(dp(10f), 0, dp(10f), 0)
        minHeight = dp(32f)
        isClickable = true
        isFocusable = true
        setOnClickListener { tap { controller?.useLastSentence() } }
        asButton(this)
    }
    private val inputBox = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48f)
        setPadding(dp(8f), dp(2f), dp(4f), dp(2f))
        addView(inputField, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(sentenceChip, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(clearButton, LayoutParams(dp(40f), dp(40f)))
    }
    private val translateButton = text(15f).apply {
        text = context.getString(R.string.ai_translate)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        maxLines = 1
        minHeight = dp(48f)
        setPadding(dp(18f), 0, dp(18f), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener { tap { onTranslate() } }
        asButton(this)
    }

    // ---------------------------------------------------------------- output (rebuilt per state)
    private val output = LinearLayout(context).apply { orientation = VERTICAL }

    /** The output view of the last [render] for tests: the translation text, or null. */
    internal var resultView: TextView? = null
        private set

    /** The shown translation is laid out right to left (tests: a detached view does not resolve its text direction). */
    internal var resultRtl: Boolean = false
        private set

    private var lastAnnounced: String? = null

    init {
        orientation = VERTICAL
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(4f), dp(4f), 0)
            addView(sourceChip, LayoutParams(0, dp(44f), 1f))
            addView(swapButton, LayoutParams(dp(44f), dp(48f)))
            addView(destChip, LayoutParams(0, dp(44f), 1f))
            addView(settingsButton, LayoutParams(dp(42f), dp(48f)).apply { marginStart = dp(2f) })
            addView(closeButton, LayoutParams(dp(42f), dp(48f)))
        }
        val inputRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            addView(inputBox, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(translateButton, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48f)).apply { marginStart = dp(8f) })
        }
        addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(inputRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(output, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        visibility = GONE
    }

    /** Fades the panel in rising a little (ADR 0035 motion); appears at once with "Remove animations". */
    fun reveal() = Motion.reveal(this, fromYPx = 8f * d)

    /** Re-reads the controller: header, box and output. Call on state changes (taps, answers), not per key. */
    fun render() {
        val c = controller
        setBackgroundColor(theme.stripBg)
        if (c == null || !c.isOpen) {
            output.removeAllViews()
            resultView = null
            lastAnnounced = null
            return
        }
        renderHeader(c)
        renderInput()
        renderOutput(c)
    }

    private fun renderHeader(c: TranslateController) {
        val detect = context.getString(R.string.translate_detect)
        val dest = c.destination // also brings the Detect source up to date with the keyboard language
        val auto = c.direction.isAuto
        val sourceLang = AiLanguages.name(c.direction.likelySource(c.detected))
        sourceCaption.visibility = if (auto) VISIBLE else GONE
        sourceCaption.text = detect
        sourceCaption.setTextColor(theme.hintText)
        sourceName.text = sourceLang
        destName.text = dest.label
        val picking = c.picker
        styleChip(sourceChip, picking == TranslatePicker.SOURCE, listOf(sourceName), sourceCaret)
        styleChip(destChip, picking == TranslatePicker.DESTINATION, listOf(destName), destCaret)
        sourceChip.contentDescription = context.getString(R.string.ai_direction_source, c.sourceLabel(detect))
        destChip.contentDescription = context.getString(R.string.ai_direction_destination, dest.label)
        for (b in listOf(swapButton, settingsButton, closeButton)) {
            b.color = theme.stripText
            b.background = pressable(0, 22f)
        }
    }

    /** Re-reads the box only: cheap, called on every key while the panel is open. */
    fun renderInput() {
        val c = controller ?: return
        if (!c.isOpen) return
        val t = c.input.text
        val empty = t.isEmpty()
        val label = when (c.source) {
            TranslateSource.SELECTION -> if (c.replacesFieldText) context.getString(R.string.ai_source_selection) else null
            TranslateSource.SENTENCE -> if (c.replacesFieldText) context.getString(R.string.translate_source_sentence) else null
            else -> null
        }
        // The box edits the controller's buffer in place: its cursor and selection survive every re-render, a language
        // change, a swap and the language lists.
        val hint = context.getString(R.string.translate_hint)
        if (inputField.theme !== theme) inputField.theme = theme
        inputField.label = label.orEmpty()
        val e = inputField.editor
        e.buffer = c.input.buffer
        e.hint = hint
        e.describe = { s -> if (s.isEmpty()) hint else context.getString(R.string.translate_input_spoken, s) }
        e.contentDescription = if (empty) hint else context.getString(R.string.translate_input_spoken, t)
        inputBox.background = GradientDrawable().apply {
            cornerRadius = 16f * d
            setColor(theme.key)
            if (theme.hasBorder) setStroke(dp(1f), theme.border)
        }
        clearButton.visibility = if (empty) GONE else VISIBLE
        clearButton.color = theme.hintText
        clearButton.background = pressable(0, 20f)
        val offerSentence = empty && c.sentenceAvailable
        sentenceChip.visibility = if (offerSentence) VISIBLE else GONE
        sentenceChip.setTextColor(theme.keyText)
        sentenceChip.background = GradientDrawable().apply {
            cornerRadius = 16f * d
            setColor(theme.pressed)
        }
        val enabled = c.canTranslate
        translateButton.isEnabled = enabled
        translateButton.setTextColor(if (enabled) theme.onAccent else theme.hintText)
        translateButton.background = GradientDrawable().apply {
            cornerRadius = 24f * d
            setColor(if (enabled) theme.accent else theme.key)
            if (!enabled && theme.hasBorder) setStroke(dp(1f), theme.border)
        }
        translateButton.contentDescription = context.getString(R.string.ai_translate_to, c.destination.label)
    }

    private fun renderOutput(c: TranslateController) {
        output.removeAllViews()
        resultView = null
        var announce: String? = null
        when (val s = c.step) {
            TranslateStep.Idle -> {
                val block = c.blocker
                if (block != null) output.addView(statusLine(block))
                if (c.notice == AiNotice.INSERT_FAILED) output.addView(line(context.getString(R.string.ai_apply_failed), warn = true))
            }
            is TranslateStep.Working -> {
                val working = context.getString(R.string.ai_working_translate, s.task.destination.label)
                output.addView(
                    row().apply {
                        setPadding(dp(12f), 0, dp(4f), dp(4f))
                        addView(
                            ProgressBar(context).apply {
                                isIndeterminate = true
                                indeterminateTintList = ColorStateList.valueOf(theme.hintText)
                                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                            },
                            LayoutParams(dp(20f), dp(20f)),
                        )
                        addView(
                            line(working).apply { setPadding(dp(10f), 0, dp(8f), 0) },
                            LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                        )
                        addView(pill(context.getString(R.string.ai_cancel)) { tap { c.cancel() } })
                    },
                )
                announce = working
            }
            is TranslateStep.Result -> {
                output.addView(resultCard(c, s))
                announce = if (c.notice == AiNotice.COPIED) context.getString(R.string.ai_copied) else s.shownText
            }
            is TranslateStep.Failed -> {
                val msg = AiMessages.failure(context, s.failure)
                output.addView(
                    row().apply {
                        setPadding(dp(12f), 0, dp(4f), dp(4f))
                        addView(
                            line(msg, warn = true).apply { maxLines = 3 },
                            LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                        )
                        s.failure.link?.let { l -> addView(pill(AiMessages.link(context, l)) { onLink(l) }) }
                        if (AiGate.canRetry(s.failure) && s.task != null) addView(pill(context.getString(R.string.ai_retry)) { tap { c.retry() } })
                    },
                )
                announce = msg
            }
        }
        output.visibility = if (output.childCount > 0) VISIBLE else GONE
        if (announce != null && announce != lastAnnounced) announceForAccessibility(announce)
        lastAnnounced = announce
    }

    /** "Sign in to use AI  [Sign in]": a blocked or plan-locked state in one line, before anything is sent. */
    private fun statusLine(f: AiFailure): View = row().apply {
        setPadding(dp(12f), 0, dp(4f), dp(4f))
        val text = when (f) {
            AiFailure.Offline -> context.getString(R.string.ai_status_offline)
            AiFailure.SignInRequired -> context.getString(R.string.ai_status_signed_out)
            AiFailure.UpdateRequired -> context.getString(R.string.ai_status_update)
            else -> context.getString(R.string.ai_err_not_entitled)
        }
        addView(line(text, warn = true).apply { maxLines = 2 }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        f.link?.let { l -> addView(pill(AiMessages.link(context, l)) { onLink(l) }) }
    }

    private fun resultCard(c: TranslateController, r: TranslateStep.Result): View {
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(14f), dp(8f), dp(6f), dp(4f))
            background = GradientDrawable().apply {
                cornerRadius = 16f * d
                setColor(theme.key)
                if (theme.hasBorder) setStroke(dp(1f), theme.border)
            }
        }
        // "Amharic → English": the server's detected source when it reported one, else the source shown in the header.
        val from = r.result.sourceLanguage?.takeIf { it in AiLanguages.ALL }?.let(AiLanguages::name)
            ?: r.task.sourceLanguage?.let(AiLanguages::name)
            ?: AiLanguages.name(c.direction.likelySource(c.detected))
        val to = r.task.destination.label
        card.addView(
            TextView(context).apply {
                text = "$from → $to"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(theme.hintText)
                contentDescription = context.getString(R.string.translate_direction_spoken, from, to)
            },
        )
        val rtl = AiLanguages.isRtl(r.language)
        resultRtl = rtl
        val result = TextView(context).apply {
            text = r.shownText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(theme.keyText)
            textDirection = if (rtl) TEXT_DIRECTION_RTL else TEXT_DIRECTION_LTR
            textAlignment = TEXT_ALIGNMENT_VIEW_START
            gravity = Gravity.START
            setTextIsSelectable(false)
            setPadding(0, dp(2f), dp(8f), dp(2f))
        }
        resultView = result
        card.addView(MaxHeightScroll(context, dp(RESULT_MAX_DP)).apply { addView(result) }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (r.result.alternatives.isNotEmpty()) {
            val alts = row()
            alts.addView(pill(context.getString(R.string.ai_main_result), selected = r.selected < 0, small = true) { c.selectAlternative(-1) })
            r.result.alternatives.forEachIndexed { i, a ->
                alts.addView(pill(a.label ?: context.getString(R.string.ai_alternative, i + 1), selected = r.selected == i, small = true) { c.selectAlternative(i) })
            }
            card.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(alts) })
        }
        r.result.notes.take(2).forEach { n ->
            card.addView(TextView(context).apply { text = n; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setTextColor(theme.hintText) })
        }
        val actions = row().apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        val copied = c.notice == AiNotice.COPIED
        actions.addView(
            pill(context.getString(if (copied) R.string.ai_copied else R.string.ai_copy), icon = if (copied) ToolIcon.CHECK else ToolIcon.COPY, onCard = true) {
                tap { c.copy() }
            },
        )
        val insertLabel = context.getString(if (c.replacesFieldText) R.string.ai_replace else R.string.ai_insert)
        actions.addView(pill(insertLabel, primary = true) { tap { onApplied(c.insert()) } })
        card.addView(actions, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(8f), 0, dp(8f), dp(6f))
            addView(card, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    // ---------------------------------------------------------------- swap

    private fun swap() {
        val c = controller ?: return
        tap { c.swap() }
        if (Motion.enabled(context)) {
            swapButton.animate().cancel()
            swapButton.rotation = 0f
            swapButton.animate().rotation(180f).setDuration(Motion.DURATION_MS).withEndAction { swapButton.rotation = 0f }.start()
        }
        val from = AiLanguages.name(c.direction.likelySource(c.detected))
        announceForAccessibility(context.getString(R.string.translate_direction_spoken, from, c.destination.label))
    }

    // ---------------------------------------------------------------- view helpers

    private fun tap(block: () -> Unit) {
        if (haptics()) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        block()
    }

    private fun text(sizeSp: Float) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        includeFontPadding = false
    }

    private fun row() = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun line(text: String, warn: Boolean = false) = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(if (warn) theme.warn else theme.stripText)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun languageChip(caption: TextView?, name: TextView, caret: IconView, click: () -> Unit) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14f), 0, dp(6f), 0)
        val col = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            if (caption != null) addView(caption.apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END })
            addView(name.apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END })
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        addView(col, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(caret.apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }, LayoutParams(dp(24f), dp(24f)))
        isClickable = true
        isFocusable = true
        setOnClickListener { click() }
        asButton(this)
    }

    private fun styleChip(chip: View, selected: Boolean, texts: List<TextView>, caret: IconView) {
        chip.background = GradientDrawable().apply {
            cornerRadius = 22f * d
            setColor(if (selected) theme.pressed else theme.key)
            if (selected) setStroke(dp(2f), theme.accentText) else if (theme.hasBorder) setStroke(dp(1f), theme.border)
        }
        chip.isSelected = selected
        texts.forEach { it.setTextColor(theme.keyText) }
        caret.color = theme.keyText
    }

    private fun button(v: View, desc: Int, click: () -> Unit) {
        v.contentDescription = context.getString(desc)
        v.isClickable = true
        v.isFocusable = true
        v.setOnClickListener { click() }
        asButton(v)
    }

    /** A pressed-state background: transparent, the pressed fill while touched (round for icon buttons). */
    private fun pressable(color: Int, radiusDp: Float) = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { cornerRadius = radiusDp * d; setColor(theme.stripDivider) })
        addState(intArrayOf(), GradientDrawable().apply { cornerRadius = radiusDp * d; setColor(color) })
    }

    /** A 40 dp pill button: [primary] on the accent; else tonal on the key fill ([onCard]: on the pressed fill, inside a card). */
    private fun pill(
        label: String,
        primary: Boolean = false,
        selected: Boolean = false,
        small: Boolean = false,
        onCard: Boolean = false,
        icon: ToolIcon? = null,
        click: () -> Unit,
    ): TextView = TextView(context).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 13f else 14f)
        if (primary) setTypeface(typeface, Typeface.BOLD)
        val fg = if (primary) theme.onAccent else theme.keyText
        setTextColor(fg)
        gravity = Gravity.CENTER
        maxLines = 1
        minHeight = dp(if (small) 36f else 40f)
        setPadding(dp(if (icon != null) 12f else 16f), 0, dp(16f), 0)
        if (icon != null) {
            val px = dp(18f)
            setCompoundDrawables(ToolIconDrawable(icon, fg, px).also { it.setBounds(0, 0, px, px) }, null, null, null)
            compoundDrawablePadding = dp(6f)
        }
        background = GradientDrawable().apply {
            cornerRadius = 20f * d
            setColor(
                when {
                    primary -> theme.accent
                    selected || onCard -> theme.pressed
                    else -> theme.key
                },
            )
            if (selected) setStroke(dp(2f), theme.accentText) else if (!primary && !onCard && theme.hasBorder) setStroke(dp(1f), theme.border)
        }
        isSelected = selected
        isClickable = true
        isFocusable = true
        contentDescription = label
        setOnClickListener { click() }
        asButton(this, if (small) selected else null)
        layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(4f), dp(4f), dp(4f), dp(4f))
        }
    }

    private fun asButton(v: View, checked: Boolean? = null) {
        ViewCompat.setAccessibilityDelegate(
            v,
            object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                    if (checked != null) {
                        info.isCheckable = true
                        info.isChecked = checked
                    }
                }
            },
        )
    }

    /** One icon of the keyboard's family ([ToolIcons]) centred in the view, in [color]. */
    internal class IconView(context: Context, val icon: ToolIcon, private val sizeDp: Float) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var color: Int = 0xFF000000.toInt()
            set(value) {
                if (field == value) return
                field = value
                invalidate()
            }

        override fun onDraw(canvas: Canvas) {
            ToolIcons.draw(canvas, icon, width / 2f, height / 2f, sizeDp * resources.displayMetrics.density, color, paint)
        }
    }

    /** A ScrollView that never grows past [maxPx]. */
    private class MaxHeightScroll(context: Context, private val maxPx: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.AT_MOST))
        }
    }

    internal val translateButtonView: TextView get() = translateButton
    internal val sourceChipView: View get() = sourceChip
    internal val destinationChipView: View get() = destChip
    internal val swapButtonView: View get() = swapButton
    internal val settingsButtonView: View get() = settingsButton
    internal val closeButtonView: View get() = closeButton
    internal val clearButtonView: View get() = clearButton
    internal val sentenceChipView: View get() = sentenceChip
    internal val inputField0: KeyboardTextField get() = inputField
    internal val inputEditor: EditorView get() = inputField.editor
    internal val inputLabelView: TextView get() = inputField.labelTextView
    internal val outputView: LinearLayout get() = output

    companion object {
        /** The translation scrolls past this height (dp), so the panel stays compact above the keys. */
        const val RESULT_MAX_DP = 104f
    }
}
