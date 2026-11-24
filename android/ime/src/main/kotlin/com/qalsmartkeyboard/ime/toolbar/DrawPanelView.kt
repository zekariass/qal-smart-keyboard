package com.qalsmartkeyboard.ime.toolbar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.draw.BackgroundPattern
import com.qalsmartkeyboard.ime.draw.DecodeResult
import com.qalsmartkeyboard.ime.draw.DrawBackground
import com.qalsmartkeyboard.ime.draw.DrawCanvasView
import com.qalsmartkeyboard.ime.draw.DrawController
import com.qalsmartkeyboard.ime.draw.DrawMode
import com.qalsmartkeyboard.ime.draw.DrawStatus
import com.qalsmartkeyboard.ime.draw.DrawTool
import com.qalsmartkeyboard.ime.draw.DrawingRenderer
import com.qalsmartkeyboard.ime.draw.InkDefaults
import com.qalsmartkeyboard.ime.share.ShareOutcome
import java.util.concurrent.Executor
import kotlin.math.max

/**
 * The Draw & Sketch panel (T8.4), opened by the Tools grid's "Draw" tile, in place of the keys at the keyboard's height.
 *
 * Canvas mode: a tools row (pen, highlighter, eraser, undo, redo, clear, background / colour switch), a row of ten colour
 * swatches (or the background choices), the canvas ([DrawCanvasView]), and a row of four thicknesses and save, insert, share
 * and gallery. Every control is a 48dp target with a spoken name. When the keyboard is short (a landscape phone) the canvas
 * keeps [MIN_CANVAS_DP] and the panel scrolls instead of squeezing it. Gallery mode: the newest saved drawings as
 * thumbnails; tap one to select it, then edit, duplicate, insert, share or delete (delete and clear need a second tap).
 *
 * Chrome uses the theme's tokens; the canvas shows the chosen background. The drawing exists only in memory until Save, and
 * Gallery files stay in the app's private storage (ADR 0031/0048); nothing is sent anywhere.
 */
class DrawPanelView(context: Context) : UtilityPanelView(context) {
    var controller: DrawController? = null
        set(value) {
            field = value
            if (value != null) canvasView.session = value.session
        }

    /** Runs thumbnail rendering off the main thread (the service passes a single background thread). */
    var executor: Executor = Executor { it.run() }

    /** Posts a result back to the main thread. */
    var mainPost: (Runnable) -> Unit = { it.run() }

    /** The image was committed into the field: the service closes the panel. */
    var onInserted: () -> Unit = {}

    /** Gallery thumbnail decoded for a drawing (tests read how many were shown). */
    val thumbnailCount: Int get() = thumbs.size

    val canvasView = DrawCanvasView(context)

    private var paperMode = false
    private val thumbs = HashMap<String, Bitmap>()
    private val thumbViews = HashMap<String, ImageView>()
    private var thumbGeneration = 0
    private val renderer = DrawingRenderer()
    private var statusView: TextView? = null

    init {
        canvasView.onStrokeAdded = { controller?.strokeAdded() }
    }

    /** The status line (tests). */
    val statusText: CharSequence get() = statusView?.text ?: ""

    override fun build() {
        val c = controller ?: return
        if (c.mode == DrawMode.GALLERY) buildGallery(c) else buildCanvas(c)
    }

    /** The controller changed: rebuild the chrome, keeping the canvas view (and its bitmap) and the scroll position. */
    fun refresh() {
        if (controller == null || visibility == View.GONE) return
        rebuildKeepingScroll()
    }

    /** The panel closed: free the canvas bitmaps and the thumbnails. */
    fun release() {
        thumbGeneration++
        column.removeAllViews()
        canvasView.release()
        for (v in thumbViews.values) v.setImageDrawable(null)
        thumbViews.clear()
        for (b in thumbs.values) b.recycle()
        thumbs.clear()
    }

    // ---- canvas mode ---------------------------------------------------------------------------------------------

