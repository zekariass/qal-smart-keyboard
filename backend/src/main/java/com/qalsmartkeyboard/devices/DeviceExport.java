package com.qalsmartkeyboard.devices;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.identity.ExportRows;
import com.qalsmartkeyboard.identity.ExportWriter;
import com.qalsmartkeyboard.identity.UserExportParticipant;

/**
 * Data export (B6.1, ADR 0022) of the devices tables: {@code user_devices} (name, platform, versions, attestation
 * status, push provider and whether a push token is registered; the push token itself is a credential and is left out)
 * and the language resources installed on each device.
 */
@Component
class DeviceExport implements UserExportParticipant {

	private final JdbcClient jdbc;

	DeviceExport(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public int exportOrder() {
		return DEVICES;
	}

	@Override
	public void exportUser(UUID userId, ExportWriter out) {
		Map<String, Object> user = Map.of("u", userId);
		out.section("devices", "Account and preferences", "Registered installations: platform, names, versions, "
				+ "attestation status, push provider (the push token is not included).", ExportRows.query(jdbc, """
						SELECT id, installation_id, platform, distribution_channel, device_name, app_version, os_version,
						       keyboard_full_access, attestation_provider, attestation_status, attestation_checked_at,
						       push_provider, push_token IS NOT NULL AS has_push_token, device_status, last_seen_at,
						       created_at, updated_at
						FROM user_devices WHERE user_id = :u ORDER BY created_at, id
						""", user));
		out.section("deviceLanguageInstallations", "Account and preferences",
				"Language resources installed on each of the devices.", ExportRows.query(jdbc, """
						SELECT i.id, i.device_id, i.resource_id, i.installed_version, i.installation_status,
						       i.checksum_verified, i.signature_verified, i.installed_at, i.last_error_code, i.updated_at
						FROM device_language_resource_installations i JOIN user_devices d ON d.id = i.device_id
						WHERE d.user_id = :u ORDER BY i.updated_at, i.id
						""", user));
	}
}
