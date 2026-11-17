package com.qalsmartkeyboard.devices;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.qalsmartkeyboard.devices.DeviceDtos.DeviceDto;
import com.qalsmartkeyboard.devices.DeviceDtos.DeviceRegistrationRequest;
import com.qalsmartkeyboard.devices.DeviceDtos.DeviceUpdateRequest;
import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.identity.DeviceInfo;

import jakarta.validation.Valid;

/**
 * {@code POST /api/v1/devices} ({@code registerDevice}), {@code PATCH /api/v1/devices/{deviceId}}
 * ({@code updateDevice}) and {@code DELETE /api/v1/devices/{deviceId}} ({@code revokeDevice}); all need an access
 * token. The client headers fill what the body leaves out: {@code X-App-Version} is the app version, and a present
 * {@code X-Client-Platform} must equal the body's platform on registration.
 */
@RestController
class DeviceController {

	static final String PLATFORM_HEADER = "X-Client-Platform";
	static final String VERSION_HEADER = "X-App-Version";

	private final DeviceService service;

	DeviceController(DeviceService service) {
		this.service = service;
	}

	@PostMapping(path = "/api/v1/devices", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<DeviceDto> register(@AuthenticationPrincipal AuthenticatedUser caller,
			@Valid @RequestBody DeviceRegistrationRequest body,
			@RequestHeader(name = PLATFORM_HEADER, required = false) String platformHeader,
			@RequestHeader(name = VERSION_HEADER, required = false) String versionHeader) {
		if (platformHeader != null && !platformHeader.equals(body.platform())) {
			throw DeviceService.invalid("platform", "PLATFORM_MISMATCH",
					"The platform must match the X-Client-Platform header.");
		}
		DeviceInfo info = new DeviceInfo(body.installationId(), body.platform(), body.deviceName(),
				body.appVersion() != null ? body.appVersion() : versionHeader, body.osVersion(), body.pushProvider(),
				body.pushToken());
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.register(caller, info));
	}

	@PatchMapping(path = "/api/v1/devices/{deviceId}", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<DeviceDto> update(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable UUID deviceId,
			@Valid @RequestBody DeviceUpdateRequest body,
			@RequestHeader(name = VERSION_HEADER, required = false) String versionHeader) {
		DeviceInfo changes = new DeviceInfo(null, null, body.deviceName(),
				body.appVersion() != null ? body.appVersion() : versionHeader, body.osVersion(), body.pushProvider(),
				body.pushToken());
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(service.update(caller.userId(), deviceId, changes));
	}

	@DeleteMapping("/api/v1/devices/{deviceId}")
	ResponseEntity<Void> revoke(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable UUID deviceId) {
		service.revoke(caller.userId(), deviceId);
		return ResponseEntity.noContent().build();
	}
}
