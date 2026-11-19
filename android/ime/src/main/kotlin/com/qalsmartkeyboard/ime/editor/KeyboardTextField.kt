package com.qalsmartkeyboard.ime.editor

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.theme.KeyboardTheme
import com.qalsmartkeyboard.ime.theme.ThemeCatalog
import com.qalsmartkeyboard.ime.toolbar.ToolIcon
import com.qalsmartkeyboard.ime.toolbar.ToolIconDrawable

/**
 * The keyboard's own text field (ADR 0038): an [EditorView] under a small slot that shows either the field's [label]
 * ("Selected text", "Your text · 12 / 5,000") or, while the user selects, the action row (Cut, Copy, Paste, Select all;
 * Gboard's selection toolbar, placed in the field so it never covers the keys or leaves the keyboard window). Used by the
 * translate box, the AI panels' typing strip and Word Art's strip.
 *
 * [fixedSlot]: the slot keeps its height when it has nothing to show, so a strip with a fixed height never jumps.
 *
 * The action row always fits the field's width (a phone at 360 dp beside the translate box's clear × and Translate
 * button, Oromo's long labels): the labelled pills first, then the same with tighter padding, then icon pills (each still
 * named for TalkBack, with a tooltip). Only a field narrower than four icons would scroll.
 */
class KeyboardTextField(context: Context) : LinearLayout(context) {
    val editor = EditorView(context)

    var theme: KeyboardTheme = ThemeCatalog.classic(
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES,
    )
        set(value) {
            field = value
            editor.applyTheme(value)
            labelView.setTextColor(value.hintText)
            renderActions()
        }

    /** The clipboard of the action row (the host field's rules: sensitive clips are never offered or read). */
    var clipboard: EditorClipboard
        get() = editor.clipboard
        set(value) {
            editor.clipboard = value
        }

    /** The field's name line, or empty for none. */
    var label: CharSequence = ""
        set(value) {
            if (field.toString() == value.toString()) return
            field = value
            labelView.text = value
            updateSlot()
        }

    var fixedSlot: Boolean = false
        set(value) {
            field = value
            updateSlot()
        }

