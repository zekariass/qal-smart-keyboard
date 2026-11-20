package com.qalsmartkeyboard.billing.revenuecat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.qalsmartkeyboard.billing.spi.PaymentEnvironment;
import com.qalsmartkeyboard.billing.spi.PaymentProviderException;
import com.qalsmartkeyboard.billing.spi.StoreCustomer;
import com.qalsmartkeyboard.billing.spi.StoreNotification;
import com.qalsmartkeyboard.billing.spi.StoreNotificationRequest;
import com.qalsmartkeyboard.billing.spi.StoreSubscription;
import com.qalsmartkeyboard.billing.spi.WebhookAuthentication;

import tools.jackson.databind.json.JsonMapper;

/** ADR 0046: RevenueCat webhook authentication, event parsing and the v1 subscriber mapping (no network). */
class RevenueCatProviderTest {

	static final String AUTH = "Bearer rc-webhook-auth-for-tests-0123456789";
	static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
	/** The documented INITIAL_PURCHASE sample, with our user id and a Play product. */
	static final String EVENT = """
			{"event":{"event_timestamp_ms":1658726378679,"product_id":"qal_premium:monthly","period_type":"NORMAL",
			"purchased_at_ms":1658726374000,"expiration_at_ms":1659331174000,"environment":"SANDBOX",
			"entitlement_ids":["premium"],"transaction_id":"GPA.1","original_transaction_id":"GPA.1",
			"app_user_id":"0b6f4a52-2c1e-4c1a-9d1e-0c9c5e8d7a11","aliases":["$RCAnonymousID:8069238d"],
			"original_app_user_id":"$RCAnonymousID:87c6049c","currency":"USD","price":4.99,
			"subscriber_attributes":{"$email":{"updated_at_ms":1662955084635,"value":"firstlast@gmail.com"}},
			"store":"PLAY_STORE","type":"INITIAL_PURCHASE","id":"12345678-1234-1234-1234-123456789012",
			"app_id":"1234567890","experiments":[{"experiment_id":"prexp123"}]},"api_version":"1.0"}""";

	static RevenueCatProvider provider(String signingSecret) {
		return new RevenueCatProvider(new RevenueCatProperties("sk_test_never_logged", AUTH, signingSecret,
				Duration.ofMinutes(5), PaymentEnvironment.SANDBOX, URI.create("http://localhost:1"), null, null),
				Clock.fixed(NOW, ZoneOffset.UTC));
	}

	static StoreNotificationRequest request(String body, Map<String, List<String>> headers) {
		return new StoreNotificationRequest(body.getBytes(StandardCharsets.UTF_8), headers);
	}

	@Test
	void authenticatesWithTheConfiguredAuthorizationValueAndOptionalSignature() {
		RevenueCatProvider p = provider(null);
		assertThat(p.authenticateNotification(request(EVENT, Map.of("authorization", List.of(AUTH)))))
				.isEqualTo(WebhookAuthentication.VALID);
		assertThat(p.authenticateNotification(request(EVENT, Map.of("authorization", List.of(AUTH + "x")))))
				.isEqualTo(WebhookAuthentication.INVALID);
		assertThat(p.authenticateNotification(request(EVENT, Map.of()))).isEqualTo(WebhookAuthentication.INVALID);

		String secret = "rc-signing-secret";
		RevenueCatProvider signed = provider(secret);
		long t = NOW.getEpochSecond() - 30;
		String v1 = RevenueCatProvider.hmacHex(secret.getBytes(StandardCharsets.UTF_8),
				(t + "." + EVENT).getBytes(StandardCharsets.UTF_8));
		assertThat(signed.authenticateNotification(request(EVENT, Map.of("authorization", List.of(AUTH),
				"x-revenuecat-webhook-signature", List.of("t=" + t + ",v1=" + v1))))).isEqualTo(WebhookAuthentication.VALID);
		// auth header alone is not enough once signing is on; stale, tampered and forged signatures fail
		assertThat(signed.authenticateNotification(request(EVENT, Map.of("authorization", List.of(AUTH)))))
				.isEqualTo(WebhookAuthentication.INVALID);
		long old = NOW.getEpochSecond() - 600;
		String oldSig = RevenueCatProvider.hmacHex(secret.getBytes(StandardCharsets.UTF_8),
				(old + "." + EVENT).getBytes(StandardCharsets.UTF_8));
		assertThat(signed.authenticateNotification(request(EVENT, Map.of("authorization", List.of(AUTH),
				"x-revenuecat-webhook-signature", List.of("t=" + old + ",v1=" + oldSig)))))
				.isEqualTo(WebhookAuthentication.INVALID);
		assertThat(signed.authenticateNotification(request(EVENT.replace("4.99", "0.01"), Map.of("authorization",
				List.of(AUTH), "x-revenuecat-webhook-signature", List.of("t=" + t + ",v1=" + v1)))))
				.isEqualTo(WebhookAuthentication.INVALID);
	}

