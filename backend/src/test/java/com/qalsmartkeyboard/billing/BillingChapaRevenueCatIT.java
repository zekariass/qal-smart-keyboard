package com.qalsmartkeyboard.billing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
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
 * ADR 0045 step A2 and ADR 0046 end to end on PostgreSQL 18 with the real security chain and outbox, contract-validated,
 * against one WireMock standing in for ArifPay, Chapa and RevenueCat (shapes from their documentation):
 * <ul>
 * <li>Chapa: hosted checkout, valid / forged / duplicate / out-of-order signed webhooks that only trigger verification,
 * verification that grants.</li>
 * <li>The Ethiopian default switches ArifPay to Chapa and back through the admin routes endpoint alone (options and the
 * checkout provider follow).</li>
 * <li>RevenueCat: options offer it globally, the client report and webhooks are verified with the REST API before a
 * STORE_MANAGED term is granted, webhook auth and dedupe, refund and expiration revoke, the entitlement does not depend on
 * the current country.</li>
 * </ul>
 */
@SpringBootTest(properties = { "qal.outbox.poll-interval=100ms", "qal.billing.arifpay.api-key=it-arifpay-key",
		"qal.billing.arifpay.webhook-secret=it-webhook-secret-0123456789-abcdefghij",
		"qal.billing.chapa.secret-key=CHASECK_TEST-it-chapa-key",
		"qal.billing.chapa.webhook-secret=" + BillingChapaRevenueCatIT.CHAPA_SECRET,
		"qal.billing.revenuecat.secret-api-key=sk_it_revenuecat",
		"qal.billing.revenuecat.webhook-auth=" + BillingChapaRevenueCatIT.RC_AUTH,
		"qal.billing.public-base-url=https://api.example.test", "qal.geo.trusted-proxies=127.0.0.1/32,::1/128" })
@AutoConfigureMockMvc
@ContractValidated
@Testcontainers
class BillingChapaRevenueCatIT {

	static final String CHAPA_SECRET = "it-chapa-webhook-secret-hash";
	static final String RC_AUTH = "Bearer it-revenuecat-webhook-authorization";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	static final WireMockServer WM = new WireMockServer(
			options().dynamicPort().globalTemplating(true).notifier(new ConsoleNotifier(false)));

	static {
		WM.start();
	}

	@DynamicPropertySource
	static void urls(DynamicPropertyRegistry registry) {
		registry.add("qal.billing.arifpay.base-url", () -> WM.baseUrl() + "/arifpay");
		registry.add("qal.billing.chapa.base-url", () -> WM.baseUrl() + "/chapa");
		registry.add("qal.billing.revenuecat.base-url", () -> WM.baseUrl() + "/rc");
	}

	@AfterAll
	static void stop() {
		WM.stop();
	}

	record User(UUID id, String bearer) {
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

	User registered() {
		UUID id = transactions.execute(s -> {
			String phone = "+2519" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
			return ((PhoneSignIn.SignedIn) accounts.signInWithPhone(phone, true, Instant.now())).user().id();
		});
		return new User(id, "Bearer " + accessTokens.issue(id, UUID.randomUUID(), null, "REGISTERED").value());
	}

	User admin() {
		User a = registered();
		jdbc.update("INSERT INTO user_admin_roles (user_id, admin_role_id) SELECT ?, id FROM admin_roles WHERE role_code = 'FINANCE'",
				a.id());
		return a;
	}

