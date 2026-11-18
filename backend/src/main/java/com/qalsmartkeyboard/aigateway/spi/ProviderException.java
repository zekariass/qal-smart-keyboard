package com.qalsmartkeyboard.aigateway.spi;

import java.time.Duration;
import java.util.Objects;

/**
 * Typed provider failures (B5.1, ADR 0014). Adapters throw only these. Messages are fixed text and the cause chain is
 * never a provider response: provider raw errors, bodies, prompts and user text are never carried or logged (SD 19).
 * The pipeline (B5.2/B5.3) decides retry, fallback, circuit-breaker accounting and the contract {@code code}; each
 * {@link Kind} carries the default so the mapping lives in one place.
 */
public abstract sealed class ProviderException extends RuntimeException {

	/**
	 * Failure classes. {@code contractCode} is the {@code Problem.code} the gateway answers with when no fallback
	 * succeeds (null for failures that are not a client-visible outcome); {@code retryable} says a retry on the same
	 * route can help (the pipeline still bounds it); {@code countsAgainstHealth} feeds the circuit breaker (the
	 * provider, not the request, is at fault).
	 */
	public enum Kind {
		UNAVAILABLE("PROVIDER_UNAVAILABLE", true, true),
		TIMEOUT("PROVIDER_TIMEOUT", true, true),
		CONTENT_REJECTED("CONTENT_REJECTED", false, false),
		UNSUPPORTED_LANGUAGE_PAIR("LANGUAGE_PAIR_UNAVAILABLE", false, false),
		QUOTA_EXHAUSTED_AT_PROVIDER("PROVIDER_UNAVAILABLE", false, true),
		AUTHENTICATION_FAILED("PROVIDER_UNAVAILABLE", false, true),
		INVALID_RESPONSE("PROVIDER_UNAVAILABLE", true, true),
		CANCELLED(null, false, false);

		private final String contractCode;
		private final boolean retryable;
		private final boolean countsAgainstHealth;

		Kind(String contractCode, boolean retryable, boolean countsAgainstHealth) {
			this.contractCode = contractCode;
			this.retryable = retryable;
			this.countsAgainstHealth = countsAgainstHealth;
		}

		public String contractCode() {
			return contractCode;
		}

		public boolean retryable() {
			return retryable;
		}

		public boolean countsAgainstHealth() {
			return countsAgainstHealth;
		}
	}

	private final Kind kind;
	private final String providerCode;

	private ProviderException(Kind kind, String providerCode, String message, Throwable cause) {
		super(message, cause);
		this.kind = Objects.requireNonNull(kind);
		this.providerCode = Objects.requireNonNull(providerCode);
	}

	public Kind kind() {
		return kind;
	}

	public String providerCode() {
		return providerCode;
	}

	/** Network failure, connection refused, HTTP 5xx or a misconfigured integration. */
	public static final class Unavailable extends ProviderException {
		public Unavailable(String providerCode, Throwable cause) {
			super(Kind.UNAVAILABLE, providerCode, "provider unavailable", cause);
		}
	}

	/** The call exceeded the caller's deadline. */
	public static final class Timeout extends ProviderException {
		public Timeout(String providerCode, Throwable cause) {
			super(Kind.TIMEOUT, providerCode, "provider timed out", cause);
		}
	}

	/** The provider safety filter or policy refused the content. Not retried, not a provider-health signal. */
	public static final class ContentRejected extends ProviderException {
		public ContentRejected(String providerCode) {
			super(Kind.CONTENT_REJECTED, providerCode, "provider rejected the content", null);
		}
	}

	/** The provider does not serve this language pair (or feature). */
	public static final class UnsupportedLanguagePair extends ProviderException {
		public UnsupportedLanguagePair(String providerCode) {
			super(Kind.UNSUPPORTED_LANGUAGE_PAIR, providerCode, "language pair not supported by provider", null);
		}
	}

	/** Our account at the provider is out of quota or credit (HTTP 429/402 from the vendor): alert ops, route away. */
	public static final class QuotaExhaustedAtProvider extends ProviderException {
		private final Duration retryAfter;

		public QuotaExhaustedAtProvider(String providerCode, Duration retryAfter) {
			super(Kind.QUOTA_EXHAUSTED_AT_PROVIDER, providerCode, "provider quota exhausted", null);
			this.retryAfter = retryAfter;
		}

		/** The vendor Retry-After hint, or null. */
		public Duration retryAfter() {
			return retryAfter;
		}
	}

	/** Our credential was refused (HTTP 401/403). Misconfiguration: page someone, never retry. */
	public static final class AuthenticationFailed extends ProviderException {
		public AuthenticationFailed(String providerCode) {
			super(Kind.AUTHENTICATION_FAILED, providerCode, "provider credentials rejected", null);
		}
	}

	/** The answer was malformed, incomplete or truncated, so it cannot be settled or shown. */
	public static final class InvalidResponse extends ProviderException {
		public InvalidResponse(String providerCode, Throwable cause) {
			super(Kind.INVALID_RESPONSE, providerCode, "provider response invalid", cause);
		}
	}

	/** The calling thread was interrupted (client went away, shutdown). The interrupt flag is restored. */
	public static final class Cancelled extends ProviderException {
		public Cancelled(String providerCode) {
			super(Kind.CANCELLED, providerCode, "provider call cancelled", null);
		}
	}
}
