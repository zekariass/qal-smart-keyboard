package com.qalsmartkeyboard.billing;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.qalsmartkeyboard.billing.spi.PaymentEnvironment;
import com.qalsmartkeyboard.platform.geo.CountryResolution.Confidence;

/**
 * {@code qal.billing.*} (ADR 0045).
 *
 * @param environment          which merchant accounts checkout uses ({@code payment_provider_accounts.environment})
 * @param publicBaseUrl        the API's public origin (env {@code QAL_PUBLIC_BASE_URL}); notify and return URLs handed
 *                             to providers are built from it. Required for hosted checkout (a checkout cannot start
 *                             without it: {@code PAYMENT_METHOD_UNAVAILABLE})
 * @param appReturnUri         where {@code /billing/return/{attemptId}} redirects (the app intercepts it); also the
 *                             allow-list for {@code CreateAttemptRequest.returnUrl}
 * @param orderTtl             how long an unpaid order can take attempts
 * @param attemptTtl           how long a hosted checkout stays open
 * @param minCountryConfidence the least {@code CountryResolution} confidence that unlocks a country's own provider
 *                             routes; below it the global ({@code ZZ}) routes apply. MEDIUM = a trusted IP country is
 *                             needed (client hints alone never unlock a country)
 * @param reconciliation       the stuck-attempt re-verification job
 * @param storeRenewalPending  how long after its period end a store subscription that is still set to renew stays
 *                             ACTIVE while the store has not reported the renewal yet (RevenueCat's Play renewal gap;
 *                             ADR 0046)
 * @param storeVerificationProvider the store-billing adapter that verifies GOOGLE_PLAY / APPLE_APP_STORE purchase reports
 *                             while no direct store adapter exists (ADR 0046)
 */
@ConfigurationProperties("qal.billing")
record BillingProperties(@DefaultValue("SANDBOX") PaymentEnvironment environment, URI publicBaseUrl,
		@DefaultValue("qalkeyboard://billing/return") URI appReturnUri, @DefaultValue("30m") Duration orderTtl,
		@DefaultValue("30m") Duration attemptTtl, @DefaultValue("MEDIUM") Confidence minCountryConfidence,
		@DefaultValue Reconciliation reconciliation, @DefaultValue("6h") Duration storeRenewalPending,
		@DefaultValue("REVENUECAT") String storeVerificationProvider) {

	/**
	 * @param enabled        run the db-scheduler task
	 * @param interval       delay between runs
	 * @param stuckAfter     an open attempt older than this is re-verified with the provider
	 * @param recheckEvery   minimum delay between two checks of the same attempt
	 * @param giveUpAfter    attempts older than this are left to the provider-vs-ledger reconciliation (step A2)
	 * @param checkoutGrace  a never-started checkout is EXPIRED this long after its expiry
	 * @param batchSize      attempts per run
	 */
	record Reconciliation(@DefaultValue("true") boolean enabled, @DefaultValue("2m") Duration interval,
			@DefaultValue("10m") Duration stuckAfter, @DefaultValue("5m") Duration recheckEvery,
			@DefaultValue("48h") Duration giveUpAfter, @DefaultValue("10m") Duration checkoutGrace,
			@DefaultValue("50") int batchSize) {
	}

	BillingProperties {
		if (publicBaseUrl != null && publicBaseUrl.toString().isBlank()) {
			publicBaseUrl = null;
		}
		if (orderTtl.isNegative() || orderTtl.isZero() || attemptTtl.isNegative() || attemptTtl.isZero()) {
			throw new IllegalArgumentException("qal.billing.order-ttl and attempt-ttl must be positive");
		}
		if (minCountryConfidence == Confidence.NONE) {
			throw new IllegalArgumentException("qal.billing.min-country-confidence must be LOW, MEDIUM or HIGH");
		}
		if (storeRenewalPending == null || storeRenewalPending.isNegative()) {
			storeRenewalPending = Duration.ofHours(6);
		}
		if (reconciliation.batchSize() < 1) {
			throw new IllegalArgumentException("qal.billing.reconciliation.batch-size must be positive");
		}
	}
}
