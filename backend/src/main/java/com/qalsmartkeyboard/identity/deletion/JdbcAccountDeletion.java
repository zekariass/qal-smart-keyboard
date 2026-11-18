package com.qalsmartkeyboard.identity.deletion;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.identity.AccountDeletion;
import com.qalsmartkeyboard.identity.account.UserAccountStore;
import com.qalsmartkeyboard.identity.account.UserAccountStore.AccountState;
import com.qalsmartkeyboard.identity.session.SessionService;

/**
 * {@link AccountDeletion} on {@code users} and {@code user_sessions} (ADR 0015). The row lock is the one sign-in and
 * guest merge take ({@link UserAccountStore#lockForMerge}), so a sign-in and a deletion step of one account never
 * interleave.
 */
@Component
class JdbcAccountDeletion implements AccountDeletion {

	private static final Logger log = LoggerFactory.getLogger(JdbcAccountDeletion.class);

	private final JdbcClient jdbc;
	private final UserAccountStore accounts;
	private final SessionService sessions;

	JdbcAccountDeletion(JdbcClient jdbc, UserAccountStore accounts, SessionService sessions) {
		this.jdbc = jdbc;
		this.accounts = accounts;
		this.sessions = sessions;
	}

	@Override
	public RequestOutcome requestDeletion(UUID userId, UUID keepSessionFamily, Instant now) {
		AccountState state = accounts.lockForMerge(userId).orElse(null);
		if (state == null) {
			return RequestOutcome.NOT_ALLOWED;
		}
		switch (state.accountStatus()) {
			case "ACTIVE":
				break;
			case "DELETION_PENDING":
				return RequestOutcome.ALREADY_PENDING;
			case "SUSPENDED":
				return RequestOutcome.SUSPENDED;
			default:
				return RequestOutcome.NOT_ALLOWED;
		}
		jdbc.sql("""
				UPDATE users SET account_status = 'DELETION_PENDING', updated_at = :now
				WHERE id = :id AND account_status = 'ACTIVE' AND deleted_at IS NULL
				""").param("now", Timestamp.from(now)).param("id", userId).update();
		int revoked = sessions.revokeAllForDeletion(userId, keepSessionFamily);
		log.info("Account {} is pending deletion; {} other session row(s) ended", userId, revoked);
		return RequestOutcome.STARTED;
	}

	@Override
	public boolean cancelDeletion(UUID userId, Instant now) {
		accounts.lockForMerge(userId);
		return jdbc.sql("""
				UPDATE users SET account_status = 'ACTIVE', updated_at = :now
				WHERE id = :id AND account_status = 'DELETION_PENDING' AND deleted_at IS NULL
				""").param("now", Timestamp.from(now)).param("id", userId).update() == 1;
	}

	@Override
	public int closeAccount(UUID userId, Instant now) {
		AccountState state = accounts.lockForMerge(userId)
				.orElseThrow(() -> new IllegalStateException("No account to delete"));
		if ("MERGED".equals(state.accountStatus())) {
			throw new IllegalStateException("A merged guest is not deleted through a deletion request");
		}
		jdbc.sql("""
				UPDATE users SET deleted_at = :now, updated_at = :now
				WHERE id = :id AND deleted_at IS NULL
				""").param("now", Timestamp.from(now)).param("id", userId).update();
		// after deleted_at: a sign-in serialised behind our row lock now finds the account closed
		return sessions.revokeAllForDeletion(userId, null);
	}

	@Override
	public void anonymiseAccount(UUID userId, Instant now) {
		accounts.lockForMerge(userId);
		int changed = jdbc.sql("""
				UPDATE users SET email = NULL, email_normalized = NULL, email_verified_at = NULL,
				       phone_e164 = NULL, phone_verified_at = NULL, display_name = NULL,
				       market_country_code = NULL, market_source = NULL, ui_locale = NULL, time_zone = DEFAULT,
				       age_confirmed_at = NULL, last_login_at = NULL,
				       account_status = 'DELETED', deleted_at = COALESCE(deleted_at, :now), updated_at = :now
				WHERE id = :id AND account_status <> 'MERGED'
				""").param("now", Timestamp.from(now)).param("id", userId).update();
		if (changed != 1) {
			throw new IllegalStateException("No deletable account to anonymise");
		}
	}
}
