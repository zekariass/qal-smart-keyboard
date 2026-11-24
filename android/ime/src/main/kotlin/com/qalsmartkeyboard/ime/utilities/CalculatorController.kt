package com.qalsmartkeyboard.ime.utilities

import uniffi.qal_keyboard_core.CalcException
import uniffi.qal_keyboard_core.calcEval

/** Why the calculator could not give a result (the panel maps each to a short localised line). */
enum class CalcFailure { INCOMPLETE, DIVISION_BY_ZERO, TOO_LARGE, INVALID, TOO_LONG }

/** One evaluation outcome; the default evaluator is keyboard-core's `calc_eval` (no text is kept or logged). */
sealed interface CalcOutcome {
    data class Ok(val text: String) : CalcOutcome
    data class Failed(val why: CalcFailure) : CalcOutcome
}

fun interface CalcEvaluator {
    fun eval(expression: String): CalcOutcome

    companion object {
        /** keyboard-core's safe evaluator. */
        val Core = CalcEvaluator { expr ->
            try {
                CalcOutcome.Ok(calcEval(expr))
            } catch (e: CalcException) {
                CalcOutcome.Failed(
                    when (e) {
                        is CalcException.Empty -> CalcFailure.INCOMPLETE
                        is CalcException.Syntax -> CalcFailure.INVALID
                        is CalcException.DivisionByZero -> CalcFailure.DIVISION_BY_ZERO
                        is CalcException.Overflow -> CalcFailure.TOO_LARGE
                        is CalcException.Domain -> CalcFailure.INVALID
                        is CalcException.TooLong, is CalcException.TooDeep -> CalcFailure.TOO_LONG
                    },
                )
            }
        }
    }
}

/** A key of the calculator pad. */
sealed interface CalcKey {
    data class Digit(val d: Int) : CalcKey
    data object Dot : CalcKey

    /** One of `+ − × ÷ ^`. */
    data class Op(val symbol: Char) : CalcKey
    data object OpenParen : CalcKey
    data object CloseParen : CalcKey
    data object Percent : CalcKey
    data object Backspace : CalcKey
    data object Clear : CalcKey
    data object Equals : CalcKey
}

/**
 * The quick calculator's state (no Views, JVM-tested). The expression is edited key by key; a live [preview] shows the
 * result while the expression is complete; `=` replaces the expression with its result (a digit then starts a new
 * expression, an operator continues from the result). The evaluator is keyboard-core's, so percent, exponents and
 * precision behave as documented there (`200 + 10%` is 220). Nothing is stored or logged.
 */
class CalculatorController(
    private val evaluator: CalcEvaluator = CalcEvaluator.Core,
    private val onChanged: () -> Unit = {},
) {
    var expression: String = ""
        private set

    /** True right after `=`: the expression is a finished result. */
    var showingResult: Boolean = false
        private set

    /** Set by `=` when the expression could not be evaluated; cleared by the next key. */
    var error: CalcFailure? = null
        private set

    /** The result of the current expression while it is complete, else null (an empty or half-typed expression). */
    fun preview(): String? {
        if (expression.isEmpty() || showingResult) return null
        return (evaluator.eval(expression) as? CalcOutcome.Ok)?.text?.takeIf { it != expression }
    }

    /** What Insert and Copy hand over: the result of the expression ("2+2" gives "4"), or null when there is none. */
    fun resultText(): String? {
        if (expression.isEmpty()) return null
        if (showingResult) return expression
        return (evaluator.eval(expression) as? CalcOutcome.Ok)?.text
    }

    fun key(k: CalcKey) {
        error = null
        when (k) {
            is CalcKey.Digit -> {
                startFresh()
                append(k.d.toString())
            }
            CalcKey.Dot -> {
                startFresh()
                val tail = expression.takeLastWhile { it.isDigit() || it == '.' }
                if ('.' !in tail) append(if (tail.isEmpty()) "0." else ".")
            }
            is CalcKey.Op -> {
                showingResult = false
                val last = expression.lastOrNull()
                when {
                    expression.isEmpty() -> if (k.symbol == '−') append("−") // only a sign can start an expression
                    last in OPS -> {
                        // An operator after an operator replaces it (a minus after × ÷ ^ is a sign instead).
                        if (k.symbol == '−' && last != '−' && last != '+') append("−") else expression = expression.dropLast(1) + k.symbol
                    }
                    last == '(' -> if (k.symbol == '−') append("−")
                    else -> append(k.symbol.toString())
                }
            }
            CalcKey.OpenParen -> {
                startFresh()
                val last = expression.lastOrNull()
                // Implicit multiplication keeps "2(3)" meaningful.
                append(if (last != null && (last.isDigit() || last == ')' || last == '%' || last == '.')) "×(" else "(")
            }
            CalcKey.CloseParen -> {
                showingResult = false
                val open = expression.count { it == '(' } - expression.count { it == ')' }
                val last = expression.lastOrNull()
                if (open > 0 && last != null && last != '(' && last !in OPS) append(")")
            }
            CalcKey.Percent -> {
                showingResult = false
                val last = expression.lastOrNull()
                if (last != null && (last.isDigit() || last == ')' || last == '%')) append("%")
            }
            CalcKey.Backspace -> {
                if (showingResult) {
                    expression = ""
                    showingResult = false
                } else {
                    expression = expression.dropLast(1)
                }
            }
            CalcKey.Clear -> {
                expression = ""
                showingResult = false
            }
            CalcKey.Equals -> equals()
        }
        onChanged()
    }

    private fun equals() {
        if (expression.isEmpty() || showingResult) return
        // Close parentheses the user left open, like a phone calculator.
        val open = expression.count { it == '(' } - expression.count { it == ')' }
        val closed = expression + ")".repeat(maxOf(open, 0))
        when (val o = evaluator.eval(closed)) {
            is CalcOutcome.Ok -> {
                expression = o.text.replace('-', '−')
                showingResult = true
            }
            is CalcOutcome.Failed -> error = o.why
        }
    }

    /** A digit or "(" typed after a result starts a new expression. */
    private fun startFresh() {
        if (showingResult) {
            expression = ""
            showingResult = false
        }
    }

    private fun append(s: String) {
        if (expression.length + s.length <= MAX_LENGTH) expression += s
    }

    companion object {
        /** Kept below keyboard-core's 256-character limit. */
        const val MAX_LENGTH = 200
        private val OPS = setOf('+', '−', '×', '÷', '^')
    }
}
