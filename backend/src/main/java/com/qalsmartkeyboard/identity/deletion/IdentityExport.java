package com.qalsmartkeyboard.identity.deletion;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.identity.ExportRows;
import com.qalsmartkeyboard.identity.ExportWriter;
import com.qalsmartkeyboard.identity.UserExportParticipant;

/**
 * Data export (B6.1, ADR 0022) of identity's tables.
 * <ul>
 * <li>{@code account}: the {@code users} row as the person sees it (type, status, contact details, market, locale, time
 * zone, age confirmation, timestamps). Left out: {@code merged_into_user_id} (internal guest-merge link).</li>
 * <li>{@code authIdentities}: how the person signs in (provider, provider subject, provider e-mail).</li>
 * <li>{@code sessions}: metadata of the refresh-token families (when, from which device, IP address and user agent).
 * Left out: the refresh-token hashes and family ids (credentials).</li>
 * <li>Not exported: password credentials and reset tokens (credentials), OTP challenges and attestation nonces
 * (short-lived authentication records without personal content), the data keys.</li>
 * </ul>
 */
@Component
class IdentityExport implements UserExportParticipant {

	private final JdbcClient jdbc;

	IdentityExport(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public int exportOrder() {
		return IDENTITY;
	}

	@Override
	public void exportUser(UUID userId, ExportWriter out) {
		Map<String, Object> user = Map.of("u", userId);
		out.section("account", "Account and preferences", "The account row: type, status, contact details, market, locale, "
				+ "time zone, age confirmation and timestamps.", ExportRows.query(jdbc, """
						SELECT id, account_type, account_status, email, email_verified_at, phone_e164, phone_verified_at,
						       display_name, market_country_code, market_source, ui_locale, time_zone, age_confirmed_at,
						       last_login_at, created_at, updated_at
						FROM users WHERE id = :u
						""", user));
		out.section("authIdentities", "Account and preferences",
				"Sign-in methods: provider, provider subject and provider e-mail.", ExportRows.query(jdbc, """
						SELECT id, identity_provider, provider_subject, provider_email, created_at, last_used_at
						FROM user_auth_identities WHERE user_id = :u ORDER BY created_at, id
						""", user));
		out.section("sessions", "Sessions", "Metadata of every sign-in session (refresh-token family): device, "
				+ "timestamps, IP address and user agent. Token hashes are not included.", ExportRows.query(jdbc, """
						SELECT id, device_id, session_status, revoked_reason, host(ip_address) AS ip_address, user_agent,
						       created_at, last_used_at, expires_at, revoked_at
						FROM user_sessions WHERE user_id = :u ORDER BY created_at, id
						""", user));
		out.excluded("credentials", "Password hashes, reset tokens, refresh-token hashes, one-time-code challenges, "
				+ "attestation nonces and data keys are security material, not personal content; they are never exported.");
	}
}
