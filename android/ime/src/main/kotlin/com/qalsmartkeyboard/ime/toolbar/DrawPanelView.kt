package com.qalsmartkeyboard.ime.toolbar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
import com.qalsmartkeyboard.ime.draw.Brush
import com.qalsmartkeyboard.ime.draw.DecodeResult
import com.qalsmartkeyboard.ime.draw.DrawBackground
import com.qalsmartkeyboard.ime.draw.DrawCanvasView
import com.qalsmartkeyboard.ime.draw.DrawController
import com.qalsmartkeyboard.ime.draw.DrawInput
import com.qalsmartkeyboard.ime.draw.DrawMode
import com.qalsmartkeyboard.ime.draw.DrawOptions
import com.qalsmartkeyboard.ime.draw.DrawStatus
import com.qalsmartkeyboard.ime.draw.DrawTool
import com.qalsmartkeyboard.ime.draw.DrawingRenderer
import com.qalsmartkeyboard.ime.draw.DrawingStore
import com.qalsmartkeyboard.ime.draw.InkDefaults
import com.qalsmartkeyboard.ime.draw.Pt
import com.qalsmartkeyboard.ime.draw.ShapeKind
import com.qalsmartkeyboard.ime.draw.ShapeObject
import com.qalsmartkeyboard.ime.draw.Stroke
import com.qalsmartkeyboard.ime.draw.StrokeRenderer
import com.qalsmartkeyboard.ime.draw.TextObject
import com.qalsmartkeyboard.ime.draw.TextSize
import com.qalsmartkeyboard.ime.share.ShareOutcome
import java.util.concurrent.Executor
import kotlin.math.max

