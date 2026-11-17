package com.qalsmartkeyboard.identity.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.qalsmartkeyboard.identity.oauth.IdTokenVerificationException.Failure;

/**
 * B2.2: ID-token verification against a <b>fake JWKS</b> (loopback HTTP server, {@link FakeGoogle}): signature, algorithm
 * pinning, issuer, audience, expiry, nonce, key rotation with refresh, caching and outages. No real network.
 */
class JwksIdTokenVerifierTest {

	static FakeGoogle google;
	static RSAKey key1;
	static RSAKey key2;
	static RSAKey attacker;

	JwksIdTokenVerifier verifier;

	@BeforeAll
	static void startFakeGoogle() {
		google = new FakeGoogle();
		key1 = FakeGoogle.newKey("kid-1");
		key2 = FakeGoogle.newKey("kid-2");
		attacker = FakeGoogle.newKey("kid-1"); // same kid, different key
	}

	@AfterAll
	static void stopFakeGoogle() {
		google.close();
	}

	@BeforeEach
	void freshVerifier() {
		google.respondWith(200);
		google.publish(key1);
		verifier = newVerifier(google.jwksUri(), Duration.ofHours(1), Duration.ofMillis(100));
	}

	static JwksIdTokenVerifier newVerifier(URI jwks, Duration cacheTtl, Duration minRefresh) {
		return new JwksIdTokenVerifier(jwks, Set.of("https://accounts.google.com", "accounts.google.com"),
				Set.of(FakeGoogle.CLIENT_ID, "another-client-id"), Duration.ofSeconds(60), cacheTtl, minRefresh,
				Duration.ofHours(1), Duration.ofSeconds(2), Clock.systemUTC());
	}

	void assertRejected(String token, String nonce, Failure expected) {
		assertThatThrownBy(() -> verifier.verify(token, nonce)).isInstanceOfSatisfying(IdTokenVerificationException.class,
				e -> assertThat(e.failure()).isEqualTo(expected));
	}

	void assertRejected(String token, String nonce) {
		assertThatThrownBy(() -> verifier.verify(token, nonce)).isInstanceOf(IdTokenVerificationException.class)
				.satisfies(e -> assertThat(((IdTokenVerificationException) e).failure().unavailable()).isFalse());
	}

	// ---------------------------------------------------------------- happy path

	@Test
	void acceptsAValidTokenAndReturnsTheMinimalClaims() throws Exception {
		VerifiedIdToken token = verifier.verify(FakeGoogle.sign(key1, FakeGoogle.claims("sub-1", "n-1").build()), "n-1");
		assertThat(token.subject()).isEqualTo("sub-1");
		assertThat(token.email()).isEqualTo("sub-1@example.test");
		assertThat(token.name()).isEqualTo("Test User");
		assertThat(token.toString()).doesNotContain("sub-1").doesNotContain("example.test").doesNotContain("Test User");
	}

	@Test
	void acceptsBothGoogleIssuerSpellingsAndAnyConfiguredClientId() throws Exception {
		verifier.verify(FakeGoogle.sign(key1, FakeGoogle.claims("s", "n").issuer("accounts.google.com").build()), "n");
		verifier.verify(FakeGoogle.sign(key1, FakeGoogle.claims("s", "n").audience("another-client-id").build()), "n");
		verifier.verify(FakeGoogle.sign(key1,
				FakeGoogle.claims("s", "n").audience(java.util.List.of("someone-else", FakeGoogle.CLIENT_ID)).build()), "n");
	}

	@Test
	void anEmailIsExposedOnlyWhenTheProviderSaysItIsVerified() throws Exception {
		assertThat(verifier.verify(FakeGoogle.sign(key1, FakeGoogle.claims("s", "n").claim("email_verified", false).build()), "n")
				.email()).isNull();
		assertThat(verifier.verify(FakeGoogle.sign(key1, FakeGoogle.claims("s", "n").claim("email_verified", null).build()), "n")
				.email()).isNull();
		assertThat(verifier.verify(FakeGoogle.sign(key1, FakeGoogle.claims("s", "n").claim("email_verified", "true").build()), "n")
				.email()).isEqualTo("s@example.test");
		assertThat(verifier.verify(FakeGoogle.sign(key1, FakeGoogle.claims("s", "n").claim("email", null).build()), "n")
				.email()).isNull();
	}

	// ---------------------------------------------------------------- rejections

	@Test
	void rejectsABadSignature() {
		// signed by another key that claims the published key id
		assertRejected(FakeGoogle.sign(attacker, FakeGoogle.claims("s", "n").build()), "n", Failure.BAD_SIGNATURE);
		// a valid token with a tampered payload
		String token = FakeGoogle.sign(key1, FakeGoogle.claims("victim", "n").build());
		String[] parts = token.split("\\.");
		String forged = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
				FakeGoogle.claims("attacker", "n").build().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
		assertRejected(parts[0] + "." + forged + "." + parts[2], "n", Failure.BAD_SIGNATURE);
	}

