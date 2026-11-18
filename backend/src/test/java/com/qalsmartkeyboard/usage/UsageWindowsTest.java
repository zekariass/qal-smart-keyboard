package com.qalsmartkeyboard.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.qalsmartkeyboard.entitlements.ResetPeriod;

/** Window arithmetic (SD 15.3): time zones, DST, month lengths, leap days, anchors, lifetime. */
class UsageWindowsTest {

	static final ZoneId ADDIS = ZoneId.of("Africa/Addis_Ababa"); // UTC+3, no DST
	static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
	static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
	static final ZoneId SANTIAGO = ZoneId.of("America/Santiago"); // DST starts at local midnight

	static Instant local(String dateTime, ZoneId zone) {
		return LocalDateTime.parse(dateTime).atZone(zone).toInstant();
	}

	static UsageWindow daily(ZoneId zone, Instant at) {
		return UsageWindows.of(ResetPeriod.DAILY, zone, null, at);
	}

	// ------------------------------------------------------------------ DAILY

	@Test
	void dailyWindowIsTheLocalDayInTheUsersZone() {
		UsageWindow w = daily(ADDIS, Instant.parse("2026-11-18T09:00:00Z"));
		assertThat(w.start()).isEqualTo(Instant.parse("2026-11-17T21:00:00Z")); // 00:00 in Addis
		assertThat(w.end()).isEqualTo(Instant.parse("2026-11-18T21:00:00Z")); // the contract example's resetAt
		assertThat(w.resetAt()).isEqualTo(w.end());
		assertThat(w.lifetime()).isFalse();
	}

	@Test
	void midnightInTheUsersZoneStartsANewWindowAndTheEndIsExclusive() {
		Instant lastInstant = local("2026-11-18T23:59:59.999999", ADDIS);
		Instant midnight = local("2026-11-19T00:00", ADDIS);
		UsageWindow before = daily(ADDIS, lastInstant);
		UsageWindow after = daily(ADDIS, midnight);
		assertThat(before.end()).isEqualTo(midnight).isEqualTo(after.start());
		assertThat(before.contains(lastInstant)).isTrue();
		assertThat(before.contains(midnight)).isFalse();
		assertThat(after.contains(midnight)).isTrue();
		// the same instant is another day in another zone
		assertThat(daily(ZoneId.of("UTC"), lastInstant).start()).isEqualTo(Instant.parse("2026-11-18T00:00:00Z"));
	}

	@Test
	void dstDaysAre23And25HoursLong() {
		UsageWindow spring = daily(BERLIN, local("2026-03-29T12:00", BERLIN));
		assertThat(Duration.between(spring.start(), spring.end())).isEqualTo(Duration.ofHours(23));
		assertThat(spring.start()).isEqualTo(Instant.parse("2026-03-28T23:00:00Z"));
		assertThat(spring.end()).isEqualTo(Instant.parse("2026-03-29T22:00:00Z"));
		UsageWindow autumn = daily(NEW_YORK, local("2026-11-01T12:00", NEW_YORK));
		assertThat(Duration.between(autumn.start(), autumn.end())).isEqualTo(Duration.ofHours(25));
		// 23:59 local before the change and 00:00 after it are in different windows
		assertThat(daily(NEW_YORK, local("2026-10-31T23:59:59", NEW_YORK)).end()).isEqualTo(autumn.start());
	}

	@Test
	void aDayWhoseMidnightDoesNotExistStartsAtTheFirstValidLocalTime() {
		// Chile springs forward at 24:00 on 2026-09-05: 2026-09-06T00:00 does not exist locally
		UsageWindow w = daily(SANTIAGO, local("2026-09-06T12:00", SANTIAGO));
		assertThat(w.start().atZone(SANTIAGO).toLocalTime().toString()).isEqualTo("01:00");
		assertThat(Duration.between(w.start(), w.end())).isEqualTo(Duration.ofHours(23)); // 01:00 to 24:00
		UsageWindow previous = daily(SANTIAGO, local("2026-09-05T23:59:59", SANTIAGO));
		assertThat(previous.end()).isEqualTo(w.start());
		assertThat(Duration.between(previous.start(), previous.end())).isEqualTo(Duration.ofHours(24));
	}

	// ------------------------------------------------------------------ MONTHLY

	@ParameterizedTest
	@CsvSource({ "2026-01-31T23:59:59, 2026-01-01T00:00, 2026-02-01T00:00",
			"2026-02-01T00:00:00, 2026-02-01T00:00, 2026-03-01T00:00",
			"2028-02-29T12:00:00, 2028-02-01T00:00, 2028-03-01T00:00",
			"2026-12-31T23:59:59, 2026-12-01T00:00, 2027-01-01T00:00" })
	void calendarMonthInTheUsersZoneWithoutAnAnchor(String at, String start, String end) {
		UsageWindow w = UsageWindows.of(ResetPeriod.MONTHLY, ADDIS, null, local(at, ADDIS));
		assertThat(w.start()).isEqualTo(local(start, ADDIS));
		assertThat(w.end()).isEqualTo(local(end, ADDIS));
	}

