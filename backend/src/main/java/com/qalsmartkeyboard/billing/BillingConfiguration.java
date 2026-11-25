package com.qalsmartkeyboard.billing;

import java.time.Clock;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import com.qalsmartkeyboard.administration.AdminAccess;
import com.qalsmartkeyboard.administration.AuditTrail;
import com.qalsmartkeyboard.billing.spi.PaymentEnvironment;
import com.qalsmartkeyboard.billing.spi.PaymentProvider;
import com.qalsmartkeyboard.billing.spi.StoreBillingProvider;
import com.qalsmartkeyboard.platform.outbox.OutboxEvent;
import com.qalsmartkeyboard.platform.outbox.OutboxEventHandler;
import com.qalsmartkeyboard.platform.outbox.OutboxPublisher;
import com.qalsmartkeyboard.usage.Credits;

import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the billing core (ADR 0045): provider registry, routing, catalogue, orders, attempts, verification and
 * fulfilment, webhook intake, the checkout return URL, store purchases and notifications (ADR 0046), the outbox
 * consumers and the reconciliation task.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BillingProperties.class)
class BillingConfiguration {

	static final String RECONCILIATION_TASK = "billing-payment-reconciliation";
	private static final Logger log = LoggerFactory.getLogger(BillingConfiguration.class);

	@Bean
	ProviderRegistry billingProviderRegistry(ObjectProvider<PaymentProvider> gateways,
			ObjectProvider<StoreBillingProvider> stores, BillingProperties properties, Environment environment) {
		if (properties.environment() == PaymentEnvironment.SANDBOX
				&& environment.acceptsProfiles(Profiles.of("prod | production"))) {
			log.warn("qal.billing.environment is SANDBOX under a production profile: only sandbox merchant accounts "
					+ "can take payments");
		}
		return new ProviderRegistry(gateways.orderedStream().toList(), stores.orderedStream().toList());
	}

	@Bean
	ProviderRouting billingProviderRouting(JdbcTemplate jdbc, ProviderRegistry registry, BillingProperties properties,
			ObjectProvider<Clock> clock) {
		return new ProviderRouting(new JdbcRoutingData(jdbc), registry, properties.environment(),
				properties.minCountryConfidence(), clock(clock));
	}

	@Bean
	BillingCatalog billingCatalog(JdbcTemplate jdbc, ProviderRouting routing, BillingProperties properties,
			ObjectProvider<Clock> clock) {
		return new BillingCatalog(jdbc, routing, properties.environment(), clock(clock));
	}

	@Bean
	OrderService billingOrderService(JdbcTemplate jdbc, TransactionTemplate transactions, ProviderRouting routing,
			BillingCatalog catalog, BillingProperties properties, JsonMapper json, ObjectProvider<Clock> clock) {
		return new OrderService(jdbc, transactions, routing, catalog, properties, json, clock(clock));
	}

	@Bean
	AttemptService billingAttemptService(JdbcTemplate jdbc, TransactionTemplate transactions, OrderService orders,
			ProviderRouting routing, ProviderRegistry registry, BillingProperties properties, JsonMapper json,
			ObjectProvider<Clock> clock) {
		return new AttemptService(jdbc, transactions, orders, routing, registry, properties, json, clock(clock));
	}

	@Bean
	Fulfilment billingFulfilment(JdbcTemplate jdbc, Credits credits, JsonMapper json) {
		return new Fulfilment(jdbc, credits, json);
	}

	@Bean
	CatalogAdmin billingCatalogAdmin(JdbcTemplate jdbc, TransactionTemplate transactions, AdminAccess admin,
			AuditTrail audit, ObjectProvider<Clock> clock) {
		return new CatalogAdmin(jdbc, transactions, admin, audit, clock(clock));
	}

	@Bean
	MarketOfferingAdmin billingMarketOfferingAdmin(JdbcTemplate jdbc, TransactionTemplate transactions,
			AdminAccess admin, AuditTrail audit, ObjectProvider<Clock> clock) {
		return new MarketOfferingAdmin(jdbc, transactions, admin, audit, clock(clock));
	}

	@Bean
	PaymentVerifier billingPaymentVerifier(JdbcTemplate jdbc, TransactionTemplate transactions,
			ProviderRegistry registry, Fulfilment fulfilment, OutboxPublisher outbox, BillingProperties properties,
			JsonMapper json, ObjectProvider<Clock> clock) {
		return new PaymentVerifier(jdbc, transactions, registry, fulfilment, outbox, properties, json, clock(clock));
	}

	@Bean
	WebhookIntake billingWebhookIntake(JdbcTemplate jdbc, TransactionTemplate transactions, ProviderRegistry registry,
			OutboxPublisher outbox, PaymentVerifier verifier, JsonMapper json, ObjectProvider<Clock> clock) {
		return new WebhookIntake(jdbc, transactions, registry, outbox, verifier, json, clock(clock));
	}

