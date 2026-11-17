package com.qalsmartkeyboard.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class AppVersionTest {

	private static AppVersion v(String s) {
		return AppVersion.parse(s).orElseThrow(() -> new AssertionError("not parsed: " + s));
	}

	@ParameterizedTest
	@CsvSource({
			"1.10.0, 1.9.0, 1",
			"1.9.0, 1.10.0, -1",
			"2.0.0, 2.0.0, 0",
			"2.0, 2.0.0, 0",
			"2, 2.0.0, 0",
			"2.1, 2.0.9, 1",
			"10.0.0, 9.99.99, 1",
			"2.0.0+build.5, 2.0.0, 0",
			"2.0.0+5, 2.0.0+6, 0",
			"2.0.0-beta.1, 2.0.0, -1",
			"2.0.0, 2.0.0-rc.1, 1",
			"2.0.0-alpha, 2.0.0-beta, -1",
			"2.0.0-beta.2, 2.0.0-beta.10, -1",
			"2.0.0-beta, 2.0.0-beta.1, -1",
			"2.0.0-1, 2.0.0-alpha, -1",
			"2.0.0-rc.1, 2.0.0-beta.11, 1",
			"1.9.9-debug, 2.0.0, -1",
			"1.0.0, 0.0.0, 1" })
	void comparesBySemverPrecedence(String a, String b, int expectedSign) {
		assertThat(Integer.signum(v(a).compareTo(v(b)))).isEqualTo(expectedSign);
		assertThat(Integer.signum(v(b).compareTo(v(a)))).isEqualTo(-expectedSign);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " ", "v2.0.0", "2.0.0.1", "a.b.c", "2..0", "2.0.", "-1.0.0", "2.0.0-", "2.0.0-beta..1",
			"2.0.0-01", "2.0.0+", "2.0.0 ", " 2.0.0", "2.0.0\n", "1234567890.0.0", "2.0.0-beta_1", "2.0.0-١" })
	void rejectsMalformed(String s) {
		assertThat(AppVersion.parse(s)).isEmpty();
	}

	@Test
	void rejectsNullAndOverlong() {
		assertThat(AppVersion.parse(null)).isEmpty();
		assertThat(AppVersion.parse("1.0.0-" + "a".repeat(40))).isEmpty();
		assertThat(AppVersion.parse("1.0.0-" + "a".repeat(34))).isPresent(); // exactly 40 characters
	}

	@Test
	void canonicalTextDropsBuildMetadataAndFillsMissingParts() {
		assertThat(v("2.1+7").toString()).isEqualTo("2.1.0");
		assertThat(v("3.0.1-rc.2+abc").toString()).isEqualTo("3.0.1-rc.2");
	}
}
