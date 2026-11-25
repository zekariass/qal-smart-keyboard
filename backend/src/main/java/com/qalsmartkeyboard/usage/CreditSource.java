package com.qalsmartkeyboard.usage;

/**
 * Why a credit lot exists (V1 {@code ai_credit_lots.lot_source}). The ledger row that opens the lot is of the matching
 * type ({@code PROMOTION} is written as {@code GRANT}).
 */
public enum CreditSource {
	/**
	 * Bought credit pack. Through {@link Credits#grant} {@code sourceRef} is the {@code payment_orders.id}; a store-billed
	 * pack uses {@link Credits#grantStorePurchase} (V32: a PURCHASE lot is backed by an order or a store purchase).
	 */
	PURCHASE,
	/** Granted by the operator or the product (welcome credit, support). */
	GRANT,
	/** Marketing promotion. */
	PROMOTION,
	/** Credit given back for a failed or refunded service (not a payment refund of a purchase). */
	REFUND,
	/** Manual correction that adds credit. */
	ADJUSTMENT
}
