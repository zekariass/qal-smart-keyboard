package com.qalsmartkeyboard.billing.chapa;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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
import com.qalsmartkeyboard.billing.spi.ProviderRefundStatus;
import com.qalsmartkeyboard.billing.spi.RefundRequest;
import com.qalsmartkeyboard.billing.spi.RefundResult;
import com.qalsmartkeyboard.billing.spi.RefundStatusRequest;
import com.qalsmartkeyboard.billing.spi.VerificationRequest;
import com.qalsmartkeyboard.billing.spi.VerificationResult;
import com.qalsmartkeyboard.billing.spi.WebhookAuthentication;
import com.qalsmartkeyboard.billing.spi.WebhookEvent;
import com.qalsmartkeyboard.billing.spi.WebhookRequest;

/**
 * ADR 0045 step A2: the Chapa adapter against the compose stubs ({@code infra/compose/wiremock/mappings/chapa-*.json},
 * written from developer.chapa.co) and a bare WireMock for failures. Never calls Chapa.
 */
class ChapaProviderTest {

	static final String KEY = "CHASECK_TEST-unit-test-key-never-logged";
	static final String SECRET = "chapa-webhook-secret-hash-for-tests";
	static final MerchantAccount ACCOUNT = new MerchantAccount("CHAPA", "chapa-et-sandbox", "ET", "ETB",
			PaymentEnvironment.SANDBOX, null);
	static final Money PRICE = new Money(29900, "ETB", 2);
	/** The documented transaction webhook (developer.chapa.co/integrations/webhooks), our tx_ref. */
	static final String CHARGE_SUCCESS = """
			{"event":"charge.success","first_name":"Abebe","last_name":"Bikila","email":"abebe@example.com",
			"mobile":"251900000000","currency":"ETB","amount":"299.00","charge":"10.47","status":"success",
			"mode":"test","reference":"AP634JFwEbxd","created_at":"2026-11-20T19:21:18.000000Z",
			"updated_at":"2026-11-20T19:21:27.000000Z","type":"API","tx_ref":"%s","payment_method":"telebirr",
			"customization":{"title":null,"description":null,"logo":null},"meta":null}""";

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

	static ChapaProvider provider(WireMockServer server, PaymentEnvironment env, boolean acceptSecretHash) {
		return new ChapaProvider(new ChapaProperties(KEY, SECRET, env, URI.create(server.baseUrl() + "/chapa"),
				Duration.ofSeconds(2), Duration.ofSeconds(2), "Qal Keyboard", acceptSecretHash));
	}

	static ChapaProvider provider(WireMockServer server) {
		return provider(server, PaymentEnvironment.SANDBOX, true);
	}

	static CheckoutRequest checkout(String txRef) {
		URI ret = URI.create("https://api.example.test/api/v1/billing/return/" + txRef);
		return new CheckoutRequest(ACCOUNT, txRef, "QAL-ET-2611-ABCDEF", PRICE, "Premium (30 days)", "CHAPA_CHECKOUT",
				null, URI.create(ret + "?result=success"), URI.create(ret + "?result=cancel"),
				URI.create(ret + "?result=error"),
				URI.create("https://api.example.test/api/v1/billing/webhooks/CHAPA/chapa-et-sandbox"),
				Instant.parse("2026-11-20T10:30:00Z"), "am");
	}

	static VerificationRequest verification(String txRef) {
		return new VerificationRequest(ACCOUNT, txRef, txRef, PRICE);
	}

	static WebhookRequest webhook(String body, Map<String, List<String>> headers) {
		return new WebhookRequest(ACCOUNT, body.getBytes(StandardCharsets.UTF_8), headers, Map.of());
	}

	// ------------------------------------------------------------------------------------------------ checkout

