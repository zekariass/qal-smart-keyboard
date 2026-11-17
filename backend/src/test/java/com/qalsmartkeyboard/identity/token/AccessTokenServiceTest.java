package com.qalsmartkeyboard.identity.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.qalsmartkeyboard.identity.AuthenticatedUser;

class AccessTokenServiceTest {

	static final SecureRandom RANDOM = new SecureRandom();
	static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");

	static TokenProperties props(TokenProperties.SigningKey key, List<TokenProperties.VerificationKey> previous) {
		return new TokenProperties("qal-smart-keyboard", "qal-api", Duration.ofMinutes(15), Duration.ofSeconds(30),
				Duration.ofDays(60), Duration.ofSeconds(30), key, previous);
	}

	static final TokenProperties EPHEMERAL = props(new TokenProperties.SigningKey("dev-1", null, null, true), null);

	final AccessTokenKeys keys = AccessTokenKeys.load(EPHEMERAL, new MockEnvironment(), RANDOM);

	AccessTokenService at(Instant instant) {
		return new AccessTokenService(keys, EPHEMERAL, Clock.fixed(instant, ZoneOffset.UTC));
	}

	final UUID user = UUID.randomUUID();
	final UUID session = UUID.randomUUID();

	@Test
	void issuedTokenVerifiesAndCarriesOnlyTheMinimalClaims() throws Exception {
		AccessTokenService.AccessToken token = at(NOW).issue(user, session, null, "REGISTERED");
		assertThat(token.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
		assertThat(token.toString()).doesNotContain(token.value());

		Jwt jwt = at(NOW.plusSeconds(60)).decoder().decode(token.value());
		assertThat(jwt.getHeaders()).containsEntry("alg", "ES256").containsEntry("typ", "at+jwt")
				.containsEntry("kid", keys.signingKeyId());
		assertThat(jwt.getClaims().keySet()).containsExactlyInAnyOrder("iss", "aud", "sub", "sid", "accountType", "iat",
				"exp", "jti");
		assertThat(AccessTokenService.principal(jwt))
				.isEqualTo(new AuthenticatedUser(user, session, null, "REGISTERED"));

		UUID device = UUID.randomUUID();
		Jwt withDevice = at(NOW).decoder().decode(at(NOW).issue(user, session, device, "GUEST").value());
		assertThat(AccessTokenService.principal(withDevice).deviceId()).isEqualTo(device);
		assertThat(AccessTokenService.principal(withDevice).isGuest()).isTrue();
	}

	@Test
	void expiredTokenFailsWithTheExpiredCodeOnly() {
		String token = at(NOW).issue(user, session, null, "REGISTERED").value();
		JwtDecoder later = at(NOW.plus(Duration.ofMinutes(15)).plusSeconds(31)).decoder();
		assertThatThrownBy(() -> later.decode(token)).isInstanceOfSatisfying(JwtValidationException.class,
				e -> assertThat(e.getErrors()).extracting(err -> err.getErrorCode())
						.containsExactly(AccessTokenService.EXPIRED_ERROR_CODE));
		// within the clock skew it is still accepted
		assertThat(at(NOW.plus(Duration.ofMinutes(15)).plusSeconds(29)).decoder().decode(token)).isNotNull();
	}

	@Test
	void tokenFromAnotherKeyIsRejectedBeforeAnyClaimCheck() {
		AccessTokenKeys otherKeys = AccessTokenKeys.load(EPHEMERAL, new MockEnvironment(), RANDOM);
		String foreign = new AccessTokenService(otherKeys, EPHEMERAL, Clock.fixed(NOW.minus(Duration.ofDays(1)),
				ZoneOffset.UTC)).issue(user, session, null, "REGISTERED").value();
		// expired AND foreign: must not be reported as merely expired
		assertThatThrownBy(() -> at(NOW).decoder().decode(foreign)).isInstanceOf(BadJwtException.class)
				.isNotInstanceOf(JwtValidationException.class);
	}

	@Test
	void tamperedPayloadIsRejected() {
		String[] parts = at(NOW).issue(user, session, null, "REGISTERED").value().split("\\.");
		String payload = new String(Base64.getUrlDecoder().decode(parts[1]))
				.replace("\"REGISTERED\"", "\"GUEST\"");
		String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes())
				+ "." + parts[2];
		assertThatThrownBy(() -> at(NOW).decoder().decode(tampered)).isInstanceOf(BadJwtException.class);
	}

