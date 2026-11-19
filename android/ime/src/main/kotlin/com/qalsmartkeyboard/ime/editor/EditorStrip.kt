package com.qalsmartkeyboard.ime.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.theme.KeyboardTheme
import com.qalsmartkeyboard.ime.theme.ThemeCatalog
import com.qalsmartkeyboard.ime.toolbar.ToolIcon
import com.qalsmartkeyboard.ime.toolbar.ToolIcons

/**
 * The strip shown in the bar's place while the keys type into one of the keyboard's own fields (ADR 0038): the AI
 * panels' fields ("Type it", ADR 0031 §4) and Word Art's text (ADR 0030). The keys stay below it, so the field is never
 * hidden behind them. It holds a [KeyboardTextField] (the field's name and count, or the action row; two lines of
 * editable text with a caret), a microphone that dictates into the field at the cursor ([onMic], shown while [micShown]),
 * and Done. A fixed [HEIGHT_DP], so typing, selecting or dictating never resizes the keyboard.
 */
open class EditorStrip(context: Context) : LinearLayout(context) {
    val field = KeyboardTextField(context).apply {
        fixedSlot = true
        fillHeight = true
        editor.maxLines = 4
        editor.minLines = 1
    }

    /** Done: the service commits the open composition into the field, then ends typing. */
    var onDone: () -> Unit = {}

    /** The microphone: dictation into this field starts (or stops). */
    var onMic: () -> Unit = {}

    var micShown: Boolean = false
        set(value) {
            field = value
            mic.visibility = if (value) VISIBLE else GONE
        }

    /** The microphone shows the listening state (dictation into this field is running). */
    var micActive: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            mic.active = value
            mic.contentDescription = context.getString(if (value) R.string.editor_dictate_stop else R.string.editor_dictate)
            ViewCompat.setStateDescription(mic, if (value) context.getString(R.string.voice_listening_short) else null)
        }

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

    private val box = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(10f), 0, dp(6f), 0)
        addView(field, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private val mic = MicButton(context).apply {
        contentDescription = context.getString(R.string.editor_dictate)
        isClickable = true
        isFocusable = true
        visibility = GONE
        setOnClickListener { onMic() }
        asButton(this)
    }

    private val done = TextView(context).apply {
        text = context.getString(R.string.wordart_done)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minWidth = dp(64f)
        setPadding(dp(14f), 0, dp(14f), 0)
        contentDescription = context.getString(R.string.ai_typing_done)
        isClickable = true
        isFocusable = true
        setOnClickListener { onDone() }
        asButton(this)
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
        addView(box, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = dp(6f) })
        val side = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(mic, LayoutParams(dp(48f), dp(44f)).apply { bottomMargin = dp(4f) })
            addView(done, LayoutParams(LayoutParams.WRAP_CONTENT, dp(44f)))
        }
        addView(side, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        restyle()
    }

    private fun restyle() {
        val t = theme
        setBackgroundColor(t.stripBg)
        box.background = GradientDrawable().apply {
            setColor(t.key)
            cornerRadius = 14f * d
            if (t.hasBorder) setStroke(dp(1f), t.border)
        }
        field.theme = t
        done.background = GradientDrawable().apply { setColor(t.accent); cornerRadius = 22f * d }
        done.setTextColor(t.onAccent)
        mic.iconColor = t.stripText
        mic.activeFill = t.accent
        mic.activeColor = t.onAccent
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(HEIGHT_DP), MeasureSpec.EXACTLY))
    }

    private fun asButton(v: View) = ViewCompat.setAccessibilityDelegate(
        v,
        object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        },
    )

    /** The keyboard's microphone icon; an accent disc behind it while listening. */
    internal class MicButton(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var iconColor = 0xFF000000.toInt()
        var activeFill = 0xFF1A73E8.toInt()
        var activeColor = 0xFFFFFFFF.toInt()
        var active = false
            set(value) {
                field = value
                invalidate()
            }

        override fun onDraw(canvas: Canvas) {
            val dd = resources.displayMetrics.density
            if (active) {
                paint.style = Paint.Style.FILL
                paint.color = activeFill
                canvas.drawCircle(width / 2f, height / 2f, 20f * dd, paint)
            }
            ToolIcons.draw(canvas, ToolIcon.MIC, width / 2f, height / 2f, 24f * dd, if (active) activeColor else iconColor, paint)
        }
    }

    internal val doneView: View get() = done
    internal val micView: View get() = mic
    val editorView: EditorView get() = this.field.editor

    companion object {
        /** The strip's fixed height (dp): the name line or action row, then about two lines of text. */
        const val HEIGHT_DP = 104f
    }
}
