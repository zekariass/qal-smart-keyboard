package com.qalsmartkeyboard.privacy;

import java.util.Optional;

/**
 * Where export archives are kept: a private S3-compatible bucket (MinIO locally) with a 7-day lifecycle rule (SD 8,
 * 19.2), written and read by the backend only; a client never gets a bucket URL (ADR 0022). Objects are
 * <b>ciphertext only</b>: the archive is sealed with the user's data key before it is stored, so neither the bucket nor
 * a backup of it holds plaintext, and crypto-shredding the user makes every object unreadable.
 */
interface ExportObjectStore {

	/**
	 * Stores {@code bytes} under {@code key} (overwriting).
	 *
	 * @throws IllegalStateException if storage is not configured or the store refuses
	 */
	void put(String key, byte[] bytes);

	/**
	 * Reads the object, at most {@code maxBytes} long.
	 *
	 * @return the bytes, or empty when there is no such object
	 * @throws IllegalStateException if storage is not configured, the store fails or the object is larger than
	 *                               {@code maxBytes}
	 */
	Optional<byte[]> get(String key, long maxBytes);

	/**
	 * Deletes the object; deleting a missing object is not an error.
	 *
	 * @throws IllegalStateException if storage is not configured or the store refuses
	 */
	void delete(String key);

	/** Whether this store can be used at all (false for the placeholder used when no credentials are configured). */
	default boolean configured() {
		return true;
	}
}
