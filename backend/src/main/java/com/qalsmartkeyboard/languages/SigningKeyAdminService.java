package com.qalsmartkeyboard.languages;

import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.administration.AdminAccess;
import com.qalsmartkeyboard.administration.AuditTrail;
import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.languages.AdminDtos.AdminSigningKeyDto;
import com.qalsmartkeyboard.languages.AdminDtos.PackSignerDto;
import com.qalsmartkeyboard.languages.AdminDtos.SigningKeyRegistration;
import com.qalsmartkeyboard.languages.PublishingRepository.KeyRow;
import com.qalsmartkeyboard.languages.pack.Ed25519;
import com.qalsmartkeyboard.languages.pack.KeyEndorsement;
import com.qalsmartkeyboard.languages.pack.PackFormat;
import com.qalsmartkeyboard.languages.pack.PackSigner;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.ApiFieldError;

/**
 * Resource-signing key administration (B3.2, pack spec 7.3/7.4, ADR 0019). Public keys only: private keys stay in the
 * signer (KMS in production, ADR 0007 D10).
 *
 * <ul>
 * <li>The server's own key is registered without an endorsement (it is pinned in app builds); its public key must equal
 * the signer's.</li>
 * <li>Any other key is registered <b>endorsed</b> by the server key, which must itself be registered ACTIVE: rotation
 * announces the next key this way before it signs anything ({@code endorse=false} registers a key that verifies uploads
 * only, such as a build pipeline key, which devices never trust).</li>
 * <li>Status moves only forward: ACTIVE to RETIRING (no new packs) to REVOKED (never verifies; discovery stops serving
 * packs it signed), or ACTIVE straight to REVOKED on compromise.</li>
 * </ul>
 */
class SigningKeyAdminService {

	static final String ENTITY = "resource_signing_key";
	static final String REGISTER = "RESOURCE_SIGNING_KEY_REGISTER";
	static final String RETIRE = "RESOURCE_SIGNING_KEY_RETIRE";
	static final String REVOKE = "RESOURCE_SIGNING_KEY_REVOKE";

	private final PublishingRepository repository;
	private final PackSigner signer;
	private final AdminAccess access;
	private final AuditTrail audit;
	private final TransactionTemplate tx;

	SigningKeyAdminService(PublishingRepository repository, PackSigner signer, AdminAccess access, AuditTrail audit,
			PlatformTransactionManager transactions) {
		this.repository = repository;
		this.signer = signer;
		this.access = access;
		this.audit = audit;
		this.tx = new TransactionTemplate(transactions);
	}

	PackSignerDto signer(AuthenticatedUser user) {
		access.require(user, AdminAccess.SIGNING_KEYS_MANAGE, "PACK_SIGNER_READ", ENTITY, null);
		String keyId = signer.keyId();
		byte[] publicKey = signer.publicKey();
		return repository.key(keyId)
				.map(k -> new PackSignerDto(keyId, "Ed25519", b64(publicKey),
						Arrays.equals(PackPublishingService.decodeKey(k.publicKey()), publicKey), k.status()))
				.orElse(new PackSignerDto(keyId, "Ed25519", b64(publicKey), false, null));
	}

