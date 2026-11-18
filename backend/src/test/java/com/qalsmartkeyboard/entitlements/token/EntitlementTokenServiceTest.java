package com.qalsmartkeyboard.entitlements.token;

import static com.qalsmartkeyboard.entitlements.token.EntitlementTokenVerifier.reason;
import static com.qalsmartkeyboard.entitlements.token.TokenFixtures.granted;
import static com.qalsmartkeyboard.entitlements.token.TokenFixtures.keys;
import static com.qalsmartkeyboard.entitlements.token.TokenFixtures.pem;
import static com.qalsmartkeyboard.entitlements.token.TokenFixtures.properties;
import static com.qalsmartkeyboard.entitlements.token.TokenFixtures.resolverReturning;
import static com.qalsmartkeyboard.entitlements.token.TokenFixtures.verifier;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.qalsmartkeyboard.entitlements.AccessState;
import com.qalsmartkeyboard.entitlements.EntitlementResolver;
import com.qalsmartkeyboard.entitlements.token.EntitlementTokenService.EntitlementToken;
import com.qalsmartkeyboard.entitlements.token.EntitlementTokenVerifier.Reason;
import com.qalsmartkeyboard.entitlements.token.EntitlementTokenVerifier.Validity;
import com.qalsmartkeyboard.entitlements.token.EntitlementTokenVerifier.Verified;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;

/** B4.4 done-when: issue, verify, tampering, algorithm and key confusion, expiry and offline grace, key rotation. */
class EntitlementTokenServiceTest {

	static final Instant T0 = Instant.parse("2026-11-18T08:00:00Z");

	final UUID userId = UUID.randomUUID();
	final UUID deviceId = UUID.randomUUID();
	final TokenFixtures.Pem pem = pem();
	final EntitlementTokenProperties properties = properties("ent-2026-11", pem, List.of());
	final EntitlementTokenKeys keys = keys(properties);
	final EntitlementTokenVerifier verifier = verifier(keys);

	EntitlementTokenService service(EntitlementResolver resolver, Instant now) {
		return new EntitlementTokenService(resolver, keys, properties, Clock.fixed(now, ZoneOffset.UTC));
	}

	EntitlementToken premiumToken() {
		return service(resolverReturning(granted(userId, "PREMIUM", true)), T0).issue(userId, deviceId);
	}

	// ------------------------------------------------------------------ issue and verify