    /** Whether the action row's taps give haptic feedback. */
    var haptics: () -> Boolean = { true }

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).toInt()

    private val labelView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        includeFontPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO // the editor's description names the field
    }
    private val actionRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val actionScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        isHorizontalFadingEdgeEnabled = true // only a field too narrow even for icons scrolls: then the cut pill fades
        setFadingEdgeLength(dp(16f))
        addView(actionRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        visibility = GONE
    }

    /** How the action row is drawn so that it fits the field's width. */
    internal enum class RowFit { LABELS, COMPACT, ICONS }

    /** The style of the last fit (tests). */
    internal var rowFit: RowFit = RowFit.LABELS
        private set
    private val slot = FrameLayout(context).apply {
        addView(labelView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        addView(actionScroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    init {
        orientation = VERTICAL
        addView(slot, LayoutParams(LayoutParams.MATCH_PARENT, dp(SLOT_DP)))
        addView(editor, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        editor.onActionsChanged = { renderActions() }
        editor.applyTheme(theme)
        labelView.setTextColor(theme.hintText)
        updateSlot()
    }

    /** The editor takes the height the slot leaves (a strip with a fixed height); false: it takes its own lines. */
    var fillHeight: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            editor.layoutParams = if (value) LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f) else LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

    private fun updateSlot() {
        val acting = editor.actionsShown && actionRow.childCount > 0
        actionScroll.visibility = if (acting) VISIBLE else GONE
        labelView.visibility = if (!acting && label.isNotEmpty()) VISIBLE else INVISIBLE
        slot.visibility = if (acting || label.isNotEmpty() || fixedSlot) VISIBLE else GONE
        // The label alone is one small line; the row (and a fixed slot) take the row's height.
        val h = if (acting || fixedSlot) dp(SLOT_DP) else LayoutParams.WRAP_CONTENT
        if (slot.layoutParams.height != h) slot.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, h)
    }

    /** Rebuilds the action row for the buffer's state (called when the row shows; cheap). */
    fun renderActions() {
        actionRow.removeAllViews()
        val b = editor.buffer
        if (editor.actionsShown && b != null) {
            for (a in EditorActions.available(b, clipboard)) actionRow.addView(pill(a))
        }
        lastFitWidth = -1
        updateSlot()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            fitActions(MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private var lastFitWidth = -1

    /** Picks the widest style of the action row that fits [available] px and applies it to the pills. */
    private fun fitActions(available: Int) {
        if (available == lastFitWidth || actionRow.childCount == 0) return
        lastFitWidth = available
        val pills = actionViews
        fun need(padDp: Float, gapDp: Float) = pills.sumOf { p ->
            (p.paint.measureText(label(p.tag as EditorAction)) + 2 * dp(padDp) + dp(gapDp)).toDouble()
        }
        val fit = when {
            need(LABEL_PAD_DP, LABEL_GAP_DP) <= available -> RowFit.LABELS
            need(COMPACT_PAD_DP, COMPACT_GAP_DP) <= available -> RowFit.COMPACT
            else -> RowFit.ICONS
        }
        rowFit = fit
        pills.forEach { style(it, fit) }
    }

    private fun label(a: EditorAction): String = context.getString(
        when (a) {
            EditorAction.CUT -> R.string.editor_cut
            EditorAction.COPY -> R.string.ai_copy
            EditorAction.PASTE -> R.string.editor_paste
            EditorAction.SELECT_ALL -> R.string.editor_select_all
        },
    )

    private fun icon(a: EditorAction): ToolIcon = when (a) {
        EditorAction.CUT -> ToolIcon.CUT
        EditorAction.COPY -> ToolIcon.COPY
        EditorAction.PASTE -> ToolIcon.CLIPBOARD
        EditorAction.SELECT_ALL -> ToolIcon.SELECT_ALL
    }

    /** Draws [p] as a label (with [fit]'s padding) or as an icon of the keyboard's family. Changes only what differs. */
    private fun style(p: TextView, fit: RowFit) {
        val a = p.tag as EditorAction
        val icons = fit == RowFit.ICONS
        val pad = dp(
            when (fit) {
                RowFit.LABELS -> LABEL_PAD_DP
                RowFit.COMPACT -> COMPACT_PAD_DP
                RowFit.ICONS -> ICON_PAD_DP
            },
        )
        val text = if (icons) "" else label(a)
        if (p.text.toString() != text) p.text = text
        if (icons && p.compoundDrawables[0] == null) {
            val px = dp(ICON_DP)
            p.setCompoundDrawables(ToolIconDrawable(icon(a), theme.keyText, px).also { it.setBounds(0, 0, px, px) }, null, null, null)
        } else if (!icons && p.compoundDrawables[0] != null) {
            p.setCompoundDrawables(null, null, null, null)
        }
        if (android.os.Build.VERSION.SDK_INT >= 26) p.tooltipText = if (icons) label(a) else null
        if (p.paddingLeft != pad || p.paddingRight != pad) p.setPadding(pad, 0, pad, 0)
        val gap = dp(if (fit == RowFit.LABELS) LABEL_GAP_DP else COMPACT_GAP_DP)
        val lp = p.layoutParams as LayoutParams
        if (lp.marginEnd != gap) {
            lp.marginEnd = gap
            p.layoutParams = lp
        }
    }

    private fun pill(a: EditorAction): TextView = TextView(context).apply {
        tag = a
        text = label(a)
        contentDescription = text // the name in every style, also when only the icon shows
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(theme.keyText)
        gravity = Gravity.CENTER
        maxLines = 1 // not singleLine: a horizontally scrolling single line centres its text off the pill
        setPadding(dp(LABEL_PAD_DP), 0, dp(LABEL_PAD_DP), 0)
        background = GradientDrawable().apply {
            cornerRadius = 16f * d
            setColor(theme.pressed)
        }
        isClickable = true
        isFocusable = true
        setOnClickListener {
            if (haptics()) performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            editor.perform(a)
        }
        ViewCompat.setAccessibilityDelegate(
            this,
            object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: android.view.View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                }
            },
        )
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(SLOT_DP - 4f)).apply { marginEnd = dp(LABEL_GAP_DP) }
    }

    /** The row's buttons, for tests. */
    internal val actionViews: List<TextView> get() = (0 until actionRow.childCount).map { actionRow.getChildAt(it) as TextView }
    internal val labelTextView: TextView get() = labelView
    internal val actionScrollView: HorizontalScrollView get() = actionScroll

    companion object {
        /** The label / action row slot (dp). */
        const val SLOT_DP = 34f
        private const val LABEL_PAD_DP = 12f
        private const val LABEL_GAP_DP = 6f
        private const val COMPACT_PAD_DP = 8f
        private const val COMPACT_GAP_DP = 4f
        private const val ICON_PAD_DP = 11f
        private const val ICON_DP = 18f
    }
}