	@Test
	void initializesAHostedCheckoutWithTheDocumentedRequest() {
		String txRef = UUID.randomUUID().toString();
		CheckoutSession s = provider(compose).initiateCheckout(checkout(txRef));
		assertThat(s.providerReference()).isEqualTo(txRef);
		assertThat(s.checkoutUrl().toString()).isEqualTo("https://checkout.chapa.co/checkout/payment/MOCK-" + txRef);

		List<LoggedRequest> sent = compose.findAll(postRequestedFor(urlPathEqualTo("/chapa/v1/transaction/initialize")));
		assertThat(sent).hasSize(1);
		LoggedRequest r = sent.get(0);
		assertThat(r.getHeader("Authorization")).isEqualTo("Bearer " + KEY);
		assertThat(r.getHeader("Content-Type")).startsWith("application/json");
		String body = r.getBodyAsString();
		assertThat(body).contains("\"amount\":\"299.00\"", "\"currency\":\"ETB\"", "\"tx_ref\":\"" + txRef + "\"",
				"\"return_url\":\"https://api.example.test/api/v1/billing/return/" + txRef + "?result=success\"",
				"\"callback_url\":\"https://api.example.test/api/v1/billing/return/" + txRef + "\"",
				"\"customization\":{\"title\":\"Qal Keyboard\",\"description\":\"Premium 30 days QAL-ET-2611-ABCDEF\"}");
		// data minimisation: no payer data
		assertThat(body).doesNotContain("email", "phone_number", "first_name", "last_name");
	}

	@Test
	void aReusedTxRefIsRejectedAndEnvironmentsNeverMix() {
		PaymentProviderException dup = catchThrowableOfType(PaymentProviderException.class,
				() -> provider(compose).initiateCheckout(checkout("dup-" + UUID.randomUUID())));
		assertThat(dup.kind()).isEqualTo(PaymentProviderException.Kind.REJECTED);
		assertThat(dup.safeCode()).isEqualTo("DUPLICATE_TX_REF");

		ChapaProvider live = provider(bare, PaymentEnvironment.PRODUCTION, true);
		assertThat(live.supports(PaymentEnvironment.SANDBOX)).isFalse();
		PaymentProviderException mismatch = catchThrowableOfType(PaymentProviderException.class,
				() -> live.initiateCheckout(checkout(UUID.randomUUID().toString())));
		assertThat(mismatch.safeCode()).isEqualTo("ENVIRONMENT_MISMATCH");
		assertThat(bare.getAllServeEvents()).isEmpty();
	}

