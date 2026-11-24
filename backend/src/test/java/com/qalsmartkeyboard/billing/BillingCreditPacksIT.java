package com.qalsmartkeyboard.billing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import com.qalsmartkeyboard.usage.CreditSource;
import com.qalsmartkeyboard.usage.Credits;
import com.qalsmartkeyboard.usage.Metering;
import com.qalsmartkeyboard.usage.QuotaExceededException;
import com.qalsmartkeyboard.usage.QuotaExceededException.UpgradeOption;
import com.qalsmartkeyboard.usage.Reservation;

/**
 * T8.10 prepaid credit packs end to end on PostgreSQL 18 with the real security chain, outbox and a WireMock ArifPay
 * (the BillingPaymentsIT stubs), contract-validated: the seeded ET packs, order -> attempt -> verified webhook -> one
 * credit lot -> {@code /me/credits}; every repeat path (duplicate webhooks, reconciliation, concurrent verifications, a
 * late success on an expired order) still grants one lot; refunds reverse only the unused credits; consumption is
 * allowance first, then the lot; the guest, store-channel and catalogue refusals; the per-market offering matrix
 * (credits only, both, plans only, ZZ fallback) for listing, ordering and the QUOTA_EXCEEDED hint; and the catalogue and
 * offering admin endpoints (permissions, validation, price versioning, audit).
 */
@SpringBootTest(properties = { "qal.outbox.poll-interval=100ms", "qal.billing.arifpay.api-key=it-arifpay-key",
		"qal.billing.arifpay.webhook-secret=it-webhook-secret-0123456789-abcdefghij",
		"qal.billing.public-base-url=https://api.example.test", "qal.geo.trusted-proxies=127.0.0.1/32,::1/128" })
