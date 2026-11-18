package com.qalsmartkeyboard.languages.pack;

/**
 * Why the verifier refused a container: the codes of {@code docs/specs/pack-format-v1.md} section 6 and the Rust
 * {@code PackRejectReason}, spelled exactly as there (they are also the API's {@code reason} values).
 */
public enum PackRejectReason {
	/** Container framing is invalid (bad magic, bad lengths, truncated, trailing bytes). */
	MALFORMED,
	/** Container, manifest or artifact exceeds the configured limits. */
	TOO_LARGE,
	/** Container format version is not 1. */
	UNSUPPORTED_FORMAT_VERSION,
	/** The header key id is not in the trusted key set. */
	UNKNOWN_KEY,
	/** The header key id names a revoked key. */
	REVOKED_KEY,
	/** Strict Ed25519 verification failed. */
	BAD_SIGNATURE,
	/** The authenticated manifest is not valid JSON or a member is missing or invalid. */
	INVALID_MANIFEST,
	/** {@code resourceType} is not one of the known resource types. */
	UNSUPPORTED_RESOURCE_TYPE,
	/** The manifest's {@code signatureKeyId} differs from the header key id. */
	KEY_MISMATCH,
	/** The artifact length differs from the manifest's {@code sizeBytes}. */
	SIZE_MISMATCH,
	/** The artifact SHA-256 differs from the manifest's {@code sha256}. */
	CHECKSUM_MISMATCH
}
