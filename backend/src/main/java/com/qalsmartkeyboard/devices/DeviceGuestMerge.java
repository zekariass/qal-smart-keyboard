package com.qalsmartkeyboard.devices;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.qalsmartkeyboard.devices.DeviceStore.DeviceRow;
import com.qalsmartkeyboard.identity.GuestMergeParticipant;

/**
 * Guest merge (B2.6, ADR 0010) for {@code user_devices}: the guest's installations become the target's, with their
 * attestation verdicts (the verdict belongs to the installation, and the person signing in is using it).
 *
 * <ul>
 * <li>An installation the target does not have yet: the row is rebound ({@code user_id} changes, id, verdict, push
 * token and versions stay), so the sign-in's own device registration, which runs after the merge, finds it.</li>
 * <li>An installation the target already has (unique {@code (user_id, installation_id)}): <b>the target's row wins</b>.
 * It takes the guest row's verdict only if that is a real verdict, newer, for the same platform and the target row is
 * ACTIVE; the guest row is revoked (push token dropped) and stays with the merged guest.</li>
 * </ul>
 * Each installation is taken under the same advisory lock as a registration ({@code qal.device:<user>:<installation>}),
 * so a concurrent registration of the target cannot race the move. The guest's sessions were already revoked by
 * identity.
 */
class DeviceGuestMerge implements GuestMergeParticipant {

	private static final Logger log = LoggerFactory.getLogger(DeviceGuestMerge.class);

	private final DeviceStore store;

	DeviceGuestMerge(DeviceStore store) {
		this.store = store;
	}

	@Override
	public int mergeOrder() {
		return DEVICES;
	}

	@Override
	public void mergeGuest(UUID guestUserId, UUID targetUserId, Instant now) {
		int moved = 0;
		int kept = 0;
		for (DeviceRow device : store.allOfUserForUpdate(guestUserId)) {
			store.lockInstallation(targetUserId, device.installationId());
			DeviceRow existing = store.findByInstallationForUpdate(targetUserId, device.installationId()).orElse(null);
			if (existing == null) {
				store.moveToUser(device.id(), guestUserId, targetUserId, now);
				moved++;
			}
			else {
				store.adoptNewerAttestation(existing.id(), device.id(), now);
				if (!"REVOKED".equals(device.status())) {
					store.markRevoked(device.id(), now);
				}
				kept++;
			}
		}
		if (moved + kept > 0) {
			log.info("Guest merge {} -> {}: {} device(s) moved, {} already registered by the target", guestUserId,
					targetUserId, moved, kept);
		}
	}
}
