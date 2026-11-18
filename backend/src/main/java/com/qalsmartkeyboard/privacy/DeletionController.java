package com.qalsmartkeyboard.privacy;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.privacy.DeletionDtos.DeletionRequestCreateDto;
import com.qalsmartkeyboard.privacy.DeletionDtos.DeletionRequestDto;

import jakarta.validation.Valid;

/**
 * {@code requestAccountDeletion}, {@code getAccountDeletion} and {@code cancelAccountDeletion} (SD 19.4, ADR 0015).
 * Any valid access token may use them, guests included (a guest's data is deleted the same way). Responses are
 * {@code no-store}.
 */
@RestController
class DeletionController {

	private final DeletionRequestService deletions;

	DeletionController(DeletionRequestService deletions) {
		this.deletions = deletions;
	}

	@PostMapping(path = "/api/v1/me/deletion-request", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<DeletionRequestDto> requestAccountDeletion(@AuthenticationPrincipal AuthenticatedUser caller,
			@Valid @RequestBody DeletionRequestCreateDto body) {
		// body.reason() is validated and deliberately dropped (ADR 0015)
		return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
				.body(deletions.request(caller));
	}

	@GetMapping("/api/v1/me/deletion-request")
	ResponseEntity<DeletionRequestDto> getAccountDeletion(@AuthenticationPrincipal AuthenticatedUser caller) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(deletions.current(caller.userId()));
	}

	@DeleteMapping("/api/v1/me/deletion-request")
	ResponseEntity<Void> cancelAccountDeletion(@AuthenticationPrincipal AuthenticatedUser caller) {
		deletions.cancel(caller.userId());
		return ResponseEntity.noContent().build();
	}
}
