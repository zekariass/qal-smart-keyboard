package com.qalsmartkeyboard.personalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.platform.crypto.EncryptionContext;
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;

/**
 * {@code qal.creative-backup.enabled=false} (ADR 0050): every backup operation answers 404 {@code NOT_FOUND}, contract
 * valid, and the privacy lifecycle still works: withdrawing the consent deletes what was stored before the switch.
 */
@SpringBootTest(properties = { "qal.outbox.dispatcher-enabled=false", "qal.creative-backup.enabled=false" })
@AutoConfigureMockMvc
@ContractValidated
@Testcontainers
class CreativeBackupDisabledIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	static final String BASE = "/api/v1/me/creative-backup";

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	AccessTokenService tokens;
	@Autowired
	UserDataEncryption encryption;

	@Test
	void everyOperationIsNotFoundAndWithdrawalStillDeletes() throws Exception {
		UUID u = jdbc.queryForObject("""
				INSERT INTO users (account_type, account_status, time_zone) VALUES ('REGISTERED', 'ACTIVE', 'UTC')
				RETURNING id""", UUID.class);
		String bearer = "Bearer " + tokens.issue(u, UUID.randomUUID(), null, "REGISTERED").value();
		mvc.perform(put("/api/v1/me/consents/CREATIVE_BACKUP").header("Authorization", bearer)
				.contentType(MediaType.APPLICATION_JSON).content("{\"consentVersion\":\"1\",\"isGranted\":true}"))
				.andExpect(status().isOk());
		for (var request : List.of(get(BASE + "/summary"), get(BASE + "/items"), get(BASE + "/items/a1"),
				put(BASE + "/items/a1").contentType(MediaType.APPLICATION_JSON)
						.content("{\"kind\":\"DRAWING\",\"updatedAt\":\"2026-11-26T10:00:00Z\",\"content\":\"AAAA\"}"),
				delete(BASE + "/items/a1"), delete(BASE))) {
			mvc.perform(request.header("Authorization", bearer)).andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		}

		// an item stored while the feature was on is still deleted by the withdrawal
		UUID id = UUID.randomUUID();
		byte[] sealed = encryption.encrypt(u, EncryptionContext.of("creative_backup_items", "content_ciphertext", id),
				new byte[] { 1, 2, 3 }).ciphertext();
		jdbc.update("""
				INSERT INTO creative_backup_items (id, user_id, client_item_id, item_kind, revision, content_ciphertext,
				       key_version, size_bytes, client_updated_at) VALUES (?, ?, 'old', 'DRAWING', 1, ?, 1, 3, now())""", id,
				u, sealed);
		mvc.perform(put("/api/v1/me/consents/CREATIVE_BACKUP").header("Authorization", bearer)
				.contentType(MediaType.APPLICATION_JSON).content("{\"consentVersion\":\"1\",\"isGranted\":false}"))
				.andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM creative_backup_items WHERE user_id = ?", Long.class, u))
				.isZero();
	}
}
