package com.qalsmartkeyboard.languages;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.administration.AdminAccess;
import com.qalsmartkeyboard.administration.AuditTrail;
import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.languages.AdminDtos.AdminLanguageResourceDto;
import com.qalsmartkeyboard.languages.AdminDtos.DatasetDto;
import com.qalsmartkeyboard.languages.AdminDtos.DatasetListDto;
import com.qalsmartkeyboard.languages.AdminDtos.DatasetRecord;
import com.qalsmartkeyboard.languages.AdminDtos.DatasetRegistrationRequest;
import com.qalsmartkeyboard.languages.AdminDtos.PublishRequest;
import com.qalsmartkeyboard.languages.PublishingRepository.DatasetRow;
import com.qalsmartkeyboard.languages.PublishingRepository.KeyRow;
import com.qalsmartkeyboard.languages.PublishingRepository.ResourceRow;
import com.qalsmartkeyboard.languages.pack.KeyRing;
import com.qalsmartkeyboard.languages.pack.KeyStatus;
import com.qalsmartkeyboard.languages.pack.LicenseAllowList;
import com.qalsmartkeyboard.languages.pack.PackLimits;
import com.qalsmartkeyboard.languages.pack.PackManifest;
import com.qalsmartkeyboard.languages.pack.PackRejectedException;
import com.qalsmartkeyboard.languages.pack.PackSigner;
import com.qalsmartkeyboard.languages.pack.PackVerifier;
import com.qalsmartkeyboard.languages.pack.PackWriter;
import com.qalsmartkeyboard.languages.pack.SemVer;
import com.qalsmartkeyboard.languages.pack.TransliterationProfileArtifact;
import com.qalsmartkeyboard.languages.pack.TrustedKey;
import com.qalsmartkeyboard.languages.pack.VerifiedPack;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.ApiFieldError;
import com.qalsmartkeyboard.platform.outbox.OutboxPublisher;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Admin pack publishing (B3.2, ADR 0019): upload, validate, stage, publish, retire.
 *
 * <p>State machine over {@code language_resources} (V1 statuses; {@code STAGED} is {@code PUBLISHED} on the
 * {@code INTERNAL} channel, which discovery shows only to internal builds, B3.1):
 *
 * <pre>
 * upload ──> DRAFT ──stage──> STAGED ──publish(BETA|STABLE)──> PUBLISHED ──publish(same or wider channel)──> PUBLISHED
 *              └──────────────────┴───────────────retire───────────────────────┴──> RETIRED
 * </pre>
 *
 * <p>Every upload is verified exactly like the device verifies it ({@link PackVerifier}: size, framing, key, strict
 * signature, manifest, key id, size, SHA-256) against {@code resource_signing_keys}, then held to the publishing policy:
 * no deltas yet, an ACTIVE signing key, a known language, a commercial licence (N10, {@link LicenseAllowList}), at least
 * one registered commercial dataset, the canonical profile code for rules packs (ADR 0007 D21) and a version newer than
 * every version of the slot that was ever released (rollback protection). Stage and publish re-check the policy and
 * <b>re-sign</b> the pack with the server key ({@link PackSigner}): the served container carries the target channel and
 * minimum app version in its signed manifest and the production signature (ADR 0007 D10), with the same resource id,
 * version and artifact, so devices treat it as the same pack (spec 8.1). Publishing to STABLE needs a second admin's
 * approval (Review D25). Every action and every refusal is audited; publication changes emit an outbox event for cache
 * and CDN invalidation (discovery keeps no cache of its own).
 */
class PackPublishingService {

	static final String ENTITY = "language_resource";
	static final String DATASET_ENTITY = "language_dataset";
	static final String UPLOAD = "LANGUAGE_RESOURCE_UPLOAD";
	static final String STAGE = "LANGUAGE_RESOURCE_STAGE";
	static final String APPROVE_STABLE = "LANGUAGE_RESOURCE_APPROVE_STABLE";
	static final String PUBLISH = "LANGUAGE_RESOURCE_PUBLISH";
	static final String RETIRE = "LANGUAGE_RESOURCE_RETIRE";
	static final String REGISTER_DATASET = "LANGUAGE_DATASET_REGISTER";
	static final String EVENT = "language-resource.publication-changed";

