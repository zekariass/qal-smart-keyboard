package com.qalsmartkeyboard.identity.account;

import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code users} as the identity module needs it for sign-in and the {@code UserProfile} response. The phone
 * number never leaves the module except masked; the e-mail
 * address is the account's own (e-mail sign-in, ADR 0033).
 */
public record UserAccount(UUID id, String accountType, String accountStatus, String phoneE164,
		Instant phoneVerifiedAt, String displayName, String uiLocale, String timeZone, Instant createdAt, String email,
		Instant emailVerifiedAt) {

	/** An account without an e-mail address. */
	public UserAccount(UUID id, String accountType, String accountStatus, String phoneE164, Instant phoneVerifiedAt,
			String displayName, String uiLocale, String timeZone, Instant createdAt) {
		this(id, accountType, accountStatus, phoneE164, phoneVerifiedAt, displayName, uiLocale, timeZone, createdAt,
				null, null);
	}

	/** Never prints the phone number or the e-mail address. */
	@Override
	public String toString() {
		return "UserAccount[id=" + id + ", accountType=" + accountType + ", accountStatus=" + accountStatus + "]";
	}
}
