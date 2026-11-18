package com.qalsmartkeyboard.languages;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Writes for admin pack publishing (B3.2): {@code language_resources}, {@code resource_signing_keys},
 * {@code language_datasets}, {@code language_resource_datasets} (V15) and the rules-pack link of
 * {@code transliteration_profiles}. JSON columns are exchanged as text.
 */
@Repository
class PublishingRepository {

	/** A {@code language_resources} row as the publisher sees it (any status). */
	record ResourceRow(UUID id, UUID languageId, String languageCode, String resourceType, String version,
			String platform, String channel, int rolloutPercentage, String status, String artifactUri, String sha256,
			Long sizeBytes, Long containerSizeBytes, String minAppVersion, String licenseCode, String keyId,
			String manifestJson, String releaseNotes, OffsetDateTime publishedAt, OffsetDateTime createdAt,
			OffsetDateTime updatedAt) {
	}

	record KeyRow(UUID id, String keyId, String publicKey, String status, OffsetDateTime validFrom,
			String endorsedByKeyId, String endorsementSignature) {
	}

	record DatasetRow(UUID id, String code, String licenseCode, List<String> permittedUses, String reviewStatus) {
	}

	private static final String RESOURCE_SELECT = """
			SELECT r.id, r.language_id, l.language_code, r.resource_type, r.resource_version, r.platform,
			       r.release_channel, r.rollout_percentage, r.publication_status, r.artifact_uri, r.sha256_checksum,
			       r.size_bytes, r.container_size_bytes, r.min_app_version, r.license_code, k.key_id,
			       r.manifest_json::text, r.release_notes, r.published_at, r.created_at, r.updated_at
			FROM language_resources r
			JOIN supported_languages l ON l.id = r.language_id
			LEFT JOIN resource_signing_keys k ON k.id = r.signature_key_id
			""";

	private static final RowMapper<ResourceRow> RESOURCE = (rs, i) -> new ResourceRow(rs.getObject(1, UUID.class),
			rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
			rs.getString(7), rs.getInt(8), rs.getString(9), rs.getString(10), rs.getString(11),
			rs.getObject(12, Long.class), rs.getObject(13, Long.class), rs.getString(14), rs.getString(15),
			rs.getString(16), rs.getString(17), rs.getString(18), rs.getObject(19, OffsetDateTime.class),
			rs.getObject(20, OffsetDateTime.class), rs.getObject(21, OffsetDateTime.class));

	private static final RowMapper<KeyRow> KEY = (rs, i) -> new KeyRow(rs.getObject(1, UUID.class), rs.getString(2),
			rs.getString(3), rs.getString(4), rs.getObject(5, OffsetDateTime.class), rs.getString(6), rs.getString(7));

	private static final String KEY_SELECT = """
			SELECT id, key_id, public_key, key_status, valid_from, endorsed_by_key_id, endorsement_signature
			FROM resource_signing_keys""";

	private final JdbcTemplate jdbc;

	PublishingRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	// ------------------------------------------------------------------ languages, profiles

	Optional<UUID> languageId(String languageCode) {
		return jdbc.query("SELECT id FROM supported_languages WHERE language_code = ?",
				(rs, i) -> rs.getObject(1, UUID.class), languageCode).stream().findFirst();
	}

	/** Statuses of the language's transliteration profiles with this exact code (any version). */
	List<String> profileStatuses(UUID languageId, String profileCode) {
		return jdbc.queryForList(
				"SELECT profile_status FROM transliteration_profiles WHERE language_id = ? AND profile_code = ?",
				String.class, languageId, profileCode);
	}

	/**
	 * Points the language's profiles with this code and status at the rules resource (SD 7.6, discovery's profile link).
	 * Staging links DRAFT profiles (so activating one later finds its published rules resource, V1 section 19.1);
	 * publishing to a client channel links ACTIVE ones.
	 */
	int linkProfiles(UUID languageId, String profileCode, String profileStatus, UUID resourceId) {
		return jdbc.update("""
				UPDATE transliteration_profiles SET rules_resource_id = ?, updated_at = NOW()
				WHERE language_id = ? AND profile_code = ? AND profile_status = ?""", resourceId, languageId,
				profileCode, profileStatus);
	}

	/** V1 section 19.2: a rules resource backing an ACTIVE profile cannot be unpublished. */
	boolean backsActiveProfile(UUID resourceId) {
		return Boolean.TRUE.equals(jdbc.queryForObject("""
				SELECT EXISTS (SELECT 1 FROM transliteration_profiles
				               WHERE rules_resource_id = ? AND profile_status = 'ACTIVE')""", Boolean.class, resourceId));
	}

	// ------------------------------------------------------------------ signing keys

	List<KeyRow> keys() {
		return jdbc.query(KEY_SELECT + " ORDER BY valid_from, key_id", KEY);
	}

	Optional<KeyRow> key(String keyId) {
		return jdbc.query(KEY_SELECT + " WHERE key_id = ?", KEY, keyId).stream().findFirst();
	}

	Optional<KeyRow> lockKey(String keyId) {
		return jdbc.query(KEY_SELECT + " WHERE key_id = ? FOR UPDATE", KEY, keyId).stream().findFirst();
	}

	void insertKey(String keyId, String publicKeyBase64, String endorsedBy, String endorsement) {
		jdbc.update("""
				INSERT INTO resource_signing_keys (key_id, algorithm, public_key, key_status, endorsed_by_key_id,
				    endorsement_signature)
				VALUES (?, 'ED25519', ?, 'ACTIVE', ?, ?)""", keyId, publicKeyBase64, endorsedBy, endorsement);
	}

