package com.qalsmartkeyboard.preferences;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.identity.ExportRows;
import com.qalsmartkeyboard.identity.ExportWriter;
import com.qalsmartkeyboard.identity.UserExportParticipant;
import com.qalsmartkeyboard.platform.crypto.EncryptionContext;
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;

/**
 * Data export (B6.1, ADR 0022) of the preferences tables, with the encrypted columns opened (the plaintext exists only
 * inside the encrypted archive):
 * <ul>
 * <li>{@code preferences}, {@code typingLanguages}: the settings, with languages as codes.</li>
 * <li>{@code destinationProfiles}: the labels are decrypted ({@code user_destination_profiles.label_ciphertext}).</li>
 * <li>{@code appDestinationRules}: <b>hashes only</b>. The server never receives an app's package name (ADR 0012): a
 * rule is stored under {@code HMAC(user key, package name)}, so the export can only list the hash, and the package
 * names live on the person's devices. The HMAC key ({@code user_app_rule_keys}) is a secret and is not exported.</li>
 * <li>{@code styleProfiles}: sign-off and preferred phrases decrypted.</li>
 * </ul>
 * Revision counters ({@code user_typing_language_revisions}, {@code user_app_rule_revisions}) are sync bookkeeping and
 * not exported.
 */
@Component
class PreferencesExport implements UserExportParticipant {

	private final JdbcClient jdbc;
	private final UserDataEncryption encryption;

	PreferencesExport(JdbcClient jdbc, UserDataEncryption encryption) {
		this.jdbc = jdbc;
		this.encryption = encryption;
	}

	@Override
	public int exportOrder() {
		return PREFERENCES;
	}

	@Override
	public void exportUser(UUID userId, ExportWriter out) {
		Map<String, Object> user = Map.of("u", userId);
		out.section("preferences", "Account and preferences",
				"The settings document (defaults, formats, AI processing and history preferences, theme, free-form settings).",
				ExportRows.query(jdbc, """
						SELECT p.user_id, s.language_code AS default_source_language,
						       d.language_code AS default_destination_language, p.source_detection_mode,
						       p.translation_mode, p.preserve_names_in_translation, p.default_translation_style,
						       p.default_formality, p.default_addressee_gender, p.calendar_system, p.time_notation,
						       p.numeral_style, p.voice_input_language_mode, v.language_code AS preferred_voice_language,
						       p.ai_processing_preference, p.ai_history_enabled, p.history_retention_days,
						       p.analytics_opt_in, p.keyboard_theme, p.haptics_enabled, p.sound_enabled, p.settings_json,
						       p.revision, p.created_at, p.updated_at
						FROM user_preferences p
						LEFT JOIN supported_languages s ON s.id = p.default_source_language_id
						LEFT JOIN supported_languages d ON d.id = p.default_destination_language_id
						LEFT JOIN supported_languages v ON v.id = p.preferred_voice_language_id
						WHERE p.user_id = :u
						""", user));
		out.section("typingLanguages", "Account and preferences", "Typing languages with input mode and order.",
				ExportRows.query(jdbc, """
						SELECT t.id, l.language_code, t.is_enabled, t.is_primary, t.sort_order, t.input_mode,
						       t.transliteration_profile_id, t.conversion_trigger, t.show_transliteration_candidates,
						       t.unknown_token_behavior, t.created_at, t.updated_at
						FROM user_typing_languages t JOIN supported_languages l ON l.id = t.language_id
						WHERE t.user_id = :u ORDER BY t.sort_order, t.id
						""", user));
		out.section("destinationProfiles", "Account and preferences",
				"Destination profiles (for example \"Mum\"): the label is decrypted.", ExportRows.query(jdbc, """
						SELECT p.id, p.label_ciphertext, l.language_code AS destination_language, p.formality,
						       p.addressee_gender, p.default_tone, p.default_style, p.glossary_id, p.sort_order,
						       p.is_default, p.created_at, p.updated_at
						FROM user_destination_profiles p JOIN supported_languages l ON l.id = p.destination_language_id
						WHERE p.user_id = :u ORDER BY p.sort_order, p.id
						""", user, row -> {
					byte[] ciphertext = (byte[]) row.remove("labelCiphertext");
					row.put("label", encryption.decryptString(userId,
							EncryptionContext.of("user_destination_profiles", "label_ciphertext", (String) row.get("id")),
							ciphertext));
					return row;
				}));
		out.section("appDestinationRules", "Account and preferences",
				"Per-app destination rules, as hashes only: the server never receives app package names (ADR 0012), so the "
						+ "names are not in this export; they exist only on your devices.",
				ExportRows.query(jdbc, """
						SELECT r.id, r.platform, r.app_package_hmac, r.destination_profile_id,
						       l.language_code AS destination_language, r.is_enabled, r.created_at, r.updated_at
						FROM user_app_destination_rules r
						LEFT JOIN supported_languages l ON l.id = r.destination_language_id
						WHERE r.user_id = :u ORDER BY r.created_at, r.id
						""", user));
		out.section("styleProfiles", "Account and preferences",
				"Writing-style profiles per language: sign-off and preferred phrases are decrypted.",
				ExportRows.query(jdbc, """
						SELECT s.id, l.language_code, s.formality, s.length_preference, s.emoji_usage,
						       s.signoff_ciphertext, s.preferred_phrases_ciphertext, s.learned_hints, s.is_enabled,
						       s.created_at, s.updated_at
						FROM user_style_profiles s JOIN supported_languages l ON l.id = s.language_id
						WHERE s.user_id = :u ORDER BY s.created_at, s.id
						""", user, row -> {
					String id = (String) row.get("id");
					byte[] signoff = (byte[]) row.remove("signoffCiphertext");
					byte[] phrases = (byte[]) row.remove("preferredPhrasesCiphertext");
					row.put("signoff", signoff == null ? null : encryption.decryptString(userId,
							EncryptionContext.of("user_style_profiles", "signoff_ciphertext", id), signoff));
					row.put("preferredPhrases", phrases == null ? null : encryption.decryptString(userId,
							EncryptionContext.of("user_style_profiles", "preferred_phrases_ciphertext", id), phrases));
					return row;
				}));
		out.excluded("appRuleKey", "The per-user key that hashes app package names is a secret; it is not exported. "
				+ "App package names are not stored on the server at all.");
	}
}
