package com.qalsmartkeyboard.identity;

import java.util.UUID;

/**
 * What a client tells the server about its installation (the contract's {@code DeviceRegistration}), as identity hands
 * it to the {@code devices} module through {@link DeviceRegistrar}. Values are client-asserted and unvalidated here.
 *
 * @param installationId random per-install id (not a hardware identifier)
 * @param platform       {@code ANDROID}, {@code IOS} or {@code WEB}
 * @param pushProvider   {@code FCM} or {@code APNS}; {@code null} derives it from the platform when a token is sent
 * @param pushToken      write-only; never logged or returned
 */
public record DeviceInfo(UUID installationId, String platform, String deviceName, String appVersion, String osVersion,
		String pushProvider, String pushToken) {

	/** Devices exist for the native platforms only ({@code user_devices.platform}). */
	public boolean isNative() {
		return "ANDROID".equals(platform) || "IOS".equals(platform);
	}

	/** Never prints the push token or the device name. */
	@Override
	public String toString() {
		return "DeviceInfo[installationId=" + installationId + ", platform=" + platform + "]";
	}
}
