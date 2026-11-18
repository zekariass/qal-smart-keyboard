package com.qalsmartkeyboard.languages;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.languages.DictionaryDtos.ChangesPage;
import com.qalsmartkeyboard.languages.DictionaryDtos.ChangesRequest;
import com.qalsmartkeyboard.languages.DictionaryDtos.ChangesResult;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** {@code pullDictionaryChanges} and {@code pushDictionaryChanges}. Responses are {@code no-store}: they carry words. */
@RestController
@Validated
class DictionaryController {

	private final DictionarySyncService sync;

	DictionaryController(DictionarySyncService sync) {
		this.sync = sync;
	}

	@GetMapping("/api/v1/me/dictionary/changes")
	ResponseEntity<ChangesPage> pull(@AuthenticationPrincipal AuthenticatedUser caller,
			@RequestParam(required = false) @Size(max = 128) String since,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
		return noStore(sync.pull(caller, since, limit));
	}

	@PostMapping(path = "/api/v1/me/dictionary/changes", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<ChangesResult> push(@AuthenticationPrincipal AuthenticatedUser caller,
			@Valid @RequestBody ChangesRequest body) {
		return noStore(sync.push(caller, body.changes()));
	}

	private static <T> ResponseEntity<T> noStore(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}
}
