package com.qalsmartkeyboard.languages;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import com.qalsmartkeyboard.configuration.AppVersion;
import com.qalsmartkeyboard.languages.LanguageDtos.DeviceRequirementsDto;
import com.qalsmartkeyboard.languages.LanguageDtos.LanguageDto;
import com.qalsmartkeyboard.languages.LanguageDtos.LanguageResourceDto;
import com.qalsmartkeyboard.languages.LanguageDtos.ResourceListDto;
import com.qalsmartkeyboard.languages.LanguageDtos.SigningKeyDto;
import com.qalsmartkeyboard.languages.LanguageDtos.TransliterationDto;
import com.qalsmartkeyboard.languages.LanguageDtos.TransliterationProfileDto;
import com.qalsmartkeyboard.languages.LanguagesRepository.KeyRow;
import com.qalsmartkeyboard.languages.LanguagesRepository.LanguageRow;
import com.qalsmartkeyboard.languages.LanguagesRepository.ProfileRow;
import com.qalsmartkeyboard.languages.LanguagesRepository.ResourceRow;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.ApiFieldError;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read APIs for languages, transliteration profiles, language-resource (pack) discovery and signing keys.
 *
 * <p>Visibility rules for packs (all enforced here, none by the client):
 * <ul>
 * <li>Only {@code PUBLISHED} resources of active languages whose signing key is not {@code REVOKED}, with a known
 * artifact size, and of a resource type the contract can express.</li>
 * <li>Platform: {@code ANDROID} sees {@code SHARED} and {@code ANDROID} packs, {@code IOS} sees {@code SHARED} and
 * {@code IOS}, {@code WEB} sees {@code SHARED}.</li>
 * <li>Channel: a request for a channel sees that channel and every more stable one ({@code BETA} sees BETA and STABLE),
 * default {@code STABLE}. {@code INTERNAL} needs a valid access token and {@code qal.languages.internal-channel-enabled};
 * a single INTERNAL resource is otherwise a 404.</li>
 * <li>{@code minAppVersion} above the caller's {@code appVersion}: hidden from the list.</li>
 * <li>Staged rollout: 100% is visible to everyone; below that only to authenticated callers whose stable bucket
 * ({@code SHA-256(deviceId or userId : resourceId) mod 100}) is under the percentage. Anonymous callers have no stable
 * identity, so they never see a partial rollout.</li>
 * </ul>
 */
class LanguagesService {

	static final int DEFAULT_PAGE = 20;
	static final int MAX_PAGE = 100;
	private static final int BATCH = 100;
	private static final int MAX_BATCHES = 20;
	private static final Pattern LANGUAGE_CODE = Pattern.compile("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$");
	private static final Set<String> INPUT_MODES = Set.of("DIRECT_SCRIPT_LAYOUT", "PHONETIC_LATIN_TO_NATIVE",
			"LATIN_PASSTHROUGH");
	private static final Set<String> ACCELERATORS = Set.of("NONE", "NNAPI", "GPU", "NPU", "CORE_ML");
	private static final Logger log = LoggerFactory.getLogger(LanguagesService.class);

	/** Who is asking: {@code subject} is the device (else user) id of a valid access token, {@code null} if anonymous. */
	record Caller(UUID subject) {
		static final Caller ANONYMOUS = new Caller(null);

		boolean authenticated() {
			return subject != null;
		}
	}

	private final LanguagesRepository repository;
	private final ArtifactUrlSigner signer;
	private final boolean internalChannelEnabled;
	private final JsonMapper json = JsonMapper.builder().build();

	LanguagesService(LanguagesRepository repository, ArtifactUrlSigner signer, boolean internalChannelEnabled) {
		this.repository = repository;
		this.signer = signer;
		this.internalChannelEnabled = internalChannelEnabled;
	}

	// ------------------------------------------------------------------ languages

	List<LanguageDto> languages() {
		List<LanguageDto> out = new ArrayList<>();
		for (LanguageRow r : repository.activeLanguages()) {
			out.add(new LanguageDto(r.code(), r.englishName(), r.nativeName(), r.scriptCode(), r.writingDirection(),
					r.supportLevel(), r.launch(), inputModes(r.metadataJson())));
		}
		return out;
	}

	/** {@code metadata.inputModes} when it is a non-empty array of contract values; the catalogue has no column for it. */
	private List<String> inputModes(String metadataJson) {
		try {
			JsonNode modes = json.readTree(metadataJson).get("inputModes");
			if (modes == null || !modes.isArray()) {
				return null;
			}
			List<String> out = new ArrayList<>();
			for (JsonNode m : modes) {
				if (m.isString() && INPUT_MODES.contains(m.asString()) && !out.contains(m.asString())) {
					out.add(m.asString());
				}
			}
			return out.isEmpty() ? null : out;
		}
		catch (RuntimeException e) {
			return null;
		}
	}

