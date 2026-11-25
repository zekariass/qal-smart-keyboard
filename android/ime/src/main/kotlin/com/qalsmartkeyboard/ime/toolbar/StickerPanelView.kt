package com.qalsmartkeyboard.ime.toolbar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.sticker.PackAccess
import com.qalsmartkeyboard.ime.sticker.StickerColours
import com.qalsmartkeyboard.ime.sticker.StickerController
import com.qalsmartkeyboard.ime.sticker.StickerField
import com.qalsmartkeyboard.ime.sticker.StickerPackId
import com.qalsmartkeyboard.ime.sticker.StickerPacks
import com.qalsmartkeyboard.ime.sticker.StickerRef
import com.qalsmartkeyboard.ime.sticker.StickerRenderer
import com.qalsmartkeyboard.ime.sticker.StickerScreen
import com.qalsmartkeyboard.ime.sticker.StickerShape
import com.qalsmartkeyboard.ime.sticker.StickerSource
import com.qalsmartkeyboard.ime.sticker.StickerSpec
import com.qalsmartkeyboard.ime.sticker.StickerStatus
import com.qalsmartkeyboard.ime.sticker.StickerTextSize
import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.min

/**
 * The Sticker maker panel (T8.7), opened by the Tools grid's "Stickers" tile, in place of the keys at the AI panels' height.
 *
 * Strip: a row with Make, "My stickers" and the three bundled packs (the premium packs show a lock and stay listed when the
 * plan does not include them), then the stickers of the open tab, 4 to a row. Tap = insert into the field (the share sheet
 * when the field does not take WebP); long press = Insert / Share / Delete (delete needs a second tap). A locked pack shows
 * its stickers dimmed with a lock and a "See plans" button; nothing about it is hidden.
 *
 * Maker: a header with Back, the three sources (Text, Emoji, Drawing) and Save; below it the live preview and the controls
 * of the source, each a 48dp-high scrolling row so nothing overflows the key area. Text and the caption are typed with the
 * keys (the service shows the typing strip above them, like the AI panels' fields). Making stickers is free and offline.
 *
 * Chrome uses the theme's tokens. The stickers are drawn by [StickerRenderer] straight onto the view canvases, so no bitmap
 * is kept for the bundled packs or the preview; the personal stickers' thumbnails are decoded small, off the main thread, and
 * all of it is freed by [release] when the panel closes (the typing budget, §17.1).
 */
class StickerPanelView(context: Context) : UtilityPanelView(context) {
    var controller: StickerController? = null

    /** Runs thumbnail decoding off the main thread (the service passes a single background thread). */
    var executor: Executor = Executor { it.run() }

    /** Posts a result back to the main thread. */
    var mainPost: (Runnable) -> Unit = { it.run() }

    /** The Drawing source's "Open Draw" (false) and "New drawing" (true): the service opens the Draw panel. */
    var onOpenDraw: (fresh: Boolean) -> Unit = {}

    private val renderer = StickerRenderer()
    private val thumbs = HashMap<String, Bitmap>()
    private val thumbViews = HashMap<String, ImageView>()
    private var thumbGeneration = 0
    private var statusView: TextView? = null

    /** Thumbnails decoded for the user's own stickers (tests read how many were shown). */
    val thumbnailCount: Int get() = thumbs.size

    /** The status line (tests). */
    val statusText: CharSequence get() = statusView?.text ?: ""

    override fun build() {
        val c = controller ?: return
        // The maker's four 48dp rows plus its header must fit the key area (~251dp): 1dp of padding, 2dp between rows.
        column.setPadding(dp(8f), dp(1f), dp(8f), dp(1f))
        if (c.screen == StickerScreen.MAKE) buildMake(c) else buildStrip(c)
    }

    /** The controller changed: rebuild the chrome, keeping the scroll position. */
    fun refresh() {
        if (controller == null || visibility == View.GONE) return
        rebuildKeepingScroll()
    }

    /** The panel closed: free the thumbnails. */
    fun release() {
        thumbGeneration++
        column.removeAllViews()
        for (v in thumbViews.values) v.setImageDrawable(null)
        thumbViews.clear()
        for (b in thumbs.values) b.recycle()
        thumbs.clear()
    }

