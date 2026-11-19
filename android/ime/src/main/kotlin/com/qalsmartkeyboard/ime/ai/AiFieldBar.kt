package com.qalsmartkeyboard.ime.ai

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.theme.KeyboardTheme
import com.qalsmartkeyboard.ime.theme.ThemeCatalog

/**
 * Shown in the bar's place while the keys type into an AI panel field (ADR 0031; like Word Art's strip): which field,
 * its count against the limit, the end of the text (two lines, the newest text stays visible) and Done. Done, Enter on
 * a one-line field, the emoji key and Back return to the panel. A fixed [HEIGHT_DP], so typing never resizes the keyboard.
 */
class AiFieldBar(context: Context) : LinearLayout(context) {
    var workspace: AiWorkspace? = null

    /** Done: the service commits the open composition into the field, then ends typing. */
    var onDone: () -> Unit = {}

    var theme: KeyboardTheme = ThemeCatalog.classic(
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES,
    )
        set(value) {
            if (field === value) return
            field = value
            restyle()
        }

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).toInt()

    private val label = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }
    private val text = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        maxLines = 2
        ViewCompat.setAccessibilityLiveRegion(this, ViewCompat.ACCESSIBILITY_LIVE_REGION_NONE)
    }
    private val box = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12f), dp(2f), dp(12f), dp(2f))
        addView(label)
        addView(text)
    }
    private val done = TextView(context).apply {
        text = context.getString(R.string.wordart_done)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minWidth = dp(64f)
        setPadding(dp(16f), 0, dp(16f), 0)
        contentDescription = context.getString(R.string.ai_typing_done)
        isClickable = true
        isFocusable = true
        setOnClickListener { onDone() }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
        addView(box, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = dp(8f) })
        addView(done, LayoutParams(LayoutParams.WRAP_CONTENT, dp(48f)))
        restyle()
    }

    private fun restyle() {
        val t = theme
        setBackgroundColor(t.stripBg)
        box.background = GradientDrawable().apply { setColor(t.key); cornerRadius = 14f * d }
        label.setTextColor(t.hintText)
        text.setTextColor(t.keyText)
        text.setHintTextColor(t.hintText)
        done.background = GradientDrawable().apply { setColor(t.accent); cornerRadius = 24f * d }
        done.setTextColor(t.onAccent)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(HEIGHT_DP), MeasureSpec.EXACTLY))
    }

    /** Re-reads the field being typed into. Cheap: called on every key while typing. */
    fun render() {
        val w = workspace ?: return
        val f = w.typingField ?: return
        val name = fieldLabel(w)
        label.text = context.getString(R.string.ai_typing_label, name, AiMessages.count(f.length, f.limit))
        val t = f.text
        text.hint = context.getString(R.string.ai_typing_hint)
        // Typing happens at the end: show the end (two lines hold about this much on a phone).
        val tail = if (t.length > TAIL_CHARS) "…" + t.substring(t.length - TAIL_CHARS) else t
        text.text = if (t.isEmpty()) "" else tail + "▏"
        box.contentDescription = context.getString(R.string.ai_typing_field, name, t)
    }

    private fun fieldLabel(w: AiWorkspace): String = context.getString(
        when (w.screen) {
            AiScreen.ASSIST -> when (w.assist.typing) {
                AiAssistField.NOTE -> R.string.ai_reply_note_label
                else -> R.string.ai_source_typed
            }
            AiScreen.EMAIL -> when (w.email.typing) {
                AiEmailField.INSTRUCTIONS -> R.string.ai_email_instructions_new
                AiEmailField.ORIGINAL -> R.string.ai_email_original_label
                AiEmailField.DRAFT -> R.string.ai_email_draft_label
                AiEmailField.RECIPIENT -> R.string.ai_email_recipient
                AiEmailField.SENDER -> R.string.ai_email_sender
                AiEmailField.SUBJECT -> R.string.ai_email_subject
                AiEmailField.BODY, null -> R.string.ai_email_body
            }
            AiScreen.CLOSED -> R.string.ai_source_typed
        },
    )

    internal val doneView: View get() = done
    internal val textView: TextView get() = text

    companion object {
        const val HEIGHT_DP = 64f
        private const val TAIL_CHARS = 90
    }
}
