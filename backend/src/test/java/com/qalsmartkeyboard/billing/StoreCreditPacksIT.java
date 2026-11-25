package com.qalsmartkeyboard.billing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
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
import com.qalsmartkeyboard.usage.Credits;
import com.qalsmartkeyboard.usage.Metering;
import com.qalsmartkeyboard.usage.QuotaExceededException;
import com.qalsmartkeyboard.usage.QuotaExceededException.UpgradeOption;
import com.qalsmartkeyboard.usage.Reservation;

/**
 * T8.10 store-billed credit packs (Google Play consumables through RevenueCat, owner rule 2026-11-25) end to end on
 * PostgreSQL 18 with the real security chain and outbox, contract-validated, against a WireMock RevenueCat (v1
 * {@code GET /subscribers} with {@code non_subscriptions}, webhook events of the documented shape):
 * <ul>
 * <li>the store listing (options and products with {@code storeProductId}), no gateway order in a store market;</li>
 * <li>report → verify → exactly one lot with the pack's units and purchase time + validity, {@code /me/credits};</li>
 * <li>duplicate reports, webhook-then-report, report-then-webhook, replayed and repeated webhooks, a webhook alone and
 * 8 concurrent reports: one lot;</li>
 * <li>refunds reverse only the unused credits (confirmed by the API only), replays reverse nothing;</li>
 * <li>refusals: unverified / unknown transaction, another account's transaction (also after a transfer), the other
 * environment, a pack not offered or a policy not approved (kept PENDING, granted once the gate opens), the
 * direct-download build, guests;</li>
 * <li>the direct-download billing options (no purchase in this build), the QUOTA_EXCEEDED hint, and the ledger
 * invariant (a PURCHASE lot has exactly one backing: an order or a store purchase).</li>
 * </ul>
 */
@SpringBootTest(properties = { "qal.outbox.poll-interval=100ms",
		"qal.billing.revenuecat.secret-api-key=sk_it_revenuecat",
		"qal.billing.revenuecat.webhook-auth=" + StoreCreditPacksIT.RC_AUTH,
		"qal.billing.public-base-url=https://api.example.test", "qal.geo.trusted-proxies=127.0.0.1/32,::1/128" })
@AutoConfigureMockMvc
@ContractValidated
@Testcontainers
class StoreCreditPacksIT {

	static final String RC_AUTH = "Bearer it-revenuecat-webhook-authorization-store-packs";
	static final List<String> PACKS = List.of("CREDITS_100", "CREDITS_500", "CREDITS_1500");

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	static final WireMockServer WM = new WireMockServer(
			options().dynamicPort().notifier(new ConsoleNotifier(false)));

	static {
		WM.start();
	}

