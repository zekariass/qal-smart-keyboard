package com.qalsmartkeyboard.preferences;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Bodies of {@code /me/destination-profiles} and {@code /me/app-destination-rules} (contract schemas of the same names). */
final class DestinationDtos {

	private DestinationDtos() {
	}

	/** {@code DestinationProfileInput}; {@code label} is 1-80 characters and is never persisted in the clear. */
	record ProfileInput(@NotNull @Size(min = 1, max = 80) String label,
			@NotNull @Size(max = 35) @Pattern(regexp = PreferencesDtos.LANGUAGE) String destinationLanguage,
			@Pattern(regexp = "^(INFORMAL|FORMAL|HONORIFIC)$") String formality,
			@Pattern(regexp = "^(MASCULINE|FEMININE|PLURAL|UNKNOWN)$") String addresseeGender,
			@Pattern(regexp = "^(FRIENDLY|POLITE|PROFESSIONAL|CONCISE|APOLOGETIC|HONORIFIC)$") String defaultTone,
			@Pattern(regexp = "^(LITERAL|NATURAL|POLITE|PROFESSIONAL|FRIENDLY)$") String defaultStyle, UUID glossaryId,
			Integer sortOrder) {
	}

	/** {@code DestinationProfile}. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Profile(UUID id, String label, String destinationLanguage, String formality, String addresseeGender,
			String defaultTone, String defaultStyle, UUID glossaryId, int sortOrder, Instant createdAt,
			Instant updatedAt) {
	}

	record ProfileList(List<Profile> items) {
	}

	/**
	 * {@code AppDestinationRule}. The hash alphabet is the whole defence against a raw package name: a package name
	 * always contains a dot and is never 64 lower-case hex characters.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Rule(@NotNull @Pattern(regexp = "^[0-9a-f]{64}$") String appPackageHmac, UUID destinationProfileId,
			@Size(max = 35) @Pattern(regexp = PreferencesDtos.LANGUAGE) String destinationLanguage, Boolean enabled) {
	}

	/** {@code AppDestinationRulesDocument}. */
	record RulesDocument(@NotNull @Min(0) Integer revision, @NotNull @Size(max = 200) List<@NotNull @Valid Rule> rules) {
	}
}