	private static final Logger log = LoggerFactory.getLogger(PackPublishingService.class);
	private static final Pattern DATASET_CODE = Pattern.compile("^[A-Za-z0-9._-]{1,80}$");
	private static final List<String> CHANNEL_ORDER = List.of("INTERNAL", "BETA", "STABLE");
	private static final int MAX_DATASETS = 50;
	private static final int MAX_RELEASE_NOTES = 2000;

	private final PublishingRepository repository;
	private final PackObjectStore store;
	private final PackSigner signer;
	private final LicenseAllowList licenses;
	private final AdminAccess access;
	private final AuditTrail audit;
	private final OutboxPublisher outbox;
	private final TransactionTemplate tx;
	private final JsonMapper json;
	private final PackLimits limits;
	private final long maxUploadBytes;

	PackPublishingService(PublishingRepository repository, PackObjectStore store, PackSigner signer,
			LicenseAllowList licenses, AdminAccess access, AuditTrail audit, OutboxPublisher outbox,
			PlatformTransactionManager transactions, JsonMapper json, long maxUploadBytes) {
		this.repository = repository;
		this.store = store;
		this.signer = signer;
		this.licenses = licenses;
		this.access = access;
		this.audit = audit;
		this.outbox = outbox;
		this.tx = new TransactionTemplate(transactions);
		this.json = json;
		this.maxUploadBytes = maxUploadBytes;
		// The device's default limits (pack spec 9), or a larger container cap if uploads may be larger: a pack the
		// devices would refuse as TOO_LARGE is refused here too, and re-signing never makes a stored pack unreadable.
		this.limits = new PackLimits(Math.max(maxUploadBytes, PackLimits.DEFAULT_MAX_CONTAINER_BYTES),
				PackLimits.DEFAULT_MAX_MANIFEST_BYTES, PackLimits.DEFAULT_MAX_ARTIFACT_BYTES);
	}

	// ------------------------------------------------------------------ upload

	AdminLanguageResourceDto upload(AuthenticatedUser user, InputStream body, long contentLength,
			List<String> datasetCodes) {
		AdminAccess.Actor actor = access.require(user, AdminAccess.RESOURCES_UPLOAD, UPLOAD, ENTITY, null);
		String[] entityId = { null };
		return audited(actor, UPLOAD, () -> entityId[0], () -> {
			byte[] bytes = readBounded(body, contentLength);
			List<String> codes = checkedDatasetCodes(datasetCodes);
			VerifiedPack pack = verify(bytes);
			PackManifest m = pack.manifest();
			entityId[0] = m.resourceId();
			UUID id = UUID.fromString(m.resourceId());
			if (m.isDelta()) {
				throw rejected("DELTA_NOT_SUPPORTED", "Delta packs cannot be published yet");
			}
			if (pack.key().status() != KeyStatus.ACTIVE) {
				throw rejected("KEY_NOT_ACTIVE", "New packs must be signed by an ACTIVE key");
			}
			UUID languageId = repository.languageId(m.languageCode())
					.orElseThrow(() -> rejected("UNKNOWN_LANGUAGE", "The pack's language is not in the catalogue"));
			checkLicense(m.licenseCode());
			List<DatasetRow> datasets = checkDatasets(codes);
			if ("TRANSLITERATION_RULES".equals(m.resourceType())) {
				checkProfile(languageId, profileCode(pack.artifact(), m.profile()), false);
			}
			if (repository.resourceExists(id)) {
				throw conflict("A resource with this resourceId already exists");
			}
			checkNewerThanReleased(languageId, m.resourceType(), m.platform(), m.version(), id);

			String containerSha = PackVerifier.sha256Hex(bytes);
			String uri = store.put("uploads/" + m.resourceId() + "/" + containerSha + ".pack", bytes);
			KeyRow keyRow = repository.key(pack.key().keyId()).orElseThrow();
			String manifestJson = new String(pack.manifestBytes(), StandardCharsets.UTF_8);
			String requirements = m.deviceRequirements() == null ? "{}" : json.writeValueAsString(m.deviceRequirements());
			Map<String, Object> meta = new LinkedHashMap<>();
			meta.put("languageCode", m.languageCode());
			meta.put("resourceType", m.resourceType());
			meta.put("version", m.version().toString());
			meta.put("platform", m.platform());
			meta.put("sha256", m.sha256());
			meta.put("containerSha256", containerSha);
			meta.put("uploadKeyId", pack.key().keyId());
			meta.put("datasetCodes", codes);
			tx.executeWithoutResult(s -> {
				try {
					repository.insertDraft(id, languageId, m.resourceType(), m.version().toString(), m.platform(), uri,
							m.sha256(), m.sizeBytes().longValueExact(), bytes.length,
							m.minAppVersion() == null ? null : m.minAppVersion().toString(), requirements,
							m.licenseCode(), keyRow.id(), Base64.getEncoder().encodeToString(pack.signature()),
							manifestJson);
				}
				catch (DuplicateKeyException e) {
					throw conflict("This version of the resource already exists");
				}
				datasets.forEach(d -> repository.linkDataset(id, d.id()));
				audit.record(new AuditTrail.Entry(actor.userId(), null, UPLOAD, ENTITY, m.resourceId(),
						AuditTrail.Outcome.SUCCESS, meta));
			});
			return dto(repository.resource(id).orElseThrow());
		});
	}