	List<TransliterationProfileDto> profiles(String languageCode) {
		if (!LANGUAGE_CODE.matcher(languageCode).matches() || !repository.activeLanguageExists(languageCode)) {
			throw notFound();
		}
		List<TransliterationProfileDto> out = new ArrayList<>();
		for (ProfileRow p : repository.profiles(languageCode)) {
			out.add(new TransliterationProfileDto(p.code(), p.version(), p.displayName(), p.languageCode(),
					p.sourceScript(), p.targetScript(), p.inputScheme(), p.reversible(), p.status(),
					p.rulesResourceId()));
		}
		return out;
	}

	// ------------------------------------------------------------------ resources

	ResourceListDto listResources(PackPlatform platform, String appVersion, ReleaseChannel channel, String type,
			String languageCode, String cursor, Integer limit, Caller caller) {
		AppVersion version = AppVersion.parse(appVersion)
				.orElseThrow(() -> invalid("appVersion", "INVALID", "Must be a semantic version such as 2.0.3."));
		ReleaseChannel requested = channel == null ? ReleaseChannel.STABLE : channel;
		requireChannelAllowed(requested, caller);
		int pageSize = limit == null ? DEFAULT_PAGE : limit;
		if (pageSize < 1 || pageSize > MAX_PAGE) {
			throw invalid("limit", "OUT_OF_RANGE", "limit must be between 1 and " + MAX_PAGE + ".");
		}
		if (languageCode != null && !LANGUAGE_CODE.matcher(languageCode).matches()) {
			throw invalid("languageCode", "INVALID", "Not a valid language code.");
		}
		if (cursor != null && cursor.length() > 512) {
			throw invalid("cursor", "INVALID", "The cursor is not valid.");
		}
		OffsetDateTime afterAt = null;
		UUID afterId = null;
		if (cursor != null) {
			try {
				String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", 2);
				afterAt = OffsetDateTime.parse(parts[0]);
				afterId = UUID.fromString(parts[1]);
			}
			catch (RuntimeException e) {
				throw invalid("cursor", "INVALID", "The cursor is not valid.");
			}
		}
		List<String> platforms = platform.packPlatforms();
		List<String> channels = requested.visibleChannels();

		List<LanguageResourceDto> page = new ArrayList<>();
		String nextCursor = null;
		boolean exhausted = false;
		ResourceRow lastExamined = null;
		ResourceRow lastReturned = null;
		for (int i = 0; i < MAX_BATCHES && !exhausted && nextCursor == null; i++) {
			List<ResourceRow> rows = repository.resources(platforms, channels, type, languageCode, afterAt, afterId,
					BATCH);
			exhausted = rows.size() < BATCH;
			for (ResourceRow row : rows) {
				lastExamined = row;
				afterAt = row.publishedAt();
				afterId = row.id();
				if (!compatible(row, version) || !inRollout(row, caller)) {
					continue;
				}
				LanguageResourceDto dto = toDto(row, false);
				if (dto == null) {
					continue;
				}
				if (page.size() == pageSize) {
					nextCursor = cursorOf(lastReturned);
					break;
				}
				page.add(dto);
				lastReturned = row;
			}
		}
		if (nextCursor == null && !exhausted && lastExamined != null) {
			nextCursor = cursorOf(lastExamined); // scanned MAX_BATCHES of rows without filling the page
		}
		return new ResourceListDto(page, nextCursor);
	}

	LanguageResourceDto resource(UUID id, Caller caller) {
		ResourceRow row = repository.resource(id).orElseThrow(LanguagesService::notFound);
		if (ReleaseChannel.INTERNAL.name().equals(row.channel()) && !internalAllowed(caller)) {
			throw notFound();
		}
		if (!inRollout(row, caller)) {
			throw notFound();
		}
		LanguageResourceDto dto = toDto(row, true);
		if (dto == null) {
			throw notFound();
		}
		return dto;
	}

	List<SigningKeyDto> signingKeys() {
		List<SigningKeyDto> out = new ArrayList<>();
		for (KeyRow k : repository.signingKeys()) {
			out.add(new SigningKeyDto(k.keyId(), "Ed25519", k.publicKey(), k.status(),
					k.validFrom() == null ? null : k.validFrom().toInstant(),
					k.validUntil() == null ? null : k.validUntil().toInstant(), k.endorsedByKeyId(),
					k.endorsementSignature()));
		}
		return out;
	}

	// ------------------------------------------------------------------ rules

	private void requireChannelAllowed(ReleaseChannel channel, Caller caller) {
		if (channel != ReleaseChannel.INTERNAL) {
			return;
		}
		if (!caller.authenticated()) {
			throw new ApiException(ApiErrorCode.UNAUTHENTICATED);
		}
		if (!internalChannelEnabled) {
			throw invalid("channel", "NOT_AVAILABLE", "This release channel is not available.");
		}
	}

