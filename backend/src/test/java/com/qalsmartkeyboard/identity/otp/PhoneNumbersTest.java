package com.qalsmartkeyboard.identity.otp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PhoneNumbersTest {

	@ParameterizedTest
	@ValueSource(strings = { "+251911234567", "+251711234567", "+447700900123", "+12025550123", "+4915112345678" })
	void acceptsEthiopianMobilesAndOtherE164(String number) {
		assertThat(PhoneNumbers.rejectionReason(number)).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "+251111234567", // Addis landline
			"+25191123456", // too short
			"+2519112345678", // too long
			"+251811234567", // not a mobile prefix
			"0911234567", "+251 911 234 567", "251911234567", "+0911234567", "+12345", "" })
	void rejectsLandlinesWrongLengthsAndNonE164(String number) {
		assertThat(PhoneNumbers.rejectionReason(number)).isPresent();
	}

	@ParameterizedTest
	@ValueSource(strings = { "+251911234567" })
	void destinationTag(String number) {
		assertThat(PhoneNumbers.destination(number)).isEqualTo("ET");
		assertThat(PhoneNumbers.destination("+447700900123")).isEqualTo("INTL");
	}
}
