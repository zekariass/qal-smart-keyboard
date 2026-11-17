package com.qalsmartkeyboard.identity.otp;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.qalsmartkeyboard.identity.session.DeviceRegistrationDto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Request and response bodies of {@code /auth/otp/*} (contract schemas of the same names). */
final class OtpDtos {

	private OtpDtos() {
	}

	/**
	 * {@code OtpStartRequest}. {@code attestation} is accepted by the contract but not used before B2.3 (unknown members
	 * are ignored). {@code VOICE_CALL} is accepted and delivered as SMS: no voice provider exists yet. The optional
	 * {@code installationId} (same UUID as {@code device.installationId} on verify) keys the per-device start limit
	 * (ADR 0007 D9).
	 */
	record OtpStartRequest(@NotNull @Pattern(regexp = "^\\+[1-9][0-9]{6,14}$") String phoneNumber,
			@Pattern(regexp = "^(SMS|VOICE_CALL)$") String channel, UUID installationId) {

		@Override
		public String toString() {
			return "OtpStartRequest[phoneNumber=<redacted>, channel=" + channel + ", installationId=" + installationId
					+ "]";
		}
	}

	/** {@code OtpChallenge}. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record OtpChallengeDto(UUID challengeId, Instant expiresAt, Instant resendAvailableAt, int codeLength) {
	}

	/** {@code OtpVerifyRequest}. */
	record OtpVerifyRequest(@NotNull UUID challengeId, @NotNull @Pattern(regexp = "^[0-9]{4,8}$") String code,
			Boolean ageConfirmed, @Valid DeviceRegistrationDto device) {

		@Override
		public String toString() {
			return "OtpVerifyRequest[challengeId=" + challengeId + ", code=<redacted>, ageConfirmed=" + ageConfirmed
					+ "]";
		}
	}
}
