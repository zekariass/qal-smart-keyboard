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

	private static final java.util.List<String> DEFAULT = java.util.List.of("+2519");

	@org.junit.jupiter.api.Test
	void defaultAllowsOnlyEthioTelecomMobiles() {
		assertThat(PhoneNumbers.eligibility("+251911234567", DEFAULT)).isEqualTo(PhoneNumbers.Eligibility.ALLOWED);
		assertThat(PhoneNumbers.eligibility("+251711234567", DEFAULT))
				.isEqualTo(PhoneNumbers.Eligibility.CARRIER_NOT_SUPPORTED);
		assertThat(PhoneNumbers.eligibility("+447700900123", DEFAULT))
				.isEqualTo(PhoneNumbers.Eligibility.REGION_NOT_SUPPORTED);
		assertThat(PhoneNumbers.eligibility("+12025550123", DEFAULT))
				.isEqualTo(PhoneNumbers.Eligibility.REGION_NOT_SUPPORTED);
	}

	@org.junit.jupiter.api.Test
	void configuredPrefixesWiden() {
		java.util.List<String> wide = java.util.List.of("+2519", "+2517", "+44");
		assertThat(PhoneNumbers.eligibility("+251711234567", wide)).isEqualTo(PhoneNumbers.Eligibility.ALLOWED);
		assertThat(PhoneNumbers.eligibility("+447700900123", wide)).isEqualTo(PhoneNumbers.Eligibility.ALLOWED);
		assertThat(PhoneNumbers.eligibility("+12025550123", wide))
				.isEqualTo(PhoneNumbers.Eligibility.REGION_NOT_SUPPORTED);
	}
}
