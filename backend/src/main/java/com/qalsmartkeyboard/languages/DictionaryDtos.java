package com.qalsmartkeyboard.languages;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Bodies of {@code /me/dictionary/changes} (contract schemas {@code DictionaryChange*}). */
final class DictionaryDtos {

	static final String LANGUAGE = "^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$";

	private DictionaryDtos() {
	}

	/**
	 * {@code DictionaryChange}. On upload {@code clientChangeId} is the client's id of the change; on download it is the
	 * id of the dictionary entry (stable per word), so a client can recognise what it already holds.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Change(@NotNull @Pattern(regexp = "^(ADD|DELETE)$") String op,
			@NotNull @Size(max = 35) @Pattern(regexp = LANGUAGE) String languageCode,
			@NotNull @Size(min = 1, max = 64) String word, @NotNull UUID clientChangeId, Instant changedAt) {
	}

	record ChangesRequest(@NotNull @Size(min = 1, max = 500) List<@NotNull @Valid Change> changes) {
	}

	record Rejected(UUID clientChangeId, String code) {
	}

	/** {@code DictionaryChangesResult}. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record ChangesResult(int applied, List<Rejected> rejected, String syncToken) {
	}

	/** {@code DictionaryChangesPage}. */
	record ChangesPage(List<Change> changes, String syncToken, boolean hasMore) {
	}
}
