package com.qalsmartkeyboard.languages.pack;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.Objects;

/**
 * Ed25519 signing with a key held in process memory (JDK {@code Signature "Ed25519"}): the 32-byte RFC 8032 seed comes
 * from configuration ({@code QAL_PACK_SIGNING_PRIVATE_KEY}), or is random per JVM for development and tests. Production
 * keys belong in a KMS ({@link KmsPackSigner}, ADR 0007 D10, ADR 0019); a configured seed is the interim. A probe signs and
 * strictly verifies a message at construction, so a broken key never reaches a pack.
 */
public final class LocalEd25519PackSigner implements PackSigner {

	private final String keyId;
	private final PrivateKey privateKey;
	private final byte[] publicKey;

	public LocalEd25519PackSigner(String keyId, byte[] seed) {
		if (!PackFormat.isValidKeyId(Objects.requireNonNull(keyId, "keyId"))) {
			throw new IllegalArgumentException("pack signing key id must be 1-120 chars of [A-Za-z0-9._-]");
		}
		KeyPair pair = Ed25519.keyPairFromSeed(seed);
		this.keyId = keyId;
		this.privateKey = pair.getPrivate();
		this.publicKey = Ed25519.rawPublicKey(pair.getPublic());
		probe(this);
	}

	@Override
	public String keyId() {
		return keyId;
	}

	@Override
	public byte[] publicKey() {
		return publicKey.clone();
	}

	@Override
	public byte[] sign(byte[] message) {
		return Ed25519.sign(privateKey, message);
	}

	/** Signs a probe message and verifies it strictly with the announced public key. */
	static void probe(PackSigner signer) {
		byte[] message = "QAL-PACK-SIGNER-PROBE-v1\0".getBytes(StandardCharsets.US_ASCII);
		if (!Ed25519.isValidPublicKey(signer.publicKey())
				|| !Ed25519.verifyStrict(signer.publicKey(), message, signer.sign(message))) {
			throw new IllegalStateException("The pack signing key failed its sign/verify probe");
		}
	}

	@Override
	public String toString() {
		return "LocalEd25519PackSigner[" + keyId + "]";
	}
}
