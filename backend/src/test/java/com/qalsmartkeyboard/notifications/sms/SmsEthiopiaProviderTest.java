package com.qalsmartkeyboard.notifications.sms;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.ConsoleNotifier;
import com.qalsmartkeyboard.notifications.sms.SmsDeliveryException.Reason;
import com.qalsmartkeyboard.testsupport.logging.CapturedLogs;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * ADR 0025: SMS Ethiopia v2 adapter. Part one talks to an in-process WireMock serving the compose stubs
 * ({@code infra/compose/wiremock/mappings/sms-ethiopia-*.json}); part two uses a bare WireMock to force each outcome.
 * Never calls smsethiopia.com.
 */
class SmsEthiopiaProviderTest {

	static final String KEY = "sms-eth-test-key-do-not-log-7Qx9";
	static final String PHONE = "+251911234567";
	static final String MSISDN = "251911234567";
	static final String TEXT = "Qal: your sign-in code is 482913. It expires in 5 minutes. Never share this code.";

	static WireMockServer compose;
	static WireMockServer bare;

	SimpleMeterRegistry meters;
	MutableClock clock;

	@BeforeAll
	static void start() {
		Path dir = Path.of("..", "infra", "compose", "wiremock").toAbsolutePath().normalize();
		compose = new WireMockServer(options().dynamicPort().usingFilesUnderDirectory(dir.toString())
				.globalTemplating(true).notifier(new ConsoleNotifier(false)));
		compose.start();
		bare = new WireMockServer(options().dynamicPort().notifier(new ConsoleNotifier(false)));
		bare.start();
	}

	@AfterAll
	static void stop() {
		compose.stop();
		bare.stop();
	}

	@BeforeEach
	void reset() {
		bare.resetAll();
		compose.resetRequests();
		meters = new SimpleMeterRegistry();
		clock = new MutableClock(Instant.parse("2026-11-18T10:00:00Z"));
	}

	SmsEthiopiaProvider provider(WireMockServer server) {
		return provider(URI.create(server.baseUrl() + "/"), Duration.ofSeconds(2));
	}

	SmsEthiopiaProvider provider(URI baseUrl, Duration readTimeout) {
		return new SmsEthiopiaProvider(
				new SmsEthiopiaProvider.Config(baseUrl, KEY, Duration.ofMillis(500), readTimeout), meters, clock);
	}

	double count(String outcome, String code) {
		var counter = meters.find(SmsEthiopiaProvider.METER).tags("provider", "sms-ethiopia", "outcome", outcome, "code",
				code).counter();
		return counter == null ? 0 : counter.count();
	}

	static void stub(ResponseDefinitionBuilder response) {
		bare.stubFor(post(urlPathEqualTo(SmsEthiopiaProvider.SEND_PATH)).willReturn(response));
	}

	static ResponseDefinitionBuilder json(int status, String body) {
		return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
	}

	SmsDeliveryException failure(SmsEthiopiaProvider provider) {
		return catchThrowableOfType(SmsDeliveryException.class, () -> provider.send(PHONE, TEXT));
	}

	// ------------------------------------------------------------------ compose stubs

	@Test
	void sendsToTheComposeStubWithKeyHeaderAndMsisdnWithoutPlus() {
		try (CapturedLogs logs = new CapturedLogs()) {
			provider(compose).send(PHONE, TEXT);
			assertThat(logs.texts()).anyMatch(t -> t.startsWith("SMS Ethiopia accepted message ") && t.length() > 40);
		}
		compose.verify(1, postRequestedFor(urlPathEqualTo("/api/v2/sms/send")).withHeader("KEY", equalTo(KEY))
				.withHeader("Authorization", absent()).withHeader("API_KEY", absent())
				.withHeader("Content-Type", equalTo("application/json"))
				.withRequestBody(equalToJson("{\"msisdn\":\"" + MSISDN + "\",\"text\":\"" + TEXT + "\"}")));
		assertThat(count("accepted", "none")).isEqualTo(1.0);
	}

