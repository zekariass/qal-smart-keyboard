package com.qalsmartkeyboard.devices;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request and response bodies of {@code /devices} (contract schemas of the same names). */
final class DeviceDtos {

	private DeviceDtos() {
	}

	/** {@code DeviceRegistration}. The push token is write-only: never returned, never printed. */
	record DeviceRegistrationRequest(@NotNull UUID installationId,
			@NotNull @Pattern(regexp = "^(ANDROID|IOS|WEB)$") String platform, @Size(max = 160) String deviceName,
			@Size(max = 40) String appVersion, @Size(max = 80) String osVersion,
			@Pattern(regexp = "^(FCM|APNS)$") String pushProvider, @Size(max = 4096) String pushToken) {

		@Override
		public String toString() {
			return "DeviceRegistrationRequest[installationId=" + installationId + ", platform=" + platform + "]";
		}
	}

	/** {@code DeviceUpdate}. An empty {@code pushToken} clears the stored token. */
	record DeviceUpdateRequest(@Size(max = 160) String deviceName, @Size(max = 40) String appVersion,
			@Size(max = 80) String osVersion, @Pattern(regexp = "^(FCM|APNS)$") String pushProvider,
			@Size(max = 4096) String pushToken) {

		@Override
		public String toString() {
			return "DeviceUpdateRequest[]";
		}
	}

	/** {@code AttestationEvidence}. The token is write-only: never returned, never printed. */
	record AttestationEvidenceRequest(@NotNull @Pattern(regexp = "^(PLAY_INTEGRITY|APP_ATTEST)$") String provider,
			@NotBlank @Size(max = 16384) String token, @Size(max = 256) String keyId,
			@NotBlank @Size(max = 128) String nonce) {

		@Override
		public String toString() {
			return "AttestationEvidenceRequest[provider=" + provider + "]";
		}
	}

	/** {@code AttestationResult}. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record AttestationResultDto(String attestationStatus, Instant checkedAt, Instant validUntil) {
	}

	/** {@code Device}. Contains no push token. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record DeviceDto(UUID id, UUID installationId, String platform, String deviceName, String appVersion,
			String osVersion, String status, String attestationStatus, Instant lastSeenAt, Instant createdAt) {

		static DeviceDto of(DeviceStore.DeviceRow row) {
			return new DeviceDto(row.id(), row.installationId(), row.platform(), row.deviceName(), row.appVersion(),
					row.osVersion(), row.status(), row.attestationStatus(), row.lastSeenAt(), row.createdAt());
		}
	}

	/** Response of {@code GET /devices}: {@code items} plus {@code nextCursor} (absent at the end). */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record DeviceListDto(java.util.List<DeviceDto> items, String nextCursor) {
	}
}
