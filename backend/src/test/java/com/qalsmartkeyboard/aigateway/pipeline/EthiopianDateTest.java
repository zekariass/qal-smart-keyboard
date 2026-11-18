package com.qalsmartkeyboard.aigateway.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

/** Known Ethiopian new years, leap Pagume, Genna, and the Ethiopian clock (SD 11.5). */
class EthiopianDateTest {

	@Test
	void newYearIsMeskeremOneOnTheEleventhOrBeforeAGregorianLeapYearTheTwelfth() {
		assertThat(EthiopianDate.from(LocalDate.of(2026, 9, 11))).isEqualTo(new EthiopianDate(2019, 1, 1));
		assertThat(EthiopianDate.from(LocalDate.of(2027, 9, 12))).isEqualTo(new EthiopianDate(2020, 1, 1));
		assertThat(EthiopianDate.from(LocalDate.of(2023, 9, 12))).isEqualTo(new EthiopianDate(2016, 1, 1));
		assertThat(EthiopianDate.from(LocalDate.of(2024, 9, 11))).isEqualTo(new EthiopianDate(2017, 1, 1));
	}

	@Test
	void leapYearHasSixPagumeDays() {
		// 2019 E.C. (2019 mod 4 = 3) is a leap year: Pagume 6 is the day before the 2027 new year
		assertThat(EthiopianDate.from(LocalDate.of(2027, 9, 11))).isEqualTo(new EthiopianDate(2019, 13, 6));
		assertThat(EthiopianDate.from(LocalDate.of(2026, 9, 10))).isEqualTo(new EthiopianDate(2018, 13, 5));
	}

	@Test
	void gennaAndAnOrdinaryDate() {
		assertThat(EthiopianDate.from(LocalDate.of(2026, 1, 7))).isEqualTo(new EthiopianDate(2018, 4, 29));
		EthiopianDate d = EthiopianDate.from(LocalDate.of(2026, 11, 18));
		assertThat(d).isEqualTo(new EthiopianDate(2019, 3, 9));
		assertThat(d.formatLatin()).isEqualTo("9 Hidar 2019");
		assertThat(d.formatAmharic()).isEqualTo("ኅዳር 9 ቀን 2019 ዓ.ም.");
	}

	@Test
	void everyDayOfSeveralYearsMapsToAValidDateInOrder() {
		EthiopianDate previous = EthiopianDate.from(LocalDate.of(2019, 12, 31));
		for (LocalDate g = LocalDate.of(2020, 1, 1); g.isBefore(LocalDate.of(2032, 1, 1)); g = g.plusDays(1)) {
			EthiopianDate e = EthiopianDate.from(g);
			boolean next = (e.year() == previous.year() && e.month() == previous.month() && e.day() == previous.day() + 1)
					|| (e.year() == previous.year() && e.month() == previous.month() + 1 && e.day() == 1)
					|| (e.year() == previous.year() + 1 && e.month() == 1 && e.day() == 1 && previous.month() == 13);
			assertThat(next).as(g.toString()).isTrue();
			previous = e;
		}
	}

	@Test
	void ethiopianClockCountsFromSix() {
		assertThat(EthiopianDate.ethiopianClockHour(LocalTime.of(7, 0))).isEqualTo(1);
		assertThat(EthiopianDate.ethiopianClockHour(LocalTime.of(9, 0))).isEqualTo(3);
		assertThat(EthiopianDate.ethiopianClockHour(LocalTime.of(6, 0))).isEqualTo(12);
		assertThat(EthiopianDate.ethiopianClockHour(LocalTime.of(18, 30))).isEqualTo(12);
		assertThat(EthiopianDate.ethiopianClockHour(LocalTime.of(0, 0))).isEqualTo(6);
	}

	@Test
	void invalidDatesAreRefused() {
		assertThatThrownBy(() -> new EthiopianDate(2019, 13, 7)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new EthiopianDate(2019, 14, 1)).isInstanceOf(IllegalArgumentException.class);
	}
}
