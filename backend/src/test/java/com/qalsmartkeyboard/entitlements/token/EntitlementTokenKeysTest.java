package com.qalsmartkeyboard.entitlements.token;

import static com.qalsmartkeyboard.entitlements.token.TokenFixtures.RANDOM;
import static com.qalsmartkeyboard.entitlements.token.TokenFixtures.pem;
import static com.qalsmartkeyboard.entitlements.token.TokenFixtures.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.nimbusds.jose.util.Base64URL;
import com.qalsmartkeyboard.entitlements.token.EntitlementTokenProperties.SigningKey;
import com.qalsmartkeyboard.entitlements.token.EntitlementTokenProperties.VerificationKey;

/** B4.4 key handling: fail closed, the ephemeral switch, pair and curve checks, JWK input, the published set. */
class EntitlementTokenKeysTest {

	static EntitlementTokenProperties props(SigningKey key, List<VerificationKey> previous) {
		return properties(key, previous, List.of("ON_DEVICE_AI_MODELS"));
	}

	static EntitlementTokenKeys load(EntitlementTokenProperties p, String... profiles) {
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles(profiles);
		return EntitlementTokenKeys.load(p, env, RANDOM);
	}

	@Test
	void noConfiguredKeyFailsClosed() {
		assertThatThrownBy(() -> load(props(new SigningKey("ent-1", null, null, false), List.of())))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("QAL_ENTITLEMENT_TOKEN_PRIVATE_KEY");
		assertThatThrownBy(() -> load(props(new SigningKey("ent-1", "  ", "", false), List.of())))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void ephemeralKeysOnlyOutsideProductionLikeProfiles() {
		EntitlementTokenKeys keys = load(props(new SigningKey("ent-1", null, null, true), List.of()), "dev");
		assertThat(keys.signingKeyId()).startsWith("ephemeral-");
		assertThat(keys.publicKeys()).containsOnlyKeys(keys.signingKeyId());
		for (String profile : List.of("prod", "production", "staging")) {
			assertThatThrownBy(() -> load(props(new SigningKey("ent-1", null, null, true), List.of()), profile))
					.as(profile).isInstanceOf(IllegalStateException.class).hasMessageContaining("refused");
		}
		// a configured key is used even with the switch on, also in production
		TokenFixtures.Pem pem = pem();
		assertThat(load(props(new SigningKey("ent-1", pem.privateKey(), pem.publicKey(), true), List.of()), "prod")
				.signingKeyId()).isEqualTo("ent-1");
	}

	@Test
	void pemPairMustMatchAndBeEd25519() throws Exception {
		TokenFixtures.Pem a = pem();
		TokenFixtures.Pem b = pem();
		assertThat(load(props(new SigningKey("ent-1", a.privateKey(), a.publicKey(), false), List.of())).signingKeyId())
				.isEqualTo("ent-1");
		// bare base64 DER works too
		String bare = Base64.getEncoder().encodeToString(a.pair().getPrivate().getEncoded());
		assertThat(load(props(new SigningKey("ent-1", bare, a.publicKey(), false), List.of())).signingKeyId())
				.isEqualTo("ent-1");

		assertThatThrownBy(() -> load(props(new SigningKey("ent-1", a.privateKey(), b.publicKey(), false), List.of())))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("do not match");
		assertThatThrownBy(() -> load(props(new SigningKey("ent-1", a.privateKey(), null, false), List.of())))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("public-key is required");

		KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
		ec.initialize(new ECGenParameterSpec("secp256r1"));
		var p256 = ec.generateKeyPair();
		String ecPrivate = TokenFixtures.pem("PRIVATE KEY", p256.getPrivate().getEncoded());
		String ecPublic = TokenFixtures.pem("PUBLIC KEY", p256.getPublic().getEncoded());
		assertThatThrownBy(() -> load(props(new SigningKey("ent-1", ecPrivate, ecPublic, false), List.of())))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("not an Ed25519")
				.hasMessageNotContaining(ecPrivate.substring(30, 60));
		assertThatThrownBy(() -> load(props(new SigningKey("ent-1", a.privateKey(), ecPublic, false), List.of())))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("not an Ed25519");
		assertThatThrownBy(() -> load(props(new SigningKey("ent-1", "not!base64", a.publicKey(), false), List.of())))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void privateKeyAsJwk() {
		TokenFixtures.Pem a = pem();
		EntitlementTokenKeys fromPem = load(props(new SigningKey("ent-1", a.privateKey(), a.publicKey(), false), List.of()));
		byte[] x = EntitlementTokenKeys.rawPublic(a.pair().getPublic());
		byte[] pkcs8 = a.pair().getPrivate().getEncoded();
		byte[] d = java.util.Arrays.copyOfRange(pkcs8, pkcs8.length - 32, pkcs8.length);
		String jwk = "{\"kty\":\"OKP\",\"crv\":\"Ed25519\",\"d\":\"" + Base64URL.encode(d) + "\",\"x\":\""
				+ Base64URL.encode(x) + "\"}";
		EntitlementTokenKeys fromJwk = load(props(new SigningKey("ent-1", jwk, null, false), List.of()));
		assertThat(EntitlementTokenKeys.rawPublic(fromJwk.publicKeys().get("ent-1"))).isEqualTo(x);
		assertThat(fromJwk.jwkSet().toString()).isEqualTo(fromPem.jwkSet().toString());
		// a JWK plus a matching public PEM is fine, a different one is not
		assertThat(load(props(new SigningKey("ent-1", jwk, a.publicKey(), false), List.of())).signingKeyId())
				.isEqualTo("ent-1");
		assertThatThrownBy(() -> load(props(new SigningKey("ent-1", jwk, pem().publicKey(), false), List.of())))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("does not match");
		// d and x of different keys
		String mixed = "{\"kty\":\"OKP\",\"crv\":\"Ed25519\",\"d\":\"" + Base64URL.encode(d) + "\",\"x\":\""
				+ Base64URL.encode(EntitlementTokenKeys.rawPublic(pem().pair().getPublic())) + "\"}";
		assertThatThrownBy(() -> load(props(new SigningKey("ent-1", mixed, null, false), List.of())))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("do not match");
		String publicOnly = "{\"kty\":\"OKP\",\"crv\":\"Ed25519\",\"x\":\"" + Base64URL.encode(x) + "\"}";
		assertThatThrownBy(() -> load(props(new SigningKey("ent-1", publicOnly, null, false), List.of())))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("no 'd'");
		String x25519 = "{\"kty\":\"OKP\",\"crv\":\"X25519\",\"d\":\"" + Base64URL.encode(d) + "\",\"x\":\""
				+ Base64URL.encode(x) + "\"}";
		assertThatThrownBy(() -> load(props(new SigningKey("ent-1", x25519, null, false), List.of())))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("Ed25519");
	}

	@Test
	void previousKeysArePublishedAfterTheSigningKeyAndKeyIdsAreChecked() {
		TokenFixtures.Pem current = pem();
		TokenFixtures.Pem old = pem();
		EntitlementTokenKeys keys = load(props(new SigningKey("ent-2", current.privateKey(), current.publicKey(), false),
				List.of(new VerificationKey("ent-1", old.publicKey()))));
		assertThat(keys.publicKeys().keySet()).containsExactly("ent-2", "ent-1");

		assertThatThrownBy(() -> load(props(new SigningKey("ent-2", current.privateKey(), current.publicKey(), false),
				List.of(new VerificationKey("ent-2", old.publicKey()))))).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Duplicate");
		assertThatThrownBy(() -> load(props(new SigningKey("ent 2", current.privateKey(), current.publicKey(), false),
				List.of()))).isInstanceOf(IllegalStateException.class).hasMessageContaining("key ids");
		assertThatThrownBy(() -> load(props(new SigningKey("ent-2", current.privateKey(), current.publicKey(), false),
				List.of(new VerificationKey("ent-1", null))))).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void theJwkSetIsPublicOnly() {
		TokenFixtures.Pem current = pem();
		EntitlementTokenKeys keys = load(props(new SigningKey("ent-2", current.privateKey(), current.publicKey(), false),
				List.of(new VerificationKey("ent-1", pem().publicKey()))));
		Map<String, Object> json = keys.jwkSet().toJSONObject(true);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> list = (List<Map<String, Object>>) json.get("keys");
		assertThat(list).hasSize(2);
		for (Map<String, Object> k : list) {
			assertThat(k).containsOnlyKeys("kty", "crv", "x", "kid", "use", "alg").containsEntry("kty", "OKP")
					.containsEntry("crv", "Ed25519").containsEntry("use", "sig").containsEntry("alg", "EdDSA");
		}
		assertThat(keys.toString()).doesNotContain(current.privateKey().substring(30, 50));
		assertThat(new SigningKey("ent-2", current.privateKey(), current.publicKey(), false).toString())
				.contains("<redacted>").doesNotContain(current.privateKey().substring(30, 50));
	}

	@Test
	void lifetimesAreCappedBySystemDesign() {
		SigningKey key = new SigningKey("ent-1", null, null, true);
		assertThatThrownBy(() -> new EntitlementTokenProperties("i", "a", Duration.ofHours(25), Duration.ofHours(72),
				List.of(), key, List.of())).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new EntitlementTokenProperties("i", "a", Duration.ZERO, Duration.ofHours(72),
				List.of(), key, List.of())).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new EntitlementTokenProperties("i", "a", Duration.ofHours(24), Duration.ofHours(73),
				List.of(), key, List.of())).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new EntitlementTokenProperties("i", "a", Duration.ofHours(24), Duration.ofHours(72),
				List.of("bad code"), key, List.of())).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new EntitlementTokenProperties(" ", "a", Duration.ofHours(24), Duration.ofHours(72),
				List.of(), key, List.of())).isInstanceOf(IllegalArgumentException.class);
	}
}
