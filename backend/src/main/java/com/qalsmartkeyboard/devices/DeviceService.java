package com.qalsmartkeyboard.devices;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.devices.DeviceDtos.AttestationResultDto;
import com.qalsmartkeyboard.devices.DeviceDtos.DeviceDto;
import com.qalsmartkeyboard.devices.DeviceStore.DeviceRow;
import com.qalsmartkeyboard.identity.AttestationChecker;
import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.identity.DeviceAttestation;
import com.qalsmartkeyboard.identity.DeviceInfo;
import com.qalsmartkeyboard.identity.DeviceRegistrar;
import com.qalsmartkeyboard.identity.DeviceSessions;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.ApiFieldError;
import com.qalsmartkeyboard.platform.outbox.OutboxPublisher;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Devices (SD 9.4): register (idempotent per user and installation), list, update (versions, push token), revoke.
 *
 * <ul>
 * <li><b>Ownership:</b> every lookup is by device id <em>and</em> the caller's user id. Another user's device, an
 * unknown id and (for updates) a revoked device are all the same 404 {@code NOT_FOUND}; nothing reveals that a device
 * id exists.</li>
 * <li><b>Sessions:</b> a registration through {@code POST /devices} binds the caller's session family to the device if
 * it has none yet (the next refreshed access token carries {@code did}); revoking a device ends every session family
 * bound to it in the same transaction, so its refresh tokens stop working at once (access tokens live on for at most
 * their 15 minute lifetime).</li>
 * <li><b>Push token:</b> stored as given in {@code user_devices.push_token} (V1 keeps it plain: a unique index must
 * recognise a token that moves between accounts, and it is a delivery address, not user content). It is write-only
 * in the API, never logged, and dropped with the device on revocation. A token already held by another ACTIVE device
 * moves to this one.</li>
 * <li><b>Platform:</b> {@code ANDROID} or {@code IOS} (V1 CHECK); {@code WEB} has no devices. An installation never
 * changes platform: registering it again with another platform is a 400.</li>
 * </ul>
 */
class DeviceService implements DeviceRegistrar {

	private static final Logger log = LoggerFactory.getLogger(DeviceService.class);
	static final int MAX_PAGE = 100;
	static final int DEFAULT_PAGE = 20;
	static final String REVOKED_EVENT = "devices.device.revoked";

	private final DeviceStore store;
	private final DeviceSessions sessions;
	private final AttestationChecker attestations;
	private final OutboxPublisher outbox;
	private final TransactionTemplate transactions;
	private final Clock clock;
	private final MeterRegistry meters;

	DeviceService(DeviceStore store, DeviceSessions sessions, AttestationChecker attestations, OutboxPublisher outbox,
			TransactionTemplate transactions, Clock clock, MeterRegistry meters) {
		this.store = store;
		this.sessions = sessions;
		this.attestations = attestations;
		this.outbox = outbox;
		this.transactions = transactions;
		this.clock = clock;
		this.meters = meters;
	}

	// ------------------------------------------------------------------ register

	/** {@code POST /devices}: register or refresh the installation and bind the caller's session to it. */
	DeviceDto register(AuthenticatedUser caller, DeviceInfo info) {
		return transactions.execute(status -> {
			UUID deviceId = register(caller.userId(), info, null);
			if (caller.deviceId() == null && sessions.bindDeviceToSession(caller.userId(), caller.sessionId(), deviceId)) {
				log.debug("Session family {} bound to device {}", caller.sessionId(), deviceId);
			}
			return DeviceDto.of(store.findOwned(caller.userId(), deviceId).orElseThrow());
		});
	}

