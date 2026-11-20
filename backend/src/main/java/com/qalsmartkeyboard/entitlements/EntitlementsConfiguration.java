package com.qalsmartkeyboard.entitlements;

import java.time.Clock;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.qalsmartkeyboard.platform.outbox.OutboxEvent;
import com.qalsmartkeyboard.platform.outbox.OutboxEventHandler;

import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EntitlementProperties.class)
class EntitlementsConfiguration {

	/** Outbox event the billing module writes when a user's subscription sources change (ADR 0045). */
	static final String SUBSCRIPTION_CHANGED = "billing.subscription.changed";

	@Bean
	EntitlementResolver entitlementResolver(JdbcTemplate jdbc, EntitlementProperties properties,
			ObjectProvider<Clock> clock) {
		return new EntitlementResolver(new JdbcEntitlementData(jdbc), properties,
				clock.getIfAvailable(Clock::systemUTC));
	}

	/**
	 * Drops the cached resolution of a user whose subscriptions changed (a verified payment granted or extended a term),
	 * so {@code /me/entitlements} and the entitlement token show the new plan at once rather than after the cache TTL.
	 * The event carries ids only; the plan is read from the tables as always. The cache is per instance: other instances
	 * catch up within {@code qal.entitlements.cache-ttl}.
	 */
	@Bean
	OutboxEventHandler entitlementsSubscriptionChangedHandler(EntitlementResolver resolver, JsonMapper json) {
		return new OutboxEventHandler() {
			@Override
			public String consumerName() {
				return "entitlements.invalidate-on-subscription-change";
			}

			@Override
			public String eventType() {
				return SUBSCRIPTION_CHANGED;
			}

			@Override
			public void handle(OutboxEvent event) {
				String userId = json.readTree(event.payloadJson()).path("userId").asString();
				resolver.invalidate(UUID.fromString(userId));
			}
		};
	}
}
