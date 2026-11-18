package com.qalsmartkeyboard.languages;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Read-only access to the language catalogue, profiles, resources and signing keys. JSON columns are read as text. */
@Repository
class LanguagesRepository {

	record LanguageRow(String code, String englishName, String nativeName, String scriptCode, String writingDirection,
			String supportLevel, boolean launch, String metadataJson) {
	}

	record ProfileRow(String code, String version, String displayName, String languageCode, String sourceScript,
			String targetScript, String inputScheme, boolean reversible, String status, UUID rulesResourceId) {
	}

	/** One published resource joined with its language, signing key and transliteration profile (if it is a rules pack). */
	record ResourceRow(UUID id, String languageCode, String resourceType, String version, String platform,
			String channel, String artifactUri, String sha256, long sizeBytes, Long containerSizeBytes,
			UUID baseResourceId, String keyId, String minAppVersion, String licenseCode, String deviceRequirementsJson,
			String releaseNotes, OffsetDateTime publishedAt, int rolloutPercentage, String profileCode,
			String profileVersion, String normalizationForm) {
	}

	record KeyRow(String keyId, String algorithm, String publicKey, String status, OffsetDateTime validFrom,
			OffsetDateTime validUntil, String endorsedByKeyId, String endorsementSignature) {
	}

	/** Resource types of the contract's {@code ResourceType}; equals the V1 CHECK list (ADR 0007 D20 added GLOSSARY). */
	static final List<String> CONTRACT_RESOURCE_TYPES = List.of("KEYBOARD_LAYOUT", "TRANSLITERATION_RULES", "DICTIONARY",
			"FREQUENCY_LIST", "PREDICTION_MODEL", "AUTOCORRECTION_MODEL", "PROTECTED_TERMS", "EMOJI_KEYWORDS",
			"GLOSSARY", "PRONUNCIATION", "SPELL_MODEL", "LANGUAGE_ID_MODEL", "TRANSLATION_MODEL", "ASR_MODEL", "TTS_VOICE",
			"ON_DEVICE_LLM", "OTHER");

	private static final String RESOURCE_SELECT = """
			SELECT r.id, l.language_code, r.resource_type, r.resource_version, r.platform, r.release_channel,
			       r.artifact_uri, r.sha256_checksum, r.size_bytes, r.container_size_bytes, r.base_resource_id, k.key_id,
			       r.min_app_version, r.license_code, r.device_requirements::text, r.release_notes, r.published_at,
			       r.rollout_percentage,
			       p.profile_code, p.profile_version, p.normalization_form
			FROM language_resources r
			JOIN supported_languages l ON l.id = r.language_id
			JOIN resource_signing_keys k ON k.id = r.signature_key_id
			LEFT JOIN LATERAL (
			    SELECT tp.profile_code, tp.profile_version, tp.normalization_form
			    FROM transliteration_profiles tp
			    WHERE tp.rules_resource_id = r.id AND tp.language_id = r.language_id
			    ORDER BY (tp.profile_status = 'ACTIVE') DESC, tp.created_at, tp.id
			    LIMIT 1) p ON TRUE
			WHERE r.publication_status = 'PUBLISHED' AND l.is_active AND k.key_status <> 'REVOKED'
			  AND r.size_bytes IS NOT NULL
			""";

	private static final RowMapper<ResourceRow> RESOURCE_MAPPER = (rs, i) -> new ResourceRow(rs.getObject(1, UUID.class),
			rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
			rs.getString(8), rs.getLong(9), rs.getObject(10, Long.class), rs.getObject(11, UUID.class),
			rs.getString(12), rs.getString(13), rs.getString(14), rs.getString(15), rs.getString(16),
			rs.getObject(17, OffsetDateTime.class), rs.getInt(18), rs.getString(19), rs.getString(20),
			rs.getString(21));

	private final JdbcTemplate jdbc;

	LanguagesRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	List<LanguageRow> activeLanguages() {
		return jdbc.query("""
				SELECT language_code, english_name, native_name, script_code, writing_direction, support_level,
				       is_launch_language, metadata::text
				FROM supported_languages WHERE is_active ORDER BY sort_order, english_name, language_code""",
				(rs, i) -> new LanguageRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
						rs.getString(5), rs.getString(6), rs.getBoolean(7), rs.getString(8)));
	}

	boolean activeLanguageExists(String code) {
		return Boolean.TRUE.equals(jdbc.queryForObject(
				"SELECT EXISTS (SELECT 1 FROM supported_languages WHERE is_active AND language_code = ?)", Boolean.class,
				code));
	}

	/**
	 * ACTIVE and RETIRED profiles of an active language (never DRAFT). The rules resource id is only exposed while that
	 * resource is published on a client-visible channel (not INTERNAL).
	 */
	List<ProfileRow> profiles(String languageCode) {
		return jdbc.query("""
				SELECT p.profile_code, p.profile_version, p.display_name, l.language_code, p.source_script_code,
				       p.target_script_code, p.input_scheme, p.supports_reverse, p.profile_status,
				       CASE WHEN r.publication_status = 'PUBLISHED' AND r.release_channel <> 'INTERNAL' THEN r.id END
				FROM transliteration_profiles p
				JOIN supported_languages l ON l.id = p.language_id
				LEFT JOIN language_resources r ON r.id = p.rules_resource_id AND r.language_id = p.language_id
				WHERE l.language_code = ? AND l.is_active AND p.profile_status IN ('ACTIVE', 'RETIRED')
				ORDER BY p.profile_code, p.created_at, p.profile_version""",
				(rs, i) -> new ProfileRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
						rs.getString(5), rs.getString(6), rs.getString(7), rs.getBoolean(8), rs.getString(9),
						rs.getObject(10, UUID.class)),
				languageCode);
	}

	Optional<ResourceRow> resource(UUID id) {
		return jdbc.query(RESOURCE_SELECT + " AND r.resource_type IN (" + placeholders(CONTRACT_RESOURCE_TYPES.size())
				+ ") AND r.id = ?", RESOURCE_MAPPER, concat(CONTRACT_RESOURCE_TYPES, List.of(id))).stream().findFirst();
	}

	/**
	 * Published resources in keyset order (newest first). SQL filters what it can express; the caller applies the
	 * app-version and rollout rules. {@code platforms}/{@code channels} are never empty.
	 */
	List<ResourceRow> resources(List<String> platforms, List<String> channels, String type, String languageCode,
			OffsetDateTime afterPublishedAt, UUID afterId, int batch) {
		StringBuilder sql = new StringBuilder(RESOURCE_SELECT);
		List<Object> args = new ArrayList<>(CONTRACT_RESOURCE_TYPES);
		sql.append(" AND r.resource_type IN (").append(placeholders(CONTRACT_RESOURCE_TYPES.size())).append(')');
		sql.append(" AND r.platform IN (").append(placeholders(platforms.size())).append(')');
		args.addAll(platforms);
		sql.append(" AND r.release_channel IN (").append(placeholders(channels.size())).append(')');
		args.addAll(channels);
		if (type != null) {
			sql.append(" AND r.resource_type = ?");
			args.add(type);
		}
		if (languageCode != null) {
			sql.append(" AND l.language_code = ?");
			args.add(languageCode);
		}
		if (afterPublishedAt != null) {
			sql.append(" AND (r.published_at, r.id) < (?, ?)");
			args.add(afterPublishedAt);
			args.add(afterId);
		}
		sql.append(" ORDER BY r.published_at DESC, r.id DESC LIMIT ?");
		args.add(batch);
		return jdbc.query(sql.toString(), RESOURCE_MAPPER, args.toArray());
	}

	List<KeyRow> signingKeys() {
		return jdbc.query("""
				SELECT key_id, algorithm, public_key, key_status, valid_from, valid_until, endorsed_by_key_id,
				       endorsement_signature
				FROM resource_signing_keys ORDER BY valid_from, key_id""",
				(rs, i) -> new KeyRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
						rs.getObject(5, OffsetDateTime.class), rs.getObject(6, OffsetDateTime.class), rs.getString(7),
						rs.getString(8)));
	}

	private static String placeholders(int n) {
		return String.join(", ", Collections.nCopies(n, "?"));
	}

	/** Positional arguments for the resource-type placeholders followed by the remaining ones (the id placeholder is last). */
	private static Object[] concat(List<String> first, List<?> rest) {
		List<Object> all = new ArrayList<>(first);
		all.addAll(rest);
		return all.toArray();
	}
}
