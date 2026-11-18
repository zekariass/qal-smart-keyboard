package com.qalsmartkeyboard.languages.pack;

/**
 * The seam for a cloud KMS asymmetric signing key (no SDK in this build yet, ADR 0019). An adapter implements it over,
 * for example, Google Cloud KMS {@code AsymmetricSign} with an Ed25519 key ({@code EC_SIGN_ED25519}) or AWS KMS
 * {@code Sign} where Ed25519 keys are offered. The private key never leaves the KMS.
 */
public interface KmsSigningClient {

	/** The raw 32-byte Ed25519 public key of {@code keyName} (adapters convert PEM/SPKI). */
	byte[] publicKey(String keyName);

	/** A pure Ed25519 (RFC 8032) signature over {@code message} by {@code keyName}: 64 bytes {@code R || S}. */
	byte[] signEd25519(String keyName, byte[] message);
}
