package com.qalsmartkeyboard.aigateway;

import java.util.Optional;
import java.util.UUID;

/**
 * Where the pipeline hands a settled, successful AI result for the user's opt-in history (B5.7, ADR 0021). The
 * implementation (module {@code personalization}) decides whether anything is kept: the user's {@code historyEnabled}
 * preference, a granted {@code SAVE_AI_HISTORY} consent and the {@code HISTORY_SYNC} entitlement must all hold, and the
 * request must have said {@code saveToHistory}. The pipeline only offers; it never decides, and a failing sink never
 * changes the user's outcome.
 */
public interface AiHistorySink {

	/** Keeps nothing (tests that build a pipeline without history). */
	AiHistorySink NONE = entry -> Optional.empty();

	/**
	 * Called outside a transaction after the request was settled, once per newly charged request (never for a replay
	 * served from the cache, never for an uncharged recompute).
	 *
	 * @return the id of the stored history item, empty when nothing was kept
	 */
	Optional<UUID> offer(Entry entry);

	/**
	 * The text of one settled request. Text stays out of {@code toString} (SD 19).
	 *
	 * @param requestId the {@code ai_requests} id (also the reservation id), the idempotency of the history row
	 * @param feature   catalogue feature code
	 * @param source    source language code (or the detected one), null when unknown
	 * @param destination destination language code, null for a transform
	 */
	record Entry(UUID userId, UUID requestId, String feature, String source, String destination, String inputText,
			String outputText) {

		@Override
		public String toString() {
			return "Entry[" + requestId + ", " + feature + "]";
		}
	}
}
