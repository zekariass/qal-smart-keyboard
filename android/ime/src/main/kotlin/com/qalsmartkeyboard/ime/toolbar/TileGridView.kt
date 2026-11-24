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
 * One tile of a [TileGridView]: [key] identifies it (a [Tool], an AI tool), [label] is the short name under the icon,
 * [description] its TalkBack name; [locked] adds a small lock (and ", locked" for TalkBack).
 */
data class GridTile<K : Any>(val key: K, val icon: ToolIcon, val label: String, val locked: Boolean = false, val description: String = label)

/**
 * A grid panel in place of the key area (ADR 0035, ADR 0041): the tools panel and the AI tools grid. It takes the
 * keyboard's current height ([heightProvider], like the emoji panel) and shows labelled tiles, [COLUMNS] per row,
 * scrolling (with a fading edge) when they do not fit; an optional compact line above the tiles ([notice]) says why
 * something cannot run. Each tile is an icon of the keyboard's family ([ToolIcons]) on a rounded key-coloured square with a
 * short label under it (two lines at most). The bar above shows a back arrow and the title; the arrow and the system Back
 * return to the keys.
 *
 * Plain Views: every tile is one focusable Button for accessibility with its name. Colours: theme tokens only.
 */
open class TileGridView<K : Any>(context: Context) : FrameLayout(context) {
    /** The height to take: the key area's, so the keyboard does not change size. */
    var heightProvider: () -> Int = { 0 }

    /** The tiles to show (read on every [render]). */
    var gridTiles: () -> List<GridTile<K>> = { emptyList() }

    /** A tile was tapped. */
    var onGridTile: (K) -> Unit = {}

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

    protected val d = resources.displayMetrics.density
    protected fun dp(v: Float) = (v * d).toInt()

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8f), dp(6f), dp(8f), dp(16f))
    }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        // Tiles cut by the bottom edge fade out, so they read as "scroll for more".
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(dp(24f))
        addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** The tiles shown by the last [render], in order (tests and accessibility checks). */
    var shownTiles: List<GridTile<K>> = emptyList()
        private set

    init {
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = heightProvider().takeIf { it > 0 } ?: dp(DEFAULT_HEIGHT_DP)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    /** A compact line above the tiles (a status and its action), or null. Rebuilt on every [render]. */
    protected open fun notice(): View? = null

    /** Rebuilds the grid from [gridTiles] and the theme. Called when the panel opens, the theme or the plan state changes. */
    open fun render() {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, if (theme.isGradient) theme.backgroundStops else intArrayOf(theme.bgTop, theme.bgTop))
        content.removeAllViews()
        notice()?.let { content.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        shownTiles = gridTiles()
        shownTiles.chunked(COLUMNS).forEach { rowTiles ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            rowTiles.forEach { row.addView(tile(it), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
            repeat(COLUMNS - rowTiles.size) { row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
            content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        scroll.scrollTo(0, 0)
    }

    /** The tile view of [key] in the current grid, or null. */
    fun tileView(key: K): View? = content.findViewWithTag(key)

    private fun tile(e: GridTile<K>): View {
        val t = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2f), dp(6f), dp(2f), dp(4f))
            minimumHeight = dp(76f)
            tag = e.key
            isClickable = true
            isFocusable = true
            contentDescription = if (e.locked) context.getString(R.string.tool_locked, e.description) else e.description
            setOnClickListener {
                if (haptics()) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onGridTile(e.key)
            }
        }
        t.addView(IconTile(context, e.icon, e.locked, theme), LinearLayout.LayoutParams(dp(TILE_DP), dp(TILE_DP)))
        t.addView(
            TextView(context).apply {
                text = e.label
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
