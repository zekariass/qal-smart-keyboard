package com.qalsmartkeyboard.app.auth

/** Result of normalising what the user typed into the phone field. */
sealed interface PhoneResult {
    data class Valid(val e164: String) : PhoneResult
    data object Empty : PhoneResult

    /** Not 9 digits starting with 9 or 7 (landline, wrong length, other prefix). */
    data object NotEthiopianMobile : PhoneResult

    /** An Ethiopian mobile on another carrier (07..., Safaricom Ethiopia): it cannot receive sign-in codes yet. */
    data object CarrierNotSupported : PhoneResult

    /** An international number (`+` or `00` and not 251): phone sign-in is Ethiopia only. */
    data object OutsideEthiopia : PhoneResult
}

/**
 * Phone number rules (ADR 0007 D48), mirroring the server: only Ethio Telecom mobiles (`+2519XXXXXXXX`) get a code.
 * The field has a fixed `+251` prefix and the user types the 9-digit national number (`911 234 567`). A leading `0`
 * (`0911 234 567`) is stripped, and a pasted `251...`, `+251...` or `00251...` is accepted too. `07...`/`7...`
 * (Safaricom Ethiopia) is refused with its own message, and a number with another country code with another one,
 * both without calling the server.
 */
object PhoneInput {
    const val DEFAULT_PREFIX = "+251"
    private val ETHIO_TELECOM = Regex("^9[0-9]{8}$")
    private val SAFARICOM = Regex("^7[0-9]{8}$")

    /** Keeps digits (and a leading `+`, so a pasted international number can be recognised) while typing. */
    fun sanitize(raw: String): String =
        raw.filterIndexed { i, c -> c in '0'..'9' || (c == '+' && i == 0) }.take(16)

    /** The national significant digits: a pasted country code or a leading trunk `0` removed. */
    fun national(raw: String): String {
        val d = raw.filter { it in '0'..'9' }
        return when {
            d.startsWith("00251") -> d.drop(5)
            d.startsWith("251") -> d.drop(3) // no national number starts with 2
            d.startsWith("0") -> d.drop(1)
            else -> d
        }
    }

    fun normalize(raw: String): PhoneResult {
        val t = raw.trim()
        if (t.none { it in '0'..'9' }) return PhoneResult.Empty
        val digits = t.filter { it in '0'..'9' }
        val international = (t.startsWith("+") && !digits.startsWith("251")) ||
            (digits.startsWith("00") && !digits.startsWith("00251"))
        if (international && digits.length > 2) return PhoneResult.OutsideEthiopia
        val n = national(t)
        return when {
            ETHIO_TELECOM.matches(n) -> PhoneResult.Valid(DEFAULT_PREFIX + n)
            SAFARICOM.matches(n) -> PhoneResult.CarrierNotSupported
            else -> PhoneResult.NotEthiopianMobile
        }
    }
}
