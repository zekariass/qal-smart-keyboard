package com.qalsmartkeyboard.billing;

import java.util.EnumSet;
import java.util.Set;

import com.qalsmartkeyboard.billing.spi.ProviderPaymentStatus;

/**
 * The attempt and order state machines (ADR 0045 "State machines"). Payment states only move forward, with one
 * deliberate exception: a closed attempt or order (FAILED, CANCELLED, EXPIRED) can still become SUCCEEDED / PAID when
 * the provider later verifies a capture, because money that was really taken is never ignored (the payer either gets
 * the term or, for a second capture on a paid order, a refund in step A2). SUCCEEDED and PAID are terminal here
 * (refunds are new ledger rows, step A2).
 */
final class PaymentStates {

	private PaymentStates() {
	}

	enum AttemptStatus {
		INITIATED, PENDING, REQUIRES_ACTION, SUCCEEDED, FAILED, CANCELLED, EXPIRED, UNKNOWN;

		private static final Set<AttemptStatus> OPEN = EnumSet.of(INITIATED, PENDING, REQUIRES_ACTION, UNKNOWN);

		/** Still waiting for a provider outcome (re-verified by the reconciliation job). */
		boolean isOpen() {
			return OPEN.contains(this);
		}

		boolean canMoveTo(AttemptStatus next) {
			if (next == this) {
				return false;
			}
			return switch (this) {
				case SUCCEEDED -> false;
				case FAILED, CANCELLED, EXPIRED -> next == SUCCEEDED;
				case INITIATED, PENDING, REQUIRES_ACTION, UNKNOWN -> next != INITIATED;
			};
		}

		/** The attempt status a verified provider outcome leads to, or null for "no change". */
		static AttemptStatus fromProvider(ProviderPaymentStatus status) {
			return switch (status) {
				case SUCCEEDED -> SUCCEEDED;
				case PENDING -> PENDING;
				case FAILED -> FAILED;
				case CANCELLED -> CANCELLED;
				case EXPIRED -> EXPIRED;
				case NOT_STARTED, UNKNOWN -> null;
			};
		}
	}

	enum OrderStatus {
		CREATED, AWAITING_PAYMENT, PAID, FAILED, CANCELLED, EXPIRED, PARTIALLY_REFUNDED, REFUNDED;

		/** New attempts may start. */
		boolean isPayable() {
			return this == CREATED || this == AWAITING_PAYMENT;
		}

		boolean canMoveTo(OrderStatus next) {
			if (next == this) {
				return false;
			}
			return switch (this) {
				case CREATED -> next != CREATED;
				case AWAITING_PAYMENT -> next == PAID || next == FAILED || next == CANCELLED || next == EXPIRED;
				case FAILED, CANCELLED, EXPIRED -> next == PAID;
				case PAID -> next == PARTIALLY_REFUNDED || next == REFUNDED;
				case PARTIALLY_REFUNDED -> next == REFUNDED;
				case REFUNDED -> false;
			};
		}
	}
}
