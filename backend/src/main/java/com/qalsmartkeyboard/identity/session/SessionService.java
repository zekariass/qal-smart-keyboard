package com.qalsmartkeyboard.identity.session;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.identity.account.UserAccount;
import com.qalsmartkeyboard.identity.account.UserAccountStore;
import com.qalsmartkeyboard.identity.session.SessionStore.SessionRow;
import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.identity.token.AccessTokenService.AccessToken;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.outbox.OutboxPublisher;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Sessions and refresh-token rotation (SD 9.3, Review D4, {@code user_sessions}).
 *
 * <ul>
 * <li><b>Issue</b> (sign-in): a new family ({@code token_family_id}, the access token's {@code sid}) with one ACTIVE
 * row holding the SHA-256 of a fresh 256-bit refresh token, expiring after the sliding TTL (60 days).</li>
 * <li><b>Refresh</b>: the presented token's row is locked ({@code FOR UPDATE}). ACTIVE and unexpired: a successor row
 * is inserted in the same family and the old one is set ROTATED ({@code replaced_by_session_id}) by a
 * compare-and-set, so exactly one successor exists per token.</li>
 * <li><b>Reuse</b>: presenting a ROTATED token means a copy of an old token is in use. Every ACTIVE row of the family
 * is revoked ({@code TOKEN_REUSE}), {@code reuse_detected_at} is set, an outbox event lets notifications tell the
 * user, and the answer is {@code REFRESH_TOKEN_REUSED}. A second concurrent refresh of the same token waits on the row
 * lock, then sees ROTATED and is treated as reuse, as SD 9.3 prescribes: the race can never leave two valid
 * successors (the cost is that the family is revoked).</li>
 * <li><b>Logout</b>: revokes the caller's family, or all of the user's families ({@code allDevices}).</li>
 * </ul>
 * Failures return from the transaction (so revocations commit) and then become Problems.
 */
public class SessionService implements SessionIssuer {

	private static final Logger log = LoggerFactory.getLogger(SessionService.class);
	static final String REUSE_EVENT = "identity.refresh_token.reuse_detected";

	record Refreshed(IssuedSession session, UserAccount user) {
	}

	private enum RefreshFailure {
		UNKNOWN, REUSED, EXPIRED, REVOKED, SUSPENDED, ACCOUNT_CLOSED
	}

	private sealed interface RefreshOutcome {
		record Ok(Refreshed refreshed) implements RefreshOutcome {
		}

		record Failed(RefreshFailure failure) implements RefreshOutcome {
		}
	}

	private final SessionStore store;
	private final UserAccountStore accounts;
	private final AccessTokenService accessTokens;
	private final RefreshTokens refreshTokens;
	private final OutboxPublisher outbox;
	private final TransactionTemplate transactions;
	private final java.time.Duration refreshTtl;
	private final Clock clock;
	private final MeterRegistry meters;

	SessionService(SessionStore store, UserAccountStore accounts, AccessTokenService accessTokens,
			RefreshTokens refreshTokens, OutboxPublisher outbox, TransactionTemplate transactions,
			java.time.Duration refreshTtl, Clock clock, MeterRegistry meters) {
		this.store = store;
		this.accounts = accounts;
		this.accessTokens = accessTokens;
		this.refreshTokens = refreshTokens;
		this.outbox = outbox;
		this.transactions = transactions;
		this.refreshTtl = refreshTtl;
		this.clock = clock;
		this.meters = meters;
	}

	/** Joins the caller's transaction (the sign-in proof commits with the session). */
	@Override
	public IssuedSession issue(UserAccount user, SessionContext context) {
		IssuedSession session = newRow(user, UUID.randomUUID(), context, clock.instant());
		count("issued");
		return session;
	}

	private IssuedSession newRow(UserAccount user, UUID familyId, SessionContext context, Instant now) {
		String refreshToken = refreshTokens.newToken();
		Instant refreshExpiresAt = now.plus(refreshTtl);
		UUID rowId = UUID.randomUUID();
		store.insert(rowId, user.id(), context.deviceId(), familyId, RefreshTokens.hash(refreshToken),
				context.ipAddress(), context.userAgent(), now, refreshExpiresAt);
		AccessToken access = accessTokens.issue(user.id(), familyId, context.deviceId(), user.accountType());
		return new IssuedSession(access.value(), access.expiresAt(), refreshToken, refreshExpiresAt, familyId,
				context.deviceId());
	}

	Refreshed refresh(String refreshToken, SessionContext context) {
		String hash = RefreshTokens.hash(refreshToken);
		RefreshOutcome outcome = transactions.execute(status -> refreshInTransaction(hash, context));
		return switch (outcome) {
			case RefreshOutcome.Ok ok -> {
				count("rotated");
				yield ok.refreshed();
			}
			case RefreshOutcome.Failed f -> throw problem(f.failure());
		};
	}

	private RefreshOutcome refreshInTransaction(String hash, SessionContext context) {
		SessionRow row = store.findByHashForUpdate(hash).orElse(null);
		if (row == null) {
			return new RefreshOutcome.Failed(RefreshFailure.UNKNOWN);
		}
		Instant now = clock.instant();
		switch (row.status()) {
			case "ROTATED" -> {
				boolean firstDetection = store.markReuseDetected(row.id(), now);
				int revoked = store.revokeFamily(row.familyId(), "TOKEN_REUSE", now);
				count("reuse_detected");
				if (firstDetection || revoked > 0) {
					// one event per incident, not per replayed request (notifications tells the user, SD 9.3)
					outbox.publish("user_session_family", row.familyId().toString(), REUSE_EVENT,
							Map.of("userId", row.userId().toString(), "tokenFamilyId", row.familyId().toString(),
									"revokedSessions", revoked));
					log.warn("Refresh-token reuse detected for session family {}; {} active session(s) revoked",
							row.familyId(), revoked);
				}
				return new RefreshOutcome.Failed(RefreshFailure.REUSED);
			}
			case "EXPIRED" -> {
				return new RefreshOutcome.Failed(RefreshFailure.EXPIRED);
			}
			case "REVOKED" -> {
				return new RefreshOutcome.Failed(RefreshFailure.REVOKED);
			}
			default -> {
				// ACTIVE
			}
		}
		if (!now.isBefore(row.expiresAt())) {
			store.markExpired(row.id());
			count("expired");
			return new RefreshOutcome.Failed(RefreshFailure.EXPIRED);
		}
		UserAccount user = accounts.findById(row.userId()).orElse(null);
		if (user == null || "DELETED".equals(user.accountStatus()) || "MERGED".equals(user.accountStatus())) {
			store.revokeFamily(row.familyId(),
					user != null && "MERGED".equals(user.accountStatus()) ? "ACCOUNT_MERGED" : "ACCOUNT_DELETED", now);
			return new RefreshOutcome.Failed(RefreshFailure.ACCOUNT_CLOSED);
		}
		if ("SUSPENDED".equals(user.accountStatus())) {
			return new RefreshOutcome.Failed(RefreshFailure.SUSPENDED);
		}
		// the successor keeps the family and the device; the request metadata is the current one
		SessionContext next = new SessionContext(row.deviceId(), context.ipAddress(), context.userAgent());
		String refreshToken = refreshTokens.newToken();
		Instant refreshExpiresAt = now.plus(refreshTtl);
		UUID nextId = UUID.randomUUID();
		store.insert(nextId, user.id(), next.deviceId(), row.familyId(), RefreshTokens.hash(refreshToken),
				next.ipAddress(), next.userAgent(), now, refreshExpiresAt);
		if (!store.markRotated(row.id(), nextId, now)) {
			// unreachable while the row lock is held; never hand out a second successor
			throw new IllegalStateException("Refresh token was rotated concurrently");
		}
		AccessToken access = accessTokens.issue(user.id(), row.familyId(), next.deviceId(), user.accountType());
		return new RefreshOutcome.Ok(new Refreshed(new IssuedSession(access.value(), access.expiresAt(), refreshToken,
				refreshExpiresAt, row.familyId(), next.deviceId()), user));
	}

	void logout(AuthenticatedUser caller, boolean allDevices) {
		Instant now = clock.instant();
		transactions.executeWithoutResult(status -> {
			if (allDevices) {
				store.revokeAllOfUser(caller.userId(), "LOGOUT", now);
			}
			else {
				store.revokeFamilyOfUser(caller.sessionId(), caller.userId(), "LOGOUT", now);
			}
		});
		count(allDevices ? "logout_all" : "logout");
	}

	private static ApiException problem(RefreshFailure failure) {
		return switch (failure) {
			case REUSED -> new ApiException(ApiErrorCode.REFRESH_TOKEN_REUSED, HttpStatus.UNAUTHORIZED,
					"This refresh token was already used. All sessions of this sign-in were ended; sign in again.");
			case EXPIRED -> new ApiException(ApiErrorCode.TOKEN_EXPIRED, HttpStatus.UNAUTHORIZED,
					"The refresh token has expired. Sign in again.");
			case SUSPENDED -> new ApiException(ApiErrorCode.ACCOUNT_SUSPENDED, HttpStatus.FORBIDDEN, null);
			case UNKNOWN, REVOKED, ACCOUNT_CLOSED -> new ApiException(ApiErrorCode.UNAUTHENTICATED,
					HttpStatus.UNAUTHORIZED, "The refresh token is not valid. Sign in again.");
		};
	}

	private void count(String event) {
		if (meters != null) {
			meters.counter("qal.identity.sessions", "event", event).increment();
		}
	}
}
