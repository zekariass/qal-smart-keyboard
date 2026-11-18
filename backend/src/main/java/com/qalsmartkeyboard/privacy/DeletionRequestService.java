package com.qalsmartkeyboard.privacy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.entitlements.EntitlementResolver;
import com.qalsmartkeyboard.identity.AccountDeletion;
import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.outbox.OutboxPublisher;
import com.qalsmartkeyboard.privacy.DeletionDtos.DeletionRequestDto;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * {@code requestAccountDeletion}, {@code getAccountDeletion} and {@code cancelAccountDeletion} (SD 19.4, plan B6.2,
 * ADR 0015). A request makes the account {@code DELETION_PENDING} for the grace period
 * ({@code qal.privacy.deletion.grace-period}, 14 days) and records a {@code SCHEDULED} request; the
 * {@link DeletionExecutor} carries it out when it is due. Until the executor has started, the request can be cancelled
 * and the account is {@code ACTIVE} again.
 *
 * <p>Lock order (shared with the executor): the request row, then the {@code users} row ({@link AccountDeletion}).
 * A new request locks only the {@code users} row before inserting; the partial unique index
 * {@code ux_user_one_open_deletion_request} is the last line against a second open request.
 */
class DeletionRequestService {

	private static final Logger log = LoggerFactory.getLogger(DeletionRequestService.class);

	static final String REQUESTED_EVENT = "privacy.account.deletion_requested";
	static final String CANCELLED_EVENT = "privacy.account.deletion_cancelled";
	static final String CANCELLED_BY_USER = "USER_CANCELLED";

	private final JdbcClient jdbc;
	private final TransactionTemplate tx;
	private final AccountDeletion accounts;
	private final EntitlementResolver entitlements;
	private final OutboxPublisher outbox;
	private final DeletionProperties properties;
	private final Clock clock;
	private final MeterRegistry meters;

