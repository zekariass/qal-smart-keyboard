package com.qalsmartkeyboard.app.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneInputTest {
    private fun valid(input: String) = (PhoneInput.normalize(input) as PhoneResult.Valid).e164

    @Test fun nineDigitsGetThePrefix() {
        assertEquals("+251911234567", valid("911234567"))
        assertEquals("+251911234567", valid("911 234 567"))
    }

    @Test fun aLeadingZeroIsStripped() {
        assertEquals("+251911234567", valid("0911234567"))
        assertEquals("+251911234567", valid("0911 234 567"))
    }

    @Test fun aPastedCountryCodeIsAccepted() {
        for (s in listOf("251911234567", "+251911234567", "00251911234567", "+251 (91) 123-4567")) {
            assertEquals(s, "+251911234567", valid(s))
        }
    }

    @Test fun landlinesAndWrongLengthsAreRejectedLikeTheServer() {
        assertEquals(PhoneResult.NotEthiopianMobile, PhoneInput.normalize("0111234567")) // Addis landline
        assertEquals(PhoneResult.NotEthiopianMobile, PhoneInput.normalize("111234567"))
        assertEquals(PhoneResult.NotEthiopianMobile, PhoneInput.normalize("09112345")) // too short
        assertEquals(PhoneResult.NotEthiopianMobile, PhoneInput.normalize("91123456")) // 8 digits
        assertEquals(PhoneResult.NotEthiopianMobile, PhoneInput.normalize("9112345678")) // 10 digits
        assertEquals(PhoneResult.NotEthiopianMobile, PhoneInput.normalize("811234567")) // 8 is not a mobile prefix
        assertEquals(PhoneResult.NotEthiopianMobile, PhoneInput.normalize("71123456")) // 8 digits
        assertEquals(PhoneResult.OutsideEthiopia, PhoneInput.normalize("00911234567")) // 00 + 91 (India)
    }

    @Test fun otherCountriesAreRejectedOnTheDevice() {
        assertEquals(PhoneResult.OutsideEthiopia, PhoneInput.normalize("+447700900123"))
        assertEquals(PhoneResult.OutsideEthiopia, PhoneInput.normalize("+14155550123"))
        assertEquals(PhoneResult.OutsideEthiopia, PhoneInput.normalize("0014155550123"))
    }

    @Test fun safaricomIsRefusedWithItsOwnResult() {
        for (s in listOf("711234567", "0711234567", "+251711234567", "251711234567", "0711 234 567")) {
            assertEquals(s, PhoneResult.CarrierNotSupported, PhoneInput.normalize(s))
        }
    }

    @Test fun emptyAndGarbage() {
        assertEquals(PhoneResult.Empty, PhoneInput.normalize(""))
        assertEquals(PhoneResult.Empty, PhoneInput.normalize("  -() +"))
        assertEquals(PhoneResult.Empty, PhoneInput.normalize("abc"))
    }

    @Test fun ethiopicAndArabicIndicDigitsAreNotAccepted() {
        assertEquals(PhoneResult.Empty, PhoneInput.normalize("፱፩፩"))
        assertEquals("", PhoneInput.sanitize("፱٩abc"))
    }

    @Test fun sanitizeKeepsOnlyDigits() {
        assertEquals("251911", PhoneInput.sanitize("251 91-1(x)"))
        assertEquals("+4477", PhoneInput.sanitize("+44+77")) // a plus only first
        assertEquals(16, PhoneInput.sanitize("1".repeat(30)).length)
    }
}
