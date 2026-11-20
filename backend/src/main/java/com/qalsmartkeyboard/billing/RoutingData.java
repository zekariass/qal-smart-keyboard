package com.qalsmartkeyboard.billing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.qalsmartkeyboard.billing.spi.PaymentEnvironment;

/** What {@link ProviderRouting} reads: routes, market policies and eligible merchant-account methods. */
interface RoutingData {

	/** A {@code billing_provider_routes} row with its provider's switches. */
	record Route(String providerCode, UUID providerId, String providerType, String providerStatus,
			boolean providerActive, int priority, boolean enabled) {
	}

	/** A {@code billing_market_channel_policies} row. */
	record Policy(String purchaseFlow, String approvalStatus, boolean purchaseUiAllowed, boolean externalPaymentAllowed,
			Instant effectiveFrom, Instant effectiveUntil) {
	}

	/**
	 * An account method that passes SD 16.3 rules 3-5 except the amount range: active merchant account of the
	 * country and environment, enabled + AVAILABLE base method and account method.
	 */
	record Method(UUID accountId, String accountCode, String currencyCode, String merchantAccountReference,
			UUID baseMethodId, UUID accountMethodId, String methodCode, String providerMethodCode, String displayName,
			String methodType, boolean requiresRedirect, boolean supportsAutoRenew, Long minAmountMinor,
			Long maxAmountMinor) {
	}

	/** Routes of one scope in priority order (disabled ones included). */
	List<Route> routes(String countryCode, String platform, String distribution);

	Optional<Policy> policy(String countryCode, String platform, String distribution);

	/** Eligible methods of {@code providerId} for {@code countryCode}/{@code environment}, optionally one currency. */
	List<Method> methods(UUID providerId, String countryCode, PaymentEnvironment environment, String currencyCode);
}
