package com.qalsmartkeyboard.ime.toolbar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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

/**
 * The clipboard panel (ADR 0035): replaces the key area at the keyboard's current height. Recent clips ([entries],
 * newest first) as cards in two columns; a tap pastes the clip into the field, the × on a card removes it, "Clear all"
 * removes every one. With no clips one short line says what will appear here (or that the history is off). The bar
 * above shows a back arrow and the title; the arrow and the system Back return to the keys.
 *
 * The clips' text is shown here and handed to [onPaste] only; nothing is logged. Colours: theme tokens only (card text on
 * the key fill, labels in the strip text colour on the keyboard background).
 */
class ClipboardPanelView(context: Context) : FrameLayout(context) {
    var heightProvider: () -> Int = { 0 }

    /** The clips to show, newest first. */
    var entries: () -> List<Clip> = { emptyList() }

    /** The "Clipboard history" setting: off = only the current clip is offered, without delete buttons. */
    var historyOn: () -> Boolean = { true }

    var onPaste: (Clip) -> Unit = {}
    var onDelete: (Clip) -> Unit = {}
    var onClearAll: () -> Unit = {}
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

    private val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        setPadding(dp(8f), 0, dp(4f), 0)
    }
    private val list = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8f), 0, dp(8f), dp(4f))
    }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = true
        addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val empty = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(dp(24f), 0, dp(24f), 0)
    }
    /** The clips shown by the last [render] (tests). */
    var shown: List<Clip> = emptyList()
        private set

    init {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val middle = FrameLayout(context)
        middle.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        middle.addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        column.addView(middle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        render()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = heightProvider().takeIf { it > 0 } ?: dp(DEFAULT_HEIGHT_DP)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    /** Rebuilds from [entries]. Called on open, after a paste, delete or clear, and when the theme changes. */
    fun render() {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, if (theme.isGradient) theme.backgroundStops else intArrayOf(theme.bgTop, theme.bgTop))
        val on = historyOn()
        shown = entries()
        header.removeAllViews()
        list.removeAllViews()
        if (shown.isNotEmpty() && on) header.addView(clearAll())
        shown.chunked(2).forEach { pair ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { row.addView(card(it, deletable = on)) }
            if (pair.size == 1) row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        empty.text = context.getString(if (on) R.string.clip_empty else R.string.clip_history_off)
        empty.setTextColor(theme.stripText)
        empty.visibility = if (shown.isEmpty()) VISIBLE else GONE
        scroll.visibility = if (shown.isEmpty()) GONE else VISIBLE
    }

    /** The card of clip [id] in the current list (tests). */
    fun cardFor(id: Long): View? = list.findViewWithTag(id)

    /** The delete button of clip [id] (tests). */
    fun deleteFor(id: Long): View? = list.findViewWithTag("delete:$id")

    /** The Clear all button (tests). */
    val clearAllButton: View? get() = header.getChildAt(0)

    private fun clearAll(): View = TextView(context).apply {
        text = context.getString(R.string.clip_clear_all)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(theme.stripText)
        gravity = Gravity.CENTER
        minHeight = dp(40f)
        setPadding(dp(12f), 0, dp(12f), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            tap(this)
            onClearAll()
        }
        asButton(this)
    }

    private fun card(c: Clip, deletable: Boolean): View {
        val preview = if (c.text.length > PREVIEW_CHARS) c.text.substring(0, PREVIEW_CHARS) + "…" else c.text
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            minimumHeight = dp(56f)
            tag = c.id
            background = GradientDrawable().apply {
                cornerRadius = 12f * d
                setColor(theme.key)
                if (theme.hasBorder) setStroke(dp(theme.borderWidthDp), theme.border)
            }
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(R.string.clip_paste_item, preview)
            setOnClickListener {
                tap(this)
                onPaste(c)
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4f), dp(4f), dp(4f), dp(4f)) }
        }
        asButton(card)
        card.addView(
            TextView(context).apply {
                text = preview
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(theme.keyText)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(12f), dp(10f), if (deletable) 0 else dp(12f), dp(10f))
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (deletable) {
            card.addView(
                IconButton(context, ToolIcon.CLOSE, theme.hintText).apply {
                    tag = "delete:${c.id}"
                    contentDescription = context.getString(R.string.clip_delete_item)
                    setOnClickListener {
                        tap(this)
                        onDelete(c)
                    }
                    asButton(this)
                },
                LinearLayout.LayoutParams(dp(40f), dp(40f)),
            )
        }
        return card
    }

    private fun tap(v: View) {
        if (haptics()) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun asButton(v: View) {
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

    /** A small icon button (the card's ×), the icon centred in a 40 dp target. */
    internal class IconButton(context: Context, private val icon: ToolIcon, private val color: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            isClickable = true
            isFocusable = true
        }

        override fun onDraw(canvas: Canvas) {
            ToolIcons.draw(canvas, icon, width / 2f, height / 2f, 18f * resources.displayMetrics.density, color, paint)
        }
    }

    companion object {
        /** A card shows at most this much of a clip (3 lines are drawn anyway); the whole clip is pasted. */
        const val PREVIEW_CHARS = 300
        private const val DEFAULT_HEIGHT_DP = 260f
    }
}
