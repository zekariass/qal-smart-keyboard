package com.qalsmartkeyboard.identity;

import java.util.UUID;

/**
 * Port that identity uses to register the installation of a signing-in user. Implemented by the {@code devices}
 * module, which owns {@code user_devices} (identity has no table access to it, and devices depends on identity, never
 * the other way round). Runs inside the caller's transaction, so the device row, the account and the session commit
 * together.
 */
public interface DeviceRegistrar {

	/**
	 * Registers or refreshes the installation for the user (idempotent per user and installation id; a revoked device
	 * becomes active again, with its attestation reset).
	 *
	 * @param attestation a verdict to record on the row, or {@code null}
	 * @return {@code user_devices.id}
	 * @throws com.qalsmartkeyboard.platform.ApiException 400 {@code VALIDATION_FAILED} for an unusable registration
	 */
	UUID register(UUID userId, DeviceInfo device, DeviceAttestation attestation);
}
