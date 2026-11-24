package com.qalsmartkeyboard.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.ApiFieldError;

/**
 * {@code getCredits} and {@code listCreditTransactions} (SD 15.4, 15.6; plan T8.10): the caller's own credit account,
 * read-only. Lots are the ones that can still be spent (remaining credits, not expired), in the order they are spent
 * (soonest expiry first, the {@link JdbcCredits} FIFO). The ledger pages newest first with a keyset cursor. Nothing
 * here looks at the caller's country: credits work everywhere.
 */
final class CreditReadService {

	static final int DEFAULT_LIMIT = 50;
	static final int MAX_LIMIT = 100;
	private static final int SCALE = 6;

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record CreditSummaryDto(BigDecimal balanceUnits, Instant nextExpiryAt, List<CreditLotDto> lots) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record CreditLotDto(UUID lotId, BigDecimal originalUnits, BigDecimal remainingUnits, String source,
			Instant expiresAt) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record CreditTransactionDto(UUID id, String transactionType, BigDecimal deltaUnits, BigDecimal balanceAfter,
			String featureCode, UUID lotId, UUID aiRequestId, UUID paymentOrderId, String description,
			Instant createdAt) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record CreditTransactionPageDto(List<CreditTransactionDto> items, String nextCursor) {
	}

	private final JdbcClient jdbc;
	private final Credits credits;
	private final Clock clock;

	CreditReadService(JdbcClient jdbc, Credits credits, Clock clock) {
		this.jdbc = jdbc;
		this.credits = credits;
		this.clock = clock;
	}

	CreditSummaryDto summary(UUID userId) {
		Instant now = clock.instant();
		List<CreditLotDto> lots = jdbc.sql("""
				SELECT l.id, l.initial_units, l.remaining_units, l.lot_source, l.expires_at
				FROM ai_credit_lots l JOIN ai_credit_accounts a ON a.id = l.credit_account_id
				WHERE a.user_id = :user AND a.currency_unit = 'AI_CREDIT' AND l.remaining_units > 0
				  AND (l.expires_at IS NULL OR l.expires_at > :now)
				ORDER BY l.expires_at NULLS LAST, l.created_at, l.id
				""").param("user", userId).param("now", Timestamp.from(now))
				.query((rs, n) -> new CreditLotDto(rs.getObject(1, UUID.class), plain(rs.getBigDecimal(2)),
						plain(rs.getBigDecimal(3)), rs.getString(4), instant(rs, 5)))
				.list();
		Instant nextExpiry = lots.stream().map(CreditLotDto::expiresAt).filter(Objects::nonNull).min(Instant::compareTo)
				.orElse(null);
		return new CreditSummaryDto(plain(credits.balance(userId, now).exact()), nextExpiry, lots);
	}

	CreditTransactionPageDto transactions(UUID userId, String cursor, Integer limitParam) {
		int limit = limitParam == null ? DEFAULT_LIMIT : limitParam;
		if (limit < 1 || limit > MAX_LIMIT) {
			throw invalid("limit", "OUT_OF_RANGE", "limit must be between 1 and 100.");
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
				throw invalid("cursor", "INVALID", "The cursor is not valid.");
			}
		}
		String keyset = afterCreated == null ? "" : " AND (t.created_at, t.id) < (:afterCreated, :afterId)";
		JdbcClient.StatementSpec sql = jdbc.sql("""
				SELECT t.id, t.transaction_type, t.delta_units, t.balance_after, r.feature_code, t.lot_id,
				       t.ai_request_id, t.payment_order_id, t.description, t.created_at
				FROM ai_credit_transactions t
				JOIN ai_credit_accounts a ON a.id = t.credit_account_id
				LEFT JOIN usage_reservations r ON r.id = t.ai_request_id AND r.user_id = a.user_id
				WHERE a.user_id = :user AND a.currency_unit = 'AI_CREDIT'""" + keyset + """

				ORDER BY t.created_at DESC, t.id DESC
				LIMIT :limit""").param("user", userId).param("limit", limit + 1);
		if (afterCreated != null) {
			sql = sql.param("afterCreated", Timestamp.from(afterCreated)).param("afterId", afterId);
		}
		List<CreditTransactionDto> rows = sql.query(CreditReadService::transaction).list();
		List<CreditTransactionDto> items = new ArrayList<>(rows.subList(0, Math.min(limit, rows.size())));
		String next = null;
		if (rows.size() > limit) {
			CreditTransactionDto last = items.get(items.size() - 1);
			String raw = last.createdAt() + "|" + last.id();
			next = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
		}
		return new CreditTransactionPageDto(items, next);
	}

	private static CreditTransactionDto transaction(ResultSet rs, int n) throws SQLException {
		return new CreditTransactionDto(rs.getObject(1, UUID.class), rs.getString(2), plain(rs.getBigDecimal(3)),
				plain(rs.getBigDecimal(4)), rs.getString(5), rs.getObject(6, UUID.class), rs.getObject(7, UUID.class),
				rs.getObject(8, UUID.class), rs.getString(9), instant(rs, 10));
	}

	/** At most 6 decimals, no trailing zeros and never exponent notation (500.000000 is written as 500). */
	static BigDecimal plain(BigDecimal v) {
		BigDecimal s = v.setScale(SCALE, RoundingMode.DOWN).stripTrailingZeros();
		return s.scale() < 0 ? s.setScale(0) : s;
	}

	private static Instant instant(ResultSet rs, int column) throws SQLException {
		Timestamp t = rs.getTimestamp(column);
		return t == null ? null : t.toInstant();
	}

	private static ApiException invalid(String field, String code, String message) {
		return new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, null,
				List.of(new ApiFieldError(field, code, message)), java.util.Map.of());
	}
}
