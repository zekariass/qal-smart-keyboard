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

/**
 * The keyboard's own text field (ADR 0038): an [EditorView] under a small slot that shows either the field's [label]
 * ("Selected text", "Your text · 12 / 5,000") or, while the user selects, the action row (Cut, Copy, Paste, Select all;
 * Gboard's selection toolbar, placed in the field so it never covers the keys or leaves the keyboard window). Used by the
 * translate box, the AI panels' typing strip and Word Art's strip.
 *
 * [fixedSlot]: the slot keeps its height when it has nothing to show, so a strip with a fixed height never jumps.
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
        addView(actionRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        visibility = GONE
    }
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
        updateSlot()
    }

    private fun pill(a: EditorAction): TextView = TextView(context).apply {
        text = context.getString(
            when (a) {
                EditorAction.CUT -> R.string.editor_cut
                EditorAction.COPY -> R.string.ai_copy
                EditorAction.PASTE -> R.string.editor_paste
                EditorAction.SELECT_ALL -> R.string.editor_select_all
            },
        )
        contentDescription = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(theme.keyText)
        gravity = Gravity.CENTER
        maxLines = 1 // not singleLine: a horizontally scrolling single line centres its text off the pill
        setPadding(dp(12f), 0, dp(12f), 0)
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
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(SLOT_DP - 4f)).apply { marginEnd = dp(6f) }
    }

    /** The row's buttons, for tests. */
    internal val actionViews: List<TextView> get() = (0 until actionRow.childCount).map { actionRow.getChildAt(it) as TextView }
    internal val labelTextView: TextView get() = labelView

    companion object {
        /** The label / action row slot (dp). */
        const val SLOT_DP = 34f
    }
}
