package com.qalsmartkeyboard.ime.ai

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.PathParser
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.qalsmartkeyboard.ime.theme.KeyboardTheme

/** Material-style 24×24 icon outlines used by the AI panels (drawn with the theme's colours, no image resources). */
object AiIcons {
    const val TRANSLATE = "M12.87,15.07l-2.54,-2.51 0.03,-0.03c1.74,-1.94 2.98,-4.17 3.71,-6.53L17,6L17,4h-7L10,2L8,2v2L1,4v1.99h11.17C11.5,7.92 10.44,9.75 9,11.35 8.07,10.32 7.3,9.19 6.69,8h-2c0.73,1.63 1.73,3.17 2.98,4.56l-5.09,5.02L4,19l5,-5 3.11,3.11 0.76,-2.04zM18.5,10h-2L12,22h2l1.12,-3h4.75L21,22h2l-4.5,-12zM15.88,17l1.62,-4.33L19.12,17h-3.24z"
    const val EXPLAIN = "M9,21c0,0.55 0.45,1 1,1h4c0.55,0 1,-0.45 1,-1v-1L9,20v1zM12,2C8.14,2 5,5.14 5,9c0,2.38 1.19,4.47 3,5.74L8,17c0,0.55 0.45,1 1,1h6c0.55,0 1,-0.45 1,-1v-2.26c1.81,-1.27 3,-3.36 3,-5.74 0,-3.86 -3.14,-7 -7,-7z"
    const val GRAMMAR = "M12.45,16h2.09L9.43,3L7.57,3L2.46,16h2.09l1.12,-3h5.64l1.14,3zM6.43,11L8.5,5.48 10.57,11L6.43,11zM21.59,11.59l-8.09,8.09L9.83,16l-1.41,1.41 5.09,5.09L23,13l-1.41,-1.41z"
    const val REWRITE = "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z"
    const val PROFESSIONAL = "M20,6h-4L16,4c0,-1.11 -0.89,-2 -2,-2h-4c-1.11,0 -2,0.89 -2,2v2L4,6c-1.11,0 -1.99,0.89 -1.99,2L2,19c0,1.11 0.89,2 2,2h16c1.11,0 2,-0.89 2,-2L22,8c0,-1.11 -0.89,-2 -2,-2zM14,6h-4L10,4h4v2z"
    const val SIMPLIFY = "M4,9h16v2H4zM4,13h10v2H4z"
    const val REPLY = "M10,9V5l-7,7 7,7v-4.1c5,0 8.5,1.6 11,5.1 -1,-5 -4,-10 -11,-11z"
    const val SUMMARIZE = "M14,17H4v2h10v-2zM20,9H4v2h16V9zM4,15h16v-2H4v2zM4,5v2h16V5H4z"
    const val FORMAT = "M4,10.5c-0.83,0 -1.5,0.67 -1.5,1.5s0.67,1.5 1.5,1.5 1.5,-0.67 1.5,-1.5 -0.67,-1.5 -1.5,-1.5zM4,4.5c-0.83,0 -1.5,0.67 -1.5,1.5S3.17,7.5 4,7.5 5.5,6.83 5.5,6 4.83,4.5 4,4.5zM4,16.5c-0.83,0 -1.5,0.68 -1.5,1.5s0.68,1.5 1.5,1.5 1.5,-0.68 1.5,-1.5 -0.67,-1.5 -1.5,-1.5zM7,19h14v-2L7,17v2zM7,13h14v-2L7,11v2zM7,5v2h14L21,5L7,5z"
    const val PASTE = "M19,2h-4.18C14.4,0.84 13.3,0 12,0c-1.3,0 -2.4,0.84 -2.82,2L5,2c-1.1,0 -2,0.9 -2,2v16c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2L21,4c0,-1.1 -0.9,-2 -2,-2zM12,2c0.55,0 1,0.45 1,1s-0.45,1 -1,1 -1,-0.45 -1,-1 0.45,-1 1,-1zM19,20L5,20L5,4h2v3h10L17,4h2v16z"
    const val COPY = "M16,1L4,1c-1.1,0 -2,0.9 -2,2v14h2L4,3h12L16,1zM19,5L8,5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2L21,7c0,-1.1 -0.9,-2 -2,-2zM19,21L8,21L8,7h11v14z"
    const val EMAIL = "M20,4L4,4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2L22,6c0,-1.1 -0.9,-2 -2,-2zM20,8l-8,5 -8,-5L4,6l8,5 8,-5v2z"
    const val REFRESH = "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7L20,4l-2.35,2.35z"
    const val BACK = "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z"
    const val SWAP = "M6.99,11L3,15l3.99,4v-3H14v-2H6.99v-3zM21,9l-3.99,-4v3H10v2h7.01v3L21,9z"
    const val INSERT = "M19,7v4H5.83l3.58,-3.59L8,6l-6,6 6,6 1.41,-1.41L5.83,13H21V7z"
    const val CLOSE = "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z"
    const val OPEN = "M19,19H5V5h7V3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2v-7h-2v7zM14,3v2h3.59l-9.83,9.83 1.41,1.41L19,6.41V10h2V3h-7z"
    const val LOCK = "M18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2zM12,17c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2zM15.1,8H8.9V6c0,-1.71 1.39,-3.1 3.1,-3.1 1.71,0 3.1,1.39 3.1,3.1v2z"
    const val ASSIST = "M19,9l1.25,-2.75L23,5l-2.75,-1.25L19,1l-1.25,2.75L15,5l2.75,1.25L19,9zM11.5,9.5L9,4 6.5,9.5 1,12l5.5,2.5L9,20l2.5,-5.5L17,12l-5.5,-2.5zM19,15l-1.25,2.75L15,19l2.75,1.25L19,23l1.25,-2.75L23,19l-2.75,-1.25L19,15z"
    const val EDIT = REWRITE
    const val KEYBOARD = "M20,5H4c-1.1,0 -1.99,0.9 -1.99,2L2,17c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2zM11,8h2v2h-2V8zM11,11h2v2h-2v-2zM8,8h2v2H8V8zM8,11h2v2H8v-2zM7,13H5v-2h2v2zM7,10H5V8h2v2zM16,17H8v-2h8v2zM16,13h-2v-2h2v2zM16,10h-2V8h2v2zM19,13h-2v-2h2v2zM19,10h-2V8h2v2z"
    const val CHECK = "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z"