	// ------------------------------------------------------------------ read

	AdminLanguageResourceDto get(AuthenticatedUser user, UUID id) {
		access.require(user, AdminAccess.LANGUAGES_READ, "LANGUAGE_RESOURCE_READ", ENTITY, id.toString());
		return dto(repository.resource(id).orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND)));
	}

	// ------------------------------------------------------------------ stage, approve, publish, retire

	AdminLanguageResourceDto stage(AuthenticatedUser user, UUID id) {
		AdminAccess.Actor actor = access.require(user, AdminAccess.RESOURCES_EDIT, STAGE, ENTITY, id.toString());
		return audited(actor, STAGE, id::toString, () -> {
			tx.executeWithoutResult(s -> {
				ResourceRow row = lock(id);
				if (!"DRAFT".equals(row.status())) {
					throw conflict("Only a DRAFT resource can be staged");
				}
				release(actor, row, "INTERNAL", 100, row.minAppVersion(), null, null, STAGE);
			});
			return dto(repository.resource(id).orElseThrow());
		});
	}

	/** The second pair of eyes for STABLE (Review D25): the approval is this audit row. */
	AdminLanguageResourceDto approveStable(AuthenticatedUser user, UUID id) {
		AdminAccess.Actor actor = access.require(user, AdminAccess.RESOURCES_APPROVE, APPROVE_STABLE, ENTITY,
				id.toString());
		return audited(actor, APPROVE_STABLE, id::toString, () -> {
			tx.executeWithoutResult(s -> {
				ResourceRow row = lock(id);
				if (!"PUBLISHED".equals(row.status())) {
					throw conflict("Only a staged or published resource can be approved for STABLE");
				}
				audit.record(new AuditTrail.Entry(actor.userId(), null, APPROVE_STABLE, ENTITY, id.toString(),
						AuditTrail.Outcome.SUCCESS, Map.of("sha256", row.sha256())));
			});
			return dto(repository.resource(id).orElseThrow());
		});
	}

	AdminLanguageResourceDto publish(AuthenticatedUser user, UUID id, PublishRequest request) {
		AdminAccess.Actor actor = access.require(user, AdminAccess.RESOURCES_PUBLISH, PUBLISH, ENTITY, id.toString());
		return audited(actor, PUBLISH, id::toString, () -> {
			PublishRequest req = checkedPublishRequest(request);
			tx.executeWithoutResult(s -> {
				ResourceRow row = lock(id);
				if ("DRAFT".equals(row.status())) {
					throw conflict("Stage the resource before publishing it");
				}
				if (!"PUBLISHED".equals(row.status())) {
					throw conflict("A retired or revoked resource cannot be published");
				}
				if (CHANNEL_ORDER.indexOf(req.channel()) < CHANNEL_ORDER.indexOf(row.channel())) {
					throw conflict("A published resource cannot move to a narrower channel; retire it instead");
				}
				UUID approver = null;
				if ("STABLE".equals(req.channel())) {
					approver = audit.approver(APPROVE_STABLE, ENTITY, id.toString(), actor.userId())
							.orElseThrow(() -> new ApiException(ApiErrorCode.APPROVAL_REQUIRED, HttpStatus.FORBIDDEN,
									"Publishing to STABLE needs the approval of a second admin"));
				}
				String minApp = req.minAppVersion() != null ? req.minAppVersion() : row.minAppVersion();
				release(actor, row, req.channel(), req.rolloutPercentage(), minApp, req.releaseNotes(), approver,
						PUBLISH);
			});
			return dto(repository.resource(id).orElseThrow());
		});
	}

	AdminLanguageResourceDto retire(AuthenticatedUser user, UUID id) {
		AdminAccess.Actor actor = access.require(user, AdminAccess.RESOURCES_PUBLISH, RETIRE, ENTITY, id.toString());
		return audited(actor, RETIRE, id::toString, () -> {
			tx.executeWithoutResult(s -> {
				ResourceRow row = lock(id);
				if (!"DRAFT".equals(row.status()) && !"PUBLISHED".equals(row.status())) {
					throw conflict("The resource is already retired or revoked");
				}
				if (repository.backsActiveProfile(id)) {
					throw conflict("The resource backs an ACTIVE transliteration profile; retire the profile first");
				}
				repository.retire(id);
				outbox.publish(ENTITY, id.toString(), EVENT,
						Map.of("resourceId", id.toString(), "status", "RETIRED", "channel", row.channel()));
				audit.record(new AuditTrail.Entry(actor.userId(), null, RETIRE, ENTITY, id.toString(),
						AuditTrail.Outcome.SUCCESS, Map.of("previousState", state(row))));
			});
			return dto(repository.resource(id).orElseThrow());
		});
	}

	// ------------------------------------------------------------------ datasets

	DatasetListDto registerDatasets(AuthenticatedUser user, DatasetRegistrationRequest request) {
		AdminAccess.Actor actor = access.require(user, AdminAccess.RESOURCES_UPLOAD, REGISTER_DATASET, DATASET_ENTITY,
				null);
		return audited(actor, REGISTER_DATASET, () -> null, () -> {
			List<DatasetRecord> records = request == null || request.datasets() == null ? List.of() : request.datasets();
			List<ApiFieldError> errors = new ArrayList<>();
			if (records.isEmpty() || records.size() > 100) {
				errors.add(new ApiFieldError("datasets", "SIZE", "Between 1 and 100 datasets are required."));
			}
			Map<String, UUID> languages = new LinkedHashMap<>();
			for (int i = 0; i < records.size() && errors.isEmpty(); i++) {
				DatasetRecord r = records.get(i);
				String f = "datasets[" + i + "].";
				if (r == null || r.datasetCode() == null || !DATASET_CODE.matcher(r.datasetCode()).matches()) {
					errors.add(new ApiFieldError(f + "datasetCode", "INVALID", "1-80 characters of A-Z a-z 0-9 . _ -."));
					continue;
				}
				LicenseAllowList.Verdict verdict = licenses.check(r.licenseCode());
				if (verdict != LicenseAllowList.Verdict.ALLOWED) {
					errors.add(new ApiFieldError(f + "licenseCode", licenseReason(verdict),
							"The licence must be on the commercial-use allow-list."));
				}
				if (r.name() != null && r.name().length() > 200) {
					errors.add(new ApiFieldError(f + "name", "TOO_LONG", "At most 200 characters."));
				}
				if (r.languageCode() != null && !"mul".equals(r.languageCode())) {
					Optional<UUID> lang = repository.languageId(r.languageCode());
					if (lang.isEmpty()) {
						errors.add(new ApiFieldError(f + "languageCode", "UNKNOWN", "Not a catalogue language."));
					}
					else {
						languages.put(r.languageCode(), lang.get());
					}
				}
			}
			if (!errors.isEmpty()) {
				throw new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, null, errors, Map.of());
			}
			return tx.execute(s -> {
				List<DatasetDto> out = new ArrayList<>();
				for (DatasetRecord r : records) {
					LicenseAllowList.Entry entry = licenses.entry(r.licenseCode()).orElseThrow();
					boolean created = repository.insertDataset(r.datasetCode(),
							r.name() == null || r.name().isBlank() ? r.datasetCode() : r.name(),
							r.languageCode() == null ? null : languages.get(r.languageCode()), r.sourceUrl(),
							r.licenseCode(), r.licenseUrl() != null ? r.licenseUrl() : entry.url(), entry.consentBasis());
					DatasetRow row = repository.datasets(List.of(r.datasetCode())).getFirst();
					if (!row.licenseCode().equals(r.licenseCode())) {
						throw conflict("A dataset with this code is registered under another licence");
					}
					if (created) {
						audit.record(new AuditTrail.Entry(actor.userId(), null, REGISTER_DATASET, DATASET_ENTITY,
								r.datasetCode(), AuditTrail.Outcome.SUCCESS, Map.of("licenseCode", r.licenseCode())));
					}
					out.add(new DatasetDto(row.code(), row.licenseCode(), row.reviewStatus(), row.permittedUses(),
							created));
				}
				return new DatasetListDto(out);
			});
		});
	}

	// ------------------------------------------------------------------ release (stage and publish)

	/**
	 * Re-checks the policy for {@code channel}, re-signs the container with the server key for that channel and
	 * minimum app version, stores it and makes the row visible there.
	 */
	private void release(AdminAccess.Actor actor, ResourceRow row, String channel, int rollout, String minAppVersion,
			String releaseNotes, UUID approver, String action) {
		repository.lockSlot(row.languageId(), row.resourceType(), row.platform());
		checkLicense(row.licenseCode());
		checkDatasets(repository.datasetCodes(row.id()));
		SemVer version = SemVer.parse(row.version()).orElseThrow();
		checkNewerThanReleased(row.languageId(), row.resourceType(), row.platform(), version, row.id());

		byte[] artifact = storedArtifact(row);
		ObjectNode manifest = storedManifest(row);
		if ("TRANSLITERATION_RULES".equals(row.resourceType())) {
			// N9: DRAFT profiles may be tested on INTERNAL, only ACTIVE ones reach BETA/STABLE users.
			checkProfile(row.languageId(), profileCode(artifact, manifest.get("profile")), !"INTERNAL".equals(channel));
		}
		KeyRow signerRow = activeSignerRow();
		manifest.put("releaseChannel", channel);
		manifest.put("signatureKeyId", signer.keyId());
		if (minAppVersion != null) {
			manifest.put("minAppVersion", minAppVersion);
		}
		else {
			manifest.remove("minAppVersion");
		}
		manifest.remove("artifactUrl"); // informational only, and the stored location is not a client URL
		byte[] manifestBytes = json.writeValueAsBytes(manifest);
		byte[] container = PackWriter.sign(signer, manifestBytes, artifact);
		// The served container must pass the device's verification with the server key alone.
		VerifiedPack check = PackVerifier.verify(container,
				KeyRing.of(new TrustedKey(signer.keyId(), signer.publicKey(), KeyStatus.ACTIVE)), limits);
		if (!check.manifest().sha256().equals(row.sha256())) {
			throw new IllegalStateException("Re-signed pack does not commit to the stored artifact");
		}
		String containerSha = PackVerifier.sha256Hex(container);
		String key = "packs/" + row.languageCode() + "/" + row.resourceType().toLowerCase(Locale.ROOT) + "/"
				+ row.platform().toLowerCase(Locale.ROOT) + "/" + row.version() + "/" + row.id() + "-"
				+ containerSha.substring(0, 16) + ".pack";
		String uri = store.put(key, container);
		repository.publish(row.id(), channel, rollout, minAppVersion, uri, container.length, signerRow.id(),
				Base64.getEncoder().encodeToString(check.signature()),
				new String(manifestBytes, StandardCharsets.UTF_8), releaseNotes);
		if ("TRANSLITERATION_RULES".equals(row.resourceType())) {
			repository.linkProfiles(row.languageId(), profileCode(artifact, manifest.get("profile")),
					"INTERNAL".equals(channel) ? "DRAFT" : "ACTIVE", row.id());
		}
		outbox.publish(ENTITY, row.id().toString(), EVENT,
				Map.of("resourceId", row.id().toString(), "status", "PUBLISHED", "channel", channel));
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("previousState", state(row));
		meta.put("channel", channel);
		meta.put("rolloutPercentage", rollout);
		if (minAppVersion != null) {
			meta.put("minAppVersion", minAppVersion);
		}
		meta.put("signingKeyId", signer.keyId());
		meta.put("containerSha256", containerSha);
		audit.record(new AuditTrail.Entry(actor.userId(), approver, action, ENTITY, row.id().toString(),
				AuditTrail.Outcome.SUCCESS, meta));
	}

	/** The server key must be registered ACTIVE with the same public key, or no client could trust what it signs. */
	private KeyRow activeSignerRow() {
		String keyId = signer.keyId();
		KeyRow row = repository.key(keyId).orElseThrow(
				() -> conflict("The server signing key is not registered in resource_signing_keys"));
		if (!"ACTIVE".equals(row.status())
				|| !Arrays.equals(decodeKey(row.publicKey()), signer.publicKey())) {
			throw conflict("The server signing key is not an ACTIVE registered key with the same public key");
		}
		return row;
	}

	/** The artifact of the stored container, checked against the row (the database, not the bucket, is trusted). */
	private byte[] storedArtifact(ResourceRow row) {
		byte[] stored = store.get(row.artifactUri(), limits.maxContainerBytes());
		PackVerifier.Framing f;
		try {
			f = PackVerifier.framing(stored, limits);
		}
		catch (PackRejectedException e) {
			throw new IllegalStateException("The stored pack container is damaged");
		}
		byte[] artifact = Arrays.copyOfRange(stored, f.artifactOffset(), f.artifactOffset() + f.artifactLength());
		if (row.sizeBytes() == null || artifact.length != row.sizeBytes()
				|| !PackVerifier.sha256Hex(artifact).equals(row.sha256())) {
			throw new IllegalStateException("The stored pack artifact does not match the resource row");
		}
		return artifact;
	}

	private ObjectNode storedManifest(ResourceRow row) {
		try {
			JsonNode node = json.readTree(row.manifestJson());
			if (node instanceof ObjectNode object && object.size() > 0) {
				return object;
			}
		}
		catch (JacksonException e) {
			// fall through
		}
		throw new IllegalStateException("The stored manifest of the resource is not usable");
	}

	// ------------------------------------------------------------------ policy

	private VerifiedPack verify(byte[] bytes) {
		try {
			return PackVerifier.verify(bytes, trustedKeys(), limits);
		}
		catch (PackRejectedException e) {
			HttpStatus status = e.reason().name().equals("TOO_LARGE") ? HttpStatus.CONTENT_TOO_LARGE
					: HttpStatus.UNPROCESSABLE_CONTENT;
			throw new ApiException(ApiErrorCode.PACK_REJECTED, status, e.detail(), List.of(),
					Map.of("reason", e.reason().name()));
		}
	}

	/** {@code resource_signing_keys} as the trusted key set (ACTIVE and RETIRING verify, REVOKED never does). */
	KeyRing trustedKeys() {
		List<TrustedKey> keys = new ArrayList<>();
		for (KeyRow k : repository.keys()) {
			keys.add(new TrustedKey(k.keyId(), decodeKey(k.publicKey()), KeyStatus.valueOf(k.status())));
		}
		try {
			return new KeyRing(keys);
		}
		catch (IllegalArgumentException e) {
			throw new IllegalStateException("resource_signing_keys contains an unusable key: " + e.getMessage());
		}
	}

	static byte[] decodeKey(String base64) {
		try {
			return Base64.getDecoder().decode(base64.strip());
		}
		catch (IllegalArgumentException e) {
			return new byte[0];
		}
	}

	private void checkLicense(String code) {
		LicenseAllowList.Verdict verdict = licenses.check(code);
		if (verdict != LicenseAllowList.Verdict.ALLOWED) {
			throw rejected(licenseReason(verdict), "The pack licence is not on the commercial-use allow-list");
		}
	}

	private static String licenseReason(LicenseAllowList.Verdict verdict) {
		return switch (verdict) {
			case MISSING -> "LICENSE_MISSING";
			case UNKNOWN -> "LICENSE_UNKNOWN";
			case NOT_COMMERCIAL -> "LICENSE_NOT_COMMERCIAL";
			case ALLOWED -> "ALLOWED";
		};
	}

	/** Every dataset must exist, carry an allow-listed commercial licence and permit commercial use (N10). */
	private List<DatasetRow> checkDatasets(List<String> codes) {
		if (codes.isEmpty()) {
			throw rejected("DATASET_MISSING", "A pack needs at least one registered source dataset");
		}
		List<DatasetRow> rows = repository.datasets(codes);
		if (rows.size() != new LinkedHashSet<>(codes).size()) {
			throw rejected("DATASET_UNKNOWN", "A named dataset is not registered");
		}
		for (DatasetRow d : rows) {
			boolean commercial = licenses.check(d.licenseCode()) == LicenseAllowList.Verdict.ALLOWED
					&& d.permittedUses().contains("COMMERCIAL") && !d.permittedUses().contains("RESEARCH_ONLY")
					&& !"REJECTED".equals(d.reviewStatus()) && !"RESTRICTED".equals(d.reviewStatus());
			if (!commercial) {
				throw rejected("DATASET_NOT_COMMERCIAL", "A source dataset is not cleared for commercial use");
			}
		}
		return rows;
	}

	/** D21: the rules pack's profile id is a backend profile code of the language (ACTIVE when users will see it). */
	private void checkProfile(UUID languageId, String profileCode, boolean requireActive) {
		List<String> statuses = repository.profileStatuses(languageId, profileCode);
		if (statuses.isEmpty() || statuses.stream().allMatch("RETIRED"::equals)) {
			throw rejected("PROFILE_UNKNOWN", "The rules pack's profile id is not a profile code of the language");
		}
		if (requireActive && !statuses.contains("ACTIVE")) {
			throw rejected("PROFILE_NOT_ACTIVE", "The rules pack's profile is not ACTIVE");
		}
	}

	/** The artifact's {@code profileId}; a manifest {@code profile.code}, if present, must agree. */
	private static String profileCode(byte[] artifact, JsonNode manifestProfile) {
		String id = TransliterationProfileArtifact.profileId(artifact)
				.orElseThrow(() -> rejected("INVALID_ARTIFACT", "The rules pack is not a transliteration profile"));
		if (manifestProfile != null && manifestProfile.isObject() && manifestProfile.has("code")
				&& !id.equals(manifestProfile.get("code").asString(null))) {
			throw rejected("PROFILE_MISMATCH", "The manifest profile code differs from the artifact's profile id");
		}
		return id;
	}

	private void checkNewerThanReleased(UUID languageId, String type, String platform, SemVer version, UUID id) {
		for (String v : repository.releasedVersions(languageId, type, platform, id)) {
			Optional<SemVer> released = SemVer.parse(v);
			if (released.isEmpty() || version.compareTo(released.get()) <= 0) {
				throw rejected("DOWNGRADE", "A version at least as new was already released for this slot");
			}
		}
	}

	// ------------------------------------------------------------------ request checks

	private byte[] readBounded(InputStream body, long contentLength) {
		if (contentLength > maxUploadBytes) {
			throw tooLarge();
		}
		try {
			byte[] bytes = body.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxUploadBytes + 1));
			if (bytes.length > maxUploadBytes) {
				throw tooLarge();
			}
			if (bytes.length == 0) {
				throw rejected("MALFORMED", "The request body is empty");
			}
			return bytes;
		}
		catch (IOException e) {
			throw new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, "The upload was interrupted");
		}
	}

	private static ApiException tooLarge() {
		return new ApiException(ApiErrorCode.PACK_REJECTED, HttpStatus.CONTENT_TOO_LARGE,
				"The pack exceeds the upload limit", List.of(), Map.of("reason", "TOO_LARGE"));
	}

	private static List<String> checkedDatasetCodes(List<String> codes) {
		List<String> out = codes == null ? List.of() : List.copyOf(new LinkedHashSet<>(codes));
		if (out.size() > MAX_DATASETS || out.stream().anyMatch(c -> c == null || !DATASET_CODE.matcher(c).matches())) {
			throw new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, null,
					List.of(new ApiFieldError("datasetCode", "INVALID",
							"Up to 50 dataset codes of 1-80 characters of A-Z a-z 0-9 . _ -.")),
					Map.of());
		}
		return out;
	}

	private static PublishRequest checkedPublishRequest(PublishRequest r) {
		List<ApiFieldError> errors = new ArrayList<>();
		if (r == null) {
			errors.add(new ApiFieldError("channel", "REQUIRED", "channel is required."));
			throw new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, null, errors, Map.of());
		}
		if (!"BETA".equals(r.channel()) && !"STABLE".equals(r.channel())) {
			errors.add(new ApiFieldError("channel", "INVALID", "channel must be BETA or STABLE."));
		}
		if (r.rolloutPercentage() == null || r.rolloutPercentage() < 0 || r.rolloutPercentage() > 100) {
			errors.add(new ApiFieldError("rolloutPercentage", "INVALID", "rolloutPercentage must be 0-100."));
		}
		if (r.minAppVersion() != null && SemVer.parse(r.minAppVersion()).isEmpty()) {
			errors.add(new ApiFieldError("minAppVersion", "INVALID", "minAppVersion must be a semantic version."));
		}
		if (r.releaseNotes() != null && r.releaseNotes().length() > MAX_RELEASE_NOTES) {
			errors.add(new ApiFieldError("releaseNotes", "TOO_LONG", "releaseNotes is at most 2000 characters."));
		}
		if (!errors.isEmpty()) {
			throw new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, null, errors, Map.of());
		}
		return r;
	}

	// ------------------------------------------------------------------ helpers

	private ResourceRow lock(UUID id) {
		return repository.lockResource(id).orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
	}

	/**
	 * Runs an admin action; a refusal or failure is audited ({@code FAILURE}, with the problem code and pack reason only)
	 * in its own transaction before the error propagates.
	 */
	private <T> T audited(AdminAccess.Actor actor, String action, Supplier<String> entityId, Supplier<T> work) {
		try {
			return work.get();
		}
		catch (ApiException e) {
			Map<String, Object> meta = new LinkedHashMap<>();
			meta.put("code", e.code().name());
			Object reason = e.extensions().get("reason");
			if (reason != null) {
				meta.put("reason", reason);
			}
			recordFailure(actor, action, entityId.get(), meta);
			throw e;
		}
		catch (RuntimeException e) {
			log.warn("Admin action {} failed: {}", action, e.getClass().getSimpleName());
			recordFailure(actor, action, entityId.get(), Map.of("code", ApiErrorCode.INTERNAL_ERROR.name()));
			throw e;
		}
	}

	private void recordFailure(AdminAccess.Actor actor, String action, String entityId, Map<String, Object> meta) {
		try {
			audit.recordIndependently(new AuditTrail.Entry(actor.userId(), null, action,
					REGISTER_DATASET.equals(action) ? DATASET_ENTITY : ENTITY, entityId, AuditTrail.Outcome.FAILURE,
					meta));
		}
		catch (RuntimeException auditFailure) {
			log.error("Could not audit the failed admin action {}", action);
		}
	}

	static ApiException rejected(String reason, String detail) {
		return new ApiException(ApiErrorCode.PACK_REJECTED, HttpStatus.UNPROCESSABLE_CONTENT, detail, List.of(),
				Map.of("reason", reason));
	}

	static ApiException conflict(String detail) {
		return new ApiException(ApiErrorCode.PUBLICATION_CONFLICT, HttpStatus.CONFLICT, detail);
	}

	static String state(ResourceRow row) {
		if ("PUBLISHED".equals(row.status())) {
			return "INTERNAL".equals(row.channel()) ? "STAGED" : "PUBLISHED";
		}
		return row.status();
	}

	private AdminLanguageResourceDto dto(ResourceRow r) {
		return new AdminLanguageResourceDto(r.id(), r.languageCode(), r.resourceType(), r.version(), r.platform(),
				state(r), r.channel(), r.rolloutPercentage(), r.minAppVersion(), r.sha256(), r.sizeBytes(),
				r.containerSizeBytes(), r.licenseCode(), r.keyId(), r.artifactUri(), repository.datasetCodes(r.id()),
				r.releaseNotes(), r.publishedAt() == null ? null : r.publishedAt().toInstant(),
				r.createdAt().toInstant(), r.updatedAt().toInstant());
	}
}