	@Test
	void issuedTokenVerifiesWithThePinnedKeyAndCarriesOnlyTheDocumentedClaims() throws ParseException {
		EntitlementToken token = premiumToken();
		assertThat(token.keyId()).isEqualTo("ent-2026-11");
		assertThat(token.expiresAt()).isEqualTo(T0.plus(Duration.ofHours(24)));
		assertThat(token.offlineGraceUntil()).isEqualTo(T0.plus(Duration.ofHours(96)));

		Verified v = verifier.verify(token.jws(), T0, userId, deviceId);
		assertThat(v.validity()).isEqualTo(Validity.VALID);
		assertThat(v.keyId()).isEqualTo("ent-2026-11");
		assertThat(v.subject()).isEqualTo(userId);
		assertThat(v.deviceId()).isEqualTo(deviceId);
		assertThat(v.planCode()).isEqualTo("PREMIUM");
		assertThat(v.features()).containsExactly(Map.entry("ON_DEVICE_AI_MODELS", true));
		assertThat(v.issuedAt()).isEqualTo(T0);
		assertThat(v.expiresAt()).isEqualTo(token.expiresAt());
		assertThat(v.offlineGraceUntil()).isEqualTo(token.offlineGraceUntil());
		assertThat(v.policyVersion()).isEqualTo("PROVISIONAL_V2");
		assertThat(v.allows("ON_DEVICE_AI_MODELS", true)).isTrue();

		SignedJWT jwt = SignedJWT.parse(token.jws());
		assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.EdDSA);
		assertThat(jwt.getHeader().getType()).isEqualTo(new JOSEObjectType("qal-ent+jwt"));
		assertThat(jwt.getHeader().getKeyID()).isEqualTo("ent-2026-11");
		assertThat(jwt.getHeader().toJSONObject().keySet()).containsExactlyInAnyOrder("alg", "typ", "kid");
		// minimal and PII-free: no phone, email, name, plan display name, allowances or usage
		assertThat(jwt.getJWTClaimsSet().getClaims().keySet()).containsExactlyInAnyOrder("iss", "aud", "sub",
				"deviceId", "planCode", "features", "policyVersion", "ver", "iat", "exp", "offlineGraceUntil", "jti");
		assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("qal-keyboard");
		assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("qal-smart-keyboard");
	}

	@Test
	void everyTokenHasItsOwnId() {
		EntitlementTokenService service = service(resolverReturning(granted(userId, "PREMIUM", true)), T0);
		Verified a = verifier.verify(service.issue(userId, null).jws(), T0);
		Verified b = verifier.verify(service.issue(userId, null).jws(), T0);
		assertThat(a.tokenId()).isNotEqualTo(b.tokenId());
	}

	@Test
	void withoutADeviceTheClaimIsAbsent() throws ParseException {
		EntitlementToken token = service(resolverReturning(granted(userId, "PREMIUM", true)), T0).issue(userId, null);
		assertThat(SignedJWT.parse(token.jws()).getJWTClaimsSet().getClaims()).doesNotContainKey("deviceId");
		assertThat(verifier.verify(token.jws(), T0, userId, UUID.randomUUID()).deviceId()).isNull();
	}

	@Test
	void freePlanCarriesTheGateAsFalse() {
		EntitlementToken token = service(resolverReturning(granted(userId, "FREE", false)), T0).issue(userId, null);
		Verified v = verifier.verify(token.jws(), T0);
		assertThat(v.features()).containsExactly(Map.entry("ON_DEVICE_AI_MODELS", false));
		assertThat(v.allows("ON_DEVICE_AI_MODELS", false)).isFalse();
	}

	@Test
	void onlyGatedUnmeteredFeaturesAreEverWrittenServerAllowancesNever() throws ParseException {
		EntitlementTokenProperties wide = properties(
				new EntitlementTokenProperties.SigningKey("ent-2026-11", pem.privateKey(), pem.publicKey(), false),
				List.of(), List.of("TRANSLATE", "ON_DEVICE_AI_MODELS", "OFFLINE_TYPING", "NOT_A_FEATURE", "HISTORY_SYNC"));
		EntitlementToken token = new EntitlementTokenService(resolverReturning(granted(userId, "PREMIUM", true)), keys,
				wide, Clock.fixed(T0, ZoneOffset.UTC)).issue(userId, null);
		// TRANSLATE is metered (a server allowance), OFFLINE_TYPING is never gated, NOT_A_FEATURE is unknown
		assertThat(verifier.verify(token.jws(), T0).features()).containsExactly(Map.entry("ON_DEVICE_AI_MODELS", true),
				Map.entry("HISTORY_SYNC", true));
		assertThat(SignedJWT.parse(token.jws()).getJWTClaimsSet().toString()).doesNotContain("TRANSLATE", "600");
	}

	@Test
	void accountsThatMayNotHoldEntitlementsGetNoToken() {
		record Case(AccessState access, ApiErrorCode code, HttpStatus status) {
		}
		for (Case c : List.of(new Case(AccessState.SUSPENDED, ApiErrorCode.ACCOUNT_SUSPENDED, HttpStatus.FORBIDDEN),
				new Case(AccessState.DELETED, ApiErrorCode.DELETION_PENDING, HttpStatus.FORBIDDEN),
				new Case(AccessState.MERGED, ApiErrorCode.GUEST_SESSION_INVALID, HttpStatus.UNAUTHORIZED),
				new Case(AccessState.PENDING, ApiErrorCode.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED),
				new Case(AccessState.UNKNOWN, ApiErrorCode.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED))) {
			EntitlementTokenService service = service(resolverReturning(TokenFixtures.denied(userId, c.access())), T0);
			assertThatThrownBy(() -> service.issue(userId, deviceId)).as(c.access().name())
					.isInstanceOfSatisfying(ApiException.class, e -> {
						assertThat(e.code()).isEqualTo(c.code());
						assertThat(e.status()).isEqualTo(c.status());
					});
		}
	}

	// ------------------------------------------------------------------ tampering

	@Test
	void tamperedPayloadOrSignatureIsRejected() {
		String[] parts = premiumToken().jws().split("\\.");
		String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
		String forged = payload.replace("\"PREMIUM\"", "\"PREMIUM_PLUS\"").replace("true", "true ");
		assertThat(forged).isNotEqualTo(payload);
		String forgedPayload = Base64.getUrlEncoder().withoutPadding()
				.encodeToString(forged.getBytes(StandardCharsets.UTF_8));
		assertThat(reason(() -> verifier.verify(parts[0] + "." + forgedPayload + "." + parts[2], T0)))
				.contains(Reason.SIGNATURE);

		byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
		signature[10] ^= 0x01;
		String badSignature = Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
		assertThat(reason(() -> verifier.verify(parts[0] + "." + parts[1] + "." + badSignature, T0)))
				.contains(Reason.SIGNATURE);

		String shortSignature = Base64.getUrlEncoder().withoutPadding()
				.encodeToString(java.util.Arrays.copyOf(signature, 63));
		assertThat(reason(() -> verifier.verify(parts[0] + "." + parts[1] + "." + shortSignature, T0)))
				.contains(Reason.SIGNATURE);

		// a header swapped for another valid one breaks the signature too (the header is signed)
		String otherHeader = b64("{\"alg\":\"EdDSA\",\"typ\":\"qal-ent+jwt\",\"kid\":\"ent-2026-11\",\"x\":1}");
		assertThat(reason(() -> verifier.verify(otherHeader + "." + parts[1] + "." + parts[2], T0)))
				.contains(Reason.SIGNATURE);

		for (String malformed : List.of("", parts[0] + "." + parts[1], parts[0] + "." + parts[1] + ".",
				parts[0] + "." + parts[1] + "." + parts[2] + ".x", parts[0] + "=." + parts[1] + "." + parts[2],
				"a".repeat(9000))) {
			assertThat(reason(() -> verifier.verify(malformed, T0))).as(malformed).contains(Reason.MALFORMED);
		}
	}

	// ------------------------------------------------------------------ algorithm, type and key confusion

	@Test
	void onlyEdDsaIsAccepted() throws Exception {
		String[] parts = premiumToken().jws().split("\\.");
		String signingInputPayload = parts[1];

		// alg none, with or without a dummy signature
		String none = b64("{\"alg\":\"none\",\"typ\":\"qal-ent+jwt\",\"kid\":\"ent-2026-11\"}");
		assertThat(reason(() -> verifier.verify(none + "." + signingInputPayload + ".", T0))).contains(Reason.MALFORMED);
		assertThat(reason(() -> verifier.verify(none + "." + signingInputPayload + "." + parts[2], T0)))
				.contains(Reason.ALGORITHM);

		// HS256 keyed with the public key bytes (the classic algorithm-confusion forgery)
		String hs = b64("{\"alg\":\"HS256\",\"typ\":\"qal-ent+jwt\",\"kid\":\"ent-2026-11\"}");
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(keys.publicKeys().get("ent-2026-11").getEncoded(), "HmacSHA256"));
		String hsSig = Base64.getUrlEncoder().withoutPadding()
				.encodeToString(mac.doFinal((hs + "." + signingInputPayload).getBytes(StandardCharsets.US_ASCII)));
		assertThat(reason(() -> verifier.verify(hs + "." + signingInputPayload + "." + hsSig, T0)))
				.contains(Reason.ALGORITHM);

		// ES256 with a real EC key under the pinned kid
		KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
		ec.initialize(new ECGenParameterSpec("secp256r1"));
		JWSObject es = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.ES256).type(EntitlementTokenService.TYPE)
				.keyID("ent-2026-11").build(), new Payload(Base64.getUrlDecoder().decode(signingInputPayload)));
		es.sign(new ECDSASigner((java.security.interfaces.ECPrivateKey) ec.generateKeyPair().getPrivate()));
		assertThat(reason(() -> verifier.verify(es.serialize(), T0))).contains(Reason.ALGORITHM);

		// the fully-specified "Ed25519" name (RFC 9864) and case variants are not "EdDSA"
		for (String alg : List.of("Ed25519", "EDDSA", "eddsa", "Ed448")) {
			String header = b64("{\"alg\":\"" + alg + "\",\"typ\":\"qal-ent+jwt\",\"kid\":\"ent-2026-11\"}");
			assertThat(reason(() -> verifier.verify(header + "." + signingInputPayload + "." + parts[2], T0))).as(alg)
					.contains(Reason.ALGORITHM);
		}
	}

	@Test
	void typeAndKeyIdAreEnforced() throws Exception {
		String payload = premiumToken().jws().split("\\.")[1];
		byte[] claims = Base64.getUrlDecoder().decode(payload);

		// an otherwise valid EdDSA signature under another typ (e.g. an access-token typ) is refused
		assertThat(reason(() -> verifier.verify(sign(keys, header("at+jwt", "ent-2026-11"), claims), T0)))
				.contains(Reason.TYPE);
		assertThat(reason(() -> verifier.verify(sign(keys, header(null, "ent-2026-11"), claims), T0)))
				.contains(Reason.TYPE);
		// no kid, an unknown kid
		assertThat(reason(() -> verifier.verify(sign(keys, header("qal-ent+jwt", null), claims), T0)))
				.contains(Reason.UNKNOWN_KEY);
		EntitlementTokenKeys rogue = keys(properties("rogue", pem(), List.of()));
		assertThat(reason(() -> verifier.verify(sign(rogue, header("qal-ent+jwt", "rogue"), claims), T0)))
				.contains(Reason.UNKNOWN_KEY);
		// a rogue key claiming the pinned kid
		assertThat(reason(() -> verifier.verify(sign(rogue, header("qal-ent+jwt", "ent-2026-11"), claims), T0)))
				.contains(Reason.SIGNATURE);
		// an embedded key or crit is never honoured
		JWSHeader embedded = new JWSHeader.Builder(JWSAlgorithm.EdDSA).type(EntitlementTokenService.TYPE)
				.keyID("ent-2026-11").jwk(EntitlementTokenKeys.jwk("rogue", rogue.publicKeys().get("rogue"))).build();
		assertThat(reason(() -> verifier.verify(sign(rogue, embedded, claims), T0))).contains(Reason.MALFORMED);
	}

	@Test
	void issuerAudienceSubjectAndDeviceAreChecked() {
		String jws = premiumToken().jws();
		assertThat(reason(() -> new EntitlementTokenVerifier(keys.publicKeys(), "someone-else", "qal-keyboard",
				Duration.ofMinutes(5)).verify(jws, T0))).contains(Reason.ISSUER);
		assertThat(reason(() -> new EntitlementTokenVerifier(keys.publicKeys(), "qal-smart-keyboard", "qal-api",
				Duration.ofMinutes(5)).verify(jws, T0))).contains(Reason.AUDIENCE);
		assertThat(reason(() -> verifier.verify(jws, T0, UUID.randomUUID(), null))).contains(Reason.SUBJECT);
		assertThat(reason(() -> verifier.verify(jws, T0, userId, UUID.randomUUID()))).contains(Reason.DEVICE);
	}

	@Test
	void claimsBeyondThePinnedPolicyAreRejectedEvenWhenSigned() {
		long iat = T0.getEpochSecond();
		long day = 24 * 3600;
		String ok = sign(keys, header("qal-ent+jwt", "ent-2026-11"), claims(iat, iat + day, iat + 4 * day, 1));
		assertThat(verifier.verify(ok, T0).validity()).isEqualTo(Validity.VALID);
		// 25 h validity, 73 h grace, grace before exp, exp before iat, unknown format version
		assertThat(reason(() -> verifier.verify(sign(keys, header("qal-ent+jwt", "ent-2026-11"),
				claims(iat, iat + day + 3600, iat + day + 3600, 1)), T0))).contains(Reason.CLAIMS);
		assertThat(reason(() -> verifier.verify(sign(keys, header("qal-ent+jwt", "ent-2026-11"),
				claims(iat, iat + day, iat + 4 * day + 3600, 1)), T0))).contains(Reason.CLAIMS);
		assertThat(reason(() -> verifier.verify(sign(keys, header("qal-ent+jwt", "ent-2026-11"),
				claims(iat, iat + day, iat + day - 1, 1)), T0))).contains(Reason.CLAIMS);
		assertThat(reason(() -> verifier.verify(sign(keys, header("qal-ent+jwt", "ent-2026-11"),
				claims(iat, iat, iat + day, 1)), T0))).contains(Reason.CLAIMS);
		assertThat(reason(() -> verifier.verify(sign(keys, header("qal-ent+jwt", "ent-2026-11"),
				claims(iat, iat + day, iat + 4 * day, 2)), T0))).contains(Reason.VERSION);
		// a token issued more than the skew in the future
		assertThat(reason(() -> verifier.verify(ok, T0.minus(Duration.ofMinutes(6))))).contains(Reason.NOT_YET_VALID);
		assertThat(verifier.verify(ok, T0.minus(Duration.ofMinutes(4))).validity()).isEqualTo(Validity.VALID);
	}

	// ------------------------------------------------------------------ expiry and offline grace

	@Test
	void validUntilExpThenOfflineOnlyForSeventyTwoHoursThenRejected() {
		EntitlementToken token = premiumToken();
		Instant exp = token.expiresAt();
		Instant graceEnd = exp.plus(Duration.ofHours(72));
		assertThat(token.offlineGraceUntil()).isEqualTo(graceEnd);

		Verified beforeExp = verifier.verify(token.jws(), exp.minusSeconds(1));
		assertThat(beforeExp.validity()).isEqualTo(Validity.VALID);
		assertThat(beforeExp.allows("ON_DEVICE_AI_MODELS", true)).isTrue();
		assertThat(beforeExp.allows("ON_DEVICE_AI_MODELS", false)).isTrue();

		Verified atExp = verifier.verify(token.jws(), exp);
		assertThat(atExp.validity()).isEqualTo(Validity.OFFLINE_GRACE);
		assertThat(atExp.allows("ON_DEVICE_AI_MODELS", true)).as("online: fetch a fresh token").isFalse();
		assertThat(atExp.allows("ON_DEVICE_AI_MODELS", false)).isTrue();

		Verified lastGraceSecond = verifier.verify(token.jws(), graceEnd.minusSeconds(1));
		assertThat(lastGraceSecond.validity()).isEqualTo(Validity.OFFLINE_GRACE);
		assertThat(lastGraceSecond.allows("ON_DEVICE_AI_MODELS", false)).isTrue();

		assertThat(reason(() -> verifier.verify(token.jws(), graceEnd))).contains(Reason.EXPIRED);
		assertThat(reason(() -> verifier.verify(token.jws(), graceEnd.plus(Duration.ofDays(365)))))
				.contains(Reason.EXPIRED);
	}

	@Test
	void issueTimeIsTruncatedToSecondsSoTheEdgesAreExact() {
		Instant odd = T0.plusMillis(999);
		EntitlementToken token = service(resolverReturning(granted(userId, "PREMIUM", true)), odd).issue(userId, null);
		assertThat(token.expiresAt()).isEqualTo(T0.plus(Duration.ofHours(24)));
		assertThat(verifier.verify(token.jws(), token.expiresAt().minusNanos(1)).validity()).isEqualTo(Validity.VALID);
	}

	// ------------------------------------------------------------------ rotation

	@Test
	void aRotatedKeyKeepsVerifyingWhilePublishedAndIsRejectedOnceRemoved() {
		TokenFixtures.Pem oldPem = pem;
		TokenFixtures.Pem newPem = pem();
		String oldToken = premiumToken().jws();

		// step 1: the new key signs, the old one is published as a previous key
		EntitlementTokenProperties rotated = properties("ent-2026-12", newPem,
				List.of(new EntitlementTokenProperties.VerificationKey("ent-2026-11", oldPem.publicKey())));
		EntitlementTokenKeys rotatedKeys = keys(rotated);
		assertThat(rotatedKeys.publicKeys().keySet()).containsExactly("ent-2026-12", "ent-2026-11");
		EntitlementTokenService rotatedService = new EntitlementTokenService(
				resolverReturning(granted(userId, "PREMIUM", true)), rotatedKeys, rotated,
				Clock.fixed(T0.plus(Duration.ofHours(1)), ZoneOffset.UTC));
		EntitlementToken newToken = rotatedService.issue(userId, null);
		assertThat(newToken.keyId()).isEqualTo("ent-2026-12");

		EntitlementTokenVerifier both = verifier(rotatedKeys);
		assertThat(both.verify(oldToken, T0.plus(Duration.ofHours(2))).keyId()).isEqualTo("ent-2026-11");
		assertThat(both.verify(newToken.jws(), T0.plus(Duration.ofHours(2))).keyId()).isEqualTo("ent-2026-12");
		// the old pinned set does not know the new key yet: clients must ship it before it signs (ADR 0013)
		assertThat(reason(() -> verifier.verify(newToken.jws(), T0.plus(Duration.ofHours(2)))))
				.contains(Reason.UNKNOWN_KEY);

		// step 2: after the old key's last token is past its grace, it is removed
		EntitlementTokenKeys newOnly = keys(properties("ent-2026-12", newPem, List.of()));
		assertThat(newOnly.publicKeys().keySet()).containsExactly("ent-2026-12");
		EntitlementTokenVerifier newVerifier = verifier(newOnly);
		assertThat(reason(() -> newVerifier.verify(oldToken, T0.plus(Duration.ofHours(2)))))
				.contains(Reason.UNKNOWN_KEY);
		assertThat(newVerifier.verify(newToken.jws(), T0.plus(Duration.ofHours(2))).validity())
				.isEqualTo(Validity.VALID);
	}

	// ------------------------------------------------------------------ interoperability

	/** RFC 8037 A.4: the JDK-backed signer reproduces the RFC's Ed25519 JWS byte for byte (Ed25519 is deterministic). */
	@Test
	void signerMatchesTheRfc8037Vector() throws Exception {
		String jwk = "{\"kty\":\"OKP\",\"crv\":\"Ed25519\",\"d\":\"nWGxne_9WmC6hEr0kuwsxERJxWl7MmkZcDusAxyuf2A\","
				+ "\"x\":\"11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo\"}";
		// the loader accepts the JWK form and its probe proves that d and x belong together
		EntitlementTokenKeys rfc = keys(TokenFixtures.properties(
				new EntitlementTokenProperties.SigningKey("rfc8037", jwk, null, false), List.of(),
				List.of("ON_DEVICE_AI_MODELS")));
		JWSObject jws = new JWSObject(new JWSHeader(JWSAlgorithm.EdDSA), new Payload("Example of Ed25519 signing"));
		jws.sign(new JdkEd25519Signer(rfc.signingKey()));
		assertThat(jws.serialize()).isEqualTo("eyJhbGciOiJFZERTQSJ9.RXhhbXBsZSBvZiBFZDI1NTE5IHNpZ25pbmc."
				+ "hgyY0il_MGCjP0JzlnLWG1PPOt7-09PGcvMg3AIbQR6dWbhijcNR4ki4iylGjg5BhVsPt9g7sVvpAr_MuM0KAg");
		assertThat(OctetKeyPair.parse(rfc.jwkSet().getKeys().get(0).toJSONObject()).getX().toString())
				.isEqualTo("11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo");
		assertThat(HexFormat.of().formatHex(EntitlementTokenKeys.rawPublic(rfc.publicKeys().get("rfc8037"))))
				.isEqualTo("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
	}

	@Test
	void theSignerRefusesOtherAlgorithms() {
		JWSObject jws = new JWSObject(new JWSHeader(JWSAlgorithm.ES256), new Payload("x"));
		assertThatThrownBy(() -> jws.sign(new JdkEd25519Signer(keys.signingKey())))
				.isInstanceOf(com.nimbusds.jose.JOSEException.class);
	}

	// ------------------------------------------------------------------ helpers

	static String b64(String json) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}

	static JWSHeader header(String typ, String kid) {
		JWSHeader.Builder b = new JWSHeader.Builder(JWSAlgorithm.EdDSA);
		if (typ != null) {
			b.type(new JOSEObjectType(typ));
		}
		if (kid != null) {
			b.keyID(kid);
		}
		return b.build();
	}

	static String sign(EntitlementTokenKeys with, JWSHeader header, byte[] payload) {
		JWSObject jws = new JWSObject(header, new Payload(payload));
		try {
			jws.sign(new JdkEd25519Signer(with.signingKey()));
		}
		catch (com.nimbusds.jose.JOSEException e) {
			throw new IllegalStateException(e);
		}
		return jws.serialize();
	}

	byte[] claims(long iat, long exp, long grace, int ver) {
		JWTClaimsSet set = new JWTClaimsSet.Builder().issuer("qal-smart-keyboard").audience("qal-keyboard")
				.subject(userId.toString()).claim("planCode", "PREMIUM")
				.claim("features", Map.of("ON_DEVICE_AI_MODELS", true)).claim("ver", ver)
				.issueTime(Date.from(Instant.ofEpochSecond(iat))).expirationTime(Date.from(Instant.ofEpochSecond(exp)))
				.claim("offlineGraceUntil", grace).jwtID(UUID.randomUUID().toString()).build();
		return set.toString().getBytes(StandardCharsets.UTF_8);
	}
}
