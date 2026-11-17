package com.qalsmartkeyboard.platform.crypto.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import com.qalsmartkeyboard.platform.crypto.kms.WrappedKey;

/** Unit-test store mirroring the V1 guards: FK to users, unique version, one ACTIVE per user. */
final class InMemoryUserDataKeyStore implements UserDataKeyStore {

	private final Set<UUID> users = new HashSet<>();
	private final Map<UUID, TreeMap<Integer, KeyRecord>> keys = new HashMap<>();

	synchronized UUID newUser() {
		UUID id = UUID.randomUUID();
		users.add(id);
		return id;
	}

	/** Direct row edit, like an attacker or operator with database write access. */
	synchronized void replace(UUID userId, KeyRecord record) {
		keys.get(userId).put(record.keyVersion(), record);
	}

	@Override
	public synchronized List<KeyRecord> findAll(UUID userId) {
		return new ArrayList<>(keys.getOrDefault(userId, new TreeMap<>()).values());
	}

	@Override
	public synchronized Optional<KeyRecord> find(UUID userId, int keyVersion) {
		return Optional.ofNullable(keys.getOrDefault(userId, new TreeMap<>()).get(keyVersion));
	}

	@Override
	public void lockUser(UUID userId) {
		// single-threaded unit tests; the PostgreSQL advisory lock is covered by EnvelopeEncryptionIT
	}

	@Override
	public synchronized void insertActive(UUID userId, int keyVersion, WrappedKey wrapped) {
		if (!users.contains(userId)) {
			throw new DataIntegrityViolationException("fk_user");
		}
		TreeMap<Integer, KeyRecord> rows = keys.computeIfAbsent(userId, u -> new TreeMap<>());
		if (rows.containsKey(keyVersion)
				|| rows.values().stream().anyMatch(r -> r.status() == KeyStatus.ACTIVE)) {
			throw new DuplicateKeyException("ux_user_data_keys");
		}
		rows.put(keyVersion, new KeyRecord(keyVersion, KeyStatus.ACTIVE, wrapped));
	}

	@Override
	public synchronized int markActiveRotated(UUID userId) {
		return update(userId, KeyStatus.ACTIVE, KeyStatus.ROTATED);
	}

	@Override
	public synchronized int destroyAll(UUID userId) {
		return update(userId, null, KeyStatus.DESTROYED);
	}

	@Override
	public synchronized int insertShredMarker(UUID userId) {
		if (!users.contains(userId)) {
			return 0;
		}
		keys.computeIfAbsent(userId, u -> new TreeMap<>()).put(1, new KeyRecord(1, KeyStatus.DESTROYED, null));
		return 1;
	}

	private int update(UUID userId, KeyStatus from, KeyStatus to) {
		int count = 0;
		for (Map.Entry<Integer, KeyRecord> e : keys.getOrDefault(userId, new TreeMap<>()).entrySet()) {
			KeyRecord r = e.getValue();
			boolean matches = from == null ? r.status() != KeyStatus.DESTROYED : r.status() == from;
			if (matches) {
				e.setValue(new KeyRecord(r.keyVersion(), to, to == KeyStatus.DESTROYED ? null : r.wrapped()));
				count++;
			}
		}
		return count;
	}
}
