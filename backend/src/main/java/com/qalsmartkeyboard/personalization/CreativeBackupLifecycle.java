package com.qalsmartkeyboard.personalization;

import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.identity.ExportRows;
import com.qalsmartkeyboard.identity.ExportWriter;
import com.qalsmartkeyboard.identity.UserDeletionParticipant;
import com.qalsmartkeyboard.identity.UserExportParticipant;
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;

/**
 * The creative backup's part of the privacy lifecycle (ADR 0050, following ADR 0015 and ADR 0022):
 * <ul>
 * <li><b>Account deletion</b> ({@link UserDeletionParticipant}): {@code creative_backup_items} rows are deleted. They are
 * already unreadable when this runs: the executor crypto-shreds the user's data keys first (ADR 0005).</li>
 * <li><b>Data export</b> ({@link UserExportParticipant}): section {@code creativeBackup}, one row per item with its
 * metadata and the decrypted content as standard base64 ({@code contentBase64}), like history's decrypted text.</li>
 * <li><b>Guest merge</b> needs no participant: guests cannot back up (403 {@code GUEST_NOT_ALLOWED}), so a guest has no
 * rows to move.</li>
 * </ul>
 * Logs carry ids and counts only.
 */
@Component
class CreativeBackupLifecycle implements UserDeletionParticipant, UserExportParticipant {

	private static final Logger log = LoggerFactory.getLogger(CreativeBackupLifecycle.class);

	private final JdbcClient jdbc;
	private final UserDataEncryption encryption;

	CreativeBackupLifecycle(JdbcClient jdbc, UserDataEncryption encryption) {
		this.jdbc = jdbc;
		this.encryption = encryption;
	}

	@Override
	public int deletionOrder() {
		return UserDeletionParticipant.PERSONALIZATION;
	}

	@Override
	public int purgeUser(UUID userId, Instant now) {
		int deleted = jdbc.sql("DELETE FROM creative_backup_items WHERE user_id = :user").param("user", userId).update();
		log.info("Account deletion {}: {} creative backup items deleted", userId, deleted);
		return deleted;
	}

	@Override
	public int exportOrder() {
		return UserExportParticipant.PERSONALIZATION;
	}

	@Override
	public void exportUser(UUID userId, ExportWriter out) {
		out.section("creativeBackup", "User-created media (opt-in backup)",
				"Backed-up creative items (drawings): metadata and the content decrypted, as standard base64 of the bytes "
						+ "the app uploaded.",
				ExportRows.query(jdbc, """
						SELECT id, client_item_id AS item_id, item_kind AS kind, revision, size_bytes,
						       client_updated_at AS updated_at, created_at, updated_at AS stored_at, content_ciphertext
						FROM creative_backup_items WHERE user_id = :u ORDER BY client_item_id
						""", Map.of("u", userId), row -> {
					UUID id = UUID.fromString((String) row.remove("id"));
					byte[] sealed = (byte[]) row.remove("contentCiphertext");
					byte[] content = encryption.decrypt(userId, CreativeBackupService.context(id), sealed);
					try {
						row.put("contentBase64", Base64.getEncoder().encodeToString(content));
					}
					finally {
						Arrays.fill(content, (byte) 0);
					}
					return row;
				}));
	}
}
