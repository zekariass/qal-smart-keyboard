package com.qalsmartkeyboard.languages.pack;

/**
 * Resource-exhaustion limits applied before any expensive work (pack spec section 9). All must be non-zero; the manifest
 * limit is at most 1 MiB and the container limit at most 2^31-1 (the largest Java array).
 */
public record PackLimits(long maxContainerBytes, long maxManifestBytes, long maxArtifactBytes) {

	/** 32 MiB + 128 KiB. */
	public static final long DEFAULT_MAX_CONTAINER_BYTES = 32L * 1024 * 1024 + 128 * 1024;
	public static final long DEFAULT_MAX_MANIFEST_BYTES = 64L * 1024;
	public static final long DEFAULT_MAX_ARTIFACT_BYTES = 32L * 1024 * 1024;
	public static final long HARD_MAX_MANIFEST_BYTES = 1024L * 1024;
	public static final long HARD_MAX_CONTAINER_BYTES = 0x7FFF_FFFFL;

	public static final PackLimits DEFAULT = new PackLimits(DEFAULT_MAX_CONTAINER_BYTES, DEFAULT_MAX_MANIFEST_BYTES,
			DEFAULT_MAX_ARTIFACT_BYTES);

	public PackLimits {
		if (maxContainerBytes <= 0 || maxManifestBytes <= 0 || maxArtifactBytes <= 0) {
			throw new IllegalArgumentException("pack limits must be non-zero");
		}
		if (maxManifestBytes > HARD_MAX_MANIFEST_BYTES) {
			throw new IllegalArgumentException("max manifest bytes exceeds the hard limit (1 MiB)");
		}
		if (maxContainerBytes > HARD_MAX_CONTAINER_BYTES) {
			throw new IllegalArgumentException("max container bytes exceeds the hard limit (2^31-1)");
		}
	}
}
