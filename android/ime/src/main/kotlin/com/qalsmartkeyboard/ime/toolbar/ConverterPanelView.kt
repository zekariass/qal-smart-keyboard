package com.qalsmartkeyboard.ime.toolbar

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.utilities.ConverterController
import com.qalsmartkeyboard.ime.utilities.ConverterMode
import com.qalsmartkeyboard.ime.utilities.ConverterResult
import com.qalsmartkeyboard.ime.utilities.FxSnapshotCache
import com.qalsmartkeyboard.ime.utilities.NumKey
import com.qalsmartkeyboard.ime.utilities.RatesNote
import com.qalsmartkeyboard.ime.utilities.formatDecimal

/** The converter tab's name in the UI language. */
fun converterModeLabel(c: Context, m: ConverterMode): String = c.getString(
    when (m) {
        ConverterMode.LENGTH -> R.string.conv_mode_length
        ConverterMode.MASS -> R.string.conv_mode_mass
        ConverterMode.TEMPERATURE -> R.string.conv_mode_temperature
        ConverterMode.VOLUME -> R.string.conv_mode_volume
        ConverterMode.AREA -> R.string.conv_mode_area
        ConverterMode.SPEED -> R.string.conv_mode_speed
        ConverterMode.DATA -> R.string.conv_mode_data
        ConverterMode.CURRENCY -> R.string.tool_currency
    },
)

/**
 * The unit and currency converter panel (T8.3), opened by the Tools grid's "Units" and "Currency" tiles (the second opens
 * on its Currency tab): tabs for the categories, a from / swap / to row (a tap on a unit moves to the next one, with the
 * other direction on a long press), the typed number and its conversion, a 4-column number pad with Copy and Insert.
 * Currency reads only the offline rates snapshot ([cache]); with none it says "Rates unavailable offline", and an old
 * snapshot carries "Rates as of ... may be out of date". Units and currency codes are the same in every UI language.
 */
class ConverterPanelView(context: Context) : UtilityPanelView(context) {
    var controller: ConverterController = ConverterController()
        set(value) {
            field = value
            refresh()
        }

    /** The offline rates cache for the note under the result (null in tests that do not care). */
    var cache: FxSnapshotCache? = null

    var onInsert: (String) -> Unit = {}
    var onCopy: (String) -> Unit = {}

    private var inputView: TextView? = null
    private var resultView: TextView? = null
    private var noteView: TextView? = null
    private var fromButton: TextView? = null
    private var toButton: TextView? = null

    /** What the number and result lines show (tests). */
    val inputText: CharSequence get() = inputView?.text ?: ""
    val resultLine: CharSequence get() = resultView?.text ?: ""
    val noteText: CharSequence get() = noteView?.text ?: ""

