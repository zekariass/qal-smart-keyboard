package com.qalsmartkeyboard.identity.account;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@code users} and {@code user_auth_identities} for sign-in. Must run inside the caller's transaction; the OTP flow
 * holds the per-number advisory lock, so two sign-ins for one number never race here.
 */
@Component
public class UserAccountStore {

	/** Result of a phone sign-in attempt after the code was accepted. */
	public sealed interface PhoneSignIn {

		/** @param created whether the account was created by this sign-in */
		record SignedIn(UserAccount user, boolean created) implements PhoneSignIn {
		}

		/** No account exists and the request did not confirm the minimum age (D26). Nothing was written. */
		record AgeConfirmationRequired() implements PhoneSignIn {
		}

		/** The account exists but is {@code SUSPENDED}. */
		record Suspended() implements PhoneSignIn {
		}

		/** The number belongs to a {@code DELETED} or {@code MERGED} account row that can no longer sign in. */
		record Unavailable() implements PhoneSignIn {
		}
	}

	private static final String USER_COLUMNS = "u.id, u.account_type, u.account_status, u.phone_e164, "
			+ "u.phone_verified_at, u.display_name, u.ui_locale, u.time_zone, u.created_at";

	private final JdbcClient jdbc;

	public UserAccountStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<UserAccount> findById(UUID userId) {
		return jdbc.sql("SELECT " + USER_COLUMNS + " FROM users u WHERE u.id = :id").param("id", userId)
				.query(UserAccountStore::map).optional();
	}

	/**
	 * Finds the account of a verified phone number, or creates a {@code REGISTERED}, {@code ACTIVE} account with a
	 * {@code PHONE} identity. Lookup order: the {@code PHONE} identity, then {@code users.phone_e164} of a live account
	 * (the identity row is added then). Records the login on success.
	 */
	public PhoneSignIn signInWithPhone(String phoneE164, boolean ageConfirmed, Instant now) {
		Optional<UserAccount> existing = jdbc.sql("SELECT " + USER_COLUMNS + """
				 FROM user_auth_identities i JOIN users u ON u.id = i.user_id
				WHERE i.identity_provider = 'PHONE' AND i.provider_subject = :phone
				""").param("phone", phoneE164).query(UserAccountStore::map).optional();
		boolean hasIdentity = existing.isPresent();
		if (existing.isEmpty()) {
			existing = jdbc.sql("SELECT " + USER_COLUMNS
					+ " FROM users u WHERE u.phone_e164 = :phone AND u.deleted_at IS NULL")
					.param("phone", phoneE164).query(UserAccountStore::map).optional();
		}
		if (existing.isPresent()) {
			UserAccount user = existing.get();
			switch (user.accountStatus()) {
				case "SUSPENDED":
					return new PhoneSignIn.Suspended();
				case "DELETED", "MERGED":
					return new PhoneSignIn.Unavailable();
				default:
					break;
			}
			if (!hasIdentity) {
				insertPhoneIdentity(user.id(), phoneE164, now);
			}
			jdbc.sql("""
					UPDATE user_auth_identities SET last_used_at = :now
					WHERE user_id = :userId AND identity_provider = 'PHONE'
					""").param("now", Timestamp.from(now)).param("userId", user.id()).update();
			UserAccount updated = jdbc.sql("""
					UPDATE users u SET last_login_at = :now, updated_at = :now,
					       phone_verified_at = COALESCE(u.phone_verified_at, :now),
					       account_status = CASE WHEN u.account_status = 'PENDING' THEN 'ACTIVE' ELSE u.account_status END
					WHERE u.id = :id
					RETURNING """ + " " + USER_COLUMNS).param("now", Timestamp.from(now)).param("id", user.id())
					.query(UserAccountStore::map).single();
			return new PhoneSignIn.SignedIn(updated, false);
		}
		if (!ageConfirmed) {
			return new PhoneSignIn.AgeConfirmationRequired();
		}
		UUID userId = UUID.randomUUID();
		UserAccount created = jdbc.sql("""
				INSERT INTO users AS u (id, account_type, phone_e164, phone_verified_at, account_status, age_confirmed_at,
				                        last_login_at, created_at, updated_at)
				VALUES (:id, 'REGISTERED', :phone, :now, 'ACTIVE', :now, :now, :now, :now)
				RETURNING """ + " " + USER_COLUMNS).param("id", userId).param("phone", phoneE164)
				.param("now", Timestamp.from(now)).query(UserAccountStore::map).single();
		insertPhoneIdentity(userId, phoneE164, now);
		return new PhoneSignIn.SignedIn(created, true);
	}

	private void insertPhoneIdentity(UUID userId, String phoneE164, Instant now) {
		jdbc.sql("""
				INSERT INTO user_auth_identities (id, user_id, identity_provider, provider_subject, created_at, last_used_at)
				VALUES (:id, :userId, 'PHONE', :phone, :now, :now)
				ON CONFLICT DO NOTHING
				""").param("id", UUID.randomUUID()).param("userId", userId).param("phone", phoneE164)
				.param("now", Timestamp.from(now)).update();
	}

	private static UserAccount map(ResultSet rs, int row) throws SQLException {
		return new UserAccount(rs.getObject("id", UUID.class), rs.getString("account_type"),
				rs.getString("account_status"), rs.getString("phone_e164"), instant(rs, "phone_verified_at"),
				rs.getString("display_name"), rs.getString("ui_locale"), rs.getString("time_zone"),
				instant(rs, "created_at"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp t = rs.getTimestamp(column);
		return t == null ? null : t.toInstant();
	}
}
