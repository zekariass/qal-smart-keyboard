package com.qalsmartkeyboard.identity.session;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.identity.DeviceRegistrar;
import com.qalsmartkeyboard.identity.DeviceSessions;
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
 * Sessions and refresh-token rotation (SD 9.3, Review D4, ADR 0006, ADR 0007 D8, {@code user_sessions}).
 *
 * <ul>
 * <li><b>Issue</b> (sign-in): a new family ({@code token_family_id}, the access token's {@code sid}) with one ACTIVE
 * row holding the SHA-256 of a fresh 256-bit refresh token, expiring after the sliding TTL (60 days).</li>
 * <li><b>Refresh</b>: the family's advisory lock, then the presented token's row ({@code FOR UPDATE}). ACTIVE and
 * unexpired: a successor row is inserted in the same family and the old one is set ROTATED
 * ({@code replaced_by_session_id}) by a compare-and-set, so exactly one successor exists per token.</li>
 * <li><b>Grace retry</b> (a client retrying after a lost response): a ROTATED token is rotated again instead of being
 * treated as reuse only if (1) the grace window {@code qal.identity.tokens.refresh-reuse-grace} (default 30 s, 0
 * disables) since its <em>first</em> rotation has not passed, (2) the token is unexpired and no reuse was detected on
 * it, and (3) its direct successor, locked after it, is still ACTIVE, unexpired, has no successor and was never
 * presented ({@code last_used_at IS NULL}). The unused successor is revoked ({@code SUPERSEDED_RETRY}), a new one is
 * inserted, and the rotated row's pointer moves to it ({@code rotated_at} is kept, so retries never extend the
 * window).</li>
 * <li><b>Reuse</b>: every other presentation of a ROTATED token, and any presentation of a superseded successor (a
 * second holder of the family), means a copy of an old token is in use. Every ACTIVE row of the family is revoked
 * ({@code TOKEN_REUSE}), {@code reuse_detected_at} is set, an outbox event lets notifications tell the user, and the
 * answer is {@code REFRESH_TOKEN_REUSED}.</li>
 * <li><b>Logout</b>: revokes the caller's family, or all of the user's families ({@code allDevices}), under the family
 * lock(s), so a concurrent rotation cannot leave a live successor behind.</li>
 * </ul>
 * Concurrency: every refresh of one family is serialised by the family lock (and by the row locks, presented row
 * first, successor second). Two concurrent grace retries run one after the other and the second supersedes the first
 * one's successor, so at most one ACTIVE successor exists. A retry racing the first use of the successor either finds
 * it unused (grace rotation; that use then presents a superseded token and revokes the family) or used (the family is
 * revoked). Failures return from the transaction (so revocations commit) and then become Problems.
 */
public class SessionService implements SessionIssuer, DeviceSessions {

	private static final Logger log = LoggerFactory.getLogger(SessionService.class);
	static final String REUSE_EVENT = "identity.refresh_token.reuse_detected";

	record Refreshed(IssuedSession session, UserAccount user) {
	}

	private enum RefreshFailure {
		UNKNOWN, REUSED, EXPIRED, REVOKED, SUSPENDED, ACCOUNT_CLOSED
	}

	private sealed interface RefreshOutcome {
		record Ok(Refreshed refreshed, boolean graceRetry) implements RefreshOutcome {
		}

		record Failed(RefreshFailure failure) implements RefreshOutcome {
		}
	}

	private final ObjectProvider<DeviceRegistrar> devices;
	private final SessionStore store;
	private final UserAccountStore accounts;
	private final AccessTokenService accessTokens;
	private final RefreshTokens refreshTokens;
	private final OutboxPublisher outbox;
	private final TransactionTemplate transactions;
	private final Duration refreshTtl;
	private final Duration reuseGrace;
	private final Clock clock;
	private final MeterRegistry meters;

	SessionService(ObjectProvider<DeviceRegistrar> devices, SessionStore store, UserAccountStore accounts, AccessTokenService accessTokens,
			RefreshTokens refreshTokens, OutboxPublisher outbox, TransactionTemplate transactions, Duration refreshTtl,
			Duration reuseGrace, Clock clock, MeterRegistry meters) {
		this.devices = devices;
		this.store = store;
		this.accounts = accounts;
		this.accessTokens = accessTokens;
		this.refreshTokens = refreshTokens;
		this.outbox = outbox;
		this.transactions = transactions;
		this.refreshTtl = refreshTtl;
		this.reuseGrace = reuseGrace;
		this.clock = clock;
		this.meters = meters;
	}

	/** Joins the caller's transaction (the sign-in proof commits with the session). */
	@Override
	public IssuedSession issue(UserAccount user, SessionContext context) {
		IssuedSession session = newRow(user, UUID.randomUUID(), withDevice(user, context), clock.instant());
		count("issued");
		return session;
	}

