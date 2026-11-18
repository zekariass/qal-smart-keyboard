package com.qalsmartkeyboard.usage;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;

import com.qalsmartkeyboard.entitlements.ResetPeriod;

/**
 * Window arithmetic of the metering engine (SD 15.3, ADR 0011). Pure functions: the caller supplies the user's time
 * zone, the reset period of the feature and, for paid plans, the anchor.
 *
 * <ul>
 * <li><b>DAILY</b>: the calendar day of {@code at} in {@code zone}, from its first instant to the first instant of the
 * next day. On a DST change the day is 23 or 25 hours long; where midnight does not exist (a DST gap at 00:00) the day
 * starts at the first valid local time ({@link LocalDate#atStartOfDay(ZoneId)}).</li>
 * <li><b>WEEKLY</b>: ISO week (Monday to Monday) in {@code zone}. Not used by the seed; supported because
 * {@code plan_entitlements.reset_period} allows it.</li>
 * <li><b>MONTHLY</b>: without an anchor (FREE, GUEST: the DEFAULT plan) the calendar month in {@code zone}. With an
 * anchor (the paid period start, SD 15.3) the window {@code [anchor + k months, anchor + (k + 1) months)} that contains
 * {@code at}, every boundary computed from the anchor itself on the local time line of {@code zone}, so a 31st anchor
 * gives the 28th/29th in February and the 31st again in March (no drift), and the local time of day survives DST.</li>
 * <li><b>YEARLY</b>: like MONTHLY with 12-month steps (calendar year without an anchor).</li>
 * <li><b>NONE</b>: the lifetime window {@link UsageWindow#LIFETIME}; it never resets.</li>
 * </ul>
 */
public final class UsageWindows {

	private UsageWindows() {
	}

	/**
	 * @param anchor the paid period start for MONTHLY/YEARLY windows, or null for calendar windows; ignored otherwise
	 */
	public static UsageWindow of(ResetPeriod period, ZoneId zone, Instant anchor, Instant at) {
		Objects.requireNonNull(period, "period");
		Objects.requireNonNull(zone, "zone");
		Objects.requireNonNull(at, "at");
		LocalDate day = LocalDate.ofInstant(at, zone);
		return switch (period) {
			case NONE -> UsageWindow.LIFETIME;
			case DAILY -> days(day, day.plusDays(1), zone);
			case WEEKLY -> {
				LocalDate monday = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
				yield days(monday, monday.plusWeeks(1), zone);
			}
			case MONTHLY -> anchor == null ? days(day.withDayOfMonth(1), day.withDayOfMonth(1).plusMonths(1), zone)
					: anchored(anchor, zone, at, 1);
			case YEARLY -> anchor == null ? days(day.withDayOfYear(1), day.withDayOfYear(1).plusYears(1), zone)
					: anchored(anchor, zone, at, 12);
		};
	}

	private static UsageWindow days(LocalDate from, LocalDate to, ZoneId zone) {
		return new UsageWindow(from.atStartOfDay(zone).toInstant(), to.atStartOfDay(zone).toInstant());
	}

	private static UsageWindow anchored(Instant anchor, ZoneId zone, Instant at, int monthsPerWindow) {
		ZonedDateTime origin = anchor.atZone(zone);
		long months = ChronoUnit.MONTHS.between(origin.toLocalDateTime(), at.atZone(zone).toLocalDateTime());
		long k = Math.floorDiv(months, monthsPerWindow);
		Instant start = boundary(origin, k, monthsPerWindow);
		while (start.isAfter(at)) {
			k--;
			start = boundary(origin, k, monthsPerWindow);
		}
		Instant end = boundary(origin, k + 1, monthsPerWindow);
		while (!end.isAfter(at)) {
			k++;
			start = end;
			end = boundary(origin, k + 1, monthsPerWindow);
		}
		return new UsageWindow(start, end);
	}

	/** The k-th boundary, always from the anchor (plusMonths clamps the day of month and keeps the local time). */
	private static Instant boundary(ZonedDateTime origin, long k, int monthsPerWindow) {
		return origin.plusMonths(k * monthsPerWindow).toInstant();
	}
}
