package com.qalsmartkeyboard.identity.otp;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request and response bodies of {@code /auth/otp/*} (contract schemas of the same names). */
final class OtpDtos {

	private OtpDtos() {
	}

	/**
	 * {@code OtpStartRequest}. {@code attestation} is accepted by the contract but not used before B2.3 (unknown members
	 * are ignored). {@code VOICE_CALL} is accepted and delivered as SMS: no voice provider exists yet.
	 */
	record OtpStartRequest(@NotNull @Pattern(regexp = "^\\+[1-9][0-9]{6,14}$") String phoneNumber,
			@Pattern(regexp = "^(SMS|VOICE_CALL)$") String channel) {

		@Override
		public String toString() {
			return "OtpStartRequest[phoneNumber=<redacted>, channel=" + channel + "]";
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

	/**
	 * {@code DeviceRegistration}. Only {@code installationId} is used before B2.5 (per-device verify limit); device rows
	 * are registered by the devices module in B2.5.
	 */
	record DeviceRegistrationDto(@NotNull UUID installationId,
			@NotNull @Pattern(regexp = "^(ANDROID|IOS|WEB)$") String platform, @Size(max = 160) String deviceName,
			@Size(max = 40) String appVersion, @Size(max = 80) String osVersion,
			@Pattern(regexp = "^(FCM|APNS)$") String pushProvider, @Size(max = 4096) String pushToken) {

		@Override
		public String toString() {
			return "DeviceRegistrationDto[installationId=" + installationId + ", platform=" + platform + "]";
		}
	}
}
