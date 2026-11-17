package com.qalsmartkeyboard.identity.otp;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Anomaly counters for OTP (SD 18 abuse, SD 23 alerting on OTP volume). Tags are low-cardinality outcome names and
 * the {@code ET}/{@code INTL} destination; never a number, hash, IP or code.
 *
 * <ul>
 * <li>{@code qal.identity.otp.started{outcome=sent|delivery_failed|cooldown|rate_limited|invalid_number,destination}}</li>
 * <li>{@code qal.identity.otp.verified{outcome=success|invalid|expired|too_many_attempts|replayed|superseded|unknown_challenge|rate_limited|age_required|suspended|unavailable}}</li>
 * <li>{@code qal.identity.otp.lockouts}: challenges locked by reaching the attempt limit</li>
 * </ul>
 * Rate-limit rejections are also counted per policy by the platform ({@code qal.ratelimit.rejected{policy}}).
 */
class OtpMetrics {

	private final MeterRegistry meters;

	/** @param meters may be {@code null} (web slices without metrics); counting is then a no-op */
	OtpMetrics(MeterRegistry meters) {
		this.meters = meters;
	}

	void started(String outcome, String destination) {
		if (meters != null) {
			meters.counter("qal.identity.otp.started", "outcome", outcome, "destination", destination).increment();
		}
	}

	void verified(String outcome) {
		if (meters != null) {
			meters.counter("qal.identity.otp.verified", "outcome", outcome).increment();
		}
	}

	void lockout() {
		if (meters != null) {
			meters.counter("qal.identity.otp.lockouts").increment();
		}
	}
}
