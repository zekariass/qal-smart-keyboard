package com.qalsmartkeyboard.identity.session;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.qalsmartkeyboard.identity.account.UserAccount;

/** {@code AuthSession} of the contract (response of OTP verify and refresh). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthSessionDto(String tokenType, String accessToken, Instant accessTokenExpiresAt, String refreshToken,
		Instant refreshTokenExpiresAt, UUID deviceId, @JsonProperty("isNewUser") Boolean isNewUser,
		UUID mergedGuestUserId, UserProfileDto user) {

	public static AuthSessionDto of(IssuedSession session, UserAccount user, Boolean isNewUser) {
		return of(session, user, isNewUser, null);
	}

	/** @param mergedGuestUserId the guest account linked by this sign-in (B2.6), or {@code null} */
	public static AuthSessionDto of(IssuedSession session, UserAccount user, Boolean isNewUser, UUID mergedGuestUserId) {
		return new AuthSessionDto("Bearer", session.accessToken(), session.accessTokenExpiresAt(),
				session.refreshToken(), session.refreshTokenExpiresAt(), session.deviceId(), isNewUser,
				mergedGuestUserId, UserProfileDto.of(user));
	}

	/** Never prints the tokens. */
	@Override
	public String toString() {
		return "AuthSessionDto[user=" + (user == null ? null : user.id()) + ", isNewUser=" + isNewUser + "]";
	}
}
