package com.qalsmartkeyboard.identity.deletion;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.identity.UserDeletionParticipant;

/**
 * Account deletion (B6.2, ADR 0015) for identity's own per-user tables. All are personal and deleted:
 * <ul>
 * <li>{@code user_auth_identities} (phone number or IdP subject, provider e-mail): once gone, the same phone number or
 * Google account can never reach the deleted account again; a later sign-in with it creates a <b>new</b> account
 * (V1 {@code ux_auth_provider_subject} is free again, and {@code ux_users_phone_e164} ignores deleted rows).</li>
 * <li>{@code user_sessions} (refresh-token hashes, IP address, user agent; already revoked when the account was
 * closed), {@code user_password_credentials}, {@code password_reset_tokens}, {@code otp_challenges} bound to the user,
 * {@code attestation_nonces} bound to the user.</li>
 * </ul>
 * Not touched: {@code user_data_keys} (DESTROYED rows with NULL wrapped keys are the shred record and stop any new key
 * for the user, ADR 0005) and the {@code users} row (anonymised in the last step by {@link JdbcAccountDeletion}).
 */
@Component
class IdentityDeletionParticipant implements UserDeletionParticipant {

	private static final Logger log = LoggerFactory.getLogger(IdentityDeletionParticipant.class);

	private final JdbcClient jdbc;

	IdentityDeletionParticipant(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public int deletionOrder() {
		return IDENTITY;
	}

	@Override
	public int purgeUser(UUID userId, Instant now) {
		int rows = 0;
		for (String table : new String[] { "user_auth_identities", "user_sessions", "user_password_credentials",
				"password_reset_tokens", "otp_challenges", "attestation_nonces" }) {
			rows += jdbc.sql("DELETE FROM " + table + " WHERE user_id = :user").param("user", userId).update();
		}
		log.info("Account deletion {}: {} identity row(s) deleted", userId, rows);
		return rows;
	}
}