	@Override
	public UUID register(UUID userId, DeviceInfo info, DeviceAttestation attestation) {
		validatePlatform(info);
		String pushProvider = pushProvider(info.platform(), info.pushProvider(), info.pushToken());
		return transactions.execute(status -> {
			store.lockInstallation(userId, info.installationId());
			var now = clock.instant();
			DeviceRow existing = store.findByInstallationForUpdate(userId, info.installationId()).orElse(null);
			UUID id;
			boolean created = existing == null;
			if (created) {
				id = UUID.randomUUID();
				store.insert(id, userId, info.installationId(), info.platform(), info.deviceName(), info.appVersion(),
						info.osVersion(), now);
			}
			else {
				if (!existing.platform().equals(info.platform())) {
					throw invalid("platform", "PLATFORM_MISMATCH", "This installation is registered for another platform.");
				}
				id = existing.id();
				store.refresh(id, info.deviceName(), info.appVersion(), info.osVersion(), now);
			}
			if (info.pushToken() != null && !info.pushToken().isBlank()) {
				setPushToken(id, pushProvider, info.pushToken());
			}
			if (attestation != null) {
				store.recordAttestation(id, attestation.provider(), attestation.status(), attestation.checkedAt());
			}
			count(created ? "registered" : "refreshed");
			return id;
		});
	}

	@Override
	public boolean claimGuestInstallation(UUID installationId) {
		store.lockInstallationId(installationId);
		return !store.guestOwnsInstallation(installationId);
	}

	// ------------------------------------------------------------------ list, update, revoke