	@DynamicPropertySource
	static void urls(DynamicPropertyRegistry registry) {
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

	/**
	 * The go-live switches of the README "Store-billed credit packs: go-live" (sandbox): RevenueCat active, packs
	 * ACTIVE, the ZZ Play policy approved, the sandbox pack mappings active and AI credits offered on ZZ.
	 */
	@BeforeEach
	void activate() {
		WM.resetAll();
		UUID reviewer = registered().id();
		jdbc.update("UPDATE payment_providers SET provider_status = 'ACTIVE', is_active = TRUE WHERE provider_code = 'REVENUECAT'");
		jdbc.update("UPDATE billing_products SET product_status = 'ACTIVE' WHERE product_code IN ('CREDITS_100', 'CREDITS_500', 'CREDITS_1500')");
		approveZzPlay(reviewer);
		jdbc.update("""
				UPDATE external_product_mappings SET is_active = TRUE
				WHERE environment = 'SANDBOX' AND external_product_id IN ('credits_100', 'credits_500', 'credits_1500')
				  AND provider_id = (SELECT id FROM payment_providers WHERE provider_code = 'REVENUECAT')""");
		zzCredits(true);
	}

	void approveZzPlay(UUID reviewer) {
		jdbc.update("""
				UPDATE billing_market_channel_policies
				   SET approval_status = 'APPROVED', reviewed_at = NOW(), reviewed_by_user_id = COALESCE(reviewed_by_user_id, ?),
				       purchase_ui_allowed = TRUE
				 WHERE country_code = 'ZZ' AND client_platform = 'ANDROID' AND distribution_channel = 'GOOGLE_PLAY'""",
				reviewer);
	}

	void zzCredits(boolean enabled) {
		jdbc.update("UPDATE billing_market_offerings SET is_enabled = ? WHERE market_country_code = 'ZZ' AND offering = 'AI_CREDITS'",
				enabled);
	}

	// ------------------------------------------------------------------------------------------------ helpers

	static MockHttpServletRequestBuilder fromCountry(MockHttpServletRequestBuilder b, String country) {
		return b.header("CF-IPCountry", country).header("X-Qal-Network-Country", country.toLowerCase())
				.header("X-Client-Platform", "ANDROID");
	}

	static Instant purchaseTime() {
		return Instant.now().minus(Duration.ofMinutes(5)).truncatedTo(ChronoUnit.SECONDS);
	}

	/** One {@code non_subscriptions} transaction (v1 shape; see RevenueCatProvider for the fields read). */
	static String tx(String storeTransactionId, Instant purchased, boolean sandbox, Instant refundedAt) {
		return """
				{"id":"rc-%1$s","is_sandbox":%2$s,"purchase_date":"%3$s","original_purchase_date":"%3$s",
				"store":"play_store","store_transaction_id":"%1$s"%4$s}"""
				.formatted(storeTransactionId, sandbox, purchased,
						refundedAt == null ? "" : ",\"refunded_at\":\"" + refundedAt + "\"");
	}

	/** RevenueCat's subscriber document of {@code user}: no subscriptions, these one-time purchases. */
	static void rc(UUID user, String nonSubscriptions) {
		WM.stubFor(WireMock.get(urlPathEqualTo("/rc/v1/subscribers/" + user))
				.withHeader("Authorization", equalTo("Bearer sk_it_revenuecat"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
						{"request_date":"2026-11-25T12:00:00Z","request_date_ms":1795608000000,"subscriber":{
						"original_app_user_id":"%s","management_url":null,"first_seen":"2026-11-01T00:00:00Z",
						"entitlements":{},"subscriptions":{},"other_purchases":{},"non_subscriptions":%s}}"""
						.formatted(user, nonSubscriptions))));
	}

	static void rcOne(UUID user, String product, String storeTransactionId, Instant purchased) {
		rc(user, "{\"" + product + "\":[" + tx(storeTransactionId, purchased, true, null) + "]}");
	}

	static String newTx() {
		return "GPA.3388-" + ThreadLocalRandom.current().nextInt(1000, 9999) + "-"
				+ ThreadLocalRandom.current().nextInt(10000, 99999) + "-" + UUID.randomUUID().toString().substring(0, 5);
	}

	ResultActions report(User u, String productId, String transactionId, String channel) throws Exception {
		StringBuilder body = new StringBuilder("{\"provider\":\"REVENUECAT\",\"productId\":\"" + productId + "\"");
		if (transactionId != null) {
			body.append(",\"transactionId\":\"").append(transactionId).append('"');
		}
		if (channel != null) {
			body.append(",\"distributionChannel\":\"").append(channel).append('"');
		}
		body.append('}');
		return mvc.perform(MockMvcRequestBuilders.post("/api/v1/billing/store-purchases")
				.header("Authorization", u.bearer()).header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON).content(body.toString()));
	}

	static String rcEvent(String id, String type, UUID user, String product, String transactionId, String extra) {
		return """
				{"api_version":"1.0","event":{"id":"%s","type":"%s","app_user_id":"%s","original_app_user_id":"%s",
				"aliases":["%s"],"product_id":"%s","period_type":"NORMAL","purchased_at_ms":1795600000000,
				"expiration_at_ms":null,"environment":"SANDBOX","entitlement_ids":null,"store":"PLAY_STORE",
				"transaction_id":"%s","original_transaction_id":"%s","event_timestamp_ms":1795600001000,
				"app_id":"app1","country_code":"US","currency":"USD","price":3.99,
				"subscriber_attributes":{"$email":{"value":"buyer@example.com","updated_at_ms":1}}%s}}"""
				.formatted(id, type, user, user, user, product, transactionId, transactionId, extra);
	}

	ResultActions rcWebhook(String body) throws Exception {
		return mvc.perform(MockMvcRequestBuilders.post("/api/v1/billing/store-notifications/REVENUECAT")
				.contentType(MediaType.APPLICATION_JSON).content(body).header("Authorization", RC_AUTH));
	}

	String webhookStatus(String eventJson) {
		return jdbc.queryForList("SELECT event_status FROM payment_webhook_events WHERE external_event_id = ?",
				String.class, (String) JsonPath.read(eventJson, "$.event.id")).stream().findFirst().orElse(null);
	}

	int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	int lots(UUID user) {
		return count("""
				SELECT COUNT(*) FROM ai_credit_lots l JOIN ai_credit_accounts a ON a.id = l.credit_account_id
				WHERE a.user_id = ? AND l.lot_source = 'PURCHASE'""", user);
	}

	int ledger(UUID user, String type) {
		return count("""
				SELECT COUNT(*) FROM ai_credit_transactions t JOIN ai_credit_accounts a ON a.id = t.credit_account_id
				WHERE a.user_id = ? AND t.transaction_type = ?""", user, type);
	}

	String purchaseStatus(String storeTransactionId) {
		return jdbc.queryForList("SELECT purchase_status FROM store_purchases WHERE store_transaction_id = ?",
				String.class, storeTransactionId).stream().findFirst().orElse(null);
	}

	BigDecimal balance(UUID user) {
		return credits.balance(user, Instant.now()).exact();
	}

	ResultActions billingOptions(User u, String country, String channel) throws Exception {
		return mvc.perform(fromCountry(get("/api/v1/billing/options"), country).header("Authorization", u.bearer())
				.param("clientPlatform", "ANDROID").param("distributionChannel", channel)).andExpect(status().isOk());
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

	// ------------------------------------------------------------------------------------------------ listing + grant

	@Test
	void theStoreListsThePacksAndAVerifiedReportGrantsExactlyOneLot() throws Exception {
		User u = registered();
		billingOptions(u, "US", "GOOGLE_PLAY").andExpect(jsonPath("$.purchaseFlow").value("STORE_BILLING"))
				.andExpect(jsonPath("$.purchaseUiAllowed").value(true))
				.andExpect(jsonPath("$.paymentMethods[0].providerCode").value("REVENUECAT"))
				.andExpect(jsonPath("$.products.length()").value(3))
				.andExpect(jsonPath("$.products[0].productCode").value("CREDITS_100"))
				.andExpect(jsonPath("$.products[0].productType").value("AI_CREDIT_PACK"))
				.andExpect(jsonPath("$.products[0].creditUnits").value(100))
				.andExpect(jsonPath("$.products[0].creditValidityDays").value(365))
				.andExpect(jsonPath("$.products[0].prices[0].billingPeriod").value("ONE_TIME"))
				.andExpect(jsonPath("$.products[0].prices[0].renewalModel").value("STORE_MANAGED"))
				.andExpect(jsonPath("$.products[0].prices[0].storeProductId").value("credits_100"))
				.andExpect(jsonPath("$.products[0].prices[0].storeOfferingId").value("credits"))
				.andExpect(jsonPath("$.products[0].prices[0].storePackageId").value("credits_100"))
				.andExpect(jsonPath("$.products[0].prices[0].storeOfferId").doesNotExist())
				.andExpect(jsonPath("$.products[2].prices[0].storeProductId").value("credits_1500"))
				.andExpect(jsonPath("$.products[?(@.productType == 'SUBSCRIPTION')]").isEmpty());
		mvc.perform(get("/api/v1/billing/products").header("Authorization", u.bearer()).param("marketCountry", "ZZ")
				.param("salesChannel", "ANDROID_GOOGLE_PLAY")).andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(3))
				.andExpect(jsonPath("$.items[1].prices[0].storeProductId").value("credits_500"));
		// the store flow never uses gateway orders
		mvc.perform(fromCountry(MockMvcRequestBuilders.post("/api/v1/billing/orders"), "US")
				.header("Authorization", u.bearer()).header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"productCode":"CREDITS_500","billingPeriod":"ONE_TIME","marketCountry":"US","salesChannel":"ANDROID_GOOGLE_PLAY"}"""))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BILLING_POLICY_DENIED"));
		assertThat(count("SELECT COUNT(*) FROM payment_orders WHERE user_id = ?", u.id())).isZero();

		String gpa = newTx();
		Instant purchased = purchaseTime();
		rcOne(u.id(), "credits_500", gpa, purchased);
		String body = report(u, "credits_500", gpa, "GOOGLE_PLAY").andExpect(status().isOk())
				.andExpect(jsonPath("$.purchaseType").value("CONSUMABLE"))
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.environment").value("SANDBOX"))
				.andExpect(jsonPath("$.productId").value("credits_500"))
				.andExpect(jsonPath("$.productCode").value("CREDITS_500"))
				.andExpect(jsonPath("$.transactionId").value(gpa))
				.andExpect(jsonPath("$.creditUnits").value(500))
				.andExpect(jsonPath("$.autoRenewing").value(false))
				.andExpect(jsonPath("$.subscription").doesNotExist())
				.andExpect(jsonPath("$.creditLotId").isNotEmpty()).andReturn().getResponse().getContentAsString();
		String lotId = JsonPath.read(body, "$.creditLotId");
		assertThat(Instant.parse(JsonPath.read(body, "$.creditsExpireAt")))
				.isEqualTo(purchased.plus(Duration.ofDays(365)));
		WM.verify(getRequestedFor(urlPathEqualTo("/rc/v1/subscribers/" + u.id())));

		mvc.perform(get("/api/v1/me/credits").header("Authorization", u.bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.balanceUnits").value(500)).andExpect(jsonPath("$.lots.length()").value(1))
				.andExpect(jsonPath("$.lots[0].lotId").value(lotId))
				.andExpect(jsonPath("$.lots[0].source").value("PURCHASE"))
				.andExpect(jsonPath("$.lots[0].originalUnits").value(500))
				.andExpect(jsonPath("$.lots[0].expiresAt").isNotEmpty());
		assertThat(jdbc.queryForObject("""
				SELECT sp.purchase_type || '/' || bp.product_code || '/' || sp.purchase_status || '/' || sp.auto_renewing
				FROM store_purchases sp JOIN billing_products bp ON bp.id = sp.billing_product_id
				WHERE sp.store_transaction_id = ?""", String.class, gpa)).isEqualTo("CONSUMABLE/CREDITS_500/ACTIVE/false");
		assertThat(count("""
				SELECT COUNT(*) FROM ai_credit_lots l JOIN store_purchases sp ON sp.id = l.store_purchase_id
				WHERE l.id = ?::uuid AND sp.store_transaction_id = ? AND l.payment_order_id IS NULL
				  AND l.initial_units = 500""", lotId, gpa)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM user_subscriptions WHERE user_id = ?", u.id())).isZero();
		assertThat(ledger(u.id(), "PURCHASE")).isEqualTo(1);

		// re-reports (same transaction, or the product alone) answer the same purchase and grant nothing more
		report(u, "credits_500", gpa, "GOOGLE_PLAY").andExpect(status().isOk())
				.andExpect(jsonPath("$.creditLotId").value(lotId));
		report(u, "credits_500", null, null).andExpect(status().isOk()).andExpect(jsonPath("$.creditLotId").value(lotId));
		mvc.perform(MockMvcRequestBuilders.post("/api/v1/billing/store-purchases").header("Authorization", u.bearer())
				.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"provider":"GOOGLE_PLAY","productId":"credits_500","purchaseToken":"play-token-it","packageName":"com.qalsmartkeyboard.app","transactionId":"%s"}"""
						.formatted(gpa)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.creditLotId").value(lotId));
		assertThat(lots(u.id())).isEqualTo(1);
		assertThat(ledger(u.id(), "PURCHASE")).isEqualTo(1);
		assertThat(balance(u.id())).isEqualByComparingTo("500");
		assertThat(count("""
				SELECT COUNT(*) FROM audit_events WHERE action_code = 'BILLING_STORE_CREDIT_PACK_GRANT'
				AND actor_type = 'PROVIDER' AND entity_id = (SELECT id::text FROM store_purchases WHERE store_transaction_id = ?)""",
				gpa)).isEqualTo(1);
		// the purchase token is neither stored nor logged into the purchase
		assertThat(count("SELECT COUNT(*) FROM store_purchases WHERE metadata::text LIKE '%play-token-it%'")).isZero();
	}

	@Test
	void webhookAndReportInEitherOrderReplaysAndAWebhookAloneGrantOnce() throws Exception {
		// webhook first (the app was closed): the webhook alone grants
		User a = registered();
		String gpaA = newTx();
		rcOne(a.id(), "credits_100", gpaA, purchaseTime());
		String first = rcEvent(UUID.randomUUID().toString(), "NON_RENEWING_PURCHASE", a.id(), "credits_100", gpaA, "");
		rcWebhook(first).andExpect(status().isOk()).andExpect(jsonPath("$.received").value(true));
		await().atMost(Duration.ofSeconds(15)).until(() -> lots(a.id()) == 1);
		assertThat(purchaseStatus(gpaA)).isEqualTo("ACTIVE");
		assertThat(count("""
				SELECT COUNT(*) FROM store_purchase_events e JOIN store_purchases sp ON sp.id = e.store_purchase_id
				WHERE sp.store_transaction_id = ? AND e.notification_type = 'NON_RENEWING_PURCHASE'
				  AND e.event_details::text NOT LIKE '%buyer@example.com%'""", gpaA)).isEqualTo(1);
		// then the client report: the same lot
		String lot = JsonPath.read(report(a, "credits_100", gpaA, "GOOGLE_PLAY").andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString(), "$.creditLotId");
		// a replayed delivery (same event id) is stored once; another event about the same transaction changes nothing
		rcWebhook(first).andExpect(status().isOk());
		String again = rcEvent(UUID.randomUUID().toString(), "NON_RENEWING_PURCHASE", a.id(), "credits_100", gpaA, "");
		rcWebhook(again).andExpect(status().isOk());
		await().atMost(Duration.ofSeconds(15)).until(() -> "PROCESSED".equals(webhookStatus(again)));
		assertThat(count("SELECT COUNT(*) FROM payment_webhook_events WHERE external_event_id = ?",
				(String) JsonPath.read(first, "$.event.id"))).isEqualTo(1);
		assertThat(lots(a.id())).isEqualTo(1);
		assertThat(ledger(a.id(), "PURCHASE")).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT id::text FROM ai_credit_lots WHERE store_purchase_id = "
				+ "(SELECT id FROM store_purchases WHERE store_transaction_id = ?)", String.class, gpaA)).isEqualTo(lot);
		assertThat(balance(a.id())).isEqualByComparingTo("100");

		// report first, then the webhook
		User b = registered();
		String gpaB = newTx();
		rcOne(b.id(), "credits_1500", gpaB, purchaseTime());
		report(b, "credits_1500", gpaB, "GOOGLE_PLAY").andExpect(status().isOk())
				.andExpect(jsonPath("$.creditUnits").value(1500));
		String late = rcEvent(UUID.randomUUID().toString(), "NON_RENEWING_PURCHASE", b.id(), "credits_1500", gpaB, "");
		rcWebhook(late).andExpect(status().isOk());
		await().atMost(Duration.ofSeconds(15)).until(() -> "PROCESSED".equals(webhookStatus(late)));
		assertThat(lots(b.id())).isEqualTo(1);
		assertThat(ledger(b.id(), "PURCHASE")).isEqualTo(1);
		assertThat(balance(b.id())).isEqualByComparingTo("1500");
		assertThat(count("SELECT COUNT(*) FROM store_purchases WHERE store_transaction_id IN (?, ?)", gpaA, gpaB))
				.isEqualTo(2);
	}

	@Test
	void eightConcurrentReportsAndAWebhookGrantOneLot() throws Exception {
		User u = registered();
		String gpa = newTx();
		rcOne(u.id(), "credits_100", gpa, purchaseTime());
		int threads = 8;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		List<Integer> statuses = new ArrayList<>();
		try {
			CountDownLatch go = new CountDownLatch(1);
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				results.add(pool.submit(() -> {
					go.await();
					return report(u, "credits_100", gpa, "GOOGLE_PLAY").andReturn().getResponse().getStatus();
				}));
			}
			go.countDown();
			rcWebhook(rcEvent(UUID.randomUUID().toString(), "NON_RENEWING_PURCHASE", u.id(), "credits_100", gpa, ""))
					.andExpect(status().isOk());
			for (Future<Integer> f : results) {
				statuses.add(f.get());
			}
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(statuses).hasSize(threads).allMatch(s -> s == 200);
		Thread.sleep(1000); // let the outbox process the webhook too
		assertThat(lots(u.id())).isEqualTo(1);
		assertThat(ledger(u.id(), "PURCHASE")).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM store_purchases WHERE store_transaction_id = ?", gpa)).isEqualTo(1);
		assertThat(balance(u.id())).isEqualByComparingTo("100");
	}

	// ------------------------------------------------------------------------------------------------ refunds

	@Test
	void aRefundReversesOnlyTheUnusedCreditsOnceTheStoreConfirmsIt() throws Exception {
		User u = registered();
		String gpa = newTx();
		rcOne(u.id(), "credits_100", gpa, purchaseTime());
		report(u, "credits_100", gpa, "GOOGLE_PLAY").andExpect(status().isOk());
		for (int i = 0; i < 12; i++) { // FREE: 10 translations a day from the allowance, then 1 credit each
			Reservation r = metering.reserve(u.id(), "TRANSLATE", 1, UUID.randomUUID().toString());
			metering.settle(r.id(), 1);
		}
		assertThat(balance(u.id())).isEqualByComparingTo("98");

		// a CANCELLATION the REST API contradicts (still listed, not refunded) reverses nothing and is retried
		String claimed = rcEvent(UUID.randomUUID().toString(), "CANCELLATION", u.id(), "credits_100", gpa,
				",\"cancel_reason\":\"CUSTOMER_SUPPORT\"");
		rcWebhook(claimed).andExpect(status().isOk());
		await().atMost(Duration.ofSeconds(15)).until(() -> count("""
				SELECT COUNT(*) FROM payment_webhook_events WHERE external_event_id = ?
				AND processing_error = 'REFUND_NOT_CONFIRMED'""", (String) JsonPath.read(claimed, "$.event.id")) == 1);
		assertThat(purchaseStatus(gpa)).isEqualTo("ACTIVE");
		assertThat(ledger(u.id(), "REVERSAL")).isZero();
		assertThat(balance(u.id())).isEqualByComparingTo("98");

		// RevenueCat no longer lists the refunded consumable: the CANCELLATION is confirmed
		rc(u.id(), "{}");
		String refund = rcEvent(UUID.randomUUID().toString(), "CANCELLATION", u.id(), "credits_100", gpa,
				",\"cancel_reason\":\"CUSTOMER_SUPPORT\"");
		rcWebhook(refund).andExpect(status().isOk());
		await().atMost(Duration.ofSeconds(15)).until(() -> "REFUNDED".equals(purchaseStatus(gpa)));
		assertThat(balance(u.id())).isEqualByComparingTo("0");
		assertThat(count("""
				SELECT COUNT(*) FROM ai_credit_transactions t JOIN ai_credit_accounts a ON a.id = t.credit_account_id
				WHERE a.user_id = ? AND t.transaction_type = 'REVERSAL' AND t.delta_units = -98 AND t.balance_after = 0""",
				u.id())).isEqualTo(1);
		assertThat(ledger(u.id(), "CONSUMPTION")).isEqualTo(2); // consumed credits stay consumed
		assertThat(jdbc.queryForObject("""
				SELECT metadata->>'reversedCredits' || '/' || (metadata->>'cancelReason') FROM audit_events
				WHERE action_code = 'BILLING_STORE_CREDIT_PACK_REFUND' AND actor_type = 'PROVIDER'
				  AND entity_id = (SELECT id::text FROM store_purchases WHERE store_transaction_id = ?)""", String.class,
				gpa)).isEqualTo("98/CUSTOMER_SUPPORT");

		// replays: another CANCELLATION, the client report, the earlier unconfirmed event's retry: nothing more
		String replay = rcEvent(UUID.randomUUID().toString(), "CANCELLATION", u.id(), "credits_100", gpa, "");
		rcWebhook(replay).andExpect(status().isOk());
		await().atMost(Duration.ofSeconds(15)).until(() -> "PROCESSED".equals(webhookStatus(replay)));
		rcOne(u.id(), "credits_100", gpa, purchaseTime());
		report(u, "credits_100", gpa, "GOOGLE_PLAY").andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("REFUNDED")).andExpect(jsonPath("$.creditLotId").isNotEmpty());
		assertThat(ledger(u.id(), "REVERSAL")).isEqualTo(1);
		assertThat(lots(u.id())).isEqualTo(1);
		assertThat(balance(u.id())).isEqualByComparingTo("0");

		// refunded_at on the listed transaction (seen by a report) refunds as well
		User v = registered();
		String gpaV = newTx();
		Instant bought = purchaseTime();
		rcOne(v.id(), "credits_500", gpaV, bought);
		report(v, "credits_500", gpaV, "GOOGLE_PLAY").andExpect(status().isOk());
		rc(v.id(), "{\"credits_500\":[" + tx(gpaV, bought, true, Instant.now().truncatedTo(ChronoUnit.SECONDS)) + "]}");
		report(v, "credits_500", gpaV, "GOOGLE_PLAY").andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("REFUNDED"));
		report(v, "credits_500", gpaV, "GOOGLE_PLAY").andExpect(status().isOk());
		assertThat(balance(v.id())).isEqualByComparingTo("0");
		assertThat(count("""
				SELECT COUNT(*) FROM ai_credit_transactions t JOIN ai_credit_accounts a ON a.id = t.credit_account_id
				WHERE a.user_id = ? AND t.transaction_type = 'REVERSAL' AND t.delta_units = -500""", v.id())).isEqualTo(1);
	}

	// ------------------------------------------------------------------------------------------------ refusals

	@Test
	void nothingIsGrantedForUnverifiedForeignOrOtherEnvironmentPurchases() throws Exception {
		// RevenueCat does not list it (yet): nothing
		User u = registered();
		rc(u.id(), "{}");
		report(u, "credits_500", newTx(), "GOOGLE_PLAY").andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("STORE_PURCHASE_INVALID"));
		// another transaction than the one listed
		rcOne(u.id(), "credits_500", newTx(), purchaseTime());
		report(u, "credits_500", "GPA.0000-0000-0000-00000", "GOOGLE_PLAY").andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("STORE_PURCHASE_INVALID"));
		// a listed transaction without a store transaction id is never granted
		User noId = registered();
		rc(noId.id(), """
				{"credits_100":[{"id":"cadba0c81b","is_sandbox":true,"purchase_date":"2026-11-25T09:00:00Z","store":"play_store"}]}""");
		report(noId, "credits_100", null, "GOOGLE_PLAY").andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("STORE_PURCHASE_INVALID"));
		assertThat(lots(noId.id())).isZero();

		// another account's transaction: the caller's own RevenueCat customer does not list it
		User owner = registered();
		User other = registered();
		String gpa = newTx();
		rcOne(owner.id(), "credits_500", gpa, purchaseTime());
		rc(other.id(), "{}");
		report(other, "credits_500", gpa, "GOOGLE_PLAY").andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("STORE_PURCHASE_INVALID"));
		report(owner, "credits_500", gpa, "GOOGLE_PLAY").andExpect(status().isOk());
		// even when RevenueCat lists it for the other account later (a transfer), it is granted once, to its owner
		rcOne(other.id(), "credits_500", gpa, purchaseTime());
		report(other, "credits_500", gpa, "GOOGLE_PLAY").andExpect(status().isUnprocessableContent());
		rcWebhook(rcEvent(UUID.randomUUID().toString(), "TRANSFER", other.id(), "credits_500", gpa, "")).andExpect(
				status().isOk());
		Thread.sleep(1000);
		assertThat(lots(other.id())).isZero();
		assertThat(lots(owner.id())).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM store_purchases WHERE store_transaction_id = ?", gpa)).isEqualTo(1);

		// a production purchase is not honoured by this sandbox server
		User prod = registered();
		String gpaProd = newTx();
		rc(prod.id(), "{\"credits_500\":[" + tx(gpaProd, purchaseTime(), false, null) + "]}");
		report(prod, "credits_500", gpaProd, "GOOGLE_PLAY").andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("STORE_PURCHASE_INVALID"))
				.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("environment")));
		assertThat(count("SELECT COUNT(*) FROM store_purchases WHERE user_id = ?", prod.id())).isZero();

		// the direct-download build has no store billing: refused before RevenueCat is asked
		User direct = registered();
		report(direct, "credits_500", newTx(), "DIRECT_DOWNLOAD").andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("BILLING_POLICY_DENIED"));
		WM.verify(0, getRequestedFor(urlPathEqualTo("/rc/v1/subscribers/" + direct.id())));

		// guests cannot buy
		UUID guest = transactions.execute(s -> accounts.createGuest(Instant.now()).id());
		User g = new User(guest, "Bearer " + accessTokens.issue(guest, UUID.randomUUID(), null, "GUEST").value());
		report(g, "credits_500", newTx(), "GOOGLE_PLAY").andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("GUEST_NOT_ALLOWED"));
		assertThat(lots(guest)).isZero();
	}

	@Test
	void aPackNotOfferedOrAPolicyNotApprovedIsKeptPendingAndGrantedOnceTheGateOpens() throws Exception {
		User u = registered();
		String gpa = newTx();
		rcOne(u.id(), "credits_500", gpa, purchaseTime());
		zzCredits(false);
		try {
			billingOptions(u, "US", "GOOGLE_PLAY").andExpect(jsonPath("$.products").isEmpty());
			report(u, "credits_500", gpa, "GOOGLE_PLAY").andExpect(status().isUnprocessableContent())
					.andExpect(jsonPath("$.code").value("PRODUCT_NOT_AVAILABLE"));
			assertThat(purchaseStatus(gpa)).isEqualTo("PENDING");
			assertThat(jdbc.queryForObject("SELECT metadata->>'blockedReason' FROM store_purchases WHERE store_transaction_id = ?",
					String.class, gpa)).isEqualTo("NOT_SELLABLE");
			assertThat(lots(u.id())).isZero();
		}
		finally {
			zzCredits(true);
		}
		report(u, "credits_500", gpa, "GOOGLE_PLAY").andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACTIVE"));
		assertThat(lots(u.id())).isEqualTo(1);

		// policy not approved: verified, kept, refused; a later webhook grants once it is approved again
		User v = registered();
		String gpaV = newTx();
		rcOne(v.id(), "credits_100", gpaV, purchaseTime());
		jdbc.update("""
				UPDATE billing_market_channel_policies SET approval_status = 'REVIEW_REQUIRED', purchase_ui_allowed = FALSE
				WHERE country_code = 'ZZ' AND client_platform = 'ANDROID' AND distribution_channel = 'GOOGLE_PLAY'""");
		try {
			report(v, "credits_100", gpaV, "GOOGLE_PLAY").andExpect(status().isForbidden())
					.andExpect(jsonPath("$.code").value("BILLING_POLICY_DENIED"));
			assertThat(purchaseStatus(gpaV)).isEqualTo("PENDING");
			assertThat(jdbc.queryForObject("SELECT metadata->>'blockedReason' FROM store_purchases WHERE store_transaction_id = ?",
					String.class, gpaV)).isEqualTo("POLICY_DENIED");
			assertThat(lots(v.id())).isZero();
		}
		finally {
			approveZzPlay(registered().id());
		}
		rcWebhook(rcEvent(UUID.randomUUID().toString(), "NON_RENEWING_PURCHASE", v.id(), "credits_100", gpaV, ""))
				.andExpect(status().isOk());
		await().atMost(Duration.ofSeconds(15)).until(() -> "ACTIVE".equals(purchaseStatus(gpaV)));
		assertThat(lots(v.id())).isEqualTo(1);

		// an inactive store mapping (not live yet) blocks as well
		User w = registered();
		String gpaW = newTx();
		rcOne(w.id(), "credits_1500", gpaW, purchaseTime());
		jdbc.update("UPDATE external_product_mappings SET is_active = FALSE WHERE external_product_id = 'credits_1500'");
		try {
			report(w, "credits_1500", gpaW, "GOOGLE_PLAY").andExpect(status().isUnprocessableContent())
					.andExpect(jsonPath("$.code").value("PRODUCT_NOT_AVAILABLE"));
			assertThat(lots(w.id())).isZero();
		}
		finally {
			jdbc.update("""
					UPDATE external_product_mappings SET is_active = TRUE
					WHERE external_product_id = 'credits_1500' AND environment = 'SANDBOX'""");
		}
	}

	// ------------------------------------------------------------------------------------------------ direct download

	@Test
	void aDirectDownloadBuildInAStoreMarketIsToldThereIsNoPurchaseInThisBuild() throws Exception {
		User u = registered();
		// V25 left the sideloaded APK's global policy unapproved (no purchase UI)
		assertThat(jdbc.queryForObject("""
				SELECT approval_status || '/' || purchase_ui_allowed || '/' || purchase_flow FROM billing_market_channel_policies
				WHERE country_code = 'ZZ' AND client_platform = 'ANDROID' AND distribution_channel = 'DIRECT_DOWNLOAD'""",
				String.class)).isEqualTo("REVIEW_REQUIRED/false/STORE_BILLING");
		billingOptions(u, "US", "DIRECT_DOWNLOAD").andExpect(jsonPath("$.purchaseUiAllowed").value(false))
				.andExpect(jsonPath("$.purchaseFlow").value("NONE")).andExpect(jsonPath("$.products").isEmpty())
				.andExpect(jsonPath("$.paymentMethods").isEmpty())
				.andExpect(jsonPath("$.notice").value(BillingCatalog.NO_STORE_IN_BUILD));
		mvc.perform(get("/api/v1/billing/products").header("Authorization", u.bearer()).param("marketCountry", "ZZ")
				.param("salesChannel", "ANDROID_DIRECT")).andExpect(status().isOk())
				.andExpect(jsonPath("$.items").isEmpty());
		// even an approved policy for that scope (an operator mistake) never offers store billing to a sideloaded APK
		jdbc.update("""
				UPDATE billing_market_channel_policies
				   SET approval_status = 'APPROVED', reviewed_at = NOW(), reviewed_by_user_id = ?, purchase_ui_allowed = TRUE
				 WHERE country_code = 'ZZ' AND client_platform = 'ANDROID' AND distribution_channel = 'DIRECT_DOWNLOAD'""",
				u.id());
		try {
			billingOptions(u, "US", "DIRECT_DOWNLOAD").andExpect(jsonPath("$.purchaseUiAllowed").value(false))
					.andExpect(jsonPath("$.products").isEmpty())
					.andExpect(jsonPath("$.notice").value(BillingCatalog.NO_STORE_IN_BUILD));
		}
		finally {
			jdbc.update("""
					UPDATE billing_market_channel_policies
					   SET approval_status = 'REVIEW_REQUIRED', purchase_ui_allowed = FALSE
					 WHERE country_code = 'ZZ' AND client_platform = 'ANDROID' AND distribution_channel = 'DIRECT_DOWNLOAD'""");
		}
		// the Play build of the same market is unaffected and gets no notice
		billingOptions(u, "US", "GOOGLE_PLAY").andExpect(jsonPath("$.purchaseUiAllowed").value(true))
				.andExpect(jsonPath("$.notice").doesNotExist());
	}

	// ------------------------------------------------------------------------------------------------ hint + invariant

	@Test
	void quotaExceededOffersStorePacksWhereTheyAreSellable() throws Exception {
		User u = registered();
		jdbc.update("UPDATE users SET market_country_code = 'US', market_source = 'STOREFRONT' WHERE id = ?", u.id());
		for (int i = 0; i < 10; i++) {
			metering.reserve(u.id(), "TRANSLATE", 1, UUID.randomUUID().toString());
		}
		QuotaExceededException q = quota(() -> metering.reserve(u.id(), "TRANSLATE", 1, UUID.randomUUID().toString()));
		assertThat(q.upgradeOptions()).contains(new UpgradeOption("CREDIT_PACK", "CREDITS_100"),
				new UpgradeOption("CREDIT_PACK", "CREDITS_500"), new UpgradeOption("CREDIT_PACK", "CREDITS_1500"));
		zzCredits(false);
		try {
			QuotaExceededException off = quota(
					() -> metering.reserve(u.id(), "TRANSLATE", 1, UUID.randomUUID().toString()));
			assertThat(off.upgradeOptions()).noneMatch(o -> "CREDIT_PACK".equals(o.type()));
		}
		finally {
			zzCredits(true);
		}
		jdbc.update("UPDATE external_product_mappings SET is_active = FALSE WHERE external_product_id LIKE 'credits_%'");
		try {
			QuotaExceededException notLive = quota(
					() -> metering.reserve(u.id(), "TRANSLATE", 1, UUID.randomUUID().toString()));
			assertThat(notLive.upgradeOptions()).noneMatch(o -> "CREDIT_PACK".equals(o.type()));
		}
		finally {
			jdbc.update("""
					UPDATE external_product_mappings SET is_active = TRUE
					WHERE external_product_id LIKE 'credits_%' AND environment = 'SANDBOX'""");
		}
	}

	@Test
	void aPurchaseLotIsBackedByExactlyOneOrderOrStorePurchase() throws Exception {
		User u = registered();
		String gpa = newTx();
		rcOne(u.id(), "credits_100", gpa, purchaseTime());
		report(u, "credits_100", gpa, "GOOGLE_PLAY").andExpect(status().isOk());
		UUID purchase = jdbc.queryForObject("SELECT id FROM store_purchases WHERE store_transaction_id = ?", UUID.class,
				gpa);
		UUID account = jdbc.queryForObject("SELECT id FROM ai_credit_accounts WHERE user_id = ?", UUID.class, u.id());

		assertThat(count("""
				SELECT COUNT(*) FROM ai_credit_lots
				WHERE lot_source = 'PURCHASE' AND num_nonnulls(payment_order_id, store_purchase_id) <> 1""")).isZero();
		assertThat(count("SELECT COUNT(*) FROM ai_credit_lots WHERE lot_source <> 'PURCHASE' AND store_purchase_id IS NOT NULL"))
				.isZero();
		// neither backing, both backings, a non-purchase lot pointing at a store purchase: refused by the schema
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO ai_credit_lots (credit_account_id, lot_source, initial_units, remaining_units)
				VALUES (?, 'PURCHASE', 1, 1)""", account)).isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_credit_lot_purchase_backing");
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO ai_credit_lots (credit_account_id, lot_source, initial_units, remaining_units, payment_order_id,
				       store_purchase_id)
				VALUES (?, 'PURCHASE', 1, 1, ?, ?)""", account, UUID.randomUUID(), purchase))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_credit_lot_purchase_backing");
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO ai_credit_lots (credit_account_id, lot_source, initial_units, remaining_units, store_purchase_id)
				VALUES (?, 'GRANT', 1, 1, ?)""", account, purchase)).isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_credit_lot_purchase_backing");
		// one lot per store purchase: a second row and a regrant with other units are refused
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO ai_credit_lots (credit_account_id, lot_source, initial_units, remaining_units, store_purchase_id)
				VALUES (?, 'PURCHASE', 1, 1, ?)""", account, purchase)).isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ux_credit_lot_store_purchase");
		assertThat(credits.grantStorePurchase(u.id(), new BigDecimal("100"), purchase,
				jdbc.queryForObject("SELECT expires_at FROM ai_credit_lots WHERE store_purchase_id = ?",
						java.sql.Timestamp.class, purchase).toInstant()).created()).isFalse();
		assertThatThrownBy(() -> credits.grantStorePurchase(u.id(), new BigDecimal("101"), purchase,
				Instant.now().plus(Duration.ofDays(30)))).isInstanceOf(IllegalArgumentException.class);
		// a consumable store purchase always names its pack and its store transaction
		assertThatThrownBy(() -> jdbc.update("UPDATE store_purchases SET store_transaction_id = NULL WHERE id = ?",
				purchase)).isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_store_purchase_consumable_fields");
		assertThat(lots(u.id())).isEqualTo(1);
		assertThat(PACKS).allMatch(code -> count("""
				SELECT COUNT(*) FROM external_product_mappings m JOIN billing_prices pr ON pr.id = m.billing_price_id
				JOIN billing_products bp ON bp.id = pr.billing_product_id
				WHERE bp.product_code = ? AND pr.renewal_model = 'STORE_MANAGED' AND NOT pr.is_active""", code) == 2);
	}
}
