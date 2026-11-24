package com.qalsmartkeyboard.ime.utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T8.3: the unit converter table and affine temperature conversion. */
class UnitConverterTest {
    private fun conv(v: Double, from: String, to: String) = UnitConverter.convert(v, UnitConverter.unit(from)!!, UnitConverter.unit(to)!!)

    @Test fun knownValues() {
        assertEquals(2.20462262185, conv(1.0, "kg", "lb"), 1e-9)
        assertEquals(1.0, conv(0.45359237, "kg", "lb"), 1e-12)
        assertEquals(0.393700787402, conv(1.0, "cm", "in"), 1e-9)
        assertEquals(2.54, conv(1.0, "in", "cm"), 1e-12)
        assertEquals(1.609344, conv(1.0, "mi", "km"), 1e-12)
        assertEquals(5280.0, conv(1.0, "mi", "ft"), 1e-9)
        assertEquals(3.0, conv(1.0, "yd", "ft"), 1e-12)
        assertEquals(16.0, conv(1.0, "lb", "oz"), 1e-12)
        assertEquals(3.785411784, conv(1.0, "gal", "l"), 1e-12)
        assertEquals(128.0, conv(1.0, "gal", "floz"), 1e-9)
        assertEquals(16.0, conv(1.0, "gal", "cup"), 1e-9)
        assertEquals(3.0, conv(1.0, "tbsp", "tsp"), 1e-9)
        assertEquals(43560.0, conv(1.0, "ac", "ft2"), 1e-6)
        assertEquals(100.0, conv(1.0, "km2", "ha"), 1e-9)
        assertEquals(62.1371192237, conv(100.0, "kmh", "mph"), 1e-8)
        assertEquals(1.0, conv(1.852, "kmh", "kn"), 1e-12)
        assertEquals(3.6, conv(1.0, "ms", "kmh"), 1e-12)
        assertEquals(1.073741824, conv(1.0, "gib", "gb"), 1e-12)
        assertEquals(1000.0, conv(1.0, "mb", "kb"), 1e-9)
        assertEquals(1024.0, conv(1.0, "mib", "kib"), 1e-9)
    }

    @Test fun temperatureIsAffine() {
        assertEquals(32.0, conv(0.0, "c", "f"), 1e-9)
        assertEquals(212.0, conv(100.0, "c", "f"), 1e-9)
        assertEquals(-40.0, conv(-40.0, "c", "f"), 1e-9)
        assertEquals(100.0, conv(212.0, "f", "c"), 1e-9)
        assertEquals(-17.7777777778, conv(0.0, "f", "c"), 1e-9)
        assertEquals(273.15, conv(0.0, "c", "k"), 1e-9)
        assertEquals(0.0, conv(273.15, "k", "c"), 1e-9)
        assertEquals(255.372222222, conv(0.0, "f", "k"), 1e-8)
        assertEquals(-459.67, conv(0.0, "k", "f"), 1e-8)
        assertEquals(98.6, conv(37.0, "c", "f"), 1e-9)
    }

    @Test fun everyUnitRoundTripsAndEveryCategoryHasUnits() {
        for (cat in UnitCategory.entries) {
            val units = UnitConverter.units(cat)
            assertTrue("$cat", units.size >= 3)
            assertEquals(units.map { it.id }.toSet().size, units.size)
            for (a in units) for (b in units) {
                val back = UnitConverter.convert(UnitConverter.convert(12.5, a, b), b, a)
                assertEquals("${a.id}->${b.id}", 12.5, back, 1e-9)
            }
            val (x, y) = UnitConverter.defaultPair(cat)
            assertTrue(x.id != y.id && x.category == cat && y.category == cat)
        }
    }

    @Test(expected = IllegalArgumentException::class) fun mixedCategoriesAreRejected() {
        conv(1.0, "kg", "m")
    }

    @Test fun formatting() {
        assertEquals("2.204622622", formatDecimal(2.20462262185))
        assertEquals("0", formatDecimal(0.0))
        assertEquals("-17.77777778", formatDecimal(-17.7777777778))
        assertEquals("1000", formatDecimal(1000.0))
        assertEquals("0.5", formatDecimal(0.5))
        assertEquals("1.5E+20", formatDecimal(1.5e20))
        assertEquals("", formatDecimal(Double.NaN))
        assertEquals("98.6", formatDecimal(98.6))
    }
}
