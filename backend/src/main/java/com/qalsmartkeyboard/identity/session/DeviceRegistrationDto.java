package com.qalsmartkeyboard.identity.session;

import java.util.UUID;

import com.qalsmartkeyboard.identity.DeviceInfo;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code DeviceRegistration} as it appears in the sign-in requests (OTP verify, OAuth exchange). {@code installationId}
 * keys the per-device limits; a native device is also registered by the devices module while the session is issued
 * (B2.5), so the session carries the device. The push token is write-only: never returned, never printed.
 */
public record DeviceRegistrationDto(@NotNull UUID installationId,
		@NotNull @Pattern(regexp = "^(ANDROID|IOS|WEB)$") String platform, @Size(max = 160) String deviceName,
		@Size(max = 40) String appVersion, @Size(max = 80) String osVersion,
		@Pattern(regexp = "^(FCM|APNS)$") String pushProvider, @Size(max = 4096) String pushToken) {

	/** {@code appVersion} falls back to the {@code X-App-Version} header. */
	public DeviceInfo toDeviceInfo(String appVersionHeader) {
		return new DeviceInfo(installationId, platform, deviceName, appVersion != null ? appVersion : appVersionHeader,
				osVersion, pushProvider, pushToken);
	}

	@Override
	public String toString() {
		return "DeviceRegistrationDto[installationId=" + installationId + ", platform=" + platform + "]";
	}
}
