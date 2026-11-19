package com.qalsmartkeyboard.ime.toolbar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.qalsmartkeyboard.ime.FidelVariant
import com.qalsmartkeyboard.ime.KeyLayoutChoice
import com.qalsmartkeyboard.ime.LatinLayout
import com.qalsmartkeyboard.ime.LayoutOption
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.theme.KeyboardTheme
import com.qalsmartkeyboard.ime.theme.ThemeCatalog

/** The name of a layout choice: the Latin arrangements' own names (never translated), the Fidel variants in the UI language. */
fun layoutName(c: Context, choice: KeyLayoutChoice): String = when (choice) {
    is KeyLayoutChoice.Latin -> choice.layout.label
    is KeyLayoutChoice.Fidel -> c.getString(
        when (choice.variant) {
            FidelVariant.TWO_KEY -> R.string.layout_fidel_two_key
            FidelVariant.LONG_PRESS -> R.string.layout_fidel_long_press
        },
    )
}

/** The one-line description of a layout choice, in the UI language (ADR 0032). */
fun layoutDescription(c: Context, choice: KeyLayoutChoice): String = c.getString(
    when (choice) {
        is KeyLayoutChoice.Latin -> when (choice.layout) {
            LatinLayout.QWERTY -> R.string.layout_desc_qwerty
            LatinLayout.AZERTY -> R.string.layout_desc_azerty
            LatinLayout.QWERTZ -> R.string.layout_desc_qwertz
            LatinLayout.DVORAK -> R.string.layout_desc_dvorak
            LatinLayout.COLEMAK -> R.string.layout_desc_colemak
        }
        is KeyLayoutChoice.Fidel -> when (choice.variant) {
            FidelVariant.TWO_KEY -> R.string.layout_desc_fidel_two_key
            FidelVariant.LONG_PRESS -> R.string.layout_desc_fidel_long_press
        }
    },
)

/** A small preview of a layout: the Latin arrangement's top letter row, the Fidel variant's way to type ሱ. */
fun layoutPreview(choice: KeyLayoutChoice): String = when (choice) {
    is KeyLayoutChoice.Latin -> choice.layout.rows.first().toList().joinToString(" ")
    is KeyLayoutChoice.Fidel -> when (choice.variant) {
        FidelVariant.TWO_KEY -> "ሰ + ሁ → ሱ"
        FidelVariant.LONG_PRESS -> "ስ · ሰ ሱ ሲ ሳ ሴ ሶ"
    }
}

/**
 * The Layout panel (ADR 0043), opened by the Tools grid's "Layout" tile: the current language's key layouts in place of the
 * keys, at the keyboard's height like the other tool panels (ADR 0035); the bar shows a back arrow and the title. One row
 * per layout: its name, a one-line description and a small preview, the one in force checked. A tap applies the layout
 * and returns to the keys ([onChoose]); the arrow or the system Back returns with nothing changed.
 *
 * Plain Views; each row is one focusable radio-button node for TalkBack ("Dvorak, …, checked"). Colours: theme tokens only.
 */
class LayoutPanelView(context: Context) : FrameLayout(context) {
    /** The height to take: the key area's, so the keyboard does not change size. */
    var heightProvider: () -> Int = { 0 }

    /** The layouts to show (read on every [render]). */
    var options: () -> List<LayoutOption> = { emptyList() }

    /** A layout was tapped. */
    var onChoose: (KeyLayoutChoice) -> Unit = {}

    /** Whether a tap gives haptic feedback (the keys' setting). */
    var haptics: () -> Boolean = { true }

    var theme: KeyboardTheme = ThemeCatalog.classic(
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES,
    )
        set(value) {
            if (field === value) return
            field = value
            render()
        }

    /** The options shown by the last [render], in order (tests). */
    var shown: List<LayoutOption> = emptyList()
        private set

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).toInt()

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
    }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(dp(24f))
        addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    init {
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = heightProvider().takeIf { it > 0 } ?: dp(DEFAULT_HEIGHT_DP)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    /** The row of [choice] in the current list, or null. */
    fun rowFor(choice: KeyLayoutChoice): View? = content.findViewWithTag(choice)

    /** Rebuilds the list from [options] and the theme. Called when the panel opens and when the theme changes. */
    fun render() {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, if (theme.isGradient) theme.backgroundStops else intArrayOf(theme.bgTop, theme.bgTop))
        content.removeAllViews()
        shown = options()
        for (o in shown) {
            content.addView(row(o), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4f) })
        }
        scroll.scrollTo(0, 0)
    }

    private fun card(color: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = theme.cornerRadiusDp.coerceAtLeast(6f) * d
        setColor(color)
        if (theme.hasBorder) setStroke((theme.borderWidthDp * d).toInt().coerceAtLeast(1), theme.border)
    }

    private fun row(o: LayoutOption): View {
        val name = layoutName(context, o.choice)
        val description = layoutDescription(context, o.choice)
        val r = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52f)
            setPadding(dp(14f), dp(6f), dp(10f), dp(6f))
            tag = o.choice
            isClickable = true
            isFocusable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), card(theme.pressed))
                addState(intArrayOf(), card(theme.key))
            }
            contentDescription = "$name, $description"
            setOnClickListener {
                if (haptics()) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onChoose(o.choice)
            }
        }
        // Name and preview on one line, the description under them: five layouts fit the key area of a phone.
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(label(name, 15f, theme.keyText, bold = true))
        head.addView(
            label(layoutPreview(o.choice), 12f, theme.hintText, mono = o.choice is KeyLayoutChoice.Latin),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12f) },
        )
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(head)
        texts.addView(label(description, 12f, theme.hintText, lines = 2))
        r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(Check(context, o.selected), LinearLayout.LayoutParams(dp(28f), dp(28f)).apply { marginStart = dp(8f) })
        ViewCompat.setAccessibilityDelegate(
            r,
            object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = RadioButton::class.java.name
                    info.isCheckable = true
                    info.isChecked = o.selected
                }
            },
        )
        return r
    }

    private fun label(text: String, sp: Float, color: Int, bold: Boolean = false, lines: Int = 1, mono: Boolean = false) = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (mono) typeface = Typeface.MONOSPACE
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** The selected row's check: the accent circle with a check mark; nothing on the other rows. */
    private inner class Check(context: Context, private val on: Boolean) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val icon = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            if (!on) return
            fill.color = theme.accent
            canvas.drawCircle(width / 2f, height / 2f, width / 2f, fill)
            ToolIcons.draw(canvas, ToolIcon.CHECK, width / 2f, height / 2f, width * 0.62f, theme.onAccent, icon, strokeScale = 1.3f)
        }
    }

    private companion object {
        const val DEFAULT_HEIGHT_DP = 260f
    }
}
