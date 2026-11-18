package com.qalsmartkeyboard.aigateway;

import java.time.Duration;
import java.util.List;

import com.qalsmartkeyboard.aigateway.spi.ProviderCapability;
import com.qalsmartkeyboard.aigateway.spi.ProviderException;

/**
 * What a routed call came to (B5.2). On {@link Outcome#SUCCEEDED} {@code value} is the provider result and
 * {@code providerCode} the provider that served it; otherwise {@code value} is null, {@code finalErrorKind} the kind
 * of the last provider failure (null when no provider was called) and {@link #contractCode()} the {@code Problem.code}
 * B5.3 answers with. Never carries text, only codes, counts and durations.
 *
 * @param servedByLastResort the serving provider was DEGRADED (ADR 0007 D29)
 * @param attempts the provider calls made, in order
 * @param skipped candidates that were not called, with the reason
 * @param elapsed wall time of the whole route
 * @param capability the capability row that matched the serving provider (quality tier for the response, model code);
 *                   null unless SUCCEEDED
 */
public record RouteResult<T>(Outcome outcome, T value, String providerCode, boolean servedByLastResort,
		List<Attempt> attempts, List<Skip> skipped, ProviderException.Kind finalErrorKind, Duration elapsed,
		ProviderCapability capability) {

	public enum Outcome {
		SUCCEEDED("") ,
		/** Every candidate failed, was skipped (kill switch, open breaker, cost cap) or none is routable. */
		PROVIDER_UNAVAILABLE("PROVIDER_UNAVAILABLE"),
		/** The last failure was a timeout, or the overall deadline ran out. */
		PROVIDER_TIMEOUT("PROVIDER_TIMEOUT"),
		/** Zero retention was required and no ZDR provider can serve it (some other provider could). */
		ZERO_RETENTION_UNAVAILABLE("ZERO_RETENTION_UNAVAILABLE"),
		/** No provider offers the feature for this language pair and tier, or all refused the pair. */
		LANGUAGE_PAIR_UNAVAILABLE("LANGUAGE_PAIR_UNAVAILABLE"),
		/** The provider safety filter refused the content; never retried elsewhere. */
		CONTENT_REJECTED("CONTENT_REJECTED"),
		/** The caller went away (interrupt). Release the reservation, send no body (ADR 0007 D29). */
		CANCELLED("");

		private final String code;

		Outcome(String code) {
			this.code = code;
		}
	}

	/** One provider call. {@code errorKind} null = it succeeded. */
	public record Attempt(int number, String providerCode, boolean lastResort, Duration latency,
			ProviderException.Kind errorKind) {
	}

	public enum SkipReason {
		KILL_SWITCH_PROVIDER, KILL_SWITCH_FEATURE, CIRCUIT_OPEN, COST_CAP
	}

	public record Skip(String providerCode, SkipReason reason) {
	}

	public boolean succeeded() {
		return outcome == Outcome.SUCCEEDED;
	}

	/** The {@code Problem.code} for a failed route; null for success and for a client abort. */
	public String contractCode() {
		return outcome.code.isEmpty() ? null : outcome.code;
	}
}
