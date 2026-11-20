package com.qalsmartkeyboard.billing;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.qalsmartkeyboard.billing.BillingDtos.SubscriptionDto;
import com.qalsmartkeyboard.billing.BillingDtos.SubscriptionListDto;

/**
 * {@code GET /me/subscriptions}: the caller's subscriptions, newest first, keyset-paged on {@code (created_at, id)}.
 * {@code renewable} stays false until the manual renewal operation exists (M4.3); buying the same plan again stacks a
 * term (SD 15.5).
 */
final class SubscriptionQueries {

	static final int DEFAULT_LIMIT = 20;
	static final int MAX_LIMIT = 100;

	private final JdbcTemplate jdbc;

	SubscriptionQueries(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	SubscriptionListDto list(UUID userId, String cursor, Integer limitParam) {
		int limit = limitParam == null ? DEFAULT_LIMIT : limitParam;
		if (limit < 1 || limit > MAX_LIMIT) {
			throw BillingErrors.invalid("limit", "OUT_OF_RANGE", "limit must be between 1 and 100.");
		}
		Instant afterCreated = null;
		UUID afterId = null;
		if (cursor != null) {
			try {
				String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|");
				afterCreated = Instant.parse(parts[0]);
				afterId = UUID.fromString(parts[1]);
			}
			catch (RuntimeException e) {
				throw BillingErrors.invalid("cursor", "INVALID", "The cursor is not valid.");
			}
		}
		List<Object> args = new ArrayList<>();
		args.add(userId);
		String keyset = "";
		if (afterCreated != null) {
			keyset = " AND (s.created_at, s.id) < (?, ?)";
			args.add(Timestamp.from(afterCreated));
			args.add(afterId);
		}
		args.add(limit + 1);
		List<Object[]> rows = jdbc.query("""
				SELECT s.id, p.plan_code, s.subscription_status, s.source_type, s.billing_mode, pp.provider_code,
				       m.method_code, s.payment_mandate_id, s.current_period_start, s.current_period_end,
				       s.auto_renew_enabled, s.cancel_at_period_end, s.next_billing_at, s.created_at
				FROM user_subscriptions s
				JOIN subscription_plans p ON p.id = s.plan_id
				LEFT JOIN payment_providers pp ON pp.id = s.provider_id
				LEFT JOIN provider_payment_methods m ON m.id = s.provider_payment_method_id
				WHERE s.user_id = ?""" + keyset + " ORDER BY s.created_at DESC, s.id DESC LIMIT ?",
				(rs, i) -> new Object[] { new SubscriptionDto(rs.getObject(1, UUID.class), rs.getString(2),
						rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
						rs.getObject(8, UUID.class), JdbcRoutingData.instant(rs, 9), JdbcRoutingData.instant(rs, 10),
						rs.getBoolean(11), rs.getBoolean(12), JdbcRoutingData.instant(rs, 13), false, null),
						JdbcRoutingData.instant(rs, 14) },
				args.toArray());
		List<SubscriptionDto> items = new ArrayList<>();
		String next = null;
		for (int i = 0; i < rows.size() && i < limit; i++) {
			items.add((SubscriptionDto) rows.get(i)[0]);
		}
		if (rows.size() > limit) {
			Object[] last = rows.get(limit - 1);
			String raw = ((Instant) last[1]).toString() + "|" + ((SubscriptionDto) last[0]).subscriptionId();
			next = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
		}
		return new SubscriptionListDto(items, next);
	}
}