	@Test
	void composeStubMagicNumbersDriveTheErrorOutcomes() {
		SmsEthiopiaProvider p = provider(compose);
		assertThat(catchThrowableOfType(SmsDeliveryException.class, () -> p.send("+251900010005", TEXT)).reason())
				.isEqualTo(Reason.INSUFFICIENT_BALANCE);
		assertThat(catchThrowableOfType(SmsDeliveryException.class, () -> p.send("+251900010007", TEXT)).reason())
				.isEqualTo(Reason.RECIPIENT_NOT_WHITELISTED);
		assertThat(catchThrowableOfType(SmsDeliveryException.class, () -> p.send("+251900000500", TEXT)).reason())
				.isEqualTo(Reason.UNAVAILABLE);
		SmsDeliveryException limited = catchThrowableOfType(SmsDeliveryException.class,
				() -> p.send("+251900000429", TEXT));
		assertThat(limited.reason()).isEqualTo(Reason.RATE_LIMITED);
		assertThat(limited.retryAfter()).contains(Duration.ofSeconds(30));
	}

	// ------------------------------------------------------------------ success

	@Test
	void acceptedResponseIsToleratedWithoutJsonContentTypeAndLogsSegments() {
		stub(aResponse().withStatus(200).withHeader("Content-Type", "text/plain")
				.withBody("{\"id\":\"01JZX5M8Q3T5V0X8YW9RCB2K7D\",\"segments\":2,\"status\":\"ACCEPTED\"}"));
		try (CapturedLogs logs = new CapturedLogs()) {
			provider(bare).send(PHONE, TEXT);
			assertThat(logs.texts()).anyMatch(t -> t.contains("01JZX5M8Q3T5V0X8YW9RCB2K7D") && t.contains("2 segments"));
		}
		assertThat(count("accepted", "none")).isEqualTo(1.0);
	}

	// ------------------------------------------------------------------ vendor error codes

	@ParameterizedTest
	@CsvSource({ "10000, INVALID_NUMBER, false", "10001, INTEGRATION_ERROR, true",
			"10002, PROVIDER_MISCONFIGURED, true", "10005, INSUFFICIENT_BALANCE, true",
			"10006, PROVIDER_MISCONFIGURED, true", "10007, RECIPIENT_NOT_WHITELISTED, false" })
	void vendorErrorCodesMapToTypedReasons(String code, Reason reason, boolean alert) {
		// the vendor text may echo the number: it must never reach the logs or the exception
		stub(json(400, "{\"error_message\":\"Something about " + MSISDN + " failed. Error Code :: " + code + "\"}"));
		try (CapturedLogs logs = new CapturedLogs()) {
			SmsDeliveryException e = failure(provider(bare));
			assertThat(e.reason()).isEqualTo(reason);
			assertThat(e.reason().alert()).isEqualTo(alert);
			assertThat(e).hasNoCause().hasMessageNotContaining(MSISDN).hasMessageNotContaining("Something");
			assertThat(logs.texts()).isNotEmpty().anyMatch(t -> t.contains(code))
					.noneMatch(t -> t.contains("911234567") || t.contains("Something") || t.contains("482913"));
		}
		assertThat(count(reason.tag(), code)).isEqualTo(1.0);
		assertThat(meters.find(SmsEthiopiaProvider.METER).tag("alert", Boolean.toString(alert)).counter()).isNotNull();
	}

