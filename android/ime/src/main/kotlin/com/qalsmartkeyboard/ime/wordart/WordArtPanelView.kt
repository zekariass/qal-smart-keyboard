package com.qalsmartkeyboard.ime.wordart

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.qalsmartkeyboard.ime.KeyboardController
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.theme.KeyboardTheme
import com.qalsmartkeyboard.ime.theme.ThemeCatalog

/**
 * The Word Art panel (ADR 0030): replaces the key area at the same height, like the emoji panel it is a tab of.
 *
 * Top: ABC (back to the keys), the text field (a tap brings the keys back to type into it) and the Greeting switch.
 * Then the category chips, and the list of style previews (a plain [ListView]: rows are built only when scrolled to, and
 * recycled). A tap on a style opens a large preview card with Insert and Copy. Built from ordinary views so TalkBack,
 * font scaling and RTL come for free; every colour is a [KeyboardTheme] token. Every preview carries a content
 * description of the plain text and the style name, because screen readers read the Unicode "bold" and "script" letters badly.
 */
class WordArtPanelView(context: Context) : LinearLayout(context) {
    var controller: KeyboardController? = null

    /** The height to take: the key area's, so the keyboard does not change size. */
    var heightProvider: () -> Int = { 0 }

    var theme: KeyboardTheme = ThemeCatalog.classic(
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES,
    )
        set(value) {
            if (field === value) return
            field = value
            signature = ""
            restyle()
            refresh()
        }

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).toInt()

    private val abc = chip("ABC").also {
        it.contentDescription = context.getString(R.string.wordart_back_to_keys)
        it.setOnClickListener { controller?.emoji?.close() }
    }
    private val textField = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        textSize = 15f
        setSingleLine()
        ellipsize = TextUtils.TruncateAt.START
        setPadding(dp(12f), 0, dp(12f), 0)
        isFocusable = true
        isClickable = true
        setOnClickListener { controller?.emoji?.startWordArtTyping() }
    }
    private val greetingChip = chip(context.getString(R.string.wordart_greeting)).also {
        it.contentDescription = context.getString(R.string.wordart_greeting_desc)
        it.setOnClickListener { controller?.emoji?.wordArt?.toggleGreeting() }
    }
    private val chipRow = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding(dp(6f), 0, dp(6f), 0) }
    private val chipScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(chipRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
    private val chips = HashMap<WordArtCategory, TextView>()

    private val list = ListView(context).apply {
        divider = ColorDrawable(Color.TRANSPARENT)
        dividerHeight = dp(6f)
        selector = ColorDrawable(Color.TRANSPARENT)
        setPadding(dp(8f), dp(2f), dp(8f), dp(2f))
        clipToPadding = false
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
    }

    // The large preview card.
    private val cardTitle = TextView(context).apply { textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
    private val cardPreview = TextView(context).apply { textSize = 24f; gravity = Gravity.CENTER; setTextIsSelectable(false) }
    private val cardScroll = ScrollView(context).apply {
        isFillViewport = true
        addView(cardPreview, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }
    private val cardNote = TextView(context).apply {
        textSize = 12f
        gravity = Gravity.CENTER
        ViewCompat.setAccessibilityLiveRegion(this, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
    }
    private val cardBack = button(context.getString(R.string.wordart_back)).also {
        it.contentDescription = context.getString(R.string.wordart_back_desc)
        it.setOnClickListener { controller?.emoji?.wordArt?.select(null) }
    }
    private val cardCopy = button(context.getString(R.string.wordart_copy)).also {
        it.setOnClickListener { act { w, s -> w.copy(s) } }
    }
    private val cardInsert = button(context.getString(R.string.wordart_insert)).also {
        it.setOnClickListener { act { w, s -> w.insert(s) } }
    }
    private val card = LinearLayout(context).apply {
        orientation = VERTICAL
        visibility = GONE
        setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
        addView(cardTitle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(cardScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(cardNote, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                addView(cardBack, LayoutParams(0, dp(44f), 1f).apply { marginEnd = dp(6f) })
                addView(cardCopy, LayoutParams(0, dp(44f), 1f).apply { marginEnd = dp(6f) })
                addView(cardInsert, LayoutParams(0, dp(44f), 1.4f))
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
    }

    private val adapter = StyleAdapter()
    private var entries: List<Entry> = emptyList()
    private var signature = ""

    private class Entry(val style: WordArtStyle, val rendered: Rendered)

    init {
        orientation = VERTICAL
        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
                addView(abc, LayoutParams(dp(52f), LayoutParams.MATCH_PARENT).apply { marginEnd = dp(6f) })
                addView(textField, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = dp(6f) })
                addView(greetingChip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
            },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(44f)),
        )
        for (c in WordArtCategory.entries) {
            val v = chip(context.getString(c.titleRes())).also { v ->
                v.setOnClickListener { controller?.emoji?.wordArt?.selectCategory(c) }
            }
            chips[c] = v
            chipRow.addView(v, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply { marginEnd = dp(5f) })
        }
        addView(chipScroll, LayoutParams(LayoutParams.MATCH_PARENT, dp(36f)))
        addView(
            FrameLayout(context).apply {
                addView(list, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            },
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
        )
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val e = entries.getOrNull(position) ?: return@setOnItemClickListener
            controller?.emoji?.wordArt?.select(e.style)
        }
        restyle()
    }

    private fun act(f: (WordArtController, WordArtStyle) -> Unit) {
        val w = controller?.emoji?.wordArt ?: return
        val s = w.selected ?: return
        f(w, s)
    }

    private fun chip(label: String) = TextView(context).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        setSingleLine()
        setPadding(dp(12f), 0, dp(12f), 0)
        minimumWidth = dp(44f)
        isClickable = true
        isFocusable = true
    }

    private fun button(label: String) = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        stateListAnimator = null
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(8f), 0, dp(8f), 0)
    }

    private fun rounded(color: Int, radiusDp: Float = theme.cornerRadiusDp + 4f, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * d
            if (stroke != 0) setStroke(dp(1.5f), stroke)
        }

    private fun restyle() {
        val t = theme
        setBackgroundColor(Color.TRANSPARENT)
        abc.background = rounded(t.special); abc.setTextColor(t.specialText)
        textField.background = rounded(t.key)
        textField.setHintTextColor(t.hintText)
        textField.setTextColor(t.keyText)
        card.background = null
        cardTitle.setTextColor(t.hintText)
        cardPreview.setTextColor(t.keyText)
        cardNote.setTextColor(t.hintText)
        for (b in listOf(cardBack, cardCopy)) { b.background = rounded(t.special); b.setTextColor(t.specialText) }
        cardInsert.background = rounded(t.accent); cardInsert.setTextColor(t.onAccent)
        paintChips()
        adapter.notifyDataSetChanged()
    }

    private fun paintChips() {
        val sel = controller?.emoji?.wordArt?.category
        for ((c, v) in chips) {
            val on = c == sel
            v.background = rounded(if (on) t().accent else t().stripBg, 18f)
            v.setTextColor(if (on) t().onAccent else t().stripText)
            v.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            v.contentDescription = if (on) context.getString(R.string.wordart_category_selected, context.getString(c.titleRes())) else context.getString(c.titleRes())
            v.isSelected = on
        }
        val on = controller?.emoji?.wordArt?.greeting == true
        greetingChip.background = rounded(if (on) t().accent else t().stripBg, 18f)
        greetingChip.setTextColor(if (on) t().onAccent else t().stripText)
        ViewCompat.setStateDescription(greetingChip, context.getString(if (on) R.string.wordart_state_on else R.string.wordart_state_off))
        greetingChip.isSelected = on
    }

    private fun t() = theme

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = heightProvider().takeIf { it > 0 } ?: dp(250f)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    /** The Word Art state changed (text, category, greeting, card, notice): redraws what differs. Cheap when nothing did. */
    fun refresh() {
        val w = controller?.emoji?.wordArt ?: return
        val sel = w.selected
        val sig = "${w.text}|${w.category}|${w.greeting}|${w.options.language}|${theme.id}"
        if (sig != signature) {
            signature = sig
            entries = w.styles().map { Entry(it, w.render(it)) }.sortedBy { if (it.rendered.support == Support.NONE) 1 else 0 }
            adapter.notifyDataSetChanged()
            list.setSelection(0)
        }
        paintChips()
        val text = w.text
        textField.text = text
        textField.hint = context.getString(R.string.wordart_field_hint)
        textField.contentDescription = if (text.isBlank()) context.getString(R.string.wordart_field_empty) else context.getString(R.string.wordart_field, text)
        card.visibility = if (sel != null) VISIBLE else GONE
        list.visibility = if (sel != null) INVISIBLE else VISIBLE
        // Hide the (hidden) list from TalkBack while the card is up, and the card while the list is.
        list.importantForAccessibility = if (sel != null) IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else IMPORTANT_FOR_ACCESSIBILITY_AUTO
        if (sel != null) showCard(w, sel)
    }

    private fun showCard(w: WordArtController, style: WordArtStyle) {
        val r = w.render(style)
        cardTitle.text = style.label
        cardPreview.text = r.text
        cardPreview.contentDescription = context.getString(R.string.wordart_preview, style.label, r.plain)
        val msg = w.notice.messageRes()?.let { context.getString(it) }
        val note = when {
            msg != null -> msg
            r.draftPhrase -> context.getString(R.string.wordart_draft_phrase)
            w.showingSample -> context.getString(R.string.wordart_sample_hint)
            else -> ""
        }
        if (cardNote.text.toString() != note) {
            cardNote.text = note
            if (msg != null) cardNote.announceForAccessibility(msg)
        }
        val usable = !w.showingSample || w.greeting
        cardInsert.isEnabled = usable
        cardCopy.isEnabled = usable
        cardInsert.alpha = if (usable) 1f else 0.5f
        cardCopy.alpha = if (usable) 1f else 0.5f
    }

    private inner class StyleAdapter : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int): Any = entries[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun isEnabled(position: Int) = entries.getOrNull(position)?.rendered?.support != Support.NONE
        override fun areAllItemsEnabled() = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? Row) ?: Row(context)
            row.bind(entries[position])
            return row
        }
    }

    private inner class Row(context: Context) : LinearLayout(context) {
        private val name = TextView(context).apply { textSize = 11f; setSingleLine(); ellipsize = TextUtils.TruncateAt.END; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        private val preview = TextView(context).apply { textSize = 17f; maxLines = 3; ellipsize = TextUtils.TruncateAt.END; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        init {
            orientation = VERTICAL
            minimumHeight = dp(52f)
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            gravity = Gravity.CENTER_VERTICAL
            addView(name, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(preview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            isFocusable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        fun bind(e: Entry) {
            val ok = e.rendered.support != Support.NONE
            val latinOnly = context.getString(R.string.wordart_latin_only)
            val reason = if (e.style.kind == StyleKind.SPACING) context.getString(R.string.wordart_unavailable) else latinOnly
            background = rounded(theme.key)
            name.setTextColor(theme.hintText)
            preview.setTextColor(theme.keyText)
            name.text = if (ok) e.style.label else "${e.style.label} · $reason"
            preview.text = if (ok) e.rendered.text else e.rendered.plain
            alpha = if (ok) 1f else 0.55f
            contentDescription = if (ok) context.getString(R.string.wordart_row, e.style.label, e.rendered.plain)
            else context.getString(R.string.wordart_row_disabled, e.style.label, e.rendered.plain, reason)
        }
    }

    init {
        contentDescription = context.getString(R.string.wordart_tab)
    }

    /** The text field, for tests. */
    internal val fieldView: TextView get() = textField
    internal val listView: ListView get() = list
    internal val cardView: View get() = card
    internal val previewView: TextView get() = cardPreview
    internal val insertButton: Button get() = cardInsert
    internal val copyButton: Button get() = cardCopy
    internal val backButton: Button get() = cardBack
    internal val greetingView: TextView get() = greetingChip
    internal val abcView: TextView get() = abc
    internal fun chipFor(c: WordArtCategory): TextView = chips.getValue(c)
}
