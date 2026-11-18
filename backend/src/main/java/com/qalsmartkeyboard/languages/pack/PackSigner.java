package com.qalsmartkeyboard.languages.pack;

/**
 * The server-side pack signing key (port). Implementations: {@link LocalEd25519PackSigner} (a configured or, outside
 * production-like profiles, ephemeral key held in memory) and {@link KmsPackSigner} (a cloud KMS asymmetric key; the
 * private key never leaves the KMS). See ADR 0019.
 *
 * <p>Callers never sign raw data: {@link PackWriter} signs {@code "QAL-PACK-MANIFEST-v1\0" || manifest} and
 * {@link KeyEndorsement} signs {@code "QAL-KEY-ENDORSEMENT-v1\0" || ...}, so a signature made for one purpose can never be
 * replayed for the other.
 */
public interface PackSigner {

	/** The key id written into container headers and manifests ({@code resource_signing_keys.key_id}). */
	String keyId();

	/** The raw 32-byte Ed25519 public key. */
	byte[] publicKey();

	/**
	 * Pure Ed25519 (RFC 8032) signature over {@code message}.
	 *
	 * @throws IllegalStateException if the key is not available (not configured, KMS unreachable)
	 */
	byte[] sign(byte[] message);
}
