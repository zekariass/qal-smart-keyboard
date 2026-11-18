package com.qalsmartkeyboard.privacy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Wire and read models of the consent operations (contract {@code Consent}, {@code ConsentUpdate}). */
final class ConsentDtos {

	private ConsentDtos() {
	}

	/**
	 * @param grantedAt when the latest grant was recorded (kept after a withdrawal, so a client can show granted on X,
	 *                  withdrawn on Y); absent when never granted
	 * @param revokedAt when the latest decision withdrew the consent; absent while it is granted
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record ConsentDto(UUID id, String consentCode, String consentVersion, boolean isGranted, Instant grantedAt,
			Instant revokedAt, Instant recordedAt) {
	}

	record ConsentUpdateDto(@NotNull @NotBlank @Size(max = 40) String consentVersion, @NotNull Boolean isGranted,
			Map<String, Object> details) {
	}

	record ConsentsDto(List<ConsentDto> items) {
	}

	/** One page of {@code GET /me/consents/history}; {@code nextCursor} is absent at the end. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record ConsentHistoryDto(List<ConsentDto> items, String nextCursor) {
	}
}
