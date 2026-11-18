package com.qalsmartkeyboard.languages;

/**
 * Where pack containers are kept (S3-compatible object storage, MinIO locally). Locations are {@code s3://<bucket>/<key>}
 * URIs, the form {@code language_resources.artifact_uri} stores and {@link ArtifactUrlSigner} serves.
 */
interface PackObjectStore {

	/**
	 * Stores {@code bytes} under {@code key} (overwriting: keys are content-addressed, so a rewrite is identical).
	 *
	 * @return the {@code s3://bucket/key} location
	 * @throws IllegalStateException if storage is not configured or the store refuses
	 */
	String put(String key, byte[] bytes);

	/**
	 * Reads the object at an {@code s3://bucket/key} location of this store's bucket, at most {@code maxBytes} long.
	 *
	 * @throws IllegalStateException if storage is not configured, the object is missing or larger than {@code maxBytes}
	 */
	byte[] get(String location, long maxBytes);
}
