package com.qalsmartkeyboard.privacy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.identity.GuestMergeParticipant;
import com.qalsmartkeyboard.platform.crypto.CiphertextRejectedException;
import com.qalsmartkeyboard.platform.crypto.EncryptedValue;
import com.qalsmartkeyboard.platform.crypto.EncryptionContext;
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;

/**
 * Guest merge (B2.6, ADR 0010) for consents and history (SD 9.1 "preferences, consents and remaining trial usage move";
 * SD 19.3; V1 section 6).
 *
 * <ul>
 * <li><b>{@code user_consents}</b> is append-only (UPDATE/DELETE are rejected by a trigger), so decisions are
 * <em>appended</em> for the target, never moved; the guest's rows stay as the guest's record. For each consent code
 * with the guest's latest decision G: if the target has no decision, G is appended; if the target's latest decision is
 * a grant and G is a <b>later revocation</b>, G is appended (a withdrawal is never lost); otherwise the target's
 * decision stands (a guest grant never overrides a decision of the registered account). Appended rows keep G's
 * version, value, platform and {@code recorded_at} (when the person decided) and name their origin in
 * {@code metadata} ({@code mergedFromGuestUserId}, {@code mergedFromConsentId}).</li>
 * <li><b>{@code user_history_items}</b>: every item moves with input and output re-encrypted for the target (expiry
 * unchanged, so the retention job treats it like the target's own items). An item that does not open as the guest is
 * dropped (logged by id). Guests have no {@code HISTORY_SYNC} entitlement, so this is normally empty.</li>
 * <li>Not moved: {@code data_export_requests} (an export of the guest account, expires on its own) and
 * {@code user_deletion_requests} (a guest with an open deletion request is not {@code ACTIVE} and cannot be
 * merged).</li>
 * </ul>
 */
@Component
class PrivacyGuestMerge implements GuestMergeParticipant {

	private static final Logger log = LoggerFactory.getLogger(PrivacyGuestMerge.class);
	static final String HISTORY = "user_history_items";

	private record Decision(UUID id, String code, String version, boolean granted, Timestamp recordedAt,
			String platform) {
	}

	private final JdbcClient jdbc;
	private final UserDataEncryption encryption;

	PrivacyGuestMerge(JdbcClient jdbc, UserDataEncryption encryption) {
		this.jdbc = jdbc;
		this.encryption = encryption;
	}

	@Override
	public int mergeOrder() {
		return PRIVACY;
	}

	@Override
	public void mergeGuest(UUID guest, UUID target, Instant now) {
		int consents = mergeConsents(guest, target, now);
		int history = mergeHistory(guest, target, Timestamp.from(now));
		log.info("Guest merge {} -> {}: {} consent decision(s) appended, {} history item(s) moved", guest, target,
				consents, history);
	}

	private int mergeConsents(UUID guest, UUID target, Instant now) {
		Map<String, Decision> targetLatest = latest(target).stream()
				.collect(Collectors.toMap(Decision::code, Function.identity()));
		int appended = 0;
		for (Decision g : latest(guest)) {
			Decision t = targetLatest.get(g.code());
			boolean take = t == null
					|| (t.granted() && !g.granted() && g.recordedAt().after(t.recordedAt()));
			if (!take) {
				continue;
			}
			jdbc.sql("""
					INSERT INTO user_consents (id, user_id, consent_code, consent_version, is_granted, recorded_at,
					                           source_platform, metadata)
					VALUES (:id, :target, :code, :version, :granted, :recordedAt, :platform,
					        jsonb_build_object('mergedFromGuestUserId', CAST(:guest AS text),
					                           'mergedFromConsentId', CAST(:consentId AS text),
					                           'mergedAt', CAST(:now AS text)))
					""").param("id", UUID.randomUUID()).param("target", target).param("code", g.code())
					.param("version", g.version()).param("granted", g.granted()).param("recordedAt", g.recordedAt())
					.param("platform", g.platform()).param("guest", guest.toString())
					.param("consentId", g.id().toString()).param("now", now.toString()).update();
			appended++;
		}
		return appended;
	}

	/** The latest decision per consent code (V1 {@code ix_user_consents_latest}). */
	private List<Decision> latest(UUID userId) {
		return jdbc.sql("""
				SELECT DISTINCT ON (consent_code) id, consent_code, consent_version, is_granted, recorded_at, source_platform
				FROM user_consents WHERE user_id = :userId
				ORDER BY consent_code, recorded_at DESC, id DESC
				""").param("userId", userId)
				.query((rs, n) -> new Decision(rs.getObject("id", UUID.class), rs.getString("consent_code"),
						rs.getString("consent_version"), rs.getBoolean("is_granted"), rs.getTimestamp("recorded_at"),
						rs.getString("source_platform")))
				.list();
	}

	private int mergeHistory(UUID guest, UUID target, Timestamp now) {
		record Item(UUID id, byte[] input, byte[] output) {
		}
		List<Item> items = jdbc.sql(
				"SELECT id, input_ciphertext, output_ciphertext FROM user_history_items WHERE user_id = :guest ORDER BY id")
				.param("guest", guest)
				.query((rs, n) -> new Item(rs.getObject("id", UUID.class), rs.getBytes("input_ciphertext"),
						rs.getBytes("output_ciphertext")))
				.list();
		int moved = 0;
		for (Item item : items) {
			List<EncryptedValue> sealed;
			try {
				sealed = encryption.reencryptRow(guest, target,
						List.of(EncryptionContext.of(HISTORY, "input_ciphertext", item.id()),
								EncryptionContext.of(HISTORY, "output_ciphertext", item.id())),
						List.of(item.input(), item.output()));
			}
			catch (CiphertextRejectedException e) {
				log.warn("Guest merge: {} row {} does not decrypt and is not moved", HISTORY, item.id());
				continue;
			}
			jdbc.sql("""
					UPDATE user_history_items SET user_id = :target, input_ciphertext = :input,
					       output_ciphertext = :output, key_version = :keyVersion, updated_at = :now
					WHERE id = :id AND user_id = :guest
					""").param("target", target).param("input", sealed.get(0).ciphertext())
					.param("output", sealed.get(1).ciphertext()).param("keyVersion", sealed.get(0).keyVersion())
					.param("now", now).param("id", item.id()).param("guest", guest).update();
			moved++;
		}
		jdbc.sql("DELETE FROM user_history_items WHERE user_id = :guest").param("guest", guest).update();
		return moved;
	}
}