	void setKeyStatus(String keyId, String status) {
		jdbc.update("UPDATE resource_signing_keys SET key_status = ?, updated_at = NOW() WHERE key_id = ?", status, keyId);
	}

	// ------------------------------------------------------------------ datasets

	List<DatasetRow> datasets(List<String> codes) {
		if (codes.isEmpty()) {
			return List.of();
		}
		return jdbc.query("""
				SELECT id, dataset_code, license_code, permitted_uses, review_status
				FROM language_datasets WHERE dataset_code = ANY (?)""",
				(rs, i) -> new DatasetRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
						Arrays.asList((String[]) rs.getArray(4).getArray()), rs.getString(5)),
				(Object) codes.toArray(String[]::new));
	}

	/** Inserts the dataset unless its code exists; returns whether a row was created. */
	boolean insertDataset(String code, String name, UUID languageId, String sourceUrl, String licenseCode,
			String licenseUrl, String consentBasis) {
		return jdbc.update("""
				INSERT INTO language_datasets (dataset_code, name, language_id, source_url, license_code, license_url,
				    permitted_uses, consent_basis, review_status)
				VALUES (?, ?, ?, ?, ?, ?, ARRAY['COMMERCIAL']::text[], ?, 'PENDING_REVIEW')
				ON CONFLICT (dataset_code) DO NOTHING""", code, name, languageId, sourceUrl, licenseCode, licenseUrl,
				consentBasis) == 1;
	}

	List<String> datasetCodes(UUID resourceId) {
		return jdbc.queryForList("""
				SELECT d.dataset_code FROM language_resource_datasets rd JOIN language_datasets d ON d.id = rd.dataset_id
				WHERE rd.resource_id = ? ORDER BY d.dataset_code""", String.class, resourceId);
	}

	void linkDataset(UUID resourceId, UUID datasetId) {
		jdbc.update("INSERT INTO language_resource_datasets (resource_id, dataset_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
				resourceId, datasetId);
	}

	// ------------------------------------------------------------------ resources

	Optional<ResourceRow> resource(UUID id) {
		return jdbc.query(RESOURCE_SELECT + " WHERE r.id = ?", RESOURCE, id).stream().findFirst();
	}

	Optional<ResourceRow> lockResource(UUID id) {
		return jdbc.query(RESOURCE_SELECT + " WHERE r.id = ? FOR UPDATE OF r", RESOURCE, id).stream().findFirst();
	}

	boolean resourceExists(UUID id) {
		return Boolean.TRUE.equals(
				jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM language_resources WHERE id = ?)", Boolean.class, id));
	}

	/**
	 * Versions of the slot (language, type, platform) that were ever visible to a client channel (staged, published,
	 * retired or revoked: {@code published_at} set), other than {@code exceptId}. A device may hold any of them, so a new
	 * pack must be newer than all (pack spec 8.1, A1.6 version floor).
	 */
	List<String> releasedVersions(UUID languageId, String resourceType, String platform, UUID exceptId) {
		return jdbc.queryForList("""
				SELECT resource_version FROM language_resources
				WHERE language_id = ? AND resource_type = ? AND platform = ? AND published_at IS NOT NULL AND id <> ?""",
				String.class, languageId, resourceType, platform, exceptId);
	}

	/** Locks the slot so concurrent stage/publish calls in one slot are serialised (downgrade check, then update). */
	void lockSlot(UUID languageId, String resourceType, String platform) {
		jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null,
				"language_resource_slot:" + languageId + ":" + resourceType + ":" + platform);
	}

	void insertDraft(UUID id, UUID languageId, String resourceType, String version, String platform,
			String artifactUri, String sha256, long sizeBytes, long containerSizeBytes, String minAppVersion,
			String deviceRequirementsJson, String licenseCode, UUID signingKeyRowId, String signatureBase64,
			String manifestJson) {
		jdbc.update("""
				INSERT INTO language_resources (id, language_id, resource_type, resource_version, platform, release_channel,
				    rollout_percentage, artifact_uri, sha256_checksum, size_bytes, container_size_bytes, min_app_version,
				    device_requirements, license_code, signature_key_id, manifest_signature, manifest_json,
				    publication_status)
				VALUES (?, ?, ?, ?, ?, 'INTERNAL', 0, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, 'DRAFT')""", id, languageId,
				resourceType, version, platform, artifactUri, sha256, sizeBytes, containerSizeBytes, minAppVersion,
				deviceRequirementsJson, licenseCode, signingKeyRowId, signatureBase64, manifestJson);
	}

	/** Makes the row visible on {@code channel} with a freshly signed container. */
	void publish(UUID id, String channel, int rolloutPercentage, String minAppVersion, String artifactUri,
			long containerSizeBytes, UUID signingKeyRowId, String signatureBase64, String manifestJson,
			String releaseNotes) {
		jdbc.update("""
				UPDATE language_resources
				SET publication_status = 'PUBLISHED', release_channel = ?, rollout_percentage = ?, min_app_version = ?,
				    artifact_uri = ?, container_size_bytes = ?, signature_key_id = ?, manifest_signature = ?,
				    manifest_json = ?::jsonb, release_notes = COALESCE(?, release_notes), published_at = NOW(),
				    updated_at = NOW()
				WHERE id = ?""", channel, rolloutPercentage, minAppVersion, artifactUri, containerSizeBytes, signingKeyRowId,
				signatureBase64, manifestJson, releaseNotes, id);
	}

	void retire(UUID id) {
		jdbc.update("UPDATE language_resources SET publication_status = 'RETIRED', updated_at = NOW() WHERE id = ?", id);
	}
}
