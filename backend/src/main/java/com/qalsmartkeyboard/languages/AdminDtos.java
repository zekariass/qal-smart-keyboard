package com.qalsmartkeyboard.languages;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Bodies of the {@code /admin/v1} language-resource operations, named after the contract schemas (B3.2). */
final class AdminDtos {

	private AdminDtos() {
	}

	/**
	 * {@code AdminLanguageResource}. {@code state}: {@code DRAFT} (uploaded and validated, invisible), {@code STAGED}
	 * (published on the INTERNAL channel only), {@code PUBLISHED} (BETA or STABLE), {@code RETIRED}, {@code REVOKED}.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record AdminLanguageResourceDto(UUID id, String languageCode, String resourceType, String version, String platform,
			String state, String channel, int rolloutPercentage, String minAppVersion, String sha256, Long sizeBytes,
			Long containerSizeBytes, String licenseCode, String signatureKeyId, String artifactUri,
			List<String> datasetCodes, String releaseNotes, Instant publishedAt, Instant createdAt, Instant updatedAt) {
	}

	/** {@code PublishLanguageResourceRequest}. */
	record PublishRequest(String channel, Integer rolloutPercentage, String minAppVersion, String releaseNotes) {
	}

	/** One {@code LanguageDatasetRecord}: the shape of a {@code datasets.manifest.json} entry (other members ignored). */
	record DatasetRecord(String datasetCode, String name, String languageCode, String sourceUrl, String licenseCode,
			String licenseUrl) {
	}

	/** {@code RegisterLanguageDatasetsRequest}: a {@code datasets.manifest.json} document is accepted as is. */
	record DatasetRegistrationRequest(List<DatasetRecord> datasets) {
	}

	record DatasetDto(String datasetCode, String licenseCode, String reviewStatus, List<String> permittedUses,
			boolean created) {
	}

	record DatasetListDto(List<DatasetDto> items) {
	}

	/** {@code PackSigner}: the server's current signing key and whether it is registered. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record PackSignerDto(String keyId, String algorithm, String publicKey, boolean registered, String status) {
	}

	/** {@code RegisterSigningKeyRequest}. */
	record SigningKeyRegistration(String keyId, String publicKey, Boolean endorse) {
	}

	/** {@code AdminSigningKey}. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record AdminSigningKeyDto(String keyId, String algorithm, String publicKey, String status, Instant validFrom,
			String endorsedByKeyId, String endorsementSignature) {
	}
}
