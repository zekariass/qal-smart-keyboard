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

	/** Result of an external-identity (Google, later Apple) sign-in after the ID token was verified. */
	public sealed interface IdentitySignIn {

		/** @param created whether the account was created by this sign-in */
		record SignedIn(UserAccount user, boolean created) implements IdentitySignIn {
		}

		/** No account exists for the identity and the request did not confirm the minimum age (D26). Nothing was written. */
		record AgeConfirmationRequired() implements IdentitySignIn {
		}

		/** The account exists but is {@code SUSPENDED}. */
		record Suspended() implements IdentitySignIn {
		}

		/** The identity belongs to a {@code DELETED} or {@code MERGED} account that can no longer sign in. */
		record Unavailable() implements IdentitySignIn {
		}
	}

	/**
	 * The effective status: {@code DELETED} as soon as {@code deleted_at} is set, which the account deletion executor
	 * does when it starts (B6.2, ADR 0015) while the row is still {@code DELETION_PENDING}. So a sign-in or refresh in
	 * that window is refused like one of a deleted account.
	 */
	private static final String USER_COLUMNS = "u.id, u.account_type, "
			+ "CASE WHEN u.deleted_at IS NOT NULL THEN 'DELETED' ELSE u.account_status END AS account_status, "
			+ "u.phone_e164, u.phone_verified_at, u.display_name, u.ui_locale, u.time_zone, u.created_at, u.email, "
			+ "u.email_verified_at";

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
		return signInWithPhone(phoneE164, ageConfirmed, now, null);
	}

	/**
	 * As {@link #signInWithPhone(String, boolean, Instant)}, but when no account exists for the number and
	 * {@code guestToUpgrade} is set, that guest account is <b>upgraded in place</b> (B2.6, ADR 0010) instead of a new
	 * account being created: it becomes {@code REGISTERED} with the verified number and the {@code PHONE} identity, and
	 * keeps its id and every row that belongs to it. {@code SignedIn.created} is then {@code true} (a registered account
	 * was created by this sign-in). The caller has locked the guest row and checked that it is an active guest.
	 *
	 * @param guestToUpgrade the caller's mergeable guest account, or {@code null}
	 */
	public PhoneSignIn signInWithPhone(String phoneE164, boolean ageConfirmed, Instant now, UUID guestToUpgrade) {
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
		if (guestToUpgrade != null) {
			UserAccount upgraded = jdbc.sql("""
					UPDATE users u SET account_type = 'REGISTERED', phone_e164 = :phone, phone_verified_at = :now,
					       age_confirmed_at = COALESCE(u.age_confirmed_at, :now), last_login_at = :now, updated_at = :now
					WHERE u.id = :id AND u.account_type = 'GUEST' AND u.account_status = 'ACTIVE' AND u.deleted_at IS NULL
					RETURNING """ + " " + USER_COLUMNS).param("phone", phoneE164).param("now", Timestamp.from(now))
					.param("id", guestToUpgrade).query(UserAccountStore::map).optional()
					.orElseThrow(UserAccountStore::guestChanged);
			insertPhoneIdentity(upgraded.id(), phoneE164, now);
			return new PhoneSignIn.SignedIn(upgraded, true);
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

	/**
	 * Finds the account of a verified external identity <b>by provider and subject only</b>, or creates a
	 * {@code REGISTERED}, {@code ACTIVE} account with that identity. An e-mail address is never used to find or link an
	 * account: a provider's e-mail can match a different person's phone or e-mail account, and linking by it would be an
	 * account takeover. Only {@code provider_email} (verified e-mails only, {@code null} otherwise) is kept on the
	 * identity row; {@code users.email} is not set. The caller serialises sign-ins of one identity (advisory lock), and
	 * must run in a transaction.
	 *
	 * @param provider    {@code GOOGLE} or {@code APPLE}
	 * @param subject     the issuer's stable {@code sub}
	 * @param verifiedEmail the e-mail if the provider asserts it is verified, else {@code null}
	 * @param displayName used for a <em>new</em> account only (never overwrites), may be {@code null}
	 */
	public IdentitySignIn signInWithIdentity(String provider, String subject, String verifiedEmail, String displayName,
			boolean ageConfirmed, Instant now) {
		return signInWithIdentity(provider, subject, verifiedEmail, displayName, ageConfirmed, now, null);
	}

	/**
	 * As {@link #signInWithIdentity(String, String, String, String, boolean, Instant)}, but when no account exists for
	 * the identity and {@code guestToUpgrade} is set, that guest account is <b>upgraded in place</b> (B2.6, ADR 0010):
	 * it becomes {@code REGISTERED} with the identity (and the display name if it has none) and keeps its id and rows.
	 * {@code SignedIn.created} is then {@code true}. The caller has locked the guest row and checked it.
	 *
	 * @param guestToUpgrade the caller's mergeable guest account, or {@code null}
	 */
	public IdentitySignIn signInWithIdentity(String provider, String subject, String verifiedEmail, String displayName,
			boolean ageConfirmed, Instant now, UUID guestToUpgrade) {
		Optional<UserAccount> existing = jdbc.sql("SELECT " + USER_COLUMNS + """
				 FROM user_auth_identities i JOIN users u ON u.id = i.user_id
				WHERE i.identity_provider = :provider AND i.provider_subject = :subject
				""").param("provider", provider).param("subject", subject).query(UserAccountStore::map).optional();
		if (existing.isPresent()) {
			UserAccount user = existing.get();
			switch (user.accountStatus()) {
				case "SUSPENDED":
					return new IdentitySignIn.Suspended();
				case "DELETED", "MERGED":
					return new IdentitySignIn.Unavailable();
				default:
					break;
			}
			jdbc.sql("""
					UPDATE user_auth_identities SET last_used_at = :now, provider_email = COALESCE(:email, provider_email)
					WHERE identity_provider = :provider AND provider_subject = :subject
					""").param("now", Timestamp.from(now)).param("email", verifiedEmail).param("provider", provider)
					.param("subject", subject).update();
			UserAccount updated = jdbc.sql("""
					UPDATE users u SET last_login_at = :now, updated_at = :now,
					       account_status = CASE WHEN u.account_status = 'PENDING' THEN 'ACTIVE' ELSE u.account_status END
					WHERE u.id = :id
					RETURNING """ + " " + USER_COLUMNS).param("now", Timestamp.from(now)).param("id", user.id())
					.query(UserAccountStore::map).single();
			return new IdentitySignIn.SignedIn(updated, false);
		}
		if (!ageConfirmed) {
			return new IdentitySignIn.AgeConfirmationRequired();
		}
		UserAccount created;
		if (guestToUpgrade != null) {
			created = jdbc.sql("""
					UPDATE users u SET account_type = 'REGISTERED', display_name = COALESCE(u.display_name, :displayName),
					       age_confirmed_at = COALESCE(u.age_confirmed_at, :now), last_login_at = :now, updated_at = :now
					WHERE u.id = :id AND u.account_type = 'GUEST' AND u.account_status = 'ACTIVE' AND u.deleted_at IS NULL
					RETURNING """ + " " + USER_COLUMNS).param("displayName", displayName)
					.param("now", Timestamp.from(now)).param("id", guestToUpgrade).query(UserAccountStore::map)
					.optional().orElseThrow(UserAccountStore::guestChanged);
		}
		else {
			created = jdbc.sql("""
					INSERT INTO users AS u (id, account_type, display_name, account_status, age_confirmed_at,
					                        last_login_at, created_at, updated_at)
					VALUES (:id, 'REGISTERED', :displayName, 'ACTIVE', :now, :now, :now, :now)
					RETURNING """ + " " + USER_COLUMNS).param("id", UUID.randomUUID())
					.param("displayName", displayName).param("now", Timestamp.from(now))
					.query(UserAccountStore::map).single();
		}
		UUID userId = created.id();
		jdbc.sql("""
				INSERT INTO user_auth_identities (id, user_id, identity_provider, provider_subject, provider_email,
				                                  created_at, last_used_at)
				VALUES (:id, :userId, :provider, :subject, :email, :now, :now)
				""").param("id", UUID.randomUUID()).param("userId", userId).param("provider", provider)
				.param("subject", subject).param("email", verifiedEmail).param("now", Timestamp.from(now)).update();
		return new IdentitySignIn.SignedIn(created, true);
	}

	/**
	 * Creates the {@code REGISTERED}, {@code ACTIVE} account of an e-mail sign-up with its {@code EMAIL} identity
	 * (subject = the normalised address) and records the login (ADR 0033). The password credential is the caller's. With
	 * {@code guestToUpgrade} the guest row itself becomes the account (B2.6, ADR 0010), as for phone and Google; the
	 * caller has locked it. The caller holds the per-address lock and has checked that no live account has the address
	 * (V1 {@code ux_users_email_normalized} is the backstop).
	 *
	 * @param emailVerifiedAt when the mailbox was proven, {@code null} for an unverified sign-up
	 */
	public UserAccount createEmailAccount(String email, String emailNormalized, String displayName,
			Instant emailVerifiedAt, Instant now, UUID guestToUpgrade) {
		UserAccount created;
		Timestamp verified = emailVerifiedAt == null ? null : Timestamp.from(emailVerifiedAt);
		if (guestToUpgrade != null) {
			created = jdbc.sql("""
					UPDATE users u SET account_type = 'REGISTERED', email = :email, email_normalized = :normalized,
					       email_verified_at = :verified, display_name = COALESCE(u.display_name, :displayName),
					       age_confirmed_at = COALESCE(u.age_confirmed_at, :now), last_login_at = :now, updated_at = :now
					WHERE u.id = :id AND u.account_type = 'GUEST' AND u.account_status = 'ACTIVE' AND u.deleted_at IS NULL
					RETURNING """ + " " + USER_COLUMNS).param("email", email).param("normalized", emailNormalized)
					.param("verified", verified).param("displayName", displayName).param("now", Timestamp.from(now))
					.param("id", guestToUpgrade).query(UserAccountStore::map).optional()
					.orElseThrow(UserAccountStore::guestChanged);
		}
		else {
			created = jdbc.sql("""
					INSERT INTO users AS u (id, account_type, email, email_normalized, email_verified_at, display_name,
					                        account_status, age_confirmed_at, last_login_at, created_at, updated_at)
					VALUES (:id, 'REGISTERED', :email, :normalized, :verified, :displayName, 'ACTIVE', :now, :now, :now,
					        :now)
					RETURNING """ + " " + USER_COLUMNS).param("id", UUID.randomUUID()).param("email", email)
					.param("normalized", emailNormalized).param("verified", verified).param("displayName", displayName)
					.param("now", Timestamp.from(now)).query(UserAccountStore::map).single();
		}
		jdbc.sql("""
				INSERT INTO user_auth_identities (id, user_id, identity_provider, provider_subject, provider_email,
				                                  created_at, last_used_at)
				VALUES (:id, :userId, 'EMAIL', :subject, :email, :now, :now)
				""").param("id", UUID.randomUUID()).param("userId", created.id()).param("subject", emailNormalized)
				.param("email", email).param("now", Timestamp.from(now)).update();
		return created;
	}

	/**
	 * Records an e-mail sign-in of an existing account (ADR 0033): last login, the {@code EMAIL} identity's last use (the
	 * identity row is added if an older row lacks it), {@code PENDING} becomes {@code ACTIVE}, and, with
	 * {@code verifiedNow}, the address counts as verified from now on.
	 */
	public UserAccount recordEmailSignIn(UUID userId, boolean verifiedNow, Instant now) {
		UserAccount updated = jdbc.sql("""
				UPDATE users u SET last_login_at = :now, updated_at = :now,
				       email_verified_at = CASE WHEN :verify THEN COALESCE(u.email_verified_at, :now)
				                                ELSE u.email_verified_at END,
				       account_status = CASE WHEN u.account_status = 'PENDING' THEN 'ACTIVE' ELSE u.account_status END
				WHERE u.id = :id
				RETURNING """ + " " + USER_COLUMNS).param("now", Timestamp.from(now)).param("verify", verifiedNow)
				.param("id", userId).query(UserAccountStore::map).single();
		if (updated.email() != null) {
			String normalized = updated.email().toLowerCase(java.util.Locale.ROOT);
			jdbc.sql("""
					INSERT INTO user_auth_identities (id, user_id, identity_provider, provider_subject, provider_email,
					                                  created_at, last_used_at)
					VALUES (:id, :userId, 'EMAIL', :subject, :email, :now, :now)
					ON CONFLICT (user_id, identity_provider) DO UPDATE SET last_used_at = EXCLUDED.last_used_at
					""").param("id", UUID.randomUUID()).param("userId", userId).param("subject", normalized)
					.param("email", updated.email()).param("now", Timestamp.from(now)).update();
		}
		return updated;
	}

	/**
	 * Marks the address of an account as verified (a password reset proves control of the mailbox, ADR 0033) and
	 * activates a {@code PENDING} account. Does not record a login.
	 */
	public void markEmailVerified(UUID userId, Instant now) {
		jdbc.sql("""
				UPDATE users u SET email_verified_at = COALESCE(u.email_verified_at, :now), updated_at = :now,
				       account_status = CASE WHEN u.account_status = 'PENDING' THEN 'ACTIVE' ELSE u.account_status END
				WHERE u.id = :id
				""").param("now", Timestamp.from(now)).param("id", userId).update();
	}

	/**
	 * Creates an anonymous {@code GUEST} account (SD 9.1): ACTIVE, no email, phone or identity row (V1
	 * {@code ck_users_guest_has_no_identity}), no age confirmation (it has no personal data and no purchases). Its plan is
	 * {@code GUEST}, resolved from the account type (SD 15.5), so no subscription row is written.
	 */
	public UserAccount createGuest(Instant now) {
		return jdbc.sql("""
				INSERT INTO users AS u (id, account_type, account_status, last_login_at, created_at, updated_at)
				VALUES (:id, 'GUEST', 'ACTIVE', :now, :now, :now)
				RETURNING """ + " " + USER_COLUMNS).param("id", UUID.randomUUID()).param("now", Timestamp.from(now))
				.query(UserAccountStore::map).single();
	}

	/**
	 * The account's type and effective status, unlocked (one primary-key lookup). The security chain reads it for every
	 * access token (ADR 0010 for guests, ADR 0015 for closed accounts).
	 *
	 * @return empty if there is no such user
	 */
	public Optional<AccountState> state(UUID userId) {
		return jdbc.sql("""
				SELECT account_type, CASE WHEN deleted_at IS NOT NULL THEN 'DELETED' ELSE account_status END AS status
				FROM users WHERE id = :id
				""").param("id", userId)
				.query((rs, row) -> new AccountState(rs.getString("account_type"), rs.getString("status")))
				.optional();
	}

	/** {@code account_type} and effective {@code account_status} ({@code DELETED} when {@code deleted_at} is set). */
	public record AccountState(String accountType, String accountStatus) {

		public boolean isActiveGuest() {
			return "GUEST".equals(accountType) && "ACTIVE".equals(accountStatus);
		}

		/**
		 * Whether a <b>guest</b> access token of this account is honoured: an {@code ACTIVE} guest, or one whose deletion
		 * is pending (it keeps its session during the grace period so it can cancel, ADR 0015; it can no longer be
		 * merged, which needs {@link #isActiveGuest()}).
		 */
		public boolean acceptsGuestToken() {
			return "GUEST".equals(accountType)
					&& ("ACTIVE".equals(accountStatus) || "DELETION_PENDING".equals(accountStatus));
		}

		/** {@code DELETED} (including a deletion in progress) or {@code MERGED}: no token of the account is honoured. */
		public boolean isClosed() {
			return "DELETED".equals(accountStatus) || "MERGED".equals(accountStatus);
		}
	}

	/**
	 * Locks a {@code users} row for the rest of the transaction with {@code FOR NO KEY UPDATE}. That serialises merges
	 * and sign-ins of the account but, unlike {@code FOR UPDATE}, does not block foreign-key checks of rows that other
	 * transactions insert for the account (such as the data key that {@code platform.crypto} creates in its own
	 * transaction while a merge re-encrypts data for the target).
	 *
	 * @return the locked row's state, empty if there is no such user
	 */
	public Optional<AccountState> lockForMerge(UUID userId) {
		return jdbc.sql("""
				SELECT account_type, CASE WHEN deleted_at IS NOT NULL THEN 'DELETED' ELSE account_status END AS status
				FROM users WHERE id = :id FOR NO KEY UPDATE
				""").param("id", userId)
				.query((rs, row) -> new AccountState(rs.getString("account_type"), rs.getString("status")))
				.optional();
	}

	/**
	 * Marks an active guest as merged into {@code targetUserId}. The row stays a {@code GUEST} (V1
	 * {@code ck_users_merge_only_guest}); a {@code MERGED} account can never sign in again and its tokens are refused.
	 *
	 * @return whether the row was changed
	 */
	public boolean markMerged(UUID guestUserId, UUID targetUserId, Instant now) {
		return jdbc.sql("""
				UPDATE users SET account_status = 'MERGED', merged_into_user_id = :target, updated_at = :now
				WHERE id = :id AND account_type = 'GUEST' AND account_status = 'ACTIVE'
				""").param("target", targetUserId).param("now", Timestamp.from(now)).param("id", guestUserId)
				.update() == 1;
	}

	private static IllegalStateException guestChanged() {
		// unreachable while the caller holds the guest row lock (GuestMergeService.lockMergeableGuest)
		return new IllegalStateException("The guest account changed during sign-in");
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
				instant(rs, "created_at"), rs.getString("email"), instant(rs, "email_verified_at"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp t = rs.getTimestamp(column);
		return t == null ? null : t.toInstant();
	}
}
