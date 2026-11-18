package com.qalsmartkeyboard.entitlements.token;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.util.Base64URL;

/**
 * Key material for entitlement tokens (ADR 0013): one Ed25519 signing key and the public keys that are published.
 *
 * <p>Ed25519 runs on the JDK ({@code Signature "Ed25519"}, JEP 339): Nimbus' own {@code Ed25519Signer} needs Google
 * Tink, an optional dependency this build does not carry. Nimbus is used for the JWS/JWK structures only.
 *
 * <p>Fails closed at startup like the access-token keys (B2.4) and the local KMS (B1.5): no configured key means no
 * startup, unless the dev/test ephemeral switch is on, which is refused under {@code prod}, {@code production} and
 * {@code staging}. A configured pair must be Ed25519 and must match (checked by signing a probe).
 */
public final class EntitlementTokenKeys {

	static final String PRODUCTION_LIKE_PROFILES = "prod | production | staging";
	private static final Logger log = LoggerFactory.getLogger(EntitlementTokenKeys.class);

	/** DER prefix of an Ed25519 SubjectPublicKeyInfo (RFC 8410): SEQUENCE { SEQUENCE { OID 1.3.101.112 }, BIT STRING }. */
	private static final byte[] SPKI_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");
	/** DER prefix of an Ed25519 PKCS#8 v1 private key (RFC 8410): version 0, algorithm, OCTET STRING { OCTET STRING }. */
	private static final byte[] PKCS8_PREFIX = HexFormat.of().parseHex("302e020100300506032b657004220420");
	private static final int KEY_BYTES = 32;

	private final String signingKeyId;
	private final PrivateKey signingKey;
	private final Map<String, PublicKey> publicKeys;
	private final JWKSet jwkSet;

	EntitlementTokenKeys(String signingKeyId, PrivateKey signingKey, Map<String, PublicKey> publicKeys) {
		this.signingKeyId = signingKeyId;
		this.signingKey = signingKey;
		this.publicKeys = Collections.unmodifiableMap(new LinkedHashMap<>(publicKeys));
		List<JWK> jwks = new ArrayList<>();
		this.publicKeys.forEach((kid, key) -> jwks.add(jwk(kid, key)));
		this.jwkSet = new JWKSet(jwks);
	}

	String signingKeyId() {
		return signingKeyId;
	}

	PrivateKey signingKey() {
		return signingKey;
	}

	/** Every published public key by {@code kid}, the signing key's first. */
	public Map<String, PublicKey> publicKeys() {
		return publicKeys;
	}

	/** The published key set (public keys only): {@code kty OKP}, {@code crv Ed25519}, {@code alg EdDSA}. */
	public JWKSet jwkSet() {
		return jwkSet;
	}

	static EntitlementTokenKeys load(EntitlementTokenProperties properties, Environment environment,
			SecureRandom random) {
		EntitlementTokenProperties.SigningKey configured = properties.signingKey();
		String keyId = requireKeyId(configured.keyId());
		PrivateKey privateKey;
		PublicKey publicKey;
		if (configured.privateKey() != null && !configured.privateKey().isBlank()) {
			String value = configured.privateKey().strip();
			if (value.startsWith("{")) {
				OctetKeyPair jwk = parseJwk(value, "signing-key.private-key");
				if (!jwk.isPrivate()) {
					throw new IllegalStateException("qal.entitlements.token.signing-key.private-key JWK has no 'd'");
				}
				privateKey = privateFromRaw(jwk.getDecodedD());
				publicKey = publicFromRaw(jwk.getDecodedX());
				if (configured.publicKey() != null && !configured.publicKey().isBlank()
						&& !Arrays.equals(rawPublic(publicKey),
								rawPublic(parsePublic(configured.publicKey(), "signing-key.public-key")))) {
					throw new IllegalStateException(
							"qal.entitlements.token.signing-key.public-key does not match the private key JWK");
				}
			}
			else {
				if (configured.publicKey() == null || configured.publicKey().isBlank()) {
					throw new IllegalStateException(
							"qal.entitlements.token.signing-key.public-key is required with a PEM private key");
				}
				privateKey = parsePrivatePem(value);
				publicKey = parsePublic(configured.publicKey(), "signing-key.public-key");
			}
			requireMatchingPair(privateKey, publicKey);
		}
		else if (configured.allowEphemeral()) {
			if (environment.acceptsProfiles(Profiles.of(PRODUCTION_LIKE_PROFILES))) {
				throw new IllegalStateException("qal.entitlements.token.signing-key.allow-ephemeral is refused under the "
						+ "prod/production/staging profiles; configure the Ed25519 signing key");
			}
			KeyPair pair = generate(random);
			privateKey = pair.getPrivate();
			publicKey = pair.getPublic();
			byte[] suffix = new byte[4];
			random.nextBytes(suffix);
			keyId = "ephemeral-" + HexFormat.of().formatHex(suffix);
			log.warn("Entitlement tokens are signed with an EPHEMERAL Ed25519 key ({}): no client pins it. Never use "
					+ "this outside development and tests.", keyId);
		}
		else {
			throw new IllegalStateException("qal.entitlements.token.signing-key is not configured (env "
					+ "QAL_ENTITLEMENT_TOKEN_PRIVATE_KEY, QAL_ENTITLEMENT_TOKEN_PUBLIC_KEY, QAL_ENTITLEMENT_TOKEN_KEY_ID: "
					+ "an Ed25519 key pair as PEM, or the private key as an OKP JWK); "
					+ "qal.entitlements.token.signing-key.allow-ephemeral=true is for development and tests only");
		}

		Map<String, PublicKey> keys = new LinkedHashMap<>();
		keys.put(keyId, publicKey);
		for (EntitlementTokenProperties.VerificationKey previous : properties.previousKeys()) {
			String previousId = requireKeyId(previous.keyId());
			if (keys.containsKey(previousId)) {
				throw new IllegalStateException("Duplicate entitlement-token key id " + previousId);
			}
			if (previous.publicKey() == null || previous.publicKey().isBlank()) {
				throw new IllegalStateException("qal.entitlements.token.previous-keys.public-key is required");
			}
			keys.put(previousId, parsePublic(previous.publicKey(), "previous-keys.public-key"));
		}
		return new EntitlementTokenKeys(keyId, privateKey, keys);
	}

