package com.qalsmartkeyboard.tools;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.http.HttpStatus;

import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;

/** Maps the source's newest snapshot to the API shape and decides staleness against the clock. */
class FxRatesService {

	private final FxRateSource source;
	private final Duration staleAfter;
	private final Clock clock;

	FxRatesService(FxRateSource source, FxProperties properties, Clock clock) {
		this.source = source;
		this.staleAfter = properties.staleAfter();
		this.clock = clock;
	}

	FxRatesDto current() {
		FxRateSnapshot snapshot = source.latest().orElseThrow(() -> new ApiException(ApiErrorCode.PROVIDER_UNAVAILABLE,
				HttpStatus.SERVICE_UNAVAILABLE, "No exchange-rate snapshot is available."));
		return new FxRatesDto(snapshot.base(), snapshot.asOf(), snapshot.rates(), snapshot.source(),
				isStale(snapshot.asOf(), clock.instant()));
	}

	/** Stale when strictly older than the threshold; a snapshot dated in the future is not stale. */
	boolean isStale(Instant asOf, Instant now) {
		return Duration.between(asOf, now).compareTo(staleAfter) > 0;
	}
}