	@Test
	void insufficientBalanceIsAnErrorLogThatOperatorsCannotMiss() {
		stub(json(400, "{\"error_message\":\"Insufficient balance. Error Code :: 10005\"}"));
		try (CapturedLogs logs = new CapturedLogs()) {
			failure(provider(bare));
			assertThat(logs.texts()).anyMatch(t -> t.contains("INSUFFICIENT SMS BALANCE") && t.contains("10005"));
		}
		assertThat(meters.find(SmsEthiopiaProvider.METER).tags("outcome", "insufficient_balance", "alert", "true")
				.counter().count()).isEqualTo(1.0);
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"error_message\":\"x Error Code::10005\"}",
			"{\"error_message\":\"x Error Code ::  10005 \"}", "Error Code :: 10005" })
	void errorCodeParsingIsTolerant(String body) {
		stub(json(400, body));
		assertThat(failure(provider(bare)).reason()).isEqualTo(Reason.INSUFFICIENT_BALANCE);
	}

	@Test
	void unknownErrorCodeIsAnIntegrationErrorWithABoundedTag() {
		stub(json(400, "{\"error_message\":\"New thing. Error Code :: 10099\"}"));
		assertThat(failure(provider(bare)).reason()).isEqualTo(Reason.INTEGRATION_ERROR);
		assertThat(count("integration_error", "unknown")).isEqualTo(1.0);
	}

	@Test
	void fieldKeyedValidationErrorIsOurBug() {
		stub(json(400, "{\"msisdn\":\"msisdn is mandatory\",\"text\":\"text is mandatory\"}"));
		assertThat(failure(provider(bare)).reason()).isEqualTo(Reason.INTEGRATION_ERROR);
		assertThat(count("integration_error", "validation")).isEqualTo(1.0);
	}

	// ------------------------------------------------------------------ HTTP status

	@ParameterizedTest
	@CsvSource({ "401, AUTHENTICATION_FAILED, http_401", "403, AUTHENTICATION_FAILED, http_403",
			"404, INTEGRATION_ERROR, http_404", "405, INTEGRATION_ERROR, http_4xx", "500, UNAVAILABLE, http_5xx",
			"502, UNAVAILABLE, http_5xx", "503, UNAVAILABLE, http_5xx" })
	void httpStatusMapping(int status, Reason reason, String code) {
		stub(json(status, "{\"error_message\":\"echo " + MSISDN + "\"}"));
		try (CapturedLogs logs = new CapturedLogs()) {
			assertThat(failure(provider(bare)).reason()).isEqualTo(reason);
			assertThat(logs.texts()).noneMatch(t -> t.contains("911234567") || t.contains(KEY));
		}
		assertThat(count(reason.tag(), code)).isEqualTo(1.0);
	}

	@Test
	void redirectsAreNotFollowed() {
		stub(aResponse().withStatus(301).withHeader("Location", bare.baseUrl() + "/elsewhere"));
		assertThat(failure(provider(bare)).reason()).isEqualTo(Reason.INTEGRATION_ERROR);
		bare.verify(0, postRequestedFor(urlPathEqualTo("/elsewhere")));
		assertThat(count("integration_error", "http_3xx")).isEqualTo(1.0);
	}

	@Test
	void rateLimitWithRetryAfterIsHonouredLocallyWithoutRetrying() {
		stub(json(429, "{}").withHeader("Retry-After", "30"));
		SmsEthiopiaProvider p = provider(bare);
		SmsDeliveryException first = failure(p);
		assertThat(first.reason()).isEqualTo(Reason.RATE_LIMITED);
		assertThat(first.retryAfter()).contains(Duration.ofSeconds(30));
		bare.verify(1, postRequestedFor(anyUrl())); // no retry inside the adapter

		clock.advance(Duration.ofSeconds(10));
		SmsDeliveryException blocked = failure(p);
		assertThat(blocked.reason()).isEqualTo(Reason.RATE_LIMITED);
		assertThat(blocked.retryAfter()).contains(Duration.ofSeconds(20));
		bare.verify(1, postRequestedFor(anyUrl())); // refused locally during the window
		assertThat(count("rate_limited", "local_backoff")).isEqualTo(1.0);

		clock.advance(Duration.ofSeconds(21));
		stub(json(200, "{\"id\":\"01JZX5M8Q3T5V0X8YW9RCB2K7D\",\"segments\":1,\"status\":\"ACCEPTED\"}"));
		p.send(PHONE, TEXT);
		bare.verify(2, postRequestedFor(anyUrl()));
	}

	@Test
	void rateLimitWithoutRetryAfterDoesNotBlockTheNextSend() {
		stub(json(429, "{}"));
		SmsEthiopiaProvider p = provider(bare);
		assertThat(failure(p).retryAfter()).isEmpty();
		failure(p);
		bare.verify(2, postRequestedFor(anyUrl()));
		assertThat(count("rate_limited", "http_429")).isEqualTo(2.0);
	}

	@Test
	void hugeRetryAfterIsCapped() {
		stub(json(429, "{}").withHeader("Retry-After", "86400"));
		SmsEthiopiaProvider p = provider(bare);
		failure(p);
		clock.advance(SmsEthiopiaProvider.MAX_RETRY_AFTER.plusSeconds(1));
		failure(p);
		bare.verify(2, postRequestedFor(anyUrl()));
	}

	// ------------------------------------------------------------------ transport and decoding

	@Test
	void timeoutIsUnavailableWithinTheReadTimeout() {
		stub(json(200, "{\"id\":\"x\",\"status\":\"ACCEPTED\"}").withFixedDelay(3000));
		long start = System.nanoTime();
		SmsDeliveryException e = catchThrowableOfType(SmsDeliveryException.class,
				() -> provider(URI.create(bare.baseUrl()), Duration.ofMillis(300)).send(PHONE, TEXT));
		assertThat(e.reason()).isEqualTo(Reason.UNAVAILABLE);
		assertThat(e).hasNoCause();
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
		assertThat(count("unavailable", "timeout")).isEqualTo(1.0);
	}

	@Test
	void connectionRefusedIsUnavailable() throws Exception {
		int port;
		try (ServerSocket s = new ServerSocket(0)) {
			port = s.getLocalPort();
		}
		SmsDeliveryException e = catchThrowableOfType(SmsDeliveryException.class,
				() -> provider(URI.create("http://127.0.0.1:" + port + "/"), Duration.ofSeconds(1)).send(PHONE, TEXT));
		assertThat(e.reason()).isEqualTo(Reason.UNAVAILABLE);
		assertThat(e).hasNoCause().hasMessageNotContaining("127.0.0.1");
		assertThat(count("unavailable", "io_error")).isEqualTo(1.0);
	}

	@ParameterizedTest
	@ValueSource(strings = { "not json", "{\"id\":", "[]", "{\"id\":\"01J\",\"status\":\"REJECTED\"}", "{}", "" })
	void unreadableOrNotAcceptedSuccessBodyIsAnInvalidResponse(String body) {
		stub(json(200, body));
		assertThat(failure(provider(bare)).reason()).isEqualTo(Reason.INVALID_RESPONSE);
	}

	// ------------------------------------------------------------------ local checks

	@ParameterizedTest
	@ValueSource(strings = { "+251711234567", "+25191123456", "+2519112345678", "251911234567", "+14155550100",
			"+251 911234567", "" })
	void numbersOutsideEthioTelecomAreRefusedLocally(String number) {
		stub(json(200, "{\"id\":\"x\",\"status\":\"ACCEPTED\"}"));
		SmsDeliveryException e = catchThrowableOfType(SmsDeliveryException.class,
				() -> provider(bare).send(number, TEXT));
		assertThat(e.reason()).isEqualTo(Reason.INVALID_NUMBER);
		bare.verify(0, anyRequestedFor(anyUrl()));
		assertThat(count("invalid_number", "local_reject")).isEqualTo(1.0);
	}

	@Test
	void nullNumberAndBlankTextAreRefusedLocally() {
		SmsEthiopiaProvider p = provider(bare);
		assertThat(catchThrowableOfType(SmsDeliveryException.class, () -> p.send(null, TEXT)).reason())
				.isEqualTo(Reason.INVALID_NUMBER);
		assertThat(catchThrowableOfType(SmsDeliveryException.class, () -> p.send(PHONE, " ")).reason())
				.isEqualTo(Reason.INTEGRATION_ERROR);
		bare.verify(0, anyRequestedFor(anyUrl()));
	}

	// ------------------------------------------------------------------ secrets

	@Test
	void theKeyNeverAppearsInToStringExceptionsOrLogs() {
		SmsEthiopiaProvider p = provider(bare);
		assertThat(p.toString()).doesNotContain(KEY).contains("<redacted>");
		assertThat(new SmsProperties.SmsEthiopia(KEY, URI.create("https://smsethiopia.com/"), null, null).toString())
				.doesNotContain(KEY).contains("<redacted>");
		List<Integer> statuses = List.of(200, 400, 401, 404, 429, 500);
		try (CapturedLogs logs = new CapturedLogs()) {
			for (int status : statuses) {
				bare.resetAll();
				stub(json(status, status == 200 ? "{\"id\":\"01J\",\"status\":\"ACCEPTED\"}"
						: "{\"error_message\":\"bad key " + KEY + " Error Code :: 10002\"}"));
				try {
					p.send(PHONE, TEXT);
				}
				catch (SmsDeliveryException e) {
					assertThat(e.toString()).doesNotContain(KEY).doesNotContain(MSISDN);
					assertThat(e).hasNoCause();
				}
			}
			assertThat(logs.texts()).isNotEmpty()
					.noneMatch(t -> t.contains(KEY) || t.contains("911234567") || t.contains("482913"));
		}
	}

	@Test
	void blankKeyIsRefused() {
		assertThatThrownBy(() -> new SmsEthiopiaProvider.Config(URI.create("https://smsethiopia.com/"), " ", null, null))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("QAL_SMS_ETHIOPIA_API_KEY");
	}

	static final class MutableClock extends Clock {

		private Instant now;

		MutableClock(Instant now) {
			this.now = now;
		}

		void advance(Duration d) {
			now = now.plus(d);
		}

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}
}