    // ---- the strip -----------------------------------------------------------------------------------------------

    private fun buildStrip(c: StickerController) {
        val sel = c.selected
        if (sel != null) buildActions(c, sel) else buildTabs(c)
        val tab = c.tab
        thumbViews.clear()
        if (tab == null) buildMine(c) else buildPack(c, tab)
        val status = caption(statusLabel(c), 12f, 2).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        statusView = status
        column.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun buildTabs(c: StickerController) {
        val chips = ArrayList<View>()
        chips += button(str(R.string.sticker_make), str(R.string.sticker_make_desc), PadStyle.ACCENT, sp = 14f) { c.startMake() }.also {
            it.setCompoundDrawablesRelative(iconOf(ToolIcon.PLUS, textColorOf(PadStyle.ACCENT)), null, null, null)
            it.compoundDrawablePadding = dp(4f)
        }
        chips += chip(str(R.string.sticker_tab_mine), str(R.string.sticker_tab_mine), c.tab == null) { c.selectTab(null) }
        val locked = c.locked()
        for (p in StickerPacks.ALL) {
            val name = packName(p.id)
            val selected = c.tab == p.id
            val view = chip(
                name,
                if (locked) str(R.string.sticker_pack_locked_desc, name) else name,
                selected,
            ) { c.selectTab(p.id) }
            if (locked) {
                view.setCompoundDrawablesRelative(iconOf(ToolIcon.LOCK, textColorOf(if (selected) PadStyle.ACCENT else PadStyle.KEY)), null, null, null)
                view.compoundDrawablePadding = dp(4f)
            }
            chips += view
        }
        chipStrip(chips)
    }

    private fun buildActions(c: StickerController, ref: StickerRef) {
        val desc = str(R.string.sticker_insert_description)
        val title = str(R.string.sticker_share_title)
        val chips = ArrayList<View>()
        chips += button(str(R.string.sticker_insert), str(R.string.sticker_insert), PadStyle.ACCENT, sp = 14f) { c.insert(ref, desc, title) }
        chips += button(str(R.string.sticker_share), str(R.string.sticker_share), sp = 14f) { c.share(ref, title) }
        if (ref is StickerRef.Mine) {
            val armed = c.deleteArmedId == ref.id
            chips += button(
                if (armed) str(R.string.sticker_delete_confirm) else str(R.string.sticker_delete),
                if (armed) str(R.string.sticker_delete_confirm) else str(R.string.sticker_delete),
                if (armed) PadStyle.ACCENT else PadStyle.SPECIAL,
                sp = 14f,
            ) { c.requestDelete(ref.id) }
        }
        chips += button(str(R.string.sticker_done), str(R.string.sticker_done_desc), PadStyle.SPECIAL, sp = 14f) { c.clearSelection() }
        chipStrip(chips)
    }

    private fun buildMine(c: StickerController) {
        val list = c.mine
        if (list.isEmpty()) {
            column.addView(
                caption(str(R.string.sticker_empty), 13f, 3),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            return
        }
        val cell = cellPx()
        grid(list.size) { i ->
            val item = list[i]
            val ref = StickerRef.Mine(item.id)
            val selected = c.selected == ref
            val iv = track(
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
                    isClickable = true
                    isFocusable = true
                    contentDescription = str(R.string.sticker_item_desc, i + 1, list.size)
                    background = card(if (selected) theme.accent else theme.key)
                    thumbs[item.id]?.let { setImageBitmap(it) }
                    setOnClickListener { haptic(); c.insert(ref, str(R.string.sticker_insert_description), str(R.string.sticker_share_title)) }
                    setOnLongClickListener { haptic(); c.select(ref); true }
                    asSelectable(this, selected)
                },
            )
            thumbViews[item.id] = iv
            if (thumbs[item.id] == null) loadThumbnail(c, item.id, cell)
            iv
        }
    }

    private fun buildPack(c: StickerController, id: StickerPackId) {
        val pack = StickerPacks.pack(id)
        val access = c.packAccess
        val locked = !access.open
        if (locked) {
            val note = caption(accessNote(access), 13f, 3).apply { setTextColor(theme.stripText) }
            val see = button(
                str(if (access == PackAccess.SIGN_IN) R.string.sticker_sign_in else R.string.sticker_see_plans),
                str(if (access == PackAccess.SIGN_IN) R.string.sticker_sign_in_desc else R.string.sticker_see_plans_desc),
                PadStyle.ACCENT,
                sp = 14f,
            ) { c.seePlans() }
            row(note to 1.6f, see to 1f, stretch = false)
        }
        grid(pack.stickers.size) { i ->
            val s = pack.stickers[i]
            val ref = StickerRef.Pack(id, s.id)
            val selected = c.selected == ref
            track(
                SpecView(context, renderer).apply {
                    spec = s.spec
                    dimmed = locked
                    lockedBadge = locked
                    this.theme = this@StickerPanelView.theme
                    picked = selected
                    isClickable = true
                    isFocusable = true
                    contentDescription = if (locked) str(R.string.sticker_pack_item_locked_desc, s.label) else s.label
                    setOnClickListener { haptic(); c.insert(ref, str(R.string.sticker_insert_description), str(R.string.sticker_share_title)) }
                    setOnLongClickListener {
                        haptic()
                        if (locked) c.insert(ref, "") else c.select(ref)
                        true
                    }
                    asSelectable(this, selected)
                },
            )
        }
    }

    /** [count] cells of [cellPx] in rows of [COLUMNS]; [make] builds cell i. */
    private fun grid(count: Int, make: (Int) -> View) {
        val cell = cellPx()
        val gap = dp(GAP_DP * 2)
        (0 until count).chunked(COLUMNS).forEach { idx ->
            val r = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            idx.forEachIndexed { k, i ->
                r.addView(make(i), LinearLayout.LayoutParams(cell, cell).apply { if (k > 0) marginStart = gap })
            }
            column.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = gap })
        }
    }

    private fun cellPx(): Int {
        val gap = dp(GAP_DP * 2)
        val avail = (if (width > 0) width else resources.displayMetrics.widthPixels) - dp(16f)
        return max(dp(MIN_TARGET_DP), (avail - gap * (COLUMNS - 1)) / COLUMNS)
    }

    /** Decodes the thumbnail of [id] (sampled down) on the background executor and shows it if the strip is still the same one. */
    private fun loadThumbnail(c: StickerController, id: String, cellPx: Int) {
        val gen = thumbGeneration
        executor.execute {
            val file = c.store.file(id)
            val bmp = decodeSampled(file.path, cellPx) ?: return@execute
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

    // ---- the maker -----------------------------------------------------------------------------------------------

    private fun buildMake(c: StickerController) {
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(
            glyphButton(ToolIcon.BACK, str(R.string.sticker_back)) { c.backToStrip() },
            LinearLayout.LayoutParams(dp(MIN_TARGET_DP), dp(MIN_TARGET_DP)),
        )
        val sources = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            StickerSource.TEXT to R.string.sticker_source_text,
            StickerSource.EMOJI to R.string.sticker_source_emoji,
            StickerSource.DRAWING to R.string.sticker_source_drawing,
        ).forEachIndexed { i, (src, label) ->
            sources.addView(
                chip(str(label), str(label), c.source == src) { c.selectSource(src) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(if (i == 0) 2f else 4f) },
            )
        }
        header.addView(sources, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(
            button(str(R.string.sticker_save), str(R.string.sticker_save_desc), PadStyle.ACCENT, sp = 14f) { c.make() }.apply { alpha = if (c.busy) 0.5f else 1f },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(MIN_TARGET_DP)).apply { marginStart = dp(6f) },
        )
        column.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ROW_GAP_DP) })

        val body = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        // Left: the preview and the status under it.
        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val preview = SpecView(context, renderer).apply {
            spec = c.spec()
            placeholder = c.source == StickerSource.TEXT && spec == null
            this.theme = this@StickerPanelView.theme
            contentDescription = str(R.string.sticker_preview_desc)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        left.addView(preview, LinearLayout.LayoutParams(dp(PREVIEW_DP), dp(PREVIEW_DP)))
        val status = caption(statusLabel(c), 11f, 3).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        statusView = status
        left.addView(status, LinearLayout.LayoutParams(dp(PREVIEW_DP), ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(left, LinearLayout.LayoutParams(dp(PREVIEW_DP), ViewGroup.LayoutParams.WRAP_CONTENT))

        // Right: the controls of the source, one 48dp row each.
        val right = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        when (c.source) {
            StickerSource.TEXT -> {
                right.addRow(textRow(c))
                right.addRow(inkRow(c))
                right.addRow(shapeRow(c))
            }
            StickerSource.EMOJI -> {
                right.addRow(emojiRow(c))
                right.addRow(captionRow(c))
                right.addRow(inkRow(c))
                right.addRow(shapeRow(c))
            }
            StickerSource.DRAWING -> {
                right.addRow(caption(str(if (c.hasDrawing) R.string.sticker_drawing_ready else R.string.sticker_drawing_none), 13f, 2).apply { gravity = Gravity.CENTER_VERTICAL })
                right.addRow(
                    scrolling(
                        listOf(
                            button(str(R.string.sticker_open_draw), str(R.string.sticker_open_draw_desc), PadStyle.KEY, sp = 14f) { onOpenDraw(false) },
                            button(str(R.string.sticker_new_drawing), str(R.string.sticker_new_drawing_desc), PadStyle.KEY, sp = 14f) { onOpenDraw(true) },
                        ),
                    ),
                )
                right.addRow(
                    scrolling(
                        listOf(
                            chip(str(R.string.sticker_paper_none), str(R.string.sticker_paper_none), !c.drawingPaper) { c.setDrawingPaper(false) },
                            chip(str(R.string.sticker_paper_on), str(R.string.sticker_paper_on), c.drawingPaper) { c.setDrawingPaper(true) },
                        ),
                    ),
                )
            }
        }
        body.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6f) })
        column.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Adds a 48dp-high row of the maker's controls under the previous one. */
    private fun LinearLayout.addRow(v: View) {
        addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(MIN_TARGET_DP)).apply { if (childCount > 0) topMargin = dp(ROW_GAP_DP) })
    }

    /** The text: a field button that opens typing, and the size button (small, medium, large). */
    private fun textRow(c: StickerController): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            fieldButton(c, StickerField.TEXT, str(R.string.sticker_text_hint), str(R.string.sticker_text_field_desc)),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
        )
        val sizeName = when (c.size) {
            StickerTextSize.SMALL -> R.string.sticker_size_small
            StickerTextSize.MEDIUM -> R.string.sticker_size_medium
            StickerTextSize.LARGE -> R.string.sticker_size_large
        }
        val letter = when (c.size) {
            StickerTextSize.SMALL -> "S"
            StickerTextSize.MEDIUM -> "M"
            StickerTextSize.LARGE -> "L"
        }
        row.addView(
            button(letter, str(R.string.sticker_size_desc, str(sizeName)), PadStyle.SPECIAL, sp = 16f) {
                c.selectSize(
                    when (c.size) {
                        StickerTextSize.SMALL -> StickerTextSize.MEDIUM
                        StickerTextSize.MEDIUM -> StickerTextSize.LARGE
                        StickerTextSize.LARGE -> StickerTextSize.SMALL
                    },
                )
            },
            LinearLayout.LayoutParams(dp(MIN_TARGET_DP), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginStart = dp(4f) },
        )
        return row
    }

    private fun captionRow(c: StickerController): View =
        fieldButton(c, StickerField.CAPTION, str(R.string.sticker_caption_hint), str(R.string.sticker_caption_field_desc))

    /** A button that shows what was typed (or a hint) and starts typing into [field] with the keys. */
    private fun fieldButton(c: StickerController, field: StickerField, hint: String, description: String): View {
        val typed = (if (field == StickerField.TEXT) c.text.text else c.caption.text).replace('\n', ' ')
        val b = button(typed.ifEmpty { hint }, str(R.string.sticker_field_spoken, description, typed), PadStyle.KEY, sp = 14f) { c.beginTyping(field) }
        b.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        if (typed.isEmpty()) b.setTextColor(theme.hintText)
        return b
    }

    private fun emojiRow(c: StickerController): View = scrolling(
        StickerController.EMOJIS.map { e ->
            val selected = c.emoji == e
            val v = track(
                TextView(context).apply {
                    text = e
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                    gravity = Gravity.CENTER
                    minimumWidth = dp(MIN_TARGET_DP)
                    minimumHeight = dp(MIN_TARGET_DP)
                    isClickable = true
                    isFocusable = true
                    contentDescription = str(R.string.sticker_emoji_desc, e)
                    background = styleBackground(selected)
                    setOnClickListener { haptic(); c.selectEmoji(e) }
                    asSelectable(this, selected)
                },
            )
            v
        },
    )

    /** The ink colours of the text and caption (the picked swatch is ringed; until one is picked the ink follows the background). */
    private fun inkRow(c: StickerController): View = scrolling(
        StickerColours.INK.mapIndexed { i, colour ->
            swatch(colour, str(R.string.sticker_ink_desc, inkName(i)), selected = c.ink == colour) { c.selectInk(i) }
        },
    )

    /** The background shapes and, for a shaped sticker, its fill colours. */
    private fun shapeRow(c: StickerController): View {
        val views = ArrayList<View>()
        for (s in StickerShape.entries) {
            val name = shapeName(s)
            views += chip(name, str(R.string.sticker_shape_desc, name), c.shape == s) { c.selectShape(s) }
        }
        if (c.shape != StickerShape.NONE) {
            StickerColours.FILLS.forEachIndexed { i, colour ->
                views += swatch(colour, str(R.string.sticker_fill_desc, i + 1), selected = c.fillIndex == i) { c.selectFill(i) }
            }
        }
        return scrolling(views)
    }

    // ---- controls ------------------------------------------------------------------------------------------------

    private fun str(id: Int) = context.getString(id)
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun haptic() {
        if (haptics()) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun packName(id: StickerPackId) = str(
        when (id) {
            StickerPackId.GREETINGS -> R.string.sticker_pack_greetings
            StickerPackId.CELEBRATIONS -> R.string.sticker_pack_celebrations
            StickerPackId.REACTIONS -> R.string.sticker_pack_reactions
        },
    )

    private fun shapeName(s: StickerShape) = str(
        when (s) {
            StickerShape.NONE -> R.string.sticker_shape_none
            StickerShape.CIRCLE -> R.string.sticker_shape_circle
            StickerShape.ROUNDED -> R.string.sticker_shape_rounded
            StickerShape.BURST -> R.string.sticker_shape_badge
        },
    )

    private fun inkName(i: Int) = str(
        listOf(
            R.string.draw_colour_black, R.string.draw_colour_white, R.string.draw_colour_red, R.string.draw_colour_orange,
            R.string.draw_colour_yellow, R.string.draw_colour_green, R.string.draw_colour_teal, R.string.draw_colour_blue,
            R.string.draw_colour_purple, R.string.draw_colour_brown,
        )[i],
    )

    private fun accessNote(a: PackAccess) = str(
        when (a) {
            PackAccess.SIGN_IN -> R.string.sticker_locked_sign_in
            PackAccess.UNKNOWN -> R.string.sticker_locked_unknown
            else -> R.string.sticker_locked_plan
        },
    )

    private fun statusLabel(c: StickerController): String {
        val s = c.status
        if (c.screen == StickerScreen.STRIP && c.selected == null && s == null) {
            return if (c.tab == null && c.mine.size >= c.store.max) str(R.string.sticker_status_full, c.store.max) else ""
        }
        return when (s) {
            null -> if (c.deleteArmedId != null) str(R.string.sticker_delete_confirm) else ""
            StickerStatus.SAVED -> str(R.string.sticker_status_saved)
            StickerStatus.SAVE_FAILED -> str(R.string.sticker_status_save_failed)
            StickerStatus.FULL -> str(R.string.sticker_status_full, c.store.max)
            StickerStatus.NOTHING_TO_MAKE -> str(R.string.sticker_status_nothing)
            StickerStatus.NO_DRAWING -> str(R.string.sticker_status_no_drawing)
            StickerStatus.INSERTED -> str(R.string.sticker_status_inserted)
            StickerStatus.SHARED -> str(R.string.sticker_status_shared)
            StickerStatus.SEND_FAILED -> str(R.string.sticker_status_failed)
            StickerStatus.DELETED -> str(R.string.sticker_status_deleted)
            StickerStatus.LOCKED -> str(R.string.sticker_status_locked)
            StickerStatus.WORKING -> str(R.string.sticker_status_working)
        }
    }

    private fun iconOf(icon: ToolIcon, color: Int) = ToolIconDrawable(icon, color, dp(18f)).apply { setBounds(0, 0, dp(18f), dp(18f)) }

    /** A sideways-scrolling row of equal-height controls. */
    private fun scrolling(views: List<View>): View {
        val inner = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        views.forEachIndexed { i, v ->
            inner.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(MIN_TARGET_DP)).apply { if (i > 0) marginStart = dp(4f) })
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(inner, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
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

    /** A 48dp icon button. */
    private fun glyphButton(icon: ToolIcon, description: String, onClick: () -> Unit): View =
        track(
            object : View(context) {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                private val tint = textColorOf(PadStyle.SPECIAL)
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
                background = styleBackground(false)
                setOnClickListener { haptic(); onClick() }
                asSelectable(this, false)
            },
        )

    /** A colour disc, 48dp wide and high; the selected one is ringed. */
    private fun swatch(colour: Int, description: String, selected: Boolean, onClick: () -> Unit): View =
        track(
            object : View(context) {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                override fun onMeasure(w: Int, h: Int) {
                    setMeasuredDimension(dp(MIN_TARGET_DP), dp(MIN_TARGET_DP))
                }

                override fun onDraw(canvas: Canvas) {
                    val cx = width / 2f
                    val cy = height / 2f
                    val r = 13f * d
                    paint.style = Paint.Style.FILL
                    paint.color = colour
                    canvas.drawCircle(cx, cy, r, paint)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1.5f * d
                    paint.color = 0x80808080.toInt()
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

    /**
     * A sticker (or the preview) drawn straight onto the view: no bitmap is held. [dimmed] + [lockedBadge] = a locked pack's
     * sticker; [placeholder] draws a faint "Aa" where nothing was typed yet.
     */
    private class SpecView(context: Context, private val renderer: StickerRenderer) : View(context) {
        var spec: StickerSpec? = null
        var dimmed = false
        var lockedBadge = false
        var placeholder = false
        var picked = false
        var theme = com.qalsmartkeyboard.ime.theme.ThemeCatalog.classic(true)
        private val d = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = theme.cornerRadiusDp.coerceAtLeast(6f) * d
            paint.style = Paint.Style.FILL
            paint.color = if (picked) theme.accent else theme.key
            canvas.drawRoundRect(0f, 0f, w, h, r, r, paint)
            val pad = 5f * d
            val side = min(w, h) - 2 * pad
            val sp = spec
            if (sp != null) {
                val save = if (dimmed) canvas.saveLayerAlpha(0f, 0f, w, h, 110) else canvas.save()
                canvas.translate((w - side) / 2f, (h - side) / 2f)
                renderer.draw(canvas, sp, side)
                canvas.restoreToCount(save)
            } else if (placeholder) {
                paint.color = theme.hintText
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = side * 0.4f
                paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
                canvas.drawText("Aa", w / 2f, h / 2f + paint.textSize * 0.35f, paint)
            }
            if (lockedBadge) {
                val b = 12f * d
                val bx = w - b * 0.9f
                val by = h - b * 0.9f
                paint.color = theme.special
                canvas.drawCircle(bx, by, b * 0.75f, paint)
                ToolIcons.draw(canvas, ToolIcon.LOCK, bx, by, b, theme.specialText, iconPaint, strokeScale = 1.4f)
            }
        }
    }

    companion object {
        const val COLUMNS = 4
        private const val PREVIEW_DP = 112f
        private const val ROW_GAP_DP = 2f

        /** Decodes [path] at the smallest power-of-two sampling that is still at least [targetPx] wide; null when unreadable. */
        fun decodeSampled(path: String, targetPx: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetPx) sample *= 2
            return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