	/** Go-live switches for ArifPay and Chapa (ET direct and Play policies) and RevenueCat (global Play policy). */
	@BeforeEach
	void activate() {
		WM.resetAll();
		WM.stubFor(post(urlPathEqualTo("/arifpay/api/sandbox/checkout/session")).willReturn(aResponse()
				.withHeader("Content-Type", "application/json").withBody("""
						{"error":false,"msg":"No Errors","data":{"sessionId":"ARP-{{jsonPath request.body '$.nonce'}}",
						"paymentUrl":"https://checkout.arifpay.org/checkout/ARP-{{jsonPath request.body '$.nonce'}}",
						"totalAmount":{{jsonPath request.body '$.items[0].price'}} }}""")));
		WM.stubFor(post(urlPathEqualTo("/chapa/v1/transaction/initialize")).willReturn(aResponse()
				.withHeader("Content-Type", "application/json").withBody("""
						{"message":"Hosted Link","status":"success","data":{
						"checkout_url":"https://checkout.chapa.co/checkout/payment/{{jsonPath request.body '$.tx_ref'}}"}}""")));
		UUID reviewer = registered().id();
		jdbc.update("""
				UPDATE payment_providers SET provider_status = 'ACTIVE', is_active = TRUE
				WHERE provider_code IN ('ARIFPAY', 'CHAPA', 'REVENUECAT')""");
		jdbc.update("""
				UPDATE payment_provider_accounts SET account_status = 'ACTIVE', is_active = TRUE
				WHERE account_code IN ('arifpay-et-sandbox', 'chapa-et-sandbox')""");
		jdbc.update("""
				UPDATE provider_payment_methods SET availability_status = 'AVAILABLE', is_enabled = TRUE
				WHERE method_code IN ('ARIFPAY_CHECKOUT', 'CHAPA_CHECKOUT')""");
		jdbc.update("""
				UPDATE payment_provider_account_methods SET availability_status = 'AVAILABLE', is_enabled = TRUE
				WHERE provider_account_id IN (SELECT id FROM payment_provider_accounts
				                              WHERE account_code IN ('arifpay-et-sandbox', 'chapa-et-sandbox'))""");
		jdbc.update("UPDATE billing_products SET product_status = 'ACTIVE' WHERE product_code IN ('PLAN_PREMIUM', 'PLAN_PREMIUM_PLUS')");
		jdbc.update("""
				UPDATE billing_market_channel_policies
				   SET approval_status = 'APPROVED', reviewed_at = NOW(), reviewed_by_user_id = COALESCE(reviewed_by_user_id, ?),
				       purchase_ui_allowed = TRUE, external_payment_allowed = (purchase_flow <> 'STORE_BILLING')
				 WHERE (country_code = 'ET' AND client_platform = 'ANDROID')
				    OR (country_code = 'ZZ' AND client_platform = 'ANDROID' AND distribution_channel = 'GOOGLE_PLAY')""",
				reviewer);
		jdbc.update("""
				UPDATE external_product_mappings SET is_active = TRUE
				WHERE environment = 'SANDBOX' AND provider_id = (SELECT id FROM payment_providers WHERE provider_code = 'REVENUECAT')""");
	}

	// ------------------------------------------------------------------------------------------------ helpers

	static MockHttpServletRequestBuilder fromCountry(MockHttpServletRequestBuilder b, String country) {
		return b.header("CF-IPCountry", country).header("X-Qal-Network-Country", country.toLowerCase())
				.header("X-Client-Platform", "ANDROID");
	}

