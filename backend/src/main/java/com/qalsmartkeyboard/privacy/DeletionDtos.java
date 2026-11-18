package com.qalsmartkeyboard.privacy;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Wire models of the account deletion operations (contract {@code DeletionRequestCreate}, {@code DeletionRequest}). */
final class DeletionDtos {

	private DeletionDtos() {
	}

	/**
	 * @param confirm must be {@code true} (the contract's {@code const: true})
	 * @param reason  optional free text; validated but <b>not stored</b> (there is no column for it, and free text is
	 *                user content that must not outlive the account, ADR 0015)
	 */
	record DeletionRequestCreateDto(@NotNull @AssertTrue Boolean confirm, @Size(max = 255) String reason) {
	}

	/**
	 * {@code activeStoreSubscriptions} is omitted: no store purchase can exist before the stores module (M4), which
	 * must fill it then (ADR 0015).
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record DeletionRequestDto(UUID id, String status, Instant requestedAt, Instant scheduledPurgeAt) {
	}
}