@AutoConfigureMockMvc
@ContractValidated
@Testcontainers
class BillingCreditPacksIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	static final WireMockServer ARIFPAY = new WireMockServer(
			options().dynamicPort().globalTemplating(true).notifier(new ConsoleNotifier(false)));
	static final String WEBHOOK_SECRET = "it-webhook-secret-0123456789-abcdefghij";
	static final String ACCOUNT = "arifpay-et-sandbox";
	static final List<String> PACKS = List.of("CREDITS_100", "CREDITS_500", "CREDITS_1500");

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

	record Checkout(String orderId, String attemptId, String sessionId, String amount) {
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
	@Autowired
	PaymentVerifier verifier;
	@Autowired
	Fulfilment fulfilment;
	@Autowired
	Metering metering;
	@Autowired
	Credits credits;

	User registered() {
		UUID id = transactions.execute(s -> {
			String phone = "+2519" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
			return ((PhoneSignIn.SignedIn) accounts.signInWithPhone(phone, true, Instant.now())).user().id();
		});
		return new User(id, "Bearer " + accessTokens.issue(id, UUID.randomUUID(), null, "REGISTERED").value());
	}

	User finance() {
		User u = registered();
		jdbc.update("INSERT INTO user_admin_roles (user_id, admin_role_id) SELECT ?, id FROM admin_roles WHERE role_code = 'FINANCE'",
				u.id());
		return u;
	}

	/** The ADR 0045 go-live switches for ArifPay and the ET direct policy, plus ACTIVE plans and packs. */
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
		jdbc.update("""
				UPDATE billing_products SET product_status = 'ACTIVE'
				WHERE product_code IN ('PLAN_PREMIUM', 'PLAN_PREMIUM_PLUS', 'CREDITS_100', 'CREDITS_500', 'CREDITS_1500')""");
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

	ResultActions postOrder(User u, String key, String json) throws Exception {
		return mvc.perform(fromEthiopia(MockMvcRequestBuilders.post("/api/v1/billing/orders"))
				.header("Authorization", u.bearer()).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	static String pack(String productCode) {
		return """
				{"productCode":"%s","billingPeriod":"ONE_TIME","marketCountry":"ET","salesChannel":"ANDROID_DIRECT"}"""
				.formatted(productCode);
	}

	static String plan() {
		return """
				{"productCode":"PLAN_PREMIUM","billingPeriod":"MONTHLY","marketCountry":"ET","salesChannel":"ANDROID_DIRECT"}""";
	}

	static String amountOf(String productCode) {
		return switch (productCode) {
			case "CREDITS_100" -> "49.0";
			case "CREDITS_500" -> "199.0";
			case "CREDITS_1500" -> "499.0";
			default -> "299.0";
		};
	}

	String orderId(User u, String json) throws Exception {
		String body = postOrder(u, UUID.randomUUID().toString(), json).andExpect(status().isCreated()).andReturn()
				.getResponse().getContentAsString();
		return JsonPath.read(body, "$.orderId");
	}

	Checkout checkout(User u, String productCode) throws Exception {
		String orderId = orderId(u, "PLAN_PREMIUM".equals(productCode) ? plan() : pack(productCode));
		String body = mvc.perform(MockMvcRequestBuilders.post("/api/v1/billing/orders/" + orderId + "/attempts")
				.header("Authorization", u.bearer()).header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON).content("{\"methodCode\":\"ARIFPAY_CHECKOUT\"}"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.nextAction.type").value("REDIRECT")).andReturn()
				.getResponse().getContentAsString();
		String attemptId = JsonPath.read(body, "$.attemptId");
		return new Checkout(orderId, attemptId, "ARP-" + attemptId, amountOf(productCode));
	}

	static void session(Checkout c, String status, String amount) {
		String tx = "SUCCESS".equals(status) ? "\"SIM-" + c.attemptId().substring(0, 8) + "\"" : "null";
		ARIFPAY.stubFor(WireMock.get(urlPathEqualTo("/arifpay/api/sandbox/checkout/session/" + c.sessionId()))
				.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
						{"error":false,"msg":"No Errors","data":{"id":1,"uuid":"%s","nonce":"%s","totalAmount":%s,"test":true,
						"phone":null,"transaction":{"id":2,"transactionId":%s,"transactionStatus":"%s","paymentType":"TELEBIRR_USSD",
						"amount":%s,"fee":0.0,"updatedAt":"2026-11-24T11:05:28.000+00:00"}}}"""
						.formatted(c.sessionId(), c.attemptId(), c.amount(), tx, status, amount))));
	}

	static void paid(Checkout c) {
		session(c, "SUCCESS", c.amount());
	}

	static String token(String nonce) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(mac.doFinal(("ARIFPAY|" + ACCOUNT + "|" + nonce).getBytes(StandardCharsets.UTF_8)));
	}

	ResultActions webhook(Checkout c) throws Exception {
		String body = """
				{"uuid":"%1$s","sessionId":"%1$s","nonce":"%2$s","phone":"251900000000","paymentMethod":"TELEBIRR_USSD",
				 "totalAmount":1,"transactionStatus":"SUCCESS","transaction":{"transactionId":"SIM-1","transactionStatus":"SUCCESS"}}"""
				.formatted(c.sessionId(), c.attemptId());
		return mvc.perform(MockMvcRequestBuilders.post("/api/v1/billing/webhooks/ARIFPAY/" + ACCOUNT + "?t=" + token(c.attemptId()))
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	/** Buys a pack (or the Premium plan) through the verified-webhook path and waits for PAID. */
	Checkout buy(User u, String productCode) throws Exception {
		Checkout c = checkout(u, productCode);
		paid(c);
		webhook(c).andExpect(status().isOk());
		await().atMost(Duration.ofSeconds(15)).until(() -> "PAID".equals(orderStatus(c.orderId())));
		return c;
	}

	String orderStatus(String orderId) {
		return jdbc.queryForObject("SELECT order_status FROM payment_orders WHERE id = ?::uuid", String.class, orderId);
	}

	int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	int lots(String orderId) {
		return count("SELECT COUNT(*) FROM ai_credit_lots WHERE payment_order_id = ?::uuid", orderId);
	}

	BigDecimal lotInitial(String orderId) {
		return jdbc.queryForObject("SELECT initial_units FROM ai_credit_lots WHERE payment_order_id = ?::uuid",
				BigDecimal.class, orderId);
	}

	Instant lotExpiry(String orderId) {
		return jdbc.queryForObject("SELECT expires_at FROM ai_credit_lots WHERE payment_order_id = ?::uuid",
				Timestamp.class, orderId).toInstant();
	}

	List<String> productCodes(User u) throws Exception {
		String body = mvc.perform(fromEthiopia(get("/api/v1/billing/options")).header("Authorization", u.bearer())
				.param("clientPlatform", "ANDROID").param("distributionChannel", "DIRECT_DOWNLOAD"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.products[*].productCode");
	}

	String plan(User u) throws Exception {
		String body = mvc.perform(get("/api/v1/me/entitlements").header("Authorization", u.bearer()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.plan.planCode");
	}

	/** Sets a market's offerings through the admin API (revision read first). */
	void offerings(User admin, String country, String offeringsJson) throws Exception {
		String body = mvc.perform(get("/admin/v1/billing/market-offerings/" + country).header("Authorization",
				admin.bearer())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		int revision = JsonPath.read(body, "$.revision");
		mvc.perform(put("/admin/v1/billing/market-offerings/" + country).header("Authorization", admin.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedRevision\":" + revision + ",\"offerings\":" + offeringsJson + "}"))
				.andExpect(status().isOk());
	}

	void restoreSeededOfferings(User admin) throws Exception {
		offerings(admin, "ET", "[{\"offering\":\"AI_CREDITS\",\"enabled\":true}]");
		offerings(admin, "ZZ", "[{\"offering\":\"SUBSCRIPTIONS\",\"enabled\":true},{\"offering\":\"AI_CREDITS\",\"enabled\":false}]");
	}

	static QuotaExceededException quota(Callable<?> call) {
		try {
			call.call();
		}
		catch (QuotaExceededException e) {
			return e;
		}
		catch (Exception e) {
			throw new AssertionError("expected QUOTA_EXCEEDED", e);
		}
		throw new AssertionError("expected QUOTA_EXCEEDED");
	}

	// ------------------------------------------------------------------------------------------------ catalogue

	@Test
	void theEthiopianPacksAreSeededAndListedNextToThePlans() throws Exception {
		for (String channel : List.of("ANDROID_DIRECT", "ANDROID_GOOGLE_PLAY")) {
			assertThat(jdbc.queryForList("""
					SELECT bp.product_code || ':' || pr.amount_minor FROM billing_prices pr
					JOIN billing_products bp ON bp.id = pr.billing_product_id
					WHERE pr.market_country_code = 'ET' AND pr.sales_channel = ? AND pr.currency_code = 'ETB'
					  AND pr.billing_period = 'ONE_TIME' AND pr.renewal_model = 'NONE' AND pr.tax_behavior = 'INCLUSIVE'
					  AND pr.price_version = 1 AND pr.metadata->>'source' = 'T8.10'
					ORDER BY pr.amount_minor""", String.class, channel))
					.containsExactly("CREDITS_100:4900", "CREDITS_500:19900", "CREDITS_1500:49900");
		}
		assertThat(count("""
				SELECT COUNT(*) FROM billing_products WHERE product_type = 'AI_CREDIT_PACK' AND credit_validity_days = 365
				AND product_code IN ('CREDITS_100', 'CREDITS_500', 'CREDITS_1500')""")).isEqualTo(3);
		// the global guide prices exist only as inactive references (store consumables are a follow-up)
		assertThat(count("""
				SELECT COUNT(*) FROM billing_prices WHERE market_country_code = 'ZZ' AND currency_code = 'GBP'
				AND NOT is_active AND metadata->>'source' = 'T8.10, SD 15.6'""")).isEqualTo(3);

		User u = registered();
		mvc.perform(fromEthiopia(get("/api/v1/billing/options")).header("Authorization", u.bearer())
				.param("clientPlatform", "ANDROID").param("distributionChannel", "DIRECT_DOWNLOAD"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.products[?(@.productCode == 'CREDITS_500')].productType").value("AI_CREDIT_PACK"))
				.andExpect(jsonPath("$.products[?(@.productCode == 'CREDITS_500')].creditUnits").value(500))
				.andExpect(jsonPath("$.products[?(@.productCode == 'CREDITS_500')].creditValidityDays").value(365))
				.andExpect(jsonPath("$.products[?(@.productCode == 'CREDITS_500')].prices[0].billingPeriod").value("ONE_TIME"))
				.andExpect(jsonPath("$.products[?(@.productCode == 'CREDITS_500')].prices[0].renewalModel").value("NONE"))
				.andExpect(jsonPath("$.products[?(@.productCode == 'CREDITS_1500')].prices[0].listPrice.amountMinor").value(49900));
		assertThat(productCodes(u)).contains("PLAN_PREMIUM", "CREDITS_100", "CREDITS_500", "CREDITS_1500");
	}

	// ------------------------------------------------------------------------------------------------ end to end

	@Test
	void aSandboxPurchaseGrantsExactlyOneLotThatMeCreditsShows() throws Exception {
		User u = registered();
		String key = UUID.randomUUID().toString();
		String created = postOrder(u, key, pack("CREDITS_500")).andExpect(status().isCreated())
				.andExpect(jsonPath("$.orderType").value("AI_CREDIT_TOPUP"))
				.andExpect(jsonPath("$.billingPeriod").value("ONE_TIME"))
				.andExpect(jsonPath("$.renewalModel").value("NONE"))
				.andExpect(jsonPath("$.amounts.total.amountMinor").value(19900))
				.andExpect(jsonPath("$.amounts.total.currency").value("ETB"))
				.andExpect(jsonPath("$.allowedMethods[0].methodCode").value("ARIFPAY_CHECKOUT")).andReturn()
				.getResponse().getContentAsString();
		assertThat(jdbc.queryForObject("SELECT metadata->>'creditUnits' FROM payment_orders WHERE id = ?::uuid",
				String.class, (String) JsonPath.read(created, "$.orderId"))).isEqualTo("500");

		Checkout c = buy(u, "CREDITS_500");
		Instant paidAt = Instant.now();
		assertThat(lots(c.orderId())).isEqualTo(1);
		assertThat(lotInitial(c.orderId())).isEqualByComparingTo("500");
		assertThat(lotExpiry(c.orderId())).isCloseTo(paidAt.plus(365, ChronoUnit.DAYS), within(1, ChronoUnit.MINUTES));
		assertThat(count("SELECT COUNT(*) FROM ai_credit_lots l JOIN ai_credit_accounts a ON a.id = l.credit_account_id "
				+ "WHERE l.payment_order_id = ?::uuid AND l.lot_source = 'PURCHASE' AND a.user_id = ?", c.orderId(),
				u.id())).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM ai_credit_transactions WHERE payment_order_id = ?::uuid "
				+ "AND transaction_type = 'PURCHASE' AND delta_units = 500", c.orderId())).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT receipt_number FROM payment_receipts WHERE payment_order_id = ?::uuid",
				String.class, c.orderId())).matches("ET-\\d{4}-\\d{6}");
		assertThat(count("SELECT COUNT(*) FROM user_subscriptions WHERE user_id = ?", u.id())).isZero();
		assertThat(plan(u)).isEqualTo("FREE"); // credits are not a subscription

		String lotId = jdbc.queryForObject("SELECT id::text FROM ai_credit_lots WHERE payment_order_id = ?::uuid",
				String.class, c.orderId());
		mvc.perform(get("/api/v1/billing/orders/" + c.orderId()).header("Authorization", u.bearer()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"))
				.andExpect(jsonPath("$.fulfilment.creditLotId").value(lotId))
				.andExpect(jsonPath("$.fulfilment.receiptId").isNotEmpty())
				.andExpect(jsonPath("$.fulfilment.subscriptionId").doesNotExist());
		mvc.perform(get("/api/v1/me/credits").header("Authorization", u.bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.balanceUnits").value(500)).andExpect(jsonPath("$.lots.length()").value(1))
				.andExpect(jsonPath("$.lots[0].lotId").value(lotId))
				.andExpect(jsonPath("$.lots[0].source").value("PURCHASE"))
				.andExpect(jsonPath("$.lots[0].originalUnits").value(500))
				.andExpect(jsonPath("$.lots[0].remainingUnits").value(500))
				.andExpect(jsonPath("$.lots[0].expiresAt").isNotEmpty())
				.andExpect(jsonPath("$.nextExpiryAt").isNotEmpty());
		mvc.perform(get("/api/v1/me/credits/transactions").header("Authorization", u.bearer()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].transactionType").value("PURCHASE"))
				.andExpect(jsonPath("$.items[0].deltaUnits").value(500))
				.andExpect(jsonPath("$.items[0].balanceAfter").value(500))
				.andExpect(jsonPath("$.items[0].paymentOrderId").value(c.orderId()))
				.andExpect(jsonPath("$.items[0].lotId").value(lotId))
				.andExpect(jsonPath("$.nextCursor").doesNotExist());

		// every repeat path: duplicate webhook, the return URL, reconciliation and a direct re-verification
		webhook(c).andExpect(status().isOk());
		mvc.perform(get("/api/v1/billing/return/" + c.attemptId()).param("result", "success"))
				.andExpect(status().isSeeOther());
		jdbc.update("UPDATE payment_attempts SET created_at = NOW() - INTERVAL '20 minutes' WHERE id = ?::uuid",
				c.attemptId());
		reconciler.runOnce();
		assertThat(verifier.verify(UUID.fromString(c.attemptId()), "RECONCILIATION"))
				.isEqualTo(PaymentVerifier.Outcome.ALREADY_SUCCEEDED);
		Thread.sleep(1000); // let the outbox drain the duplicate webhook and the return verification
		assertThat(lots(c.orderId())).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM ai_credit_transactions WHERE payment_order_id = ?::uuid", c.orderId()))
				.isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM payment_receipts WHERE payment_order_id = ?::uuid", c.orderId()))
				.isEqualTo(1);
		assertThat(credits.balance(u.id(), Instant.now()).exact()).isEqualByComparingTo("500");
	}

	@Test
	void concurrentVerificationsOfOnePaymentGrantOneLot() throws Exception {
		User u = registered();
		Checkout c = checkout(u, "CREDITS_100");
		paid(c);
		int threads = 8;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			CountDownLatch go = new CountDownLatch(1);
			List<Future<PaymentVerifier.Outcome>> results = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				results.add(pool.submit(() -> {
					go.await();
					return verifier.verify(UUID.fromString(c.attemptId()), "WEBHOOK");
				}));
			}
			go.countDown();
			List<PaymentVerifier.Outcome> outcomes = new ArrayList<>();
			for (Future<PaymentVerifier.Outcome> f : results) {
				outcomes.add(f.get());
			}
			assertThat(outcomes).filteredOn(o -> o == PaymentVerifier.Outcome.PAID).hasSize(1);
			assertThat(outcomes).filteredOn(o -> o != PaymentVerifier.Outcome.PAID)
					.allMatch(o -> o == PaymentVerifier.Outcome.ALREADY_SUCCEEDED);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(orderStatus(c.orderId())).isEqualTo("PAID");
		assertThat(lots(c.orderId())).isEqualTo(1);
		assertThat(lotInitial(c.orderId())).isEqualByComparingTo("100");
		assertThat(credits.balance(u.id(), Instant.now()).exact()).isEqualByComparingTo("100");
		// a second, separately verified capture on the paid order is a duplicate capture, never a second lot
		Checkout again = new Checkout(c.orderId(), c.attemptId(), c.sessionId(), c.amount());
		assertThat(verifier.verify(UUID.fromString(again.attemptId()), "RECONCILIATION"))
				.isEqualTo(PaymentVerifier.Outcome.ALREADY_SUCCEEDED);
		assertThat(lots(c.orderId())).isEqualTo(1);
	}

	@Test
	void aLateSuccessOnAnExpiredOrCancelledOrderStillGrantsItsLot() throws Exception {
		User u = registered();
		Checkout expired = checkout(u, "CREDITS_100");
		jdbc.update("UPDATE payment_orders SET order_status = 'EXPIRED' WHERE id = ?::uuid", expired.orderId());
		jdbc.update("UPDATE payment_attempts SET attempt_status = 'EXPIRED' WHERE id = ?::uuid", expired.attemptId());
		paid(expired);
		assertThat(verifier.verify(UUID.fromString(expired.attemptId()), "RECONCILIATION"))
				.isEqualTo(PaymentVerifier.Outcome.PAID);
		assertThat(orderStatus(expired.orderId())).isEqualTo("PAID");
		assertThat(lots(expired.orderId())).isEqualTo(1);

		Checkout cancelled = checkout(u, "CREDITS_500");
		mvc.perform(MockMvcRequestBuilders.post("/api/v1/billing/orders/" + cancelled.orderId() + "/cancel")
				.header("Authorization", u.bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELLED"));
		paid(cancelled);
		assertThat(verifier.verify(UUID.fromString(cancelled.attemptId()), "WEBHOOK"))
				.isEqualTo(PaymentVerifier.Outcome.PAID);
		assertThat(lots(cancelled.orderId())).isEqualTo(1);
		assertThat(credits.balance(u.id(), Instant.now()).exact()).isEqualByComparingTo("600");
	}

	// ------------------------------------------------------------------------------------------------ consumption, refund

	@Test
	void creditsAreSpentAfterTheAllowanceAndARefundReversesOnlyWhatIsLeft() throws Exception {
		User u = registered();
		Checkout c = buy(u, "CREDITS_100");
		List<Reservation> spent = new ArrayList<>();
		for (int i = 0; i < 12; i++) { // FREE: 10 translations a day from the allowance, then 1 credit each
			Reservation r = metering.reserve(u.id(), "TRANSLATE", 1, UUID.randomUUID().toString());
			metering.settle(r.id(), 1);
			spent.add(r);
		}
		assertThat(spent.subList(0, 10)).allMatch(r -> r.creditUnits() == 0 && r.allowanceUnits() == 1);
		assertThat(spent.subList(10, 12)).allMatch(r -> r.creditUnits() == 1);
		assertThat(credits.balance(u.id(), Instant.now()).exact()).isEqualByComparingTo("98");
		mvc.perform(get("/api/v1/me/credits/transactions").header("Authorization", u.bearer()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].transactionType").value("CONSUMPTION"))
				.andExpect(jsonPath("$.items[0].featureCode").value("TRANSLATE"))
				.andExpect(jsonPath("$.items[0].aiRequestId").isNotEmpty())
				.andExpect(jsonPath("$.items[2].transactionType").value("PURCHASE"));

		// the refund flow's ledger step: the unused 98 are reversed, the 2 consumed stay consumed
		BigDecimal reversed = transactions.execute(s -> fulfilment.reverseCreditTopUp(UUID.fromString(c.orderId())));
		assertThat(reversed).isEqualByComparingTo("98");
		BigDecimal replay = transactions.execute(s -> fulfilment.reverseCreditTopUp(UUID.fromString(c.orderId())));
		assertThat(replay).isEqualByComparingTo("0");
		assertThat(credits.balance(u.id(), Instant.now()).exact()).isEqualByComparingTo("0");
		assertThat(count("""
				SELECT COUNT(*) FROM ai_credit_transactions WHERE payment_order_id = ?::uuid AND transaction_type = 'REVERSAL'
				AND delta_units = -98 AND balance_after = 0""", c.orderId())).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT remaining_units FROM ai_credit_lots WHERE payment_order_id = ?::uuid",
				BigDecimal.class, c.orderId())).isEqualByComparingTo("0");
		mvc.perform(get("/api/v1/me/credits").header("Authorization", u.bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.balanceUnits").value(0)).andExpect(jsonPath("$.lots.length()").value(0))
				.andExpect(jsonPath("$.nextExpiryAt").doesNotExist());
		// a subscription order is not a credit pack
		Checkout premium = buy(u, "PLAN_PREMIUM");
		org.assertj.core.api.Assertions
				.assertThatThrownBy(() -> fulfilment.reverseCreditTopUp(UUID.fromString(premium.orderId())))
				.isInstanceOf(IllegalArgumentException.class);
	}

	// ------------------------------------------------------------------------------------------------ order rules

	@Test
	void orderRulesForPacks() throws Exception {
		User u = registered();
		// renewalPreference omitted, NONE or MANUAL_RENEW: a one-off order
		postOrder(u, UUID.randomUUID().toString(), """
				{"productCode":"CREDITS_100","billingPeriod":"ONE_TIME","marketCountry":"ET","salesChannel":"ANDROID_DIRECT","renewalPreference":"NONE"}""")
				.andExpect(status().isCreated()).andExpect(jsonPath("$.renewalModel").value("NONE"));
		postOrder(u, UUID.randomUUID().toString(), """
				{"productCode":"CREDITS_100","billingPeriod":"ONE_TIME","marketCountry":"ET","salesChannel":"ANDROID_DIRECT","renewalPreference":"MANUAL_RENEW"}""")
				.andExpect(status().isCreated()).andExpect(jsonPath("$.orderType").value("AI_CREDIT_TOPUP"));
		postOrder(u, UUID.randomUUID().toString(), """
				{"productCode":"CREDITS_100","billingPeriod":"ONE_TIME","marketCountry":"ET","salesChannel":"ANDROID_DIRECT","renewalPreference":"AUTO_RENEW"}""")
				.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("MANDATE_NOT_SUPPORTED"));
		postOrder(u, UUID.randomUUID().toString(), """
				{"productCode":"CREDITS_100","billingPeriod":"MONTHLY","marketCountry":"ET","salesChannel":"ANDROID_DIRECT"}""")
				.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("PRICE_NOT_AVAILABLE"));
		postOrder(u, UUID.randomUUID().toString(), pack("CREDITS_9999")).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("PRODUCT_NOT_AVAILABLE"));
		jdbc.update("UPDATE billing_products SET product_status = 'DRAFT' WHERE product_code = 'CREDITS_1500'");
		postOrder(u, UUID.randomUUID().toString(), pack("CREDITS_1500")).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("PRODUCT_NOT_AVAILABLE"));
		jdbc.update("UPDATE billing_products SET product_status = 'ACTIVE' WHERE product_code = 'CREDITS_1500'");

		// idempotency: the same key and body is the same order, another body is 409
		String key = UUID.randomUUID().toString();
		String first = JsonPath.read(postOrder(u, key, pack("CREDITS_500")).andExpect(status().isCreated()).andReturn()
				.getResponse().getContentAsString(), "$.orderId");
		String again = JsonPath.read(postOrder(u, key, pack("CREDITS_500")).andExpect(status().isCreated()).andReturn()
				.getResponse().getContentAsString(), "$.orderId");
		assertThat(again).isEqualTo(first);
		postOrder(u, key, pack("CREDITS_100")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

		// guests cannot buy credits (they would be lost with the guest)
		UUID guest = transactions.execute(s -> accounts.createGuest(Instant.now()).id());
		User g = new User(guest, "Bearer " + accessTokens.issue(guest, UUID.randomUUID(), null, "GUEST").value());
		postOrder(g, UUID.randomUUID().toString(), pack("CREDITS_100")).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("GUEST_NOT_ALLOWED"));
		assertThat(count("SELECT COUNT(*) FROM payment_orders WHERE user_id = ?", guest)).isZero();

		// a store-billing market (global, Play build): no gateway order, store consumables are a follow-up
		UUID reviewer = registered().id();
		jdbc.update("""
				UPDATE billing_market_channel_policies
				   SET approval_status = 'APPROVED', reviewed_at = NOW(), reviewed_by_user_id = ?, purchase_ui_allowed = TRUE
				 WHERE country_code = 'ZZ' AND client_platform = 'ANDROID' AND distribution_channel = 'GOOGLE_PLAY'""",
				reviewer);
		try {
			mvc.perform(MockMvcRequestBuilders.post("/api/v1/billing/orders").with(r -> {
				r.setRemoteAddr("203.0.113.7");
				return r;
			}).header("Authorization", u.bearer()).header("X-Client-Platform", "ANDROID")
					.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"productCode":"CREDITS_100","billingPeriod":"ONE_TIME","marketCountry":"ZZ","salesChannel":"ANDROID_GOOGLE_PLAY"}"""))
					.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BILLING_POLICY_DENIED"));
		}
		finally {
			jdbc.update("""
					UPDATE billing_market_channel_policies
					   SET approval_status = 'REVIEW_REQUIRED', purchase_ui_allowed = FALSE
					 WHERE country_code = 'ZZ' AND client_platform = 'ANDROID' AND distribution_channel = 'GOOGLE_PLAY'""");
		}
	}

	// ------------------------------------------------------------------------------------------------ offerings

	@Test
	void marketOfferingsDecideWhatIsListedAndOrderedButNeverTouchWhatWasBought() throws Exception {
		User admin = finance();
		User u = registered();
		Checkout premium = buy(u, "PLAN_PREMIUM");
		await().atMost(Duration.ofSeconds(10)).until(() -> "PREMIUM".equals(plan(u)));
		try {
			// seeded default: ET sells both
			assertThat(productCodes(u)).contains("PLAN_PREMIUM", "CREDITS_500");

			// credits only
			offerings(admin, "ET", """
					[{"offering":"SUBSCRIPTIONS","enabled":false},{"offering":"AI_CREDITS","enabled":true}]""");
			assertThat(productCodes(u)).containsExactlyInAnyOrder("CREDITS_100", "CREDITS_500", "CREDITS_1500");
			mvc.perform(get("/api/v1/billing/products").header("Authorization", u.bearer())
					.param("marketCountry", "ET").param("salesChannel", "ANDROID_DIRECT")).andExpect(status().isOk())
					.andExpect(jsonPath("$.items[?(@.productType == 'SUBSCRIPTION')]").isEmpty())
					.andExpect(jsonPath("$.items.length()").value(3));
			postOrder(u, UUID.randomUUID().toString(), plan()).andExpect(status().isUnprocessableContent())
					.andExpect(jsonPath("$.code").value("PRODUCT_NOT_AVAILABLE"));
			postOrder(u, UUID.randomUUID().toString(), pack("CREDITS_100")).andExpect(status().isCreated());
			// what was bought stays: the term bought before the switch still applies, wherever the user is
			assertThat(plan(u)).isEqualTo("PREMIUM");
			assertThat(orderStatus(premium.orderId())).isEqualTo("PAID");

			// plans only (credits off, subscriptions inherited from ZZ)
			offerings(admin, "ET", "[{\"offering\":\"AI_CREDITS\",\"enabled\":false}]");
			assertThat(productCodes(u)).contains("PLAN_PREMIUM").doesNotContain("CREDITS_100", "CREDITS_500",
					"CREDITS_1500");
			postOrder(u, UUID.randomUUID().toString(), pack("CREDITS_500")).andExpect(status().isUnprocessableContent())
					.andExpect(jsonPath("$.code").value("PRODUCT_NOT_AVAILABLE"));
			postOrder(u, UUID.randomUUID().toString(), plan()).andExpect(status().isCreated());
			mvc.perform(get("/admin/v1/billing/market-offerings/ET").header("Authorization", admin.bearer()))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.offerings[?(@.offering == 'SUBSCRIPTIONS')].source").value("DEFAULT"))
					.andExpect(jsonPath("$.offerings[?(@.offering == 'SUBSCRIPTIONS')].enabled").value(true))
					.andExpect(jsonPath("$.offerings[?(@.offering == 'AI_CREDITS')].source").value("MARKET"))
					.andExpect(jsonPath("$.offerings[?(@.offering == 'AI_CREDITS')].enabled").value(false));

			// no own setting: ET falls back to ZZ (plans on, credits off) ...
			offerings(admin, "ET", "[]");
			mvc.perform(get("/admin/v1/billing/market-offerings/ET").header("Authorization", admin.bearer()))
					.andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(0))
					.andExpect(jsonPath("$.offerings[?(@.offering == 'AI_CREDITS')].source").value("DEFAULT"))
					.andExpect(jsonPath("$.offerings[?(@.offering == 'AI_CREDITS')].enabled").value(false));
			assertThat(productCodes(u)).contains("PLAN_PREMIUM").doesNotContain("CREDITS_500");
			// ... and follows the default when it changes
			offerings(admin, "ZZ", """
					[{"offering":"SUBSCRIPTIONS","enabled":true},{"offering":"AI_CREDITS","enabled":true}]""");
			assertThat(productCodes(u)).contains("PLAN_PREMIUM", "CREDITS_500");
			// with no ZZ row either, a family is off (fail closed)
			offerings(admin, "ZZ", "[{\"offering\":\"AI_CREDITS\",\"enabled\":true}]");
			mvc.perform(get("/admin/v1/billing/market-offerings/ZZ").header("Authorization", admin.bearer()))
					.andExpect(jsonPath("$.offerings[?(@.offering == 'SUBSCRIPTIONS')].source").value("NONE"))
					.andExpect(jsonPath("$.offerings[?(@.offering == 'SUBSCRIPTIONS')].enabled").value(false));
			assertThat(productCodes(u)).doesNotContain("PLAN_PREMIUM").contains("CREDITS_500");
			assertThat(plan(u)).isEqualTo("PREMIUM");
		}
		finally {
			restoreSeededOfferings(admin);
		}
		assertThat(productCodes(u)).contains("PLAN_PREMIUM", "CREDITS_500");
	}

	@Test
	void quotaExceededOffersCreditPacksOnlyWhereTheyAreSold() throws Exception {
		User admin = finance();
		User u = registered();
		for (int i = 0; i < 10; i++) {
			metering.reserve(u.id(), "TRANSLATE", 1, UUID.randomUUID().toString());
		}
		QuotaExceededException q = quota(() -> metering.reserve(u.id(), "TRANSLATE", 1, UUID.randomUUID().toString()));
		assertThat(q.creditBalance()).isEqualByComparingTo("0");
		assertThat(q.upgradeOptions()).contains(new UpgradeOption("PLAN", "PLAN_PREMIUM"),
				new UpgradeOption("CREDIT_PACK", "CREDITS_100"), new UpgradeOption("CREDIT_PACK", "CREDITS_500"),
				new UpgradeOption("CREDIT_PACK", "CREDITS_1500"));
		try {
			offerings(admin, "ET", "[{\"offering\":\"AI_CREDITS\",\"enabled\":false}]");
			QuotaExceededException off = quota(
					() -> metering.reserve(u.id(), "TRANSLATE", 1, UUID.randomUUID().toString()));
			assertThat(off.upgradeOptions()).contains(new UpgradeOption("PLAN", "PLAN_PREMIUM"))
					.noneMatch(o -> "CREDIT_PACK".equals(o.type()));
		}
		finally {
			restoreSeededOfferings(admin);
		}
		// after buying, the credits pay once the allowance is used (allowance first, SD 15.3)
		buy(u, "CREDITS_100");
		Reservation r = metering.reserve(u.id(), "TRANSLATE", 1, UUID.randomUUID().toString());
		assertThat(r.creditUnits()).isEqualTo(1);
	}

	// ------------------------------------------------------------------------------------------------ admin

	@Test
	void adminEndpointsNeedTheFinancePermissions() throws Exception {
		User nobody = registered();
		mvc.perform(get("/admin/v1/billing/products").header("Authorization", nobody.bearer()))
				.andExpect(status().isForbidden());
		mvc.perform(put("/admin/v1/billing/products/CREDITS_100/prices/ET/ANDROID_DIRECT")
				.header("Authorization", nobody.bearer()).contentType(MediaType.APPLICATION_JSON).content("""
						{"currency":"ETB","amountMinor":1,"billingPeriod":"ONE_TIME","renewalModel":"NONE"}"""))
				.andExpect(status().isForbidden());
		mvc.perform(patch("/admin/v1/billing/products/CREDITS_100").header("Authorization", nobody.bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"creditUnits\":5}"))
				.andExpect(status().isForbidden());
		mvc.perform(put("/admin/v1/billing/market-offerings/ET").header("Authorization", nobody.bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":1,\"offerings\":[]}"))
				.andExpect(status().isForbidden());
		mvc.perform(get("/admin/v1/billing/market-offerings/ET")).andExpect(status().isUnauthorized());
		assertThat(count("""
				SELECT COUNT(*) FROM audit_events WHERE actor_user_id = ? AND outcome = 'DENIED'
				AND action_code IN ('BILLING_PRICE_SET', 'BILLING_PRODUCT_UPDATE', 'BILLING_MARKET_OFFERINGS_REPLACE')""",
				nobody.id())).isEqualTo(3);
		assertThat(jdbc.queryForObject("SELECT credit_units FROM billing_products WHERE product_code = 'CREDITS_100'",
				BigDecimal.class)).isEqualByComparingTo("100");

		User admin = finance();
		mvc.perform(get("/admin/v1/billing/products").param("marketCountry", "ET").header("Authorization",
				admin.bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[?(@.productCode == 'CREDITS_500')].creditUnits").value(500))
				.andExpect(jsonPath("$.items[?(@.productCode == 'CREDITS_500')].productType").value("AI_CREDIT_PACK"))
				.andExpect(jsonPath("$.items[?(@.productCode == 'CREDITS_500')].prices[?(@.salesChannel == 'ANDROID_DIRECT' && @.active == true)].amount.amountMinor")
						.value(19900));
		mvc.perform(get("/admin/v1/billing/products").param("marketCountry", "et").header("Authorization",
				admin.bearer())).andExpect(status().isBadRequest());
		mvc.perform(get("/admin/v1/billing/market-offerings/ET").header("Authorization", admin.bearer()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.countryCode").value("ET"))
				.andExpect(jsonPath("$.offerings[?(@.offering == 'AI_CREDITS')].source").value("MARKET"))
				.andExpect(jsonPath("$.offerings[?(@.offering == 'AI_CREDITS')].enabled").value(true));
		String et = mvc.perform(get("/admin/v1/billing/market-offerings/ET").header("Authorization", admin.bearer()))
				.andReturn().getResponse().getContentAsString();
		int revision = JsonPath.read(et, "$.revision");
		mvc.perform(put("/admin/v1/billing/market-offerings/ET").header("Authorization", admin.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedRevision\":" + (revision + 7) + ",\"offerings\":[]}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVISION_CONFLICT"))
				.andExpect(jsonPath("$.currentRevision").value(revision));
		mvc.perform(put("/admin/v1/billing/market-offerings/ET").header("Authorization", admin.bearer())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"expectedRevision":%d,"offerings":[{"offering":"AI_CREDITS","enabled":true},{"offering":"AI_CREDITS","enabled":false}]}"""
						.formatted(revision)))
				.andExpect(status().isBadRequest());
		mvc.perform(put("/admin/v1/billing/market-offerings/ET").header("Authorization", admin.bearer())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"expectedRevision":%d,"offerings":[{"offering":"ADD_ONS","enabled":true}]}""".formatted(revision)))
				.andExpect(status().isBadRequest());
		try {
			offerings(admin, "ET", """
					[{"offering":"SUBSCRIPTIONS","enabled":false},{"offering":"AI_CREDITS","enabled":true}]""");
			assertThat(count("""
					SELECT COUNT(*) FROM audit_events WHERE action_code = 'BILLING_MARKET_OFFERINGS_REPLACE'
					AND entity_id = 'ET' AND outcome = 'SUCCESS' AND actor_user_id = ?""", admin.id())).isEqualTo(1);
		}
		finally {
			restoreSeededOfferings(admin);
		}
	}

	@Test
	void packUnitsAndValidityAreAdminDataAndAnOrderKeepsWhatItWasCreatedWith() throws Exception {
		User admin = finance();
		User u = registered();
		Checkout before = checkout(u, "CREDITS_100");
		try {
			mvc.perform(patch("/admin/v1/billing/products/CREDITS_100").header("Authorization", admin.bearer())
					.contentType(MediaType.APPLICATION_JSON).content("{\"creditUnits\":120,\"creditValidityDays\":30}"))
					.andExpect(status().isOk()).andExpect(jsonPath("$.creditUnits").value(120))
					.andExpect(jsonPath("$.creditValidityDays").value(30));
			assertThat(count("""
					SELECT COUNT(*) FROM audit_events WHERE action_code = 'BILLING_PRODUCT_UPDATE' AND entity_id = 'CREDITS_100'
					AND outcome = 'SUCCESS' AND metadata->'after'->>'creditUnits' = '120'""")).isEqualTo(1);
			Checkout after = checkout(u, "CREDITS_100");
			paid(before);
			paid(after);
			assertThat(verifier.verify(UUID.fromString(before.attemptId()), "WEBHOOK")).isEqualTo(PaymentVerifier.Outcome.PAID);
			assertThat(verifier.verify(UUID.fromString(after.attemptId()), "WEBHOOK")).isEqualTo(PaymentVerifier.Outcome.PAID);
			assertThat(lotInitial(before.orderId())).isEqualByComparingTo("100");
			assertThat(lotExpiry(before.orderId())).isAfter(Instant.now().plus(364, ChronoUnit.DAYS));
			assertThat(lotInitial(after.orderId())).isEqualByComparingTo("120");
			assertThat(lotExpiry(after.orderId())).isBefore(Instant.now().plus(31, ChronoUnit.DAYS));
			mvc.perform(get("/api/v1/me/credits").header("Authorization", u.bearer()))
					.andExpect(jsonPath("$.balanceUnits").value(220))
					.andExpect(jsonPath("$.lots[0].originalUnits").value(120)) // soonest expiry first
					.andExpect(jsonPath("$.nextExpiryAt").value(lotExpiry(after.orderId()).toString()));

			mvc.perform(patch("/admin/v1/billing/products/PLAN_PREMIUM").header("Authorization", admin.bearer())
					.contentType(MediaType.APPLICATION_JSON).content("{\"creditUnits\":5}"))
					.andExpect(status().isBadRequest());
			mvc.perform(patch("/admin/v1/billing/products/CREDITS_100").header("Authorization", admin.bearer())
					.contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
			mvc.perform(patch("/admin/v1/billing/products/CREDITS_100").header("Authorization", admin.bearer())
					.contentType(MediaType.APPLICATION_JSON).content("{\"creditUnits\":0}"))
					.andExpect(status().isBadRequest());
			mvc.perform(patch("/admin/v1/billing/products/NO_SUCH_PACK").header("Authorization", admin.bearer())
					.contentType(MediaType.APPLICATION_JSON).content("{\"creditUnits\":5}"))
					.andExpect(status().isNotFound());
			// status changes need prices:approve (FINANCE has it): DRAFT takes the pack off sale
			mvc.perform(patch("/admin/v1/billing/products/CREDITS_100").header("Authorization", admin.bearer())
					.contentType(MediaType.APPLICATION_JSON).content("{\"productStatus\":\"DRAFT\"}"))
					.andExpect(status().isOk()).andExpect(jsonPath("$.productStatus").value("DRAFT"));
			assertThat(productCodes(u)).doesNotContain("CREDITS_100");
		}
		finally {
			mvc.perform(patch("/admin/v1/billing/products/CREDITS_100").header("Authorization", admin.bearer())
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"creditUnits\":100,\"creditValidityDays\":365,\"productStatus\":\"ACTIVE\"}"))
					.andExpect(status().isOk());
		}
	}

	@Test
	void pricesAreSetPerMarketWithoutRewritingAPriceAnOrderUses() throws Exception {
		User admin = finance();
		String ke = "/admin/v1/billing/products/CREDITS_100/prices/KE/ANDROID_DIRECT";
		String body = """
				{"currency":"KES","amountMinor":%d,"billingPeriod":"ONE_TIME","renewalModel":"NONE"}""";
		mvc.perform(put(ke).header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
				.content(body.formatted(15000))).andExpect(status().isOk())
				.andExpect(jsonPath("$.prices.length()").value(1))
				.andExpect(jsonPath("$.prices[0].marketCountry").value("KE"))
				.andExpect(jsonPath("$.prices[0].amount.amountMinor").value(15000))
				.andExpect(jsonPath("$.prices[0].amount.currency").value("KES"))
				.andExpect(jsonPath("$.prices[0].priceVersion").value(1))
				.andExpect(jsonPath("$.prices[0].active").value(true))
				.andExpect(jsonPath("$.prices[0].taxBehavior").value("INCLUSIVE"));
		String scope = "CREDITS_100/KE/ANDROID_DIRECT/KES/ONE_TIME/NONE";
		mvc.perform(put(ke).header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
				.content(body.formatted(15000))).andExpect(status().isOk());
		assertThat(count("SELECT COUNT(*) FROM audit_events WHERE action_code = 'BILLING_PRICE_SET' AND entity_id = ?",
				scope)).isEqualTo(1); // the same values again change nothing
		// no order uses it yet: changed in place
		mvc.perform(put(ke).header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
				.content(body.formatted(16000))).andExpect(status().isOk())
				.andExpect(jsonPath("$.prices.length()").value(1))
				.andExpect(jsonPath("$.prices[0].amount.amountMinor").value(16000))
				.andExpect(jsonPath("$.prices[0].priceVersion").value(1));
		mvc.perform(put(ke).header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"currency":"KES","amountMinor":16000,"billingPeriod":"ONE_TIME","renewalModel":"NONE","active":false}"""))
				.andExpect(status().isOk()).andExpect(jsonPath("$.prices[0].active").value(false));
		assertThat(count("SELECT COUNT(*) FROM audit_events WHERE action_code = 'BILLING_PRICE_SET' AND entity_id = ?",
				scope)).isEqualTo(3);

		// an order uses the ET price: a new version starts, the order keeps its amount
		User u = registered();
		String oldOrder = orderId(u, pack("CREDITS_1500"));
		String et = "/admin/v1/billing/products/CREDITS_1500/prices/ET/ANDROID_DIRECT";
		try {
			mvc.perform(put(et).header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"currency":"ETB","amountMinor":44900,"billingPeriod":"ONE_TIME","renewalModel":"NONE"}"""))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.prices[?(@.salesChannel == 'ANDROID_DIRECT' && @.active == true)].amount.amountMinor")
							.value(44900))
					.andExpect(jsonPath("$.prices[?(@.salesChannel == 'ANDROID_DIRECT' && @.priceVersion == 1)].active")
							.value(false))
					.andExpect(jsonPath("$.prices[?(@.salesChannel == 'ANDROID_DIRECT' && @.priceVersion == 1)].validUntil")
							.isNotEmpty());
			mvc.perform(get("/api/v1/billing/orders/" + oldOrder).header("Authorization", u.bearer()))
					.andExpect(jsonPath("$.amounts.total.amountMinor").value(49900));
			String newOrder = JsonPath.read(postOrder(u, UUID.randomUUID().toString(), pack("CREDITS_1500"))
					.andExpect(status().isCreated()).andExpect(jsonPath("$.amounts.total.amountMinor").value(44900))
					.andReturn().getResponse().getContentAsString(), "$.orderId");
			assertThat(newOrder).isNotEqualTo(oldOrder);
		}
		finally {
			mvc.perform(put(et).header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"currency":"ETB","amountMinor":49900,"billingPeriod":"ONE_TIME","renewalModel":"NONE"}"""))
					.andExpect(status().isOk());
		}

		// validation
		mvc.perform(put(et).header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON).content("""
				{"currency":"ETB","amountMinor":100,"billingPeriod":"MONTHLY","renewalModel":"MANUAL_RENEW"}"""))
				.andExpect(status().isBadRequest());
		mvc.perform(put(et).header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON).content("""
				{"currency":"XYZ","amountMinor":100,"billingPeriod":"ONE_TIME","renewalModel":"NONE"}"""))
				.andExpect(status().isBadRequest());
		mvc.perform(put(et).header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON).content("""
				{"currency":"ETB","amountMinor":100,"taxBehavior":"EXCLUSIVE","billingPeriod":"ONE_TIME","renewalModel":"NONE"}"""))
				.andExpect(status().isBadRequest());
		mvc.perform(put(et).header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON).content("""
				{"currency":"ETB","amountMinor":-1,"billingPeriod":"ONE_TIME","renewalModel":"NONE"}"""))
				.andExpect(status().isBadRequest());
		mvc.perform(put("/admin/v1/billing/products/CREDITS_100/prices/ET/NOPE").header("Authorization", admin.bearer())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"currency":"ETB","amountMinor":100,"billingPeriod":"ONE_TIME","renewalModel":"NONE"}"""))
				.andExpect(status().isBadRequest());
		mvc.perform(put("/admin/v1/billing/products/NO_SUCH/prices/ET/ANDROID_DIRECT")
				.header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON).content("""
						{"currency":"ETB","amountMinor":100,"billingPeriod":"ONE_TIME","renewalModel":"NONE"}"""))
				.andExpect(status().isNotFound());
		assertThat(count("""
				SELECT COUNT(*) FROM billing_prices pr JOIN billing_products bp ON bp.id = pr.billing_product_id
				WHERE bp.product_code = 'CREDITS_1500' AND pr.market_country_code = 'ET' AND pr.sales_channel = 'ANDROID_DIRECT'
				AND pr.is_active AND (pr.valid_until IS NULL OR pr.valid_until > NOW()) AND pr.amount_minor = 49900""")).isEqualTo(1);
	}

	// ------------------------------------------------------------------------------------------------ /me/credits

	@Test
	void meCreditsIsTheCallersOwnLedgerPagedNewestFirst() throws Exception {
		mvc.perform(get("/api/v1/me/credits")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/me/credits/transactions")).andExpect(status().isUnauthorized());
		User owner = registered();
		User other = registered();
		Instant expires = Instant.now().plus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
		for (int i = 0; i < 3; i++) {
			Thread.sleep(5); // distinct created_at for the newest-first order
			credits.grant(owner.id(), new BigDecimal("2.5"), CreditSource.GRANT, "it-grant-" + owner.id() + "-" + i,
					i == 0 ? null : expires.plus(i, ChronoUnit.DAYS));
		}
		mvc.perform(get("/api/v1/me/credits").header("Authorization", owner.bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.balanceUnits").value(7.5)).andExpect(jsonPath("$.lots.length()").value(3))
				.andExpect(jsonPath("$.lots[0].source").value("GRANT"))
				.andExpect(jsonPath("$.lots[0].expiresAt").value(expires.plus(1, ChronoUnit.DAYS).toString()))
				.andExpect(jsonPath("$.lots[2].expiresAt").doesNotExist())
				.andExpect(jsonPath("$.nextExpiryAt").value(expires.plus(1, ChronoUnit.DAYS).toString()));
		mvc.perform(get("/api/v1/me/credits").header("Authorization", other.bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.balanceUnits").value(0)).andExpect(jsonPath("$.lots.length()").value(0));
		mvc.perform(get("/api/v1/me/credits/transactions").header("Authorization", other.bearer()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));

		String page1 = mvc.perform(get("/api/v1/me/credits/transactions").param("limit", "2")
				.header("Authorization", owner.bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].transactionType").value("GRANT"))
				.andExpect(jsonPath("$.items[0].balanceAfter").value(7.5))
				.andExpect(jsonPath("$.items[1].balanceAfter").value(5)).andReturn().getResponse()
				.getContentAsString();
		String cursor = JsonPath.read(page1, "$.nextCursor");
		mvc.perform(get("/api/v1/me/credits/transactions").param("limit", "2").param("cursor", cursor)
				.header("Authorization", owner.bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].balanceAfter").value(2.5))
				.andExpect(jsonPath("$.nextCursor").doesNotExist());
		// someone else's cursor only pages that caller's own rows
		mvc.perform(get("/api/v1/me/credits/transactions").param("cursor", cursor).header("Authorization",
				other.bearer())).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
		mvc.perform(get("/api/v1/me/credits/transactions").param("cursor", "not-a-cursor")
				.header("Authorization", owner.bearer())).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/me/credits/transactions").param("limit", "0").header("Authorization",
				owner.bearer())).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/me/credits/transactions").param("limit", "101").header("Authorization",
				owner.bearer())).andExpect(status().isBadRequest());

		UUID guest = transactions.execute(s -> accounts.createGuest(Instant.now()).id());
		mvc.perform(get("/api/v1/me/credits").header("Authorization",
				"Bearer " + accessTokens.issue(guest, UUID.randomUUID(), null, "GUEST").value()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.balanceUnits").value(0));
	}
}