	@Test
	void rejectsAWrongAudience() {
		assertRejected(FakeGoogle.sign(key1, FakeGoogle.claims("s", "n").audience("someone-elses-app").build()), "n",
				Failure.BAD_AUDIENCE);
		assertRejected(FakeGoogle.sign(key1, new JWTClaimsSet.Builder(FakeGoogle.claims("s", "n").build()).audience(
				(String) null).build()), "n", Failure.MISSING_CLAIM);
	}

	@Test
	void rejectsAWrongIssuer() {
		assertRejected(FakeGoogle.sign(key1, FakeGoogle.claims("s", "n").issuer("https://evil.example").build()), "n",
				Failure.BAD_ISSUER);
		assertRejected(FakeGoogle.sign(key1, FakeGoogle.claims("s", "n").issuer("http://accounts.google.com").build()), "n",
				Failure.BAD_ISSUER);
	}

	@Test
	void rejectsAnExpiredTokenButToleratesSmallClockSkew() throws Exception {
		Instant now = Instant.now();
		assertRejected(FakeGoogle.sign(key1,
				FakeGoogle.claims("s", "n").issueTime(Date.from(now.minusSeconds(7200)))
						.expirationTime(Date.from(now.minusSeconds(300))).build()),
				"n", Failure.EXPIRED);
		// 20 s past expiry is inside the 60 s skew
		verifier.verify(FakeGoogle.sign(key1, FakeGoogle.claims("s", "n").issueTime(Date.from(now.minusSeconds(3600)))
				.expirationTime(Date.from(now.minusSeconds(20))).build()), "n");
	}

	@Test
	void rejectsATokenIssuedInTheFutureAndOneWithoutExpiry() {
		Instant now = Instant.now();
		assertRejected(FakeGoogle.sign(key1, FakeGoogle.claims("s", "n").issueTime(Date.from(now.plusSeconds(600)))
				.expirationTime(Date.from(now.plusSeconds(4200))).build()), "n", Failure.NOT_YET_VALID);
		assertRejected(FakeGoogle.sign(key1, FakeGoogle.claims("s", "n").expirationTime(null).build()), "n",
				Failure.MISSING_CLAIM);
		assertRejected(FakeGoogle.sign(key1, FakeGoogle.claims("s", "n").issueTime(null).build()), "n", Failure.MISSING_CLAIM);
	}

	@Test
	void rejectsAMissingOrWrongNonceAndAMissingSubject() {
		assertRejected(FakeGoogle.sign(key1, FakeGoogle.claims("s", "other").build()), "n", Failure.BAD_NONCE);
		assertRejected(FakeGoogle.sign(key1, FakeGoogle.claims("s", "n").claim("nonce", null).build()), "n", Failure.BAD_NONCE);
		assertRejected(FakeGoogle.sign(key1, FakeGoogle.claims("s", "n").build()), null, Failure.BAD_NONCE);
		assertRejected(FakeGoogle.sign(key1, FakeGoogle.claims("s", "n").subject(null).build()), "n", Failure.MISSING_CLAIM);
		assertRejected(FakeGoogle.sign(key1, FakeGoogle.claims("x".repeat(256), "n").build()), "n", Failure.MISSING_CLAIM);
	}

