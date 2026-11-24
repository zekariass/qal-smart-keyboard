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
 * The calculator panel (T8.3), opened by the Tools grid's "Calculator" tile: a 5-column pad in place of the keys, its display (the
 * expression and the live result or the reason `=` failed, on one line) in the panel bar above ([displayInBar]). Insert commits the result to the field,
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
    private var shownExpression: CharSequence = ""
    private var shownResult: CharSequence = ""

    /**
     * True when the host shows the expression and result in its panel bar ([onDisplay]), so the pad takes the whole key area:
     * the five rows then fit the keyboard's height at 48dp with 4dp gaps. False (a host without a bar): a one-line display
     * is built above the pad and the column scrolls if the height is short.
     */
    var displayInBar: Boolean = false

    /** The display changed: its one-line text, what TalkBack says, and whether it is an error. Called with [displayInBar]. */
    var onDisplay: (text: String, description: String?, warn: Boolean) -> Unit = { _, _, _ -> }

    /** The text of the expression line (tests). */
    val expressionText: CharSequence get() = shownExpression

    /** The text of the result / error line (tests). */
    val resultLine: CharSequence get() = shownResult

    override fun build() {
        expressionView = null
        resultView = null
        if (!displayInBar) {
            val expr = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(theme.keyText)
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                maxLines = 1
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
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14f), dp(2f), dp(14f), dp(2f))
                background = card(theme.key)
                minimumHeight = dp(44f)
                addView(expr, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(res, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8f) })
            }
            column.addView(display, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4f) })
        }

        fun s(id: Int) = context.getString(id)
        fun digit(n: Int) = button(n.toString(), n.toString()) { controller.key(CalcKey.Digit(n)) }
        fun op(sym: Char, id: Int) = button(sym.toString(), s(id), PadStyle.SPECIAL) { controller.key(CalcKey.Op(sym)) }
        fun cells(vararg v: View) = v.map { it to 1f }.toTypedArray()

        val clear = button("C", s(R.string.calc_key_clear), PadStyle.SPECIAL) { controller.key(CalcKey.Clear) }
        val open = button("(", s(R.string.calc_key_open), PadStyle.SPECIAL) { controller.key(CalcKey.OpenParen) }
        val close = button(")", s(R.string.calc_key_close), PadStyle.SPECIAL) { controller.key(CalcKey.CloseParen) }
        val pct = button("%", s(R.string.calc_key_percent), PadStyle.SPECIAL) { controller.key(CalcKey.Percent) }
        val back = button("⌫", s(R.string.calc_key_backspace), PadStyle.SPECIAL) { controller.key(CalcKey.Backspace) }
        // Four rows of six (device test 2026-11-24: the key area is five keyboard rows, ~251dp on a 2.625-density phone, so
        // five 48dp pad rows plus gaps did not fit and the last row scrolled; four rows fit with room to spare).
        val copy = button(s(R.string.util_copy), s(R.string.util_copy), sp = 13f) { controller.resultText()?.let(onCopy) }
        val insert = button(s(R.string.util_insert), s(R.string.util_insert), PadStyle.ACCENT, sp = 13f) { controller.resultText()?.let(onInsert) }
        val dot = button(".", s(R.string.calc_key_dot)) { controller.key(CalcKey.Dot) }
        val eq = button("=", s(R.string.calc_key_equals), PadStyle.ACCENT, sp = 22f) { controller.key(CalcKey.Equals) }
        row(*cells(digit(7), digit(8), digit(9), op('÷', R.string.calc_key_divide), clear, back))
        row(*cells(digit(4), digit(5), digit(6), op('×', R.string.calc_key_multiply), open, close))
        row(*cells(digit(1), digit(2), digit(3), op('−', R.string.calc_key_minus), pct, op('^', R.string.calc_key_power)))
        row(*cells(digit(0), dot, eq, op('+', R.string.calc_key_plus), copy, insert))
        refresh()
    }

    /** Updates the display from the controller without rebuilding the pad. */
    fun refresh() {
        val c = controller
        val exprText = c.expression.ifEmpty { "0" }
        val exprDescription = if (c.expression.isEmpty()) {
            context.getString(R.string.calc_empty_description)
        } else {
            context.getString(R.string.calc_expression_description, spoken(c.expression))
        }
        val err = c.error
        val resultText: String
        val resultDescription: String?
        if (err != null) {
            resultText = context.getString(errorText(err))
            resultDescription = resultText
        } else {
            val preview = c.preview()
            resultText = if (preview != null) "= ${preview.replace('-', '−')}" else ""
            resultDescription = if (preview != null) context.getString(R.string.calc_result_description, preview) else null
        }
        shownExpression = exprText
        shownResult = resultText
        expressionView?.let { it.text = exprText; it.contentDescription = exprDescription }
        resultView?.let {
            it.text = resultText
            it.setTextColor(if (err != null) theme.warn else theme.hintText)
            it.contentDescription = resultDescription
        }
        if (displayInBar) {
            val line = if (err != null) resultText else if (resultText.isEmpty()) exprText else "$exprText  $resultText"
            val spokenLine = listOfNotNull(exprDescription, resultDescription).joinToString(". ")
            onDisplay(line, spokenLine, err != null)
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
