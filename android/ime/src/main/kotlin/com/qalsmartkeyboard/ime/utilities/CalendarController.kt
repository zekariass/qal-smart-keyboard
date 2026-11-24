package com.qalsmartkeyboard.ime.utilities

import java.util.Calendar
import uniffi.qal_keyboard_core.CalendarException
import uniffi.qal_keyboard_core.EthiopianDate
import uniffi.qal_keyboard_core.GregorianDate
import uniffi.qal_keyboard_core.arabicToEthiopic
import uniffi.qal_keyboard_core.ethiopianDaysInMonth
import uniffi.qal_keyboard_core.ethiopianToGregorian
import uniffi.qal_keyboard_core.gregorianToEthiopian
import uniffi.qal_keyboard_core.isGregorianLeapYear

/** A calendar date as plain numbers; the calendar it belongs to is given by context. */
data class YMD(val year: Int, val month: Int, val day: Int)

/** The input fields of the calendar converter. */
enum class DateField { YEAR, MONTH, DAY }

enum class CalendarDirection { GREGORIAN_TO_ETHIOPIAN, ETHIOPIAN_TO_GREGORIAN }

/** Month names and era suffixes in the UI language, handed in by the panel (resources) so this class stays pure. */
class CalendarNames(
    val ethiopianMonths: List<String>,
    val gregorianMonths: List<String>,
    val ethiopianEra: String,
    val gregorianEra: String,
) {
    init {
        require(ethiopianMonths.size == 13 && gregorianMonths.size == 12)
    }
}

/**
 * The Ethiopian and Gregorian calendar converter's state (no Views, JVM-tested). The conversion is keyboard-core's
 * (`gregorian_to_ethiopian`, `ethiopian_to_gregorian`); this class only holds the input date, keeps its day inside the
 * month (Pagume has 5 days, 6 in a leap year) and formats the result. [today] is the device date in the Gregorian calendar.
 */
class CalendarController(
    private val today: () -> YMD = ::deviceToday,
    private val onChanged: () -> Unit = {},
) {
    var direction: CalendarDirection = CalendarDirection.GREGORIAN_TO_ETHIOPIAN
        private set

    /** The input date, in the calendar [direction] starts from. */
    var input: YMD = today()
        private set

    /** Ethiopic numerals (፳፻፲፰) in place of Western digits in the formatted result. */
    var ethiopicNumerals: Boolean = false
        private set

    private val inputIsEthiopian get() = direction == CalendarDirection.ETHIOPIAN_TO_GREGORIAN

    /** Days in [month] of [year] in the input calendar. */
    fun daysInMonth(year: Int = input.year, month: Int = input.month): Int =
        if (inputIsEthiopian) {
            ethiopianDaysInMonth(year, month.toUInt()).toInt()
        } else {
            gregorianDays(year, month)
        }

    /** Months in the input calendar's year. */
    val monthsInYear: Int get() = if (inputIsEthiopian) 13 else 12

    /** Moves one field by [delta] (wrapping months into the year, days inside the month), then keeps the date valid. */
    fun step(field: DateField, delta: Int) {
        var (y, m, d) = input
        when (field) {
            DateField.YEAR -> y = (y + delta).coerceIn(MIN_YEAR, MAX_YEAR)
            DateField.MONTH -> m = Math.floorMod(m - 1 + delta, monthsInYear) + 1
            DateField.DAY -> {
                val n = daysInMonth(y, m)
                d = Math.floorMod(d - 1 + delta, n) + 1
            }
        }
        input = YMD(y, m, d.coerceAtMost(daysInMonth(y, m)))
        onChanged()
    }

    /** Sets a whole date (clamped to a valid one). */
    fun set(date: YMD) {
        val y = date.year.coerceIn(MIN_YEAR, MAX_YEAR)
        val m = date.month.coerceIn(1, monthsInYear)
        input = YMD(y, m, date.day.coerceIn(1, daysInMonth(y, m)))
        onChanged()
    }

    /** Today's date in the input calendar. */
    fun useToday() {
        val t = today()
        input = if (inputIsEthiopian) toEthiopian(t) ?: input else t
        onChanged()
    }

    /** Swaps direction; the result becomes the new input, so the same day stays on screen. */
    fun swap() {
        val out = result()
        direction = if (inputIsEthiopian) CalendarDirection.GREGORIAN_TO_ETHIOPIAN else CalendarDirection.ETHIOPIAN_TO_GREGORIAN
        if (out != null) input = out
        onChanged()
    }

    fun setEthiopicNumerals(on: Boolean) {
        ethiopicNumerals = on
        onChanged()
    }

    /** The converted date in the other calendar, or null when it falls outside the supported years. */
    fun result(): YMD? = if (inputIsEthiopian) toGregorian(input) else toEthiopian(input)

    /** The input date formatted in its own calendar ("11 September 2024 G.C."). */
    fun inputText(names: CalendarNames): String = format(input, inputIsEthiopian, names)

    /** The converted date formatted in its calendar, or null; what Insert and Copy hand over. */
    fun resultText(names: CalendarNames): String? = result()?.let { format(it, !inputIsEthiopian, names) }

    /** The weekday-free date string "day month year era", digits Ethiopic when [ethiopicNumerals]. */
    fun format(date: YMD, ethiopian: Boolean, names: CalendarNames): String {
        val month = (if (ethiopian) names.ethiopianMonths else names.gregorianMonths).getOrElse(date.month - 1) { "" }
        val era = if (ethiopian) names.ethiopianEra else names.gregorianEra
        return "${number(date.day)} $month ${number(date.year)} $era"
    }

    private fun number(n: Int): String =
        if (ethiopicNumerals && n > 0) runCatching { arabicToEthiopic(n.toULong()) }.getOrDefault(n.toString()) else n.toString()

    companion object {
        const val MIN_YEAR = 1
        const val MAX_YEAR = 9999

        fun gregorianDays(year: Int, month: Int): Int = when (month) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            2 -> if (isGregorianLeapYear(year)) 29 else 28
            else -> 0
        }

        fun toEthiopian(g: YMD): YMD? = try {
            gregorianToEthiopian(GregorianDate(g.year, g.month.toUInt(), g.day.toUInt())).let { YMD(it.year, it.month.toInt(), it.day.toInt()) }
        } catch (e: CalendarException) {
            null
        }

        fun toGregorian(e: YMD): YMD? = try {
            ethiopianToGregorian(EthiopianDate(e.year, e.month.toUInt(), e.day.toUInt())).let { YMD(it.year, it.month.toInt(), it.day.toInt()) }
        } catch (ex: CalendarException) {
            null
        }

        fun deviceToday(): YMD = Calendar.getInstance().let { YMD(it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH)) }
    }
}
