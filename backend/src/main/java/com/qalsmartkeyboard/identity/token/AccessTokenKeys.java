package com.qalsmartkeyboard.identity.token;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;

/**
 * Key material for access tokens: one ES256 (ECDSA P-256 / SHA-256) signing key and the public keys that verify. ES256
 * is asymmetric, so other services can verify with the public key only (SD 9.3 allows EdDSA or ES256; ES256 needs
 * no extra crypto provider in Nimbus or the JDK).
 *
 * <p>Fails closed at startup (as the local KMS, B1.5): no configured key pair means no startup, unless the dev/test
 * ephemeral switch is on, which is refused under {@code prod}, {@code production} and {@code staging}. A configured
 * pair must be P-256 and must match (checked by signing a probe).
 */
public final class AccessTokenKeys {

	static final String PRODUCTION_LIKE_PROFILES = "prod | production | staging";
	private static final Logger log = LoggerFactory.getLogger(AccessTokenKeys.class);

	private final String signingKeyId;
	private final ECPrivateKey signingKey;
	private final JWKSet verificationKeys;

	AccessTokenKeys(String signingKeyId, ECPrivateKey signingKey, JWKSet verificationKeys) {
		this.signingKeyId = signingKeyId;
		this.signingKey = signingKey;
		this.verificationKeys = verificationKeys;
	}

	String signingKeyId() {
		return signingKeyId;
	}

	ECPrivateKey signingKey() {
		return signingKey;
	}

	/** Public keys only. */
	public JWKSet verificationKeys() {
		return verificationKeys;
	}

	static AccessTokenKeys load(TokenProperties properties, Environment environment, SecureRandom random) {
		TokenProperties.SigningKey configured = properties.signingKey();
		String keyId = requireKeyId(configured.keyId());
		ECPrivateKey privateKey;
		ECPublicKey publicKey;
		boolean hasPrivate = configured.privateKey() != null && !configured.privateKey().isBlank();
		if (hasPrivate) {
			if (configured.publicKey() == null || configured.publicKey().isBlank()) {
				throw new IllegalStateException("qal.identity.tokens.signing-key.public-key is required with the private key");
			}
			privateKey = (ECPrivateKey) parse(configured.privateKey(), true, "signing-key.private-key");
			publicKey = (ECPublicKey) parse(configured.publicKey(), false, "signing-key.public-key");
			requireP256(publicKey, "signing-key.public-key");
			requireMatchingPair(privateKey, publicKey, random);
		}
		else if (configured.allowEphemeral()) {
			if (environment.acceptsProfiles(Profiles.of(PRODUCTION_LIKE_PROFILES))) {
				throw new IllegalStateException("qal.identity.tokens.signing-key.allow-ephemeral is refused under the "
						+ "prod/production/staging profiles; configure the signing key pair");
			}
			KeyPair pair = generateP256(random);
			privateKey = (ECPrivateKey) pair.getPrivate();
			publicKey = (ECPublicKey) pair.getPublic();
			byte[] suffix = new byte[4];
			random.nextBytes(suffix);
			keyId = "ephemeral-" + HexFormat.of().formatHex(suffix);
			log.warn("Access tokens are signed with an EPHEMERAL key ({}): tokens die with this JVM and other instances "
					+ "reject them. Never use this outside development and tests.", keyId);
		}
		else {
			throw new IllegalStateException("qal.identity.tokens.signing-key is not configured (env QAL_JWT_PRIVATE_KEY, "
					+ "QAL_JWT_PUBLIC_KEY, QAL_JWT_KEY_ID: a P-256 key pair as PEM); "
					+ "qal.identity.tokens.signing-key.allow-ephemeral=true is for development and tests only");
		}

		List<JWK> keys = new ArrayList<>();
		Set<String> ids = new HashSet<>();
		keys.add(jwk(keyId, publicKey));
		ids.add(keyId);
		for (TokenProperties.VerificationKey previous : properties.previousKeys()) {
			String previousId = requireKeyId(previous.keyId());
			if (!ids.add(previousId)) {
				throw new IllegalStateException("Duplicate access-token key id " + previousId);
			}
			ECPublicKey key = (ECPublicKey) parse(previous.publicKey(), false, "previous-keys.public-key");
			requireP256(key, "previous-keys.public-key");
			keys.add(jwk(previousId, key));
		}
		return new AccessTokenKeys(keyId, privateKey, new JWKSet(keys));
	}

	private static JWK jwk(String keyId, ECPublicKey key) {
		return new ECKey.Builder(Curve.P_256, key).keyID(keyId).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.ES256)
				.build();
	}

	private static String requireKeyId(String keyId) {
		if (keyId == null || !keyId.matches("^[A-Za-z0-9._-]{1,64}$")) {
			throw new IllegalStateException("Access-token key ids must be 1-64 characters of A-Z a-z 0-9 . _ -");
		}
		return keyId;
	}

	/** Accepts PEM (headers and line breaks ignored) or bare base64 of the DER encoding. */
	static java.security.Key parse(String pemOrBase64, boolean privateKey, String name) {
		String base64 = pemOrBase64.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
		byte[] der;
		try {
			der = Base64.getDecoder().decode(base64.getBytes(StandardCharsets.US_ASCII));
		}
		catch (IllegalArgumentException e) {
			throw new IllegalStateException("qal.identity.tokens." + name + " is not PEM or base64");
		}
		try {
			KeyFactory factory = KeyFactory.getInstance("EC");
			return privateKey ? factory.generatePrivate(new PKCS8EncodedKeySpec(der))
					: factory.generatePublic(new X509EncodedKeySpec(der));
		}
		catch (GeneralSecurityException | ClassCastException e) {
			// no cause: never echo key material
			throw new IllegalStateException("qal.identity.tokens." + name + " is not an EC "
					+ (privateKey ? "PKCS#8 private" : "X.509 public") + " key");
		}
	}

	private static void requireP256(ECPublicKey key, String name) {
		if (!Curve.P_256.equals(Curve.forECParameterSpec(key.getParams()))) {
			throw new IllegalStateException("qal.identity.tokens." + name + " must be a P-256 (prime256v1) key");
		}
	}

	private static void requireMatchingPair(ECPrivateKey privateKey, ECPublicKey publicKey, SecureRandom random) {
		try {
			byte[] probe = new byte[32];
			random.nextBytes(probe);
			Signature signer = Signature.getInstance("SHA256withECDSA");
			signer.initSign(privateKey, random);
			signer.update(probe);
			byte[] signature = signer.sign();
			Signature verifier = Signature.getInstance("SHA256withECDSA");
			verifier.initVerify(publicKey);
			verifier.update(probe);
			if (!verifier.verify(signature)) {
				throw new IllegalStateException("qal.identity.tokens.signing-key private and public keys do not match");
			}
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("qal.identity.tokens.signing-key cannot sign with ES256");
		}
	}

	static KeyPair generateP256(SecureRandom random) {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
			generator.initialize(new ECGenParameterSpec("secp256r1"), random);
			return generator.generateKeyPair();
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("P-256 key generation unavailable", e);
		}
	}

	@Override
	public String toString() {
		return "AccessTokenKeys[signingKeyId=" + signingKeyId + ", verificationKeys=" + verificationKeys.size() + "]";
	}
}
