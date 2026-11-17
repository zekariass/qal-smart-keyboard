package com.qalsmartkeyboard.identity.otp;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.qalsmartkeyboard.platform.ratelimit.RateLimitPolicy;

/**
 * {@code qal.identity.otp.*} (SD 9.2; see backend/README.md "Identity"). Limit values are PROVISIONAL and
 * config-driven (plan 0.5).
 *
 * @param secretKey               base64 of at least 32 random bytes (env {@code QAL_OTP_SECRET_KEY}); keys the code
 *                                hash, the phone hash and the challenge's sealed number. No default: startup fails
 *                                without it unless {@code allowEphemeralSecretKey}
 * @param allowEphemeralSecretKey DEV/TEST ONLY: generate a random secret per JVM (codes issued before a restart, or by
 *                                another instance, no longer verify). Refused under prod/production/staging
 * @param codeTtl                 how long a code is valid
 * @param resendCooldown          minimum time between two challenges for one number
 * @param maxAttempts             wrong codes allowed per challenge before it locks (SD: at most 5)
 * @param limits                  rate limits per IP, number and device
 */
@ConfigurationProperties("qal.identity.otp")
record OtpProperties(String secretKey, @DefaultValue("false") boolean allowEphemeralSecretKey,
		@DefaultValue("5m") Duration codeTtl, @DefaultValue("60s") Duration resendCooldown,
		@DefaultValue("5") int maxAttempts, @DefaultValue Limits limits) {

	static final int CODE_LENGTH = 6;
	static final int MAX_ATTEMPTS_CAP = 5;

	OtpProperties {
		if (maxAttempts < 1 || maxAttempts > MAX_ATTEMPTS_CAP) {
			throw new IllegalArgumentException("qal.identity.otp.max-attempts must be between 1 and 5 (SD 9.2)");
		}
		if (codeTtl.isNegative() || codeTtl.isZero()) {
			throw new IllegalArgumentException("qal.identity.otp.code-ttl must be positive");
		}
		if (resendCooldown.isNegative()) {
			throw new IllegalArgumentException("qal.identity.otp.resend-cooldown must not be negative");
		}
	}

	/**
	 * Fixed-window limits (requests per period). Starts: per client IP (generous: mobile carriers put many users behind
	 * one CGNAT address), per device when the request names its {@code installationId}, per number (hourly and daily,
	 * the SMS-cost guard). Verifies: per client IP and per device ({@code device.installationId} of the verify request).
	 * Brute force on a single challenge is bounded separately by {@code maxAttempts}.
	 */
	record Limits(Limit startPerIp, Limit startPerDevice, Limit startPerPhone, Limit startPerPhoneDaily,
			Limit verifyPerIp, Limit verifyPerDevice) {

		Limits {
			startPerIp = startPerIp != null ? startPerIp : new Limit(30, Duration.ofHours(1));
			startPerDevice = startPerDevice != null ? startPerDevice : new Limit(10, Duration.ofHours(1));
			startPerPhone = startPerPhone != null ? startPerPhone : new Limit(5, Duration.ofHours(1));
			startPerPhoneDaily = startPerPhoneDaily != null ? startPerPhoneDaily : new Limit(10, Duration.ofDays(1));
			verifyPerIp = verifyPerIp != null ? verifyPerIp : new Limit(60, Duration.ofHours(1));
			verifyPerDevice = verifyPerDevice != null ? verifyPerDevice : new Limit(20, Duration.ofHours(1));
		}
	}

	/** Both members must be set when a limit is overridden. */
	record Limit(long capacity, Duration period) {

		Limit {
			if (capacity < 1 || period == null) {
				throw new IllegalArgumentException("An OTP rate limit needs a capacity >= 1 and a period");
			}
		}


		RateLimitPolicy policy(String name) {
			return new RateLimitPolicy(name, capacity, period);
		}
	}

	/** Never prints the secret. */
	@Override
	public String toString() {
		return "OtpProperties[secretKey=" + (secretKey == null || secretKey.isBlank() ? "<unset>" : "<redacted>")
				+ ", allowEphemeralSecretKey=" + allowEphemeralSecretKey + ", codeTtl=" + codeTtl + ", resendCooldown="
				+ resendCooldown + ", maxAttempts=" + maxAttempts + ", limits=" + limits + "]";
	}
}
