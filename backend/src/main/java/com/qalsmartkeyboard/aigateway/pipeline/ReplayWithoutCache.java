package com.qalsmartkeyboard.aigateway.pipeline;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.qalsmartkeyboard.aigateway.AiCommand;
import com.qalsmartkeyboard.aigateway.AiIdempotency;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.usage.Reservation;

/**
 * The B5.3 {@link AiIdempotency}: no result cache (B5.4 adds the encrypted one). Never a second charge:
 * <ul>
 * <li>RESERVED (the original is still running): 409 {@code REQUEST_IN_PROGRESS} with {@code Retry-After}.</li>
 * <li>SETTLED (charged once already): recompute without metering (SD 11.6 "after cache expiry the request is
 * recomputed without a second charge").</li>
 * <li>RELEASED (failed or cancelled, nothing charged): the original failure again (ADR 0007 D25; contract
 * {@code IdempotencyKey}: "the client must use a new key for every new attempt"), read from the request's
 * {@code ai_requests.error_code}. A cancelled original, or one whose row is missing, answers
 * {@code PROVIDER_UNAVAILABLE} (503, nothing charged, retry with a new key). Recomputing here would hand out an
 * uncharged result for a key that was never charged.</li>
 * </ul>
 */
public final class ReplayWithoutCache implements AiIdempotency {

	/** Seconds a client should wait before polling an in-flight duplicate again. */
	static final String RETRY_AFTER_SECONDS = "2";

	private final AiRequestLog requests;

	public ReplayWithoutCache(AiRequestLog requests) {
		this.requests = requests;
	}

	@Override
	public Decision onReplay(AiCommand command, Reservation reservation) {
		return switch (reservation.status()) {
			case RESERVED -> new Decision.Fail(new ApiException(ApiErrorCode.REQUEST_IN_PROGRESS,
					ApiErrorCode.REQUEST_IN_PROGRESS.defaultStatus(),
					"A request with this Idempotency-Key is still being processed.", List.of(), Map.of(),
					Map.of("Retry-After", RETRY_AFTER_SECONDS)));
			case SETTLED -> new Decision.Recompute();
			case RELEASED -> new Decision.Fail(original(reservation));
		};
	}

	private ApiException original(Reservation reservation) {
		Optional<ApiErrorCode> code = requests.outcome(reservation.id()).map(AiRequestLog.Outcome::errorCode)
				.flatMap(ReplayWithoutCache::parse);
		ApiErrorCode c = code.orElse(ApiErrorCode.PROVIDER_UNAVAILABLE);
		return new ApiException(c, c.defaultStatus(),
				"The original request with this Idempotency-Key failed and was not charged; use a new key to retry.");
	}

	private static Optional<ApiErrorCode> parse(String code) {
		try {
			return code == null ? Optional.empty() : Optional.of(ApiErrorCode.valueOf(code));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}
}