    override fun build() {
        fun s(id: Int) = context.getString(id)
        // Tabs.
        val tabs = ConverterMode.entries.map { m ->
            val label = converterModeLabel(context, m)
            chip(label, label, controller.mode == m) {
                controller.selectMode(m)
                render() // the unit row and the selected tab change
            }
        }
        chipStrip(tabs)

        // From, swap, to.
        val from = button("", "", sp = 16f) { controller.cycleFrom(1); refresh() }
        val to = button("", "", sp = 16f) { controller.cycleTo(1); refresh() }
        from.setOnLongClickListener { controller.cycleFrom(-1); refresh(); true }
        to.setOnLongClickListener { controller.cycleTo(-1); refresh(); true }
        fromButton = from
        toButton = to
        val swap = button("⇄", s(R.string.conv_swap), PadStyle.SPECIAL) { controller.swap(); refresh() }
        column.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(from, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(swap, LinearLayout.LayoutParams(dp(MIN_TARGET_DP + 8f), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4f); marginEnd = dp(4f) })
                addView(to, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4f) },
        )

        // The typed number and its conversion.
        val input = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(theme.keyText)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            maxLines = 1
            textDirection = View.TEXT_DIRECTION_LTR
        }
        val result = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(theme.hintText)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            maxLines = 2
            textDirection = View.TEXT_DIRECTION_LTR
        }
        inputView = input
        resultView = result
        val display = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(6f), dp(14f), dp(6f))
            background = card(theme.key)
            minimumHeight = dp(72f)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(result, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        column.addView(display, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(2f) })
        val note = caption("", 12f, 2).apply { gravity = Gravity.END }
        noteView = note
        column.addView(note, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(2f) })

        // Pad.
        fun digit(n: Int) = button(n.toString(), n.toString()) { controller.key(NumKey.Digit(n)); refresh() }
        fun cells(vararg v: View) = v.map { it to 1f }.toTypedArray()
        val back = button("⌫", s(R.string.calc_key_backspace), PadStyle.SPECIAL) { controller.key(NumKey.Backspace); refresh() }
        val clear = button("C", s(R.string.calc_key_clear), PadStyle.SPECIAL) { controller.key(NumKey.Clear); refresh() }
        val neg = button("±", s(R.string.conv_key_negate), PadStyle.SPECIAL) { controller.key(NumKey.Negate); refresh() }
        val dot = button(".", s(R.string.calc_key_dot)) { controller.key(NumKey.Dot); refresh() }
        val copy = button(s(R.string.util_copy), s(R.string.util_copy), sp = 13f) { phrase()?.let(onCopy) }
        val insert = button(s(R.string.util_insert), s(R.string.util_insert), PadStyle.ACCENT, sp = 13f) { phrase()?.let(onInsert) }
        row(*cells(digit(7), digit(8), digit(9), back))
        row(*cells(digit(4), digit(5), digit(6), clear))
        row(*cells(digit(1), digit(2), digit(3), neg))
        row(*cells(digit(0), dot, copy, insert))
        refresh()
    }

    private fun phrase(): String? = (controller.result() as? ConverterResult.Value)?.phrase

    /** Updates the unit buttons, the number, the result and the rates note without rebuilding the pad. */
    fun refresh() {
        val c = controller
        val r = c.result() // currency may pick its pair here, so read the units after it
        fromButton?.let { b ->
            val sym = c.symbol(c.fromId)
            b.text = sym
            b.contentDescription = context.getString(R.string.conv_from, sym)
            b.isEnabled = c.fromId.isNotEmpty()
        }
        toButton?.let { b ->
            val sym = c.symbol(c.toId)
            b.text = sym
            b.contentDescription = context.getString(R.string.conv_to, sym)
            b.isEnabled = c.toId.isNotEmpty()
        }
        val a = c.amount()
        inputView?.text = if (c.input.isEmpty()) "0 ${c.symbol(c.fromId)}".trim() else "${c.input} ${c.symbol(c.fromId)}".trim()
        resultView?.let {
            when (r) {
                ConverterResult.Empty -> {
                    it.text = context.getString(R.string.conv_type_number)
                    it.setTextColor(theme.hintText)
                }
                ConverterResult.RatesUnavailable -> {
                    it.text = context.getString(R.string.conv_rates_unavailable)
                    it.setTextColor(theme.warn)
                }
                is ConverterResult.Value -> {
                    it.text = "= ${r.text} ${c.symbol(c.toId)}"
                    it.setTextColor(theme.keyText)
                }
            }
            it.contentDescription = if (r is ConverterResult.Value) r.phrase else it.text
        }
        inputView?.contentDescription = a?.let { formatDecimal(it) + " " + c.symbol(c.fromId) }
        noteView?.let { n ->
            n.text = when (val note = c.ratesNote(cache)) {
                null -> ""
                RatesNote.Unavailable -> "" // the result line already says it
                is RatesNote.AsOf -> context.getString(if (note.stale) R.string.conv_rates_stale else R.string.conv_rates_as_of, note.asOf)
            }
            n.visibility = if (n.text.isEmpty()) View.GONE else View.VISIBLE
        }
    }
}
