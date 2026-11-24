package com.qalsmartkeyboard.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;

/** T8.3: staleness against the clock and mapping of the source snapshot to the API shape. */
class FxRatesServiceTest {

	static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");
	static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	static FxRatesService service(Optional<FxRateSnapshot> snapshot, Duration staleAfter) {
		return new FxRatesService(() -> snapshot, new FxProperties(staleAfter), CLOCK);
	}

	static FxRateSnapshot snapshot(Instant asOf) {
		return new FxRateSnapshot("USD", asOf, Map.of("ETB", new BigDecimal("155.5"), "EUR", new BigDecimal("0.86")),
				"seed");
	}

	@Test
	void mapsTheSnapshotOneToOne() {
		FxRatesDto dto = service(Optional.of(snapshot(NOW.minusSeconds(60))), Duration.ofHours(48)).current();
		assertEquals("USD", dto.base());
		assertEquals(NOW.minusSeconds(60), dto.asOf());
		assertEquals("seed", dto.source());
		assertEquals(new BigDecimal("155.5"), dto.rates().get("ETB"));
		assertEquals(2, dto.rates().size());
		assertFalse(dto.stale());
	}

	@Test
	void isStaleOnlyWhenStrictlyOlderThanTheThreshold() {
		FxRatesService s = service(Optional.empty(), Duration.ofHours(48));
		assertFalse(s.isStale(NOW.minus(Duration.ofHours(47)), NOW));
		assertFalse(s.isStale(NOW.minus(Duration.ofHours(48)), NOW), "exactly at the threshold is still fresh");
		assertTrue(s.isStale(NOW.minus(Duration.ofHours(48)).minusSeconds(1), NOW));
	}

	@Test
	void aSnapshotFromTheFutureIsNotStale() {
		assertFalse(service(Optional.empty(), Duration.ofHours(48)).isStale(NOW.plusSeconds(3600), NOW));
	}

	@Test
	void currentUsesTheConfiguredThresholdAndClock() {
		Instant threeDaysOld = NOW.minus(Duration.ofDays(3));
		assertTrue(service(Optional.of(snapshot(threeDaysOld)), Duration.ofHours(48)).current().stale());
		assertFalse(service(Optional.of(snapshot(threeDaysOld)), Duration.ofDays(4)).current().stale());
	}

	@Test
	void noSnapshotIsA503Problem() {
		ApiException e = assertThrows(ApiException.class,
				() -> service(Optional.empty(), Duration.ofHours(48)).current());
		assertEquals(ApiErrorCode.PROVIDER_UNAVAILABLE, e.code());
	}

	@Test
	void thresholdMustBePositiveAndDefaultsTo48Hours() {
		assertThrows(IllegalArgumentException.class, () -> new FxProperties(Duration.ZERO));
		assertThrows(IllegalArgumentException.class, () -> new FxProperties(Duration.ofHours(-1)));
	}
}
