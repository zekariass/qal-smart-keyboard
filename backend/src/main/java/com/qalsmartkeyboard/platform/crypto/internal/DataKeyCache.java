package com.qalsmartkeyboard.platform.crypto.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Small in-memory LRU + TTL cache of unwrapped data keys, so not every encrypt/decrypt needs a KMS call.
 * Bounded by entry count; every entry expires {@code ttl} after it was cached. {@link #get} returns a copy and
 * the cache keeps its own copy, so evicting (which wipes the cached array) never races with a caller using
 * the key. Disabled when {@code maxEntries <= 0} or {@code ttl} is zero.
 *
 * <p>The cache only saves KMS unwraps: the service still reads the key row on every operation, so a shred
 * on another instance takes effect there immediately even though that instance's cached copy lingers in
 * memory until it expires.
 */
final class DataKeyCache {

	record Key(UUID userId, int keyVersion) {
	}

	private record CachedDek(byte[] dek, Instant expiresAt) {
	}

	private final int maxEntries;
	private final Duration ttl;
	private final InstantSource clock;
	private final LinkedHashMap<Key, CachedDek> entries;

	DataKeyCache(int maxEntries, Duration ttl, InstantSource clock) {
		this.maxEntries = maxEntries;
		this.ttl = ttl;
		this.clock = clock;
		this.entries = new LinkedHashMap<>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<Key, CachedDek> eldest) {
				if (size() > DataKeyCache.this.maxEntries) {
					wipe(eldest.getValue());
					return true;
				}
				return false;
			}
		};
	}

	private boolean enabled() {
		return maxEntries > 0 && ttl.isPositive();
	}

	/** Returns a copy of the cached key (the caller wipes it) or {@code null}. */
	synchronized byte[] get(Key key) {
		CachedDek entry = entries.get(key);
		if (entry == null) {
			return null;
		}
		if (!clock.instant().isBefore(entry.expiresAt())) {
			entries.remove(key);
			wipe(entry);
			return null;
		}
		return entry.dek().clone();
	}

	/** Caches a copy of {@code dek}; the caller keeps ownership of its array. */
	synchronized void put(Key key, byte[] dek) {
		if (!enabled()) {
			return;
		}
		CachedDek previous = entries.put(key, new CachedDek(dek.clone(), clock.instant().plus(ttl)));
		if (previous != null) {
			wipe(previous);
		}
	}

	synchronized void evictUser(UUID userId) {
		Iterator<Map.Entry<Key, CachedDek>> it = entries.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Key, CachedDek> next = it.next();
			if (next.getKey().userId().equals(userId)) {
				wipe(next.getValue());
				it.remove();
			}
		}
	}

	synchronized void clear() {
		entries.values().forEach(DataKeyCache::wipe);
		entries.clear();
	}

	synchronized int size() {
		return entries.size();
	}

	private static void wipe(CachedDek entry) {
		Arrays.fill(entry.dek(), (byte) 0);
	}
}
