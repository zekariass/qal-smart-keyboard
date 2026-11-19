package com.qalsmartkeyboard.ime.emoji

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
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
import com.qalsmartkeyboard.ime.CandidateStripView
import com.qalsmartkeyboard.ime.KeyboardController
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.theme.KeyboardTheme
import com.qalsmartkeyboard.ime.theme.ThemeCatalog
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The emoji search strip (ADR 0028), shown in place of the bar while the search is open and the normal keys are
 * back (Gboard behaviour): a back arrow (return to the panel), the query box and, to its right, the matching
 * emoji in one horizontally scrolling row (the recent ones while the query is empty). The keys type into the
 * query ([EmojiController.querySink]); tapping a result inserts it into the host field and leaves the search open.
 * Always as tall as the bar it replaces, so the keyboard keeps its height.
 */
class EmojiSearchBar(context: Context) : View(context) {
    var controller: KeyboardController? = null

    var theme: KeyboardTheme = ThemeCatalog.classic(
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES,
    )
        set(value) {
            if (field === value) return
            field = value
            invalidate()
        }

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    private val backW = 44f * d
    private val cellW = 44f * d
    private val pillRect = RectF()
    private var resultsLeft = 0f
    private var scrollX0 = 0f
    private val scroller = OverScroller(context)
    private var results: List<EmojiCell> = emptyList()
    private var query = ""

    /** Re-reads the query and results from the emoji state. */
    fun render() {
        val e = controller?.emoji ?: return
        val changed = e.results !== results
        results = e.results
        val q = e.query
        if (q != query) {
            query = q
            contentDescription = context.getString(R.string.emoji_search_field, q)
        }
        if (changed) {
            scroller.forceFinished(true)
            scrollX0 = 0f
            nodes.invalidateRoot()
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(suggestedMinimumWidth, widthMeasureSpec), (CandidateStripView.HEIGHT_DP * d).toInt())
    }

    private fun layoutPill() {
        val w = min(max(width * 0.36f, 120f * d), 190f * d)
        pillRect.set(backW, 6f * d, backW + w, height - 6f * d)
        resultsLeft = pillRect.right + 8f * d
    }

