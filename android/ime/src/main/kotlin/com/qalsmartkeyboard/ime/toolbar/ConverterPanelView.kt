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
 * Currency reads only the cached rates snapshot ([cache], refreshed in the background by the host through
 * [onCurrencyOpened]); with none it says "Rates unavailable offline", a snapshot carries "Rates as of <date>", and a stale one
 * (the server says so, or it is over a day old) "... may be out of date". Units and currency codes are the same in every UI language.
 */
class ConverterPanelView(context: Context) : UtilityPanelView(context) {
    var controller: ConverterController = ConverterController()
        set(value) {
            field = value
            refresh()
        }

    /** The offline rates cache for the note under the result (null in tests that do not care). */
    var cache: FxSnapshotCache? = null

    /** The Currency tab was built (the panel opened on it, or its tab was tapped): the host may refresh the rates in the background. */
    var onCurrencyOpened: () -> Unit = {}

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

        // Two panes under the tabs (about 220dp tall): a 3-column digit pad on the left, on the right the unit row, the number
        // and its conversion (with the rates note) and the actions, so nothing is below the fold at the default height.
        val from = button("", "", sp = 16f) { controller.cycleFrom(1); refresh() }
        val to = button("", "", sp = 16f) { controller.cycleTo(1); refresh() }
        from.setOnLongClickListener { controller.cycleFrom(-1); refresh(); true }
        to.setOnLongClickListener { controller.cycleTo(-1); refresh(); true }
        fromButton = from
        toButton = to
        val swap = button("⇄", s(R.string.conv_swap), PadStyle.SPECIAL) { controller.swap(); refresh() }
        val units = hbox(from to 1f, swap to 0f, to to 1f).also {
            (swap.layoutParams as LinearLayout.LayoutParams).apply { width = dp(MIN_TARGET_DP); weight = 0f }
        }

        // The typed number and its conversion.
        val input = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(theme.keyText)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.START
            textDirection = View.TEXT_DIRECTION_LTR
        }
        val result = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(theme.hintText)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            textDirection = View.TEXT_DIRECTION_LTR
        }
        val note = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(theme.hintText)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        inputView = input
        resultView = result
        noteView = note
        val display = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            background = card(theme.key)
            minimumHeight = dp(MIN_TARGET_DP)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(result, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(note, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        fun digit(n: Int) = button(n.toString(), n.toString()) { controller.key(NumKey.Digit(n)); refresh() }
        val back = button("⌫", s(R.string.calc_key_backspace), PadStyle.SPECIAL) { controller.key(NumKey.Backspace); refresh() }
        val clear = button("C", s(R.string.calc_key_clear), PadStyle.SPECIAL) { controller.key(NumKey.Clear); refresh() }
        val neg = button("±", s(R.string.conv_key_negate), PadStyle.SPECIAL) { controller.key(NumKey.Negate); refresh() }
        val dot = button(".", s(R.string.calc_key_dot)) { controller.key(NumKey.Dot); refresh() }
        val copy = button(s(R.string.util_copy), s(R.string.util_copy), sp = 13f) { phrase()?.let(onCopy) }
        val insert = button(s(R.string.util_insert), s(R.string.util_insert), PadStyle.ACCENT, sp = 13f) { phrase()?.let(onInsert) }
        val pad = stack(
            hbox(digit(7) to 1f, digit(8) to 1f, digit(9) to 1f) to 1f,
            hbox(digit(4) to 1f, digit(5) to 1f, digit(6) to 1f) to 1f,
            hbox(digit(1) to 1f, digit(2) to 1f, digit(3) to 1f) to 1f,
            hbox(digit(0) to 1f, dot to 1f, neg to 1f) to 1f,
        )
        val side = stack(
            units to 1f,
            display to 1.3f,
            hbox(back to 1f, clear to 1f) to 1f,
            hbox(copy to 1f, insert to 1f) to 1f,
        )
        fill(hbox(pad to 0.46f, side to 0.54f, gapDp = 6f))
        refresh()
        if (controller.mode == ConverterMode.CURRENCY) onCurrencyOpened()
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
