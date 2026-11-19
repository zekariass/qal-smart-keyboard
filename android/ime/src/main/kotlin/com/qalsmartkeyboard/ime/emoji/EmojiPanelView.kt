package com.qalsmartkeyboard.ime.emoji

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.OverScroller
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.qalsmartkeyboard.ime.Key
import com.qalsmartkeyboard.ime.KeyKind
import com.qalsmartkeyboard.ime.KeyboardController
import com.qalsmartkeyboard.ime.PopupItem
import com.qalsmartkeyboard.ime.PopupOverlay
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.enterLabelText
import com.qalsmartkeyboard.ime.theme.KeyboardTheme
import com.qalsmartkeyboard.ime.theme.ThemeCatalog
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** The spoken and shown title of a category tab and section header. */
internal fun EmojiCategory.titleRes(): Int = when (this) {
    EmojiCategory.RECENT -> R.string.emoji_category_recent
    EmojiCategory.SMILEYS -> R.string.emoji_category_smileys
    EmojiCategory.PEOPLE -> R.string.emoji_category_people
    EmojiCategory.ANIMALS -> R.string.emoji_category_animals
    EmojiCategory.FOOD -> R.string.emoji_category_food
    EmojiCategory.TRAVEL -> R.string.emoji_category_travel
    EmojiCategory.ACTIVITIES -> R.string.emoji_category_activities
    EmojiCategory.OBJECTS -> R.string.emoji_category_objects
    EmojiCategory.SYMBOLS -> R.string.emoji_category_symbols
    EmojiCategory.FLAGS -> R.string.emoji_category_flags
}

internal val TONE_STRINGS = intArrayOf(R.string.emoji_tone_1, R.string.emoji_tone_2, R.string.emoji_tone_3, R.string.emoji_tone_4, R.string.emoji_tone_5)

/**
 * The emoji panel (ADR 0028): replaces the key area at the same height. Top: the search entry and the category tabs;
 * middle: one scrolling grid of all categories (a tab jumps to its section, the highlighted tab follows the scroll);
 * bottom: ABC (back to the keys), space, backspace, enter. Custom-drawn like [com.qalsmartkeyboard.ime.KeyboardSurface]
 * (one Canvas, no per-cell views, so scrolling stays smooth). A long press on an emoji with skin tones opens the
 * variant popup in the shared [PopupOverlay]. Colours come from the keyboard theme tokens only.
 *
 * Accessibility: every tab, key and visible emoji is a virtual view (an emoji reads its CLDR name); emoji with skin
 * tones offer each tone as an action; the grid scrolls with the scroll actions.
 */
class EmojiPanelView(context: Context) : View(context) {
    var controller: KeyboardController? = null
    var overlay: PopupOverlay? = null

    /** The height to take: the key area's, so the keyboard does not change size. */
    var heightProvider: () -> Int = { 0 }

    var theme: KeyboardTheme = ThemeCatalog.classic(
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES,
    )
        set(value) {
            if (field === value) return
            field = value
            bgShader = null
            borderPaint.strokeWidth = value.borderWidthDp * d
            invalidate()
        }

    private val d = resources.displayMetrics.density
    private val emoji: EmojiController? get() = controller?.emoji

    private val bgPaint = Paint()
    private var bgShader: LinearGradient? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val tabsH get() = (if (height < 210 * d) 34f else 40f) * d
    private val bottomH get() = (if (height < 210 * d) 38f else 46f) * d
    private val gridTop get() = tabsH
    private val gridBottom get() = height - bottomH
    private val gridH get() = (gridBottom - gridTop).coerceAtLeast(1f)
    private val headerH = 28f * d
    private val gap = 4f * d
    private val rowGap = 7f * d

    private var catalog: EmojiCatalog? = null
    private var base: List<EmojiSection> = emptyList()
    private var model: EmojiGridModel? = null
    private var scrollY = 0f
    private var scrollerStarted = false
    private val scroller = OverScroller(context)

    // ------------------------------------------------------------------ data

    /** The emoji state changed (data loaded, mode, language). Cheap: the grid is rebuilt only for new data. */
    fun refresh() {
        val c = emoji?.catalog
        if (c !== catalog) {
            catalog = c
            base = if (c == null) emptyList() else EmojiCategory.entries.filter { it != EmojiCategory.RECENT }
                .map { cat -> EmojiSection(cat, c.inCategory(cat).map { EmojiCell(it.text, it) }) }
                .filter { it.cells.isNotEmpty() }
            rebuild(keepScroll = false)
        }
        invalidate()
    }