    private fun maxScroll() = max(0f, results.size * cellW - (width - resultsLeft))

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        layoutPill()
        nodes.invalidateRoot()
    }

    override fun onDraw(canvas: Canvas) {
        layoutPill()
        val h = height.toFloat()
        canvas.drawColor(theme.stripBg)
        // Back arrow.
        icon.color = theme.stripText
        icon.strokeWidth = 2f * d
        val ax = backW / 2f
        val ay = h / 2f
        canvas.drawLine(ax + 6 * d, ay, ax - 6 * d, ay, icon)
        canvas.drawLine(ax - 6 * d, ay, ax - 1 * d, ay - 5.5f * d, icon)
        canvas.drawLine(ax - 6 * d, ay, ax - 1 * d, ay + 5.5f * d, icon)
        // Query box: the end of the query stays visible (clipped at the start).
        fill.color = theme.key
        canvas.drawRoundRect(pillRect, pillRect.height() / 2f, pillRect.height() / 2f, fill)
        text.textSize = 15f * d
        val pad = 12f * d
        val avail = pillRect.width() - pad * 2
        canvas.save()
        canvas.clipRect(pillRect.left + pad, pillRect.top, pillRect.right - pad, pillRect.bottom)
        val base = pillRect.centerY() - (text.descent() + text.ascent()) / 2f
        if (query.isEmpty()) {
            text.color = theme.hintText
            canvas.drawText(context.getString(R.string.emoji_search), pillRect.left + pad, base, text)
        } else {
            text.color = theme.keyText
            val tw = text.measureText(query)
            canvas.drawText(query, pillRect.left + pad - max(0f, tw - avail + 2 * d), base, text)
            icon.color = theme.keyText
            icon.strokeWidth = 1.5f * d
            val cx = pillRect.left + pad + min(tw, avail - 2 * d)
            canvas.drawLine(cx + 2 * d, pillRect.centerY() - 8 * d, cx + 2 * d, pillRect.centerY() + 8 * d, icon)
        }
        canvas.restore()
        // Results.
        canvas.save()
        canvas.clipRect(resultsLeft, 0f, width.toFloat(), h)
        if (results.isEmpty()) {
            if (controller?.emoji?.catalog != null && query.isNotBlank()) {
                text.color = theme.stripText
                text.textSize = 13f * d
                canvas.drawText(context.getString(R.string.emoji_no_results), resultsLeft + 4 * d, h / 2f - (text.descent() + text.ascent()) / 2f, text)
            }
        } else {
            glyph.textSize = 26f * d
            val first = max(0, (scrollX0 / cellW).toInt())
            var i = first
            while (i < results.size) {
                val x = resultsLeft + i * cellW - scrollX0
                if (x >= width) break
                if (i == pressed) {
                    fill.color = theme.pressed
                    canvas.drawRoundRect(x + 2 * d, 4 * d, x + cellW - 2 * d, h - 4 * d, 8 * d, 8 * d, fill)
                }
                canvas.drawText(results[i].text, x + cellW / 2f, h / 2f - (glyph.descent() + glyph.ascent()) / 2f, glyph)
                i++
            }
        }
        canvas.restore()
        fill.color = theme.stripDivider
        canvas.drawRect(0f, h - d, width.toFloat(), h, fill)
    }

    // ------------------------------------------------------------------ touch

    private var pressed = -1
    private var downX = 0f
    private var lastX = 0f
    private var moved = false
    private var onBack = false
    private var velocity: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private fun resultAt(x: Float): Int {
        if (x < resultsLeft) return -1
        val i = ((x - resultsLeft + scrollX0) / cellW).toInt()
        return if (i in results.indices) i else -1
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val c = controller ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = e.x
                lastX = e.x
                moved = false
                onBack = e.x < backW
                pressed = if (onBack) -1 else resultAt(e.x)
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                if (!moved && abs(e.x - downX) > slop) { moved = true; pressed = -1 }
                if (moved && !onBack) {
                    scrollX0 = (scrollX0 - (e.x - lastX)).coerceIn(0f, maxScroll())
                    lastX = e.x
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                velocity?.addMovement(e)
                if (!moved) {
                    if (onBack && e.x < backW) {
                        haptic(c)
                        c.emoji.closeSearch()
                    } else {
                        val i = resultAt(e.x)
                        if (i >= 0 && i == pressed) {
                            haptic(c)
                            c.emoji.insert(results[i].text)
                        }
                    }
                } else if (!onBack) {
                    velocity?.let {
                        it.computeCurrentVelocity(1000)
                        scroller.fling(scrollX0.toInt(), 0, (-it.xVelocity).toInt(), 0, 0, maxScroll().toInt(), 0, 0)
                        postInvalidateOnAnimation()
                    }
                }
                finish()
            }
            MotionEvent.ACTION_CANCEL -> finish()
        }
        return true
    }

    private fun finish() {
        pressed = -1
        velocity?.recycle()
        velocity = null
        invalidate()
    }

    private fun haptic(c: KeyboardController) {
        if (c.prefs.hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollX0 = scroller.currX.toFloat().coerceIn(0f, maxScroll())
            postInvalidateOnAnimation()
        }
    }

    // ------------------------------------------------------------------ accessibility

    private val nodes = Nodes()

    init {
        ViewCompat.setAccessibilityDelegate(this, nodes)
        contentDescription = context.getString(R.string.emoji_search)
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = nodes.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    private inner class Nodes : ExploreByTouchHelper(this) {
        private val backId = 0
        private val resultBase = 10

        override fun getVirtualViewAt(x: Float, y: Float): Int = when {
            x < backW -> backId
            else -> resultAt(x).let { if (it >= 0) resultBase + it else INVALID_ID }
        }

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            layoutPill()
            ids += backId
            for (i in results.indices) {
                val x = resultsLeft + i * cellW - scrollX0
                if (x + cellW > resultsLeft && x < width) ids += resultBase + i
            }
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val b = Rect()
            if (id == backId) {
                b.set(0, 0, backW.toInt(), height)
                node.contentDescription = context.getString(R.string.emoji_search_close)
            } else {
                val i = id - resultBase
                val cell = results.getOrNull(i)
                if (cell == null) {
                    node.contentDescription = ""
                    node.setBoundsInParent(Rect(0, 0, 1, 1))
                    return
                }
                val x = resultsLeft + i * cellW - scrollX0
                b.set(max(x, resultsLeft).toInt(), 0, ceil(min(x + cellW, width.toFloat())).toInt(), height)
                node.contentDescription = cell.entry.name
            }
            node.className = Button::class.java.name
            node.setBoundsInParent(b)
            node.isClickable = true
            node.addAction(AccessibilityActionCompat.ACTION_CLICK)
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: Bundle?): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            val c = controller ?: return false
            if (id == backId) c.emoji.closeSearch() else results.getOrNull(id - resultBase)?.let { c.emoji.insert(it.text) } ?: return false
            sendEventForVirtualView(id, AccessibilityEvent.TYPE_VIEW_CLICKED)
            return true
        }
    }
}
