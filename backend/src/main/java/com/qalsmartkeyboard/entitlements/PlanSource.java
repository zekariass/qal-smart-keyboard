package com.qalsmartkeyboard.entitlements;

/**
 * Where the effective plan comes from. The first four are the sources SD 15.5 / 13 / 16 know about; {@link #DEFAULT} is
 * the fallback plan (FREE for registered users, GUEST for guests) and never has a row of its own.
 *
 * <p>All paid or granted sources are rows of {@code user_subscriptions} (store purchases and partner grants converge
 * there, SD 16.1 and 16.7); the resolver tells them apart by {@code source_type}. Organisation seats come from
 * {@code organization_seat_assignments}. The declaration order is the tie-break order (see {@link EntitlementResolver}).
 */
public enum PlanSource {
	/** {@code user_subscriptions} with source_type PAYMENT, PROMOTION or ADMIN_GRANT. */
	SUBSCRIPTION,
	/** {@code user_subscriptions} with source_type STORE (backed by a {@code store_purchases} row). */
	STORE_PURCHASE,
	/** An active {@code organization_seat_assignments} row on an active organisation subscription. */
	ORGANIZATION_SEAT,
	/** {@code user_subscriptions} with source_type PARTNER. */
	PARTNER,
	/** No valid source: FREE (registered) or GUEST (guest). */
	DEFAULT;

	/**
	 * The value for {@code Entitlements.plan.source} in the contract, which only knows DEFAULT, SUBSCRIPTION and
	 * ORGANIZATION_SEAT (contract gap: STORE_PURCHASE and PARTNER are reported as SUBSCRIPTION).
	 */
	public String contractValue() {
		return switch (this) {
			case DEFAULT -> "DEFAULT";
			case ORGANIZATION_SEAT -> "ORGANIZATION_SEAT";
			case SUBSCRIPTION, STORE_PURCHASE, PARTNER -> "SUBSCRIPTION";
		};
	}
}
