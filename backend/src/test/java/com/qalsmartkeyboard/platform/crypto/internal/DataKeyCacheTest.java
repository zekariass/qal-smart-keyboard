package com.qalsmartkeyboard.platform.crypto.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class DataKeyCacheTest {

	private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-17T00:00:00Z"));
	private final InstantSource clock = now::get;

	private static DataKeyCache.Key key(UUID user, int version) {
		return new DataKeyCache.Key(user, version);
	}

	@Test
	void storesAndReturnsCopies() {
		DataKeyCache cache = new DataKeyCache(10, Duration.ofMinutes(5), clock);
		UUID user = UUID.randomUUID();
		byte[] dek = { 1, 2, 3 };
		cache.put(key(user, 1), dek);
		dek[0] = 9; // caller wipes or changes its array: cache unaffected

		byte[] first = cache.get(key(user, 1));
		assertThat(first).containsExactly(1, 2, 3);
		first[1] = 9;
		assertThat(cache.get(key(user, 1))).containsExactly(1, 2, 3);
	}

	@Test
	void entriesExpireAfterTtl() {
		DataKeyCache cache = new DataKeyCache(10, Duration.ofMinutes(5), clock);
		UUID user = UUID.randomUUID();
		cache.put(key(user, 1), new byte[] { 1 });

		now.set(now.get().plus(Duration.ofMinutes(5)).minusMillis(1));
		assertThat(cache.get(key(user, 1))).isNotNull();
		now.set(now.get().plusMillis(1));
		assertThat(cache.get(key(user, 1))).isNull();
		assertThat(cache.size()).isZero();
	}

	@Test
	void boundedLeastRecentlyUsed() {
		DataKeyCache cache = new DataKeyCache(2, Duration.ofMinutes(5), clock);
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		UUID c = UUID.randomUUID();
		cache.put(key(a, 1), new byte[] { 1 });
		cache.put(key(b, 1), new byte[] { 2 });
		cache.get(key(a, 1)); // a is now most recently used
		cache.put(key(c, 1), new byte[] { 3 });

		assertThat(cache.size()).isEqualTo(2);
		assertThat(cache.get(key(b, 1))).isNull();
		assertThat(cache.get(key(a, 1))).isNotNull();
		assertThat(cache.get(key(c, 1))).isNotNull();
	}

	@Test
	void evictUserRemovesOnlyThatUsersVersions() {
		DataKeyCache cache = new DataKeyCache(10, Duration.ofMinutes(5), clock);
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		cache.put(key(a, 1), new byte[] { 1 });
		cache.put(key(a, 2), new byte[] { 2 });
		cache.put(key(b, 1), new byte[] { 3 });

		cache.evictUser(a);
		assertThat(cache.get(key(a, 1))).isNull();
		assertThat(cache.get(key(a, 2))).isNull();
		assertThat(cache.get(key(b, 1))).isNotNull();
	}

	@Test
	void zeroSizeOrTtlDisablesTheCache() {
		UUID user = UUID.randomUUID();
		DataKeyCache noEntries = new DataKeyCache(0, Duration.ofMinutes(5), clock);
		noEntries.put(key(user, 1), new byte[] { 1 });
		assertThat(noEntries.get(key(user, 1))).isNull();

		DataKeyCache noTtl = new DataKeyCache(10, Duration.ZERO, clock);
		noTtl.put(key(user, 1), new byte[] { 1 });
		assertThat(noTtl.get(key(user, 1))).isNull();
	}
}
