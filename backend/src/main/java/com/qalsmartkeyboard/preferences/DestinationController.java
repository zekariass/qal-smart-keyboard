package com.qalsmartkeyboard.preferences;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.preferences.DestinationDtos.Profile;
import com.qalsmartkeyboard.preferences.DestinationDtos.ProfileInput;
import com.qalsmartkeyboard.preferences.DestinationDtos.ProfileList;
import com.qalsmartkeyboard.preferences.DestinationDtos.RulesDocument;

import jakarta.validation.Valid;

/**
 * {@code listDestinationProfiles}, {@code createDestinationProfile}, {@code updateDestinationProfile},
 * {@code deleteDestinationProfile}, {@code getAppDestinationRules} and {@code replaceAppDestinationRules}. Any valid
 * access token may use them, guests included (the data moves to the account on sign-in, ADR 0010). Responses are
 * {@code no-store}.
 */
@RestController
class DestinationController {

	private final DestinationProfilesService profiles;
	private final AppDestinationRulesService rules;

	DestinationController(DestinationProfilesService profiles, AppDestinationRulesService rules) {
		this.profiles = profiles;
		this.rules = rules;
	}

	@GetMapping("/api/v1/me/destination-profiles")
	ResponseEntity<ProfileList> list(@AuthenticationPrincipal AuthenticatedUser caller) {
		return noStore(new ProfileList(profiles.list(caller.userId())));
	}

	@PostMapping(path = "/api/v1/me/destination-profiles", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<Profile> create(@AuthenticationPrincipal AuthenticatedUser caller,
			@Valid @RequestBody ProfileInput body) {
		Profile created = profiles.create(caller.userId(), body);
		return ResponseEntity.created(URI.create("/api/v1/me/destination-profiles/" + created.id()))
				.cacheControl(CacheControl.noStore()).body(created);
	}

	@PutMapping(path = "/api/v1/me/destination-profiles/{profileId}", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<Profile> update(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable UUID profileId,
			@Valid @RequestBody ProfileInput body) {
		return noStore(profiles.replace(caller.userId(), profileId, body));
	}

	@DeleteMapping("/api/v1/me/destination-profiles/{profileId}")
	ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable UUID profileId) {
		profiles.delete(caller.userId(), profileId);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/api/v1/me/app-destination-rules")
	ResponseEntity<RulesDocument> getRules(@AuthenticationPrincipal AuthenticatedUser caller) {
		return noStore(rules.get(caller.userId()));
	}

	@PutMapping(path = "/api/v1/me/app-destination-rules", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<RulesDocument> replaceRules(@AuthenticationPrincipal AuthenticatedUser caller,
			@Valid @RequestBody RulesDocument body) {
		return noStore(rules.replace(caller.userId(), body));
	}

	private static <T> ResponseEntity<T> noStore(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}
}
