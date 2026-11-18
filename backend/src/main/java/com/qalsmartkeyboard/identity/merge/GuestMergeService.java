package com.qalsmartkeyboard.identity.merge;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.identity.GuestMergeParticipant;
import com.qalsmartkeyboard.identity.account.UserAccountStore;
import com.qalsmartkeyboard.identity.account.UserAccountStore.AccountState;
import com.qalsmartkeyboard.identity.session.SessionService;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;
import com.qalsmartkeyboard.platform.outbox.OutboxPublisher;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Account linking on sign-in (SD 9.1 "a guest is merged on sign-in", contract {@code verifyPhoneOtp} /
 * {@code exchangeOAuthIdToken}, plan B2.6, ADR 0010). A phone or Google sign-in that carries the bearer token of a live
 * guest keeps the guest's data and allowance consumption:
 * <ul>
 * <li><b>Upgrade in place</b> when the identity has no account yet: the guest row itself becomes the
 * {@code REGISTERED} account ({@link UserAccountStore#signInWithPhone(String, boolean, java.time.Instant, UUID)}), so no
 * row moves, nothing is re-encrypted and the user id, devices, usage counters and keys stay as they are. After the
 * registered session is issued, {@link #completeUpgrade} ends the guest's sessions and publishes the event.</li>
 * <li><b>Merge</b> into an existing registered account ({@link #mergeInto}): every {@link GuestMergeParticipant}
 * moves its module's rows (re-encrypting ciphertext for the target), the guest's sessions end, the guest becomes
 * {@code MERGED} with {@code merged_into_user_id}, its data keys are crypto-shredded and the event is published.</li>
 * </ul>
 * Everything runs in the caller's sign-in transaction, after {@link #lockMergeableGuest}: the guest row lock serialises
 * every merge and upgrade of one guest, so of two concurrent sign-ins with one guest token exactly one links it and the
 * other sees it is no longer an active guest ({@link #staleGuestToken()}).
 */
public class GuestMergeService {

	private static final Logger log = LoggerFactory.getLogger(GuestMergeService.class);
	static final String MERGED_EVENT = "identity.guest.merged";

	/**
	 * The guest bearer token a sign-in request carried.
	 *
	 * @param userId    the token's {@code sub} (a {@code GUEST} account)
	 * @param sessionId the token's {@code sid} (refresh-token family)
	 */
	public record GuestCaller(UUID userId, UUID sessionId) {

		/**
		 * The guest to link: only a valid <em>guest</em> access token links anything. No bearer, or a registered
		 * account's token, signs in without a merge (the contract merges "if the request carries a guest bearer
		 * token"); the security chain has already refused invalid tokens and those of guests that are not active.
		 *
		 * @return {@code null} when there is no guest to link
		 */
		public static GuestCaller from(AuthenticatedUser caller) {
			return caller != null && caller.isGuest() ? new GuestCaller(caller.userId(), caller.sessionId()) : null;
		}
	}

	private final UserAccountStore accounts;
	private final SessionService sessions;
	private final ObjectProvider<GuestMergeParticipant> participants;
	private final UserDataEncryption encryption;
	private final OutboxPublisher outbox;
	private final JdbcClient jdbc;
	private final MeterRegistry meters;

	GuestMergeService(UserAccountStore accounts, SessionService sessions,
			ObjectProvider<GuestMergeParticipant> participants, UserDataEncryption encryption, OutboxPublisher outbox,
			JdbcClient jdbc, MeterRegistry meters) {
		this.accounts = accounts;
		this.sessions = sessions;
		this.participants = participants;
		this.encryption = encryption;
		this.outbox = outbox;
		this.jdbc = jdbc;
		this.meters = meters;
	}

	/**
	 * Locks the guest's {@code users} row for the rest of the sign-in transaction and checks that the token can still
	 * link it: the account is an {@code ACTIVE GUEST} (not merged, upgraded, suspended or deleted) and the token's
	 * session family still has an ACTIVE refresh token (a logged-out guest, or a family revoked for refresh-token reuse,
	 * cannot hand its data to anybody). Call it before the sign-in writes anything.
	 *
	 * @return whether the guest may be upgraded or merged; if not, answer {@link #staleGuestToken()}
	 */
	public boolean lockMergeableGuest(GuestCaller guest) {
		AccountState state = accounts.lockForMerge(guest.userId()).orElse(null);
		boolean mergeable = state != null && state.isActiveGuest()
				&& sessions.isFamilyActive(guest.userId(), guest.sessionId());
		if (!mergeable) {
			count("refused", "none");
			log.info("Guest {} cannot be linked on sign-in (no longer an active guest session)", guest.userId());
		}
		return mergeable;
	}

	/**
	 * After the guest row was upgraded in place and the registered session was issued (which registers the device
	 * first: device locks before session-family locks, the order every other path uses): ends the guest's session
	 * families, all but the new one, and publishes {@value #MERGED_EVENT} with mode {@code UPGRADED}.
	 *
	 * @param method        {@code PHONE} or the identity provider ({@code GOOGLE})
	 * @param newSessionId  the family of the session just issued to the registered account (kept)
	 */
	public void completeUpgrade(UUID userId, String method, UUID newSessionId) {
		int revoked = sessions.revokeAllForMerge(userId, newSessionId);
		publish(userId, userId, "UPGRADED", method, revoked);
		count("upgraded", method);
		log.info("Guest {} upgraded in place to a registered account ({}); {} guest session row(s) ended", userId,
				method, revoked);
	}

	/**
	 * Moves the guest into an existing registered account, in the caller's transaction (see the class comment). The
	 * caller holds the guest lock ({@link #lockMergeableGuest}) and has signed the target in.
	 */
	public void mergeInto(UUID guestUserId, UUID targetUserId, String method, Instant now) {
		AccountState target = accounts.lockForMerge(targetUserId).orElseThrow();
		if (!"REGISTERED".equals(target.accountType()) || guestUserId.equals(targetUserId)) {
			throw new IllegalStateException("A guest can only be merged into another registered account");
		}
		// module rows first (devices take device locks before any session-family lock, as registration and device
		// revocation do), then the guest's session families
		List<GuestMergeParticipant> ordered = participants.stream()
				.sorted(Comparator.comparingInt(GuestMergeParticipant::mergeOrder)).toList();
		for (GuestMergeParticipant participant : ordered) {
			participant.mergeGuest(guestUserId, targetUserId, now);
		}
		int revoked = sessions.revokeAllForMerge(guestUserId, null);
		// unspent attestation nonces bound to the guest can no longer be used by anybody
		jdbc.sql("""
				UPDATE attestation_nonces SET consumed_at = GREATEST(:now, created_at)
				WHERE user_id = :guest AND consumed_at IS NULL
				""").param("now", Timestamp.from(now)).param("guest", guestUserId).update();
		if (!accounts.markMerged(guestUserId, targetUserId, now)) {
			throw new IllegalStateException("The guest account changed during the merge");
		}
		// every value the participants needed was re-encrypted for the target above; what is left is unreadable now
		int destroyed = encryption.cryptoShred(guestUserId);
		publish(guestUserId, targetUserId, "MERGED", method, revoked);
		count("merged", method);
		log.info("Guest {} merged into user {} ({}); {} guest session row(s) ended, {} participant(s), {} data key(s) "
				+ "shredded", guestUserId, targetUserId, method, revoked, ordered.size(), destroyed);
	}

	/**
	 * The answer to a sign-in whose guest bearer token can no longer link its guest: the same 401 the security chain
	 * gives such a token (the guest was merged or upgraded meanwhile, or its session ended). Nothing was written; the
	 * client drops the guest token and signs in without it.
	 */
	public static ApiException staleGuestToken() {
		return new ApiException(ApiErrorCode.GUEST_SESSION_INVALID, HttpStatus.UNAUTHORIZED,
				"The guest session can no longer be linked; sign in without the guest token.",
				List.of(), Map.of(), Map.of("WWW-Authenticate", "Bearer error=\"invalid_token\""));
	}

	private void publish(UUID guestUserId, UUID targetUserId, String mode, String method, int revokedSessions) {
		outbox.publish("user", targetUserId.toString(), MERGED_EVENT,
				Map.of("guestUserId", guestUserId.toString(), "targetUserId", targetUserId.toString(), "mode", mode,
						"method", method, "revokedSessions", revokedSessions));
	}

	private void count(String outcome, String method) {
		if (meters != null) {
			meters.counter("qal.identity.guest_merge", "outcome", outcome, "method", method).increment();
		}
	}
}
