package com.qalsmartkeyboard.ime.utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.qal_keyboard_core.ethiopianMonthName

/** T8.3: the Ethiopian and Gregorian converter over keyboard-core's calendar. */
class CalendarControllerTest {
    private val names = CalendarNames(
        ethiopianMonths = listOf("Meskerem", "Tikimt", "Hidar", "Tahsas", "Tir", "Yekatit", "Megabit", "Miazia", "Ginbot", "Sene", "Hamle", "Nehase", "Pagume"),
        gregorianMonths = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"),
        ethiopianEra = "E.C.",
        gregorianEra = "G.C.",
    )

    private fun controller(today: YMD = YMD(2024, 9, 11)) = CalendarController(today = { today })

    @Test fun gregorianToEthiopian() {
        val c = controller()
        assertEquals(YMD(2024, 9, 11), c.input)
        assertEquals(YMD(2017, 1, 1), c.result()) // Enkutatash 2017
        assertEquals("1 Meskerem 2017 E.C.", c.resultText(names))
        assertEquals("11 September 2024 G.C.", c.inputText(names))
        c.set(YMD(2025, 1, 7)) // Christmas, Genna
        assertEquals(YMD(2017, 4, 29), c.result())
    }

    @Test fun ethiopianToGregorianIncludingLeapPagume() {
        val c = controller()
        c.swap() // the result becomes the input: 1 Meskerem 2017
        assertEquals(CalendarDirection.ETHIOPIAN_TO_GREGORIAN, c.direction)
        assertEquals(YMD(2017, 1, 1), c.input)
        assertEquals(YMD(2024, 9, 11), c.result())
        // 2015 is an Ethiopian leap year: Pagume has 6 days.
        c.set(YMD(2015, 13, 6))
        assertEquals(6, c.daysInMonth())
        assertEquals(YMD(2023, 9, 11), c.result())
        assertEquals("11 September 2023 G.C.", c.resultText(names))
        // 2016 is not: Pagume 6 clamps to 5 when the year steps.
        c.step(DateField.YEAR, 1)
        assertEquals(YMD(2016, 13, 5), c.input)
        assertEquals(YMD(2024, 9, 10), c.result())
        // An invalid Pagume 6 is clamped by set().
        c.set(YMD(2016, 13, 6))
        assertEquals(5, c.input.day)
    }

    @Test fun monthAndDayStepsWrapAndClamp() {
        val c = controller(YMD(2024, 1, 31))
        c.step(DateField.MONTH, 1)
        assertEquals(YMD(2024, 2, 29), c.input) // leap February
        c.step(DateField.MONTH, 11)
        assertEquals(YMD(2024, 1, 29), c.input) // wrapped
        c.step(DateField.DAY, 3)
        assertEquals(YMD(2024, 1, 1), c.input) // 29 + 3 wraps in a 31-day month
        c.step(DateField.DAY, -1)
        assertEquals(31, c.input.day)
        val e = controller()
        e.swap()
        e.set(YMD(2017, 12, 30))
        e.step(DateField.MONTH, 1)
        assertEquals(YMD(2017, 13, 5), e.input) // Pagume 2017: 2017 % 4 == 1, five days
        e.step(DateField.MONTH, 1)
        assertEquals(YMD(2017, 1, 5), e.input)
    }

    @Test fun todayInEitherCalendar() {
        val c = controller()
        c.set(YMD(2000, 1, 1))
        c.useToday()
        assertEquals(YMD(2024, 9, 11), c.input)
        c.swap()
        c.set(YMD(2000, 1, 1))
        c.useToday()
        assertEquals(YMD(2017, 1, 1), c.input)
    }

    @Test fun ethiopicNumeralsFormatDayAndYear() {
        val c = controller()
        c.setEthiopicNumerals(true)
        assertEquals("፩ Meskerem ፳፻፲፯ E.C.", c.resultText(names))
        assertEquals("፲፩ September ፳፻፳፬ G.C.", c.inputText(names))
        c.setEthiopicNumerals(false)
        assertEquals("1 Meskerem 2017 E.C.", c.resultText(names))
    }

    @Test fun outOfRangeResultIsNull() {
        val c = controller(YMD(1, 1, 1))
        assertNull(c.resultText(names))
        assertNull(c.result())
    }

    @Test fun roundTripEveryDayOfAYear() {
        var seen = 0
        for (m in 1..12) for (d in 1..CalendarController.gregorianDays(2023, m)) {
            val e = CalendarController.toEthiopian(YMD(2023, m, d))!!
            assertEquals(YMD(2023, m, d), CalendarController.toGregorian(e))
            seen++
        }
        assertEquals(365, seen)
    }

    @Test fun coreNamesAreAmharic() {
        assertEquals("መስከረም", ethiopianMonthName(1u))
        assertEquals("ጳጉሜን", ethiopianMonthName(13u))
    }
}