	@Test
	void parsesTheEventWithoutSubscriberAttributes() {
		StoreNotification n = provider(null).parseNotification(request(EVENT, Map.of()));
		assertThat(n.eventId()).isEqualTo("12345678-1234-1234-1234-123456789012");
		assertThat(n.eventType()).isEqualTo("INITIAL_PURCHASE");
		assertThat(n.userReferences()).containsExactly("0b6f4a52-2c1e-4c1a-9d1e-0c9c5e8d7a11",
				"$RCAnonymousID:87c6049c", "$RCAnonymousID:8069238d");
		assertThat(n.productId()).isEqualTo("qal_premium:monthly");
		assertThat(n.environment()).isEqualTo(PaymentEnvironment.SANDBOX);
		assertThat(n.eventTime()).isEqualTo(Instant.ofEpochMilli(1658726378679L));
		assertThat(n.test()).isFalse();
		assertThat(n.redactedEvent().toString()).doesNotContain("firstlast@gmail.com", "subscriber_attributes",
				"experiments");
		assertThat(n.redactedEvent()).containsEntry("type", "INITIAL_PURCHASE");
		assertThat(catchThrowableOfType(PaymentProviderException.class,
				() -> provider(null).parseNotification(request("{\"event\":{}}", Map.of()))).safeCode())
				.isEqualTo("WEBHOOK_NO_ID");
	}

	@Test
	void mapsTheV1SubscriberLeniently() {
		String body = """
				{"request_date_ms":1564162810884,"subscriber":{"management_url":"https://play.google.com/store/account/subscriptions",
				"original_app_user_id":"u","entitlements":{},"subscriptions":{
				"qal_premium":{"auto_resume_date":null,"billing_issues_detected_at":null,"expires_date":"2026-12-20T10:00:00Z",
				"grace_period_expires_date":null,"is_sandbox":true,"original_purchase_date":"2026-10-20T10:00:00Z",
				"ownership_type":"PURCHASED","period_type":"normal","purchase_date":"2026-11-20T10:00:00Z","refunded_at":null,
				"store":"play_store","store_transaction_id":"GPA.6801","unsubscribe_detected_at":null,
				"product_plan_identifier":"monthly"},
				"onemonth":{"expires_date":"2019-06-17T22:47:55Z","is_sandbox":false,"purchase_date":"2019-06-17T22:42:55Z",
				"store":"app_store","store_transaction_id":1000000652379790,"refunded_at":"2019-06-17T23:00:00Z"}}}}""";
		StoreCustomer c = RevenueCatProvider.mapSubscriber("u", JsonMapper.builder().build().readTree(body));
		assertThat(c.managementUrl()).isEqualTo("https://play.google.com/store/account/subscriptions");
		StoreSubscription play = c.subscriptions().get(0);
		assertThat(play.productId()).isEqualTo("qal_premium");
		assertThat(play.basePlanId()).isEqualTo("monthly");
		assertThat(play.environment()).isEqualTo(PaymentEnvironment.SANDBOX);
		assertThat(play.expiresAt()).isEqualTo(Instant.parse("2026-12-20T10:00:00Z"));
		assertThat(play.store()).isEqualTo("play_store");
		StoreSubscription apple = c.subscriptions().get(1);
		assertThat(apple.storeTransactionId()).isEqualTo("1000000652379790");
		assertThat(apple.refundedAt()).isNotNull();
		assertThat(apple.environment()).isEqualTo(PaymentEnvironment.PRODUCTION);
		assertThat(provider(null).toString()).doesNotContain("sk_test_never_logged", AUTH);
	}
}
