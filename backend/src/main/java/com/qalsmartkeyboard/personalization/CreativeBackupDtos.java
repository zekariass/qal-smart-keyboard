package com.qalsmartkeyboard.personalization;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Contract schemas {@code CreativeBackupItemMetadata}, {@code CreativeBackupItem}, {@code CreativeBackupItemPage} and
 * {@code CreativeBackupSummary} (ADR 0050). The PUT body ({@code CreativeBackupItemPut}) is read by hand, see
 * {@link CreativeBackupController}.
 */
final class CreativeBackupDtos {

	private CreativeBackupDtos() {
	}

	/** Listing metadata of one item: no content. */
	record ItemMetadata(String itemId, String kind, long revision, long sizeBytes, Instant updatedAt, Instant createdAt,
			Instant storedAt) {
	}

	/** One item with its content (standard base64 with padding); {@code toString} prints ids only. */
	record Item(String itemId, String kind, long revision, long sizeBytes, Instant updatedAt, Instant createdAt,
			Instant storedAt, String content) {

		@Override
		public String toString() {
			return "Item[" + itemId + ", " + kind + ", r" + revision + "]";
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record ItemPage(List<ItemMetadata> items, String nextCursor) {
	}

	record KindUsage(String kind, int itemCount, long totalBytes) {
	}

	record Summary(boolean consentGranted, int itemCount, long totalBytes, long maxItemBytes, int maxItemsPerKind,
			long maxTotalBytes, List<KindUsage> kinds) {
	}
}
