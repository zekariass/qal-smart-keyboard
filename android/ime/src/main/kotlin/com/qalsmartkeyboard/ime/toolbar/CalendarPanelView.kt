package com.qalsmartkeyboard.ime.toolbar

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.utilities.CalendarController
import com.qalsmartkeyboard.ime.utilities.CalendarDirection
import com.qalsmartkeyboard.ime.utilities.CalendarNames
import com.qalsmartkeyboard.ime.utilities.DateField

/** Month names and era labels in the context's (UI) language. */
fun calendarNames(c: Context): CalendarNames = CalendarNames(
    ethiopianMonths = c.resources.getStringArray(R.array.cal_months_ethiopian).toList(),
    gregorianMonths = c.resources.getStringArray(R.array.cal_months_gregorian).toList(),
    ethiopianEra = c.getString(R.string.cal_era_ec),
    gregorianEra = c.getString(R.string.cal_era_gc),
)

/**
 * The calendar converter panel (T8.3), opened by the Tools grid's "Calendar" tile: Ethiopian to Gregorian and back with
 * keyboard-core's calendar. A swap row names the two calendars (the left one is the input), three steppers (day, month,
 * year; each with previous and next buttons) set the input date, the converted date shows below in the other calendar's
 * month names (the UI language's), with an Ethiopic-numerals toggle. Today sets the input to the device date; Insert date
 * commits the converted date as text to the field, Copy puts it on the clipboard.
 */
class CalendarPanelView(context: Context) : UtilityPanelView(context) {
    var controller: CalendarController = CalendarController()
        set(value) {
            field = value
            refresh()
        }

    var onInsert: (String) -> Unit = {}
    var onCopy: (String) -> Unit = {}

    private var dayValue: TextView? = null
    private var monthValue: TextView? = null
    private var yearValue: TextView? = null
    private var fromChip: TextView? = null
    private var toChip: TextView? = null
    private var resultView: TextView? = null
    private var numerals: TextView? = null
    private var names: CalendarNames = calendarNames(context)

    /** The converted date as shown (tests). */
    val resultLine: CharSequence get() = resultView?.text ?: ""
    val monthText: CharSequence get() = monthValue?.text ?: ""

    override fun build() {
        names = calendarNames(context)
        fun s(id: Int) = context.getString(id)

        // Input calendar, swap, output calendar.
        // Tapping either calendar name swaps them, like the arrow between.
        val from = button("", "", PadStyle.ACCENT, sp = 15f) { controller.swap(); refresh() }
        val to = button("", "", PadStyle.KEY, sp = 15f) { controller.swap(); refresh() }
        fromChip = from
        toChip = to
        val swap = button("⇄", s(R.string.cal_swap), PadStyle.SPECIAL) { controller.swap(); refresh() }
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

        // Steppers.
        val day = stepper(DateField.DAY, s(R.string.cal_day))
        val month = stepper(DateField.MONTH, s(R.string.cal_month))
        val year = stepper(DateField.YEAR, s(R.string.cal_year))
        dayValue = day.second
        monthValue = month.second
        yearValue = year.second
        row(day.first to 1.1f, month.first to 1.5f, year.first to 1.2f, gapDp = 6f)

        // The converted date.
        val result = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(theme.keyText)
            gravity = Gravity.CENTER
            maxLines = 2
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            minimumHeight = dp(64f)
            background = card(theme.key)
        }
        resultView = result
        column.addView(result, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4f) })

        // Actions.
        val today = button(s(R.string.cal_today), s(R.string.cal_today), sp = 14f) { controller.useToday(); refresh() }
        val num = chip(s(R.string.cal_numerals), s(R.string.cal_numerals), controller.ethiopicNumerals) {
            controller.setEthiopicNumerals(!controller.ethiopicNumerals)
            render()
        }
        numerals = num
        row(today to 1f, num to 1.5f)
        val copy = button(s(R.string.util_copy), s(R.string.util_copy), sp = 14f) { controller.resultText(names)?.let(onCopy) }
        val insert = button(s(R.string.cal_insert), s(R.string.cal_insert), PadStyle.ACCENT, sp = 14f) { controller.resultText(names)?.let(onInsert) }
        row(copy to 1f, insert to 1.5f)
        refresh()
    }

    /** A column: the caption, the value, then previous and next buttons. Returns the column and the value view. */
    private fun stepper(field: DateField, label: String): Pair<View, TextView> {
        val value = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(theme.keyText)
            gravity = Gravity.CENTER
            maxLines = 1
            minimumHeight = dp(40f)
            background = card(theme.key)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val prev = button("−", context.getString(R.string.cal_previous, label)) { controller.step(field, -1); refresh() }
        val next = button("+", context.getString(R.string.cal_next, label)) { controller.step(field, 1); refresh() }
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(caption(label, 12f, 1).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(prev, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(next, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4f) })
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4f) },
            )
        }
        return col to value
    }

    /** Updates the labels, the steppers and the converted date without rebuilding the panel. */
    fun refresh() {
        val c = controller
        val ethiopianInput = c.direction == CalendarDirection.ETHIOPIAN_TO_GREGORIAN
        val inName = context.getString(if (ethiopianInput) R.string.cal_ethiopian else R.string.cal_gregorian)
        val outName = context.getString(if (ethiopianInput) R.string.cal_gregorian else R.string.cal_ethiopian)
        fromChip?.apply { text = inName; contentDescription = context.getString(R.string.cal_date_description, inName, c.inputText(names)) }
        toChip?.apply { text = outName; contentDescription = outName }
        val months = if (ethiopianInput) names.ethiopianMonths else names.gregorianMonths
        dayValue?.apply { text = c.input.day.toString(); contentDescription = text }
        monthValue?.apply { text = months.getOrElse(c.input.month - 1) { "" }; contentDescription = text }
        yearValue?.apply { text = c.input.year.toString(); contentDescription = text }
        resultView?.apply {
            val r = c.resultText(names)
            text = r ?: context.getString(R.string.cal_out_of_range)
            contentDescription = if (r != null) context.getString(R.string.cal_date_description, outName, r) else text
        }
    }
}