    /** The panel was opened (or came back from search): the grid is rebuilt with the current recents and starts at the top. */
    fun onOpened() {
        val e = emoji ?: return
        refresh()
        rebuild(keepScroll = false)
        // Start on the recents when there are some, else on the first category.
        if (e.recentTexts.isEmpty()) model?.let { if (it.sections.size > 1) scrollY = it.sectionTop(1).coerceAtMost(it.maxScroll(gridH)) }
        invalidate()
        nodes.invalidateRoot()
    }

    private fun cols(): Int = max(6, (width / (42f * d)).toInt())

    private fun rebuild(keepScroll: Boolean) {
        val e = emoji
        val c = catalog
        if (e == null || c == null || width == 0) {
            model = null
            return
        }
        val cols = cols()
        val cw = width.toFloat() / cols
        val sections = listOf(EmojiSection(EmojiCategory.RECENT, e.recentCells())) + base
        model = EmojiGridModel(sections, cols, headerH, cw.coerceIn(38f * d, 48f * d))
        scrollY = if (keepScroll) scrollY.coerceIn(0f, maxScroll()) else 0f
        scroller.forceFinished(true)
        nodes.invalidateRoot()
    }

    private fun maxScroll(): Float = model?.maxScroll(gridH) ?: 0f

    private fun cellText(sectionIndex: Int, index: Int): String? {
        val m = model ?: return null
        val sec = m.sections.getOrNull(sectionIndex) ?: return null
        val cell = sec.cells.getOrNull(index) ?: return null
        return if (sec.category == EmojiCategory.RECENT) cell.text else emoji?.formOf(cell.entry) ?: cell.text
    }

