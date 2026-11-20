package com.qalsmartkeyboard.billing.revenuecat;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.qalsmartkeyboard.billing.spi.PaymentEnvironment;

/**
 * {@code qal.billing.revenuecat.*} (ADR 0046). The adapter exists only when {@code secret-api-key} is set.
 *
 * @param secretApiKey         the project's secret API key (v1), sent as {@code Authorization: Bearer} to the REST
 *                             API (env {@code REVENUECAT_SECRET_API_KEY}); never logged or returned
 * @param webhookAuth          the exact {@code Authorization} header value configured for the webhook in the
 *                             RevenueCat dashboard (env {@code REVENUECAT_WEBHOOK_AUTH}); without it every
 *                             notification is refused. Required under prod/production/staging (at least 32
 *                             characters there)
 * @param webhookSigningSecret optional webhook signing secret (env {@code REVENUECAT_WEBHOOK_SIGNING_SECRET}); when set,
 *                             {@code X-RevenueCat-Webhook-Signature} must be valid as well
 * @param signatureTolerance   the largest accepted age of a signed delivery
 * @param environment          which purchases grant: SANDBOX (test purchases) or PRODUCTION; the other kind is
 *                             recorded nowhere and grants nothing (sandbox and production never mix, SD 16.1 rule 3)
 * @param baseUrl              REST API origin; blank = {@code https://api.revenuecat.com}
 */
@ConfigurationProperties("qal.billing.revenuecat")
public record RevenueCatProperties(String secretApiKey, String webhookAuth, String webhookSigningSecret,
		@DefaultValue("5m") Duration signatureTolerance, @DefaultValue("SANDBOX") PaymentEnvironment environment,
		URI baseUrl, @DefaultValue("3s") Duration connectTimeout, @DefaultValue("10s") Duration readTimeout) {

	static final URI DEFAULT_BASE = URI.create("https://api.revenuecat.com");

	public RevenueCatProperties {
		secretApiKey = blankToNull(secretApiKey);
		webhookAuth = blankToNull(webhookAuth);
		webhookSigningSecret = blankToNull(webhookSigningSecret);
		signatureTolerance = positiveOr(signatureTolerance, Duration.ofMinutes(5));
		environment = environment == null ? PaymentEnvironment.SANDBOX : environment;
		connectTimeout = positiveOr(connectTimeout, Duration.ofSeconds(3));
		readTimeout = positiveOr(readTimeout, Duration.ofSeconds(10));
		if (baseUrl != null && baseUrl.toString().isBlank()) {
			baseUrl = null;
		}
	}

	URI effectiveBaseUrl() {
		return baseUrl != null ? baseUrl : DEFAULT_BASE;
	}

	private static Duration positiveOr(Duration d, Duration fallback) {
		return d == null || d.isZero() || d.isNegative() ? fallback : d;
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.strip();
	}

	/** Never prints a secret. */
	@Override
	public String toString() {
		return "RevenueCatProperties[secretApiKey=" + redacted(secretApiKey) + ", webhookAuth=" + redacted(webhookAuth)
				+ ", webhookSigningSecret=" + redacted(webhookSigningSecret) + ", environment=" + environment
				+ ", baseUrl=" + effectiveBaseUrl() + "]";
	}

	private static String redacted(String s) {
		return s == null ? "<unset>" : "<redacted>";
	}
}
