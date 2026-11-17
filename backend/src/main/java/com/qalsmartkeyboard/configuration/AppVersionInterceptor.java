package com.qalsmartkeyboard.configuration;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.ApiFieldError;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Rejects requests from app versions below the platform's {@code minSupportedAppVersion} with 426
 * {@code APP_VERSION_UNSUPPORTED}. It runs as an MVC interceptor so the Problem is produced by the platform
 * {@code ProblemExceptionHandler} (correlation id, content type, contract shape). Which paths it covers is set in
 * {@link ConfigurationWebConfiguration}.
 *
 * <p>Header policy (the contract declares all three headers optional, so a missing header cannot be an error):
 * <ul>
 * <li>{@code X-App-Version} absent: not enforced (browsers, curl, partner tooling, unreleased clients). The header is
 * client-asserted, so this is a compatibility gate, not a security control.</li>
 * <li>{@code X-App-Version} present but malformed, or {@code X-Client-Platform} present but not ANDROID/IOS/WEB:
 * 400 {@code VALIDATION_FAILED} naming the header.</li>
 * <li>Platform absent, version present: compared with the {@code ALL}-scope minimum.</li>
 * <li>If the configuration cannot be read the request is allowed (fail open) and the failure logged: an outage of the
 * config tables must not take the whole API down.</li>
 * </ul>
 */
class AppVersionInterceptor implements HandlerInterceptor {

	static final String PLATFORM_HEADER = "X-Client-Platform";
	static final String VERSION_HEADER = "X-App-Version";
	private static final Logger log = LoggerFactory.getLogger(AppVersionInterceptor.class);

	private final ObjectProvider<ClientConfigService> service;

	AppVersionInterceptor(ObjectProvider<ClientConfigService> service) {
		this.service = service;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		String platformHeader = request.getHeader(PLATFORM_HEADER);
		String versionHeader = request.getHeader(VERSION_HEADER);
		if (platformHeader == null && versionHeader == null) {
			return true;
		}
		ClientPlatform platform = null;
		if (platformHeader != null) {
			platform = ClientPlatform.parse(platformHeader).orElseThrow(() -> invalid(PLATFORM_HEADER,
					"Must be one of ANDROID, IOS, WEB."));
		}
		if (versionHeader == null) {
			return true;
		}
		AppVersion version = AppVersion.parse(versionHeader).orElseThrow(() -> invalid(VERSION_HEADER,
				"Must be a semantic version such as 2.0.3."));

		ClientConfigService configService = service.getIfAvailable();
		if (configService == null) {
			return true; // web slice without the configuration beans
		}
		AppVersion min;
		try {
			min = configService.minSupported(platform);
		}
		catch (RuntimeException e) {
			log.error("Cannot read the minimum supported app version; allowing the request", e);
			return true;
		}
		if (version.compareTo(min) < 0) {
			throw new ApiException(ApiErrorCode.APP_VERSION_UNSUPPORTED, HttpStatus.UPGRADE_REQUIRED,
					"This app version is no longer supported. Update the app to continue.", List.of(),
					Map.of("minSupportedAppVersion", min.toString()));
		}
		return true;
	}

	private static ApiException invalid(String header, String message) {
		return new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, null,
				List.of(new ApiFieldError(header, "INVALID_HEADER", message)), Map.of());
	}
}