    // ------------------------------------------------------------------ measuring

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = heightProvider().takeIf { it > 0 } ?: (250f * d).toInt()
        setMeasuredDimension(resolveSize(suggestedMinimumWidth, widthMeasureSpec), h)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        bgShader = null
        if (catalog != null) rebuild(keepScroll = true)
    }

    // ------------------------------------------------------------------ drawing

    private val bottomWeights = floatArrayOf(1.5f, 4f, 1.5f, 1.5f)
    private val bottomRects = Array(4) { RectF() }

    private fun layoutBottom() {
        val total = bottomWeights.sum()
        val unit = (width - 6f * d) / total
        var x = 3f * d
        val top = gridBottom
        for (i in 0 until 4) {
            val w = unit * bottomWeights[i]
            bottomRects[i].set(x + gap / 2, top + rowGap / 2, x + w - gap / 2, top + bottomH - rowGap / 2)
            x += w
        }
    }

    /** Tab slots: the search entry, the categories, then the Word Art tab (ADR 0030). */
    private val slots get() = EmojiCategory.entries.size + 2
    private val wordArtSlot get() = slots - 1

    private fun slotWidth() = width / slots.toFloat()

    private fun drawBackground(canvas: Canvas) {
        if (!theme.isGradient) {
            canvas.drawColor(theme.bgTop)
            return
        }
        var s = bgShader
        if (s == null) {
            s = LinearGradient(0f, 0f, 0f, height.toFloat(), theme.bgTop, theme.bgBottom, Shader.TileMode.CLAMP)
            bgShader = s
            bgPaint.shader = s
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
    }

    private fun currentCategory(): EmojiCategory {
        val m = model ?: return EmojiCategory.SMILEYS
        return m.sections[m.sectionAt(scrollY + 1f)].category
    }

    override fun onDraw(canvas: Canvas) {
        drawBackground(canvas)
        drawTabs(canvas)
        drawGrid(canvas)
        drawBottom(canvas)
    }

    private fun drawTabs(canvas: Canvas) {
        fill.color = theme.stripBg
        canvas.drawRect(0f, 0f, width.toFloat(), tabsH, fill)
        val sw = slotWidth()
        val cur = currentCategory()
        val cy = tabsH / 2f
        for (slot in 0 until slots) {
            val cx = sw * slot + sw / 2f
            val selected = slot in 1..EmojiCategory.entries.size && EmojiCategory.entries[slot - 1] == cur
            if (slot == pressedSlot && region == Region.TABS) {
                fill.color = theme.pressed
                canvas.drawRoundRect(sw * slot + 2 * d, 3 * d, sw * (slot + 1) - 2 * d, tabsH - 3 * d, 8 * d, 8 * d, fill)
            }
            when {
                slot == 0 -> drawMagnifier(canvas, cx, cy, 8f * d, theme.stripText)
                slot == wordArtSlot -> {
                    text.color = theme.stripText
                    text.textSize = 13f * d
                    text.isFakeBoldText = true
                    canvas.drawText(WORD_ART_LABEL, cx, cy - (text.descent() + text.ascent()) / 2, text)
                    text.isFakeBoldText = false
                }
                EmojiCategory.entries[slot - 1] == EmojiCategory.RECENT -> drawClock(canvas, cx, cy, 9f * d, if (selected) theme.accentText else theme.stripText)
                else -> {
                    glyph.textSize = 19f * d
                    glyph.alpha = if (selected) 255 else 150
                    canvas.drawText(EmojiCategory.entries[slot - 1].glyph, cx, cy - (glyph.descent() + glyph.ascent()) / 2, glyph)
                    glyph.alpha = 255
                }
            }
            if (selected) {
                fill.color = theme.accent
                canvas.drawRoundRect(cx - 12 * d, tabsH - 4 * d, cx + 12 * d, tabsH - 1.5f * d, 2 * d, 2 * d, fill)
            }
        }
        fill.color = theme.stripDivider
        canvas.drawRect(0f, tabsH - d, width.toFloat(), tabsH, fill)
    }

    private fun drawMagnifier(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        icon.color = color
        icon.strokeWidth = 1.8f * d
        canvas.drawCircle(cx - r * 0.2f, cy - r * 0.2f, r * 0.7f, icon)
        canvas.drawLine(cx + r * 0.3f, cy + r * 0.3f, cx + r * 0.95f, cy + r * 0.95f, icon)
    }

    private fun drawClock(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        icon.color = color
        icon.strokeWidth = 1.8f * d
        canvas.drawCircle(cx, cy, r, icon)
        canvas.drawLine(cx, cy, cx, cy - r * 0.6f, icon)
        canvas.drawLine(cx, cy, cx + r * 0.45f, cy + r * 0.2f, icon)
    }

    private fun drawGrid(canvas: Canvas) {
        val m = model
        canvas.save()
        canvas.clipRect(0f, gridTop, width.toFloat(), gridBottom)
        if (m == null) {
            text.color = theme.hintText
            text.textSize = 14f * d
            canvas.drawText(context.getString(R.string.emoji_loading), width / 2f, gridTop + gridH / 2f, text)
            canvas.restore()
            return
        }
        val cw = width.toFloat() / m.cols
        glyph.textSize = m.cellH * 0.58f
        var i = m.firstRowAt(scrollY)
        while (i < m.rows.size) {
            val r = m.rows[i]
            val y = gridTop + r.top - scrollY
            if (y >= gridBottom) break
            val sec = m.sections[r.section]
            when {
                r.cellStart == -1 -> {
                    text.color = theme.hintText
                    text.textSize = 12.5f * d
                    text.textAlign = Paint.Align.LEFT
                    text.isFakeBoldText = true
                    canvas.drawText(context.getString(sec.category.titleRes()), 10f * d, y + r.height / 2f - (text.descent() + text.ascent()) / 2, text)
                    text.isFakeBoldText = false
                    text.textAlign = Paint.Align.CENTER
                }
                r.cellStart == -2 -> {
                    text.color = theme.hintText
                    text.textSize = 13f * d
                    canvas.drawText(context.getString(R.string.emoji_recent_empty), width / 2f, y + r.height / 2f - (text.descent() + text.ascent()) / 2, text)
                }
                else -> for (col in 0 until m.cols) {
                    val idx = r.cellStart + col
                    if (idx >= sec.cells.size) break
                    val cx = cw * col + cw / 2f
                    if (r.section == pressedSection && idx == pressedIndex && region == Region.GRID) {
                        fill.color = theme.pressed
                        canvas.drawRoundRect(cw * col + 2 * d, y + 2 * d, cw * (col + 1) - 2 * d, y + r.height - 2 * d, 10 * d, 10 * d, fill)
                    }
                    val s = cellText(r.section, idx) ?: continue
                    canvas.drawText(s, cx, y + r.height / 2f - (glyph.descent() + glyph.ascent()) / 2, glyph)
                }
            }
            i++
        }
        canvas.restore()
    }

    private fun drawBottom(canvas: Canvas) {
        layoutBottom()
        val c = controller ?: return
        val radius = theme.cornerRadiusDp * d
        for (i in 0 until 4) {
            val r = bottomRects[i]
            val down = region == Region.BOTTOM && pressedSlot == i
            fill.color = when {
                down -> theme.pressed
                i == 3 -> theme.accent
                i == 1 -> theme.key
                else -> theme.special
            }
            if (theme.hasShadow) {
                val keyFill = fill.color
                fill.color = theme.shadow
                val dy = theme.shadowDyDp * d
                canvas.drawRoundRect(r.left, r.top + dy, r.right, r.bottom + dy, radius, radius, fill)
                fill.color = keyFill
            }
            canvas.drawRoundRect(r, radius, radius, fill)
            if (theme.hasBorder) {
                val hw = borderPaint.strokeWidth / 2f
                borderPaint.color = theme.border
                canvas.drawRoundRect(r.left + hw, r.top + hw, r.right - hw, r.bottom - hw, radius, radius, borderPaint)
            }
            val label = when (i) {
                0 -> "ABC"
                1 -> c.indicator
                2 -> "⌫"
                else -> enterLabelText(c.profile.enterLabel)
            }
            text.color = when (i) { 1 -> theme.hintText; 3 -> theme.onAccent; else -> theme.specialText }
            text.textSize = (if (i == 1) 13f else if (label.length > 2) 15f else 21f) * d
            val w = text.measureText(label)
            if (w > r.width() - 4 * d) text.textSize = text.textSize * (r.width() - 4 * d) / w
            canvas.drawText(label, r.centerX(), r.centerY() - (text.descent() + text.ascent()) / 2, text)
        }
    }

    // ------------------------------------------------------------------ touch

    private enum class Region { NONE, TABS, GRID, BOTTOM }

    private var region = Region.NONE
    private var pressedSlot = -1
    private var pressedSection = -1
    private var pressedIndex = -1
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var moved = false
    private var velocity: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
    private val longPress = Runnable { onLongPress() }
    private var repeating = false
    private val repeat = object : Runnable {
        override fun run() {
            if (!repeating) return
            controller?.onKey(BACKSPACE)
            postDelayed(this, 50)
        }
    }

    // Skin-tone popup (drawn by the shared overlay, hit-tested here).
    private var popupOpen = false
    private var popupItems: List<PopupItem> = emptyList()
    private var popupEntry: EmojiEntry? = null
    private var popupRect = RectF()
    private var popupCellW = 0f
    private var popupCellH = 0f
    private var popupSel = -1

    private fun regionAt(y: Float) = when {
        y < gridTop -> Region.TABS
        y >= gridBottom -> Region.BOTTOM
        else -> Region.GRID
    }

    private fun bottomIndexAt(x: Float, y: Float): Int {
        layoutBottom()
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in 0 until 4) {
            val r = bottomRects[i]
            val dx = max(0f, max(r.left - x, x - r.right))
            val dy = max(0f, max(r.top - y, y - r.bottom))
            val dd = dx * dx + dy * dy
            if (dd < bestD) { bestD = dd; best = i }
        }
        return best
    }

    private fun cellAtView(x: Float, y: Float): Pair<Int, Int>? = model?.cellAt(x, y - gridTop + scrollY, width.toFloat())

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val c = controller ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = e.x
                downY = e.y
                lastY = e.y
                moved = false
                region = regionAt(e.y)
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                when (region) {
                    Region.TABS -> pressedSlot = (e.x / slotWidth()).toInt().coerceIn(0, slots - 1)
                    Region.BOTTOM -> {
                        pressedSlot = bottomIndexAt(e.x, e.y)
                        if (pressedSlot == 2) {
                            tap(c)
                            c.onKey(BACKSPACE)
                            repeating = true
                            postDelayed(repeat, 400)
                        }
                    }
                    else -> {
                        val cell = cellAtView(e.x, e.y)
                        pressedSection = cell?.first ?: -1
                        pressedIndex = cell?.second ?: -1
                        if (cell != null) postDelayed(longPress, longPressMs)
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                if (popupOpen) {
                    val sel = popupIndexAt(e.x, e.y)
                    if (sel != popupSel) { popupSel = sel; pushPopup() }
                } else if (region == Region.GRID) {
                    if (!moved && abs(e.y - downY) > slop) {
                        moved = true
                        removeCallbacks(longPress)
                        pressedSection = -1
                    }
                    if (moved) {
                        scrollTo(scrollY - (e.y - lastY))
                        lastY = e.y
                    }
                } else if (region == Region.BOTTOM || region == Region.TABS) {
                    // Sliding off the pressed key or tab cancels it.
                    val still = if (region == Region.TABS) regionAt(e.y) == Region.TABS && (e.x / slotWidth()).toInt().coerceIn(0, slots - 1) == pressedSlot
                    else regionAt(e.y) == Region.BOTTOM && bottomIndexAt(e.x, e.y) == pressedSlot
                    if (!still && pressedSlot != -1) { stopRepeat(); pressedSlot = -1; invalidate() }
                }
            }
            MotionEvent.ACTION_UP -> {
                velocity?.addMovement(e)
                removeCallbacks(longPress)
                when {
                    popupOpen -> {
                        val sel = popupSel
                        val entry = popupEntry
                        val items = popupItems
                        dismissPopup()
                        if (entry != null && sel in items.indices) c.emoji.insertVariant(entry, items[sel].text)
                    }
                    region == Region.GRID && !moved -> {
                        val cell = cellAtView(e.x, e.y)
                        if (cell != null && cell == (pressedSection to pressedIndex)) {
                            cellText(cell.first, cell.second)?.let { tap(c); c.emoji.insert(it) }
                        }
                    }
                    region == Region.GRID -> {
                        val vt = velocity
                        if (vt != null) {
                            vt.computeCurrentVelocity(1000)
                            scroller.fling(0, scrollY.toInt(), 0, (-vt.yVelocity).toInt(), 0, 0, 0, maxScroll().toInt())
                            postInvalidateOnAnimation()
                        }
                    }
                    region == Region.TABS -> if (pressedSlot >= 0 && pressedSlot == (e.x / slotWidth()).toInt().coerceIn(0, slots - 1)) {
                        tap(c)
                        onTab(pressedSlot)
                    }
                    region == Region.BOTTOM -> {
                        val i = bottomIndexAt(e.x, e.y)
                        if (pressedSlot == i && i != 2) { tap(c); onBottom(c, i) }
                    }
                }
                finishTouch()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                dismissPopup()
                finishTouch()
            }
        }
        return true
    }

    private fun finishTouch() {
        stopRepeat()
        region = Region.NONE
        pressedSlot = -1
        pressedSection = -1
        pressedIndex = -1
        velocity?.recycle()
        velocity = null
        invalidate()
    }

    private fun tap(c: KeyboardController) {
        if (c.prefs.hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun stopRepeat() {
        repeating = false
        removeCallbacks(repeat)
    }

    private fun onBottom(c: KeyboardController, i: Int) {
        when (i) {
            0 -> c.emoji.close()
            1 -> c.onKey(SPACE)
            3 -> c.onKey(ENTER)
        }
    }

    /** Slot 0 opens the search, the last one Word Art; the others jump to their category's section. */
    private fun onTab(slot: Int) {
        if (slot == 0) {
            emoji?.openSearch()
            return
        }
        if (slot == wordArtSlot) {
            emoji?.openWordArt()
            return
        }
        val m = model ?: return
        val cat = EmojiCategory.entries[slot - 1]
        val s = m.sections.indexOfFirst { it.category == cat }
        if (s < 0) return
        val target = m.sectionTop(s).coerceIn(0f, maxScroll())
        scroller.startScroll(0, scrollY.toInt(), 0, (target - scrollY).toInt(), 250)
        postInvalidateOnAnimation()
    }

    private fun scrollTo(y: Float) {
        val v = y.coerceIn(0f, maxScroll())
        if (v == scrollY) return
        scrollY = v
        invalidate()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currY.toFloat())
            postInvalidateOnAnimation()
        } else if (scrollerStarted) {
            nodes.invalidateRoot()
        }
        scrollerStarted = !scroller.isFinished
    }

    // ------------------------------------------------------------------ skin-tone popup

    private fun onLongPress() {
        val c = controller ?: return
        val m = model ?: return
        if (region != Region.GRID || moved) return
        val sec = m.sections.getOrNull(pressedSection) ?: return
        val cell = sec.cells.getOrNull(pressedIndex) ?: return
        val entry = cell.entry
        if (!entry.hasTones) return
        if (c.prefs.hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val row = m.rows[m.firstRowAt(downY - gridTop + scrollY)]
        val cw = width.toFloat() / m.cols
        val col = pressedIndex - row.cellStart
        val items = (listOf(entry.text) + entry.variants).mapIndexed { i, t ->
            PopupItem(t, t, description = if (i == 0) entry.name else "${entry.name}, ${context.getString(TONE_STRINGS[i - 1])}")
        }
        val cols = items.size
        popupCellW = 44f * d
        popupCellH = 48f * d
        val pw = popupCellW * cols
        val ph = popupCellH
        val cellCx = cw * col + cw / 2f
        val cellTop = gridTop + row.top - scrollY
        val left = (cellCx - pw / 2).coerceIn(0f, max(0f, width - pw))
        popupRect = RectF(left, cellTop - ph - 4f * d, left + pw, cellTop - 4f * d)
        popupItems = items
        popupEntry = entry
        popupSel = -1
        popupOpen = true
        pushPopup()
    }

    private val sl = IntArray(2)
    private val ol = IntArray(2)

    private fun pushPopup() {
        val o = overlay ?: return
        getLocationInWindow(sl)
        o.getLocationInWindow(ol)
        o.show(popupItems, popupItems.size, popupCellW, popupCellH, popupRect.left + sl[0] - ol[0], popupRect.top + sl[1] - ol[1], popupSel)
    }

    private fun popupIndexAt(x: Float, y: Float): Int {
        if (!popupRect.contains(x, y)) return -1
        return ((x - popupRect.left) / popupCellW).toInt().coerceIn(0, popupItems.size - 1)
    }

    private fun dismissPopup() {
        if (!popupOpen) return
        popupOpen = false
        overlay?.hide()
        popupItems = emptyList()
        popupEntry = null
        popupSel = -1
    }

    override fun onDetachedFromWindow() {
        stopRepeat()
        removeCallbacks(longPress)
        dismissPopup()
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------ accessibility

    private val nodes = Nodes()

    /** The virtual accessibility view ids currently exposed (tabs, keys, visible emoji); for tests. */
    internal fun accessibilityIds(): List<Int> = nodes.ids()

    init {
        ViewCompat.setAccessibilityDelegate(this, nodes)
        contentDescription = context.getString(R.string.key_emoji_description)
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = nodes.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val page = gridH * 0.8f
        return when (action) {
            AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD -> { scrollTo(scrollY + page); nodes.invalidateRoot(); true }
            AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD -> { scrollTo(scrollY - page); nodes.invalidateRoot(); true }
            else -> super.performAccessibilityAction(action, arguments)
        }
    }

    private fun flatBase(m: EmojiGridModel, section: Int): Int = (0 until section).sumOf { m.sections[it].cells.size }

    private inner class Nodes : ExploreByTouchHelper(this) {
        private val tabBase = 0
        private val keyBase = 20
        private val cellBase = 1000

        fun ids(): List<Int> = ArrayList<Int>().also { getVisibleVirtualViews(it) }

        override fun getVirtualViewAt(x: Float, y: Float): Int {
            when (regionAt(y)) {
                Region.TABS -> return tabBase + (x / slotWidth()).toInt().coerceIn(0, slots - 1)
                Region.BOTTOM -> return keyBase + bottomIndexAt(x, y)
                else -> {
                    val m = model ?: return INVALID_ID
                    val cell = cellAtView(x, y) ?: return INVALID_ID
                    return cellBase + flatBase(m, cell.first) + cell.second
                }
            }
        }

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            for (i in 0 until slots) ids += tabBase + i
            val m = model
            if (m != null) {
                var i = m.firstRowAt(scrollY)
                while (i < m.rows.size) {
                    val r = m.rows[i]
                    if (gridTop + r.top - scrollY >= gridBottom) break
                    if (r.cellStart >= 0) {
                        val sec = m.sections[r.section]
                        for (col in 0 until m.cols) {
                            val idx = r.cellStart + col
                            if (idx < sec.cells.size) ids += cellBase + flatBase(m, r.section) + idx
                        }
                    }
                    i++
                }
            }
            for (i in 0 until 4) ids += keyBase + i
        }

        private fun locate(id: Int): Pair<Int, Int>? {
            val m = model ?: return null
            var flat = id - cellBase
            for ((s, sec) in m.sections.withIndex()) {
                if (flat < sec.cells.size) return s to flat
                flat -= sec.cells.size
            }
            return null
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val bounds = Rect()
            var description = ""
            when {
                id in tabBase until tabBase + slots -> {
                    val sw = slotWidth()
                    val slot = id - tabBase
                    bounds.set((sw * slot).toInt(), 0, ceil(sw * (slot + 1)).toInt(), tabsH.toInt())
                    description = when (slot) {
                        0 -> context.getString(R.string.emoji_search)
                        wordArtSlot -> context.getString(R.string.wordart_tab)
                        else -> context.getString(EmojiCategory.entries[slot - 1].titleRes())
                    }
                    node.isSelected = slot in 1..EmojiCategory.entries.size && EmojiCategory.entries[slot - 1] == currentCategory()
                }
                id in keyBase until keyBase + 4 -> {
                    layoutBottom()
                    val r = bottomRects[id - keyBase]
                    bounds.set(r.left.toInt(), r.top.toInt(), ceil(r.right).toInt(), ceil(r.bottom).toInt())
                    val c = controller
                    description = when (id - keyBase) {
                        0 -> context.getString(R.string.emoji_back_to_keys)
                        1 -> context.getString(R.string.emoji_space)
                        2 -> context.getString(R.string.emoji_delete)
                        else -> c?.profile?.enterLabel?.let { enterLabelText(it) } ?: "Enter"
                    }
                }
                else -> {
                    val loc = locate(id)
                    val m = model
                    if (loc == null || m == null) {
                        node.contentDescription = ""
                        node.setBoundsInParent(Rect(0, 0, 1, 1))
                        return
                    }
                    val sec = m.sections[loc.first]
                    val cell = sec.cells[loc.second]
                    val rowIdx = m.rows.indexOfFirst { it.section == loc.first && it.cellStart >= 0 && loc.second in it.cellStart until it.cellStart + m.cols }
                    val row = m.rows[rowIdx]
                    val cw = width.toFloat() / m.cols
                    val col = loc.second - row.cellStart
                    val top = gridTop + row.top - scrollY
                    bounds.set((cw * col).toInt(), max(top, gridTop).toInt(), ceil(cw * (col + 1)).toInt(), min(top + row.height, gridBottom).toInt())
                    val shown = cellText(loc.first, loc.second) ?: cell.text
                    val toneIdx = cell.entry.variants.indexOf(shown)
                    description = if (toneIdx >= 0) "${cell.entry.name}, ${context.getString(TONE_STRINGS[toneIdx])}" else cell.entry.name
                    if (cell.entry.hasTones) {
                        for (k in 0 until 5) {
                            node.addAction(AccessibilityActionCompat(TONE_ACTION + k, context.getString(R.string.emoji_tone_action, context.getString(TONE_STRINGS[k]))))
                        }
                    }
                }
            }
            node.contentDescription = description
            node.className = Button::class.java.name
            node.setBoundsInParent(bounds)
            node.isClickable = true
            node.addAction(AccessibilityActionCompat.ACTION_CLICK)
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: Bundle?): Boolean {
            val c = controller ?: return false
            when {
                id in tabBase until tabBase + slots -> if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) onTab(id - tabBase) else return false
                id in keyBase until keyBase + 4 -> if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) {
                    val i = id - keyBase
                    if (i == 2) c.onKey(BACKSPACE) else onBottom(c, i)
                } else return false
                else -> {
                    val loc = locate(id) ?: return false
                    val m = model ?: return false
                    val cell = m.sections[loc.first].cells[loc.second]
                    when {
                        action == AccessibilityNodeInfoCompat.ACTION_CLICK -> cellText(loc.first, loc.second)?.let { c.emoji.insert(it) } ?: return false
                        action in TONE_ACTION until TONE_ACTION + 5 && cell.entry.hasTones ->
                            c.emoji.insertVariant(cell.entry, cell.entry.variants[action - TONE_ACTION])
                        else -> return false
                    }
                }
            }
            invalidate()
            sendEventForVirtualView(id, AccessibilityEvent.TYPE_VIEW_CLICKED)
            return true
        }
    }

    private companion object {
        val BACKSPACE = Key(KeyKind.BACKSPACE, "⌫", description = "Delete")
        val SPACE = Key(KeyKind.SPACE, " ", " ")
        val ENTER = Key(KeyKind.ENTER, "")
        const val TONE_ACTION = 0x7F0F0000
        const val WORD_ART_LABEL = "Aa✨"
    }
}
