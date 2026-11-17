package com.qalsmartkeyboard.configuration;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/config} (contract operation {@code getClientConfig}). Public in the contract
 * ({@code security: [{}, bearerAuth]}). The client headers are optional there and this endpoint is exempt from the
 * version check so an outdated client can read {@code minSupportedAppVersion}; it therefore never rejects on them:
 * an absent, unknown or malformed header just means "unknown" (flags with a version requirement are false).
 */
@RestController
class ClientConfigController {

	private final ClientConfigService service;

	ClientConfigController(ClientConfigService service) {
		this.service = service;
	}

	@GetMapping("/api/v1/config")
	ClientConfigDto getClientConfig(@RequestHeader(name = "X-Client-Platform", required = false) String platform,
			@RequestHeader(name = "X-App-Version", required = false) String appVersion) {
		// No authenticated context yet (B2.4): no stable subject key, so partial rollouts stay off.
		return service.resolve(ClientPlatform.parse(platform).orElse(null), AppVersion.parse(appVersion).orElse(null),
				null);
	}
}