	@Test
	void initializeFailuresMapToKinds() {
		bare.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/chapa/v1/transaction/initialize"))
				.willReturn(aResponse().withStatus(401).withHeader("Content-Type", "application/json")
						.withBody("{\"message\":\"Invalid API Key or User doesn’t exist\",\"status\":\"failed\",\"data\":null}")));
		assertThat(catchThrowableOfType(PaymentProviderException.class,
				() -> provider(bare).initiateCheckout(checkout("x"))).kind())
				.isEqualTo(PaymentProviderException.Kind.MISCONFIGURED);
		bare.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/chapa/v1/transaction/initialize"))
				.willReturn(aResponse().withStatus(502)));
		assertThat(catchThrowableOfType(PaymentProviderException.class,
				() -> provider(bare).initiateCheckout(checkout("x"))).kind())
				.isEqualTo(PaymentProviderException.Kind.UNAVAILABLE);
		bare.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/chapa/v1/transaction/initialize"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json")
						.withBody("{\"message\":\"Hosted Link\",\"status\":\"success\",\"data\":{\"checkout_url\":\"javascript:alert(1)\"}}")));
		assertThat(catchThrowableOfType(PaymentProviderException.class,
				() -> provider(bare).initiateCheckout(checkout("x"))).safeCode()).isEqualTo("INITIALIZE_BAD_URL");
	}

	// ------------------------------------------------------------------------------------------------ verify

	@Test
	void verifiesFromTheTransactionDocument() {
		String txRef = UUID.randomUUID().toString();
		VerificationResult paid = provider(compose).verify(verification(txRef));
		assertThat(paid.status()).isEqualTo(ProviderPaymentStatus.SUCCEEDED);
		assertThat(paid.amount()).isEqualTo(PRICE);
		assertThat(paid.merchantReference()).isEqualTo(txRef);
		assertThat(paid.providerReference()).isEqualTo(txRef);
		assertThat(paid.providerTransactionReference()).startsWith("MOCK");
		assertThat(paid.providerMethodCode()).isEqualTo("test");
		assertThat(paid.occurredAt()).isEqualTo(Instant.parse("2026-11-20T07:06:01Z"));

		assertThat(provider(compose).verify(verification("pending-1")).status()).isEqualTo(ProviderPaymentStatus.PENDING);
		VerificationResult failed = provider(compose).verify(verification("fail-1"));
		assertThat(failed.status()).isEqualTo(ProviderPaymentStatus.FAILED);
		assertThat(failed.failureCode()).isEqualTo("CHAPA_FAILED");
		VerificationResult notPaid = provider(compose).verify(verification("notpaid-1"));
		assertThat(notPaid.status()).isEqualTo(ProviderPaymentStatus.NOT_STARTED);
		assertThat(notPaid.failureCode()).isEqualTo("NOT_PAID_YET");
		assertThat(notPaid.providerReference()).isEqualTo("notpaid-1");
		VerificationResult missing = provider(compose).verify(verification("missing-1"));
		assertThat(missing.status()).isEqualTo(ProviderPaymentStatus.NOT_STARTED);
		assertThat(missing.failureCode()).isEqualTo("TRANSACTION_NOT_FOUND");
	}

	@Test
	void inconsistentDocumentsAreNeverSuccessesWithAnAmount() {
		String doc = """
				{"message":"Payment details","status":"success","data":{"currency":"%s","amount":%s,"mode":"%s",
				"status":"%s","reference":%s,"tx_ref":"%s","method":"telebirr","updated_at":"2026-11-20T07:06:01.000000Z"}}""";
		stubVerify("t1", doc.formatted("ETB", "299", "live", "success", "\"R1\"", "t1"));
		assertThat(provider(bare).verify(verification("t1")).failureCode()).isEqualTo("ENVIRONMENT_MISMATCH");
		stubVerify("t2", doc.formatted("USD", "299", "test", "success", "\"R2\"", "t2"));
		VerificationResult usd = provider(bare).verify(verification("t2"));
		assertThat(usd.status()).isEqualTo(ProviderPaymentStatus.SUCCEEDED);
		assertThat(usd.amount()).isNull(); // the core reports an amount MISMATCH, nothing is granted
		stubVerify("t3", doc.formatted("ETB", "299", "test", "success", "null", "t3"));
		assertThat(provider(bare).verify(verification("t3")).status()).isEqualTo(ProviderPaymentStatus.UNKNOWN);
		stubVerify("t4", doc.formatted("ETB", "299", "test", "success", "\"R4\"", "someone-else"));
		assertThat(provider(bare).verify(verification("t4")).failureCode()).isEqualTo("TX_REF_MISMATCH");
		stubVerify("t5", doc.formatted("ETB", "299", "test", "refunded", "\"R5\"", "t5"));
		VerificationResult refunded = provider(bare).verify(verification("t5"));
		assertThat(refunded.status()).isEqualTo(ProviderPaymentStatus.UNKNOWN);
		assertThat(refunded.failureCode()).isEqualTo("CHAPA_REFUNDED");
		stubVerify("t6", doc.formatted("ETB", "299.001", "test", "success", "\"R6\"", "t6"));
		assertThat(provider(bare).verify(verification("t6")).amount()).isNull();

		bare.stubFor(get(urlPathEqualTo("/chapa/v1/transaction/verify/t7")).willReturn(aResponse().withStatus(401)
				.withHeader("Content-Type", "application/json").withBody(
						"{\"message\":\"Test secret keys can’t be used to verify a live transaction\",\"status\":\"failed\",\"data\":null}")));
		assertThat(catchThrowableOfType(PaymentProviderException.class, () -> provider(bare).verify(verification("t7")))
				.kind()).isEqualTo(PaymentProviderException.Kind.MISCONFIGURED);
		bare.stubFor(get(urlPathEqualTo("/chapa/v1/transaction/verify/t8")).willReturn(aResponse().withStatus(503)));
		assertThat(catchThrowableOfType(PaymentProviderException.class, () -> provider(bare).verify(verification("t8")))
				.kind()).isEqualTo(PaymentProviderException.Kind.UNAVAILABLE);
	}

	static void stubVerify(String txRef, String body) {
		bare.stubFor(get(urlPathEqualTo("/chapa/v1/transaction/verify/" + txRef))
				.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
	}

	// ------------------------------------------------------------------------------------------------ webhooks

	@Test
	void webhookSignaturesAsDocumented() {
		ChapaProvider p = provider(bare);
		String body = CHARGE_SUCCESS.formatted("tx-1");
		byte[] key = SECRET.getBytes(StandardCharsets.UTF_8);
		String payloadHmac = ChapaProvider.hmacHex(key, body.getBytes(StandardCharsets.UTF_8));
		String secretHash = ChapaProvider.hmacHex(key, key);

		assertThat(p.authenticate(webhook(body, Map.of("x-chapa-signature", List.of(payloadHmac)))))
				.isEqualTo(WebhookAuthentication.VALID);
		assertThat(p.authenticate(webhook(body, Map.of("x-chapa-signature", List.of(payloadHmac.toUpperCase())))))
				.isEqualTo(WebhookAuthentication.VALID);
		assertThat(p.authenticate(webhook(body, Map.of("chapa-signature", List.of(secretHash)))))
				.isEqualTo(WebhookAuthentication.VALID);
		// one valid header is enough
		assertThat(p.authenticate(webhook(body, Map.of("chapa-signature", List.of("00"), "x-chapa-signature",
				List.of(payloadHmac))))).isEqualTo(WebhookAuthentication.VALID);
		// Chapa's sample hashes JSON.stringify(req.body): a pretty-printed delivery still verifies
		String pretty = "{\n  \"event\": \"charge.success\",\n  \"tx_ref\": \"tx-2\"\n}";
		String compactHmac = ChapaProvider.hmacHex(key,
				"{\"event\":\"charge.success\",\"tx_ref\":\"tx-2\"}".getBytes(StandardCharsets.UTF_8));
		assertThat(p.authenticate(webhook(pretty, Map.of("x-chapa-signature", List.of(compactHmac)))))
				.isEqualTo(WebhookAuthentication.VALID);

		// forged, missing, tampered, other key
		assertThat(p.authenticate(webhook(body, Map.of()))).isEqualTo(WebhookAuthentication.INVALID);
		assertThat(p.authenticate(webhook(body, Map.of("x-chapa-signature", List.of("deadbeef")))))
				.isEqualTo(WebhookAuthentication.INVALID);
		assertThat(p.authenticate(webhook(body.replace("299.00", "1.00"), Map.of("x-chapa-signature",
				List.of(payloadHmac))))).isEqualTo(WebhookAuthentication.INVALID);
		assertThat(p.authenticate(webhook(body, Map.of("x-chapa-signature", List.of(ChapaProvider
				.hmacHex("other".getBytes(StandardCharsets.UTF_8), body.getBytes(StandardCharsets.UTF_8)))))))
				.isEqualTo(WebhookAuthentication.INVALID);
		// the constant secret-hash header can be switched off
		assertThat(provider(bare, PaymentEnvironment.SANDBOX, false)
				.authenticate(webhook(body, Map.of("chapa-signature", List.of(secretHash)))))
				.isEqualTo(WebhookAuthentication.INVALID);
		// no webhook secret configured: nothing is accepted
		ChapaProvider noSecret = new ChapaProvider(new ChapaProperties(KEY, null, PaymentEnvironment.SANDBOX,
				URI.create(bare.baseUrl()), null, null, null, true));
		assertThat(noSecret.authenticate(webhook(body, Map.of("x-chapa-signature", List.of(payloadHmac)))))
				.isEqualTo(WebhookAuthentication.INVALID);
	}

	@Test
	void parsesChargeWebhooksWithoutPayerData() {
		WebhookEvent e = provider(bare).parseWebhook(webhook(CHARGE_SUCCESS.formatted("tx-9"), Map.of()));
		assertThat(e.merchantReference()).isEqualTo("tx-9");
		assertThat(e.providerReference()).isEqualTo("tx-9");
		assertThat(e.reportedStatus()).isEqualTo(ProviderPaymentStatus.SUCCEEDED);
		assertThat(e.externalEventId()).isEqualTo("chapa:charge.success:AP634JFwEbxd:success");
		assertThat(e.eventType()).isEqualTo("CHAPA_CHARGE.SUCCESS");
		assertThat(e.redactedPayload().toString()).doesNotContain("Abebe", "Bikila", "abebe@example.com",
				"251900000000");
		WebhookEvent refunded = provider(bare).parseWebhook(webhook(CHARGE_SUCCESS.formatted("tx-9")
				.replace("charge.success", "charge.refunded").replace("\"status\":\"success\"", "\"status\":\"refunded\""),
				Map.of()));
		assertThat(refunded.externalEventId()).isEqualTo("chapa:charge.refunded:AP634JFwEbxd:refunded");
		assertThat(refunded.reportedStatus()).isEqualTo(ProviderPaymentStatus.UNKNOWN);
		PaymentProviderException payout = catchThrowableOfType(PaymentProviderException.class,
				() -> provider(bare).parseWebhook(webhook("{\"event\":\"payout.success\",\"type\":\"Payout\"}", Map.of())));
		assertThat(payout.safeCode()).isEqualTo("WEBHOOK_NOT_A_CHARGE");
	}

	// ------------------------------------------------------------------------------------------------ refunds

	@Test
	void refundsByChapaReferenceAndChecksTheRefund() {
		ChapaProvider p = provider(compose);
		assertThat(p.supportsRefunds()).isTrue();
		RefundResult started = p.refund(new RefundRequest(ACCOUNT, "RF-1234", "tx-1", "tx-1", "AP634JFwEbxd", PRICE,
				"DUPLICATE_CAPTURE"));
		assertThat(started.status()).isEqualTo(ProviderRefundStatus.PENDING);
		assertThat(started.providerRefundReference()).startsWith("MERC-DIS-REF-MOCK");
		assertThat(started.amount()).isEqualTo(PRICE);
		LoggedRequest sent = compose.findAll(postRequestedFor(urlPathMatching("/chapa/v1/refund/.*"))).get(0);
		assertThat(sent.getUrl()).isEqualTo("/chapa/v1/refund/AP634JFwEbxd");
		assertThat(sent.getHeader("Content-Type")).startsWith("application/x-www-form-urlencoded");
		assertThat(sent.getBodyAsString()).contains("amount=299.00", "reference=RF-1234", "reason=DUPLICATE_CAPTURE");

		RefundResult checked = p.refundStatus(new RefundStatusRequest(ACCOUNT, "RF-1234",
				started.providerRefundReference()));
		assertThat(checked.status()).isEqualTo(ProviderRefundStatus.SUCCEEDED);

		assertThat(ChapaProvider.refundStatus("Refund Initiated")).isEqualTo(ProviderRefundStatus.PENDING);
		assertThat(ChapaProvider.refundStatus("processing")).isEqualTo(ProviderRefundStatus.PENDING);
		assertThat(ChapaProvider.refundStatus("reversed")).isEqualTo(ProviderRefundStatus.FAILED);
		assertThat(ChapaProvider.refundStatus("??")).isEqualTo(ProviderRefundStatus.UNKNOWN);
		PaymentProviderException noRef = catchThrowableOfType(PaymentProviderException.class, () -> p.refund(
				new RefundRequest(ACCOUNT, "RF-1", "tx", "tx", null, PRICE, null)));
		assertThat(noRef.safeCode()).isEqualTo("NO_PROVIDER_REFERENCE");
	}

	@Test
	void neverPrintsTheKey() {
		ChapaProvider p = provider(bare);
		assertThat(p.toString()).doesNotContain(KEY).doesNotContain(SECRET).contains("<redacted>");
		assertThat(ChapaProvider.plain("Premium (30 days) — ጤና!", 50)).isEqualTo("Premium 30 days");
	}
}
