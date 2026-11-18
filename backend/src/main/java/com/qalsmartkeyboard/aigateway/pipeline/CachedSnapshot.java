package com.qalsmartkeyboard.aigateway.pipeline;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A whole-table snapshot reloaded after a TTL, like the registry and routing caches (ADR 0014): a failed reload keeps
 * serving the previous snapshot; a failure before the first successful load propagates. TTL zero = always reload.
 */
final class CachedSnapshot<T> {

	private static final Logger log = LoggerFactory.getLogger(CachedSnapshot.class);

	private final String name;
	private final Supplier<T> loader;
	private final Duration ttl;
	private final Clock clock;
	private T value;
	private Instant loadedAt;

	CachedSnapshot(String name, Supplier<T> loader, Duration ttl, Clock clock) {
		this.name = name;
		this.loader = loader;
		this.ttl = ttl;
		this.clock = clock;
	}

	synchronized T get() {
		Instant now = clock.instant();
		if (value != null && !ttl.isZero() && now.isBefore(loadedAt.plus(ttl))) {
			return value;
		}
		try {
			value = loader.get();
			loadedAt = now;
		} catch (RuntimeException e) {
			if (value == null) {
				throw e;
			}
			log.warn("Reloading the {} snapshot failed, serving the previous one: {}", name,
					e.getClass().getSimpleName());
		}
		return value;
	}
}
