package com.qalsmartkeyboard.aigateway;

import java.time.Duration;
import java.util.Objects;
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
 */
public record RouteRequest<T>(RouteQuery query, ProviderCall<T> call, Function<T, ProviderUsage> usage,
		Duration deadline, Duration attemptTimeout, int maxAttempts) {

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
	}

	public RouteRequest<T> withLimits(Duration deadline, Duration attemptTimeout, int maxAttempts) {
		return new RouteRequest<>(query, call, usage, deadline, attemptTimeout, maxAttempts);
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
