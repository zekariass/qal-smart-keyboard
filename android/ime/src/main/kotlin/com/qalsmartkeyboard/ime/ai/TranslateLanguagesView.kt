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
 * (the catalogue's autonym, laid out right to left for RTL languages) and its name in the UI language; the current
 * choice comes first with a check, then the keyboard language and the recent ones. A tap selects and returns to the
 * panel.
 *
 * ADR 0040: the list is long (the server's translation catalogue), so a search field sits above it. A tap on it lets the
 * keys type into it: the list shrinks to [SEARCH_HEIGHT_DP] and the keys come back under it (the service shows them
 * while [TranslateController.searching]); Enter or the emoji key hides the keys again and keeps the filtered list.
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
        setPadding(dp(8f), dp(2f), dp(8f), dp(8f))
    }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = true
        addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private val searchIcon = TranslatePanelView.IconView(context, ToolIcon.SEARCH, 20f).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val searchText = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.START
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val searchClear = TranslatePanelView.IconView(context, ToolIcon.CLOSE, 18f).apply {
        contentDescription = context.getString(R.string.translate_search_clear)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            if (haptics()) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            controller?.clearSearch()
        }
    }

    /** The search field (tap: type into it with the keys). */
    private val searchRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(44f)
        setPadding(dp(12f), 0, dp(6f), 0)
        isClickable = true
        isFocusable = true
        addView(searchIcon, LinearLayout.LayoutParams(dp(24f), dp(24f)))
        addView(searchText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8f) })
        addView(searchClear, LinearLayout.LayoutParams(dp(36f), dp(36f)))
        setOnClickListener {
            if (haptics()) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            controller?.startSearch()
        }
    }

    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            searchRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(8f), dp(6f), dp(8f), dp(2f))
            },
        )
        addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** The rows of the last [render], in order: (label, selected) — for tests. */
    var rows: List<Pair<String, Boolean>> = emptyList()
        private set

    /** The codes of the language rows of the last [render] whose autonym is laid out right to left — for tests. */
    var rtlRows: Set<String> = emptySet()
        private set

    /** The search row and its × (tests). */
    internal val searchView: View get() = searchRow
    internal val searchClearView: View get() = searchClear
    internal val searchLabel: String get() = searchText.text.toString()

    init {
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        visibility = GONE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val full = heightProvider().takeIf { it > 0 } ?: dp(DEFAULT_HEIGHT_DP)
        // While the keys type into the search they are shown under the list: the list takes a short band only.
        val h = if (controller?.searching == true) minOf(full, dp(SEARCH_HEIGHT_DP)) else full
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    /** Rebuilds the list for the open picker (called when it opens, on each search key and when the theme changes). */
    fun render() {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, if (theme.isGradient) theme.backgroundStops else intArrayOf(theme.bgTop, theme.bgTop))
        renderSearch()
        list.removeAllViews()
        val c = controller
        val out = ArrayList<Pair<String, Boolean>>()
        val rtl = HashSet<String>()
        when (c?.picker) {
            TranslatePicker.SOURCE -> {
                val dir = c.direction
                for (code in c.sourceChoices()) {
                    val selected = code == dir.manualSource
                    if (code == null) {
                        val label = context.getString(R.string.translate_detect_language)
                        list.addView(item(label, null, selected) { c.chooseSource(null) })
                        out += label to selected
                    } else {
                        val self = AiLanguages.selfName(code)
                        val ui = AiLanguages.name(code)
                        val isRtl = AiLanguages.isRtl(code)
                        if (isRtl) rtl += code
                        list.addView(item(self, ui.takeIf { it != self }, selected, spoken = ui, rtl = isRtl) { c.chooseSource(code) })
                        out += self to selected
                    }
                }
            }
            TranslatePicker.DESTINATION -> {
                val current = c.destination
                for (dest in c.destinationChoices()) {
                    val selected = dest.id == current.id
                    val isRtl = !dest.isProfile && AiLanguages.isRtl(dest.language)
                    val (main, sub) = if (dest.isProfile) {
                        dest.label to AiLanguages.name(dest.language)
                    } else {
                        val self = AiLanguages.selfName(dest.language)
                        self to AiLanguages.name(dest.language).takeIf { it != self }
                    }
                    if (isRtl) rtl += dest.language
                    val spoken = if (dest.isProfile) "${dest.label}, ${AiLanguages.name(dest.language)}" else AiLanguages.name(dest.language)
                    list.addView(item(main, sub, selected, spoken = spoken, rtl = isRtl) { c.chooseDestination(dest) })
                    out += main to selected
                }
            }
            else -> Unit
        }
        if (out.isEmpty() && c != null && c.picker != TranslatePicker.NONE) {
            list.addView(
                TextView(context).apply {
                    text = context.getString(R.string.translate_no_languages)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(theme.hintText)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(16f), 0, dp(16f))
                },
            )
        }
        rows = out
        rtlRows = rtl
        scroll.scrollTo(0, 0)
        requestLayout()
    }

    private fun renderSearch() {
        val c = controller
        val query = c?.search?.text.orEmpty()
        val typing = c?.searching == true
        val hint = context.getString(R.string.translate_search_languages)
        searchText.text = when {
            query.isNotEmpty() -> if (typing) "$query|" else query
            typing -> "|"
            else -> hint
        }
        searchText.setTextColor(if (query.isEmpty()) theme.hintText else theme.keyText)
        searchIcon.color = theme.hintText
        searchClear.color = theme.hintText
        searchClear.visibility = if (query.isEmpty()) GONE else VISIBLE
        searchRow.contentDescription = if (query.isEmpty()) hint else "$hint. " + context.getString(R.string.emoji_search_field, query)
        searchRow.background = GradientDrawable().apply {
            cornerRadius = 22f * d
            setColor(theme.key)
            if (typing) setStroke(dp(2f), theme.accentText) else if (theme.hasBorder) setStroke(dp(1f), theme.border)
        }
    }

    private fun item(main: String, sub: String?, selected: Boolean, spoken: String = main, rtl: Boolean = false, click: () -> Unit): View {
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
                    // An RTL autonym is shaped and ordered right to left, still aligned with the other rows (ADR 0040).
                    if (rtl) {
                        textDirection = TEXT_DIRECTION_RTL
                        textAlignment = TEXT_ALIGNMENT_VIEW_START
                    }
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

    /** The autonym view of the row labelled [label] (tests: its text direction). */
    internal fun nameView(label: String): TextView? = ((rowView(label) as? ViewGroup)?.getChildAt(0) as? ViewGroup)?.getChildAt(0) as? TextView

    internal companion object {
        const val DEFAULT_HEIGHT_DP = 260f

        /** The list's height while the keys type into its search: the field and about two rows. */
        const val SEARCH_HEIGHT_DP = 150f
    }
}