	String createOrder(User u) throws Exception {
		String body = mvc.perform(fromCountry(MockMvcRequestBuilders.post("/api/v1/billing/orders"), "ET")
				.header("Authorization", u.bearer()).header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"productCode":"PLAN_PREMIUM","billingPeriod":"MONTHLY","marketCountry":"ET","salesChannel":"ANDROID_DIRECT"}"""))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.orderId");
	}

	String createAttempt(User u, String orderId, String methodCode, String providerCode) throws Exception {
		String body = mvc.perform(MockMvcRequestBuilders.post("/api/v1/billing/orders/" + orderId + "/attempts")
				.header("Authorization", u.bearer()).header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON).content("{\"methodCode\":\"" + methodCode + "\"}"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("REQUIRES_ACTION"))
				.andExpect(jsonPath("$.providerCode").value(providerCode)).andReturn().getResponse()
				.getContentAsString();
		return JsonPath.read(body, "$.attemptId");
	}

	ResultActions billingOptions(User u, String country, String channel) throws Exception {
		return mvc.perform(fromCountry(get("/api/v1/billing/options"), country).header("Authorization", u.bearer())
				.param("clientPlatform", "ANDROID").param("distributionChannel", channel)).andExpect(status().isOk());
	}

	/** Chapa's verify document for one tx_ref (developer.chapa.co/integrations/verify-payments). */
	static void chapaVerify(String txRef, String status, String amount) {
		WM.stubFor(WireMock.get(urlPathEqualTo("/chapa/v1/transaction/verify/" + txRef))
				.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
						{"message":"Payment details","status":"success","data":{"first_name":"A","last_name":"B",
						"email":null,"currency":"ETB","amount":%s,"charge":10.47,"mode":"test","method":"telebirr",
						"type":"API","status":"%s","reference":"CHREF%s","tx_ref":"%s","customization":{},"meta":null,
						"created_at":"2026-11-20T07:05:23.000000Z","updated_at":"2026-11-20T07:06:01.000000Z"}}"""
						.formatted(amount, status, txRef.substring(0, 8), txRef))));
	}

	static String chapaEvent(String txRef, String event, String status) {
		return """
				{"event":"%s","first_name":"Abebe","last_name":"Bikila","email":null,"mobile":"251900000000","currency":"ETB",
				"amount":"299.00","charge":"10.47","status":"%s","mode":"test","reference":"CHREF%s",
				"created_at":"2026-11-20T19:21:18.000000Z","updated_at":"2026-11-20T19:21:27.000000Z","type":"API",
				"tx_ref":"%s","payment_method":"telebirr","customization":{"title":null,"description":null,"logo":null},"meta":null}"""
				.formatted(event, status, txRef.substring(0, 8), txRef);
	}

	static String hmac(String key, String body) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return java.util.HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
	}

	ResultActions chapaWebhook(String body, String signature) throws Exception {
		MockHttpServletRequestBuilder b = MockMvcRequestBuilders.post("/api/v1/billing/webhooks/CHAPA/chapa-et-sandbox")
				.contentType(MediaType.APPLICATION_JSON).content(body);
		if (signature != null) {
			b = b.header("x-chapa-signature", signature);
		}
		return mvc.perform(b);
	}

	String one(String sql, Object... args) {
		return jdbc.queryForObject(sql, String.class, args);
	}

	int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	String plan(User u, String country) throws Exception {
		String body = mvc.perform(fromCountry(get("/api/v1/me/entitlements"), country).header("Authorization",
				u.bearer())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.plan.planCode");
	}

	// ------------------------------------------------------------------------------------------------ Chapa

	@Test
	void chapaWebhooksOnlyTriggerVerificationAndAVerifiedPaymentGrantsOnce() throws Exception {
		User u = registered();
		String orderId = createOrder(u);
		String attemptId = createAttempt(u, orderId, "CHAPA_CHECKOUT", "CHAPA");
		WM.verify(postRequestedFor(urlPathEqualTo("/chapa/v1/transaction/initialize"))
				.withHeader("Authorization", equalTo("Bearer CHASECK_TEST-it-chapa-key"))
				.withRequestBody(matchingJsonPath("$.tx_ref", equalTo(attemptId)))
				.withRequestBody(matchingJsonPath("$.amount", equalTo("299.00")))
				.withRequestBody(matchingJsonPath("$.callback_url",
						equalTo("https://api.example.test/api/v1/billing/return/" + attemptId))));
		assertThat(one("SELECT provider_payment_reference FROM payment_attempts WHERE id = ?::uuid", attemptId))
				.isEqualTo(attemptId);

		// forged: 401, stored as INVALID, never verified
		String success = chapaEvent(attemptId, "charge.success", "success");
		chapaWebhook(success, null).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("WEBHOOK_SIGNATURE_INVALID"));
		chapaWebhook(success, hmac("wrong-secret", success)).andExpect(status().isUnauthorized());
		Thread.sleep(300);
		WM.verify(0, getRequestedFor(urlPathEqualTo("/chapa/v1/transaction/verify/" + attemptId)));

		// a signed success whose verification says pending grants nothing
		chapaVerify(attemptId, "pending", "299");
		chapaWebhook(success, hmac(CHAPA_SECRET, success)).andExpect(status().isOk())
				.andExpect(jsonPath("$.received").value(true));
		await().atMost(Duration.ofSeconds(15)).until(() -> "PENDING".equals(
				one("SELECT attempt_status FROM payment_attempts WHERE id = ?::uuid", attemptId)));
		assertThat(one("SELECT order_status FROM payment_orders WHERE id = ?::uuid", orderId))
				.isEqualTo("AWAITING_PAYMENT");

		// Chapa's callback (GET on the return URL) queues a verification; now Chapa says success
		chapaVerify(attemptId, "success", "299");
		mvc.perform(get("/api/v1/billing/return/" + attemptId).param("trx_ref", attemptId).param("status", "success"))
				.andExpect(status().isSeeOther());
		await().atMost(Duration.ofSeconds(15)).until(() -> "PAID".equals(
				one("SELECT order_status FROM payment_orders WHERE id = ?::uuid", orderId)));
		assertThat(count("""
				SELECT COUNT(*) FROM payment_transactions WHERE payment_attempt_id = ?::uuid AND transaction_kind = 'CAPTURE'
				AND transaction_status = 'SUCCEEDED' AND amount_minor = 29900 AND provider_transaction_reference = ?""",
				attemptId, "CHREF" + attemptId.substring(0, 8))).isEqualTo(1);
		await().atMost(Duration.ofSeconds(10)).until(() -> "PREMIUM".equals(plan(u, "ET")));

		// duplicate delivery: acknowledged, stored once (Chapa sends no id: event:reference:status)
		chapaWebhook(success, hmac(CHAPA_SECRET, success)).andExpect(status().isOk());
		assertThat(count("SELECT COUNT(*) FROM payment_webhook_events WHERE external_event_id = ?",
				"chapa:charge.success:CHREF" + attemptId.substring(0, 8) + ":success")).isEqualTo(1);
		// out of order: a failed event after success, even with Chapa now saying failed, changes nothing
		chapaVerify(attemptId, "failed", "299");
		String failed = chapaEvent(attemptId, "charge.failed/cancelled", "failed/cancelled");
		chapaWebhook(failed, hmac(CHAPA_SECRET, failed)).andExpect(status().isOk());
		await().atMost(Duration.ofSeconds(10)).until(() -> count("""
				SELECT COUNT(*) FROM payment_webhook_events WHERE redacted_payload->>'merchantReference' = ?
				AND event_status = 'PROCESSED'""", attemptId) == 2);
		assertThat(one("SELECT attempt_status FROM payment_attempts WHERE id = ?::uuid", attemptId))
				.isEqualTo("SUCCEEDED");
		assertThat(count("SELECT COUNT(*) FROM user_subscriptions WHERE user_id = ?", u.id())).isEqualTo(1);
		// stored payloads carry no payer data
		assertThat(jdbc.queryForList("SELECT redacted_payload::text FROM payment_webhook_events WHERE "
				+ "redacted_payload->>'merchantReference' = ?", String.class, attemptId).toString())
				.doesNotContain("251900000000", "Abebe", "Bikila");
		// the term keeps working when the user travels
		assertThat(plan(u, "US")).isEqualTo("PREMIUM");
	}

	@Test
	void theEthiopianDefaultSwitchesBetweenArifPayAndChapaThroughTheAdminEndpointOnly() throws Exception {
		User admin = admin();
		User buyer = registered();
		String path = "/admin/v1/billing/provider-routes/ET/ANDROID/DIRECT_DOWNLOAD";
		billingOptions(buyer, "ET", "DIRECT_DOWNLOAD").andExpect(jsonPath("$.paymentMethods[0].methodCode").value("ARIFPAY_CHECKOUT"))
				.andExpect(jsonPath("$.paymentMethods[0].isDefault").value(true))
				.andExpect(jsonPath("$.paymentMethods[1].methodCode").value("CHAPA_CHECKOUT"));
		mvc.perform(get(path).header("Authorization", admin.bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.routes[1].providerCode").value("CHAPA"))
				.andExpect(jsonPath("$.routes[1].adapterRegistered").value(true));
		int revision = JsonPath.read(mvc.perform(get(path).header("Authorization", admin.bearer())).andReturn()
				.getResponse().getContentAsString(), "$.revision");
		try {
			mvc.perform(put(path).header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
					.content("{\"expectedRevision\":" + revision
							+ ",\"routes\":[{\"providerCode\":\"CHAPA\"},{\"providerCode\":\"ARIFPAY\"}]}"))
					.andExpect(status().isOk());
			String opts = billingOptions(buyer, "ET", "DIRECT_DOWNLOAD")
					.andExpect(jsonPath("$.paymentMethods[0].methodCode").value("CHAPA_CHECKOUT"))
					.andExpect(jsonPath("$.paymentMethods[0].isDefault").value(true))
					.andExpect(jsonPath("$.paymentMethods[1].methodCode").value("ARIFPAY_CHECKOUT"))
					.andReturn().getResponse().getContentAsString();
			// the app takes the default option: the checkout now starts at Chapa
			String defaultMethod = JsonPath.read(opts, "$.paymentMethods[0].methodCode");
			String attemptId = createAttempt(buyer, createOrder(buyer), defaultMethod, "CHAPA");
			WM.verify(postRequestedFor(urlPathEqualTo("/chapa/v1/transaction/initialize"))
					.withRequestBody(matchingJsonPath("$.tx_ref", equalTo(attemptId))));
			WM.verify(0, postRequestedFor(urlPathEqualTo("/arifpay/api/sandbox/checkout/session")));
		}
		finally {
			mvc.perform(put(path).header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
					.content("{\"expectedRevision\":" + (revision + 1)
							+ ",\"routes\":[{\"providerCode\":\"ARIFPAY\"},{\"providerCode\":\"CHAPA\"}]}"))
					.andExpect(status().isOk());
		}
		String back = billingOptions(buyer, "ET", "DIRECT_DOWNLOAD")
				.andExpect(jsonPath("$.paymentMethods[0].methodCode").value("ARIFPAY_CHECKOUT")).andReturn()
				.getResponse().getContentAsString();
		createAttempt(buyer, createOrder(buyer), JsonPath.read(back, "$.paymentMethods[0].methodCode"), "ARIFPAY");
	}

	// ------------------------------------------------------------------------------------------------ RevenueCat

	/** A v1 subscriber document (developer docs shape) with one Play subscription of qal_premium. */
	static void rcSubscriber(UUID user, String expires, String refundedAt, String unsubscribedAt, boolean sandbox) {
		WM.stubFor(WireMock.get(urlPathEqualTo("/rc/v1/subscribers/" + user))
				.withHeader("Authorization", equalTo("Bearer sk_it_revenuecat"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
						{"request_date":"2026-11-20T12:00:00Z","request_date_ms":1795176000000,"subscriber":{
						"original_app_user_id":"%1$s","management_url":"https://play.google.com/store/account/subscriptions",
						"first_seen":"2026-11-01T00:00:00Z","entitlements":{"premium":{"expires_date":"%2$s",
						"grace_period_expires_date":null,"product_identifier":"qal_premium","purchase_date":"2026-11-20T10:00:00Z"}},
						"non_subscriptions":{},"other_purchases":{},"subscriptions":{"qal_premium":{"auto_resume_date":null,
						"billing_issues_detected_at":null,"expires_date":"%2$s","grace_period_expires_date":null,
						"is_sandbox":%5$s,"original_purchase_date":"2026-10-20T10:00:00Z","ownership_type":"PURCHASED",
						"period_type":"normal","purchase_date":"2026-11-20T10:00:00Z","refunded_at":%3$s,"store":"play_store",
						"store_transaction_id":"GPA.3333-1111-2222-44444","unsubscribe_detected_at":%4$s,
						"product_plan_identifier":"monthly"}}}}"""
						.formatted(user, expires, refundedAt == null ? "null" : "\"" + refundedAt + "\"",
								unsubscribedAt == null ? "null" : "\"" + unsubscribedAt + "\"", sandbox))));
	}

	static String future() {
		return Instant.now().plus(Duration.ofDays(30)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
	}

	static String rcEvent(String id, String type, UUID user, String extra) {
		return """
				{"api_version":"1.0","event":{"id":"%s","type":"%s","app_user_id":"%s","original_app_user_id":"%s",
				"aliases":["%s"],"product_id":"qal_premium:monthly","period_type":"NORMAL","purchased_at_ms":1795168800000,
				"expiration_at_ms":1797760800000,"environment":"SANDBOX","entitlement_ids":["premium"],"store":"PLAY_STORE",
				"transaction_id":"GPA.3333","original_transaction_id":"GPA.3333","event_timestamp_ms":1795168801000,
				"app_id":"app1","country_code":"ET",
				"subscriber_attributes":{"$email":{"value":"buyer@example.com","updated_at_ms":1}}%s}}"""
				.formatted(id, type, user, user, user, extra);
	}

	ResultActions rcWebhook(String body, String authorization) throws Exception {
		MockHttpServletRequestBuilder b = MockMvcRequestBuilders.post("/api/v1/billing/store-notifications/REVENUECAT")
				.contentType(MediaType.APPLICATION_JSON).content(body);
		if (authorization != null) {
			b = b.header("Authorization", authorization);
		}
		return mvc.perform(b);
	}

	String storeStatus(UUID user) {
		return jdbc.queryForList("SELECT purchase_status FROM store_purchases WHERE user_id = ?", String.class, user)
				.stream().findFirst().orElse(null);
	}

	@Test
	void revenueCatIsTheGlobalOptionAndAClientReportIsVerifiedBeforeAStoreManagedGrant() throws Exception {
		User u = registered();
		billingOptions(u, "US", "GOOGLE_PLAY").andExpect(jsonPath("$.marketResolution.globalDefault").value(true))
				.andExpect(jsonPath("$.purchaseFlow").value("STORE_BILLING"))
				.andExpect(jsonPath("$.paymentMethods[0].providerCode").value("REVENUECAT"))
				.andExpect(jsonPath("$.paymentMethods[0].isDefault").value(true));

		// RevenueCat does not know the purchase (yet): nothing is granted
		WM.stubFor(WireMock.get(urlPathEqualTo("/rc/v1/subscribers/" + u.id())).willReturn(aResponse()
				.withStatus(201).withHeader("Content-Type", "application/json").withBody("""
						{"request_date_ms":1,"subscriber":{"original_app_user_id":"x","entitlements":{},"subscriptions":{},
						"non_subscriptions":{}}}""")));
		String report = "{\"provider\":\"REVENUECAT\",\"productId\":\"qal_premium:monthly\"}";
		mvc.perform(MockMvcRequestBuilders.post("/api/v1/billing/store-purchases").header("Authorization", u.bearer())
				.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
				.content(report)).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("STORE_PURCHASE_INVALID"));
		assertThat(count("SELECT COUNT(*) FROM user_subscriptions WHERE user_id = ?", u.id())).isZero();

		rcSubscriber(u.id(), future(), null, null, true);
		mvc.perform(MockMvcRequestBuilders.post("/api/v1/billing/store-purchases").header("Authorization", u.bearer())
				.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
				.content(report)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.environment").value("SANDBOX"))
				.andExpect(jsonPath("$.productId").value("qal_premium"))
				.andExpect(jsonPath("$.autoRenewing").value(true))
				.andExpect(jsonPath("$.subscription.planCode").value("PREMIUM"))
				.andExpect(jsonPath("$.subscription.sourceType").value("STORE"))
				.andExpect(jsonPath("$.subscription.billingMode").value("STORE_MANAGED"))
				.andExpect(jsonPath("$.subscription.providerCode").value("REVENUECAT"))
				.andExpect(jsonPath("$.subscription.manageUrl")
						.value("https://play.google.com/store/account/subscriptions"));
		await().atMost(Duration.ofSeconds(10)).until(() -> "PREMIUM".equals(plan(u, "US")));
		// travelling: the entitlement does not depend on the current country
		assertThat(plan(u, "ET")).isEqualTo("PREMIUM");
		// re-reporting is idempotent
		mvc.perform(MockMvcRequestBuilders.post("/api/v1/billing/store-purchases").header("Authorization", u.bearer())
				.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
				.content(report)).andExpect(status().isOk());
		assertThat(count("SELECT COUNT(*) FROM store_purchases WHERE user_id = ?", u.id())).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM user_subscriptions WHERE user_id = ?", u.id())).isEqualTo(1);

		// the A1 Play shape (token + package) is verified the same way, through RevenueCat by the caller's user id;
		// the token is neither trusted nor stored
		User play = registered();
		rcSubscriber(play.id(), future(), null, null, true);
		mvc.perform(MockMvcRequestBuilders.post("/api/v1/billing/store-purchases").header("Authorization", play.bearer())
				.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"provider":"GOOGLE_PLAY","productId":"qal_premium","purchaseToken":"play-token-it-123",
						"packageName":"com.qalsmartkeyboard.app"}"""))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.subscription.billingMode").value("STORE_MANAGED"));
		WM.verify(getRequestedFor(urlPathEqualTo("/rc/v1/subscribers/" + play.id())));
		assertThat(count("""
				SELECT COUNT(*) FROM store_purchases WHERE user_id = ?
				AND (metadata::text LIKE '%play-token-it-123%' OR latest_transaction_id = 'play-token-it-123')""",
				play.id())).isZero();

		// a production purchase is not honoured by this (sandbox) server; an unmapped product is not a plan
		User other = registered();
		rcSubscriber(other.id(), future(), null, null, false);
		mvc.perform(MockMvcRequestBuilders.post("/api/v1/billing/store-purchases").header("Authorization",
				other.bearer()).header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON).content(report)).andExpect(status().isUnprocessableContent());
		assertThat(count("SELECT COUNT(*) FROM store_purchases WHERE user_id = ?", other.id())).isZero();
		UUID guest = transactions.execute(s -> accounts.createGuest(Instant.now()).id());
		mvc.perform(MockMvcRequestBuilders.post("/api/v1/billing/store-purchases")
				.header("Authorization", "Bearer " + accessTokens.issue(guest, UUID.randomUUID(), null, "GUEST").value())
				.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
				.content(report)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GUEST_NOT_ALLOWED"));
		// RevenueCat down: 503, nothing changes
		User down = registered();
		WM.stubFor(WireMock.get(urlPathEqualTo("/rc/v1/subscribers/" + down.id())).willReturn(aResponse().withStatus(502)));
		mvc.perform(MockMvcRequestBuilders.post("/api/v1/billing/store-purchases").header("Authorization", down.bearer())
				.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
				.content(report)).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("PROVIDER_UNAVAILABLE"));
	}

	@Test
	void revenueCatWebhooksAreAuthenticatedDedupedAndReVerifiedThenRefundAndExpiryRevoke() throws Exception {
		User u = registered();
		assertThat(plan(u, "US")).isEqualTo("FREE"); // fills the entitlement cache
		rcSubscriber(u.id(), future(), null, null, true);
		String purchase = rcEvent(UUID.randomUUID().toString(), "INITIAL_PURCHASE", u.id(), "");

		// wrong or missing Authorization: 401, stored as INVALID, RevenueCat never asked
		rcWebhook(purchase, "Bearer wrong").andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("WEBHOOK_SIGNATURE_INVALID"));
		rcWebhook(purchase, null).andExpect(status().isUnauthorized());
		Thread.sleep(300);
		WM.verify(0, getRequestedFor(urlPathEqualTo("/rc/v1/subscribers/" + u.id())));
		assertThat(count("SELECT COUNT(*) FROM user_subscriptions WHERE user_id = ?", u.id())).isZero();

		rcWebhook(purchase, RC_AUTH).andExpect(status().isOk()).andExpect(jsonPath("$.received").value(true));
		await().atMost(Duration.ofSeconds(15)).until(() -> "PREMIUM".equals(plan(u, "US")));
		assertThat(storeStatus(u.id())).isEqualTo("ACTIVE");
		WM.verify(getRequestedFor(urlPathEqualTo("/rc/v1/subscribers/" + u.id())));
		// the raw event is kept on the purchase, without subscriber attributes
		String details = one("""
				SELECT e.event_details::text FROM store_purchase_events e JOIN store_purchases p ON p.id = e.store_purchase_id
				WHERE p.user_id = ? AND e.notification_type = 'INITIAL_PURCHASE'""", u.id());
		assertThat(details).contains("GPA.3333").doesNotContain("buyer@example.com");
		assertThat(one("""
				SELECT s.billing_mode || '/' || s.source_type || '/' || s.subscription_status FROM user_subscriptions s
				WHERE s.user_id = ?""", u.id())).isEqualTo("STORE_MANAGED/STORE/ACTIVE");

		// duplicate delivery: acknowledged, stored once
		rcWebhook(purchase, RC_AUTH).andExpect(status().isOk());
		assertThat(count("SELECT COUNT(*) FROM payment_webhook_events WHERE external_event_id = ?",
				JsonPath.<String>read(purchase, "$.event.id"))).isEqualTo(1);

		// a webhook body that lies (claims a renewal) changes nothing the REST API does not confirm: here RevenueCat
		// reports the purchase refunded, so a "RENEWAL" event revokes (state comes from the API, order is irrelevant)
		rcSubscriber(u.id(), future(), Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
				.toString(), null, true);
		rcWebhook(rcEvent(UUID.randomUUID().toString(), "RENEWAL", u.id(), ""), RC_AUTH).andExpect(status().isOk());
		await().atMost(Duration.ofSeconds(15)).until(() -> "REFUNDED".equals(storeStatus(u.id())));
		await().atMost(Duration.ofSeconds(10)).until(() -> "FREE".equals(plan(u, "US")));
		assertThat(one("SELECT subscription_status FROM user_subscriptions WHERE user_id = ?", u.id()))
				.isEqualTo("REVOKED");
		assertThat(count("""
				SELECT COUNT(*) FROM subscription_events e JOIN user_subscriptions s ON s.id = e.subscription_id
				WHERE s.user_id = ? AND e.event_type = 'REFUNDED'""", u.id())).isEqualTo(1);

		// expiration: a new purchase, then RevenueCat reports it expired (and not renewing)
		User v = registered();
		rcSubscriber(v.id(), future(), null, null, true);
		rcWebhook(rcEvent(UUID.randomUUID().toString(), "INITIAL_PURCHASE", v.id(), ""), RC_AUTH)
				.andExpect(status().isOk());
		await().atMost(Duration.ofSeconds(15)).until(() -> "ACTIVE".equals(storeStatus(v.id())));
		String past = Instant.now().minus(Duration.ofDays(2)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
		rcSubscriber(v.id(), past, null, Instant.now().minus(Duration.ofDays(10))
				.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString(), true);
		rcWebhook(rcEvent(UUID.randomUUID().toString(), "EXPIRATION", v.id(), ",\"expiration_reason\":\"UNSUBSCRIBE\""),
				RC_AUTH).andExpect(status().isOk());
		await().atMost(Duration.ofSeconds(15)).until(() -> "EXPIRED".equals(storeStatus(v.id())));
		assertThat(plan(v, "US")).isEqualTo("FREE");

		// an event for an id that is not one of our users is stored and ignored
		String stranger = rcEvent(UUID.randomUUID().toString(), "INITIAL_PURCHASE", UUID.randomUUID(), "")
				.replace("\"aliases\"", "\"x\"");
		rcWebhook(stranger, RC_AUTH).andExpect(status().isOk());
		await().atMost(Duration.ofSeconds(10)).until(() -> "IGNORED".equals(one(
				"SELECT event_status FROM payment_webhook_events WHERE external_event_id = ?",
				JsonPath.<String>read(stranger, "$.event.id"))));
		// a dashboard TEST event is acknowledged and never applied
		rcWebhook("{\"api_version\":\"1.0\",\"event\":{\"id\":\"" + UUID.randomUUID()
				+ "\",\"type\":\"TEST\",\"app_user_id\":\"" + u.id() + "\"}}", RC_AUTH).andExpect(status().isOk());
	}
}
