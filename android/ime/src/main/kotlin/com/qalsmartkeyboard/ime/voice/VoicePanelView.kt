package com.qalsmartkeyboard.ime.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.ai.AiDestination
import com.qalsmartkeyboard.ime.ai.AiLanguages
import com.qalsmartkeyboard.ime.theme.KeyboardTheme
import com.qalsmartkeyboard.ime.theme.ThemeCatalog

/**
 * The voice panel (ADR 0029): it replaces the key area at the same height while voice typing is open, so the keyboard
 * does not jump. Top row: the recognition-language chip (opens the picker) and "ABC" (back to the keyboard). Middle:
 * the one-time privacy note, the status line (listening, processing, error message), the preview of what was heard
 * (not in the field until it is final), the big button (stop while listening, with a level ring that follows the
 * voice; a microphone to start or retry) and Try again after an error. Next to the language chip, "Insert in: <language |
 * As spoken>" (T8.12, ADR 0049 D4) appears only while the controller offers insert languages; it opens a picker of the
 * same kind, and the status line says "Translating…" while the final result is being translated. The picker replaces the middle: am, ti, om, en
 * with whether each works here, then "More languages" when the speech service lists its languages.
 *
 * Standard views, so TalkBack reads every control with its role; the status line is a polite live region, so state
 * changes (listening, processing, errors) are announced. Colours: theme tokens only, in pairs the contrast test
 * covers (strip text and warn on the background, special label on the special fill, on-accent on the accent).
 * Recognised text is drawn here and nowhere else; it is never logged (N2).
 */
class VoicePanelView(context: Context) : LinearLayout(context) {
    var controller: VoiceController? = null
        set(value) {
            field = value
            value?.onLevel = { level -> button.setLevel(level) }
            render()
        }

    /** The height to take: the key area's. */
    var heightProvider: () -> Int = { 0 }

    var theme: KeyboardTheme = ThemeCatalog.classic(
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES,
    )
        set(value) {
            if (field === value) return
            field = value
            applyTheme()
        }

    private val d = resources.displayMetrics.density