	@Test
	void rejectsAlgNoneAndHmacTokensIncludingThePublicKeyAsSecretTrick() throws Exception {
		JWTClaimsSet claims = FakeGoogle.claims("s", "n").build();
		// alg: none
		assertRejected(new PlainJWT(claims).serialize(), "n");
		// HS256 keyed with the (public) RSA modulus bytes: the classic algorithm-confusion attack
		byte[] secret = key1.toPublicJWK().getModulus().decode();
		SignedJWT hs = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("kid-1").build(), claims);
		hs.sign(new MACSigner(secret));
		assertRejected(hs.serialize(), "n");
		// ES256 with a key that is not in the set, and with the kid of the RSA key
		ECKey ec = new ECKeyGenerator(Curve.P_256).keyID("kid-1").generate();
		SignedJWT es = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("kid-1").build(), claims);
		es.sign(new ECDSASigner(ec));
		assertRejected(es.serialize(), "n");
		// RS256 token claiming the alg none in a forged header is just a bad signature/format
		String token = FakeGoogle.sign(key1, claims);
		String noneHeader = java.util.Base64.getUrlEncoder().withoutPadding()
				.encodeToString("{\"alg\":\"none\",\"kid\":\"kid-1\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		assertRejected(noneHeader + "." + token.split("\\.")[1] + "." + token.split("\\.")[2], "n");
	}

	@Test
	void rejectsGarbage() {
		assertRejected("not-a-jwt", "n", Failure.MALFORMED);
		assertRejected("", "n", Failure.MALFORMED);
		assertRejected("a.b.c", "n", Failure.MALFORMED);
	}

	// ---------------------------------------------------------------- JWKS handling

	@Test
	void theKeySetIsFetchedOnceAndCached() throws Exception {
		int before = google.fetches();
		for (int i = 0; i < 5; i++) {
			verifier.verify(FakeGoogle.sign(key1, FakeGoogle.claims("s", "n").build()), "n");
		}
		assertThat(google.fetches() - before).isEqualTo(1);
	}

	@Test
	void anUnknownKeyIdTriggersARefreshSoARotatedKeyIsAccepted() throws Exception {
		verifier.verify(FakeGoogle.sign(key1, FakeGoogle.claims("s", "n").build()), "n");
		int afterFirst = google.fetches();
		// Google rotates: key2 appears, key1 stays for a while
		google.publish(key1, key2);
		Thread.sleep(200); // the refresh rate limit (100 ms here) has passed
		assertThat(verifier.verify(FakeGoogle.sign(key2, FakeGoogle.claims("s2", "n").build()), "n").subject())
				.isEqualTo("s2");
		assertThat(google.fetches()).isEqualTo(afterFirst + 1);
		// the old key still works from the refreshed cache without another fetch
		verifier.verify(FakeGoogle.sign(key1, FakeGoogle.claims("s", "n").build()), "n");
		assertThat(google.fetches()).isEqualTo(afterFirst + 1);
	}

	@Test
	void aRetiredKeyIsRejectedOnceTheRefreshDropsIt() throws Exception {
		verifier.verify(FakeGoogle.sign(key1, FakeGoogle.claims("s", "n").build()), "n");
		google.publish(key2);
		Thread.sleep(200);
		// the unknown kid forces the refresh; kid-2 verifies, then kid-1 is gone
		verifier.verify(FakeGoogle.sign(key2, FakeGoogle.claims("s", "n").build()), "n");
		assertRejected(FakeGoogle.sign(key1, FakeGoogle.claims("s", "n").build()), "n");
	}

	@Test
	void aFloodOfUnknownKeyIdsDoesNotHammerTheProviderAndIsNotAnOutage() throws Exception {
		// a long minimum refresh interval: at most one refresh for the whole flood
		verifier = newVerifier(google.jwksUri(), Duration.ofHours(1), Duration.ofSeconds(30));
		verifier.verify(FakeGoogle.sign(key1, FakeGoogle.claims("s", "n").build()), "n");
		int before = google.fetches();
		RSAKey ghost = FakeGoogle.newKey("ghost");
		for (int i = 0; i < 20; i++) {
			// rejected as a bad token (401), never as "provider unavailable" (503)
			assertRejected(FakeGoogle.sign(ghost, FakeGoogle.claims("s", "n").build()), "n");
		}
		assertThat(google.fetches() - before).isLessThanOrEqualTo(1); // the first unknown kid may refresh once
	}

	@Test
	void anUnreachableJwksIsReportedAsUnavailableNotAsABadToken() {
		google.respondWith(500);
		JwksIdTokenVerifier cold = newVerifier(google.jwksUri(), Duration.ofHours(1), Duration.ofMillis(100));
		assertThatThrownBy(() -> cold.verify(FakeGoogle.sign(key1, FakeGoogle.claims("s", "n").build()), "n"))
				.isInstanceOfSatisfying(IdTokenVerificationException.class,
						e -> assertThat(e.failure()).isEqualTo(Failure.JWKS_UNAVAILABLE));
	}

	@Test
	void cachedKeysKeepVerifyingWhileTheProviderIsDown() throws Exception {
		verifier.verify(FakeGoogle.sign(key1, FakeGoogle.claims("s", "n").build()), "n");
		google.respondWith(503);
		verifier.verify(FakeGoogle.sign(key1, FakeGoogle.claims("s", "n").build()), "n");
	}

	@Test
	void theJwksUriMustBeHttpsOrLoopback() {
		JwksIdTokenVerifier.requireSecureJwksUri(URI.create("https://www.googleapis.com/oauth2/v3/certs"));
		JwksIdTokenVerifier.requireSecureJwksUri(URI.create("http://127.0.0.1:9/certs"));
		JwksIdTokenVerifier.requireSecureJwksUri(URI.create("http://localhost:9/certs"));
		assertThatThrownBy(() -> JwksIdTokenVerifier.requireSecureJwksUri(URI.create("http://www.googleapis.com/certs")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> JwksIdTokenVerifier.requireSecureJwksUri(URI.create("file:///etc/passwd")))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