/**
 * The Draw & Sketch panel (T8.4, T8.15), opened by the Tools grid's "Draw" tile, in place of the keys at the keyboard's height.
 *
 * Canvas mode, three control rows around the canvas ([DrawCanvasView]):
 *
 * 1. The tools: brushes, eraser, shapes, text, select / move, then undo, redo, clear and the paper (background) switch.
 * 2. What the chosen tool needs: the ink colours (all but the select tool, which has Delete / Edit first), the brushes, the
 *    shapes with their fill switch, the text sizes and bold, or the papers. Scrolls sideways when it is longer than the width.
 * 3. The four thicknesses, then save, insert, share and gallery.
 *
 * Every control is a 48dp target with a spoken name. When the keyboard is short the canvas keeps [MIN_CANVAS_DP] and the panel
 * scrolls instead of squeezing it. Gallery mode: the saved drawings as thumbnails, nine to a page, newest first; tap one to
 * select it, then edit, duplicate, insert, share or delete (delete and clear need a second tap). A text is typed through the
 * keyboard's own typing strip (the service shows [DrawFieldBar] while [DrawController.typing]).
 *
 * Chrome uses the theme's tokens; the canvas shows the chosen background. The drawing exists only in memory until Save, and
 * gallery files stay in the app's private storage (ADR 0031/0048); nothing is sent anywhere. The thumbnails are rendered off
 * the main thread, one page of them at a time, and freed when the panel closes ([release]).
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

    /** Gallery thumbnails alive (tests read how many were shown). */
    val thumbnailCount: Int get() = thumbs.size

    val canvasView = DrawCanvasView(context)

    private val thumbs = HashMap<String, Bitmap>()
    private val thumbViews = HashMap<String, ImageView>()
    private var thumbGeneration = 0
    private var statusView: TextView? = null

    init {
        canvasView.onStrokeAdded = { controller?.strokeAdded() }
        canvasView.onShapeDrawn = { x1, y1, x2, y2 -> controller?.placeShape(x1, y1, x2, y2) }
        canvasView.onTextTap = { x, y -> controller?.beginText(x, y) }
        canvasView.onTextEdit = { id -> controller?.editText(id) }
        canvasView.onObjectTapped = { id -> controller?.selectAt(id) }
        canvasView.onObjectMoved = { dx, dy -> controller?.moveSelected(dx, dy) }
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
        canvasView.brush = c.brush
        canvasView.colour = c.colour
        canvasView.widthUnits = c.widthUnits
        canvasView.input = c.input
        canvasView.shapeKind = c.shapeKind
        canvasView.shapeFilled = c.shapeFilled
        canvasView.checkerLight = theme.key
        canvasView.checkerDark = theme.special
        canvasView.selectionColour = theme.accent
        val s = c.session
        val ink = c.input == DrawInput.INK

        val tools = listOf(
            glyphButton(ToolIcon.PEN, str(R.string.draw_brushes), selected = ink && c.tool != DrawTool.ERASER) { c.toggleBrushes() },
            glyphButton(ToolIcon.ERASER, str(R.string.draw_tool_eraser), selected = ink && c.tool == DrawTool.ERASER) { c.selectTool(DrawTool.ERASER) },
            glyphButton(ToolIcon.SHAPES, str(R.string.draw_shapes), selected = c.input == DrawInput.SHAPE) { c.selectShape(c.shapeKind) },
            glyphButton(ToolIcon.TEXT, str(R.string.draw_text), selected = c.input == DrawInput.TEXT) { c.selectTextTool() },
            glyphButton(ToolIcon.SELECT, str(R.string.draw_select), selected = c.input == DrawInput.SELECT) { c.selectSelectTool() },
            glyphButton(ToolIcon.UNDO, str(R.string.draw_undo), enabled = s.canUndo) { c.undo() },
            glyphButton(ToolIcon.REDO, str(R.string.draw_redo), enabled = s.canRedo) { c.redo() },
            glyphButton(
                if (c.clearArmed) ToolIcon.CHECK else ToolIcon.TRASH,
                if (c.clearArmed) str(R.string.draw_clear_confirm) else str(R.string.draw_clear),
                selected = c.clearArmed,
                enabled = s.strokes.isNotEmpty() || s.objects.isNotEmpty() || c.clearArmed,
            ) { c.requestClear() },
            glyphButton(ToolIcon.PAPER, str(if (c.options == DrawOptions.PAPER) R.string.draw_colours else R.string.draw_paper), selected = c.options == DrawOptions.PAPER) {
                c.togglePaper()
            },
        )
        strip(tools)
        strip(optionsRow(c))

        val h = heightProvider().takeIf { it > 0 } ?: dp(260f)
        val canvasH = max(dp(MIN_CANVAS_DP) + dp(4f), h - dp(CHROME_DP))
        // The status line floats over the canvas's lower edge (no row of its own), so the canvas keeps its height.
        val status = caption(statusLabel(c), 12f, 1).apply {
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
        column.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, canvasH).apply { bottomMargin = dp(GAP_DP) })

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

    /** The second row: what the chosen tool needs. */
    private fun optionsRow(c: DrawController): List<View> {
        val s = c.session
        val picked = s.selected()
        if (c.options == DrawOptions.PAPER) {
            return DrawBackground.PRESETS.map { bg ->
                swatchButton(bg.colour, bg.pattern, str(R.string.draw_bg_desc, backgroundName(bg)), selected = s.background == bg) { c.setBackground(bg) }
            }
        }
        if (c.options == DrawOptions.BRUSHES) {
            val ink = c.input == DrawInput.INK
            return listOf(
                brushButton(DrawTool.PEN, Brush.PEN, str(R.string.draw_tool_pen), ink && c.tool == DrawTool.PEN && c.brush == Brush.PEN) { c.selectBrush(Brush.PEN) },
                brushButton(DrawTool.PEN, Brush.MARKER, str(R.string.draw_brush_marker), ink && c.tool == DrawTool.PEN && c.brush == Brush.MARKER) { c.selectBrush(Brush.MARKER) },
                brushButton(DrawTool.HIGHLIGHTER, Brush.PEN, str(R.string.draw_tool_highlighter), ink && c.tool == DrawTool.HIGHLIGHTER) { c.selectTool(DrawTool.HIGHLIGHTER) },
                brushButton(DrawTool.PEN, Brush.PENCIL, str(R.string.draw_brush_pencil), ink && c.tool == DrawTool.PEN && c.brush == Brush.PENCIL) { c.selectBrush(Brush.PENCIL) },
                brushButton(DrawTool.PEN, Brush.DASHED, str(R.string.draw_brush_dashed), ink && c.tool == DrawTool.PEN && c.brush == Brush.DASHED) { c.selectBrush(Brush.DASHED) },
                brushButton(DrawTool.PEN, Brush.DOTTED, str(R.string.draw_brush_dotted), ink && c.tool == DrawTool.PEN && c.brush == Brush.DOTTED) { c.selectBrush(Brush.DOTTED) },
                brushButton(DrawTool.PEN, Brush.CALLIGRAPHY, str(R.string.draw_brush_calligraphy), ink && c.tool == DrawTool.PEN && c.brush == Brush.CALLIGRAPHY) { c.selectBrush(Brush.CALLIGRAPHY) },
            )
        }
        val lead = ArrayList<View>()
        when (c.input) {
            DrawInput.SHAPE -> {
                val shapeNow = (picked as? ShapeObject)?.kind ?: c.shapeKind
                for ((kind, name) in listOf(
                    ShapeKind.LINE to R.string.draw_shape_line, ShapeKind.ARROW to R.string.draw_shape_arrow,
                    ShapeKind.RECTANGLE to R.string.draw_shape_rect, ShapeKind.ELLIPSE to R.string.draw_shape_ellipse,
                )) {
                    lead += shapeButton(kind, str(name), selected = c.shapeKind == kind && shapeNow == kind) { c.selectShape(kind) }
                }
                lead += fillButton(c, picked)
            }
            DrawInput.TEXT -> lead += textStyleButtons(c, picked)
            DrawInput.SELECT -> {
                lead += glyphButton(ToolIcon.TRASH, str(R.string.draw_object_delete), enabled = picked != null) { c.deleteSelected() }
                if (picked is TextObject) {
                    lead += glyphButton(ToolIcon.PEN, str(R.string.draw_text_edit)) { c.editSelectedText() }
                    lead += textStyleButtons(c, picked)
                }
                if (picked is ShapeObject) lead += fillButton(c, picked)
            }
            DrawInput.INK -> Unit
        }
        val colours = InkDefaults.PALETTE.mapIndexed { i, colour ->
            val on = (picked?.colour ?: c.colour) == colour && !(c.input == DrawInput.INK && c.tool == DrawTool.ERASER)
            swatchButton(colour, BackgroundPattern.NONE, str(R.string.draw_colour_desc, colourName(i)), selected = on) { c.selectColour(colour) }
        }
        return lead + colours
    }

    private fun fillButton(c: DrawController, picked: com.qalsmartkeyboard.ime.draw.DrawObject?): View {
        val on = (picked as? ShapeObject)?.filled ?: c.shapeFilled
        return glyphButton(ToolIcon.FILL, str(R.string.draw_shape_fill), selected = on) { c.setShapeFilled(!on) }
    }

    private fun textStyleButtons(c: DrawController, picked: com.qalsmartkeyboard.ime.draw.DrawObject?): List<View> {
        val size = (picked as? TextObject)?.size ?: c.textSize
        val bold = (picked as? TextObject)?.bold ?: c.textBold
        val names = listOf(R.string.draw_text_size_small, R.string.draw_text_size_medium, R.string.draw_text_size_large, R.string.draw_text_size_xlarge)
        val out = ArrayList<View>()
        TextSize.entries.forEachIndexed { i, ts ->
            val on = size == ts
            out += button("A", str(names[i]), if (on) PadStyle.ACCENT else PadStyle.SPECIAL, sp = 11f + 3f * i) { c.selectTextSize(ts) }.also { asSelectable(it, on) }
        }
        out += button("B", str(R.string.draw_text_bold), if (bold) PadStyle.ACCENT else PadStyle.SPECIAL, sp = 18f) { c.toggleBold() }.also { asSelectable(it, bold) }
        return out
    }

    // ---- gallery mode --------------------------------------------------------------------------------------------

    private fun buildGallery(c: DrawController) {
        val pages = c.galleryPages
        row(
            button(str(R.string.draw_gallery_back), str(R.string.draw_gallery_back), sp = 13f) { c.closeGallery() } to 1.4f,
            button(str(R.string.draw_gallery_new), str(R.string.draw_gallery_new), PadStyle.ACCENT, sp = 13f) { c.newDrawing() } to 1.4f,
            button("‹", str(R.string.draw_gallery_previous), sp = 20f) { c.previousPage() }.also { it.alpha = if (c.galleryPage > 0) 1f else 0.4f } to 0.7f,
            button("›", str(R.string.draw_gallery_next), sp = 20f) { c.nextPage() }.also { it.alpha = if (c.galleryPage < pages - 1) 1f else 0.4f } to 0.7f,
            stretch = false,
        )
        val list = c.pageItems
        // Thumbnails of the pages left behind are freed; one page of bitmaps is alive at a time.
        val keep = list.map { it.id }.toSet()
        for (id in thumbs.keys.filter { it !in keep }) {
            thumbViews[id]?.setImageDrawable(null)
            thumbs.remove(id)?.recycle()
        }
        thumbViews.clear()
        if (c.gallery.isEmpty()) {
            column.addView(
                caption(str(R.string.draw_gallery_empty), 13f, 3),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        } else {
            val note = if (c.galleryFull) str(R.string.draw_gallery_full) else str(R.string.draw_gallery_count, c.galleryPage + 1, pages, c.gallery.size)
            column.addView(
                caption(note, 12f, 2).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        val gap = dp(GAP_DP)
        val avail = (if (width > 0) width else resources.displayMetrics.widthPixels) - dp(16f)
        val cell = (avail - gap * (COLUMNS - 1)) / COLUMNS
        val cellH = (cell * 0.75f).toInt()
        list.chunked(COLUMNS).forEachIndexed { rowIndex, chunk ->
            val r = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEachIndexed { i, item ->
                val number = c.galleryPage * DrawController.GALLERY_PAGE + rowIndex * COLUMNS + i + 1
                val selected = c.selectedId == item.id
                val iv = track(
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
                        minimumHeight = dp(MIN_TARGET_DP)
                        minimumWidth = dp(MIN_TARGET_DP)
                        isClickable = true
                        isFocusable = true
                        contentDescription = str(R.string.draw_gallery_item, number, c.gallery.size)
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
        val status = caption(statusLabel(c), 12f, 2).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
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
            // A renderer of its own per job: its laid-out texts are not shared with the main thread's.
            val renderer = DrawingRenderer()
            val bmp = try {
                renderer.thumbnail(drawing, px)
            } finally {
                renderer.release()
            }
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

    /** The status line: the last action's message, else the clear confirmation, else a hint for the tool in hand. */
    private fun statusLabel(c: DrawController): String {
        val st = c.status
        return when {
            c.clearArmed -> str(R.string.draw_clear_confirm)
            st == DrawStatus.GALLERY_FULL -> str(R.string.draw_status_gallery_full, DrawingStore.MAX_DRAWINGS)
            st != null -> str(
                when (st) {
                    DrawStatus.SAVED -> R.string.draw_status_saved
                    DrawStatus.SAVE_FAILED -> R.string.draw_status_save_failed
                    DrawStatus.NOTHING_TO_SEND -> R.string.draw_status_nothing
                    DrawStatus.INSERTED -> R.string.draw_status_inserted
                    DrawStatus.SHARED -> R.string.draw_status_shared
                    DrawStatus.SEND_FAILED -> R.string.draw_status_failed
                    DrawStatus.DELETED -> R.string.draw_status_deleted
                    DrawStatus.DUPLICATED -> R.string.draw_status_duplicated
                    DrawStatus.OPEN_FAILED -> R.string.draw_status_open_failed
                    DrawStatus.GALLERY_FULL -> R.string.draw_status_gallery_full
                    DrawStatus.OBJECT_LIMIT -> R.string.draw_status_object_limit
                },
            )
            c.mode == DrawMode.GALLERY -> ""
            c.input == DrawInput.SHAPE && !c.hasSelection -> str(R.string.draw_hint_shape)
            c.input == DrawInput.TEXT && !c.hasSelection -> str(R.string.draw_hint_text)
            c.input == DrawInput.SELECT && !c.hasSelection -> str(R.string.draw_hint_select)
            else -> ""
        }
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
            DrawBackground.BLUSH -> R.string.draw_bg_blush
            DrawBackground.LAVENDER -> R.string.draw_bg_lavender
            DrawBackground.SLATE -> R.string.draw_bg_slate
            DrawBackground.RULED -> R.string.draw_bg_ruled
            DrawBackground.DOTS -> R.string.draw_bg_dots
            else -> R.string.draw_bg_grid
        },
    )

    /** A row of equal 48dp-minimum controls that stretches to the width, or scrolls sideways when they do not fit. */
    private fun strip(views: List<View>) {
        val inner = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        views.forEachIndexed { i, v ->
            inner.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(GAP_DP) })
        }
        val scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            addView(inner, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        column.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(GAP_DP) })
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

    /** The shared 48dp icon-button shell: [paintGlyph] paints the glyph; [selected] = the accent fill; a disabled one is dimmed. */
    private fun iconControl(description: String, selected: Boolean, enabled: Boolean, paintGlyph: (View, Canvas, Paint) -> Unit, onClick: () -> Unit): View =
        track(
            object : View(context) {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                override fun onMeasure(w: Int, h: Int) {
                    setMeasuredDimension(resolveSize(dp(MIN_TARGET_DP), w), resolveSize(dp(MIN_TARGET_DP), h))
                }

                override fun onDraw(canvas: Canvas) = paintGlyph(this, canvas, paint)
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

    /** A 48dp icon button: [selected] = the accent fill; a disabled one is dimmed (it stays focusable, named, and can be read). */
    private fun glyphButton(icon: ToolIcon, description: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit): View {
        val tint = textColorOf(if (selected) PadStyle.ACCENT else PadStyle.SPECIAL)
        return iconControl(description, selected, enabled, { v, canvas, paint -> ToolIcons.draw(canvas, icon, v.width / 2f, v.height / 2f, 22f * d, tint, paint) }, onClick)
    }

    // Sample strokes for the brush buttons: a little wave, drawn by the same renderer as the canvas.
    private val sampleRenderer = StrokeRenderer()
    private val samplePoints = listOf(Pt(-14f, 5f), Pt(-8f, -5f), Pt(-1f, 5f), Pt(6f, -5f), Pt(14f, 4f))

    /** A brush choice: a sample of its own stroke in the tool's ink. */
    private fun brushButton(tool: DrawTool, brush: Brush, description: String, selected: Boolean, onClick: () -> Unit): View {
        val tint = textColorOf(if (selected) PadStyle.ACCENT else PadStyle.SPECIAL)
        val sample = Stroke(samplePoints, tint, 3.5f, tool, brush)
        return iconControl(
            description, selected, true,
            { v, canvas, _ ->
                canvas.save()
                canvas.translate(v.width / 2f, v.height / 2f)
                canvas.scale(d, d)
                sampleRenderer.drawStroke(canvas, sample)
                canvas.restore()
            },
            onClick,
        )
    }

    private val shapeBox = RectF()

    /** A shape choice: the outline of the shape. */
    private fun shapeButton(kind: ShapeKind, description: String, selected: Boolean, onClick: () -> Unit): View {
        val tint = textColorOf(if (selected) PadStyle.ACCENT else PadStyle.SPECIAL)
        return iconControl(
            description, selected, true,
            { v, canvas, paint ->
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f * d
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = tint
                val cx = v.width / 2f
                val cy = v.height / 2f
                val r = 10f * d
                when (kind) {
                    ShapeKind.LINE -> canvas.drawLine(cx - r, cy + r * 0.7f, cx + r, cy - r * 0.7f, paint)
                    ShapeKind.ARROW -> {
                        canvas.drawLine(cx - r, cy + r * 0.7f, cx + r, cy - r * 0.7f, paint)
                        canvas.drawLine(cx + r, cy - r * 0.7f, cx + r - 6f * d, cy - r * 0.7f + 1f * d, paint)
                        canvas.drawLine(cx + r, cy - r * 0.7f, cx + r - 1f * d, cy - r * 0.7f + 6f * d, paint)
                    }
                    ShapeKind.RECTANGLE -> {
                        shapeBox.set(cx - r, cy - r * 0.7f, cx + r, cy + r * 0.7f)
                        canvas.drawRect(shapeBox, paint)
                    }
                    ShapeKind.ELLIPSE -> {
                        shapeBox.set(cx - r, cy - r * 0.7f, cx + r, cy + r * 0.7f)
                        canvas.drawOval(shapeBox, paint)
                    }
                }
            },
            onClick,
        )
    }

    /** A colour or background swatch: a disc of the colour (a checkerboard hint for transparent, lines or dots for the papers). */
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
                    when (pattern) {
                        BackgroundPattern.NONE -> Unit
                        BackgroundPattern.RULED, BackgroundPattern.GRID -> {
                            canvas.drawLine(cx - r * 0.7f, cy - r * 0.3f, cx + r * 0.7f, cy - r * 0.3f, paint)
                            canvas.drawLine(cx - r * 0.7f, cy + r * 0.3f, cx + r * 0.7f, cy + r * 0.3f, paint)
                            if (pattern == BackgroundPattern.GRID) {
                                canvas.drawLine(cx - r * 0.3f, cy - r * 0.7f, cx - r * 0.3f, cy + r * 0.7f, paint)
                                canvas.drawLine(cx + r * 0.3f, cy - r * 0.7f, cx + r * 0.3f, cy + r * 0.7f, paint)
                            }
                        }
                        BackgroundPattern.DOTS -> {
                            paint.style = Paint.Style.FILL
                            for (dx in intArrayOf(-1, 1)) for (dy in intArrayOf(-1, 1)) canvas.drawCircle(cx + dx * r * 0.4f, cy + dy * r * 0.4f, 1.6f * d, paint)
                            paint.style = Paint.Style.STROKE
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
        val tint = textColorOf(if (selected) PadStyle.ACCENT else PadStyle.SPECIAL)
        return iconControl(
            str(names[index]), selected, true,
            { v, canvas, paint ->
                paint.style = Paint.Style.FILL
                paint.color = tint
                canvas.drawCircle(v.width / 2f, v.height / 2f, (units / 2f).coerceAtLeast(1.2f) * d, paint)
            },
            onClick,
        )
    }

    companion object {
        /** The canvas keeps at least this height; a shorter keyboard scrolls the panel instead. */
        const val MIN_CANVAS_DP = 112f

        /** The three control rows (48dp and a gap each), the canvas's padding and the gap under it. */
        private const val CHROME_DP = 3 * (48f + UtilityPanelView.GAP_DP) + 4f + UtilityPanelView.GAP_DP
        private const val COLUMNS = 3
        private const val THUMB_DP = 120f
    }
}
