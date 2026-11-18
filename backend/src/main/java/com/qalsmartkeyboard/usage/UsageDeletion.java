package com.qalsmartkeyboard.usage;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.identity.UserDeletionParticipant;

/**
 * Account deletion (B6.2, ADR 0015) for the usage tables:
 * <ul>
 * <li>Deleted: {@code usage_reservations} and {@code usage_counters} (operational window state; an open reservation
 * of a deleted account can never settle).</li>
 * <li>Kept, pseudonymised (attached to the anonymised account id only): {@code usage_events} (append-only metering
 * ledger, SD 20.1) and the credit records {@code ai_credit_accounts}, {@code ai_credit_lots},
 * {@code ai_credit_transactions} (financial: purchased credits are part of the payment trail; {@code ON DELETE
 * RESTRICT} and an append-only ledger). They hold no personal data besides the id; the retention job (B6.3) purges
 * them when their legal period ends.</li>
 * </ul>
 */
@Component
class UsageDeletion implements UserDeletionParticipant {

	private static final Logger log = LoggerFactory.getLogger(UsageDeletion.class);

	private final JdbcClient jdbc;

	UsageDeletion(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public int deletionOrder() {
		return USAGE;
	}

	@Override
	public int purgeUser(UUID userId, Instant now) {
		int reservations = jdbc.sql("DELETE FROM usage_reservations WHERE user_id = :user").param("user", userId)
				.update();
		int counters = jdbc.sql("DELETE FROM usage_counters WHERE user_id = :user").param("user", userId).update();
		log.info("Account deletion {}: {} reservations and {} usage counters deleted", userId, reservations, counters);
		return reservations + counters;
	}
}