    fun of(action: AiAssistAction): String = when (action) {
        AiAssistAction.TRANSLATE -> TRANSLATE
        AiAssistAction.EXPLAIN -> EXPLAIN
        AiAssistAction.GRAMMAR -> GRAMMAR
        AiAssistAction.REWRITE -> REWRITE
        AiAssistAction.PROFESSIONAL -> PROFESSIONAL
        AiAssistAction.SIMPLIFY -> SIMPLIFY
        AiAssistAction.REPLY -> REPLY
        AiAssistAction.SUMMARIZE -> SUMMARIZE
        AiAssistAction.FORMAT -> FORMAT
    }
}

/** A 24×24 path icon scaled to the drawable's bounds, in one colour. Cheap: the path is parsed once per drawable. */
class AiIconDrawable(pathData: String, color: Int, private val sizePx: Int) : Drawable() {
    private val path: Path = try { PathParser.createPathFromPathData(pathData) } catch (e: RuntimeException) { Path() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(b.width() / 24f, b.height() / 24f)
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * The AI panels' view kit (ADR 0031): chips, buttons, cards and labels drawn with [theme]'s tokens (the pairs
 * `ThemeContrastTest` checks: key text on key fill, strip text and warn on the panel background, on-accent on accent,
 * key text on the pressed fill). Touch targets: buttons 48 dp, chips 40 dp. Every clickable view has the Button role.
 */
class AiUi(val context: Context, val theme: KeyboardTheme) {
    val d = context.resources.displayMetrics.density
    fun dp(v: Float) = (v * d).toInt()

    private val chipStroke get() = if (theme.hasBorder) theme.border else theme.special

    fun icon(pathData: String, color: Int, sizeDp: Float = 18f): Drawable =
        AiIconDrawable(pathData, color, dp(sizeDp)).also { it.setBounds(0, 0, dp(sizeDp), dp(sizeDp)) }

    fun column(padH: Float = 0f, padV: Float = 0f) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
    }

    fun row(gravity: Int = Gravity.CENTER_VERTICAL) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        this.gravity = gravity
    }

