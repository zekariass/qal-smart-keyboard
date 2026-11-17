package com.qalsmartkeyboard.identity.attestation;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.qalsmartkeyboard.platform.ratelimit.RateLimitPolicy;

/**
 * {@code qal.identity.attestation.*} (SD 9.4, ADR 0008). Limit values are PROVISIONAL and config-driven.
 *
 * @param nonceTtl how long a nonce is valid; the contract says 5 minutes, which is also the maximum
 * @param mock     the development/test verifiers
 * @param limits   rate limits
 */
@ConfigurationProperties("qal.identity.attestation")
public record AttestationProperties(@DefaultValue("5m") Duration nonceTtl, @DefaultValue Mock mock,
		@DefaultValue Limits limits) {

	static final Duration MAX_NONCE_TTL = Duration.ofMinutes(5);

	public AttestationProperties {
		if (nonceTtl.isNegative() || nonceTtl.isZero() || nonceTtl.compareTo(MAX_NONCE_TTL) > 0) {
			throw new IllegalArgumentException("qal.identity.attestation.nonce-ttl must be positive and at most 5m");
		}
	}

	/**
	 * @param enabled DEV/TEST ONLY: register the mock Play Integrity and App Attest verifiers, which accept a published
	 *                test token format. Off by default; refused under prod/production/staging
	 */
	public record Mock(@DefaultValue("false") boolean enabled) {
	}

	/** Nonces per client IP; guest creation per client IP and per installation id. */
	public record Limits(Limit noncePerIp, Limit guestPerIp, Limit guestPerInstallation) {

		public Limits {
			noncePerIp = noncePerIp != null ? noncePerIp : new Limit(120, Duration.ofHours(1));
			guestPerIp = guestPerIp != null ? guestPerIp : new Limit(10, Duration.ofHours(1));
			guestPerInstallation = guestPerInstallation != null ? guestPerInstallation
					: new Limit(5, Duration.ofHours(1));
		}
	}

	/** Both members must be set when a limit is overridden. */
	public record Limit(long capacity, Duration period) {

		public Limit {
			if (capacity < 1 || period == null) {
				throw new IllegalArgumentException("An attestation rate limit needs a capacity >= 1 and a period");
			}
		}

		public RateLimitPolicy policy(String name) {
			return new RateLimitPolicy(name, capacity, period);
		}
	}
}
