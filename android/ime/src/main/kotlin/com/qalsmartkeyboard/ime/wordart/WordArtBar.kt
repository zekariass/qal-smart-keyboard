package com.qalsmartkeyboard.ime.wordart

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.qalsmartkeyboard.ime.CandidateStripView
import com.qalsmartkeyboard.ime.KeyboardController
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.theme.KeyboardTheme
import com.qalsmartkeyboard.ime.theme.ThemeCatalog

/**
 * The strip shown in the bar's place while the Word Art text field is being typed into (ADR 0030, like the emoji search
 * strip): the keys are back, this shows what was typed so far (the end stays visible) and a Done button. Done, the Enter
 * key, the emoji key and system Back return to the Word Art panel. Always as tall as the bar it replaces.
 */
class WordArtBar(context: Context) : LinearLayout(context) {
    var controller: KeyboardController? = null

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

    private val textField = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setSingleLine()
        ellipsize = TextUtils.TruncateAt.START
        setPadding(dp(12f), 0, dp(12f), 0)
    }
    private val done = TextView(context).apply {
        text = context.getString(R.string.wordart_done)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(14f), 0, dp(14f), 0)
        contentDescription = context.getString(R.string.wordart_typing_done)
        isClickable = true
        isFocusable = true
        setOnClickListener { controller?.emoji?.endWordArtTyping() }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8f), dp(5f), dp(8f), dp(5f))
        addView(textField, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = dp(8f) })
        addView(done, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        restyle()
    }

    private fun restyle() {
        val t = theme
        setBackgroundColor(t.stripBg)
        textField.background = GradientDrawable().apply { setColor(t.key); cornerRadius = 20f * d }
        textField.setTextColor(t.keyText)
        textField.setHintTextColor(t.hintText)
        done.background = GradientDrawable().apply { setColor(t.accent); cornerRadius = 20f * d }
        done.setTextColor(t.onAccent)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((CandidateStripView.HEIGHT_DP * d).toInt(), MeasureSpec.EXACTLY))
    }

    /** Re-reads the text from the Word Art state. */
    fun render() {
        val text = controller?.emoji?.wordArt?.text.orEmpty()
        textField.hint = context.getString(R.string.wordart_typing_hint)
        textField.text = if (text.isEmpty()) "" else text + "▏"
        textField.contentDescription = context.getString(R.string.wordart_typing_field, text)
    }

    internal val fieldView: TextView get() = textField
    internal val doneView: View get() = done
}
