package com.qalsmartkeyboard.aigateway.spi;

import java.time.Duration;

/**
 * One vendor integration (B5.1). A Spring bean per provider, matched to its {@code ai_providers.provider_code} row by
 * {@link #code()}: a row without an adapter, or an adapter without a row, is never routable (fail closed, SD 11.2).
 *
 * <p>Contract for every method:
 * <ul>
 * <li>{@code timeout} is the hard deadline for the whole call and must be positive; an adapter never blocks without
 * one and throws {@link ProviderException.Timeout} when it is exceeded.</li>
 * <li>Cancellation is thread interruption (virtual threads, SD 17): an interrupted call restores the flag and throws
 * {@link ProviderException.Cancelled}.</li>
 * <li>Only {@link ProviderException} subtypes escape. Provider bodies, raw errors, prompts and user text are never
 * logged or put in exception messages.</li>
 * <li>No retries inside the adapter: failover and retry belong to the router (B5.2).</li>
 * <li>Credentials come from configuration or environment, never from the database.</li>
 * </ul>
 * The default implementations refuse: the registry only routes a feature to a provider whose descriptor declares a
 * capability for it, so calling an undeclared operation is a programming error.
 */
public interface AiProvider {

	/** {@code ai_providers.provider_code}. */
	String code();

	default TranslateResult translate(TranslateRequest request, Duration timeout) {
		throw new UnsupportedOperationException(code() + " does not implement translate");
	}

	default TransformResult llmTransform(TransformRequest request, Duration timeout) {
		throw new UnsupportedOperationException(code() + " does not implement llmTransform");
	}

	default DetectResult detectLanguage(DetectRequest request, Duration timeout) {
		throw new UnsupportedOperationException(code() + " does not implement detectLanguage");
	}

	/** Shared precondition for adapters. */
	static void requirePositive(Duration timeout) {
		if (timeout == null || timeout.isZero() || timeout.isNegative()) {
			throw new IllegalArgumentException("timeout must be positive");
		}
	}
}
