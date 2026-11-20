package com.qalsmartkeyboard.billing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.common.ConsoleNotifier;
import com.jayway.jsonpath.JsonPath;
import com.qalsmartkeyboard.identity.account.UserAccountStore;
import com.qalsmartkeyboard.identity.account.UserAccountStore.PhoneSignIn;
import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;

/**
 * ADR 0045 end to end on PostgreSQL 18 with the real security chain, the real outbox dispatcher and a WireMock ArifPay
 * (shapes of the live sandbox), contract-validated: options per country and channel, order, attempt and hosted checkout,
 * the return URL alone grants nothing, valid / forged / duplicate / out-of-order webhooks, verification that says
 * failed or reports another amount, the reconciliation job, idempotency and the admin provider switch. The entitlement
 * cache keeps its default TTL, so the plan change proves the outbox invalidation.
 */
@SpringBootTest(properties = { "qal.outbox.poll-interval=100ms", "qal.billing.arifpay.api-key=it-arifpay-key",
		"qal.billing.arifpay.webhook-secret=it-webhook-secret-0123456789-abcdefghij",
		"qal.billing.public-base-url=https://api.example.test", "qal.geo.trusted-proxies=127.0.0.1/32,::1/128" })
@AutoConfigureMockMvc
@ContractValidated
@Testcontainers
class BillingPaymentsIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	static final WireMockServer ARIFPAY = new WireMockServer(
			options().dynamicPort().globalTemplating(true).notifier(new ConsoleNotifier(false)));
	static final String WEBHOOK_SECRET = "it-webhook-secret-0123456789-abcdefghij";
	static final String ACCOUNT = "arifpay-et-sandbox";

	static {
		ARIFPAY.start();
	}

	@DynamicPropertySource
	static void arifpay(DynamicPropertyRegistry registry) {
		registry.add("qal.billing.arifpay.base-url", () -> ARIFPAY.baseUrl() + "/arifpay");
	}

	@AfterAll
	static void stop() {
		ARIFPAY.stop();
	}

	record User(UUID id, String bearer) {
	}

	record Checkout(String orderId, String attemptId, String sessionId) {
	}

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	AccessTokenService accessTokens;
	@Autowired
	UserAccountStore accounts;
	@Autowired
	TransactionTemplate transactions;
	@Autowired
	PaymentReconciler reconciler;

	User registered() {
		UUID id = transactions.execute(s -> {
			String phone = "+2519" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
			return ((PhoneSignIn.SignedIn) accounts.signInWithPhone(phone, true, Instant.now())).user().id();
		});
		return new User(id, "Bearer " + accessTokens.issue(id, UUID.randomUUID(), null, "REGISTERED").value());
	}

	/** Go-live switches an operator flips (ADR 0045): provider, account, methods, products, ET direct policy. */
	@BeforeEach
	void activate() {
		ARIFPAY.resetAll();
		ARIFPAY.stubFor(post(urlPathEqualTo("/arifpay/api/sandbox/checkout/session")).willReturn(aResponse()
				.withHeader("Content-Type", "application/json").withBody("""
						{"error":false,"msg":"No Errors","data":{"sessionId":"ARP-{{jsonPath request.body '$.nonce'}}",
						"paymentUrl":"https://checkout.arifpay.org/checkout/ARP-{{jsonPath request.body '$.nonce'}}",
						"cancelUrl":"https://gateway.arifpay.net/v0/sandbox/checkout/session/cancel/x",
						"totalAmount":{{jsonPath request.body '$.items[0].price'}} }}""")));
		UUID reviewer = registered().id();
		jdbc.update("UPDATE payment_providers SET provider_status = 'ACTIVE', is_active = TRUE WHERE provider_code = 'ARIFPAY'");
		jdbc.update("UPDATE payment_provider_accounts SET account_status = 'ACTIVE', is_active = TRUE WHERE account_code = ?",
				ACCOUNT);
		jdbc.update("""
				UPDATE provider_payment_methods SET availability_status = 'AVAILABLE', is_enabled = TRUE
				WHERE method_code = 'ARIFPAY_CHECKOUT'""");
		jdbc.update("""
				UPDATE payment_provider_account_methods SET availability_status = 'AVAILABLE', is_enabled = TRUE
				WHERE provider_account_id = (SELECT id FROM payment_provider_accounts WHERE account_code = ?)""", ACCOUNT);
		jdbc.update("UPDATE billing_products SET product_status = 'ACTIVE' WHERE product_code IN ('PLAN_PREMIUM', 'PLAN_PREMIUM_PLUS')");
		jdbc.update("""
				UPDATE billing_market_channel_policies
				   SET approval_status = 'APPROVED', reviewed_at = NOW(), reviewed_by_user_id = COALESCE(reviewed_by_user_id, ?),
				       purchase_ui_allowed = TRUE, external_payment_allowed = TRUE
				 WHERE country_code = 'ET' AND client_platform = 'ANDROID' AND distribution_channel = 'DIRECT_DOWNLOAD'""",
				reviewer);
	}

	// ------------------------------------------------------------------------------------------------ helpers

	static MockHttpServletRequestBuilder fromEthiopia(MockHttpServletRequestBuilder b) {
		return b.header("CF-IPCountry", "ET").header("X-Qal-Network-Country", "et").header("X-Client-Platform",
				"ANDROID");
	}

	String createOrder(User u, String key, String period) throws Exception {
		String body = mvc.perform(fromEthiopia(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.post("/api/v1/billing/orders")).header("Authorization", u.bearer()).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"productCode":"PLAN_PREMIUM","billingPeriod":"%s","marketCountry":"ET","salesChannel":"ANDROID_DIRECT"}"""
						.formatted(period)))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.orderId");
	}

	String createAttempt(User u, String orderId, String key) throws Exception {
		String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.post("/api/v1/billing/orders/" + orderId + "/attempts").header("Authorization", u.bearer())
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
				.content("{\"methodCode\":\"ARIFPAY_CHECKOUT\"}")).andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("REQUIRES_ACTION"))
				.andExpect(jsonPath("$.nextAction.type").value("REDIRECT")).andReturn().getResponse()
				.getContentAsString();
		return JsonPath.read(body, "$.attemptId");
	}

	Checkout checkout(User u) throws Exception {
		String orderId = createOrder(u, UUID.randomUUID().toString(), "MONTHLY");
		String attemptId = createAttempt(u, orderId, UUID.randomUUID().toString());
		return new Checkout(orderId, attemptId, "ARP-" + attemptId);
	}

	/** The ArifPay session document as the live sandbox returns it. */
	static void session(Checkout c, String status, String transactionId, String paymentType, String amount,
			String total) {
		String tx = transactionId == null ? "null" : "\"" + transactionId + "\"";
		String type = paymentType == null ? "null" : "\"" + paymentType + "\"";
		ARIFPAY.stubFor(WireMock.get(urlPathEqualTo("/arifpay/api/sandbox/checkout/session/" + c.sessionId()))
				.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
						{"error":false,"msg":"No Errors","data":{"id":1,"uuid":"%s","nonce":"%s","totalAmount":%s,"test":true,
						"phone":null,"transaction":{"id":2,"transactionId":%s,"transactionStatus":"%s","paymentType":%s,
						"amount":%s,"fee":0.0,"updatedAt":"2026-11-20T11:05:28.000+00:00"}}}"""
						.formatted(c.sessionId(), c.attemptId(), total, tx, status, type, amount))));
	}

	static void paid(Checkout c) {
		session(c, "SUCCESS", "SIM-" + c.attemptId().substring(0, 8), "TELEBIRR_USSD", "299.0", "299.0");
	}

	static String token(String nonce) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(mac.doFinal(("ARIFPAY|" + ACCOUNT + "|" + nonce).getBytes(StandardCharsets.UTF_8)));
	}

	static String notification(Checkout c, String status) {
		return """
				{"uuid":"%1$s","sessionId":"%1$s","nonce":"%2$s","phone":"251900000000","paymentMethod":"TELEBIRR_USSD",
				 "totalAmount":299,"transactionStatus":"%3$s","transaction":{"transactionId":"SIM-1","transactionStatus":"%3$s"}}"""
				.formatted(c.sessionId(), c.attemptId(), status);
	}

	org.springframework.test.web.servlet.ResultActions webhook(String body, String token) throws Exception {
		return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.post("/api/v1/billing/webhooks/ARIFPAY/" + ACCOUNT + (token == null ? "" : "?t=" + token))
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	String orderStatus(String orderId) {
		return jdbc.queryForObject("SELECT order_status FROM payment_orders WHERE id = ?::uuid", String.class, orderId);
	}

	String attemptStatus(String attemptId) {
		return jdbc.queryForObject("SELECT attempt_status FROM payment_attempts WHERE id = ?::uuid", String.class,
				attemptId);
	}

	int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	String plan(User u) throws Exception {
		String body = mvc.perform(get("/api/v1/me/entitlements").header("Authorization", u.bearer()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.plan.planCode");
	}

	// ------------------------------------------------------------------------------------------------ options

	@Test
	void optionsOfferArifPayFirstOnlyForATrustedEthiopianIp() throws Exception {
		User u = registered();
		mvc.perform(fromEthiopia(get("/api/v1/billing/options")).header("Authorization", u.bearer())
				.param("clientPlatform", "ANDROID").param("distributionChannel", "DIRECT_DOWNLOAD"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.marketCountry").value("ET"))
				.andExpect(jsonPath("$.marketResolution.source").value("IP_EDGE"))
				.andExpect(jsonPath("$.marketResolution.confidence").value("HIGH"))
				.andExpect(jsonPath("$.purchaseFlow").value("LOCAL_GATEWAY_IN_APP"))
				.andExpect(jsonPath("$.purchaseUiAllowed").value(true))
				.andExpect(jsonPath("$.paymentMethods[0].methodCode").value("ARIFPAY_CHECKOUT"))
				.andExpect(jsonPath("$.paymentMethods[0].providerCode").value("ARIFPAY"))
				.andExpect(jsonPath("$.paymentMethods[0].methodType").value("HOSTED_CHECKOUT"))
				.andExpect(jsonPath("$.paymentMethods[0].isDefault").value(true))
				.andExpect(jsonPath("$.paymentMethods.length()").value(1))
				.andExpect(jsonPath("$.products[?(@.productCode == 'PLAN_PREMIUM')].prices[?(@.billingPeriod == 'MONTHLY')].listPrice.amountMinor")
						.value(29900));

		// hints alone (untrusted peer): global default, nothing to buy until RevenueCat (A2)
		mvc.perform(get("/api/v1/billing/options").with(r -> {
			r.setRemoteAddr("203.0.113.7");
			return r;
		}).header("Authorization", u.bearer()).header("CF-IPCountry", "ET").header("X-Qal-Network-Country", "ET")
				.header("X-Qal-Sim-Country", "ET").param("clientPlatform", "ANDROID")
				.param("distributionChannel", "DIRECT_DOWNLOAD")).andExpect(status().isOk())
				.andExpect(jsonPath("$.marketCountry").value("ZZ"))
				.andExpect(jsonPath("$.marketResolution.source").value("CLIENT_HINTS"))
				.andExpect(jsonPath("$.marketResolution.globalDefault").value(true))
				.andExpect(jsonPath("$.purchaseUiAllowed").value(false))
				.andExpect(jsonPath("$.paymentMethods.length()").value(0));

		// ET on the Play build: allowed in principle, but its seeded policy waits for a named reviewer (fail closed)
		mvc.perform(fromEthiopia(get("/api/v1/billing/options")).header("Authorization", u.bearer())
				.param("clientPlatform", "ANDROID").param("distributionChannel", "GOOGLE_PLAY"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.marketCountry").value("ET"))
				.andExpect(jsonPath("$.purchaseUiAllowed").value(false))
				.andExpect(jsonPath("$.purchaseFlow").value("NONE"));
	}

	// ------------------------------------------------------------------------------------------------ happy path

	@Test
	void verifiedWebhookPaysOnceGrantsPremiumAndTheReturnUrlAloneGrantsNothing() throws Exception {
		User u = registered();
		assertThat(plan(u)).isEqualTo("FREE"); // also fills the entitlement cache (TTL 30 s)
		Checkout c = checkout(u);
		assertThat(jdbc.queryForObject("SELECT metadata::text FROM payment_orders WHERE id = ?::uuid", String.class,
				c.orderId())).contains("\"source\": \"IP_EDGE\"", "\"country\": \"ET\"").doesNotContain("127.0.0.1");
		ARIFPAY.verify(postRequestedFor(urlPathEqualTo("/arifpay/api/sandbox/checkout/session"))
				.withRequestBody(WireMock.matchingJsonPath("$.nonce", WireMock.equalTo(c.attemptId())))
				.withRequestBody(WireMock.matchingJsonPath("$.notifyUrl", WireMock.containing("?t=" + token(c.attemptId())))));

		// the payer comes back before paying: only a verification is queued
		session(c, "PENDING", null, null, "0.0", "299.0");
		mvc.perform(get("/api/v1/billing/return/" + c.attemptId()).param("result", "success"))
				.andExpect(status().isSeeOther()).andExpect(header().string("Location",
						"qalkeyboard://billing/return?orderId=" + c.orderId() + "&attemptId=" + c.attemptId()
								+ "&result=success"));
		await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> ARIFPAY.verify(
				getRequestedFor(urlPathEqualTo("/arifpay/api/sandbox/checkout/session/" + c.sessionId()))));
		await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5))
				.until(() -> "REQUIRES_ACTION".equals(attemptStatus(c.attemptId())));
		assertThat(orderStatus(c.orderId())).isEqualTo("AWAITING_PAYMENT");
		assertThat(count("SELECT COUNT(*) FROM user_subscriptions WHERE user_id = ?", u.id())).isZero();

		// ArifPay notifies; the server verifies with the session document before granting
		paid(c);
		webhook(notification(c, "SUCCESS"), token(c.attemptId())).andExpect(status().isOk())
				.andExpect(jsonPath("$.received").value(true));
		await().atMost(Duration.ofSeconds(15)).until(() -> "PAID".equals(orderStatus(c.orderId())));
		assertThat(attemptStatus(c.attemptId())).isEqualTo("SUCCEEDED");
		assertThat(count("""
				SELECT COUNT(*) FROM payment_transactions WHERE payment_attempt_id = ?::uuid
				AND transaction_kind = 'CAPTURE' AND transaction_status = 'SUCCEEDED' AND amount_minor = 29900""",
				c.attemptId())).isEqualTo(1);
		assertThat(count("""
				SELECT COUNT(*) FROM user_subscriptions s JOIN subscription_plans p ON p.id = s.plan_id
				WHERE s.user_id = ? AND p.plan_code = 'PREMIUM' AND s.subscription_status = 'ACTIVE'
				  AND s.billing_mode = 'MANUAL_RENEW' AND s.source_type = 'PAYMENT'
				  AND s.current_period_end - s.current_period_start = INTERVAL '30 days'""", u.id())).isEqualTo(1);
		assertThat(count("""
				SELECT COUNT(*) FROM subscription_events e JOIN user_subscriptions s ON s.id = e.subscription_id
				WHERE s.user_id = ? AND e.event_type = 'ACTIVATED'""", u.id())).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT receipt_number FROM payment_receipts WHERE payment_order_id = ?::uuid",
				String.class, c.orderId())).matches("ET-\\d{4}-\\d{6}");

		mvc.perform(get("/api/v1/billing/orders/" + c.orderId()).header("Authorization", u.bearer()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"))
				.andExpect(jsonPath("$.fulfilment.subscriptionId").isNotEmpty())
				.andExpect(jsonPath("$.fulfilment.receiptId").isNotEmpty())
				.andExpect(jsonPath("$.allowedMethods.length()").value(0));
		mvc.perform(get("/api/v1/billing/orders/" + c.orderId() + "/attempts/" + c.attemptId())
				.header("Authorization", u.bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("SUCCEEDED")).andExpect(jsonPath("$.nextAction.type").value("NONE"));
		mvc.perform(get("/api/v1/me/subscriptions").header("Authorization", u.bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].planCode").value("PREMIUM"))
				.andExpect(jsonPath("$.items[0].providerCode").value("ARIFPAY"))
				.andExpect(jsonPath("$.items[0].methodCode").value("ARIFPAY_CHECKOUT"))
				.andExpect(jsonPath("$.items[0].billingMode").value("MANUAL_RENEW"));
		// the outbox invalidated the cached FREE resolution
		await().atMost(Duration.ofSeconds(10)).until(() -> "PREMIUM".equals(plan(u)));

		// duplicate delivery: acknowledged, stored once, nothing applied twice
		webhook(notification(c, "SUCCESS"), token(c.attemptId())).andExpect(status().isOk());
		assertThat(count("SELECT COUNT(*) FROM payment_webhook_events WHERE redacted_payload->>'merchantReference' = ?",
				c.attemptId())).isEqualTo(1);

		// out of order: a FAILED notification after success, even with the provider now saying FAILED, changes nothing
		session(c, "FAILED", null, "TELEBIRR_USSD", "0.0", "299.0");
		webhook(notification(c, "FAILED"), token(c.attemptId())).andExpect(status().isOk());
		await().atMost(Duration.ofSeconds(10)).until(() -> count("""
				SELECT COUNT(*) FROM payment_webhook_events WHERE redacted_payload->>'merchantReference' = ?
				AND event_status = 'PROCESSED'""", c.attemptId()) == 2);
		assertThat(attemptStatus(c.attemptId())).isEqualTo("SUCCEEDED");
		assertThat(orderStatus(c.orderId())).isEqualTo("PAID");
		assertThat(count("SELECT COUNT(*) FROM payment_transactions WHERE payment_attempt_id = ?::uuid", c.attemptId()))
				.isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM subscription_payment_allocations WHERE user_id = ?", u.id())).isEqualTo(1);
		// the stored payload is redacted (no phone number)
		assertThat(jdbc.queryForList("SELECT redacted_payload::text FROM payment_webhook_events WHERE "
				+ "redacted_payload->>'merchantReference' = ?", String.class, c.attemptId()).toString())
				.doesNotContain("251900000000");
	}

	@Test
	void buyingTheSamePlanAgainStacksTheTerm() throws Exception {
		User u = registered();
		Checkout first = checkout(u);
		paid(first);
		webhook(notification(first, "SUCCESS"), token(first.attemptId())).andExpect(status().isOk());
		await().atMost(Duration.ofSeconds(15)).until(() -> "PAID".equals(orderStatus(first.orderId())));
		Checkout second = checkout(u);
		paid(second);
		webhook(notification(second, "SUCCESS"), token(second.attemptId())).andExpect(status().isOk());
		await().atMost(Duration.ofSeconds(15)).until(() -> "PAID".equals(orderStatus(second.orderId())));
		assertThat(count("""
				SELECT COUNT(*) FROM user_subscriptions WHERE user_id = ?
				AND current_period_end - current_period_start = INTERVAL '60 days'""", u.id())).isEqualTo(1);
		assertThat(count("""
				SELECT COUNT(*) FROM subscription_events e JOIN user_subscriptions s ON s.id = e.subscription_id
				WHERE s.user_id = ? AND e.event_type = 'EXTENDED'""", u.id())).isEqualTo(1);
	}

	// ------------------------------------------------------------------------------------------------ refusals

	@Test
	void forgedWebhooksAre401AndChangeNothing() throws Exception {
		User u = registered();
		Checkout c = checkout(u);
		paid(c);
		webhook(notification(c, "SUCCESS"), null).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("WEBHOOK_SIGNATURE_INVALID"));
		webhook(notification(c, "SUCCESS"), token(UUID.randomUUID().toString())).andExpect(status().isUnauthorized());
		// stored once as INVALID under a key of its own (it can never occupy the dedupe key of the real event)
		assertThat(count("""
				SELECT COUNT(*) FROM payment_webhook_events WHERE signature_status = 'INVALID' AND event_status = 'IGNORED'
				AND external_event_id = 'invalid:' || payload_sha256 AND payload_sha256 = ?""",
				BillingErrors.sha256Hex(notification(c, "SUCCESS")))).isEqualTo(1);
		Thread.sleep(500);
		assertThat(orderStatus(c.orderId())).isEqualTo("AWAITING_PAYMENT");
		ARIFPAY.verify(0, getRequestedFor(urlPathEqualTo("/arifpay/api/sandbox/checkout/session/" + c.sessionId())));
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.post("/api/v1/billing/webhooks/ARIFPAY/no-such-account").contentType(MediaType.APPLICATION_JSON)
				.content("{}")).andExpect(status().isNotFound());
	}

	@Test
	void aNotificationClaimingSuccessIsOverruledByVerification() throws Exception {
		User u = registered();
		Checkout c = checkout(u);
		session(c, "FAILED", null, "TELEBIRR_USSD", "0.0", "299.0");
		webhook(notification(c, "SUCCESS"), token(c.attemptId())).andExpect(status().isOk());
		await().atMost(Duration.ofSeconds(15)).until(() -> "FAILED".equals(attemptStatus(c.attemptId())));
		assertThat(orderStatus(c.orderId())).isEqualTo("AWAITING_PAYMENT");
		assertThat(count("SELECT COUNT(*) FROM user_subscriptions WHERE user_id = ?", u.id())).isZero();
		assertThat(count("SELECT COUNT(*) FROM payment_transactions WHERE payment_attempt_id = ?::uuid", c.attemptId()))
				.isZero();
		mvc.perform(get("/api/v1/billing/orders/" + c.orderId() + "/attempts/" + c.attemptId())
				.header("Authorization", u.bearer())).andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.failureCode").value("ARIFPAY_FAILED"));
	}

	@Test
	void aSuccessForAnotherAmountIsAMismatchAndGrantsNothing() throws Exception {
		User u = registered();
		Checkout c = checkout(u);
		session(c, "SUCCESS", "SIM-X", "TELEBIRR_USSD", "1.0", "1.0");
		webhook(notification(c, "SUCCESS"), token(c.attemptId())).andExpect(status().isOk());
		await().atMost(Duration.ofSeconds(15)).until(() -> "UNKNOWN".equals(attemptStatus(c.attemptId())));
		assertThat(jdbc.queryForObject("SELECT failure_code FROM payment_attempts WHERE id = ?::uuid", String.class,
				c.attemptId())).isEqualTo("VERIFICATION_MISMATCH");
		assertThat(orderStatus(c.orderId())).isEqualTo("AWAITING_PAYMENT");
		assertThat(count("SELECT COUNT(*) FROM user_subscriptions WHERE user_id = ?", u.id())).isZero();
	}

	@Test
	void marketGuestAndHeaderRules() throws Exception {
		User u = registered();
		// a client claiming ET from an untrusted address is refused
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/billing/orders")
				.with(r -> {
					r.setRemoteAddr("203.0.113.7");
					return r;
				}).header("CF-IPCountry", "ET").header("Authorization", u.bearer())
				.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"productCode":"PLAN_PREMIUM","billingPeriod":"MONTHLY","marketCountry":"ET","salesChannel":"ANDROID_DIRECT"}"""))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BILLING_POLICY_DENIED"));
		// the Play build in ET is not approved yet
		mvc.perform(fromEthiopia(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.post("/api/v1/billing/orders")).header("Authorization", u.bearer())
				.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"productCode":"PLAN_PREMIUM","billingPeriod":"MONTHLY","marketCountry":"ET","salesChannel":"ANDROID_GOOGLE_PLAY"}"""))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BILLING_POLICY_DENIED"));
		UUID guest = transactions.execute(s -> accounts.createGuest(Instant.now()).id());
		mvc.perform(fromEthiopia(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.post("/api/v1/billing/orders"))
				.header("Authorization", "Bearer " + accessTokens.issue(guest, UUID.randomUUID(), null, "GUEST").value())
				.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"productCode":"PLAN_PREMIUM","billingPeriod":"MONTHLY","marketCountry":"ET","salesChannel":"ANDROID_DIRECT"}"""))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GUEST_NOT_ALLOWED"));
		mvc.perform(fromEthiopia(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.post("/api/v1/billing/orders")).header("Authorization", u.bearer())
				.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"productCode":"PLAN_PREMIUM","billingPeriod":"QUARTERLY","marketCountry":"ET","salesChannel":"ANDROID_DIRECT"}"""))
				.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("PRICE_NOT_AVAILABLE"));
	}

	// ------------------------------------------------------------------------------------------------ idempotency

	@Test
	void retriesAreIdempotentAndAnOpenCheckoutIsReused() throws Exception {
		User u = registered();
		String key = UUID.randomUUID().toString();
		String orderId = createOrder(u, key, "MONTHLY");
		assertThat(createOrder(u, key, "MONTHLY")).isEqualTo(orderId);
		mvc.perform(fromEthiopia(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.post("/api/v1/billing/orders")).header("Authorization", u.bearer()).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"productCode":"PLAN_PREMIUM","billingPeriod":"ANNUAL","marketCountry":"ET","salesChannel":"ANDROID_DIRECT"}"""))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

		String attemptKey = UUID.randomUUID().toString();
		String attemptId = createAttempt(u, orderId, attemptKey);
		assertThat(createAttempt(u, orderId, attemptKey)).isEqualTo(attemptId);
		assertThat(createAttempt(u, orderId, UUID.randomUUID().toString())).isEqualTo(attemptId);
		ARIFPAY.verify(1, postRequestedFor(urlPathEqualTo("/arifpay/api/sandbox/checkout/session")));
		assertThat(count("SELECT COUNT(*) FROM payment_attempts WHERE payment_order_id = ?::uuid", orderId)).isEqualTo(1);

		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.post("/api/v1/billing/orders/" + orderId + "/cancel").header("Authorization", u.bearer()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.post("/api/v1/billing/orders/" + orderId + "/attempts").header("Authorization", u.bearer())
				.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"methodCode\":\"ARIFPAY_CHECKOUT\"}")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ORDER_NOT_PAYABLE"));
	}

	@Test
	void providerOutageIs503AndLeavesTheAttemptForReconciliation() throws Exception {
		User u = registered();
		String orderId = createOrder(u, UUID.randomUUID().toString(), "MONTHLY");
		ARIFPAY.stubFor(post(urlPathEqualTo("/arifpay/api/sandbox/checkout/session"))
				.willReturn(aResponse().withStatus(502)));
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.post("/api/v1/billing/orders/" + orderId + "/attempts").header("Authorization", u.bearer())
				.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"methodCode\":\"ARIFPAY_CHECKOUT\"}")).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("PROVIDER_UNAVAILABLE"));
		assertThat(jdbc.queryForObject("SELECT attempt_status FROM payment_attempts WHERE payment_order_id = ?::uuid",
				String.class, orderId)).isEqualTo("UNKNOWN");
	}

	// ------------------------------------------------------------------------------------------------ reconciliation

	@Test
	void theReconciliationJobVerifiesStuckAttempts() throws Exception {
		User paidUser = registered();
		Checkout stuck = checkout(paidUser);
		paid(stuck); // paid at ArifPay, but the notification never arrived
		User leftUser = registered();
		Checkout abandoned = checkout(leftUser);
		session(abandoned, "PENDING", null, null, "0.0", "299.0");
		jdbc.update("""
				UPDATE payment_attempts SET created_at = NOW() - INTERVAL '20 minutes'
				WHERE id IN (?::uuid, ?::uuid)""", stuck.attemptId(), abandoned.attemptId());
		jdbc.update("UPDATE payment_attempts SET expires_at = NOW() - INTERVAL '1 hour' WHERE id = ?::uuid",
				abandoned.attemptId());

		PaymentReconciler.RunResult run = reconciler.runOnce();
		assertThat(run.paid()).isGreaterThanOrEqualTo(1);
		assertThat(orderStatus(stuck.orderId())).isEqualTo("PAID");
		assertThat(count("SELECT COUNT(*) FROM user_subscriptions WHERE user_id = ?", paidUser.id())).isEqualTo(1);
		assertThat(attemptStatus(abandoned.attemptId())).isEqualTo("EXPIRED");
		assertThat(count("SELECT COUNT(*) FROM user_subscriptions WHERE user_id = ?", leftUser.id())).isZero();

		// an attempt checked a moment ago is not checked again until recheck-every passed
		Checkout recent = checkout(registered());
		session(recent, "PENDING", null, "CBE", "0.0", "299.0");
		jdbc.update("UPDATE payment_attempts SET created_at = NOW() - INTERVAL '20 minutes' WHERE id = ?::uuid",
				recent.attemptId());
		reconciler.runOnce();
		assertThat(attemptStatus(recent.attemptId())).isEqualTo("PENDING");
		ARIFPAY.resetRequests();
		reconciler.runOnce();
		ARIFPAY.verify(0, getRequestedFor(urlPathEqualTo("/arifpay/api/sandbox/checkout/session/" + recent.sessionId())));
	}

	// ------------------------------------------------------------------------------------------------ admin switch

	@Test
	void anAdminSwitchesTheDefaultProviderWithRevisionConcurrency() throws Exception {
		User admin = registered();
		jdbc.update("INSERT INTO user_admin_roles (user_id, admin_role_id) SELECT ?, id FROM admin_roles WHERE role_code = 'FINANCE'",
				admin.id());
		String path = "/admin/v1/billing/provider-routes/ET/ANDROID/DIRECT_DOWNLOAD";
		mvc.perform(get(path).header("Authorization", admin.bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.revision").value(1))
				.andExpect(jsonPath("$.routes[0].providerCode").value("ARIFPAY"))
				.andExpect(jsonPath("$.routes[0].adapterRegistered").value(true))
				.andExpect(jsonPath("$.routes[0].providerActive").value(true))
				.andExpect(jsonPath("$.routes[1].providerCode").value("CHAPA"))
				.andExpect(jsonPath("$.routes[1].adapterRegistered").value(false));
		mvc.perform(put(path).header("Authorization", registered().bearer()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedRevision\":1,\"routes\":[]}")).andExpect(status().isForbidden());
		mvc.perform(put(path).header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedRevision\":0,\"routes\":[{\"providerCode\":\"CHAPA\"}]}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVISION_CONFLICT"))
				.andExpect(jsonPath("$.currentRevision").value(1));
		try {
			mvc.perform(put(path).header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"expectedRevision":1,"routes":[{"providerCode":"CHAPA","notes":"switch"},{"providerCode":"ARIFPAY"}]}"""))
					.andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2))
					.andExpect(jsonPath("$.routes[0].providerCode").value("CHAPA"))
					.andExpect(jsonPath("$.routes[1].priority").value(2));
			// Chapa has no adapter yet (A2): ArifPay stays the effective default, now at priority 2
			mvc.perform(fromEthiopia(get("/api/v1/billing/options")).header("Authorization", admin.bearer())
					.param("clientPlatform", "ANDROID").param("distributionChannel", "DIRECT_DOWNLOAD"))
					.andExpect(jsonPath("$.paymentMethods[0].providerCode").value("ARIFPAY"))
					.andExpect(jsonPath("$.paymentMethods[0].priority").value(2))
					.andExpect(jsonPath("$.paymentMethods[0].isDefault").value(true));
			assertThat(count("""
					SELECT COUNT(*) FROM audit_events WHERE action_code = 'BILLING_PROVIDER_ROUTES_REPLACE'
					AND entity_id = 'ET/ANDROID/DIRECT_DOWNLOAD' AND outcome = 'SUCCESS'""")).isEqualTo(1);
			mvc.perform(put(path).header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
					.content("{\"expectedRevision\":2,\"routes\":[{\"providerCode\":\"NOPE\"}]}"))
					.andExpect(status().isBadRequest());
		}
		finally {
			mvc.perform(put(path).header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"expectedRevision":2,"routes":[{"providerCode":"ARIFPAY"},{"providerCode":"CHAPA"}]}"""))
					.andExpect(status().isOk());
		}
	}
}
