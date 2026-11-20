package com.qalsmartkeyboard.billing;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The reconciliation hook (ADR 0045; the provider-vs-ledger report is step A2): a db-scheduler task that re-verifies
 * open attempts (INITIATED, REQUIRES_ACTION, PENDING, UNKNOWN) older than {@code stuck-after} with their provider, at
 * most every {@code recheck-every} per attempt and up to {@code give-up-after} after creation, so a missed webhook or an
 * abandoned return never leaves a paid order unfulfilled. It then expires unpaid orders past their expiry that have no
 * open attempt. Verification is the same {@link PaymentVerifier} path as webhooks, so it is idempotent.
 */
final class PaymentReconciler {

	private static final Logger log = LoggerFactory.getLogger(PaymentReconciler.class);

	record RunResult(int checked, int paid, int closed, int unavailable, int expiredOrders) {
	}

	private final JdbcTemplate jdbc;
	private final PaymentVerifier verifier;
	private final BillingProperties.Reconciliation settings;
	private final Clock clock;

	PaymentReconciler(JdbcTemplate jdbc, PaymentVerifier verifier, BillingProperties properties, Clock clock) {
		this.jdbc = jdbc;
		this.verifier = verifier;
		this.settings = properties.reconciliation();
		this.clock = clock;
	}

	RunResult runOnce() {
		Instant now = clock.instant();
		List<UUID> due = jdbc.query("""
				SELECT id FROM payment_attempts
				WHERE attempt_status IN ('INITIATED', 'REQUIRES_ACTION', 'PENDING', 'UNKNOWN')
				  AND created_at < ? AND created_at > ?
				  AND (metadata->>'lastCheckedAt' IS NULL OR CAST(metadata->>'lastCheckedAt' AS TIMESTAMPTZ) < ?)
				ORDER BY created_at
				LIMIT ?""", (rs, i) -> rs.getObject(1, UUID.class), OrderService.utc(now.minus(settings.stuckAfter())),
				OrderService.utc(now.minus(settings.giveUpAfter())), OrderService.utc(now.minus(settings.recheckEvery())),
				settings.batchSize());
		int paid = 0;
		int closed = 0;
		int unavailable = 0;
		for (UUID id : due) {
			try {
				switch (verifier.verify(id, "RECONCILIATION")) {
					case PAID -> paid++;
					case CLOSED -> closed++;
					case PROVIDER_UNAVAILABLE, NO_ADAPTER -> unavailable++;
					default -> {
					}
				}
			}
			catch (RuntimeException e) {
				unavailable++;
				log.warn("billing reconciliation failed for attemptId={} ({})", id, e.getClass().getSimpleName());
			}
		}
		int expired = jdbc.update("""
				UPDATE payment_orders o SET order_status = 'EXPIRED', updated_at = ?
				WHERE o.order_status IN ('CREATED', 'AWAITING_PAYMENT') AND o.expires_at < ?
				  AND NOT EXISTS (SELECT 1 FROM payment_attempts a WHERE a.payment_order_id = o.id
				                  AND a.attempt_status IN ('INITIATED', 'REQUIRES_ACTION', 'PENDING', 'UNKNOWN'))""",
				OrderService.utc(now), OrderService.utc(now));
		RunResult result = new RunResult(due.size(), paid, closed, unavailable, expired);
		if (!due.isEmpty() || expired > 0) {
			log.info("billing reconciliation checked={} paid={} closed={} unavailable={} expiredOrders={}",
					result.checked(), result.paid(), result.closed(), result.unavailable(), result.expiredOrders());
		}
		return result;
	}
}
