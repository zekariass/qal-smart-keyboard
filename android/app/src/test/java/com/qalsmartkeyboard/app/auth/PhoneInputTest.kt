package com.qalsmartkeyboard.app.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneInputTest {
    private fun valid(input: String) = (PhoneInput.normalize(input) as PhoneResult.Valid).e164

    @Test fun ethiopianMobileInEveryCommonShape() {
        val expected = "+251911234567"
        for (s in listOf("0911234567", "911234567", "251911234567", "+251911234567", "00251911234567", "0911 234 567", "+251 (91) 123-4567")) {
            assertEquals(s, expected, valid(s))
        }
        assertEquals("+251711234567", valid("0711234567")) // Safaricom Ethiopia
    }

    @Test fun landlinesAndWrongLengthsAreRejectedLikeTheServer() {
        assertEquals(PhoneResult.NotEthiopianMobile, PhoneInput.normalize("0111234567"))
        assertEquals(PhoneResult.NotEthiopianMobile, PhoneInput.normalize("+251 11 123 4567"))
        assertEquals(PhoneResult.NotEthiopianMobile, PhoneInput.normalize("09112345")) // too short
        assertEquals(PhoneResult.NotEthiopianMobile, PhoneInput.normalize("091123456789")) // too long
        assertEquals(PhoneResult.NotEthiopianMobile, PhoneInput.normalize("0811234567")) // 8 is not a mobile prefix
    }

    @Test fun otherCountriesNeedAPlusAndMatchTheContractPattern() {
        assertEquals("+14155550123", valid("+1 415 555 0123"))
        assertEquals("+14155550123", valid("001 415 555 0123"))
        assertEquals(PhoneResult.Invalid, PhoneInput.normalize("+0123456789")) // country code cannot start with 0
        assertEquals(PhoneResult.Invalid, PhoneInput.normalize("+12345")) // too short for E.164 pattern
        assertEquals(PhoneResult.Invalid, PhoneInput.normalize("+1234567890123456")) // longer than 15 digits
        assertEquals(PhoneResult.Invalid, PhoneInput.normalize("+251+9112345"))
        assertEquals(PhoneResult.Invalid, PhoneInput.normalize("251+911234567"))
    }

    @Test fun emptyAndGarbage() {
        assertEquals(PhoneResult.Empty, PhoneInput.normalize(""))
        assertEquals(PhoneResult.Empty, PhoneInput.normalize("  -() "))
        assertEquals(PhoneResult.Empty, PhoneInput.normalize("abc"))
    }

    @Test fun ethiopicAndArabicIndicDigitsAreNotAccepted() {
        assertEquals(PhoneResult.Empty, PhoneInput.normalize("፱፩፩"))
        assertEquals("", PhoneInput.sanitize("፱٩abc"))
    }

    @Test fun sanitizeKeepsOnlyPhoneCharacters() {
        assertEquals("+251 91-1(2)3", PhoneInput.sanitize("+251 91-1(2)3x"))
        assertEquals("251", PhoneInput.sanitize("2+5+1")) // a plus is only valid first
    }
}
