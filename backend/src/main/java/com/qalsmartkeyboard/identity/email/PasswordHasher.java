package com.qalsmartkeyboard.identity.email;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

import com.qalsmartkeyboard.platform.ratelimit.RateLimitDecision;

/**
 * Argon2id password hashing (ADR 0033) with Spring Security's {@link Argon2PasswordEncoder} (Bouncy Castle). The stored
 * value is the self-describing PHC string {@code $argon2id$v=19$m=...,t=...,p=...$salt$hash}; V1
 * {@code user_password_credentials.password_hash} holds it.
 *
 * <ul>
 * <li>Parameters default to the OWASP Password Storage Cheat Sheet baseline (m = 19 MiB, t = 2, p = 1, 16-byte salt,
 * 32-byte hash). A stored hash with weaker parameters is re-hashed after the next successful sign-in
 * ({@link #needsRehash}).</li>
 * <li>{@link #matches} with a {@code null} hash verifies against a dummy hash of the same parameters, so a sign-in for
 * an unknown address costs the same time as one for a known address.</li>
 * <li>Hashing takes memory (m per call): at most {@code maxConcurrent} run at once; a caller that cannot get a slot
 * within {@code maxWait} gets a 429 with {@code Retry-After} instead of queueing without bound.</li>
 * </ul>
 */
class PasswordHasher {

	private static final String PREFIX = "$argon2id$";

	private final Argon2PasswordEncoder encoder;
	private final String dummyHash;
	private final Semaphore slots;
	private final Duration maxWait;

	PasswordHasher(int memoryKib, int iterations, int parallelism, int maxConcurrent, Duration maxWait) {
		this.encoder = new Argon2PasswordEncoder(16, 32, parallelism, memoryKib, iterations);
		this.slots = new Semaphore(maxConcurrent, true);
		this.maxWait = maxWait;
		byte[] random = new byte[24];
		new SecureRandom().nextBytes(random);
		this.dummyHash = encoder.encode(Base64.getEncoder().encodeToString(random));
	}

	/** @param password already normalised ({@link PasswordPolicy#normalize}) */
	String hash(String password) {
		return withSlot(() -> encoder.encode(password));
	}

	/**
	 * Constant-work check: a {@code null} or foreign-format hash is checked against the dummy hash and never matches.
	 *
	 * @param password already normalised
	 */
	boolean matches(String password, String encodedHash) {
		boolean usable = encodedHash != null && encodedHash.startsWith(PREFIX);
		return withSlot(() -> {
			boolean ok = encoder.matches(password, usable ? encodedHash : dummyHash);
			return usable && ok;
		});
	}

	/** Whether the stored hash uses weaker parameters than the configured ones. */
	boolean needsRehash(String encodedHash) {
		return encodedHash != null && encodedHash.startsWith(PREFIX) && encoder.upgradeEncoding(encodedHash);
	}

	private <T> T withSlot(java.util.function.Supplier<T> work) {
		boolean acquired;
		try {
			acquired = slots.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			acquired = false;
		}
		if (!acquired) {
			throw RateLimitDecision.retryAfterProblem(Duration.ofSeconds(1));
		}
		try {
			return work.get();
		}
		finally {
			slots.release();
		}
	}
}