    private fun buildCanvas(c: DrawController) {
        canvasView.session = c.session
        canvasView.tool = c.tool
        canvasView.colour = c.colour
        canvasView.widthUnits = c.widthUnits
        canvasView.checkerLight = theme.key
        canvasView.checkerDark = theme.special
        val s = c.session

        val tools = listOf(
            glyphButton(ToolIcon.PEN, str(R.string.draw_tool_pen), selected = c.tool == DrawTool.PEN) { c.selectTool(DrawTool.PEN) },
            glyphButton(ToolIcon.HIGHLIGHTER, str(R.string.draw_tool_highlighter), selected = c.tool == DrawTool.HIGHLIGHTER) { c.selectTool(DrawTool.HIGHLIGHTER) },
            glyphButton(ToolIcon.ERASER, str(R.string.draw_tool_eraser), selected = c.tool == DrawTool.ERASER) { c.selectTool(DrawTool.ERASER) },
            glyphButton(ToolIcon.UNDO, str(R.string.draw_undo), enabled = s.canUndo) { c.undo() },
            glyphButton(ToolIcon.REDO, str(R.string.draw_redo), enabled = s.canRedo) { c.redo() },
            glyphButton(
                if (c.clearArmed) ToolIcon.CHECK else ToolIcon.TRASH,
                if (c.clearArmed) str(R.string.draw_clear_confirm) else str(R.string.draw_clear),
                selected = c.clearArmed,
                enabled = !s.strokes.isEmpty() || c.clearArmed,
            ) { c.requestClear() },
            glyphButton(ToolIcon.PAPER, str(if (paperMode) R.string.draw_colours else R.string.draw_paper), selected = paperMode) {
                paperMode = !paperMode
                refresh()
            },
        )
        strip(tools)

        if (paperMode) {
            strip(
                DrawBackground.PRESETS.map { bg ->
                    val name = backgroundName(bg)
                    swatchButton(bg.colour, bg.pattern, str(R.string.draw_bg_desc, name), selected = s.background == bg) { c.setBackground(bg) }
                },
            )
        } else {
            strip(
                InkDefaults.PALETTE.mapIndexed { i, colour ->
                    swatchButton(colour, BackgroundPattern.NONE, str(R.string.draw_colour_desc, colourName(i)), selected = c.colour == colour && c.tool != DrawTool.ERASER) {
                        c.selectColour(colour)
                    }
                },
            )
        }

        val h = heightProvider().takeIf { it > 0 } ?: dp(260f)
        val canvasH = max(dp(MIN_CANVAS_DP) + dp(4f), h - dp(CHROME_DP))
        // The status line floats over the canvas's lower edge (no row of its own), so the canvas keeps its height.
        val status = caption(statusLabel(c.status, c.clearArmed), 12f, 1).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setTextColor(theme.specialText)
            background = card(theme.special)
            visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
        statusView = status
        val holder = FrameLayout(context).apply {
            setPadding(dp(2f), dp(2f), dp(2f), dp(2f))
            background = card(theme.special)
            (canvasView.parent as? ViewGroup)?.removeView(canvasView)
            addView(canvasView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(6f), 0, dp(6f), dp(6f)) })
        }
        canvasView.contentDescription = str(R.string.draw_canvas_description)
        column.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, canvasH).apply { bottomMargin = dp(4f) })

        val widths = InkDefaults.WIDTHS.indices.map { i ->
            widthButton(i, selected = c.widthIndex == i) { c.selectWidth(i) }
        }
        val actions = listOf(
            glyphButton(ToolIcon.SAVE, str(R.string.draw_save)) { c.save() },
            glyphButton(ToolIcon.IMAGE_INSERT, str(R.string.draw_insert)) {
                if (c.insert(str(R.string.draw_insert_description), str(R.string.draw_share_title)) == ShareOutcome.INSERTED) onInserted()
            },
            glyphButton(ToolIcon.SHARE, str(R.string.draw_share)) { c.share(str(R.string.draw_share_title)) },
            glyphButton(ToolIcon.GALLERY, str(R.string.draw_gallery)) { c.openGallery() },
        )
        strip(widths + actions)
    }

    // ---- gallery mode --------------------------------------------------------------------------------------------

    private fun buildGallery(c: DrawController) {
        row(
            button(str(R.string.draw_gallery_back), str(R.string.draw_gallery_back), sp = 13f) { c.closeGallery() } to 1f,
            button(str(R.string.draw_gallery_new), str(R.string.draw_gallery_new), PadStyle.ACCENT, sp = 13f) { c.newDrawing() } to 1f,
            stretch = false,
        )
        val list = c.gallery
        thumbViews.clear()
        if (list.isEmpty()) {
            column.addView(
                caption(str(R.string.draw_gallery_empty), 13f, 3),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        val gap = dp(6f)
        val avail = (if (width > 0) width else resources.displayMetrics.widthPixels) - dp(16f)
        val cell = (avail - gap * (COLUMNS - 1)) / COLUMNS
        val cellH = (cell * 0.75f).toInt()
        list.chunked(COLUMNS).forEachIndexed { rowIndex, chunk ->
            val r = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEachIndexed { i, item ->
                val number = rowIndex * COLUMNS + i + 1
                val selected = c.selectedId == item.id
                val iv = track(
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
                        minimumHeight = dp(MIN_TARGET_DP)
                        minimumWidth = dp(MIN_TARGET_DP)
                        isClickable = true
                        isFocusable = true
                        contentDescription = str(R.string.draw_gallery_item, number, list.size)
                        background = card(if (selected) theme.accent else theme.key)
                        thumbs[item.id]?.let { setImageBitmap(it) }
                        setOnClickListener { haptic(); c.select(item.id) }
                    },
                )
                asSelectable(iv, selected)
                thumbViews[item.id] = iv
                r.addView(iv, LinearLayout.LayoutParams(cell, cellH).apply { if (i > 0) marginStart = gap })
                if (thumbs[item.id] == null) loadThumbnail(c, item.id)
            }
            column.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = gap })
        }
        val status = caption(statusLabel(c.status, false), 12f, 1).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        statusView = status
        column.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val id = c.selectedId ?: return
        val armed = c.deleteArmedId == id
        val title = str(R.string.draw_insert_description)
        chipStrip(
            listOf(
                button(str(R.string.draw_gallery_edit), str(R.string.draw_gallery_edit), PadStyle.ACCENT, sp = 13f) { c.edit(id) },
                button(str(R.string.draw_gallery_duplicate), str(R.string.draw_gallery_duplicate), sp = 13f) { c.duplicate(id) },
                button(str(R.string.draw_insert), str(R.string.draw_insert), sp = 13f) {
                    if (c.insertSaved(id, title, str(R.string.draw_share_title)) == ShareOutcome.INSERTED) onInserted()
                },
                button(str(R.string.draw_share), str(R.string.draw_share), sp = 13f) { c.shareSaved(id, str(R.string.draw_share_title)) },
                button(
                    if (armed) str(R.string.draw_gallery_delete_confirm) else str(R.string.draw_gallery_delete),
                    if (armed) str(R.string.draw_gallery_delete_confirm) else str(R.string.draw_gallery_delete),
                    if (armed) PadStyle.ACCENT else PadStyle.SPECIAL,
                    sp = 13f,
                ) { c.requestDelete(id) },
            ),
        )
    }

    /** Renders the thumbnail of [id] on the background executor and shows it if the gallery is still the same one. */
    private fun loadThumbnail(c: DrawController, id: String) {
        val gen = thumbGeneration
        val px = (THUMB_DP * d).toInt()
        executor.execute {
            val drawing = (c.store.load(id) as? DecodeResult.Ok)?.drawing ?: return@execute
            val bmp = renderer.thumbnail(drawing, px)
            mainPost(
                Runnable {
                    val view = thumbViews[id]
                    if (gen != thumbGeneration || view == null || thumbs.containsKey(id)) {
                        bmp.recycle()
                    } else {
                        thumbs[id] = bmp
                        view.setImageBitmap(bmp)
                    }
                },
            )
        }
    }

    // ---- controls ------------------------------------------------------------------------------------------------

    private fun str(id: Int) = context.getString(id)
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun haptic() {
        if (haptics()) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun statusLabel(s: DrawStatus?, clearArmed: Boolean): String = when {
        clearArmed -> str(R.string.draw_clear_confirm)
        s == null -> ""
        else -> str(
            when (s) {
                DrawStatus.SAVED -> R.string.draw_status_saved
                DrawStatus.SAVE_FAILED -> R.string.draw_status_save_failed
                DrawStatus.NOTHING_TO_SEND -> R.string.draw_status_nothing
                DrawStatus.INSERTED -> R.string.draw_status_inserted
                DrawStatus.SHARED -> R.string.draw_status_shared
                DrawStatus.SEND_FAILED -> R.string.draw_status_failed
                DrawStatus.DELETED -> R.string.draw_status_deleted
                DrawStatus.DUPLICATED -> R.string.draw_status_duplicated
                DrawStatus.OPEN_FAILED -> R.string.draw_status_open_failed
            },
        )
    }

    private fun colourName(i: Int) = str(
        listOf(
            R.string.draw_colour_black, R.string.draw_colour_white, R.string.draw_colour_red, R.string.draw_colour_orange,
            R.string.draw_colour_yellow, R.string.draw_colour_green, R.string.draw_colour_teal, R.string.draw_colour_blue,
            R.string.draw_colour_purple, R.string.draw_colour_brown,
        )[i],
    )

    private fun backgroundName(bg: DrawBackground) = str(
        when (bg) {
            DrawBackground.WHITE -> R.string.draw_bg_white
            DrawBackground.BLACK -> R.string.draw_bg_black
            DrawBackground.TRANSPARENT -> R.string.draw_bg_transparent
            DrawBackground.CREAM -> R.string.draw_bg_cream
            DrawBackground.MINT -> R.string.draw_bg_mint
            DrawBackground.SKY -> R.string.draw_bg_sky
            DrawBackground.RULED -> R.string.draw_bg_ruled
            else -> R.string.draw_bg_grid
        },
    )

    /** A row of equal 48dp-minimum controls that stretches to the width, or scrolls sideways when they do not fit. */
    private fun strip(views: List<View>) {
        val inner = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        views.forEachIndexed { i, v ->
            inner.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(4f) })
        }
        val scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            addView(inner, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        column.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4f) })
    }

    private fun asSelectable(v: View, selected: Boolean) {
        ViewCompat.setAccessibilityDelegate(
            v,
            object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.Button::class.java.name
                    info.isSelected = selected
                }
            },
        )
    }

    private fun styleBackground(selected: Boolean): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), card(theme.pressed))
        addState(intArrayOf(), card(fillOf(if (selected) PadStyle.ACCENT else PadStyle.SPECIAL)))
    }

    /** A 48dp icon button: [selected] = the accent fill; a disabled one is dimmed (it stays focusable, named, and can be read). */
    private fun glyphButton(icon: ToolIcon, description: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit): View =
        track(
            object : View(context) {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                private val tint = textColorOf(if (selected) PadStyle.ACCENT else PadStyle.SPECIAL)
                override fun onMeasure(w: Int, h: Int) {
                    setMeasuredDimension(resolveSize(dp(MIN_TARGET_DP), w), resolveSize(dp(MIN_TARGET_DP), h))
                }

                override fun onDraw(canvas: Canvas) {
                    ToolIcons.draw(canvas, icon, width / 2f, height / 2f, 22f * d, tint, paint)
                }
            }.apply {
                minimumHeight = dp(MIN_TARGET_DP)
                minimumWidth = dp(MIN_TARGET_DP)
                isClickable = true
                isFocusable = true
                contentDescription = description
                alpha = if (enabled) 1f else 0.4f
                background = styleBackground(selected)
                setOnClickListener {
                    if (!enabled) return@setOnClickListener
                    haptic()
                    onClick()
                }
                asSelectable(this, selected)
            },
        )

    /** A colour or background swatch: a disc of the colour (a checkerboard hint for transparent, lines for ruled / grid). */
    private fun swatchButton(colour: Int, pattern: BackgroundPattern, description: String, selected: Boolean, onClick: () -> Unit): View =
        track(
            object : View(context) {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                override fun onMeasure(w: Int, h: Int) {
                    setMeasuredDimension(resolveSize(dp(MIN_TARGET_DP), w), resolveSize(dp(MIN_TARGET_DP), h))
                }

                override fun onDraw(canvas: Canvas) {
                    val cx = width / 2f
                    val cy = height / 2f
                    val r = 14f * d
                    paint.style = Paint.Style.FILL
                    if ((colour ushr 24) == 0) {
                        paint.color = theme.key
                        canvas.drawCircle(cx, cy, r, paint)
                        paint.color = theme.special
                        canvas.drawRect(cx - r * 0.7f, cy - r * 0.7f, cx, cy, paint)
                        canvas.drawRect(cx, cy, cx + r * 0.7f, cy + r * 0.7f, paint)
                    } else {
                        paint.color = colour
                        canvas.drawCircle(cx, cy, r, paint)
                    }
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1.5f * d
                    paint.color = 0x80808080.toInt()
                    if (pattern != BackgroundPattern.NONE) {
                        canvas.drawLine(cx - r * 0.7f, cy - r * 0.3f, cx + r * 0.7f, cy - r * 0.3f, paint)
                        canvas.drawLine(cx - r * 0.7f, cy + r * 0.3f, cx + r * 0.7f, cy + r * 0.3f, paint)
                        if (pattern == BackgroundPattern.GRID) {
                            canvas.drawLine(cx - r * 0.3f, cy - r * 0.7f, cx - r * 0.3f, cy + r * 0.7f, paint)
                            canvas.drawLine(cx + r * 0.3f, cy - r * 0.7f, cx + r * 0.3f, cy + r * 0.7f, paint)
                        }
                    }
                    canvas.drawCircle(cx, cy, r, paint)
                    if (selected) {
                        paint.strokeWidth = 2.5f * d
                        paint.color = theme.keyText
                        canvas.drawCircle(cx, cy, r + 4f * d, paint)
                    }
                }
            }.apply {
                minimumHeight = dp(MIN_TARGET_DP)
                minimumWidth = dp(MIN_TARGET_DP)
                isClickable = true
                isFocusable = true
                contentDescription = description
                background = styleBackground(false)
                setOnClickListener { haptic(); onClick() }
                asSelectable(this, selected)
            },
        )

    /** A thickness preset: a dot as thick as the pen it picks. */
    private fun widthButton(index: Int, selected: Boolean, onClick: () -> Unit): View {
        val names = listOf(R.string.draw_width_thin, R.string.draw_width_medium, R.string.draw_width_thick, R.string.draw_width_xthick)
        val units = InkDefaults.WIDTHS[index]
        return track(
            object : View(context) {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                private val tint = textColorOf(if (selected) PadStyle.ACCENT else PadStyle.SPECIAL)
                override fun onMeasure(w: Int, h: Int) {
                    setMeasuredDimension(resolveSize(dp(MIN_TARGET_DP), w), resolveSize(dp(MIN_TARGET_DP), h))
                }

                override fun onDraw(canvas: Canvas) {
                    paint.style = Paint.Style.FILL
                    paint.color = tint
                    canvas.drawCircle(width / 2f, height / 2f, (units / 2f).coerceAtLeast(1.2f) * d, paint)
                }
            }.apply {
                minimumHeight = dp(MIN_TARGET_DP)
                minimumWidth = dp(MIN_TARGET_DP)
                isClickable = true
                isFocusable = true
                contentDescription = str(names[index])
                background = styleBackground(selected)
                setOnClickListener { haptic(); onClick() }
                asSelectable(this, selected)
            },
        )
    }

    companion object {
        /** The canvas keeps at least this height; a shorter keyboard scrolls the panel instead. */
        const val MIN_CANVAS_DP = 112f

        /** The three control rows, their gaps, the status line and the padding around the canvas. */
        private const val CHROME_DP = 3 * 52f + 8f + 4f
        private const val COLUMNS = 3
        private const val THUMB_DP = 140f
    }
}
