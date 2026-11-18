package com.qalsmartkeyboard.notifications.sms;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.qalsmartkeyboard.notifications.sms.SmsDeliveryException.Reason;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Production SMS adapter for SMS Ethiopia ({@code qal.notifications.sms.provider=sms-ethiopia}, ADR 0007 D48, ADR 0025;
 * vendor summary {@code docs/vendors/sms-ethiopia-api.md}). One call per message:
 * {@code POST /api/v2/sms/send} with header {@code KEY} and {@code {"msisdn":"2519XXXXXXXX","text":"..."}}.
 * <ul>
 * <li>RestClient on a JDK {@link HttpClient} (HTTP/1.1, no redirects), connect and read timeouts from configuration.
 * No retry: a failure surfaces as {@link SmsDeliveryException} (like {@link SmsOtpSender}, which does not retry
 * either). After a 429 with a numeric {@code Retry-After} the adapter refuses sends locally until it elapses (capped at
 * {@link #MAX_RETRY_AFTER}) so that we do not keep hitting an account-wide limit.</li>
 * <li>Only {@code +2519XXXXXXXX} (Ethio Telecom mobile) is sent; anything else is refused locally.</li>
 * <li>400 bodies are read (bounded) only to extract {@code Error Code :: n}; the text is never logged because it can
 * echo the number. Nothing logged here contains the number, the text or the key: only the outcome, the vendor error
 * code, the HTTP status, the segment count and the vendor message id (a ULID, not personal data).</li>
 * <li>Counts {@code qal.notifications.sms.provider.responses{provider,outcome,code,alert}}.</li>
 * </ul>
 */
final class SmsEthiopiaProvider implements SmsProvider {

	static final String ID = "sms-ethiopia";
	static final String SEND_PATH = "/api/v2/sms/send";
	static final String KEY_HEADER = "KEY";
	static final String METER = "qal.notifications.sms.provider.responses";
	static final Duration MAX_RETRY_AFTER = Duration.ofMinutes(5);

	private static final Logger log = LoggerFactory.getLogger(SmsEthiopiaProvider.class);
	private static final Pattern E164_ETHIO_TELECOM = Pattern.compile("\\+2519\\d{8}");
	private static final Pattern ERROR_CODE = Pattern.compile("Error Code\\s*::\\s*(\\d+)");
	private static final Set<String> KNOWN_CODES = Set.of("10000", "10001", "10002", "10005", "10006", "10007");
	private static final int MAX_BODY_BYTES = 16 * 1024;
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * @param baseUrl        vendor origin, e.g. {@code https://smsethiopia.com/}
	 * @param apiKey         campaign API key sent as header {@code KEY}; redacted in toString
	 * @param connectTimeout TCP/TLS connect timeout
	 * @param readTimeout    response timeout
	 */
	record Config(URI baseUrl, String apiKey, Duration connectTimeout, Duration readTimeout) {

		Config {
			Objects.requireNonNull(baseUrl, "baseUrl");
			if (apiKey == null || apiKey.isBlank()) {
				throw new IllegalStateException("qal.notifications.sms.sms-ethiopia.api-key is not set "
						+ "(env QAL_SMS_ETHIOPIA_API_KEY); it is required when qal.notifications.sms.provider=sms-ethiopia");
			}
			apiKey = apiKey.strip();
			connectTimeout = positiveOr(connectTimeout, Duration.ofSeconds(2));
			readTimeout = positiveOr(readTimeout, Duration.ofSeconds(5));
		}

		private static Duration positiveOr(Duration d, Duration fallback) {
			return d == null || d.isZero() || d.isNegative() ? fallback : d;
		}

		@Override
		public String toString() {
			return "Config[baseUrl=" + baseUrl + ", apiKey=<redacted>, connectTimeout=" + connectTimeout
					+ ", readTimeout=" + readTimeout + "]";
		}
	}

	private record Accepted(String id, int segments) {
	}

	private final Config config;
	private final MeterRegistry meters;
	private final Clock clock;
	private final RestClient client;
	private volatile Instant blockedUntil;

	SmsEthiopiaProvider(Config config, MeterRegistry meters) {
		this(config, meters, Clock.systemUTC());
	}

	SmsEthiopiaProvider(Config config, MeterRegistry meters, Clock clock) {
		this.config = Objects.requireNonNull(config, "config");
		this.meters = meters;
		this.clock = Objects.requireNonNull(clock, "clock");
		HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
				.followRedirects(HttpClient.Redirect.NEVER).connectTimeout(config.connectTimeout()).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
		factory.setReadTimeout(config.readTimeout());
		String base = config.baseUrl().toString();
		while (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		this.client = RestClient.builder().requestFactory(factory).baseUrl(base).build();
	}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String toString() {
		return "SmsEthiopiaProvider[" + config + "]";
	}

	@Override
	public void send(String toE164, String body) {
		if (toE164 == null || !E164_ETHIO_TELECOM.matcher(toE164).matches()) {
			throw failure(Reason.INVALID_NUMBER, "local_reject", 0, null);
		}
		if (body == null || body.isBlank()) {
			throw failure(Reason.INTEGRATION_ERROR, "local_reject", 0, null);
		}
		Instant until = blockedUntil;
		if (until != null) {
			Duration left = Duration.between(clock.instant(), until);
			if (left.isPositive()) {
				throw failure(Reason.RATE_LIMITED, "local_backoff", 0, left);
			}
			blockedUntil = null;
		}
		Map<String, String> request = new LinkedHashMap<>();
		request.put("msisdn", toE164.substring(1));
		request.put("text", body);
		Accepted accepted;
		try {
			byte[] json = JSON.writeValueAsBytes(request);
			accepted = client.post().uri(SEND_PATH).contentType(MediaType.APPLICATION_JSON)
					.accept(MediaType.APPLICATION_JSON).header(KEY_HEADER, config.apiKey()).body(json)
					.exchange((req, res) -> handle(res));
		}
		catch (SmsDeliveryException e) {
			throw e;
		}
		catch (RuntimeException e) {
			throw transportFailure(e);
		}
		count("accepted", "none", false);
		if (accepted.segments() > 1) {
			log.warn("SMS Ethiopia accepted message {} as {} segments (each is charged)", accepted.id(),
					accepted.segments());
		}
		else {
			log.info("SMS Ethiopia accepted message {}", accepted.id());
		}
	}

	// ------------------------------------------------------------------ responses

	private Accepted handle(ClientHttpResponse res) throws IOException {
		int status = res.getStatusCode().value();
		if (status >= 200 && status < 300) {
			return accepted(res, status);
		}
		if (status == 400) {
			String raw = readBounded(res.getBody());
			Matcher m = ERROR_CODE.matcher(raw);
			if (m.find()) {
				String code = m.group(1);
				throw failure(reasonFor(code), KNOWN_CODES.contains(code) ? code : "unknown", status, null);
			}
			// {"msisdn": "...", "text": "..."}: a missing/empty field, i.e. our request is wrong
			throw failure(Reason.INTEGRATION_ERROR, raw.contains("\"error_message\"") ? "unknown" : "validation",
					status, null);
		}
		if (status == 401 || status == 403) {
			throw failure(Reason.AUTHENTICATION_FAILED, "http_" + status, status, null);
		}
		if (status == 429) {
			Duration retryAfter = seconds(res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
			if (retryAfter != null) {
				Duration honoured = retryAfter.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : retryAfter;
				blockedUntil = clock.instant().plus(honoured);
			}
			throw failure(Reason.RATE_LIMITED, "http_429", status, retryAfter);
		}
		if (status >= 500) {
			throw failure(Reason.UNAVAILABLE, "http_5xx", status, null);
		}
		// 3xx (redirects are not followed: wrong base URL), 404 (wrong path) or another 4xx: our integration is wrong
		throw failure(Reason.INTEGRATION_ERROR, status == 404 ? "http_404" : "http_" + (status / 100) + "xx", status,
				null);
	}

	private Accepted accepted(ClientHttpResponse res, int status) throws IOException {
		JsonNode json;
		try {
			json = JSON.readTree(readBounded(res.getBody()));
		}
		catch (RuntimeException e) {
			throw failure(Reason.INVALID_RESPONSE, "malformed_body", status, null);
		}
		if (json == null || !json.isObject()) {
			throw failure(Reason.INVALID_RESPONSE, "malformed_body", status, null);
		}
		JsonNode id = json.get("id");
		JsonNode state = json.get("status");
		if (state == null || !state.isString() || !"ACCEPTED".equals(state.asString().toUpperCase(Locale.ROOT))) {
			throw failure(Reason.INVALID_RESPONSE, "not_accepted", status, null);
		}
		String messageId = id != null && id.isString() && !id.asString().isBlank() ? id.asString() : "<none>";
		JsonNode segments = json.get("segments");
		return new Accepted(messageId, segments != null && segments.isNumber() ? segments.asInt() : 1);
	}

	private static Reason reasonFor(String code) {
		return switch (code) {
			case "10000" -> Reason.INVALID_NUMBER;
			case "10002", "10006" -> Reason.PROVIDER_MISCONFIGURED;
			case "10005" -> Reason.INSUFFICIENT_BALANCE;
			case "10007" -> Reason.RECIPIENT_NOT_WHITELISTED;
			default -> Reason.INTEGRATION_ERROR; // 10001 empty message, or a code we do not know
		};
	}

	/** Classifies by walking the cause chain; the original exception is dropped so no URL or body detail escapes. */
	private SmsDeliveryException transportFailure(RuntimeException e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof InterruptedException) {
				Thread.currentThread().interrupt();
				return failure(Reason.UNAVAILABLE, "interrupted", 0, null);
			}
			if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
				return failure(Reason.UNAVAILABLE, "timeout", 0, null);
			}
		}
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof IOException) {
				return failure(Reason.UNAVAILABLE, "io_error", 0, null);
			}
		}
		return failure(Reason.INVALID_RESPONSE, "client_error", 0, null);
	}

	// ------------------------------------------------------------------ logging and metrics

	private SmsDeliveryException failure(Reason reason, String code, int httpStatus, Duration retryAfter) {
		count(reason.tag(), code, reason.alert());
		String status = httpStatus == 0 ? "none" : Integer.toString(httpStatus);
		switch (reason) {
			case INSUFFICIENT_BALANCE -> log.error("SMS Ethiopia: INSUFFICIENT SMS BALANCE (vendor error code {}): top up "
					+ "the campaign's SMS package; no SMS can be sent until then", code);
			case PROVIDER_MISCONFIGURED -> log.error("SMS Ethiopia: campaign not usable (vendor error code {}: 10002 = "
					+ "campaign not active, 10006 = sender ID not approved yet); check the SMS Ethiopia console", code);
			case AUTHENTICATION_FAILED -> log.error("SMS Ethiopia: API key refused (HTTP {}); check that "
					+ "QAL_SMS_ETHIOPIA_API_KEY is valid, not expired and not deactivated", status);
			case INTEGRATION_ERROR, INVALID_RESPONSE -> log.error(
					"SMS Ethiopia: request or response not as documented ({}, code {}, HTTP {}); integration bug or API change",
					reason.tag(), code, status);
			case RATE_LIMITED -> log.warn("SMS Ethiopia: rate limited (code {}, retry after {} s)", code,
					retryAfter == null ? "unknown" : retryAfter.toSeconds());
			default -> log.warn("SMS Ethiopia did not accept a message: {} (code {}, HTTP {})", reason.tag(), code,
					status);
		}
		return new SmsDeliveryException(ID, reason, retryAfter);
	}

	private void count(String outcome, String code, boolean alert) {
		if (meters != null) {
			meters.counter(METER, "provider", ID, "outcome", outcome, "code", code, "alert", Boolean.toString(alert))
					.increment();
		}
	}

	// ------------------------------------------------------------------ helpers

	private static String readBounded(InputStream in) throws IOException {
		if (in == null) {
			return "";
		}
		try (in) {
			return new String(in.readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8);
		}
	}

	private static Duration seconds(String retryAfter) {
		try {
			if (retryAfter == null) {
				return null;
			}
			long s = Long.parseLong(retryAfter.trim());
			return s < 0 ? null : Duration.ofSeconds(s);
		}
		catch (NumberFormatException e) {
			return null;
		}
	}
}