	AdminSigningKeyDto register(AuthenticatedUser user, SigningKeyRegistration request) {
		String keyId = request == null ? null : request.keyId();
		AdminAccess.Actor actor = access.require(user, AdminAccess.SIGNING_KEYS_MANAGE, REGISTER, ENTITY,
				PackFormat.isValidKeyId(keyId) ? keyId : null);
		byte[] publicKey = request == null || request.publicKey() == null ? new byte[0]
				: PackPublishingService.decodeKey(request.publicKey());
		try {
			if (!PackFormat.isValidKeyId(keyId)) {
				throw invalid("keyId", "keyId must be 1-120 characters of A-Z a-z 0-9 . _ -.");
			}
			if (!Ed25519.isValidPublicKey(publicKey)) {
				throw invalid("publicKey", "publicKey must be the base64 of a valid 32-byte Ed25519 public key.");
			}
			boolean endorse = request.endorse() == null || request.endorse();
			return tx.execute(s -> {
				if (repository.lockKey(keyId).isPresent()) {
					throw PackPublishingService.conflict("A signing key with this id is already registered");
				}
				for (KeyRow k : repository.keys()) {
					if (Arrays.equals(PackPublishingService.decodeKey(k.publicKey()), publicKey)) {
						throw PackPublishingService.conflict("This public key is already registered under another id");
					}
				}
				String endorsedBy = null;
				String endorsement = null;
				if (keyId.equals(signer.keyId())) {
					if (!Arrays.equals(publicKey, signer.publicKey())) {
						throw PackPublishingService.conflict("The public key differs from the server signing key's");
					}
				}
				else if (endorse) {
					KeyRow own = repository.key(signer.keyId()).orElseThrow(() -> PackPublishingService
							.conflict("Register the server signing key before endorsing other keys"));
					if (!"ACTIVE".equals(own.status())
							|| !Arrays.equals(PackPublishingService.decodeKey(own.publicKey()), signer.publicKey())) {
						throw PackPublishingService.conflict("The server signing key is not an ACTIVE registered key");
					}
					endorsedBy = signer.keyId();
					endorsement = KeyEndorsement.sign(signer, keyId, publicKey);
				}
				repository.insertKey(keyId, b64(publicKey), endorsedBy, endorsement);
				Map<String, Object> meta = new LinkedHashMap<>();
				meta.put("endorsedByKeyId", endorsedBy == null ? "" : endorsedBy);
				audit.record(new AuditTrail.Entry(actor.userId(), null, REGISTER, ENTITY, keyId, AuditTrail.Outcome.SUCCESS,
						meta));
				return dto(repository.key(keyId).orElseThrow());
			});
		}
		catch (ApiException e) {
			failure(actor, REGISTER, PackFormat.isValidKeyId(keyId) ? keyId : null, e);
			throw e;
		}
	}

	AdminSigningKeyDto retire(AuthenticatedUser user, String keyId) {
		return move(user, keyId, RETIRE, "RETIRING", Set.of("ACTIVE"));
	}

	AdminSigningKeyDto revoke(AuthenticatedUser user, String keyId) {
		return move(user, keyId, REVOKE, "REVOKED", Set.of("ACTIVE", "RETIRING"));
	}

	private AdminSigningKeyDto move(AuthenticatedUser user, String keyId, String action, String target,
			Set<String> from) {
		AdminAccess.Actor actor = access.require(user, AdminAccess.SIGNING_KEYS_MANAGE, action, ENTITY,
				PackFormat.isValidKeyId(keyId) ? keyId : null);
		try {
			return tx.execute(s -> {
				KeyRow key = repository.lockKey(keyId).orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
				if (!from.contains(key.status())) {
					throw PackPublishingService.conflict("Signing key status only moves ACTIVE, RETIRING, REVOKED");
				}
				repository.setKeyStatus(keyId, target);
				audit.record(new AuditTrail.Entry(actor.userId(), null, action, ENTITY, keyId, AuditTrail.Outcome.SUCCESS,
						Map.of("previousStatus", key.status(), "status", target)));
				return dto(repository.key(keyId).orElseThrow());
			});
		}
		catch (ApiException e) {
			failure(actor, action, PackFormat.isValidKeyId(keyId) ? keyId : null, e);
			throw e;
		}
	}

	private void failure(AdminAccess.Actor actor, String action, String keyId, ApiException e) {
		audit.recordIndependently(new AuditTrail.Entry(actor.userId(), null, action, ENTITY, keyId,
				AuditTrail.Outcome.FAILURE, Map.of("code", e.code().name())));
	}

	private static ApiException invalid(String field, String message) {
		return new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, null,
				List.of(new ApiFieldError(field, "INVALID", message)), Map.of());
	}

	private static AdminSigningKeyDto dto(KeyRow k) {
		return new AdminSigningKeyDto(k.keyId(), "Ed25519", k.publicKey(), k.status(),
				k.validFrom() == null ? null : k.validFrom().toInstant(), k.endorsedByKeyId(), k.endorsementSignature());
	}

	private static String b64(byte[] raw) {
		return Base64.getEncoder().encodeToString(raw);
	}
}
