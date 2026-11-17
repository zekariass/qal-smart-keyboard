/**
 * Rate limiting API (SD 8.2 {@code platform}: rate limiting; SD 18: Bucket4j limits per IP, device, user, API key
 * and task). Modules declare a {@link com.qalsmartkeyboard.platform.ratelimit.RateLimitPolicy} and call
 * {@link com.qalsmartkeyboard.platform.ratelimit.RateLimiter#tryConsume}; a rejected
 * {@link com.qalsmartkeyboard.platform.ratelimit.RateLimitDecision} turns into a {@code RATE_LIMITED} 429 Problem
 * with {@code Retry-After} and {@code RateLimit-*} headers.
 *
 * <p>The current implementation keeps buckets in memory per instance ({@code internal}). It is correct for one
 * instance only: with N instances behind a load balancer a client can get up to N times the limit. A distributed
 * store (Bucket4j on PostgreSQL per SD 8.1, later Redis/Valkey) replaces it behind the same interface.
 */
@NamedInterface("ratelimit")
package com.qalsmartkeyboard.platform.ratelimit;

import org.springframework.modulith.NamedInterface;
