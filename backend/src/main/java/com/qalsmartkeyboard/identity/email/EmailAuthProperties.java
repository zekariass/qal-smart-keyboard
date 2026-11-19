package com.qalsmartkeyboard.identity.email;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.qalsmartkeyboard.platform.ratelimit.RateLimitPolicy;

/**
 * {@code qal.identity.email.*} (ADR 0033; see backend/README.md "E-mail and password sign-in"). Limit values are
 * PROVISIONAL and config-driven like OTP's.
 *
 * @param enabled              e-mail sign-up and sign-in are offered; when off every e-mail endpoint answers 503
 *                             {@code EMAIL_NOT_CONFIGURED}
 * @param verificationRequired a new account must prove control of its mailbox with a code before it exists, and an
 *                             unverified account must verify before it signs in. Off: sign-up signs in at once with an
 *                             unverified address (for quick tests; loud WARN under production-like profiles)
 * @param codeTtl              validity of a verification or reset code
 * @param resendCooldown       minimum time between two codes for one challenge or one address
 * @param maxAttempts          wrong codes per challenge before it locks (at most 5, as phone OTP)
 * @param maxSends             codes sent per challenge (the first and the resends)
 * @param challengeMaxAge      a challenge can be resent until this long after it was created
 * @param lockout              failed password attempts before the credential locks, and for how long
 * @param argon2               Argon2id parameters and the concurrency bound
 * @param limits               fixed-window rate limits
 */
@ConfigurationProperties("qal.identity.email")
record EmailAuthProperties(@DefaultValue("true") boolean enabled, @DefaultValue("true") boolean verificationRequired,
		@DefaultValue("15m") Duration codeTtl, @DefaultValue("60s") Duration resendCooldown,
		@DefaultValue("5") int maxAttempts, @DefaultValue("5") int maxSends, @DefaultValue("24h") Duration challengeMaxAge,
		@DefaultValue Lockout lockout, @DefaultValue Argon2 argon2, @DefaultValue Limits limits) {

	static final int CODE_LENGTH = 6;

	EmailAuthProperties {
		if (maxAttempts < 1 || maxAttempts > 5) {
			throw new IllegalArgumentException("qal.identity.email.max-attempts must be between 1 and 5");
		}
		if (maxSends < 1 || maxSends > 20) {
			throw new IllegalArgumentException("qal.identity.email.max-sends must be between 1 and 20");
		}
		if (codeTtl.isNegative() || codeTtl.isZero() || codeTtl.compareTo(Duration.ofHours(1)) > 0) {
			throw new IllegalArgumentException("qal.identity.email.code-ttl must be positive and at most 1h");
		}
		if (resendCooldown.isNegative()) {
			throw new IllegalArgumentException("qal.identity.email.resend-cooldown must not be negative");
		}
		if (challengeMaxAge.compareTo(codeTtl) < 0) {
			throw new IllegalArgumentException("qal.identity.email.challenge-max-age must be at least code-ttl");
		}
	}

	/**
	 * @param maxFailures wrong passwords in a row before the credential locks
	 * @param duration    how long it stays locked
	 */
	record Lockout(@DefaultValue("10") int maxFailures, @DefaultValue("15m") Duration duration) {

		Lockout {
			if (maxFailures < 3 || duration.isNegative() || duration.isZero()) {
				throw new IllegalArgumentException(
						"qal.identity.email.lockout needs max-failures >= 3 and a positive duration");
			}
		}
	}

	/**
	 * Defaults are the OWASP Password Storage Cheat Sheet baseline for Argon2id: m = 19 MiB, t = 2, p = 1.
	 *
	 * @param memoryKib     memory per hash in KiB
	 * @param iterations    passes
	 * @param parallelism   lanes
	 * @param maxConcurrent hashes computed at once (0 = twice the available processors)
	 * @param maxWait       how long a request waits for a free slot before a 429
	 */
	record Argon2(@DefaultValue("19456") int memoryKib, @DefaultValue("2") int iterations,
			@DefaultValue("1") int parallelism, @DefaultValue("0") int maxConcurrent,
			@DefaultValue("5s") Duration maxWait) {

		/** OWASP's lightest equivalent setting is m = 7 MiB with t = 5; anything below m * t of it is weaker. */
		static final long OWASP_MIN_COST = 7168L * 5;

		Argon2 {
			if (memoryKib < 1024 || memoryKib > 1_048_576 || iterations < 1 || iterations > 20 || parallelism < 1
					|| parallelism > 16 || maxConcurrent < 0) {
				throw new IllegalArgumentException("qal.identity.email.argon2 parameters are out of range");
			}
		}

		boolean belowOwaspBaseline() {
			return (long) memoryKib * iterations < OWASP_MIN_COST;
		}

		int effectiveMaxConcurrent() {
			return maxConcurrent > 0 ? maxConcurrent : Math.max(2, 2 * Runtime.getRuntime().availableProcessors());
		}
	}

	/**
	 * Per client IP (generous: mobile carriers put many users behind one CGNAT address), per device
	 * ({@code device.installationId}) and per address (keyed hash). {@code sendPerAddress*} bound the e-mails one address
	 * receives from every flow together (sign-up, verification, reset, resend).
	 */
	record Limits(Limit registerPerIp, Limit loginPerIp, Limit loginPerDevice, Limit verifyPerIp, Limit resendPerIp,
			Limit forgotPerIp, Limit resetPerIp, Limit sendPerAddress, Limit sendPerAddressDaily) {

		Limits {
			registerPerIp = registerPerIp != null ? registerPerIp : new Limit(10, Duration.ofHours(1));
			loginPerIp = loginPerIp != null ? loginPerIp : new Limit(60, Duration.ofHours(1));
			loginPerDevice = loginPerDevice != null ? loginPerDevice : new Limit(30, Duration.ofHours(1));
			verifyPerIp = verifyPerIp != null ? verifyPerIp : new Limit(60, Duration.ofHours(1));
			resendPerIp = resendPerIp != null ? resendPerIp : new Limit(20, Duration.ofHours(1));
			forgotPerIp = forgotPerIp != null ? forgotPerIp : new Limit(20, Duration.ofHours(1));
			resetPerIp = resetPerIp != null ? resetPerIp : new Limit(30, Duration.ofHours(1));
			sendPerAddress = sendPerAddress != null ? sendPerAddress : new Limit(5, Duration.ofHours(1));
			sendPerAddressDaily = sendPerAddressDaily != null ? sendPerAddressDaily : new Limit(10, Duration.ofDays(1));
		}
	}

	/** Both members must be set when a limit is overridden. */
	record Limit(long capacity, Duration period) {

		Limit {
			if (capacity < 1 || period == null) {
				throw new IllegalArgumentException("An e-mail rate limit needs a capacity >= 1 and a period");
			}
		}

		RateLimitPolicy policy(String name) {
			return new RateLimitPolicy(name, capacity, period);
		}
	}
}
