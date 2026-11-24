package com.qalsmartkeyboard.ime.utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T8.3: the converter input handling, unit cycling and the currency modes. */
class ConverterControllerTest {
    private val snap = FxSnapshot("USD", 1_700_000_000_000L, mapOf("ETB" to 130.0, "EUR" to 0.92))

    private fun ConverterController.type(s: String) {
        for (c in s) key(
            when (c) {
                '.' -> NumKey.Dot
                '-' -> NumKey.Negate
                else -> NumKey.Digit(c - '0')
            },
        )
    }

    @Test fun kmToMilesWithPhrase() {
        val c = ConverterController()
        assertEquals(ConverterResult.Empty, c.result())
        c.type("10")
        val r = c.result() as ConverterResult.Value
        assertEquals("6.213711922", r.text)
        assertEquals("10 km = 6.213711922 mi", r.phrase)
    }

    @Test fun inputEditing() {
        val c = ConverterController()
        c.type("007")
        assertEquals("7", c.input)
        c.type(".5.")
        assertEquals("7.5", c.input)
        c.key(NumKey.Negate)
        assertEquals("-7.5", c.input)
        c.key(NumKey.Negate)
        c.key(NumKey.Backspace)
        assertEquals("7.", c.input)
        assertEquals(7.0, c.amount()!!, 0.0)
        c.key(NumKey.Clear)
        assertEquals("", c.input)
        assertNull(c.amount())
        c.key(NumKey.Dot)
        assertEquals("0.", c.input)
        c.key(NumKey.Clear)
        c.key(NumKey.Negate)
        assertNull(c.amount())
        assertEquals(ConverterResult.Empty, c.result())
        c.key(NumKey.Clear)
        c.type("1".repeat(30))
        assertEquals(15, c.input.length)
    }

    @Test fun cyclingWrapsAndSkipsTheOtherUnit() {
        val c = ConverterController()
        val ids = c.unitIds()
        c.cycleFrom(1)
        assertEquals(ids[ids.indexOf("km") + 1], c.fromId)
        repeat(ids.size) { c.cycleTo(1) }
        assertTrue(c.fromId != c.toId)
        c.cycleFrom(-1)
        c.swap()
        assertTrue(c.fromId != c.toId)
    }

    @Test fun temperatureModeUsesTheAffineFormulas() {
        val c = ConverterController()
        c.selectMode(ConverterMode.TEMPERATURE)
        c.type("37")
        assertEquals("98.6", (c.result() as ConverterResult.Value).text)
        c.swap()
        c.key(NumKey.Clear)
        c.type("212")
        assertEquals("100", (c.result() as ConverterResult.Value).text)
    }

    @Test fun currencyWithoutSnapshotIsUnavailableNotAnError() {
        val c = ConverterController(fx = { null })
        c.selectMode(ConverterMode.CURRENCY)
        c.type("100")
        assertEquals(ConverterResult.RatesUnavailable, c.result())
        assertEquals(emptyList<String>(), c.unitIds())
        c.cycleFrom(1)
        c.swap()
        assertEquals(ConverterResult.RatesUnavailable, c.result())
    }

    @Test fun currencyUsesTheCachedSnapshot() {
        var s: FxSnapshot? = null
        val c = ConverterController(fx = { s })
        c.selectMode(ConverterMode.CURRENCY)
        c.type("100")
        assertEquals(ConverterResult.RatesUnavailable, c.result())
        s = snap // rates arrive after the panel opened
        val r = c.result() as ConverterResult.Value
        assertEquals("USD", c.fromId)
        assertEquals("ETB", c.toId)
        assertEquals("13000.00", r.text)
        assertEquals("100 USD = 13000.00 ETB", r.phrase)
        c.swap()
        assertEquals("0.77", (c.result() as ConverterResult.Value).text)
    }
}