    val chip: TextView = pill().apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setOnClickListener { controller?.let { c -> if (c.pickerOpen) c.closePicker() else c.openPicker() } }
    }
    /** "Insert in: Amharic" (T8.12): shown only while the controller offers insert languages. */
    val insertChip: TextView = pill().apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        visibility = GONE
        setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.pickerOpen) c.closePicker()
            insertPickerOpen = !insertPickerOpen
            render()
        }
    }
    val back: TextView = pill().apply {
        text = context.getString(R.string.voice_back_label)
        contentDescription = context.getString(R.string.voice_back)
        setOnClickListener { controller?.backToKeyboard() }
    }
    val notice: TextView = label(13f).apply { text = context.getString(R.string.voice_notice) }
    val status: TextView = label(16f).apply {
        typeface = Typeface.DEFAULT_BOLD
        ViewCompat.setAccessibilityLiveRegion(this, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
    }
    val transcript: TextView = label(18f).apply {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.START
    }
    val button = MainButton(context).apply { setOnClickListener { controller?.onMainButton() } }
    val retry: TextView = pill().apply {
        text = context.getString(R.string.voice_retry)
        setOnClickListener { controller?.retry() }
    }

    private var insertPickerOpen = false

    /** The status / button area, hidden while a picker covers it (tests). */
    internal val bodyView: View get() = body

    /** What the "Insert in" chip reads and does; null reads the controller (a test supplies its own). */
    internal var insertSource: InsertLanguageSource? = null

    internal interface InsertLanguageSource {
        val choices: List<AiDestination>
        val current: String?
        val translating: Boolean
        fun choose(language: String?)
    }

    private class ControllerInsert(private val c: VoiceController) : InsertLanguageSource {
        override val choices get() = c.insertLanguageChoices()
        override val current get() = c.insertLanguage
        override val translating get() = c.insertTranslating
        override fun choose(language: String?) = c.chooseInsertLanguage(language)
    }

    private val body = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val p = (12 * d).toInt()
        setPadding(p, 0, p, 0)
        addView(notice, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = (6 * d).toInt() })
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(transcript, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (4 * d).toInt() })
        addView(button, LayoutParams((BUTTON_DP * d).toInt(), (BUTTON_DP * d).toInt()).apply { topMargin = (8 * d).toInt() })
        addView(retry, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
    }

    /** The picker's rows (language options, "More languages", back). */
    val pickerList = LinearLayout(context).apply { orientation = VERTICAL }
    private val picker = ScrollView(context).apply {
        isFillViewport = true
        addView(pickerList, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }

    init {
        orientation = VERTICAL
        val top = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = (8 * d).toInt()
            setPadding(p, p, p, p)
            addView(chip, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = (8 * d).toInt() })
            addView(insertChip, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = (8 * d).toInt() })
            addView(back, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        addView(top, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val middle = FrameLayout(context).apply {
            addView(body, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(picker, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        addView(middle, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        applyTheme()
        render()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = heightProvider()
        super.onMeasure(widthMeasureSpec, if (h > 0) MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY) else heightMeasureSpec)
    }

    private fun label(sp: Float) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun pill() = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minHeight = (40 * d).toInt()
        val h = (14 * d).toInt()
        setPadding(h, 0, h, 0)
        isClickable = true
        isFocusable = true
        accessibilityDelegate = ButtonRole
    }

    private fun applyTheme() {
        val t = theme
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, if (t.isGradient) t.backgroundStops else intArrayOf(t.bgTop, t.bgTop))
        for (v in listOf(chip, insertChip, back, retry)) {
            v.background = GradientDrawable().apply {
                cornerRadius = 20f * d
                setColor(t.special)
            }
            v.setTextColor(t.specialText)
        }
        notice.setTextColor(t.stripText)
        transcript.setTextColor(t.stripText)
        button.theme = t
        render()
    }

    private var shownOptions: List<Any> = emptyList()

    /** Re-reads the controller's state. Cheap; the picker rows are rebuilt only when they changed. */
    fun render() {
        val c = controller ?: return
        val tag = c.currentTag
        val name = VoiceLanguages.displayName(tag)
        chip.text = "$name  ▾"
        chip.contentDescription = context.getString(R.string.voice_language_chip, name)
        val ins = insertSource ?: ControllerInsert(c)
        val choices = ins.choices
        if (choices.isEmpty() || c.pickerOpen) insertPickerOpen = false
        insertChip.visibility = if (choices.isEmpty()) GONE else VISIBLE
        if (choices.isNotEmpty()) {
            val current = ins.current
            val target = current?.let { l -> choices.firstOrNull { it.language == l }?.label ?: AiLanguages.name(l) }
                ?: context.getString(R.string.voice_insert_as_spoken)
            insertChip.text = "${context.getString(R.string.voice_insert_chip, target)}  ▾"
            insertChip.contentDescription = context.getString(R.string.voice_insert_chip_desc, target)
        }
        val pickerShown = c.pickerOpen || insertPickerOpen
        notice.visibility = if (c.showNotice && !pickerShown) VISIBLE else GONE
        val s = c.state
        val failed = s is VoiceState.Failed
        status.setTextColor(if (failed) theme.warn else theme.stripText)
        status.text = if (s is VoiceState.Processing && ins.translating) context.getString(R.string.voice_translating) else statusText(s)
        val partial = when (s) {
            is VoiceState.Listening -> s.partial
            is VoiceState.Processing -> s.partial
            else -> ""
        }
        transcript.text = partial
        transcript.visibility = if (partial.isEmpty()) GONE else VISIBLE
        button.mode = when (s) {
            VoiceState.Preparing, is VoiceState.Listening -> MainButton.Mode.STOP
            is VoiceState.Processing -> MainButton.Mode.BUSY
            else -> MainButton.Mode.MIC
        }
        button.contentDescription = context.getString(
            when (button.mode) {
                MainButton.Mode.STOP -> R.string.voice_stop
                MainButton.Mode.BUSY -> R.string.voice_processing
                MainButton.Mode.MIC -> if (failed) R.string.voice_retry else R.string.voice_start
            },
        )
        button.isEnabled = button.mode != MainButton.Mode.BUSY
        if (button.mode != MainButton.Mode.STOP) button.setLevel(0f)
        retry.visibility = if (failed && (s as VoiceState.Failed).kind != VoiceErrorKind.UNAVAILABLE) VISIBLE else GONE
        body.visibility = if (pickerShown) INVISIBLE else VISIBLE
        picker.visibility = if (pickerShown) VISIBLE else GONE
        when {
            insertPickerOpen -> renderInsertPicker(c, ins)
            c.pickerOpen -> renderPicker(c)
            else -> shownOptions = emptyList()
        }
    }

    private fun statusText(s: VoiceState): String = when (s) {
        VoiceState.Idle -> ""
        VoiceState.Preparing -> context.getString(R.string.voice_preparing)
        is VoiceState.Listening -> context.getString(if (s.partial.isEmpty()) R.string.voice_listening else R.string.voice_listening_short)
        is VoiceState.Processing -> context.getString(R.string.voice_processing)
        VoiceState.Ready -> context.getString(R.string.voice_ready)
        is VoiceState.Failed -> errorText(context, s.kind, s.tag)
    }

    private fun renderPicker(c: VoiceController) {
        val more = c.moreOpen
        val options = if (more) c.moreOptions() else c.languageOptions()
        val hasMore = !more && !c.pickerLoading && c.moreOptions().isNotEmpty()
        val key: List<Any> = listOf(more, c.pickerLoading, hasMore) + options
        if (key == shownOptions) return
        shownOptions = key
        pickerList.removeAllViews()
        pickerList.addView(rowLabel(context.getString(R.string.voice_picker_title), bold = true))
        if (c.pickerLoading) pickerList.addView(rowLabel(context.getString(R.string.voice_picker_checking), bold = false))
        if (more) pickerList.addView(row(context.getString(R.string.voice_more_back), null) { c.showMore(false) })
        for (o in options) {
            val state = when (o.support) {
                SupportLevel.ON_DEVICE -> R.string.voice_support_offline
                SupportLevel.ONLINE -> R.string.voice_support_online
                SupportLevel.UNKNOWN -> R.string.voice_support_unknown
                SupportLevel.UNSUPPORTED -> R.string.voice_support_none
            }.let { if (c.pickerLoading && o.support == SupportLevel.UNKNOWN) null else context.getString(it) }
            val title = if (o.selected) "✓ ${o.label}" else o.label
            pickerList.addView(row(title, state, selected = o.selected) { c.selectLanguage(o.tag) })
        }
        if (hasMore) pickerList.addView(row(context.getString(R.string.voice_more_languages) + "  ›", null) { c.showMore(true) })
    }

    /** The "Insert in" picker: As spoken, then the translation languages; the current one is marked and selected = done. */
    private fun renderInsertPicker(c: VoiceController, ins: InsertLanguageSource) {
        val choices = ins.choices
        val current = ins.current
        val key: List<Any> = listOf("insert", current ?: "") + choices.map { it.language to it.label }
        if (key == shownOptions) return
        shownOptions = key
        pickerList.removeAllViews()
        pickerList.addView(rowLabel(context.getString(R.string.voice_insert_picker_title), bold = true))
        fun choose(language: String?) {
            ins.choose(language)
            insertPickerOpen = false
            render()
        }
        val spoken = context.getString(R.string.voice_insert_as_spoken)
        pickerList.addView(row(if (current == null) "✓ $spoken" else spoken, null, selected = current == null) { choose(null) })
        for (o in choices) {
            val on = o.language == current
            pickerList.addView(row(if (on) "✓ ${o.label}" else o.label, null, selected = on) { choose(o.language) })
        }
    }

    private fun rowLabel(text: String, bold: Boolean) = label(if (bold) 15f else 13f).apply {
        this.text = text
        gravity = Gravity.START
        setTextColor(theme.stripText)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        val p = (12 * d).toInt()
        setPadding(p, (6 * d).toInt(), p, (6 * d).toInt())
    }

    private fun row(title: String, detail: String?, selected: Boolean = false, onClick: () -> Unit) =
        TextView(context).apply {
            text = if (detail == null) title else "$title\n$detail"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(theme.stripText)
            if (selected) typeface = Typeface.DEFAULT_BOLD
            minHeight = (48 * d).toInt()
            gravity = Gravity.CENTER_VERTICAL
            val p = (12 * d).toInt()
            setPadding(p, (4 * d).toInt(), p, (4 * d).toInt())
            isClickable = true
            isFocusable = true
            accessibilityDelegate = ButtonRole
            if (selected) stateDescriptionCompat(this, context.getString(R.string.voice_selected))
            setOnClickListener { onClick() }
        }

    private fun stateDescriptionCompat(v: View, s: String) = ViewCompat.setStateDescription(v, s)

    /** Gives a clickable TextView the Button role for TalkBack. */
    private object ButtonRole : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = Button::class.java.name
        }
    }

    /**
     * The big round button: the accent fill with a stop square (listening), a microphone (start, retry) or three dots
     * (processing). While listening a ring around it grows with the input level ([setLevel], smoothed, one invalidate
     * per level change).
     */
    class MainButton(context: Context) : View(context) {
        enum class Mode { STOP, MIC, BUSY }

        var mode: Mode = Mode.MIC
            set(value) {
                if (field == value) return
                field = value
                invalidate()
            }

        var theme: KeyboardTheme = ThemeCatalog.classic(true)
            set(value) {
                field = value
                invalidate()
            }

        /** The smoothed level the ring draws (0..1). */
        var shownLevel: Float = 0f
            private set

        private val d = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        init {
            isClickable = true
            isFocusable = true
            ViewCompat.setAccessibilityDelegate(this, object : androidx.core.view.AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                }
            })
        }

        fun setLevel(level: Float) {
            // Rise fast, fall slowly: a calm ring that still reacts to speech.
            val next = if (level > shownLevel) level else shownLevel * 0.7f + level * 0.3f
            if (kotlin.math.abs(next - shownLevel) < 0.01f) return
            shownLevel = next
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val base = minOf(width, height) / 2f - RING_DP * d
            if (mode == Mode.STOP && shownLevel > 0f) {
                paint.style = Paint.Style.FILL
                paint.color = theme.accent
                paint.alpha = 70
                canvas.drawCircle(cx, cy, base + RING_DP * d * shownLevel, paint)
            }
            paint.alpha = 255
            paint.style = Paint.Style.FILL
            paint.color = theme.accent
            if (!isEnabled) paint.alpha = 160
            canvas.drawCircle(cx, cy, base, paint)
            paint.alpha = 255
            paint.color = theme.onAccent
            when (mode) {
                Mode.STOP -> {
                    val s = base * 0.36f
                    rect.set(cx - s, cy - s, cx + s, cy + s)
                    canvas.drawRoundRect(rect, s * 0.25f, s * 0.25f, paint)
                }
                Mode.BUSY -> for (i in -1..1) canvas.drawCircle(cx + i * base * 0.36f, cy, base * 0.1f, paint)
                Mode.MIC -> drawMic(canvas, cx, cy, base * 0.5f, paint, d)
            }
        }

        companion object {
            private const val RING_DP = 14f

            /** A microphone glyph of height about 2*[r] centred on ([cx], [cy]) in [paint]'s colour. */
            fun drawMic(canvas: Canvas, cx: Float, cy: Float, r: Float, paint: Paint, d: Float) {
                val w = r * 0.62f
                val top = cy - r
                val capsule = RectF(cx - w / 2, top, cx + w / 2, top + r * 1.25f)
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(capsule, w / 2, w / 2, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = maxOf(1.5f * d, r * 0.14f)
                paint.strokeCap = Paint.Cap.ROUND
                val arc = RectF(cx - w * 0.95f, top + r * 0.35f, cx + w * 0.95f, top + r * 1.55f)
                canvas.drawArc(arc, 0f, 180f, false, paint)
                canvas.drawLine(cx, arc.bottom, cx, cy + r, paint)
                canvas.drawLine(cx - w * 0.55f, cy + r, cx + w * 0.55f, cy + r, paint)
                paint.style = Paint.Style.FILL
            }
        }
    }

    companion object {
        private const val BUTTON_DP = 96f

        /** The plain message for a failure ([tag]'s language is named where it matters). */
        fun errorText(context: Context, kind: VoiceErrorKind, tag: String): String {
            val lang = VoiceLanguages.languageName(tag)
            val english = VoiceText.isEnglish(tag)
            return when (kind) {
                VoiceErrorKind.LANGUAGE_NOT_SUPPORTED ->
                    context.getString(if (english) R.string.voice_err_language_other else R.string.voice_err_language, lang)
                VoiceErrorKind.LANGUAGE_MAYBE_UNSUPPORTED -> context.getString(R.string.voice_err_language_maybe, lang)
                VoiceErrorKind.LANGUAGE_UNAVAILABLE -> context.getString(R.string.voice_err_language_download, lang)
                VoiceErrorKind.NETWORK -> context.getString(R.string.voice_err_network)
                VoiceErrorKind.AUDIO -> context.getString(R.string.voice_err_audio)
                VoiceErrorKind.NO_MATCH -> context.getString(R.string.voice_err_no_match)
                VoiceErrorKind.NO_SPEECH -> context.getString(R.string.voice_err_no_speech)
                VoiceErrorKind.BUSY -> context.getString(R.string.voice_err_busy)
                VoiceErrorKind.SERVER -> context.getString(R.string.voice_err_server)
                VoiceErrorKind.CLIENT -> context.getString(R.string.voice_err_client)
                VoiceErrorKind.PERMISSION -> context.getString(R.string.voice_err_permission)
                VoiceErrorKind.UNAVAILABLE -> context.getString(R.string.voice_err_unavailable)
            }
        }
    }
}
