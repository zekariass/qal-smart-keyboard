package com.qalsmartkeyboard.billing.arifpay;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.common.ConsoleNotifier;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.qalsmartkeyboard.billing.spi.CheckoutRequest;
import com.qalsmartkeyboard.billing.spi.CheckoutSession;
import com.qalsmartkeyboard.billing.spi.MerchantAccount;
import com.qalsmartkeyboard.billing.spi.Money;
import com.qalsmartkeyboard.billing.spi.PaymentEnvironment;
import com.qalsmartkeyboard.billing.spi.PaymentProviderException;
import com.qalsmartkeyboard.billing.spi.ProviderPaymentStatus;
import com.qalsmartkeyboard.billing.spi.VerificationRequest;
import com.qalsmartkeyboard.billing.spi.VerificationResult;
import com.qalsmartkeyboard.billing.spi.WebhookAuthentication;
import com.qalsmartkeyboard.billing.spi.WebhookEvent;
import com.qalsmartkeyboard.billing.spi.WebhookRequest;
import com.qalsmartkeyboard.testsupport.logging.CapturedLogs;

/**
 * ADR 0045 ArifPay adapter. Part one runs against the compose stubs ({@code infra/compose/wiremock/mappings/arifpay-*.json},
 * shapes taken from the live sandbox), part two forces failures on a bare WireMock. Never calls arifpay.
 */
class ArifPayProviderTest {

	static final String KEY = "arifpay-test-key-never-logged-9Qx";
	static final String FIXED_NONCE = "00000000-0000-4000-8000-000000000001";
	static final MerchantAccount ACCOUNT = new MerchantAccount("ARIFPAY", "arifpay-et-sandbox", "ET", "ETB",
			PaymentEnvironment.SANDBOX, null);
	static final Money PRICE = new Money(29900, "ETB", 2);

