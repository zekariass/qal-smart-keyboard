package com.qalsmartkeyboard.personalization;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Contract {@code HistoryItem} and the list envelope ({@code HistoryItemUpdate} is read by hand, see the controller). */
final class HistoryDtos {

	private HistoryDtos() {
	}

	/** The decrypted text of one item; {@code toString} prints ids only. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record HistoryItem(UUID id, String featureCode, String sourceLanguage, String destinationLanguage, String inputText,
			String outputText, boolean favorite, Instant createdAt, Instant expiresAt) {

		@Override
		public String toString() {
			return "HistoryItem[" + id + ", " + featureCode + "]";
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record HistoryPage(List<HistoryItem> items, String nextCursor) {
	}
}