	private boolean internalAllowed(Caller caller) {
		return internalChannelEnabled && caller.authenticated();
	}

	/** {@code minAppVersion} unset or not above the caller; an unparsable stored value is treated as incompatible. */
	private static boolean compatible(ResourceRow row, AppVersion appVersion) {
		if (row.minAppVersion() == null || row.minAppVersion().isBlank()) {
			return true;
		}
		return AppVersion.parse(row.minAppVersion()).map(min -> appVersion.compareTo(min) >= 0).orElse(false);
	}

	static boolean inRollout(ResourceRow row, Caller caller) {
		if (row.rolloutPercentage() >= 100) {
			return true;
		}
		return caller.authenticated() && bucket(caller.subject(), row.id()) < row.rolloutPercentage();
	}

	/** Stable 0..99 bucket of (subject, resource). */
	static int bucket(UUID subject, UUID resourceId) {
		try {
			byte[] h = MessageDigest.getInstance("SHA-256")
					.digest((subject + ":" + resourceId).getBytes(StandardCharsets.UTF_8));
			long v = ((h[0] & 0xffL) << 24) | ((h[1] & 0xffL) << 16) | ((h[2] & 0xffL) << 8) | (h[3] & 0xffL);
			return (int) (v % 100);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	/** {@code null} when the stored artifact location is unusable (logged; it must not break discovery for others). */
	private LanguageResourceDto toDto(ResourceRow r, boolean single) {
		URI url;
		try {
			url = signer.sign(r.artifactUri());
		}
		catch (IllegalArgumentException e) {
			log.error("Language resource {} has an unusable artifact location; not served: {}", r.id(), e.getMessage());
			return null;
		}
		TransliterationDto translit = r.profileCode() == null ? null
				: new TransliterationDto(r.profileCode(), r.profileVersion(), r.normalizationForm());
		return new LanguageResourceDto(r.id(), r.languageCode(), r.resourceType(), r.version(), r.platform(),
				r.channel(), url.toString(), r.sha256(), r.sizeBytes(), r.containerSizeBytes(), r.baseResourceId(),
				r.keyId(), r.minAppVersion(), r.licenseCode(), deviceRequirements(r.deviceRequirementsJson()), translit,
				r.releaseNotes(), r.publishedAt() == null ? null : r.publishedAt().toInstant());
	}

	/**
	 * Maps the stored JSON to the contract object. Contract names are read as is; the older names in the V1 column comment
	 * ({@code wifiOnly}, {@code iosExtensionAllowed}) are accepted too. Values of the wrong type are dropped.
	 */
	private DeviceRequirementsDto deviceRequirements(String jsonText) {
		try {
			JsonNode n = json.readTree(jsonText);
			if (n == null || !n.isObject() || n.isEmpty()) {
				return null;
			}
			Integer ram = nonNegativeInt(n.get("minRamMb"));
			Integer storage = nonNegativeInt(n.get("minFreeStorageMb"));
			String accel = n.has("accelerator") && n.get("accelerator").isString()
					&& ACCELERATORS.contains(n.get("accelerator").asString()) ? n.get("accelerator").asString() : null;
			String os = n.has("minOsVersion") && n.get("minOsVersion").isString() ? n.get("minOsVersion").asString()
					: null;
			Boolean wifi = bool(n, "wifiOnlyDefault", "wifiOnly");
			Boolean ext = bool(n, "iosExtensionCompatible", "iosExtensionAllowed");
			if (ram == null && storage == null && accel == null && os == null && wifi == null && ext == null) {
				return null;
			}
			return new DeviceRequirementsDto(ram, storage, accel, os, wifi, ext);
		}
		catch (RuntimeException e) {
			return null;
		}
	}

	private static Integer nonNegativeInt(JsonNode n) {
		return n != null && n.isIntegralNumber() && n.canConvertToInt() && n.asInt() >= 0 ? n.asInt() : null;
	}

	private static Boolean bool(JsonNode n, String name, String legacy) {
		JsonNode v = n.has(name) ? n.get(name) : n.get(legacy);
		return v != null && v.isBoolean() ? v.asBoolean() : null;
	}

	private static String cursorOf(ResourceRow row) {
		if (row == null) {
			return null;
		}
		Instant at = row.publishedAt().toInstant();
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString((at + "|" + row.id()).getBytes(StandardCharsets.UTF_8));
	}

	/** The contract's {@code ResourceType} values; anything else is a 400 (a null type means no filter). */
	static String checkedType(String type) {
		if (type != null && !LanguagesRepository.CONTRACT_RESOURCE_TYPES.contains(type)) {
			throw invalid("type", "INVALID", "Not a supported resource type.");
		}
		return type;
	}

	static ApiException notFound() {
		return new ApiException(ApiErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, null);
	}

	static ApiException invalid(String field, String code, String message) {
		return new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, null,
				List.of(new ApiFieldError(field, code, message)), java.util.Map.of());
	}
}
