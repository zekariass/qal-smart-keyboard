package com.qalsmartkeyboard.app.billing

import com.qalsmartkeyboard.api.billing.Money
import java.math.BigDecimal
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Currency
import java.util.Date
import java.util.Locale

/**
 * Shows the server's money as is: `amountMinor` scaled by the currency's `exponent` (29900/ETB/2 is 299.00 ETB) in the
 * user's locale. Nothing is computed: no conversion, rounding, per-month figures or discounts on the client.
 */
object MoneyFormat {
    fun format(m: Money, locale: Locale = Locale.getDefault()): String {
        val amount = BigDecimal.valueOf(m.amountMinor, m.exponent)
        val currency = try {
            Currency.getInstance(m.currency)
        } catch (e: IllegalArgumentException) {
            null
        }
        if (currency == null) {
            val plain = NumberFormat.getNumberInstance(locale).apply {
                minimumFractionDigits = m.exponent
                maximumFractionDigits = m.exponent
            }
            return plain.format(amount) + " " + m.currency
        }
        val nf = NumberFormat.getCurrencyInstance(locale).apply {
            this.currency = currency
            minimumFractionDigits = m.exponent
            maximumFractionDigits = m.exponent
        }
        return nf.format(amount)
    }

    fun date(ms: Long, locale: Locale = Locale.getDefault()): String =
        DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(ms))
}
