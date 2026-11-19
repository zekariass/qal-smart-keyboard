package com.qalsmartkeyboard.identity.session;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.qalsmartkeyboard.identity.account.UserAccount;

/**
 * {@code UserProfile} of the contract. {@code marketCountry}/{@code marketSource} are not filled yet: nothing sets
 * {@code users.market_country_code}/{@code market_source} before market resolution exists. The contract's
 * {@code marketSource} enum now equals the {@code users.market_source} CHECK list (ADR 0007 D9), so they can be mapped
 * one to one when it does.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserProfileDto(UUID id, String accountType, String accountStatus, String displayName, String email,
		Boolean emailVerified, String phoneNumberMasked, Boolean phoneVerified, String uiLocale, String timeZone,
		Instant createdAt) {

	/** {@code email}/{@code emailVerified} only for an account with its own address (e-mail sign-in, ADR 0033). */
	public static UserProfileDto of(UserAccount user) {
		return new UserProfileDto(user.id(), user.accountType(), user.accountStatus(), user.displayName(), user.email(),
				user.email() == null ? null : user.emailVerifiedAt() != null, maskPhone(user.phoneE164()),
				user.phoneE164() == null ? null : user.phoneVerifiedAt() != null, user.uiLocale(), user.timeZone(),
				user.createdAt());
	}

	/** Never prints the address. */
	@Override
	public String toString() {
		return "UserProfileDto[id=" + id + ", accountType=" + accountType + "]";
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