	@Test
	void anchoredMonthsFollowThePaidPeriodStartWithoutDrift() {
		Instant anchor = local("2028-01-31T10:00", ADDIS);
		UsageWindow jan = UsageWindows.of(ResetPeriod.MONTHLY, ADDIS, anchor, local("2028-02-15T00:00", ADDIS));
		assertThat(jan.start()).isEqualTo(anchor);
		assertThat(jan.end()).isEqualTo(local("2028-02-29T10:00", ADDIS)); // leap year: clamped to the 29th
		UsageWindow feb = UsageWindows.of(ResetPeriod.MONTHLY, ADDIS, anchor, local("2028-02-29T10:00", ADDIS));
		assertThat(feb.start()).isEqualTo(local("2028-02-29T10:00", ADDIS));
		assertThat(feb.end()).as("back to the 31st, no drift").isEqualTo(local("2028-03-31T10:00", ADDIS));
		UsageWindow apr = UsageWindows.of(ResetPeriod.MONTHLY, ADDIS, anchor, local("2028-04-30T09:59:59", ADDIS));
		assertThat(apr.start()).isEqualTo(local("2028-03-31T10:00", ADDIS));
		assertThat(apr.end()).isEqualTo(local("2028-04-30T10:00", ADDIS));
		// non-leap year
		UsageWindow feb2027 = UsageWindows.of(ResetPeriod.MONTHLY, ADDIS, local("2027-01-31T10:00", ADDIS),
				local("2027-02-28T09:59", ADDIS));
		assertThat(feb2027.end()).isEqualTo(local("2027-02-28T10:00", ADDIS));
	}

	@Test
	void anchoredMonthsKeepTheLocalTimeAcrossDst() {
		Instant anchor = local("2026-03-15T08:00", BERLIN); // CET
		UsageWindow w = UsageWindows.of(ResetPeriod.MONTHLY, BERLIN, anchor, local("2026-04-01T00:00", BERLIN));
		assertThat(w.start()).isEqualTo(anchor);
		assertThat(w.end()).isEqualTo(local("2026-04-15T08:00", BERLIN)); // 08:00 CEST: one hour earlier in UTC
		assertThat(Duration.between(w.start(), w.end())).isEqualTo(Duration.ofDays(31).minusHours(1));
	}

	@Test
	void anchoredWindowBoundariesAreInclusiveStartExclusiveEnd() {
		Instant anchor = Instant.parse("2026-01-10T07:30:00Z");
		Instant boundary = anchor.atZone(ADDIS).plusMonths(5).toInstant();
		assertThat(UsageWindows.of(ResetPeriod.MONTHLY, ADDIS, anchor, boundary).start()).isEqualTo(boundary);
		assertThat(UsageWindows.of(ResetPeriod.MONTHLY, ADDIS, anchor, boundary.minusNanos(1)).end())
				.isEqualTo(boundary);
		// an instant before the anchor still gets a well-formed window (k < 0)
		UsageWindow before = UsageWindows.of(ResetPeriod.MONTHLY, ADDIS, anchor, anchor.minusSeconds(1));
		assertThat(before.end()).isEqualTo(anchor);
		assertThat(before.start()).isEqualTo(anchor.atZone(ADDIS).minusMonths(1).toInstant());
	}

	// ------------------------------------------------------------------ others

	@Test
	void noneIsTheLifetimeWindowWithoutAReset() {
		UsageWindow w = UsageWindows.of(ResetPeriod.NONE, ADDIS, Instant.now(), Instant.now());
		assertThat(w).isEqualTo(UsageWindow.LIFETIME);
		assertThat(w.lifetime()).isTrue();
		assertThat(w.resetAt()).isNull();
		assertThat(w.contains(Instant.parse("2300-01-01T00:00:00Z"))).isTrue();
	}

	@Test
	void weeklyIsTheIsoWeekAndYearlyFollowsTheAnchor() {
		UsageWindow week = UsageWindows.of(ResetPeriod.WEEKLY, ADDIS, null, local("2026-11-18T12:00", ADDIS));
		assertThat(week.start()).isEqualTo(local("2026-11-16T00:00", ADDIS)); // Monday
		assertThat(week.end()).isEqualTo(local("2026-11-23T00:00", ADDIS));
		UsageWindow year = UsageWindows.of(ResetPeriod.YEARLY, ADDIS, null, local("2026-11-18T12:00", ADDIS));
		assertThat(year.start()).isEqualTo(local("2026-01-01T00:00", ADDIS));
		Instant leapAnchor = local("2028-02-29T00:00", ADDIS);
		UsageWindow y2029 = UsageWindows.of(ResetPeriod.YEARLY, ADDIS, leapAnchor, local("2029-06-01T00:00", ADDIS));
		assertThat(y2029.start()).isEqualTo(local("2029-02-28T00:00", ADDIS));
		assertThat(y2029.end()).isEqualTo(local("2030-02-28T00:00", ADDIS));
		assertThat(UsageWindows.of(ResetPeriod.YEARLY, ADDIS, leapAnchor, local("2032-03-01T00:00", ADDIS)).start())
				.isEqualTo(local("2032-02-29T00:00", ADDIS));
	}

	@Test
	void windowsMustNotBeEmpty() {
		Instant t = Instant.now();
		assertThatThrownBy(() -> new UsageWindow(t, t)).isInstanceOf(IllegalArgumentException.class);
	}
}
