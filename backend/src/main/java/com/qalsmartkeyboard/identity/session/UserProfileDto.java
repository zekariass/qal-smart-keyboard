package com.qalsmartkeyboard.identity.session;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.qalsmartkeyboard.identity.account.UserAccount;

/**
 * {@code UserProfile} of the contract. {@code marketCountry}/{@code marketSource} are not filled yet: the contract's
 * {@code marketSource} values do not match the {@code users.market_source} CHECK list (reported with B2.1).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserProfileDto(UUID id, String accountType, String accountStatus, String displayName,
		String phoneNumberMasked, Boolean phoneVerified, String uiLocale, String timeZone, Instant createdAt) {

	public static UserProfileDto of(UserAccount user) {
		return new UserProfileDto(user.id(), user.accountType(), user.accountStatus(), user.displayName(),
				maskPhone(user.phoneE164()), user.phoneE164() == null ? null : user.phoneVerifiedAt() != null,
				user.uiLocale(), user.timeZone(), user.createdAt());
	}

	/**
	 * Contract example {@code +251 9•• ••• 567}: the first four characters (country code for +251), the first digit
	 * of an Ethiopian number, and the last three digits.
	 */
	static String maskPhone(String e164) {
		if (e164 == null || e164.length() < 8) {
			return null;
		}
		String last = e164.substring(e164.length() - 3);
		if (e164.startsWith("+251") && e164.length() == 13) {
			return "+251 " + e164.charAt(4) + "•• ••• " + last;
		}
		return e164.substring(0, 4) + " " + "•".repeat(e164.length() - 7) + " " + last;
	}
}
