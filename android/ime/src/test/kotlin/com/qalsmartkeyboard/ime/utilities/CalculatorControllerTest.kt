package com.qalsmartkeyboard.ime.utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T8.3: the calculator key handling over the real keyboard-core evaluator (host library through JNA). */
class CalculatorControllerTest {
    private fun CalculatorController.press(vararg keys: CalcKey) = keys.forEach { key(it) }
    private fun d(n: Int) = CalcKey.Digit(n)
    private fun op(c: Char) = CalcKey.Op(c)

    @Test fun typesEvaluatesAndContinuesFromTheResult() {
        val c = CalculatorController()
        c.press(d(2), op('+'), d(3), op('×'), d(4))
        assertEquals("2+3×4", c.expression)
        assertEquals("14", c.preview())
        assertEquals("14", c.resultText())
        c.key(CalcKey.Equals)
        assertEquals("14", c.expression)
        assertTrue(c.showingResult)
        assertNull(c.preview())
        c.press(op('÷'), d(7), CalcKey.Equals)
        assertEquals("2", c.expression)
    }

    @Test fun aDigitAfterEqualsStartsOver() {
        val c = CalculatorController()
        c.press(d(1), op('+'), d(1), CalcKey.Equals, d(5))
        assertEquals("5", c.expression)
        assertFalse(c.showingResult)
    }

    @Test fun percentFollowsPhoneCalculators() {
        val c = CalculatorController()
        c.press(d(2), d(0), d(0), op('+'), d(1), d(0), CalcKey.Percent)
        assertEquals("200+10%", c.expression)
        assertEquals("220", c.preview())
        c.key(CalcKey.Equals)
        assertEquals("220", c.expression)
        c.press(CalcKey.Clear, d(5), d(0), CalcKey.Percent)
        assertEquals("0.5", c.resultText())
    }

    @Test fun clearAndBackspace() {
        val c = CalculatorController()
        c.press(d(1), d(2), CalcKey.Backspace)
        assertEquals("1", c.expression)
        c.press(op('+'), d(2), CalcKey.Clear)
        assertEquals("", c.expression)
        c.press(d(1), op('+'), d(1), CalcKey.Equals, CalcKey.Backspace)
        assertEquals("", c.expression)
        assertFalse(c.showingResult)
        assertNull(c.resultText())
    }

    @Test fun operatorRulesAndParentheses() {
        val c = CalculatorController()
        c.press(op('×'))
        assertEquals("", c.expression) // only a minus can start
        c.press(op('−'), d(3))
        assertEquals("−3", c.expression)
        c.press(op('+'), op('×'))
        assertEquals("−3×", c.expression) // replaced
        c.press(op('−'))
        assertEquals("−3×−", c.expression) // a sign after ×
        c.press(d(2), CalcKey.Equals)
        assertEquals("6", c.expression) // minus times minus
        c.press(CalcKey.Clear, CalcKey.CloseParen)
        assertEquals("", c.expression)
        c.press(d(2), CalcKey.OpenParen, d(3), op('+'), d(1))
        assertEquals("2×(3+1", c.expression)
        c.key(CalcKey.Equals) // the open parenthesis is closed for the user
        assertEquals("8", c.expression)
    }

    @Test fun dotRules() {
        val c = CalculatorController()
        c.press(CalcKey.Dot, d(5), CalcKey.Dot)
        assertEquals("0.5", c.expression)
        c.press(op('+'), CalcKey.Dot, d(5))
        assertEquals("0.5+0.5", c.expression)
        assertEquals("1", c.resultText())
    }

    @Test fun errorsAreTypedAndClearOnTheNextKey() {
        val c = CalculatorController()
        c.press(d(1), op('÷'), d(0), CalcKey.Equals)
        assertEquals(CalcFailure.DIVISION_BY_ZERO, c.error)
        assertEquals("1÷0", c.expression)
        c.key(CalcKey.Backspace)
        assertNull(c.error)
        c.press(d(9), CalcKey.Equals) // 1 divided by 9
        assertEquals("0.111111111111", c.expression)
        c.press(CalcKey.Clear, d(1), op('+'), CalcKey.Equals)
        assertEquals(CalcFailure.INVALID, c.error)
    }

    @Test fun lengthIsCappedBelowTheCoreLimit() {
        val c = CalculatorController()
        repeat(300) { c.key(d(7)) }
        assertEquals(CalculatorController.MAX_LENGTH, c.expression.length)
    }

    @Test fun usesAnInjectedEvaluatorAndReportsChanges() {
        var changes = 0
        val c = CalculatorController(evaluator = { CalcOutcome.Ok("42") }, onChanged = { changes++ })
        c.press(d(1), CalcKey.Equals)
        assertEquals("42", c.expression)
        assertEquals(2, changes)
    }
}
