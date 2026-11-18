package com.qalsmartkeyboard.preferences;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.preferences.PreferencesDtos.PreferencesDto;
import com.qalsmartkeyboard.preferences.PreferencesDtos.TypingLanguagesDto;

import jakarta.validation.Valid;

/**
 * {@code getPreferences}, {@code replacePreferences}, {@code getTypingLanguages} and {@code replaceTypingLanguages}.
 * Any valid access token may use them, guests included (a guest's documents move to the account on sign-in, ADR 0010).
 * Responses are {@code no-store}: the document is per user and the revision must never be served stale.
 */
@RestController
class PreferencesController {

	private final PreferencesService preferences;
	private final TypingLanguagesService typingLanguages;

	PreferencesController(PreferencesService preferences, TypingLanguagesService typingLanguages) {
		this.preferences = preferences;
		this.typingLanguages = typingLanguages;
	}

	@GetMapping("/api/v1/me/preferences")
	ResponseEntity<PreferencesDto> getPreferences(@AuthenticationPrincipal AuthenticatedUser caller) {
		return noStore(preferences.get(caller.userId()));
	}

	@PutMapping(path = "/api/v1/me/preferences", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<PreferencesDto> replacePreferences(@AuthenticationPrincipal AuthenticatedUser caller,
			@Valid @RequestBody PreferencesDto body) {
		return noStore(preferences.replace(caller.userId(), body));
	}

	@GetMapping("/api/v1/me/typing-languages")
	ResponseEntity<TypingLanguagesDto> getTypingLanguages(@AuthenticationPrincipal AuthenticatedUser caller) {
		return noStore(typingLanguages.get(caller.userId()));
	}

	@PutMapping(path = "/api/v1/me/typing-languages", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<TypingLanguagesDto> replaceTypingLanguages(@AuthenticationPrincipal AuthenticatedUser caller,
			@Valid @RequestBody TypingLanguagesDto body) {
		return noStore(typingLanguages.replace(caller.userId(), body));
	}

	private static <T> ResponseEntity<T> noStore(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}
}
