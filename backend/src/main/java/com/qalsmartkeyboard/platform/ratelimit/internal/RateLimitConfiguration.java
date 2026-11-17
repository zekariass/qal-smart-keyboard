package com.qalsmartkeyboard.platform.ratelimit.internal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.qalsmartkeyboard.platform.ratelimit.RateLimiter;

import io.github.bucket4j.TimeMeter;
import io.micrometer.core.instrument.MeterRegistry;

/** Provides the {@link RateLimiter}: in-memory, per instance, until a distributed store is added (SD 17.3). */
@Configuration(proxyBeanMethods = false)
class RateLimitConfiguration {

	@Bean
	RateLimiter rateLimiter(@Value("${qal.ratelimit.local.max-keys-per-policy:100000}") long maxKeysPerPolicy,
			ObjectProvider<MeterRegistry> meters) {
		return new LocalBucket4jRateLimiter(maxKeysPerPolicy, TimeMeter.SYSTEM_NANOTIME, meters.getIfAvailable());
	}
}