	@Bean
	CheckoutReturn billingCheckoutReturn(JdbcTemplate jdbc, TransactionTemplate transactions, OutboxPublisher outbox,
			BillingProperties properties, ObjectProvider<Clock> clock) {
		return new CheckoutReturn(jdbc, transactions, outbox, properties, clock(clock));
	}

	@Bean
	SubscriptionQueries billingSubscriptionQueries(JdbcTemplate jdbc) {
		return new SubscriptionQueries(jdbc);
	}

	@Bean
	StoreConsumables billingStoreConsumables(JdbcTemplate jdbc, Credits credits, ProviderRouting routing,
			AuditTrail audit, JsonMapper json) {
		return new StoreConsumables(jdbc, credits, routing, audit, json);
	}

	@Bean
	StorePurchases billingStorePurchases(JdbcTemplate jdbc, TransactionTemplate transactions,
			ProviderRegistry registry, OutboxPublisher outbox, SubscriptionQueries subscriptions,
			BillingProperties properties, JsonMapper json, ObjectProvider<Clock> clock, StoreConsumables consumables) {
		return new StorePurchases(jdbc, transactions, registry, outbox, subscriptions, properties, json, clock(clock),
				consumables);
	}

	@Bean
	StoreNotificationIntake billingStoreNotificationIntake(JdbcTemplate jdbc, TransactionTemplate transactions,
			ProviderRegistry registry, OutboxPublisher outbox, StorePurchases purchases, JsonMapper json,
			ObjectProvider<Clock> clock) {
		return new StoreNotificationIntake(jdbc, transactions, registry, outbox, purchases, json, clock(clock));
	}

	/** Processes a stored store notification: re-read the customer from the store provider, then apply. */
	@Bean
	OutboxEventHandler billingStoreNotificationHandler(StoreNotificationIntake intake) {
		return new OutboxEventHandler() {
			@Override
			public String consumerName() {
				return "billing.sync-on-store-notification";
			}

			@Override
			public String eventType() {
				return BillingEvents.STORE_NOTIFICATION_RECEIVED;
			}

			@Override
			public void handle(OutboxEvent event) {
				intake.process(event);
			}
		};
	}

	@Bean
	ProviderRouteAdmin billingProviderRouteAdmin(JdbcTemplate jdbc, TransactionTemplate transactions, AdminAccess admin,
			AuditTrail audit, ProviderRegistry registry, BillingProperties properties, ObjectProvider<Clock> clock) {
		return new ProviderRouteAdmin(jdbc, transactions, admin, audit, registry, properties, clock(clock));
	}

	@Bean
	PaymentReconciler billingPaymentReconciler(JdbcTemplate jdbc, PaymentVerifier verifier,
			BillingProperties properties, ObjectProvider<Clock> clock) {
		return new PaymentReconciler(jdbc, verifier, properties, clock(clock));
	}

	/** Re-verifies stuck attempts on the platform's db-scheduler (one instance at a time). */
	@Bean
	@ConditionalOnProperty(prefix = "qal.billing.reconciliation", name = "enabled", havingValue = "true",
			matchIfMissing = true)
	RecurringTask<Void> billingReconciliationTask(PaymentReconciler reconciler, BillingProperties properties) {
		return Tasks.recurring(RECONCILIATION_TASK, FixedDelay.of(properties.reconciliation().interval()))
				.execute((instance, context) -> reconciler.runOnce());
	}

	/** Processes a stored gateway webhook: verify with the provider, then apply (never the body alone). */
	@Bean
	OutboxEventHandler billingWebhookHandler(WebhookIntake intake) {
		return new OutboxEventHandler() {
			@Override
			public String consumerName() {
				return "billing.verify-on-webhook";
			}

			@Override
			public String eventType() {
				return BillingEvents.WEBHOOK_RECEIVED;
			}

			@Override
			public void handle(OutboxEvent event) {
				intake.process(event);
			}
		};
	}

	/** Verifies an attempt after the payer came back through the checkout return URL. */
	@Bean
	OutboxEventHandler billingVerifyRequestedHandler(PaymentVerifier verifier, JsonMapper json) {
		return new OutboxEventHandler() {
			@Override
			public String consumerName() {
				return "billing.verify-on-return";
			}

			@Override
			public String eventType() {
				return BillingEvents.VERIFY_REQUESTED;
			}

			@Override
			public void handle(OutboxEvent event) {
				UUID attemptId = UUID.fromString(json.readTree(event.payloadJson()).path("attemptId").asString());
				PaymentVerifier.Outcome outcome = verifier.verify(attemptId, "RETURN");
				if (outcome == PaymentVerifier.Outcome.PROVIDER_UNAVAILABLE) {
					throw new IllegalStateException("provider verification unavailable");
				}
			}
		};
	}

	private static Clock clock(ObjectProvider<Clock> clock) {
		return clock.getIfAvailable(Clock::systemUTC);
	}
}
