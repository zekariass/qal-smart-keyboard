package com.qalsmartkeyboard.ime.ai

import android.content.Context
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.theme.KeyboardTheme
import com.qalsmartkeyboard.ime.theme.ThemeCatalog
import com.qalsmartkeyboard.ime.toolbar.ToolIcon

/**
 * The translate panel's language list (ADR 0037): in place of the keys at the keyboard's height ([heightProvider]), like
 * Gboard's; the bar above shows a back arrow and "Translate from" / "Translate into". The source list starts with
 * "Detect language"; the destination list ends with the user's destination profiles. Each language shows its own name
 * and its name in the UI language; the current choice comes first with a check. A tap selects and returns to the panel.
 */
class TranslateLanguagesView(context: Context) : FrameLayout(context) {
    var controller: TranslateController? = null

    /** The height to take: the key area's, so the keyboard does not change size. */
    var heightProvider: () -> Int = { 0 }

    var haptics: () -> Boolean = { true }

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

    private val list = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8f), dp(4f), dp(8f), dp(8f))
    }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = true
        addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** The rows of the last [render], in order: (label, selected) — for tests. */
    var rows: List<Pair<String, Boolean>> = emptyList()
        private set

    init {
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        visibility = GONE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = heightProvider().takeIf { it > 0 } ?: dp(DEFAULT_HEIGHT_DP)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    /** Rebuilds the list for the open picker (called when it opens and when the theme changes). */
    fun render() {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, if (theme.isGradient) theme.backgroundStops else intArrayOf(theme.bgTop, theme.bgTop))
        list.removeAllViews()
        val c = controller
        val out = ArrayList<Pair<String, Boolean>>()
        when (c?.picker) {
            TranslatePicker.SOURCE -> {
                val dir = c.direction
                for (code in dir.sourceChoices()) {
                    val selected = code == dir.manualSource
                    if (code == null) {
                        val label = context.getString(R.string.translate_detect_language)
                        list.addView(item(label, null, selected) { c.chooseSource(null) })
                        out += label to selected
                    } else {
                        val self = AiLanguages.selfName(code)
                        val ui = AiLanguages.name(code)
                        list.addView(item(self, ui.takeIf { it != self }, selected, spoken = ui) { c.chooseSource(code) })
                        out += self to selected
                    }
                }
            }
            TranslatePicker.DESTINATION -> {
                val current = c.destination
                for (dest in c.direction.destinationChoices()) {
                    val selected = dest.id == current.id
                    val (main, sub) = if (dest.isProfile) {
                        dest.label to AiLanguages.name(dest.language)
                    } else {
                        val self = AiLanguages.selfName(dest.language)
                        self to AiLanguages.name(dest.language).takeIf { it != self }
                    }
                    list.addView(item(main, sub, selected, spoken = if (dest.isProfile) "${dest.label}, ${AiLanguages.name(dest.language)}" else AiLanguages.name(dest.language)) { c.chooseDestination(dest) })
                    out += main to selected
                }
            }
            else -> Unit
        }
        rows = out
        scroll.scrollTo(0, 0)
    }

    private fun item(main: String, sub: String?, selected: Boolean, spoken: String = main, click: () -> Unit): View {
        val fg = if (selected) theme.keyText else theme.stripText
        val secondary = theme.hintText
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52f)
            setPadding(dp(12f), dp(4f), dp(12f), dp(4f))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { cornerRadius = 14f * d; setColor(theme.pressed) })
                addState(
                    intArrayOf(),
                    GradientDrawable().apply {
                        cornerRadius = 14f * d
                        setColor(if (selected) theme.key else 0)
                        if (selected && theme.hasBorder) setStroke(dp(1f), theme.border)
                    },
                )
            }
            isClickable = true
            isFocusable = true
            contentDescription = spoken
            setOnClickListener {
                if (haptics()) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                click()
            }
        }
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(
                TextView(context).apply {
                    text = main
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(fg)
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
            if (sub != null) {
                addView(
                    TextView(context).apply {
                        text = sub
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setTextColor(secondary)
                        isSingleLine = true
                        ellipsize = TextUtils.TruncateAt.END
                    },
                )
            }
        }
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (selected) {
            row.addView(
                TranslatePanelView.IconView(context, ToolIcon.CHECK, 22f).apply {
                    color = theme.accentText
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(28f), dp(28f)),
            )
        }
        ViewCompat.setAccessibilityDelegate(
            row,
            object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                    info.isCheckable = true
                    info.isChecked = selected
                }
            },
        )
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(1f), 0, dp(1f))
        }
        return row
    }

    /** The row view labelled [label], or null (tests). */
    internal fun rowView(label: String): View? = (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { row ->
        val col = (row as? ViewGroup)?.getChildAt(0) as? ViewGroup
        (col?.getChildAt(0) as? TextView)?.text?.toString() == label
    }

    private companion object {
        const val DEFAULT_HEIGHT_DP = 260f
    }
}