	/**
	 * Registers the installation named by a sign-in request (B2.5) so the session, and through it the access token's
	 * {@code did}, carry the device. Skipped when the device is already known, when the request has none, or for
	 * {@code WEB} (no device rows there). The registration failing (400) fails the whole sign-in transaction.
	 */
	private SessionContext withDevice(UserAccount user, SessionContext context) {
		if (context.deviceId() != null || context.device() == null || !context.device().isNative()) {
			return context;
		}
		DeviceRegistrar registrar = devices.getIfAvailable();
		if (registrar == null) {
			return context;
		}
		UUID deviceId = registrar.register(user.id(), context.device(), null);
		return new SessionContext(deviceId, context.ipAddress(), context.userAgent(), null);
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
				count(ok.graceRetry() ? "rotated_grace" : "rotated");
				yield ok.refreshed();
			}
			case RefreshOutcome.Failed f -> throw problem(f.failure());
		};
	}

	private RefreshOutcome refreshInTransaction(String hash, SessionContext context) {
		UUID familyId = store.findFamilyByHash(hash).orElse(null);
		if (familyId == null) {
			return new RefreshOutcome.Failed(RefreshFailure.UNKNOWN);
		}
		store.lockFamily(familyId);
		SessionRow row = store.findByHashForUpdate(hash).orElse(null);
		if (row == null) {
			return new RefreshOutcome.Failed(RefreshFailure.UNKNOWN);
		}
		Instant now = clock.instant();
		switch (row.status()) {
			case "ROTATED" -> {
				SessionRow unusedSuccessor = unusedSuccessorWithinGrace(row, now);
				return unusedSuccessor == null ? reuseDetected(row, now) : rotate(row, unusedSuccessor, context, now);
			}
			case "EXPIRED" -> {
				return new RefreshOutcome.Failed(RefreshFailure.EXPIRED);
			}
			case "REVOKED" -> {
				// a successor superseded by a grace retry never reached its client: whoever presents it is a second
				// holder of the family
				return SessionStore.SUPERSEDED_RETRY.equals(row.revokedReason()) ? reuseDetected(row, now)
						: new RefreshOutcome.Failed(RefreshFailure.REVOKED);
			}
			default -> {
				// ACTIVE
			}
		}
		if (!now.isBefore(row.expiresAt())) {
			store.markExpired(row.id(), now);
			count("expired");
			return new RefreshOutcome.Failed(RefreshFailure.EXPIRED);
		}
		return rotate(row, null, context, now);
	}

	/**
	 * The grace rule (ADR 0007 D8). Locks the successor row after the presented one (same order everywhere).
	 *
	 * @return the presented ROTATED row's direct successor if a retry may replace it, else {@code null} (reuse)
	 */
	private SessionRow unusedSuccessorWithinGrace(SessionRow rotated, Instant now) {
		if (reuseGrace.isZero() || rotated.rotatedAt() == null || rotated.replacedBy() == null
				|| rotated.reuseDetectedAt() != null || !now.isBefore(rotated.rotatedAt().plus(reuseGrace))
				|| !now.isBefore(rotated.expiresAt())) {
			return null;
		}
		SessionRow next = store.findByIdForUpdate(rotated.replacedBy()).orElse(null);
		boolean unused = next != null && "ACTIVE".equals(next.status()) && next.lastUsedAt() == null
				&& next.replacedBy() == null && next.reuseDetectedAt() == null
				&& next.familyId().equals(rotated.familyId()) && now.isBefore(next.expiresAt());
		return unused ? next : null;
	}

	private RefreshOutcome reuseDetected(SessionRow row, Instant now) {
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

	/**
	 * Issues the successor of {@code presented}: a normal rotation of an ACTIVE row ({@code superseded == null}), or a
	 * grace retry of a ROTATED row whose unused successor {@code superseded} is revoked and replaced.
	 */
	private RefreshOutcome rotate(SessionRow presented, SessionRow superseded, SessionContext context, Instant now) {
		UserAccount user = accounts.findById(presented.userId()).orElse(null);
		if (user == null || "DELETED".equals(user.accountStatus()) || "MERGED".equals(user.accountStatus())) {
			store.revokeFamily(presented.familyId(),
					user != null && "MERGED".equals(user.accountStatus()) ? "ACCOUNT_MERGED" : "ACCOUNT_DELETED", now);
			return new RefreshOutcome.Failed(RefreshFailure.ACCOUNT_CLOSED);
		}
		if ("SUSPENDED".equals(user.accountStatus())) {
			if (superseded == null) {
				store.markUsed(presented.id(), now); // presented, so no longer an unused successor
			}
			return new RefreshOutcome.Failed(RefreshFailure.SUSPENDED);
		}
		// the successor keeps the family and the device; the request metadata is the current one
		SessionContext next = new SessionContext(presented.deviceId(), context.ipAddress(), context.userAgent());
		String refreshToken = refreshTokens.newToken();
		Instant refreshExpiresAt = now.plus(refreshTtl);
		UUID nextId = UUID.randomUUID();
		store.insert(nextId, user.id(), next.deviceId(), presented.familyId(), RefreshTokens.hash(refreshToken),
				next.ipAddress(), next.userAgent(), now, refreshExpiresAt);
		boolean linked = superseded == null ? store.markRotated(presented.id(), nextId, now)
				: store.supersedeUnused(superseded.id(), now)
						&& store.repointRotated(presented.id(), superseded.id(), nextId, now);
		if (!linked) {
			// unreachable while the locks are held; never hand out a second successor (rolls the transaction back)
			throw new IllegalStateException("Refresh token was rotated concurrently");
		}
		if (superseded != null) {
			log.info("Refresh retried within the grace window for session family {}; the unused successor was superseded",
					presented.familyId());
		}
		AccessToken access = accessTokens.issue(user.id(), presented.familyId(), next.deviceId(), user.accountType());
		return new RefreshOutcome.Ok(new Refreshed(new IssuedSession(access.value(), access.expiresAt(), refreshToken,
				refreshExpiresAt, presented.familyId(), next.deviceId()), user), superseded != null);
	}

	void logout(AuthenticatedUser caller, boolean allDevices) {
		Instant now = clock.instant();
		transactions.executeWithoutResult(status -> {
			// family locks first (in a stable order for logout-all), so a concurrent rotation cannot leave a live
			// successor behind
			if (allDevices) {
				store.activeFamiliesOfUser(caller.userId()).forEach(store::lockFamily);
				store.revokeAllOfUser(caller.userId(), "LOGOUT", now);
			}
			else {
				store.lockFamily(caller.sessionId());
				store.revokeFamilyOfUser(caller.sessionId(), caller.userId(), "LOGOUT", now);
			}
		});
		count(allDevices ? "logout_all" : "logout");
	}

	/**
	 * Ends every session family of a guest that is merged or upgraded into a registered account (B2.6, reason
	 * {@code ACCOUNT_MERGED}), so no refresh token issued to the guest works afterwards. Joins the caller's transaction
	 * and takes the family locks (stable order) before revoking, like logout-all.
	 *
	 * @param keepFamily the family of the registered session just issued under the same user id (in-place upgrade),
	 *                   or {@code null}
	 * @return the number of session rows revoked
	 */
	public int revokeAllForMerge(UUID userId, UUID keepFamily) {
		int revoked = revokeFamilies(userId, keepFamily, "ACCOUNT_MERGED");
		if (revoked > 0) {
			count("guest_merged");
		}
		return revoked;
	}

	/**
	 * Ends the session families of an account whose deletion was requested or is being executed (B6.2, ADR 0015, reason
	 * {@code ACCOUNT_DELETED}). Joins the caller's transaction and takes the family locks (stable order) first, like
	 * logout-all, so a concurrent rotation cannot leave a live successor behind.
	 *
	 * @param keepFamily the family of the session that asked for the deletion (it stays signed in during the grace
	 *                   period so the person can cancel), or {@code null} to end every family
	 * @return the number of session rows revoked
	 */
	public int revokeAllForDeletion(UUID userId, UUID keepFamily) {
		int revoked = revokeFamilies(userId, keepFamily, "ACCOUNT_DELETED");
		if (revoked > 0) {
			count("account_deleted");
		}
		return revoked;
	}

	/**
	 * Ends every session family of an account whose password was reset, or whose unverified credential was replaced by
	 * the person who proved control of the mailbox (ADR 0033, reason {@code PASSWORD_CHANGED}). Joins the caller's
	 * transaction and takes the family locks first, like logout-all.
	 *
	 * @return the number of session rows revoked
	 */
	public int revokeAllForPasswordChange(UUID userId) {
		int revoked = revokeFamilies(userId, null, "PASSWORD_CHANGED");
		if (revoked > 0) {
			count("password_changed");
		}
		return revoked;
	}

	private int revokeFamilies(UUID userId, UUID keepFamily, String reason) {
		List<UUID> families = store.activeFamiliesOfUser(userId).stream().filter(f -> !f.equals(keepFamily)).toList();
		families.forEach(store::lockFamily);
		Instant now = clock.instant();
		int revoked = 0;
		for (UUID family : families) {
			revoked += store.revokeFamilyOfUser(family, userId, reason, now);
		}
		return revoked;
	}

	/**
	 * Whether the token family still has an ACTIVE row of the user, i.e. the session behind an access token has not been
	 * logged out, revoked for reuse or by device revocation. A guest merge requires it (ADR 0010).
	 */
	public boolean isFamilyActive(UUID userId, UUID familyId) {
		return store.hasActiveRow(familyId, userId);
	}

	/** Joins the caller's transaction. Family locks first (stable order), so a concurrent rotation cannot be missed. */
	@Override
	public int revokeDeviceSessions(UUID userId, UUID deviceId) {
		Instant now = clock.instant();
		List<UUID> families = store.activeFamiliesOfDevice(userId, deviceId);
		families.forEach(store::lockFamily);
		int revoked = 0;
		for (UUID family : families) {
			revoked += store.revokeFamilyOfUser(family, userId, "DEVICE_REVOKED", now);
		}
		if (revoked > 0) {
			count("device_revoked");
		}
		return revoked;
	}

	/** Joins the caller's transaction; takes the family lock like a rotation does. */
	@Override
	public boolean bindDeviceToSession(UUID userId, UUID sessionId, UUID deviceId) {
		store.lockFamily(sessionId);
		return store.bindDevice(sessionId, userId, deviceId) > 0;
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
