package com.qalsmartkeyboard.languages;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.languages.LanguageDtos.LanguageListDto;
import com.qalsmartkeyboard.languages.LanguageDtos.LanguageResourceDto;
import com.qalsmartkeyboard.languages.LanguageDtos.ProfileListDto;
import com.qalsmartkeyboard.languages.LanguageDtos.ResourceListDto;
import com.qalsmartkeyboard.languages.LanguageDtos.SigningKeyListDto;
import com.qalsmartkeyboard.languages.LanguagesService.Caller;

/**
 * The five public read operations of the contract's Languages tag ({@code listLanguages},
 * {@code listTransliterationProfiles}, {@code listLanguageResources}, {@code getLanguageResource},
 * {@code listResourceSigningKeys}). All are {@code security: [{}, bearerAuth]}: anonymous is fine, a presented token must
 * be valid (the security chain answers 401 otherwise). The contract defines no {@code ETag} or {@code Cache-Control}, so
 * none is set here; the responses that carry signed URLs are never cacheable (Spring Security's default no-cache
 * headers stay).
 */
@RestController
class LanguagesController {

	private final LanguagesService service;

	LanguagesController(LanguagesService service) {
		this.service = service;
	}

	@GetMapping("/api/v1/languages")
	LanguageListDto listLanguages() {
		return new LanguageListDto(service.languages());
	}

	@GetMapping("/api/v1/languages/{languageCode}/transliteration-profiles")
	ProfileListDto listTransliterationProfiles(@PathVariable String languageCode) {
		return new ProfileListDto(service.profiles(languageCode));
	}

	@GetMapping("/api/v1/language-resources")
	ResourceListDto listLanguageResources(@RequestParam("platform") PackPlatform platform,
			@RequestParam("appVersion") String appVersion,
			@RequestParam(name = "channel", required = false) ReleaseChannel channel,
			@RequestParam(name = "type", required = false) String type,
			@RequestParam(name = "languageCode", required = false) String languageCode,
			@RequestParam(name = "cursor", required = false) String cursor,
			@RequestParam(name = "limit", required = false) Integer limit,
			@AuthenticationPrincipal AuthenticatedUser user) {
		return service.listResources(platform, appVersion, channel, LanguagesService.checkedType(type), languageCode,
				cursor, limit, caller(user));
	}

	@GetMapping("/api/v1/language-resources/{resourceId}")
	LanguageResourceDto getLanguageResource(@PathVariable UUID resourceId,
			@AuthenticationPrincipal AuthenticatedUser user) {
		return service.resource(resourceId, caller(user));
	}

	@GetMapping("/api/v1/resource-signing-keys")
	SigningKeyListDto listResourceSigningKeys() {
		return new SigningKeyListDto(service.signingKeys());
	}

	private static Caller caller(AuthenticatedUser user) {
		if (user == null) {
			return Caller.ANONYMOUS;
		}
		return new Caller(user.deviceId() != null ? user.deviceId() : user.userId());
	}
}