    /** A sideways-scrolling row (chips never truncate; the cut edge fades). */
    fun scrollRow(vararg views: View): HorizontalScrollView = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength(dp(20f))
        val inner = row()
        views.forEach { inner.addView(it) }
        addView(inner)
    }

    /** Text on the panel background. [secondary]: the hint colour (3:1); [warn]: the caution colour. */
    fun label(text: CharSequence, sizeSp: Float = 13f, secondary: Boolean = false, warn: Boolean = false, bold: Boolean = false) =
        TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(if (warn) theme.warn else if (secondary) theme.hintText else theme.stripText)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    /** A rounded card on the key fill (text inside uses [onCard]). */
    fun card(selected: Boolean = false): LinearLayout = column(12f, 8f).apply {
        background = GradientDrawable().apply {
            cornerRadius = 12f * d
            setColor(if (selected) theme.pressed else theme.key)
            if (selected) setStroke(dp(2f), theme.accentText) else if (theme.hasBorder) setStroke(dp(1f), theme.border)
        }
    }

    fun onCard(text: CharSequence, sizeSp: Float = 15f, secondary: Boolean = false, bold: Boolean = false, maxLines: Int = Int.MAX_VALUE) =
        TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(if (secondary) theme.hintText else theme.keyText)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            this.maxLines = maxLines
            if (maxLines != Int.MAX_VALUE) ellipsize = TextUtils.TruncateAt.END
        }

    /** A chip (40 dp): optional icon, selected state, a lock for plan-gated actions. */
    fun chip(
        text: CharSequence,
        iconPath: String? = null,
        selected: Boolean = false,
        enabled: Boolean = true,
        locked: Boolean = false,
        description: CharSequence? = null,
        click: () -> Unit,
    ): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        val fg = if (enabled) theme.keyText else theme.hintText
        setTextColor(fg)
        gravity = Gravity.CENTER
        isSingleLine = true
        minHeight = dp(40f)
        minimumHeight = dp(40f)
        setPadding(dp(12f), 0, dp(14f), 0)
        compoundDrawablePadding = dp(6f)
        val start = iconPath?.let { icon(it, fg, 18f) }
        val end = if (locked) icon(AiIcons.LOCK, theme.hintText, 14f) else null
        setCompoundDrawables(start, null, end, null)
        background = GradientDrawable().apply {
            cornerRadius = 20f * d
            setColor(if (selected) theme.pressed else theme.key)
            setStroke(dp(if (selected) 2f else 1f), if (selected) theme.accentText else chipStroke)
        }
        isEnabled = enabled
        isClickable = enabled
        isSelected = selected
        contentDescription = description ?: text
        setOnClickListener { click() }
        asButton(this, selected)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(3f), dp(3f), dp(3f), dp(3f))
        }
    }

    /** A 48 dp button: [primary] on the accent fill, else on the key fill. */
    fun button(
        text: CharSequence,
        iconPath: String? = null,
        primary: Boolean = false,
        enabled: Boolean = true,
        description: CharSequence? = null,
        click: () -> Unit,
    ): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.DEFAULT_BOLD
        val fg = when {
            !enabled -> theme.hintText
            primary -> theme.onAccent
            else -> theme.keyText
        }
        setTextColor(fg)
        gravity = Gravity.CENTER
        isSingleLine = true
        minHeight = dp(48f)
        minimumHeight = dp(48f)
        setPadding(dp(16f), 0, dp(16f), 0)
        compoundDrawablePadding = dp(8f)
        setCompoundDrawables(iconPath?.let { icon(it, fg, 20f) }, null, null, null)
        background = GradientDrawable().apply {
            cornerRadius = 24f * d
            setColor(if (primary && enabled) theme.accent else theme.key)
            if (!primary || !enabled) setStroke(dp(1f), chipStroke)
        }
        isEnabled = enabled
        isClickable = enabled
        contentDescription = description ?: text
        setOnClickListener { click() }
        asButton(this, null)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(4f), dp(4f), dp(4f), dp(4f))
        }
    }

    /** A 48 dp round icon button on the panel background. */
    fun iconButton(pathData: String, description: CharSequence, click: () -> Unit): View = TextView(context).apply {
        val size = dp(48f)
        minWidth = size
        minHeight = size
        gravity = Gravity.CENTER
        setCompoundDrawables(icon(pathData, theme.stripText, 22f), null, null, null)
        setPadding(dp(13f), 0, dp(13f), 0)
        contentDescription = description
        isClickable = true
        isFocusable = true
        setOnClickListener { click() }
        asButton(this, null)
        layoutParams = LinearLayout.LayoutParams(size, size)
    }

    /** A tappable text field (the keys type into it after the tap): a card with a label, the text or a hint, and a count. */
    fun field(
        label: CharSequence,
        text: String,
        hint: CharSequence,
        count: CharSequence?,
        over: Boolean,
        maxLines: Int,
        description: CharSequence,
        click: () -> Unit,
    ): LinearLayout = card().apply {
        val head = row()
        head.addView(onCard(label, 12f, secondary = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (count != null) {
            // Over the limit: the count turns bold in the full text colour (the reason is shown under the card in the caution colour).
            head.addView(onCard(count, 12f, secondary = !over, bold = over))
        }
        addView(head)
        addView(
            onCard(if (text.isEmpty()) hint else text, 15f, secondary = text.isEmpty(), maxLines = maxLines).apply {
                setPadding(0, dp(2f), 0, 0)
            },
        )
        isClickable = true
        isFocusable = true
        minimumHeight = dp(48f)
        contentDescription = description
        setOnClickListener { click() }
        asButton(this, null)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(4f), 0, dp(4f))
        }
    }

    fun spacer(hDp: Float) = View(context).apply { layoutParams = LinearLayout.LayoutParams(1, dp(hDp)) }

    /** Gives a clickable view the Button role (and a checked state for toggles) for accessibility services. */
    fun asButton(v: View, checked: Boolean?) {
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

    /** A vertical scroll view whose content fills the width. */
    fun scroll(content: View): ScrollView = ScrollView(context).apply {
        isVerticalScrollBarEnabled = true
        isFillViewport = false
        addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}
