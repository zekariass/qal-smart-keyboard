package com.qalsmartkeyboard.languages;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Response bodies of the languages module, named after the contract schemas. Absent optional members are omitted. */
final class LanguageDtos {

	private LanguageDtos() {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record LanguageDto(String code, String englishName, String nativeName, String scriptCode, String writingDirection,
			String supportLevel, boolean isLaunchLanguage, List<String> inputModes) {
	}

	record LanguageListDto(List<LanguageDto> items) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record TransliterationProfileDto(String code, String version, String displayName, String languageCode,
			String sourceScript, String targetScript, String inputScheme, boolean reversible, String status,
			UUID rulesResourceId) {
	}

	record ProfileListDto(List<TransliterationProfileDto> items) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record DeviceRequirementsDto(Integer minRamMb, Integer minFreeStorageMb, String accelerator, String minOsVersion,
			Boolean wifiOnlyDefault, Boolean iosExtensionCompatible) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record TransliterationDto(String profileCode, String profileVersion, String normalizationForm) {
	}

	/** {@code LanguageResourceManifest}: discovery metadata only; the pack's own signed manifest is authoritative. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record LanguageResourceDto(UUID id, String languageCode, String resourceType, String version, String platform,
			String channel, String artifactUrl, String sha256, long sizeBytes, Long containerSizeBytes,
			UUID baseResourceId, String signatureKeyId, String minAppVersion, String licenseCode,
			DeviceRequirementsDto deviceRequirements, TransliterationDto transliteration, String releaseNotes,
			Instant publishedAt) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record ResourceListDto(List<LanguageResourceDto> items, String nextCursor) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record SigningKeyDto(String keyId, String algorithm, String publicKey, String status, Instant notBefore,
			Instant notAfter, String endorsedByKeyId, String endorsementSignature) {
	}

	record SigningKeyListDto(List<SigningKeyDto> items) {
	}
}
