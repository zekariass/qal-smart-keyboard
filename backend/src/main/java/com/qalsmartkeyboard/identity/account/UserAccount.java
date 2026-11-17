package com.qalsmartkeyboard.identity.account;

import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code users} as the identity module needs it for sign-in and the {@code UserProfile} response. The phone
 * number never leaves the module except masked.
 */
public record UserAccount(UUID id, String accountType, String accountStatus, String phoneE164,
		Instant phoneVerifiedAt, String displayName, String uiLocale, String timeZone, Instant createdAt) {

	/** Never prints the phone number. */
	@Override
	public String toString() {
		return "UserAccount[id=" + id + ", accountType=" + accountType + ", accountStatus=" + accountStatus + "]";
	}
}