	static WireMockServer compose;
	static WireMockServer bare;

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
		compose.resetRequests();
		bare.resetAll();
	}

	static ArifPayProvider provider(WireMockServer server, String key, PaymentEnvironment env) {
		return new ArifPayProvider(new ArifPayProperties(key, env, URI.create(server.baseUrl() + "/arifpay"),
				null, Duration.ofSeconds(2), Duration.ofSeconds(2), null, null, null, ZoneId.of("Africa/Addis_Ababa"),
				"EN"), "webhook-key-for-tests-0123456789abcdef".getBytes(StandardCharsets.UTF_8));
	}

	static CheckoutRequest checkout(String nonce, Money amount) {
		URI ret = URI.create("https://api.example.test/api/v1/billing/return/" + nonce);
		return new CheckoutRequest(ACCOUNT, nonce, "QAL-ET-2611-ABCDEF", amount, "Premium (30 days)",
				"ARIFPAY_CHECKOUT", null, URI.create(ret + "?result=success"), URI.create(ret + "?result=cancel"),
				URI.create(ret + "?result=error"),
				URI.create("https://api.example.test/api/v1/billing/webhooks/ARIFPAY/arifpay-et-sandbox"),
				Instant.parse("2026-11-20T10:30:00Z"), "am");
	}

	@Test
	void createsAHostedCheckoutWithTheDocumentedRequest() {
		ArifPayProvider p = provider(compose, KEY, PaymentEnvironment.SANDBOX);
		String nonce = UUID.randomUUID().toString();
		CheckoutSession s = p.initiateCheckout(checkout(nonce, PRICE));
		assertThat(s.providerReference()).isEqualTo("ARP-" + nonce);
		assertThat(s.checkoutUrl().toString()).endsWith("/arifpay/checkout/ARP-" + nonce);

		List<LoggedRequest> sent = compose.findAll(postRequestedFor(urlPathEqualTo("/arifpay/api/sandbox/checkout/session")));
		assertThat(sent).hasSize(1);
		LoggedRequest r = sent.get(0);
		assertThat(r.getHeader("x-arifpay-key")).isEqualTo(KEY);
		String body = r.getBodyAsString();
		assertThat(body).contains("\"nonce\":\"" + nonce + "\"", "\"price\":299.00", "\"quantity\":1",
				"\"expireDate\":\"2026-11-20T13:30:00\"", "\"lang\":\"EN\"",
				"\"successUrl\":\"https://api.example.test/api/v1/billing/return/" + nonce + "?result=success\"");
		assertThat(body).contains("webhooks/ARIFPAY/arifpay-et-sandbox?t=" + p.token("arifpay-et-sandbox", nonce));
		// no payer data and no method/beneficiary overrides
		assertThat(body).doesNotContain("phone", "email", "beneficiaries", "paymentMethods");
	}

	@Test
	void sandboxKeyOnTheProductionRouteIsAConfigurationErrorAndEnvironmentsNeverMix() {
		MerchantAccount prod = new MerchantAccount("ARIFPAY", "arifpay-et-production", "ET", "ETB",
				PaymentEnvironment.PRODUCTION, null);
		bare.stubFor(post(urlPathEqualTo("/arifpay/api/checkout/session")).willReturn(aResponse().withStatus(400)
				.withHeader("Content-Type", "application/json")
				.withBody("{\"error\":true,\"msg\":\"Test API Key found\",\"data\":null}")));
		CheckoutRequest onProd = new CheckoutRequest(prod, "n", null, PRICE, null, "ARIFPAY_CHECKOUT", null,
				URI.create("https://a.test/s"), URI.create("https://a.test/c"), URI.create("https://a.test/e"),
				URI.create("https://a.test/w"), null, null);
		PaymentProviderException e = catchThrowableOfType(PaymentProviderException.class,
				() -> provider(bare, KEY, PaymentEnvironment.PRODUCTION).initiateCheckout(onProd));
		assertThat(e.kind()).isEqualTo(PaymentProviderException.Kind.MISCONFIGURED);
		// a SANDBOX account never goes through a PRODUCTION adapter (and vice versa)
		PaymentProviderException mismatch = catchThrowableOfType(PaymentProviderException.class,
				() -> provider(bare, KEY, PaymentEnvironment.PRODUCTION).initiateCheckout(checkout("n", PRICE)));
		assertThat(mismatch.safeCode()).isEqualTo("ENVIRONMENT_MISMATCH");
		assertThat(provider(bare, KEY, PaymentEnvironment.PRODUCTION).supports(PaymentEnvironment.SANDBOX)).isFalse();
	}

	@Test
	void verifiesFromTheSessionDocument() {
		ArifPayProvider p = provider(compose, KEY, PaymentEnvironment.SANDBOX);
		String nonce = UUID.randomUUID().toString();
		VerificationResult paid = p.verify(new VerificationRequest(ACCOUNT, "ARP-" + nonce, nonce, PRICE));
		assertThat(paid.status()).isEqualTo(ProviderPaymentStatus.SUCCEEDED);
		assertThat(paid.providerReference()).isEqualTo("ARP-" + nonce);
		assertThat(paid.merchantReference()).isEqualTo(nonce);
		assertThat(paid.amount()).isEqualTo(PRICE);
		assertThat(paid.providerTransactionReference()).startsWith("SIM-");
		assertThat(paid.providerMethodCode()).isEqualTo("TELEBIRR_USSD");

		VerificationResult fresh = p.verify(new VerificationRequest(ACCOUNT, "SBX-NOT-STARTED", FIXED_NONCE, PRICE));
		assertThat(fresh.status()).isEqualTo(ProviderPaymentStatus.NOT_STARTED);

		VerificationResult failed = p.verify(new VerificationRequest(ACCOUNT, "SBX-FAILED", FIXED_NONCE, PRICE));
		assertThat(failed.status()).isEqualTo(ProviderPaymentStatus.FAILED);
		assertThat(failed.failureCode()).isEqualTo("ARIFPAY_FAILED");

		VerificationResult wrong = p.verify(new VerificationRequest(ACCOUNT, "SBX-WRONG-AMOUNT", FIXED_NONCE, PRICE));
		assertThat(wrong.status()).isEqualTo(ProviderPaymentStatus.SUCCEEDED);
		assertThat(wrong.amount()).as("paid amount differs from the session total: not reported").isNull();

		VerificationResult unknown = p.verify(new VerificationRequest(ACCOUNT, "SBX-UNKNOWN", FIXED_NONCE, PRICE));
		assertThat(unknown.status()).isEqualTo(ProviderPaymentStatus.UNKNOWN);
		assertThat(unknown.failureCode()).isEqualTo("SESSION_NOT_FOUND");
	}

	@Test
	void sandboxHasNoLookupByNonce() {
		VerificationResult r = provider(compose, KEY, PaymentEnvironment.SANDBOX)
				.verify(new VerificationRequest(ACCOUNT, null, FIXED_NONCE, PRICE));
		assertThat(r.status()).isEqualTo(ProviderPaymentStatus.UNKNOWN);
		assertThat(r.failureCode()).isEqualTo("NONCE_LOOKUP_UNSUPPORTED");
	}

	@Test
	void productionLooksALostSessionUpByNonce() {
		MerchantAccount prod = new MerchantAccount("ARIFPAY", "arifpay-et-production", "ET", "ETB",
				PaymentEnvironment.PRODUCTION, null);
		bare.stubFor(get(urlPathEqualTo("/arifpay/api/checkout/getSessionByNonce/" + FIXED_NONCE))
				.withHeader("x-arifpay-key", equalTo(KEY)).willReturn(aResponse().withHeader("Content-Type",
						"application/json").withBody("""
								{"error":false,"msg":null,"data":{"uuid":"ED54BDB4D5BF2D","nonce":"%s","totalAmount":299.0,
								"test":false,"transaction":{"transactionId":"BCI6FETYVI","transactionStatus":"SUCCESS",
								"paymentType":"CBE","amount":299.0}}}""".formatted(FIXED_NONCE))));
		VerificationResult r = provider(bare, KEY, PaymentEnvironment.PRODUCTION)
				.verify(new VerificationRequest(prod, null, FIXED_NONCE, PRICE));
		assertThat(r.status()).isEqualTo(ProviderPaymentStatus.SUCCEEDED);
		assertThat(r.providerReference()).isEqualTo("ED54BDB4D5BF2D");
	}

	@Test
	void aSandboxSessionNeverVerifiesInProduction() {
		MerchantAccount prod = new MerchantAccount("ARIFPAY", "arifpay-et-production", "ET", "ETB",
				PaymentEnvironment.PRODUCTION, null);
		bare.stubFor(get(urlPathEqualTo("/arifpay/api/checkout/session/S1")).willReturn(aResponse()
				.withHeader("Content-Type", "application/json").withBody("""
						{"error":false,"data":{"uuid":"S1","nonce":"n","totalAmount":299.0,"test":true,
						"transaction":{"transactionId":"T","transactionStatus":"SUCCESS","amount":299.0}}}""")));
		VerificationResult r = provider(bare, KEY, PaymentEnvironment.PRODUCTION)
				.verify(new VerificationRequest(prod, "S1", "n", PRICE));
		assertThat(r.status()).isEqualTo(ProviderPaymentStatus.UNKNOWN);
		assertThat(r.failureCode()).isEqualTo("ENVIRONMENT_MISMATCH");
	}

	@Test
	void failuresMapToKindsWithoutLeakingTheKeyOrBodies() {
		ArifPayProvider p = provider(bare, KEY, PaymentEnvironment.SANDBOX);
		try (CapturedLogs logs = new CapturedLogs()) {
			bare.stubFor(post(urlPathEqualTo("/arifpay/api/sandbox/checkout/session")).willReturn(aResponse()
					.withStatus(503).withBody("<html>down " + KEY + "</html>")));
			assertThat(catchThrowableOfType(PaymentProviderException.class,
					() -> p.initiateCheckout(checkout("n1", PRICE))).kind())
					.isEqualTo(PaymentProviderException.Kind.UNAVAILABLE);

			bare.stubFor(post(urlPathEqualTo("/arifpay/api/sandbox/checkout/session")).willReturn(aResponse()
					.withFixedDelay(3000).withStatus(200)));
			assertThat(catchThrowableOfType(PaymentProviderException.class,
					() -> p.initiateCheckout(checkout("n2", PRICE))).kind())
					.isEqualTo(PaymentProviderException.Kind.UNAVAILABLE);

			bare.stubFor(post(urlPathEqualTo("/arifpay/api/sandbox/checkout/session")).willReturn(aResponse()
					.withStatus(400).withHeader("Content-Type", "application/json")
					.withBody("{\"error\":true,\"msg\":\"session already exist\",\"data\":null}")));
			PaymentProviderException dup = catchThrowableOfType(PaymentProviderException.class,
					() -> p.initiateCheckout(checkout("n3", PRICE)));
			assertThat(dup.kind()).isEqualTo(PaymentProviderException.Kind.REJECTED);
			assertThat(dup.safeCode()).isEqualTo("DUPLICATE_NONCE");

			bare.stubFor(post(urlPathEqualTo("/arifpay/api/sandbox/checkout/session")).willReturn(aResponse()
					.withStatus(404).withHeader("Content-Type", "application/json")
					.withBody("{\"msg\":\"API Key Not found\",\"data\":null,\"error\":true}")));
			assertThat(catchThrowableOfType(PaymentProviderException.class,
					() -> p.initiateCheckout(checkout("n4", PRICE))).kind())
					.isEqualTo(PaymentProviderException.Kind.MISCONFIGURED);

			bare.stubFor(post(urlPathEqualTo("/arifpay/api/sandbox/checkout/session")).willReturn(aResponse()
					.withHeader("Content-Type", "application/json").withBody("""
							{"error":false,"msg":"No Errors","data":{"sessionId":"S","paymentUrl":"https://x.test/c/S",
							"totalAmount":1.0}}""")));
			assertThat(catchThrowableOfType(PaymentProviderException.class,
					() -> p.initiateCheckout(checkout("n5", PRICE))).safeCode()).isEqualTo("CREATE_AMOUNT_MISMATCH");

			bare.stubFor(post(urlPathEqualTo("/arifpay/api/sandbox/checkout/session")).willReturn(aResponse()
					.withHeader("Content-Type", "application/json").withBody("""
							{"error":false,"msg":"No Errors","data":{"sessionId":"S","paymentUrl":"javascript:alert(1)",
							"totalAmount":299.0}}""")));
			assertThat(catchThrowableOfType(PaymentProviderException.class,
					() -> p.initiateCheckout(checkout("n6", PRICE))).safeCode()).isEqualTo("CREATE_BAD_URL");

			assertThat(String.join(" ", logs.texts())).doesNotContain(KEY).doesNotContain("<html>");
		}
	}

	@Test
	void statusMapping() {
		assertThat(ArifPayProvider.status("SUCCESS", "T1", "CBE")).isEqualTo(ProviderPaymentStatus.SUCCEEDED);
		assertThat(ArifPayProvider.status("SUCCESS", null, "CBE")).isEqualTo(ProviderPaymentStatus.UNKNOWN);
		assertThat(ArifPayProvider.status("PENDING", null, null)).isEqualTo(ProviderPaymentStatus.NOT_STARTED);
		assertThat(ArifPayProvider.status("PENDING", null, "TELEBIRR")).isEqualTo(ProviderPaymentStatus.PENDING);
		assertThat(ArifPayProvider.status("CANCELED", null, null)).isEqualTo(ProviderPaymentStatus.CANCELLED);
		assertThat(ArifPayProvider.status("CANCELLED", null, null)).isEqualTo(ProviderPaymentStatus.CANCELLED);
		assertThat(ArifPayProvider.status("EXPIRED", null, null)).isEqualTo(ProviderPaymentStatus.EXPIRED);
		assertThat(ArifPayProvider.status("FAILED", null, null)).isEqualTo(ProviderPaymentStatus.FAILED);
		assertThat(ArifPayProvider.status("UNAUTHORIZED", null, null)).isEqualTo(ProviderPaymentStatus.UNKNOWN);
		assertThat(ArifPayProvider.status(null, null, null)).isEqualTo(ProviderPaymentStatus.UNKNOWN);
	}

	static WebhookRequest notify(String body, String token) {
		return new WebhookRequest(ACCOUNT, body.getBytes(StandardCharsets.UTF_8), Map.of(),
				token == null ? Map.of() : Map.of("t", token));
	}

	@Test
	void notificationsNeedTheTokenOfTheirNonceAndAreRedacted() {
		ArifPayProvider p = provider(compose, KEY, PaymentEnvironment.SANDBOX);
		String nonce = UUID.randomUUID().toString();
		String body = """
				{"uuid":"ARP-%1$s","sessionId":"ARP-%1$s","nonce":"%1$s","phone":"251911111111",
				 "paymentMethod":"TELEBIRR_USSD","totalAmount":299,"transactionStatus":"SUCCESS",
				 "transaction":{"transactionId":"RCPT-1","transactionStatus":"SUCCESS"}}""".formatted(nonce);
		String token = p.token("arifpay-et-sandbox", nonce);
		assertThat(p.authenticate(notify(body, token))).isEqualTo(WebhookAuthentication.VALID);
		assertThat(p.authenticate(notify(body, null))).isEqualTo(WebhookAuthentication.INVALID);
		assertThat(p.authenticate(notify(body, p.token("arifpay-et-sandbox", "other")))).isEqualTo(
				WebhookAuthentication.INVALID);
		assertThat(p.authenticate(notify(body, p.token("arifpay-et-production", nonce)))).isEqualTo(
				WebhookAuthentication.INVALID);
		assertThat(p.authenticate(notify("not json", token))).isEqualTo(WebhookAuthentication.INVALID);
		// a different secret (another deployment) never validates
		ArifPayProvider other = new ArifPayProvider(new ArifPayProperties(KEY, PaymentEnvironment.SANDBOX, null, null,
				null, null, null, null, null, ZoneId.of("UTC"), "EN"), "another-key".getBytes(StandardCharsets.UTF_8));
		assertThat(other.authenticate(notify(body, token))).isEqualTo(WebhookAuthentication.INVALID);

		WebhookEvent e = p.parseWebhook(notify(body, token));
		assertThat(e.externalEventId()).isNull();
		assertThat(e.eventType()).isEqualTo("ARIFPAY_NOTIFY_SUCCESS");
		assertThat(e.providerReference()).isEqualTo("ARP-" + nonce);
		assertThat(e.merchantReference()).isEqualTo(nonce);
		assertThat(e.reportedStatus()).isEqualTo(ProviderPaymentStatus.SUCCEEDED);
		assertThat(e.redactedPayload().toString()).doesNotContain("251911111111").contains("RCPT-1");
		assertThat(catchThrowableOfType(PaymentProviderException.class, () -> p.parseWebhook(notify("[1]", token))))
				.isNotNull();
	}

	@Test
	void requestBodyCarriesTheTokenThatAuthenticatesTheLaterNotification() {
		ArifPayProvider p = provider(compose, KEY, PaymentEnvironment.SANDBOX);
		String nonce = UUID.randomUUID().toString();
		p.initiateCheckout(checkout(nonce, PRICE));
		compose.verify(postRequestedFor(urlPathEqualTo("/arifpay/api/sandbox/checkout/session"))
				.withRequestBody(matchingJsonPath("$.notifyUrl",
						equalTo("https://api.example.test/api/v1/billing/webhooks/ARIFPAY/arifpay-et-sandbox?t="
								+ p.token("arifpay-et-sandbox", nonce)))));
		assertThat(p.toString()).doesNotContain(KEY);
	}
}