	@Test
	void unsignedAndHmacTokensAreRejected() throws Exception {
		JWTClaimsSet claims = validClaims(c -> {
		});
		String none = new PlainJWT(claims).serialize();
		assertThatThrownBy(() -> at(NOW).decoder().decode(none)).isInstanceOf(BadJwtException.class);

		SignedJWT hs = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(new JOSEObjectType("at+jwt"))
				.keyID(keys.signingKeyId()).build(), claims);
		byte[] secret = new byte[32];
		RANDOM.nextBytes(secret);
		hs.sign(new MACSigner(secret));
		assertThatThrownBy(() -> at(NOW).decoder().decode(hs.serialize())).isInstanceOf(BadJwtException.class);
	}

	@Test
	void wrongTypeIssuerAudienceOrClaimsAreRejected() throws Exception {
		assertRejected(sign("JWT", validClaims(c -> {
		})), "typ");
		assertRejected(sign("at+jwt", validClaims(c -> c.issuer("someone-else"))), "iss");
		assertRejected(sign("at+jwt", validClaims(c -> c.audience("other-api"))), "aud");
		assertRejected(sign("at+jwt", validClaims(c -> c.subject("not-a-uuid"))), "sub");
		assertRejected(sign("at+jwt", validClaims(c -> c.claim("sid", null))), "sid");
		assertRejected(sign("at+jwt", validClaims(c -> c.claim("accountType", "ADMIN"))), "accountType");
		assertRejected(sign("at+jwt", validClaims(c -> c.jwtID(null))), "jti");
		assertRejected(sign("at+jwt", validClaims(c -> c.issueTime(Date.from(NOW.plusSeconds(600))))), "iat");
		// sanity: the helper itself produces an acceptable token
		assertThat(at(NOW).decoder().decode(sign("at+jwt", validClaims(c -> {
		})))).isNotNull();
	}

	void assertRejected(String token, String what) {
		assertThatThrownBy(() -> at(NOW).decoder().decode(token)).as(what).isInstanceOfSatisfying(
				JwtValidationException.class,
				e -> assertThat(e.getErrors()).noneMatch(
						err -> err.getErrorCode().equals(AccessTokenService.EXPIRED_ERROR_CODE)));
	}

	JWTClaimsSet validClaims(Consumer<JWTClaimsSet.Builder> change) {
		JWTClaimsSet.Builder b = new JWTClaimsSet.Builder().issuer("qal-smart-keyboard").audience("qal-api")
				.subject(user.toString()).claim("sid", session.toString()).claim("accountType", "REGISTERED")
				.issueTime(Date.from(NOW)).expirationTime(Date.from(NOW.plusSeconds(900)))
				.jwtID(UUID.randomUUID().toString());
		change.accept(b);
		return b.build();
	}

	String sign(String typ, JWTClaimsSet claims) throws Exception {
		SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).type(new JOSEObjectType(typ))
				.keyID(keys.signingKeyId()).build(), claims);
		jwt.sign(new ECDSASigner(keys.signingKey()));
		return jwt.serialize();
	}

	// ---------------------------------------------------------------- key configuration (fail closed)

	static String pem(String type, byte[] der) {
		return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
				+ "\n-----END " + type + "-----\n";
	}

	static KeyPair pair(String curve) throws Exception {
		KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
		g.initialize(new ECGenParameterSpec(curve), RANDOM);
		return g.generateKeyPair();
	}

	@Test
	void configuredPemKeyPairSignsAndVerifiesAndPreviousKeysStillVerify() throws Exception {
		KeyPair current = pair("secp256r1");
		KeyPair previous = pair("secp256r1");
		TokenProperties configured = props(new TokenProperties.SigningKey("k2",
				pem("PRIVATE KEY", current.getPrivate().getEncoded()), pem("PUBLIC KEY", current.getPublic().getEncoded()),
				false), List.of(new TokenProperties.VerificationKey("k1", pem("PUBLIC KEY",
						previous.getPublic().getEncoded()))));
		MockEnvironment prod = new MockEnvironment();
		prod.setActiveProfiles("prod");
		AccessTokenKeys loaded = AccessTokenKeys.load(configured, prod, RANDOM);
		AccessTokenService service = new AccessTokenService(loaded, configured, Clock.fixed(NOW, ZoneOffset.UTC));
		assertThat(service.decoder().decode(service.issue(user, session, null, "REGISTERED").value()).getHeaders())
				.containsEntry("kid", "k2");

		// a token signed by the retired key k1 still verifies during the rotation
		AccessTokenKeys oldKeys = new AccessTokenKeys("k1", (ECPrivateKey) previous.getPrivate(),
				loaded.verificationKeys());
		String oldToken = new AccessTokenService(oldKeys, configured, Clock.fixed(NOW, ZoneOffset.UTC))
				.issue(user, session, null, "REGISTERED").value();
		assertThat(service.decoder().decode(oldToken).getHeaders()).containsEntry("kid", "k1");
		assertThat(loaded.verificationKeys().toJSONObject(true).toString()).doesNotContain("\"d\"");
	}

	@Test
	void missingKeyFailsStartupAndEphemeralIsRefusedInProduction() {
		TokenProperties none = props(new TokenProperties.SigningKey("k", null, null, false), null);
		assertThatThrownBy(() -> AccessTokenKeys.load(none, new MockEnvironment(), RANDOM))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("not configured");
		for (String profile : new String[] { "prod", "production", "staging" }) {
			MockEnvironment env = new MockEnvironment();
			env.setActiveProfiles(profile);
			assertThatThrownBy(() -> AccessTokenKeys.load(EPHEMERAL, env, RANDOM)).isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("refused");
		}
	}

	@Test
	void mismatchedWrongCurveOrMalformedKeysFailStartup() throws Exception {
		KeyPair a = pair("secp256r1");
		KeyPair b = pair("secp256r1");
		KeyPair p384 = pair("secp384r1");
		assertThatThrownBy(() -> AccessTokenKeys.load(props(new TokenProperties.SigningKey("k",
				pem("PRIVATE KEY", a.getPrivate().getEncoded()), pem("PUBLIC KEY", b.getPublic().getEncoded()), false),
				null), new MockEnvironment(), RANDOM)).hasMessageContaining("do not match");
		assertThatThrownBy(() -> AccessTokenKeys.load(props(new TokenProperties.SigningKey("k",
				pem("PRIVATE KEY", p384.getPrivate().getEncoded()), pem("PUBLIC KEY", p384.getPublic().getEncoded()),
				false), null), new MockEnvironment(), RANDOM)).hasMessageContaining("P-256");
		String secretPem = pem("PRIVATE KEY", a.getPrivate().getEncoded());
		assertThatThrownBy(() -> AccessTokenKeys.load(props(new TokenProperties.SigningKey("k", secretPem.substring(0, 80)
				+ "!!", pem("PUBLIC KEY", a.getPublic().getEncoded()), false), null), new MockEnvironment(), RANDOM))
				.isInstanceOf(IllegalStateException.class).hasNoCause();
		assertThatThrownBy(() -> AccessTokenKeys.load(props(new TokenProperties.SigningKey("k", secretPem, null, false),
				null), new MockEnvironment(), RANDOM)).hasMessageContaining("public-key is required");
		assertThat(new TokenProperties.SigningKey("k", secretPem, null, false).toString()).doesNotContain(
				secretPem.substring(30, 60));
	}

	@Test
	void accessTokenLifetimeIsCappedAtFifteenMinutes() {
		assertThatThrownBy(() -> new TokenProperties("i", "a", Duration.ofMinutes(16), Duration.ofSeconds(30),
				Duration.ofDays(60), Duration.ofSeconds(30), new TokenProperties.SigningKey("k", null, null, true), null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void refreshReuseGraceIsBetweenZeroAndTwoMinutes() {
		TokenProperties.SigningKey key = new TokenProperties.SigningKey("k", null, null, true);
		assertThat(new TokenProperties("i", "a", Duration.ofMinutes(15), Duration.ofSeconds(30), Duration.ofDays(60),
				Duration.ZERO, key, null).refreshReuseGrace()).isZero();
		assertThat(new TokenProperties("i", "a", Duration.ofMinutes(15), Duration.ofSeconds(30), Duration.ofDays(60),
				Duration.ofMinutes(2), key, null).refreshReuseGrace()).isEqualTo(Duration.ofMinutes(2));
		for (Duration bad : new Duration[] { Duration.ofSeconds(-1), Duration.ofSeconds(121) }) {
			assertThatThrownBy(() -> new TokenProperties("i", "a", Duration.ofMinutes(15), Duration.ofSeconds(30),
					Duration.ofDays(60), bad, key, null)).isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("refresh-reuse-grace");
		}
	}
}
