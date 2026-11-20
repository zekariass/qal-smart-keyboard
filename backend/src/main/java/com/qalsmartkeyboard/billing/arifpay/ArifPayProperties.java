package com.qalsmartkeyboard.billing.arifpay;

import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.qalsmartkeyboard.billing.spi.PaymentEnvironment;

/**
 * {@code qal.billing.arifpay.*} (ADR 0045). The adapter exists only when {@code api-key} is set.
 *
 * @param apiKey        the merchant API key, sent as {@code x-arifpay-key} (env {@code ARIFPAY_SECRET_KEY}); never
 *                      logged, never returned
 * @param environment   which merchant accounts this key serves (a sandbox key only works on the sandbox routes)
 * @param baseUrl       gateway origin; blank = {@code https://gateway.arifpay.org} (SANDBOX) or
 *                      {@code https://gateway.arifpay.net} (PRODUCTION; confirm the host with ArifPay, OI-PAY-1)
 * @param webhookSecret key of the per-attempt notify-URL token (ArifPay does not sign notifications); required in
 *                      prod/production/staging (at least 32 characters), a random per-JVM key elsewhere
 * @param createPath    create checkout session; blank = {@code /api/sandbox/checkout/session} or {@code /api/checkout/session}
 * @param sessionPath   get checkout session (the verification call), {@code {sessionId}} placeholder; blank = the
 *                      sandbox or production route
 * @param noncePath     look a session up by our nonce when the create answer was lost, {@code {nonce}} placeholder;
 *                      blank = production {@code /api/checkout/getSessionByNonce/{nonce}}, none in the sandbox (the
 *                      sandbox gateway has no such route)
 * @param timeZone      zone of the {@code expireDate} wall-clock time sent to ArifPay (ArifPay documents no zone;
 *                      Addis Ababa is the safe reading: never earlier than intended)
 * @param lang          checkout page language ({@code EN}; other values to confirm with ArifPay)
 */
@ConfigurationProperties("qal.billing.arifpay")
public record ArifPayProperties(String apiKey, @DefaultValue("SANDBOX") PaymentEnvironment environment, URI baseUrl,
		String webhookSecret, @DefaultValue("3s") Duration connectTimeout, @DefaultValue("10s") Duration readTimeout,
		String createPath, String sessionPath, String noncePath, @DefaultValue("Africa/Addis_Ababa") ZoneId timeZone,
		@DefaultValue("EN") String lang) {

	static final URI SANDBOX_BASE = URI.create("https://gateway.arifpay.org");
	static final URI PRODUCTION_BASE = URI.create("https://gateway.arifpay.net");

	public ArifPayProperties {
		apiKey = blankToNull(apiKey);
		webhookSecret = blankToNull(webhookSecret);
		createPath = blankToNull(createPath);
		sessionPath = blankToNull(sessionPath);
		noncePath = blankToNull(noncePath);
		environment = environment == null ? PaymentEnvironment.SANDBOX : environment;
		connectTimeout = positiveOr(connectTimeout, Duration.ofSeconds(3));
		readTimeout = positiveOr(readTimeout, Duration.ofSeconds(10));
		timeZone = timeZone == null ? ZoneId.of("Africa/Addis_Ababa") : timeZone;
		lang = lang == null || lang.isBlank() ? "EN" : lang.strip();
		if (baseUrl != null && baseUrl.toString().isBlank()) {
			baseUrl = null;
		}
	}

	boolean sandbox() {
		return environment == PaymentEnvironment.SANDBOX;
	}

	URI effectiveBaseUrl() {
		return baseUrl != null ? baseUrl : sandbox() ? SANDBOX_BASE : PRODUCTION_BASE;
	}

	String effectiveCreatePath() {
		return createPath != null ? createPath : sandbox() ? "/api/sandbox/checkout/session" : "/api/checkout/session";
	}

	String effectiveSessionPath() {
		return sessionPath != null ? sessionPath
				: sandbox() ? "/api/sandbox/checkout/session/{sessionId}" : "/api/checkout/session/{sessionId}";
	}

	/** Null when lookup by nonce is not available. */
	String effectiveNoncePath() {
		return noncePath != null ? noncePath : sandbox() ? null : "/api/checkout/getSessionByNonce/{nonce}";
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
		return "ArifPayProperties[apiKey=" + (apiKey == null ? "<unset>" : "<redacted>") + ", environment="
				+ environment + ", baseUrl=" + effectiveBaseUrl() + ", webhookSecret="
				+ (webhookSecret == null ? "<unset>" : "<redacted>") + "]";
	}
}
