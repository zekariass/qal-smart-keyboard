package com.qalsmartkeyboard.devices;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.identity.UserDeletionParticipant;

/**
 * Account deletion (B6.2, ADR 0015) for {@code user_devices}: every installation row of the user is deleted (device
 * name, OS and app versions, attestation verdicts, push token). Their {@code device_language_resource_installations}
 * go with them ({@code ON DELETE CASCADE}); sessions pointing at them get {@code device_id = NULL} (identity deletes
 * the sessions anyway). {@code ai_requests.device_id} is a logical id only and stays with the pseudonymised metadata.
 */
@Component
class DeviceDeletion implements UserDeletionParticipant {

	private static final Logger log = LoggerFactory.getLogger(DeviceDeletion.class);

	private final JdbcClient jdbc;

	DeviceDeletion(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public int deletionOrder() {
		return DEVICES;
	}

	@Override
	public int purgeUser(UUID userId, Instant now) {
		int rows = jdbc.sql("DELETE FROM user_devices WHERE user_id = :user").param("user", userId).update();
		log.info("Account deletion {}: {} device row(s) deleted", userId, rows);
		return rows;
	}
}
