package com.qalsmartkeyboard.identity.oauth;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.qalsmartkeyboard.platform.ratelimit.RateLimitPolicy;

/**
 * {@code qal.identity.oauth.*} (SD 9.2; see backend/README.md "Identity: Google sign-in"). Limit values are
 * PROVISIONAL and config-driven (plan 0.5).
 *
 * @param google the Google provider
 * @param limits rate limits of {@code POST /auth/oauth/{provider}}
 */
@ConfigurationProperties("qal.identity.oauth")
record OAuthProperties(@DefaultValue Google google, @DefaultValue Limits limits) {

	/**
	 * @param enabled             {@code false} switches Google sign-in off. Default {@code true}
	 * @param clientIds           the OAuth client ids whose ID tokens are accepted ({@code aud}): the Android, iOS and web
	 *                            client ids (env {@code QAL_GOOGLE_CLIENT_IDS}, comma separated). With none, Google
	 *                            sign-in stays <b>off</b> (nothing is ever accepted), and startup fails under the
	 *                            prod/production/staging profiles unless {@code enabled=false}
	 * @param jwksUri             where Google publishes its signing keys (https; http only to loopback for tests)
	 * @param clockSkew           tolerance for {@code exp} and {@code iat}
	 * @param jwksCacheTtl        how long fetched keys are used before the next fetch
	 * @param jwksMinRefreshInterval at least this long between two fetches, including the refresh triggered by an
	 *                            unknown {@code kid} (so a flood of unknown key ids cannot hammer the provider)
	 * @param jwksOutageTolerance how long cached keys keep verifying when the provider cannot be reached
	 * @param httpTimeout         connect and read timeout of a JWKS fetch
	 */
	record Google(@DefaultValue("true") boolean enabled, List<String> clientIds,
			@DefaultValue("https://www.googleapis.com/oauth2/v3/certs") URI jwksUri,
			@DefaultValue("60s") Duration clockSkew, @DefaultValue("1h") Duration jwksCacheTtl,
			@DefaultValue("30s") Duration jwksMinRefreshInterval, @DefaultValue("6h") Duration jwksOutageTolerance,
			@DefaultValue("3s") Duration httpTimeout) {

		static final List<String> ISSUERS = List.of("https://accounts.google.com", "accounts.google.com");

		Google {
			clientIds = clientIds == null ? List.of()
					: clientIds.stream().filter(id -> id != null && !id.isBlank()).map(String::trim).distinct().toList();
			if (clockSkew.isNegative() || clockSkew.compareTo(Duration.ofMinutes(5)) > 0) {
				throw new IllegalArgumentException("qal.identity.oauth.google.clock-skew must be between 0 and 5m");
			}
			if (jwksCacheTtl.isNegative() || jwksCacheTtl.isZero() || jwksMinRefreshInterval.isNegative()
					|| jwksMinRefreshInterval.isZero() || jwksOutageTolerance.isNegative() || httpTimeout.isNegative()
					|| httpTimeout.isZero()) {
				throw new IllegalArgumentException("the qal.identity.oauth.google JWKS durations must be positive");
			}
		}
	}

	/** Fixed-window limits (requests per period) per client IP and, when the request names it, per installation. */
	record Limits(Limit exchangePerIp, Limit exchangePerDevice) {

		Limits {
			exchangePerIp = exchangePerIp != null ? exchangePerIp : new Limit(60, Duration.ofHours(1));
			exchangePerDevice = exchangePerDevice != null ? exchangePerDevice : new Limit(20, Duration.ofHours(1));
		}
	}

	/** Both members must be set when a limit is overridden. */
	record Limit(long capacity, Duration period) {

		Limit {
			if (capacity < 1 || period == null) {
				throw new IllegalArgumentException("An OAuth rate limit needs a capacity >= 1 and a period");
			}
		}

		RateLimitPolicy policy(String name) {
			return new RateLimitPolicy(name, capacity, period);
		}
	}
}
