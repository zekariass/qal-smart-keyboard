package com.qalsmartkeyboard.ime.toolbar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
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
 * The tools panel (ADR 0035): replaces the key area at the keyboard's current height ([heightProvider], like the emoji
 * panel), a grid of labelled tiles, [COLUMNS] per row, scrolling when the keyboard is short (landscape). Each tile is an
 * icon on a rounded key-coloured square with a short label under it; a locked tool carries a small lock and says so to
 * TalkBack. The bar above shows a back arrow and the title; the arrow and the system Back return to the keys (no ABC
 * button: the three rows of tiles then fit the key area of a portrait phone without scrolling).
 *
 * Plain Views: every tile is one focusable Button for accessibility with the tool's name. Colours: theme tokens only.
 */
class ToolsPanelView(context: Context) : FrameLayout(context) {
    /** The height to take: the key area's, so the keyboard does not change size. */
    var heightProvider: () -> Int = { 0 }

    /** The tiles to show (refreshed on every open). */
    var tiles: () -> List<ToolEntry> = { emptyList() }

    /** A tile was tapped. */
    var onTool: (Tool) -> Unit = {}

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

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).toInt()

    private val grid = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8f), dp(6f), dp(8f), dp(4f))
    }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        addView(grid, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    /** The tools shown by the last [render], in order (tests and accessibility checks). */
    var shown: List<ToolEntry> = emptyList()
        private set

    init {
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        render()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = heightProvider().takeIf { it > 0 } ?: dp(DEFAULT_HEIGHT_DP)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    /** Rebuilds the grid from [tiles] and the theme. Called when the panel opens and when the theme changes. */
    fun render() {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, if (theme.isGradient) theme.backgroundStops else intArrayOf(theme.bgTop, theme.bgTop))
        grid.removeAllViews()
        shown = tiles()
        shown.chunked(COLUMNS).forEach { rowTiles ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            rowTiles.forEach { row.addView(tile(it), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
            repeat(COLUMNS - rowTiles.size) { row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /** The tile view of [tool] in the current grid, or null. */
    fun tileFor(tool: Tool): View? = grid.findViewWithTag(tool)

    private fun tile(e: ToolEntry): View {
        val label = toolLabel(context, e.tool)
        val t = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2f), dp(6f), dp(2f), dp(4f))
            minimumHeight = dp(76f)
            tag = e.tool
            isClickable = true
            isFocusable = true
            contentDescription = if (e.locked) context.getString(R.string.tool_locked, label) else label
            setOnClickListener {
                if (haptics()) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onTool(e.tool)
            }
        }
        t.addView(IconTile(context, e.tool.icon, e.locked, theme), LinearLayout.LayoutParams(dp(TILE_DP), dp(TILE_DP)))
        t.addView(
            TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(theme.stripText) // on the keyboard background: the pair checked on every gradient stop
                gravity = Gravity.CENTER_HORIZONTAL
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(6f), 0, 0)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        asButton(t)
        return t
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

    /**
     * The tile's icon square: [icon] on the key fill (outlined in bordered themes), pressed = the pressed fill, a small
     * lock at the corner when [locked].
     */
    internal class IconTile(context: Context, private val icon: ToolIcon, private val locked: Boolean, private val theme: KeyboardTheme) : View(context) {
        private val d = resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private var down = false

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            isDuplicateParentStateEnabled = true
        }

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            val pressed = isPressed
            if (pressed != down) {
                down = pressed
                invalidate()
            }
        }

        override fun onTouchEvent(event: MotionEvent?): Boolean = false

        override fun onDraw(canvas: Canvas) {
            r.set(0f, 0f, width.toFloat(), height.toFloat())
            val radius = width * 0.3f
            fill.color = if (down) theme.pressed else theme.key
            canvas.drawRoundRect(r, radius, radius, fill)
            if (theme.hasBorder) {
                stroke.color = theme.border
                stroke.strokeWidth = theme.borderWidthDp * d
                val hw = stroke.strokeWidth / 2
                canvas.drawRoundRect(hw, hw, width - hw, height - hw, radius, radius, stroke)
            }
            ToolIcons.draw(canvas, icon, width / 2f, height / 2f, ICON_DP * d, theme.keyText, iconPaint)
            if (locked) {
                val b = 12f * d
                val bx = width - b * 0.9f
                val by = height - b * 0.9f
                fill.color = theme.special
                canvas.drawCircle(bx, by, b * 0.75f, fill)
                ToolIcons.draw(canvas, ToolIcon.LOCK, bx, by, b, theme.specialText, iconPaint, strokeScale = 1.4f)
            }
        }
    }

    companion object {
        const val COLUMNS = 4
        const val TILE_DP = 48f
        const val ICON_DP = 24f
        private const val DEFAULT_HEIGHT_DP = 260f
    }
}