	static OctetKeyPair jwk(String keyId, PublicKey key) {
		return new OctetKeyPair.Builder(Curve.Ed25519, Base64URL.encode(rawPublic(key))).keyID(keyId)
				.keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.EdDSA).build();
	}

	private static String requireKeyId(String keyId) {
		if (keyId == null || !keyId.matches("^[A-Za-z0-9._-]{1,64}$")) {
			throw new IllegalStateException("Entitlement-token key ids must be 1-64 characters of A-Z a-z 0-9 . _ -");
		}
		return keyId;
	}

	// ------------------------------------------------------------------ encodings

	/** The 32-byte public key ({@code x} of the JWK) of an Ed25519 public key. */
	public static byte[] rawPublic(PublicKey key) {
		byte[] der = key.getEncoded();
		if (der == null || der.length != SPKI_PREFIX.length + KEY_BYTES
				|| !Arrays.equals(der, 0, SPKI_PREFIX.length, SPKI_PREFIX, 0, SPKI_PREFIX.length)) {
			throw new IllegalStateException("Not an Ed25519 public key");
		}
		return Arrays.copyOfRange(der, SPKI_PREFIX.length, der.length);
	}

	/** An Ed25519 public key from its 32 raw bytes. */
	public static PublicKey publicFromRaw(byte[] x) {
		if (x == null || x.length != KEY_BYTES) {
			throw new IllegalStateException("An Ed25519 public key has 32 bytes");
		}
		try {
			return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(concat(SPKI_PREFIX, x)));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("Not an Ed25519 public key");
		}
	}

	/** An Ed25519 private key from its 32-byte seed ({@code d} of the JWK). */
	static PrivateKey privateFromRaw(byte[] d) {
		if (d == null || d.length != KEY_BYTES) {
			throw new IllegalStateException("An Ed25519 private key has 32 bytes");
		}
		byte[] der = concat(PKCS8_PREFIX, d);
		try {
			return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("Not an Ed25519 private key");
		}
		finally {
			Arrays.fill(der, (byte) 0);
		}
	}

	/** SPKI PEM, bare base64 DER, or a public OKP JWK. */
	static PublicKey parsePublic(String value, String name) {
		String trimmed = value.strip();
		if (trimmed.startsWith("{")) {
			return publicFromRaw(parseJwk(trimmed, name).getDecodedX());
		}
		try {
			return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der(trimmed, name)));
		}
		catch (GeneralSecurityException | ClassCastException e) {
			throw new IllegalStateException("qal.entitlements.token." + name + " is not an Ed25519 X.509 public key");
		}
	}

	private static PrivateKey parsePrivatePem(String value) {
		byte[] der = der(value, "signing-key.private-key");
		try {
			return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
		}
		catch (GeneralSecurityException | ClassCastException e) {
			// no cause: never echo key material
			throw new IllegalStateException(
					"qal.entitlements.token.signing-key.private-key is not an Ed25519 PKCS#8 private key");
		}
		finally {
			Arrays.fill(der, (byte) 0);
		}
	}

	private static byte[] der(String pemOrBase64, String name) {
		String base64 = pemOrBase64.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
		try {
			return Base64.getDecoder().decode(base64.getBytes(StandardCharsets.US_ASCII));
		}
		catch (IllegalArgumentException e) {
			throw new IllegalStateException("qal.entitlements.token." + name + " is not PEM, base64 or a JWK");
		}
	}

	private static OctetKeyPair parseJwk(String json, String name) {
		try {
			OctetKeyPair jwk = OctetKeyPair.parse(json);
			if (!Curve.Ed25519.equals(jwk.getCurve())) {
				throw new IllegalStateException("qal.entitlements.token." + name + " JWK must have crv Ed25519");
			}
			return jwk;
		}
		catch (ParseException e) {
			// no cause: never echo key material
			throw new IllegalStateException("qal.entitlements.token." + name + " is not an OKP JWK");
		}
	}

	private static void requireMatchingPair(PrivateKey privateKey, PublicKey publicKey) {
		try {
			byte[] probe = "qal-entitlement-token-key-check".getBytes(StandardCharsets.US_ASCII);
			Signature signer = Signature.getInstance("Ed25519");
			signer.initSign(privateKey);
			signer.update(probe);
			byte[] signature = signer.sign();
			Signature verifier = Signature.getInstance("Ed25519");
			verifier.initVerify(publicKey);
			verifier.update(probe);
			if (!verifier.verify(signature)) {
				throw new IllegalStateException(
						"qal.entitlements.token.signing-key private and public keys do not match");
			}
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("qal.entitlements.token.signing-key cannot sign with Ed25519");
		}
	}

	static KeyPair generate(SecureRandom random) {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
			generator.initialize(NamedParameterSpec.ED25519, random);
			return generator.generateKeyPair();
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("Ed25519 key generation unavailable", e);
		}
	}

	private static byte[] concat(byte[] a, byte[] b) {
		byte[] out = Arrays.copyOf(a, a.length + b.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}

	@Override
	public String toString() {
		return "EntitlementTokenKeys[signingKeyId=" + signingKeyId + ", publicKeys=" + publicKeys.keySet() + "]";
	}
}
