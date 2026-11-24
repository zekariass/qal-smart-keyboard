package com.qalsmartkeyboard.ime.toolbar

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.qalsmartkeyboard.ime.theme.KeyboardTheme
import com.qalsmartkeyboard.ime.theme.ThemeCatalog

/** How a button of a utility panel is coloured (theme tokens only). */
enum class PadStyle { KEY, SPECIAL, ACCENT }

/**
 * The shared frame of the productivity panels (T8.3: calculator, converter, calendar, QR): a panel in place of the keys at
 * the keyboard's current height, with a vertically scrolling column inside (so a short keyboard never clips a button), and
 * helpers that build 48dp-minimum buttons, chips and cards from the theme's tokens. The bar above shows the back arrow and
 * the title (set by the service), like the other tool panels (ADR 0035).
 *
 * Subclasses build their views in [build] (called when the panel opens or the theme changes) and update texts in place
 * with their own `refresh` so a key press does not rebuild the pad. Plain Views; every button is one focusable Button node.
 */
abstract class UtilityPanelView(context: Context) : FrameLayout(context) {
    /** The height to take: the key area's, so the keyboard does not change size. */
    var heightProvider: () -> Int = { 0 }

    /** Whether a tap gives haptic feedback (the keys' setting). */
    var haptics: () -> Boolean = { true }

    var theme: KeyboardTheme = ThemeCatalog.classic(
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES,
    )
        set(value) {
            if (field === value) return
            field = value
            if (built) render()
        }

    protected val d = resources.displayMetrics.density
    protected fun dp(v: Float) = (v * d).toInt()

    protected val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
    }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(dp(24f))
        addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private var built = false

    /** Every button and chip made by the last [render], in order (tests and accessibility checks). */
    val controls: List<View> get() = made

    private val made = ArrayList<View>()

    init {
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = heightProvider().takeIf { it > 0 } ?: dp(DEFAULT_HEIGHT_DP)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    /** Fills [column] with the panel's views (called by [render] after the column was emptied). */
    protected abstract fun build()

    /** Rebuilds the panel from the theme and the controller's state. Called when it opens and when the theme changes. */
    open fun render() {
        built = true
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, if (theme.isGradient) theme.backgroundStops else intArrayOf(theme.bgTop, theme.bgTop))
        column.removeAllViews()
        made.clear()
        build()
        scroll.scrollTo(0, 0)
    }

    protected fun card(color: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = theme.cornerRadiusDp.coerceAtLeast(6f) * d
        setColor(color)
        if (theme.hasBorder) setStroke((theme.borderWidthDp * d).toInt().coerceAtLeast(1), theme.border)
    }

    protected fun textColorOf(style: PadStyle) = when (style) {
        PadStyle.KEY -> theme.keyText
        PadStyle.SPECIAL -> theme.specialText
        PadStyle.ACCENT -> theme.onAccent
    }

    protected fun fillOf(style: PadStyle) = when (style) {
        PadStyle.KEY -> theme.key
        PadStyle.SPECIAL -> theme.special
        PadStyle.ACCENT -> theme.accent
    }

    /** A button of at least 48dp: [label] on the [style] fill; [description] is its TalkBack name. */
    protected fun button(label: String, description: String, style: PadStyle = PadStyle.KEY, sp: Float = 18f, onClick: () -> Unit): TextView {
        val b = TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(textColorOf(style))
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD.takeIf { style != PadStyle.KEY } ?: Typeface.DEFAULT
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            minimumHeight = dp(MIN_TARGET_DP)
            minimumWidth = dp(MIN_TARGET_DP)
            setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
            isClickable = true
            isFocusable = true
            contentDescription = description
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), card(theme.pressed))
                addState(intArrayOf(), card(fillOf(style)))
            }
            setOnClickListener {
                if (haptics()) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
        }
        asButton(b)
        made += b
        return b
    }

    /** A selectable chip (mode tabs, kind choices): the accent fill when [selected]. */
    protected fun chip(label: String, description: String, selected: Boolean, onClick: () -> Unit): TextView =
        button(label, description, if (selected) PadStyle.ACCENT else PadStyle.KEY, sp = 14f, onClick = onClick).also {
            ViewCompat.setAccessibilityDelegate(
                it,
                object : AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = Button::class.java.name
                        info.isSelected = selected
                    }
                },
            )
        }

    /** A label on the keyboard background (small caption, hint). */
    protected fun caption(text: String, sp: Float = 12f, lines: Int = 2): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(theme.stripText)
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(4f), dp(2f), dp(4f), dp(2f))
    }

    /** A horizontal row of [views] with the given weights, [heightDp] minimum tall. */
    protected fun row(vararg cells: Pair<View, Float>, gapDp: Float = 4f, bottomDp: Float = 4f): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            cells.forEachIndexed { i, (v, w) ->
                addView(
                    v,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w).apply { if (i > 0) marginStart = dp(gapDp) },
                )
            }
        }.also { column.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(bottomDp) }) }

    /** A sideways-scrolling strip of chips that fills the width when they fit. */
    protected fun chipStrip(chips: List<View>): View {
        val inner = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        chips.forEachIndexed { i, c ->
            inner.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) marginStart = dp(4f) })
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(inner, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }.also { column.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4f) }) }
    }

    /** Registers a custom control (an icon button, a swatch) so [controls] lists it. */
    protected fun <V : View> track(v: V): V {
        made += v
        return v
    }

    /** [render] again after a state change, keeping a scrolled panel where the user left it. */
    protected fun rebuildKeepingScroll() {
        val y = scroll.scrollY
        render()
        if (y > 0) scroll.post { scroll.scrollTo(0, y) }
    }

    protected fun asButton(v: View) {
        ViewCompat.setAccessibilityDelegate(
            v,
            object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                }
            },
        )
    }

    companion object {
        /** The smallest touch target (ADR 0035). */
        const val MIN_TARGET_DP = 48f
        private const val DEFAULT_HEIGHT_DP = 260f
    }
}
