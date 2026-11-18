package com.qalsmartkeyboard.aigateway;

import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import com.qalsmartkeyboard.aigateway.spi.AiFeature;
import com.qalsmartkeyboard.aigateway.spi.AiProvider;
import com.qalsmartkeyboard.aigateway.spi.DataPolicyRequirement;
import com.qalsmartkeyboard.aigateway.spi.DetectRequest;
import com.qalsmartkeyboard.aigateway.spi.DetectResult;
import com.qalsmartkeyboard.aigateway.spi.ProviderUsage;
import com.qalsmartkeyboard.aigateway.spi.QualityTier;
import com.qalsmartkeyboard.aigateway.spi.RouteQuery;
import com.qalsmartkeyboard.aigateway.spi.TransformRequest;
import com.qalsmartkeyboard.aigateway.spi.TransformResult;
import com.qalsmartkeyboard.aigateway.spi.TranslateRequest;
import com.qalsmartkeyboard.aigateway.spi.TranslateResult;

/**
 * One routed AI call: what the route must satisfy ({@code query}: feature, language pair, minimum tier, data policy,
 * input size), what to run on the chosen provider and how to read its usage for cost accounting.
 *
 * @param deadline overall time budget across all attempts; null = the configured default
 * @param attemptTimeout cap for one attempt (also bounded by the time left); null = the configured default
 * @param maxAttempts provider calls at most; 0 = the configured default
 * @param onlyProviders when not empty, only these provider codes may be tried (B5.3: the guardrail retry "on the same
 *                      route" pins the provider that produced the rejected output); never adds a provider the registry
 *                      and the policies would not allow
 * @param excludedProviders provider codes not to try (B5.3: the guardrail fallback route skips every provider already
 *                          tried for this request)
 */
public record RouteRequest<T>(RouteQuery query, ProviderCall<T> call, Function<T, ProviderUsage> usage,
		Duration deadline, Duration attemptTimeout, int maxAttempts, Set<String> onlyProviders,
		Set<String> excludedProviders) {

	/** Runs the call on one adapter with the per-attempt timeout (the SPI contract: the adapter enforces it). */
	@FunctionalInterface
	public interface ProviderCall<T> {
		T execute(AiProvider adapter, Duration timeout);
	}

	public RouteRequest {
		Objects.requireNonNull(query, "query");
		Objects.requireNonNull(call, "call");
		Objects.requireNonNull(usage, "usage");
		if (maxAttempts < 0 || (deadline != null && !deadline.isPositive())
				|| (attemptTimeout != null && !attemptTimeout.isPositive())) {
			throw new IllegalArgumentException("deadline, attempt timeout and attempts must be positive");
		}
		onlyProviders = onlyProviders == null ? Set.of() : Set.copyOf(onlyProviders);
		excludedProviders = excludedProviders == null ? Set.of() : Set.copyOf(excludedProviders);
	}

	public RouteRequest(RouteQuery query, ProviderCall<T> call, Function<T, ProviderUsage> usage, Duration deadline,
			Duration attemptTimeout, int maxAttempts) {
		this(query, call, usage, deadline, attemptTimeout, maxAttempts, Set.of(), Set.of());
	}

	public RouteRequest<T> withLimits(Duration deadline, Duration attemptTimeout, int maxAttempts) {
		return new RouteRequest<>(query, call, usage, deadline, attemptTimeout, maxAttempts, onlyProviders,
				excludedProviders);
	}

	/** The same call on exactly one provider, one attempt (a retry "on the same route"). */
	public RouteRequest<T> pinnedTo(String providerCode) {
		return new RouteRequest<>(query, call, usage, deadline, attemptTimeout, 1, Set.of(providerCode),
				excludedProviders);
	}

	/** The same call on any allowed provider except {@code providerCodes} (and those already excluded). */
	public RouteRequest<T> excluding(Collection<String> providerCodes) {
		Set<String> excluded = new HashSet<>(excludedProviders);
		excluded.addAll(providerCodes);
		return new RouteRequest<>(query, call, usage, deadline, attemptTimeout, maxAttempts, Set.of(), excluded);
	}

	/** The same route with another call (and usage reader) for the same result type. */
	public RouteRequest<T> withCall(ProviderCall<T> otherCall) {
		return new RouteRequest<>(query, otherCall, usage, deadline, attemptTimeout, maxAttempts, onlyProviders,
				excludedProviders);
	}

	/** True when the request filters allow the provider. */
	boolean allows(String providerCode) {
		return (onlyProviders.isEmpty() || onlyProviders.contains(providerCode))
				&& !excludedProviders.contains(providerCode);
	}

	public static RouteRequest<TranslateResult> translate(TranslateRequest request, QualityTier minimumTier,
			DataPolicyRequirement dataPolicy) {
		RouteQuery q = new RouteQuery(AiFeature.TRANSLATE, request.sourceLanguage(), request.destinationLanguage(),
				minimumTier, dataPolicy, request.text().characters());
		return new RouteRequest<>(q, (adapter, timeout) -> adapter.translate(request, timeout), TranslateResult::usage,
				null, null, 0);
	}

	public static RouteRequest<TransformResult> transform(TransformRequest request, QualityTier minimumTier,
			DataPolicyRequirement dataPolicy) {
		RouteQuery q = new RouteQuery(request.task(), request.language(), null, minimumTier, dataPolicy,
				request.text().characters());
		return new RouteRequest<>(q, (adapter, timeout) -> adapter.llmTransform(request, timeout),
				TransformResult::usage, null, null, 0);
	}

	public static RouteRequest<DetectResult> detect(DetectRequest request, QualityTier minimumTier,
			DataPolicyRequirement dataPolicy) {
		RouteQuery q = new RouteQuery(AiFeature.DETECT_LANGUAGE, null, null, minimumTier, dataPolicy,
				request.text().characters());
		return new RouteRequest<>(q, (adapter, timeout) -> adapter.detectLanguage(request, timeout),
				DetectResult::usage, null, null, 0);
	}
}
