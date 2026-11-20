package com.qalsmartkeyboard.billing;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.billing.PaymentStates.AttemptStatus;
import com.qalsmartkeyboard.platform.outbox.OutboxPublisher;

/**
 * {@code GET /billing/return/{attemptId}} (ADR 0045 "Return URL"): the provider's success/cancel/error URL. It never
 * changes payment state. For an open attempt it queues one verification (outbox, at most once per
 * {@link #VERIFY_SPACING} per attempt so the unauthenticated URL cannot be used to hammer the provider), then the
 * caller redirects to the app's allow-listed return URI. The {@code result} query value is UX only.
 */
final class CheckoutReturn {

	static final Duration VERIFY_SPACING = Duration.ofSeconds(30);

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;
	private final OutboxPublisher outbox;
	private final BillingProperties properties;
	private final Clock clock;

	CheckoutReturn(JdbcTemplate jdbc, TransactionTemplate transactions, OutboxPublisher outbox,
			BillingProperties properties, Clock clock) {
		this.jdbc = jdbc;
		this.transactions = transactions;
		this.outbox = outbox;
		this.properties = properties;
		this.clock = clock;
	}

	/** @return the app URI to redirect to; 404 for an unknown attempt */
	URI handle(UUID attemptId, String result) {
		String safeResult = result == null ? "unknown" : switch (result) {
			case "success", "cancel", "error" -> result;
			default -> "unknown";
		};
		UUID orderId = transactions.execute(s -> {
			Map<String, Object> row = jdbc.query("""
					SELECT payment_order_id, attempt_status, metadata->>'verifyRequestedAt'
					FROM payment_attempts WHERE id = ? FOR UPDATE""", (rs, i) -> {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("order", rs.getObject(1, UUID.class));
				m.put("status", rs.getString(2));
				m.put("requested", rs.getString(3));
				return m;
			}, attemptId).stream().findFirst().orElseThrow(BillingErrors::notFound);
			Instant now = clock.instant();
			String requested = (String) row.get("requested");
			boolean recently = requested != null && Instant.parse(requested).plus(VERIFY_SPACING).isAfter(now);
			if (AttemptStatus.valueOf((String) row.get("status")).isOpen() && !recently) {
				jdbc.update("""
						UPDATE payment_attempts
						   SET metadata = metadata || jsonb_build_object('verifyRequestedAt', CAST(? AS TEXT)), updated_at = ?
						 WHERE id = ?""", now.toString(), OrderService.utc(now), attemptId);
				outbox.publish("payment_attempt", attemptId.toString(), BillingEvents.VERIFY_REQUESTED,
						Map.of("attemptId", attemptId.toString(), "trigger", "RETURN"));
			}
			return (UUID) row.get("order");
		});
		String base = properties.appReturnUri().toString();
		String sep = base.contains("?") ? "&" : "?";
		return URI.create(base + sep + "orderId=" + orderId + "&attemptId=" + attemptId + "&result="
				+ URLEncoder.encode(safeResult, StandardCharsets.UTF_8));
	}
}
