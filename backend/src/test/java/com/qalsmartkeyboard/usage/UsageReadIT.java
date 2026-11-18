package com.qalsmartkeyboard.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.jayway.jsonpath.JsonPath;
import com.qalsmartkeyboard.entitlements.EntitlementTokenIssuer;
import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;

/**
 * B4.5 "done when": {@code /me/entitlements} and {@code /me/usage} on PostgreSQL 18 with the V1 seed, real metering
 * reservations and settlements and real credit lots. Every number is compared with what {@link Metering} and
 * {@link Credits} report, every response is validated against the contract, and reads are proven to neither create
 * counter rows nor wait for a row lock the engine holds.
 */
@SpringBootTest(properties = { "qal.outbox.dispatcher-enabled=false", "qal.entitlements.cache-ttl=0s" })
@AutoConfigureMockMvc
@ContractValidated
@Testcontainers
@Import(UsageReadIT.Fixtures.class)
class UsageReadIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	/** Clock of the engine and the resolver; null = real time. Only set around metering calls (tokens use real time). */
	static final AtomicReference<Instant> NOW = new AtomicReference<>();

	@TestConfiguration(proxyBeanMethods = false)
	static class Fixtures {

		@Bean
		@Primary
		Clock usageReadTestClock() {
			return new Clock() {
				@Override
				public ZoneId getZone() {
					return ZoneOffset.UTC;
				}

				@Override
				public Clock withZone(ZoneId zone) {
					return this;
				}

				@Override
				public Instant instant() {
					Instant fixed = NOW.get();
					return fixed != null ? fixed : Instant.now();
				}
			};
		}

		/** Stands in for the B4.4 signer. */
		@Bean
		EntitlementTokenIssuer fakeTokenIssuer() {
			return (entitlements, deviceId) -> new EntitlementTokenIssuer.Issued("aGVhZGVy.cGF5bG9hZA.c2ln", "ent-test",
					Instant.now().plus(Duration.ofHours(24)), Instant.now().plus(Duration.ofHours(72)));
		}
	}

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	TransactionTemplate tx;
	@Autowired
	AccessTokenService tokens;
	@Autowired
	Metering metering;
	@Autowired
	Credits credits;

	@AfterEach
	void realTime() {
		NOW.set(null);
	}

	// ------------------------------------------------------------------ entitlements

	@Test
	void aNewUserSeesFullAllowancesAndTheReadCreatesNoCounterRows() throws Exception {
		User u = user("REGISTERED", "ACTIVE", "Africa/Addis_Ababa");
		String body = getOk("/api/v1/me/entitlements", u);

		assertThat(counterRows(u.id())).isZero();
		assertThat(JsonPath.<String>read(body, "$.plan.planCode")).isEqualTo("FREE");
		assertThat(JsonPath.<String>read(body, "$.plan.source")).isEqualTo("DEFAULT");
		assertThat(JsonPath.<Number>read(body, "$.creditBalance").longValue()).isZero();
		Map<String, Object> translate = feature(body, "TRANSLATE");
		assertThat(translate).containsEntry("accessEnabled", true).containsEntry("includedUnits", 10)
				.containsEntry("used", 0).containsEntry("remaining", 10).containsEntry("unit", "REQUEST")
				.containsEntry("resetPeriod", "DAILY");
		// resetsAt is the end of the user's calendar day (Africa/Addis_Ababa, UTC+3)
		Instant resetsAt = Instant.parse((String) translate.get("resetsAt"));
		assertThat(resetsAt).isAfter(Instant.now()).isBeforeOrEqualTo(Instant.now().plus(Duration.ofDays(1)));
		assertThat(resetsAt.atZone(ZoneId.of("Africa/Addis_Ababa")).toLocalTime()).isEqualTo(java.time.LocalTime.MIDNIGHT);
		// ungated typing features are listed, allowed and carry no usage
		assertThat(feature(body, "OFFLINE_TYPING")).containsEntry("accessEnabled", true).containsEntry("unit", "NONE")
				.doesNotContainKeys("used", "remaining", "resetsAt", "includedUnits");
		// a gated feature the plan excludes is listed as not enabled
		assertThat(feature(body, "ON_DEVICE_AI_MODELS")).containsEntry("accessEnabled", false);
		assertThat(JsonPath.<String>read(body, "$.entitlementToken.keyId")).isEqualTo("ent-test");
	}

	@Test
	void usedRemainingAndResetMatchTheMeteringEngine() throws Exception {
		User u = user("REGISTERED", "ACTIVE", "Africa/Addis_Ababa");
		Reservation a = metering.reserve(u.id(), "TRANSLATE", 1, key());
		Reservation b = metering.reserve(u.id(), "TRANSLATE", 1, key());
		Reservation c = metering.reserve(u.id(), "TRANSLATE", 2, key());
		Settlement settled = metering.settle(a.id(), 1);
		metering.release(b.id(), ReleaseReason.FAILED);
		// c stays reserved: it counts against "remaining" but is not "used" yet

		String body = getOk("/api/v1/me/entitlements", u);
		Map<String, Object> translate = feature(body, "TRANSLATE");
		assertThat(translate).containsEntry("used", 1).containsEntry("remaining", 7);
		assertThat(Instant.parse((String) translate.get("resetsAt"))).isEqualTo(a.window().resetAt())
				.isEqualTo(settled.resetAt());
		assertThat(counterRows(u.id())).isEqualTo(1); // only the window the engine itself opened

		metering.settle(c.id(), 2);
		Settlement last = metering.settle(metering.reserve(u.id(), "TRANSLATE", 1, key()).id(), 1);
		translate = feature(getOk("/api/v1/me/entitlements", u), "TRANSLATE");
		assertThat(translate).containsEntry("used", 4).containsEntry("remaining", (int) (long) last.remainingAllowance());
		assertThat(last.remainingAllowance()).isEqualTo(6);
	}

	@Test
	void aUserOverQuotaWithCreditsSeesZeroRemainingAndTheRoundedDownBalance() throws Exception {
		User u = user("REGISTERED", "ACTIVE", "Africa/Addis_Ababa");
		credits.grant(u.id(), new BigDecimal("5.500000"), CreditSource.GRANT, UUID.randomUUID().toString(), null);
		metering.settle(metering.reserve(u.id(), "TRANSLATE", 10, key()).id(), 10); // the whole daily allowance
		Reservation paid = metering.reserve(u.id(), "TRANSLATE", 3, key()); // 3 requests x 1 credit
		assertThat(paid.creditUnits()).isEqualTo(3);
		Settlement s = metering.settle(paid.id(), 3);
		assertThat(s.creditBalance()).isEqualByComparingTo("2.5");

		String body = getOk("/api/v1/me/entitlements", u);
		assertThat(feature(body, "TRANSLATE")).containsEntry("used", 10).containsEntry("remaining", 0)
				.containsEntry("includedUnits", 10);
		assertThat(JsonPath.<Number>read(body, "$.creditBalance").longValue()).isEqualTo(2)
				.isEqualTo(credits.balance(u.id(), Instant.now()).display());

		// the usage report carries the credits the window consumed (exact, 6 dp)
		Map<String, Object> item = usageItem(getOk("/api/v1/me/usage", u), "TRANSLATE");
		assertThat(item).containsEntry("usedUnits", 10).containsEntry("includedUnits", 10).containsEntry("unit", "REQUEST");
		assertThat(new BigDecimal(item.get("creditsConsumed").toString())).isEqualByComparingTo("3");
	}

	@Test
	void aPaidPlanReportsItsSourceItsEndAndAnAnchoredMonthlyWindow() throws Exception {
		User u = user("REGISTERED", "ACTIVE", "Africa/Addis_Ababa");
		Instant start = Instant.now().minus(Duration.ofDays(5)).truncatedTo(ChronoUnit.SECONDS);
		Instant end = start.atZone(ZoneId.of("Africa/Addis_Ababa")).plusMonths(1).toInstant();
		UUID subscription = subscription(u.id(), "PREMIUM", start, end);
		Reservation r = metering.reserve(u.id(), "TRANSLATE", 5, key());
		metering.settle(r.id(), 5);

		String body = getOk("/api/v1/me/entitlements", u);
		assertThat(JsonPath.<String>read(body, "$.plan.planCode")).isEqualTo("PREMIUM");
		assertThat(JsonPath.<String>read(body, "$.plan.source")).isEqualTo("SUBSCRIPTION");
		assertThat(JsonPath.<String>read(body, "$.plan.subscriptionId")).isEqualTo(subscription.toString());
		assertThat(Instant.parse(JsonPath.read(body, "$.plan.effectiveUntil"))).isEqualTo(end);
		Map<String, Object> translate = feature(body, "TRANSLATE");
		assertThat(translate).containsEntry("includedUnits", 600).containsEntry("resetPeriod", "MONTHLY")
				.containsEntry("used", 5).containsEntry("remaining", 595);
		assertThat(Instant.parse((String) translate.get("resetsAt"))).isEqualTo(end).isEqualTo(r.window().resetAt());
		// boolean gates of the paid plan
		assertThat(feature(body, "ON_DEVICE_AI_MODELS")).containsEntry("accessEnabled", true).doesNotContainKey("used");

		Map<String, Object> item = usageItem(getOk("/api/v1/me/usage", u), "TRANSLATE");
		assertThat(Instant.parse((String) item.get("periodStart"))).isEqualTo(start);
		assertThat(Instant.parse((String) item.get("periodEnd"))).isEqualTo(end);
		assertThat(item).containsEntry("usedUnits", 5).containsEntry("includedUnits", 600);
	}

	@Test
	void aGuestSeesTheGuestPlan() throws Exception {
		User g = user("GUEST", "ACTIVE", "Africa/Addis_Ababa");
		metering.settle(metering.reserve(g.id(), "TRANSLATE", 1, key()).id(), 1);

		String body = getOk("/api/v1/me/entitlements", g);
		assertThat(JsonPath.<String>read(body, "$.plan.planCode")).isEqualTo("GUEST");
		assertThat(JsonPath.<String>read(body, "$.plan.source")).isEqualTo("DEFAULT");
		assertThat(feature(body, "TRANSLATE")).containsEntry("includedUnits", 3).containsEntry("used", 1)
				.containsEntry("remaining", 2);
		assertThat(feature(body, "SPEECH_TO_TEXT")).containsEntry("accessEnabled", false);
		assertThat(JsonPath.<Number>read(body, "$.creditBalance").longValue()).isZero();
		getOk("/api/v1/me/usage", g);
	}

	@Test
	void suspendedDeletedAndMergedAccountsAreRefused() throws Exception {
		User suspended = user("REGISTERED", "SUSPENDED", "Africa/Addis_Ababa");
		User deleting = user("REGISTERED", "DELETION_PENDING", "Africa/Addis_Ababa");
		for (String path : List.of("/api/v1/me/entitlements", "/api/v1/me/usage")) {
			mvc.perform(get(path).header("Authorization", suspended.bearer())).andExpect(status().isForbidden())
					.andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
			mvc.perform(get(path).header("Authorization", deleting.bearer())).andExpect(status().isForbidden())
					.andExpect(jsonPath("$.code").value("DELETION_PENDING"));
		}
		// a registered token of an account that no longer exists is stateless-valid but unknown to the resolver
		String ghost = "Bearer " + tokens.issue(UUID.randomUUID(), UUID.randomUUID(), null, "REGISTERED").value();
		mvc.perform(get("/api/v1/me/entitlements").header("Authorization", ghost)).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		// a merged guest loses its token at the security chain
		User merged = user("GUEST", "ACTIVE", "Africa/Addis_Ababa");
		jdbc.update("UPDATE users SET account_status = 'MERGED' WHERE id = ?", merged.id());
		mvc.perform(get("/api/v1/me/usage").header("Authorization", merged.bearer())).andExpect(status().isUnauthorized());
		// no token at all
		mvc.perform(get("/api/v1/me/entitlements")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/me/usage")).andExpect(status().isUnauthorized());
	}

	// ------------------------------------------------------------------ usage

	@Test
	void usageShowsTheCurrentWindowOfEveryMeteredFeatureWithoutCreatingRows() throws Exception {
		User u = user("REGISTERED", "ACTIVE", "Africa/Addis_Ababa");
		String body = getOk("/api/v1/me/usage", u);
		assertThat(counterRows(u.id())).isZero();
		List<String> codes = JsonPath.read(body, "$.items[*].featureCode");
		assertThat(codes).contains("TRANSLATE", "SPEECH_TO_TEXT", "TEXT_TO_SPEECH").doesNotContain("OFFLINE_TYPING",
				"HISTORY_SYNC", "ON_DEVICE_AI_MODELS");
		Map<String, Object> speech = usageItem(body, "SPEECH_TO_TEXT");
		assertThat(speech).containsEntry("usedUnits", 0).containsEntry("includedUnits", 600).containsEntry("unit", "SECOND");
		// FREE has no anchor: the calendar month in the user's zone
		ZoneId zone = ZoneId.of("Africa/Addis_Ababa");
		assertThat(Instant.parse((String) speech.get("periodStart")).atZone(zone).toLocalDate().getDayOfMonth()).isEqualTo(1);
		assertThat(Instant.parse((String) speech.get("periodEnd")).atZone(zone).toLocalDate().getDayOfMonth()).isEqualTo(1);
	}

	@Test
	void usageHistoryListsPastWindowsNewestFirstWithinThePeriod() throws Exception {
		User u = user("REGISTERED", "ACTIVE", "Africa/Addis_Ababa");
		Instant now = Instant.now();
		Reservation yesterday = withClock(now.minus(Duration.ofDays(1)), () -> {
			Reservation r = metering.reserve(u.id(), "TRANSLATE", 2, key());
			metering.settle(r.id(), 2);
			return r;
		});
		Reservation today = metering.reserve(u.id(), "TRANSLATE", 1, key());
		metering.settle(today.id(), 1);
		assertThat(yesterday.window()).isNotEqualTo(today.window());

		String body = getOk("/api/v1/me/usage?from=" + now.minus(Duration.ofDays(2)) + "&to=" + now.plus(Duration.ofDays(1)), u);
		List<Map<String, Object>> translate = JsonPath.read(body, "$.items[?(@.featureCode=='TRANSLATE')]");
		assertThat(translate).hasSize(2);
		assertThat(translate.get(0)).containsEntry("usedUnits", 1);
		assertThat(Instant.parse((String) translate.get(0).get("periodStart"))).isEqualTo(today.window().start());
		assertThat(translate.get(1)).containsEntry("usedUnits", 2).containsEntry("includedUnits", 10);
		assertThat(Instant.parse((String) translate.get(1).get("periodEnd"))).isEqualTo(yesterday.window().end());
		// a features with no row yet appears with its current (empty) window, still without creating it
		long rows = counterRows(u.id());
		assertThat(usageItem(body, "SPEECH_TO_TEXT")).containsEntry("usedUnits", 0);
		assertThat(counterRows(u.id())).isEqualTo(rows);

		// a period that only covers yesterday
		String past = getOk("/api/v1/me/usage?from=" + now.minus(Duration.ofDays(2)) + "&to=" + yesterday.window().end(), u);
		List<Map<String, Object>> only = JsonPath.read(past, "$.items[?(@.featureCode=='TRANSLATE')]");
		assertThat(only).hasSize(1);
		assertThat(only.get(0)).containsEntry("usedUnits", 2);
		// an empty or inverted period is an empty report
		assertThat(JsonPath.<List<?>>read(getOk("/api/v1/me/usage?from=" + now + "&to=" + now.minusSeconds(5), u), "$.items"))
				.isEmpty();
	}

	// ------------------------------------------------------------------ no locks

	@Test
	void readsDoNotWaitForTheRowLockTheEngineHolds() throws Exception {
		User u = user("REGISTERED", "ACTIVE", "Africa/Addis_Ababa");
		Reservation r = metering.reserve(u.id(), "TRANSLATE", 1, key());
		UUID counter = jdbc.queryForObject("SELECT usage_counter_id FROM usage_reservations WHERE id = ?", UUID.class, r.id());
		String[] bodies = new String[2];
		ExecutorService other = Executors.newSingleThreadExecutor();
		try {
			tx.executeWithoutResult(s -> {
				jdbc.queryForObject("SELECT id FROM usage_counters WHERE id = ? FOR UPDATE", UUID.class, counter);
				// the engine's row lock is held on this thread's connection; the reads run on another thread
				try {
					bodies[0] = other.submit(() -> getOk("/api/v1/me/entitlements", u)).get(5, TimeUnit.SECONDS);
					bodies[1] = other.submit(() -> getOk("/api/v1/me/usage", u)).get(5, TimeUnit.SECONDS);
				}
				catch (Exception e) {
					throw new IllegalStateException("a read waited for the counter lock", e);
				}
			});
		}
		finally {
			other.shutdownNow();
		}
		assertThat(feature(bodies[0], "TRANSLATE")).containsEntry("used", 0).containsEntry("remaining", 9);
		assertThat(usageItem(bodies[1], "TRANSLATE")).containsEntry("usedUnits", 0);
	}

	// ------------------------------------------------------------------ helpers

	record User(UUID id, String bearer) {
	}

	User user(String type, String status, String zone) {
		UUID id = jdbc.queryForObject("""
				INSERT INTO users (account_type, account_status, time_zone) VALUES (?, ?, ?) RETURNING id""", UUID.class,
				type, status, zone);
		return new User(id, "Bearer " + tokens.issue(id, UUID.randomUUID(), null, type).value());
	}

	UUID subscription(UUID user, String plan, Instant start, Instant end) {
		return jdbc.queryForObject("""
				INSERT INTO user_subscriptions (user_id, plan_id, source_type, billing_mode, subscription_status,
				    current_period_start, current_period_end)
				VALUES (?, (SELECT id FROM subscription_plans WHERE plan_code = ?), 'PAYMENT', 'MANUAL_RENEW', 'ACTIVE',
				        ?, ?) RETURNING id""", UUID.class, user, plan, Timestamp.from(start), Timestamp.from(end));
	}

	String getOk(String uri, User u) throws Exception {
		return mvc.perform(get(uri).header("Authorization", u.bearer())).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();
	}

	static Map<String, Object> feature(String body, String code) {
		List<Map<String, Object>> found = JsonPath.read(body, "$.features[?(@.featureCode=='" + code + "')]");
		assertThat(found).as(code).hasSize(1);
		return found.get(0);
	}

	static Map<String, Object> usageItem(String body, String code) {
		List<Map<String, Object>> found = JsonPath.read(body, "$.items[?(@.featureCode=='" + code + "')]");
		assertThat(found).as(code).isNotEmpty();
		return found.get(0);
	}

	long counterRows(UUID user) {
		return jdbc.queryForObject("SELECT count(*) FROM usage_counters WHERE user_id = ?", Long.class, user);
	}

	static <T> T withClock(Instant at, java.util.concurrent.Callable<T> body) throws Exception {
		NOW.set(at);
		try {
			return body.call();
		}
		finally {
			NOW.set(null);
		}
	}

	static String key() {
		return UUID.randomUUID().toString();
	}
}
