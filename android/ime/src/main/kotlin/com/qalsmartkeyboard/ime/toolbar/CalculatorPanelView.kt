package com.qalsmartkeyboard.ime.toolbar

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.utilities.CalcFailure
import com.qalsmartkeyboard.ime.utilities.CalcKey
import com.qalsmartkeyboard.ime.utilities.CalculatorController

/**
 * The calculator panel (T8.3), opened by the Tools grid's "Calculator" tile: a display (the expression, and under it the live
 * result or the reason `=` failed) over a 5-column pad, in place of the keys. Insert commits the result to the field,
 * Copy puts it on the clipboard. All arithmetic is keyboard-core's `calc_eval`; nothing is stored or sent.
 *
 * Pad: `C ( ) % ⌫` / `7 8 9 ÷ ^` / `4 5 6 × Copy` / `1 2 3 − Insert` / `0 . = +`. Plain Views, theme tokens only, every
 * button a 48dp Button with a spoken name.
 */
class CalculatorPanelView(context: Context) : UtilityPanelView(context) {
    var controller: CalculatorController = CalculatorController(onChanged = {})
        set(value) {
            field = value
            refresh()
        }

    /** Insert was tapped with this result. */
    var onInsert: (String) -> Unit = {}

    /** Copy was tapped with this result. */
    var onCopy: (String) -> Unit = {}

    private var expressionView: TextView? = null
    private var resultView: TextView? = null

    /** The text of the expression line (tests). */
    val expressionText: CharSequence get() = expressionView?.text ?: ""

    /** The text of the result / error line (tests). */
    val resultLine: CharSequence get() = resultView?.text ?: ""

    override fun build() {
        val expr = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(theme.keyText)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.START
            typeface = Typeface.DEFAULT
            // A number is read left to right even in an RTL UI language; the calculator pad itself is not mirrored.
            textDirection = View.TEXT_DIRECTION_LTR
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val res = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(theme.hintText)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            textDirection = View.TEXT_DIRECTION_LTR
        }
        expressionView = expr
        resultView = res
        val display = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(6f), dp(14f), dp(6f))
            background = card(theme.key)
            minimumHeight = dp(76f)
            addView(expr, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(res, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        column.addView(display, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6f) })

        fun s(id: Int) = context.getString(id)
        fun digit(n: Int) = button(n.toString(), n.toString()) { controller.key(CalcKey.Digit(n)) }
        fun op(sym: Char, id: Int) = button(sym.toString(), s(id), PadStyle.SPECIAL) { controller.key(CalcKey.Op(sym)) }
        fun cells(vararg v: View) = v.map { it to 1f }.toTypedArray()

        val clear = button("C", s(R.string.calc_key_clear), PadStyle.SPECIAL) { controller.key(CalcKey.Clear) }
        val open = button("(", s(R.string.calc_key_open), PadStyle.SPECIAL) { controller.key(CalcKey.OpenParen) }
        val close = button(")", s(R.string.calc_key_close), PadStyle.SPECIAL) { controller.key(CalcKey.CloseParen) }
        val pct = button("%", s(R.string.calc_key_percent), PadStyle.SPECIAL) { controller.key(CalcKey.Percent) }
        val back = button("⌫", s(R.string.calc_key_backspace), PadStyle.SPECIAL) { controller.key(CalcKey.Backspace) }
        row(*cells(clear, open, close, pct, back))
        row(*cells(digit(7), digit(8), digit(9), op('÷', R.string.calc_key_divide), op('^', R.string.calc_key_power)))
        val copy = button(s(R.string.util_copy), s(R.string.util_copy), sp = 13f) { controller.resultText()?.let(onCopy) }
        row(*cells(digit(4), digit(5), digit(6), op('×', R.string.calc_key_multiply), copy))
        val insert = button(s(R.string.util_insert), s(R.string.util_insert), PadStyle.ACCENT, sp = 13f) { controller.resultText()?.let(onInsert) }
        row(*cells(digit(1), digit(2), digit(3), op('−', R.string.calc_key_minus), insert))
        val dot = button(".", s(R.string.calc_key_dot)) { controller.key(CalcKey.Dot) }
        val eq = button("=", s(R.string.calc_key_equals), PadStyle.ACCENT, sp = 22f) { controller.key(CalcKey.Equals) }
        row(digit(0) to 1f, dot to 1f, eq to 2f, op('+', R.string.calc_key_plus) to 1f)
        refresh()
    }

    /** Updates the display from the controller without rebuilding the pad. */
    fun refresh() {
        val e = expressionView ?: return
        val r = resultView ?: return
        val c = controller
        e.text = c.expression.ifEmpty { "0" }
        e.contentDescription = if (c.expression.isEmpty()) {
            context.getString(R.string.calc_empty_description)
        } else {
            context.getString(R.string.calc_expression_description, spoken(c.expression))
        }
        val err = c.error
        if (err != null) {
            r.text = context.getString(errorText(err))
            r.setTextColor(theme.warn)
            r.contentDescription = r.text
        } else {
            val preview = c.preview()
            r.setTextColor(theme.hintText)
            r.text = if (preview != null) "= ${preview.replace('-', '−')}" else ""
            r.contentDescription = if (preview != null) context.getString(R.string.calc_result_description, preview) else null
        }
    }

    private fun errorText(f: CalcFailure) = when (f) {
        CalcFailure.INCOMPLETE -> R.string.calc_error_incomplete
        CalcFailure.DIVISION_BY_ZERO -> R.string.calc_error_div0
        CalcFailure.TOO_LARGE -> R.string.calc_error_large
        CalcFailure.INVALID -> R.string.calc_error_invalid
        CalcFailure.TOO_LONG -> R.string.calc_error_long
    }

    /** TalkBack reads operators by name rather than as symbols it may skip. */
    private fun spoken(expr: String): String = expr
        .replace("×", " ${context.getString(R.string.calc_key_multiply)} ")
        .replace("÷", " ${context.getString(R.string.calc_key_divide)} ")
        .replace("−", " ${context.getString(R.string.calc_key_minus)} ")
        .replace("+", " ${context.getString(R.string.calc_key_plus)} ")
        .replace("%", " ${context.getString(R.string.calc_key_percent)}")
        .replace("^", " ${context.getString(R.string.calc_key_power)} ")
        .replace("  ", " ")
        .trim()
}
