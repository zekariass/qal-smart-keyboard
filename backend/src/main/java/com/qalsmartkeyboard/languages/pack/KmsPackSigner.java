package com.qalsmartkeyboard.languages.pack;

import java.util.Objects;

/**
 * {@link PackSigner} over a KMS asymmetric key ({@link KmsSigningClient}). The public key is read once at construction
 * and a sign/verify probe runs, so a wrong key name, algorithm or permission fails the start instead of the first
 * publish. Every signature the KMS returns is checked strictly against that public key before it is used.
 */
public final class KmsPackSigner implements PackSigner {

	private final KmsSigningClient client;
	private final String keyName;
	private final String keyId;
	private final byte[] publicKey;

	public KmsPackSigner(KmsSigningClient client, String keyName, String keyId) {
		this.client = Objects.requireNonNull(client, "client");
		if (keyName == null || keyName.isBlank()) {
			throw new IllegalArgumentException("KMS key name is required");
		}
		if (!PackFormat.isValidKeyId(keyId)) {
			throw new IllegalArgumentException("pack signing key id must be 1-120 chars of [A-Za-z0-9._-]");
		}
		this.keyName = keyName;
		this.keyId = keyId;
		this.publicKey = client.publicKey(keyName).clone();
		LocalEd25519PackSigner.probe(this);
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
		byte[] signature = client.signEd25519(keyName, message);
		if (!Ed25519.verifyStrict(publicKey, message, signature)) {
			throw new IllegalStateException("The KMS returned a signature that does not verify");
		}
		return signature;
	}

	@Override
	public String toString() {
		return "KmsPackSigner[" + keyId + "]";
	}
}