	/**
	 * {@code GET /devices}: one page of the caller's devices, oldest first, ACTIVE and REVOKED alike (the contract's
	 * {@code status} tells them apart; a client shows revoked installations as such). Only rows of {@code userId} are
	 * ever read. The cursor is an opaque keyset position ({@code created_at}, {@code id}) and carries no user data; a
	 * malformed one is a 400 {@code VALIDATION_FAILED}.
	 */
	DevicePage list(UUID userId, String cursor, int limit) {
		if (limit < 1 || limit > MAX_PAGE) {
			throw invalid("limit", "OUT_OF_RANGE", "limit must be between 1 and " + MAX_PAGE + ".");
		}
		Instant afterCreatedAt = null;
		UUID afterId = null;
		if (cursor != null) {
			try {
				String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", 3);
				afterCreatedAt = Instant.ofEpochSecond(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
				afterId = UUID.fromString(parts[2]);
			}
			catch (RuntimeException e) {
				throw invalid("cursor", "INVALID", "The cursor is not valid.");
			}
		}
		List<DeviceRow> rows = store.listOwned(userId, afterCreatedAt, afterId, limit + 1);
		boolean more = rows.size() > limit;
		List<DeviceRow> page = more ? rows.subList(0, limit) : rows;
		String next = null;
		if (more) {
			DeviceRow last = page.get(page.size() - 1);
			String position = last.createdAt().getEpochSecond() + ":" + last.createdAt().getNano() + ":" + last.id();
			next = Base64.getUrlEncoder().withoutPadding().encodeToString(position.getBytes(StandardCharsets.UTF_8));
		}
		return new DevicePage(page.stream().map(DeviceDto::of).toList(), next);
	}

	/** A page of {@code GET /devices}; {@code nextCursor} is {@code null} at the end. */
	record DevicePage(List<DeviceDto> items, String nextCursor) {
	}

	DeviceDto update(UUID userId, UUID deviceId, DeviceInfo changes) {
		return transactions.execute(status -> {
			DeviceRow row = activeOwned(userId, deviceId);
			var now = clock.instant();
			store.refresh(row.id(), changes.deviceName(), changes.appVersion(), changes.osVersion(), now);
			String token = changes.pushToken();
			String provider = pushProvider(row.platform(), changes.pushProvider(), token);
			if (token != null) {
				if (token.isBlank()) {
					store.clearPushToken(row.id());
				}
				else {
					setPushToken(row.id(), provider, token);
				}
			}
			count("updated");
			return DeviceDto.of(store.findOwned(userId, deviceId).orElseThrow());
		});
	}

	/** Idempotent: revoking a revoked device succeeds again. Unknown or foreign ids are 404. */
	void revoke(UUID userId, UUID deviceId) {
		transactions.executeWithoutResult(status -> {
			DeviceRow row = store.findOwnedForUpdate(userId, deviceId).orElseThrow(DeviceService::notFound);
			// session families first (their locks come before any row work, as everywhere), then the device row
			int revokedSessions = sessions.revokeDeviceSessions(userId, deviceId);
			if (!"REVOKED".equals(row.status())) {
				store.markRevoked(deviceId, clock.instant());
				outbox.publish("user_device", deviceId.toString(), REVOKED_EVENT,
						Map.of("userId", userId.toString(), "deviceId", deviceId.toString(), "revokedSessions",
								revokedSessions));
				count("revoked");
				log.info("Device {} revoked; {} session row(s) ended", deviceId, revokedSessions);
			}
		});
	}

	// ------------------------------------------------------------------ attestation

	/**
	 * {@code POST /devices/{id}/attestations}: checks the verdict (single-use nonce, platform's verifier; see
	 * {@link AttestationChecker}) and stores the result on the device row. The verifier is called outside any
	 * transaction. {@code UNSUPPORTED} (no verifier available) never overwrites an existing VALID or FAILED verdict.
	 */
	AttestationResultDto attest(UUID userId, UUID deviceId, AttestationChecker.Evidence evidence) {
		DeviceRow device = store.findOwned(userId, deviceId).filter(d -> "ACTIVE".equals(d.status()))
				.orElseThrow(DeviceService::notFound);
		AttestationChecker.Result result = attestations.check(userId, device.platform(), evidence);
		transactions.executeWithoutResult(status -> {
			DeviceRow row = activeOwned(userId, deviceId);
			boolean unsupported = result.status() == AttestationChecker.Status.UNSUPPORTED;
			if (!unsupported || "NOT_CHECKED".equals(row.attestationStatus())
					|| "UNSUPPORTED".equals(row.attestationStatus())) {
				store.recordAttestation(deviceId, result.provider(), result.status().name(), result.checkedAt());
			}
		});
		count("attestation_" + result.status().name().toLowerCase(java.util.Locale.ROOT));
		return new AttestationResultDto(result.status().name(), result.checkedAt(), result.validUntil());
	}

	// ------------------------------------------------------------------ helpers

	/** The caller's ACTIVE device (a revoked device cannot be changed or attested). */
	DeviceRow activeOwned(UUID userId, UUID deviceId) {
		DeviceRow row = store.findOwnedForUpdate(userId, deviceId).orElseThrow(DeviceService::notFound);
		if (!"ACTIVE".equals(row.status())) {
			throw notFound();
		}
		return row;
	}

	private void setPushToken(UUID deviceId, String provider, String token) {
		int moved = store.setPushToken(deviceId, provider, token);
		if (moved > 0) {
			log.info("A push token moved to device {} from {} other device(s)", deviceId, moved);
		}
	}

	private static void validatePlatform(DeviceInfo info) {
		if (info.installationId() == null) {
			throw invalid("installationId", "REQUIRED", "installationId is required.");
		}
		if (!info.isNative()) {
			throw invalid("platform", "UNSUPPORTED_PLATFORM", "Devices are registered for ANDROID and IOS only.");
		}
		if (info.appVersion() != null && info.appVersion().length() > 40) {
			throw invalid("appVersion", "TOO_LONG", "appVersion is at most 40 characters.");
		}
	}

	/**
	 * The push provider of a token: FCM for Android, APNS for iOS. A provider that does not match the platform is a
	 * 400 (the V1 CHECK would reject it anyway). {@code null} when no provider and no token were sent.
	 */
	static String pushProvider(String platform, String provider, String token) {
		String expected = "ANDROID".equals(platform) ? "FCM" : "IOS".equals(platform) ? "APNS" : null;
		if (provider != null && !provider.equals(expected)) {
			throw invalid("pushProvider", "PROVIDER_PLATFORM_MISMATCH", "pushProvider does not match the platform.");
		}
		return token == null ? null : expected;
	}

	static ApiException notFound() {
		return new ApiException(ApiErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, null);
	}

	static ApiException invalid(String field, String code, String message) {
		return new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, null,
				List.of(new ApiFieldError(field, code, message)), Map.of());
	}

	private void count(String event) {
		if (meters != null) {
			meters.counter("qal.devices", "event", event).increment();
		}
	}
}
