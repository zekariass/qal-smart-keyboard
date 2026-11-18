package com.qalsmartkeyboard.aigateway.pipeline;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.JulianFields;
import java.util.List;
import java.util.Objects;

/**
 * A date in the Ethiopian (Ge'ez) calendar, Amete Mihret era, converted from the ISO calendar through the Julian day
 * number. The server needs it only to put "today" into prompt context (SD 11.5: calendar and Ethiopian clock
 * conversions), so it is a small pure-Java port of the standard arithmetic rather than a dependency on the Rust
 * {@code keyboard-core} calendar (C1.8), which the backend must not link.
 *
 * <p>Twelve months of 30 days and Pagume of 5 days (6 in a leap year: an Ethiopian year {@code y} with
 * {@code y mod 4 == 3}). The year starts on Meskerem 1 = 11 September, or 12 September before a Gregorian leap year.
 *
 * @param year  Amete Mihret year (2019 for most of Gregorian 2026/27)
 * @param month 1 (Meskerem) to 13 (Pagume)
 * @param day   1 to 30 (1 to 6 in Pagume)
 */
public record EthiopianDate(int year, int month, int day) {

	/** Julian day number of Meskerem 1, year 1 Amete Mihret, minus one (the conventional epoch offset). */
	private static final long EPOCH_OFFSET = 1_723_856L;

	private static final List<String> MONTHS_LATIN = List.of("Meskerem", "Tikimt", "Hidar", "Tahsas", "Tir",
			"Yekatit", "Megabit", "Miyazya", "Ginbot", "Sene", "Hamle", "Nehase", "Pagume");

	private static final List<String> MONTHS_GEEZ = List.of("መስከረም", "ጥቅምት", "ኅዳር", "ታኅሣሥ", "ጥር", "የካቲት",
			"መጋቢት", "ሚያዝያ", "ግንቦት", "ሰኔ", "ሐምሌ", "ነሐሴ", "ጳጉሜ");

	public EthiopianDate {
		if (month < 1 || month > 13 || day < 1 || day > (month == 13 ? 6 : 30)) {
			throw new IllegalArgumentException("not an Ethiopian calendar date");
		}
	}

	public static EthiopianDate from(LocalDate date) {
		Objects.requireNonNull(date, "date");
		long jdn = date.getLong(JulianFields.JULIAN_DAY);
		long days = jdn - EPOCH_OFFSET;
		long r = Math.floorMod(days, 1461L);
		long n = (r % 365) + 365 * (r / 1460);
		long year = 4 * Math.floorDiv(days, 1461L) + r / 365 - r / 1460;
		return new EthiopianDate((int) year, (int) (n / 30) + 1, (int) (n % 30) + 1);
	}

	/** English transliteration of the month name ("Hidar"). */
	public String monthNameLatin() {
		return MONTHS_LATIN.get(month - 1);
	}

	/** The month name in Ge'ez script ("ኅዳር"). */
	public String monthNameGeez() {
		return MONTHS_GEEZ.get(month - 1);
	}

	/** "9 Hidar 2019". */
	public String formatLatin() {
		return day + " " + monthNameLatin() + " " + year;
	}

	/** "ኅዳር 9 ቀን 2019 ዓ.ም." (the usual Amharic written form, Western digits). */
	public String formatAmharic() {
		return monthNameGeez() + " " + day + " ቀን " + year + " ዓ.ም.";
	}

	/**
	 * The Ethiopian clock hour for a time of day: hours are counted from 06:00 and 18:00, so 07:00 is 1 o'clock and
	 * 06:00 is 12 o'clock (contract {@code CulturalOptions.timeNotation}).
	 */
	public static int ethiopianClockHour(LocalTime time) {
		int h = Math.floorMod(time.getHour() - 6, 12);
		return h == 0 ? 12 : h;
	}
}
