package com.qalsmartkeyboard.platform.crypto.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.qalsmartkeyboard.platform.crypto.kms.WrappedKey;

/** Persistence of {@code user_data_keys} rows. All writes require {@link #lockUser} in the same transaction. */
interface UserDataKeyStore {

	enum KeyStatus {
		ACTIVE, ROTATED, DESTROYED
	}

	/** One {@code user_data_keys} row; {@code wrapped} is {@code null} for a DESTROYED row. */
	record KeyRecord(int keyVersion, KeyStatus status, WrappedKey wrapped) {
	}

	/** All rows of the user, ordered by key version. */
	List<KeyRecord> findAll(UUID userId);

	Optional<KeyRecord> find(UUID userId, int keyVersion);

	/** Serialises key creation, rotation and shredding per user until the transaction ends. */
	void lockUser(UUID userId);

	/**
	 * @throws org.springframework.dao.DuplicateKeyException if the version or a second ACTIVE key exists
	 * @throws org.springframework.dao.DataIntegrityViolationException if the user does not exist
	 */
	void insertActive(UUID userId, int keyVersion, WrappedKey wrapped);

	/** ACTIVE -> ROTATED; returns the number of rows changed. */
	int markActiveRotated(UUID userId);

	/** Every non-destroyed key -> DESTROYED, wrapped DEK erased; returns the number of rows changed. */
	int destroyAll(UUID userId);

	/**
	 * Records that a user without any key was shredded (a DESTROYED version-1 row), so no key can be created
	 * for it later. Does nothing if the user does not exist. Returns the number of rows inserted.
	 */
	int insertShredMarker(UUID userId);
}
