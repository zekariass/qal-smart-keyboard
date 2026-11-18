package com.qalsmartkeyboard.privacy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.platform.retention.RetentionAction;
import com.qalsmartkeyboard.platform.retention.RetentionParticipant;
import com.qalsmartkeyboard.platform.retention.RetentionRule;
import com.qalsmartkeyboard.platform.retention.RetentionSupport;

/**
 * Retention (B6.3, ADR 0020) of the privacy tables.
 *
 * <ul>
 * <li>{@code user_consents} of DELETED accounts: 30 days after {@code deleted_at} (SD 19.2 "life of account + 30 days";
 * ADR 0015 D3). Append-only, so the batch runs under {@code app.retention_purge}. A consent a payment mandate points at
 * (RESTRICT) is evidence for a financial record and stays with it. Consents of live accounts are never purged (the latest
 * decision per code is what the account's state is); those of MERGED guests go with the guest row.</li>
 * <li>{@code user_history_items}: from {@code expires_at} (the user's 1-365 days, SD 11.7, 19.2; favourites have no
 * expiry and stay until the user deletes them). The reads already ignore expired rows; the purge removes the
 * ciphertext.</li>
 * <li>{@code data_export_requests}: 30 days after the download link expired (SD 19.4: the link lives 7 days; B6.1 must
 * keep the 7-day bucket lifecycle of SD 8 on the archive objects, because a database delete cannot delete a file).
 * Requests that never completed are counted from {@code requested_at}.</li>
 * </ul>
 * {@code user_deletion_requests} are not purged on their own: they go with the tombstone row (cascade).
 */
@Component
class PrivacyRetention implements RetentionParticipant {

	static final String CONSENTS = "user-consents-deleted-accounts";
	static final String HISTORY = "user-history-items";
	static final String EXPORTS = "data-export-requests";

	private final JdbcClient jdbc;

	PrivacyRetention(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public int order() {
		return 100;
	}

	@Override
	public List<RetentionRule> rules() {
		return List.of(
				new RetentionRule(CONSENTS, "privacy", RetentionAction.DELETE, Duration.ofDays(30),
						"SD 19.2 (life of account + 30 days); ADR 0015 D3",
						"user_consents of accounts with status DELETED, counted from users.deleted_at"),
				new RetentionRule(HISTORY, "privacy", RetentionAction.DELETE, Duration.ZERO,
						"SD 11.7, 19.2 (user-selected 1-365 days, default 30)",
						"user_history_items past their own expires_at (the period is a grace after it); favourites have no expiry"),
				new RetentionRule(EXPORTS, "privacy", RetentionAction.DELETE, Duration.ofDays(30),
						"SD 19.4 (link valid 7 days); the 30 days are an assumption",
						"data_export_requests, counted from download_expires_at (else completed_at, else requested_at)"));
	}

	@Override
	public int purge(RetentionRule rule, Instant now, Instant cutoff, int limit) {
		return switch (rule.code()) {
			case CONSENTS -> {
				RetentionSupport.allowAppendOnlyPurge(jdbc);
				yield RetentionSupport.deleteBatch(jdbc, "user_consents", "id", """
						user_id IN (SELECT u.id FROM users u WHERE u.account_status = 'DELETED' AND u.deleted_at < :cutoff)
						AND NOT EXISTS (SELECT 1 FROM payment_mandates m WHERE m.consent_record_id = user_consents.id)
						""", now, cutoff, limit);
			}
			case HISTORY -> RetentionSupport.deleteBatch(jdbc, "user_history_items", "id",
					"expires_at IS NOT NULL AND expires_at < :cutoff", now, cutoff, limit);
			case EXPORTS -> RetentionSupport.deleteBatch(jdbc, "data_export_requests", "id",
					"COALESCE(download_expires_at, completed_at, requested_at) < :cutoff", now, cutoff, limit);
			default -> throw new IllegalArgumentException("Not an executable privacy rule: " + rule.code());
		};
	}
}
