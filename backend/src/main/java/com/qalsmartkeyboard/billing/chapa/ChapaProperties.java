package com.qalsmartkeyboard.billing.chapa;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.qalsmartkeyboard.billing.spi.PaymentEnvironment;

/**
 * {@code qal.billing.chapa.*} (ADR 0045 step A2). The adapter exists only when {@code secret-key} is set.
 *
 * @param secretKey                  the merchant secret key, sent as {@code Authorization: Bearer} (env
 *                                   {@code CHAPA_SECRET_KEY}); a test key ({@code CHASECK_TEST-...}) serves SANDBOX
 *                                   accounts, a live key ({@code CHASECK-...}) PRODUCTION ones. Never logged or returned
 * @param webhookSecret              the "secret hash" set in Chapa's dashboard webhook settings (env
 *                                   {@code CHAPA_WEBHOOK_SECRET}); without it every webhook is refused (401). Required
 *                                   under the prod/production/staging profiles
 * @param environment                which merchant accounts the key serves; Chapa uses one host for both modes, the key
 *                                   decides (a test key cannot verify a live payment and vice versa)
 * @param baseUrl                    API origin; blank = {@code https://api.chapa.co}
 * @param title                      {@code customization[title]} on the hosted page (kept short and plain: Chapa limits
 *                                   it, the exact limit is not documented)
 * @param acceptSecretHashSignature  accept the documented {@code chapa-signature} header, an HMAC of the secret by
 *                                   itself (a constant shared token), besides the payload HMAC in
 *                                   {@code x-chapa-signature}. On by default because Chapa documents it; switch off once
 *                                   live traffic shows {@code x-chapa-signature} on every event. A webhook is only a
 *                                   trigger to verify either way
 */
@ConfigurationProperties("qal.billing.chapa")
public record ChapaProperties(String secretKey, String webhookSecret,
		@DefaultValue("SANDBOX") PaymentEnvironment environment, URI baseUrl,
		@DefaultValue("3s") Duration connectTimeout, @DefaultValue("10s") Duration readTimeout,
		@DefaultValue("Qal Keyboard") String title, @DefaultValue("true") boolean acceptSecretHashSignature) {

	static final URI DEFAULT_BASE = URI.create("https://api.chapa.co");

	public ChapaProperties {
		secretKey = blankToNull(secretKey);
		webhookSecret = blankToNull(webhookSecret);
		environment = environment == null ? PaymentEnvironment.SANDBOX : environment;
		connectTimeout = positiveOr(connectTimeout, Duration.ofSeconds(3));
		readTimeout = positiveOr(readTimeout, Duration.ofSeconds(10));
		title = title == null || title.isBlank() ? "Qal Keyboard" : title.strip();
		if (baseUrl != null && baseUrl.toString().isBlank()) {
			baseUrl = null;
		}
	}

	URI effectiveBaseUrl() {
		return baseUrl != null ? baseUrl : DEFAULT_BASE;
	}

	/** Chapa test secret keys carry {@code _TEST} in their prefix ({@code CHASECK_TEST-...}). */
	boolean testKey() {
		return secretKey != null && secretKey.toUpperCase(java.util.Locale.ROOT).startsWith("CHASECK_TEST");
	}

	private static Duration positiveOr(Duration d, Duration fallback) {
		return d == null || d.isZero() || d.isNegative() ? fallback : d;
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.strip();
	}

	/** Never prints the key or the webhook secret. */
	@Override
	public String toString() {
		return "ChapaProperties[secretKey=" + (secretKey == null ? "<unset>" : "<redacted>") + ", environment="
				+ environment + ", baseUrl=" + effectiveBaseUrl() + ", webhookSecret="
				+ (webhookSecret == null ? "<unset>" : "<redacted>") + "]";
	}
}