	DeletionRequestService(JdbcClient jdbc, TransactionTemplate tx, AccountDeletion accounts,
			EntitlementResolver entitlements, OutboxPublisher outbox, DeletionProperties properties, Clock clock,
			MeterRegistry meters) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.accounts = accounts;
		this.entitlements = entitlements;
		this.outbox = outbox;
		this.properties = properties;
		this.clock = clock;
		this.meters = meters;
	}

	/**
	 * Schedules the deletion of the caller's account. The caller's session stays valid (to see and cancel the request);
	 * every other session family ends; AI features stop at once (a {@code DELETION_PENDING} account holds no plan, ADR
	 * 0007 D22) while typing and the other account features keep working.
	 *
	 * @throws ApiException 409 {@code DELETION_PENDING} when a deletion is already pending; 403
	 *                      {@code ACCOUNT_SUSPENDED} for a suspended account; 403 {@code FORBIDDEN} otherwise
	 */
	DeletionRequestDto request(AuthenticatedUser caller) {
		UUID userId = caller.userId();
		Instant now = clock.instant();
		DeletionRequestDto created = tx.execute(status -> {
			switch (accounts.requestDeletion(userId, caller.sessionId(), now)) {
				case STARTED -> {
				}
				case ALREADY_PENDING -> throw new ApiException(ApiErrorCode.DELETION_PENDING, HttpStatus.CONFLICT,
						"A deletion of this account is already pending; cancel it or wait for it.");
				case SUSPENDED -> throw new ApiException(ApiErrorCode.ACCOUNT_SUSPENDED, HttpStatus.FORBIDDEN,
						"A suspended account is deleted through support.");
				case NOT_ALLOWED -> throw new ApiException(ApiErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, null);
			}
			UUID id = UUID.randomUUID();
			Instant purgeAt = now.plus(properties.gracePeriod());
			jdbc.sql("""
					INSERT INTO user_deletion_requests (id, user_id, request_status, requested_at, scheduled_purge_at,
					                                    updated_at)
					VALUES (:id, :user, 'SCHEDULED', :now, :purgeAt, :now)
					""").param("id", id).param("user", userId).param("now", Timestamp.from(now))
					.param("purgeAt", Timestamp.from(purgeAt)).update();
			outbox.publish("user", userId.toString(), REQUESTED_EVENT, Map.of("requestId", id.toString(), "userId",
					userId.toString(), "scheduledPurgeAt", purgeAt.toString()));
			return new DeletionRequestDto(id, "SCHEDULED", now, purgeAt);
		});
		entitlements.invalidate(userId);
		count("requested");
		log.info("Account {} requested deletion {}; purge scheduled for {}", userId, created.id(),
				created.scheduledPurgeAt());
		return created;
	}

	/**
	 * The caller's open request ({@code SCHEDULED}, or {@code PROCESSING} in the instant the executor starts).
	 *
	 * @throws ApiException 404 when there is none
	 */
	DeletionRequestDto current(UUID userId) {
		return jdbc.sql("""
				SELECT id, request_status, requested_at, scheduled_purge_at FROM user_deletion_requests
				WHERE user_id = :user AND request_status IN ('REQUESTED', 'SCHEDULED', 'PROCESSING')
				""").param("user", userId).query(DeletionRequestService::map).optional()
				.orElseThrow(DeletionRequestService::noOpenRequest);
	}

	/**
	 * Cancels the caller's scheduled deletion; the account is {@code ACTIVE} again (sessions ended by the request stay
	 * ended: those devices sign in again).
	 *
	 * @throws ApiException 404 when there is no open request; 409 {@code DELETION_PENDING} when the executor has
	 *                      already started (from then on the account's tokens are refused anyway)
	 */
	void cancel(UUID userId) {
		Instant now = clock.instant();
		UUID cancelled = tx.execute(status -> {
			record Open(UUID id, String status) {
			}
			Open open = jdbc.sql("""
					SELECT id, request_status FROM user_deletion_requests
					WHERE user_id = :user AND request_status IN ('REQUESTED', 'SCHEDULED', 'PROCESSING')
					FOR UPDATE
					""").param("user", userId)
					.query((rs, n) -> new Open(rs.getObject("id", UUID.class), rs.getString("request_status")))
					.optional().orElseThrow(DeletionRequestService::noOpenRequest);
			if ("PROCESSING".equals(open.status())) {
				throw new ApiException(ApiErrorCode.DELETION_PENDING, HttpStatus.CONFLICT,
						"The deletion has already started and can no longer be cancelled.");
			}
			jdbc.sql("""
					UPDATE user_deletion_requests SET request_status = 'CANCELLED', cancellation_reason = :reason,
					       updated_at = :now
					WHERE id = :id
					""").param("reason", CANCELLED_BY_USER).param("now", Timestamp.from(now)).param("id", open.id())
					.update();
			accounts.cancelDeletion(userId, now);
			outbox.publish("user", userId.toString(), CANCELLED_EVENT,
					Map.of("requestId", open.id().toString(), "userId", userId.toString()));
			return open.id();
		});
		entitlements.invalidate(userId);
		count("cancelled");
		log.info("Account {} cancelled deletion {}", userId, cancelled);
	}

	private static ApiException noOpenRequest() {
		return new ApiException(ApiErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "There is no pending deletion request.");
	}

	private static DeletionRequestDto map(ResultSet rs, int row) throws SQLException {
		Timestamp purgeAt = rs.getTimestamp("scheduled_purge_at");
		return new DeletionRequestDto(rs.getObject("id", UUID.class), rs.getString("request_status"),
				rs.getTimestamp("requested_at").toInstant(), purgeAt == null ? null : purgeAt.toInstant());
	}

	private void count(String outcome) {
		if (meters != null) {
			meters.counter("qal.privacy.account_deletion", "outcome", outcome).increment();
		}
	}
}
