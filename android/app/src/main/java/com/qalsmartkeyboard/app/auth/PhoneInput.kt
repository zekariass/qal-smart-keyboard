package com.qalsmartkeyboard.app.auth

/** Result of normalising what the user typed into the phone field. */
sealed interface PhoneResult {
    data class Valid(val e164: String) : PhoneResult
    data object Empty : PhoneResult

    /** Not a number the contract accepts (`^\+[1-9][0-9]{6,14}$`). */
    data object Invalid : PhoneResult

    /** `+251` but not `+251` followed by 9 digits starting with 9 or 7 (the server's rule: no landlines). */
    data object NotEthiopianMobile : PhoneResult
}

/**
 * Phone number rules, the same as the server's (B2.1 `PhoneNumbers`): strict E.164 from the
 * contract, plus for `+251` a mobile number `^\+251[79][0-9]{8}$`. Input is normalised first with
 * Ethiopia as the default country: `0911 234 567`, `911234567`, `251911234567`,
 * `00251911234567` and `+251 91 123 4567` all become `+251911234567`. Other countries need `+`
 * (or `00`).
 */
object PhoneInput {
    const val DEFAULT_PREFIX = "+251"
    private val E164 = Regex("^\\+[1-9][0-9]{6,14}$")
    private val ETHIOPIAN_MOBILE = Regex("^\\+251[79][0-9]{8}$")

    /** Keeps only what can belong to a phone number while typing (digits, one leading `+`, spaces, dashes, brackets). */
    fun sanitize(raw: String): String =
        raw.filterIndexed { i, c -> c in '0'..'9' || c in " -()" || (c == '+' && i == 0) }.take(24)

    fun normalize(raw: String): PhoneResult {
        val s = raw.filter { it in '0'..'9' || it == '+' }
        if (s.isEmpty()) return PhoneResult.Empty
        if (s.indexOf('+') > 0 || s.count { it == '+' } > 1) return PhoneResult.Invalid
        val e164 = when {
            s.startsWith("+") -> s
            s.startsWith("00") -> "+" + s.drop(2)
            s.startsWith("0") -> DEFAULT_PREFIX + s.drop(1)
            s.startsWith("251") && s.length == 12 -> "+$s"
            else -> DEFAULT_PREFIX + s
        }
        if (!E164.matches(e164)) return PhoneResult.Invalid
        if (e164.startsWith(DEFAULT_PREFIX) && !ETHIOPIAN_MOBILE.matches(e164)) return PhoneResult.NotEthiopianMobile
        return PhoneResult.Valid(e164)
    }
}
