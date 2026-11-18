package com.qalsmartkeyboard.identity.oauth;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.identity.account.UserAccountStore;
import com.qalsmartkeyboard.identity.merge.GuestMergeService;
import com.qalsmartkeyboard.identity.session.SessionIssuer;
import com.qalsmartkeyboard.platform.ratelimit.RateLimiter;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Wires external-identity sign-in. Fails closed: Google sign-in is on only when client ids are configured, so a token
 * minted for somebody else's app can never be accepted. Without client ids it is off (requests get 400
 * {@code PROVIDER_NOT_SUPPORTED}), and under the {@code prod}, {@code production} and {@code staging} profiles startup
 * fails instead, unless {@code qal.identity.oauth.google.enabled=false} says that this is intended.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OAuthProperties.class)
class OAuthConfiguration {

	private static final Logger log = LoggerFactory.getLogger(OAuthConfiguration.class);
	private static final String PRODUCTION_LIKE_PROFILES = "prod | production | staging";

	@Bean
	OAuthService oAuthService(OAuthProperties properties, Environment environment, UserAccountStore accounts,
			SessionIssuer sessions, RateLimiter limiter, JdbcClient jdbc, PlatformTransactionManager transactionManager,
			ObjectProvider<Clock> clock, ObjectProvider<MeterRegistry> meters, GuestMergeService merges) {
		Clock c = clock.getIfAvailable(Clock::systemUTC);
		Map<String, JwksIdTokenVerifier> verifiers = new HashMap<>();
		OAuthProperties.Google google = properties.google();
		if (google.enabled()) {
			if (google.clientIds().isEmpty()) {
				if (environment.acceptsProfiles(Profiles.of(PRODUCTION_LIKE_PROFILES))) {
					throw new IllegalStateException("qal.identity.oauth.google.client-ids is not set (env "
							+ "QAL_GOOGLE_CLIENT_IDS, comma separated Android/iOS/web OAuth client ids); set "
							+ "qal.identity.oauth.google.enabled=false to run without Google sign-in");
				}
				log.warn("Google sign-in is OFF: qal.identity.oauth.google.client-ids is empty");
			}
			else {
				verifiers.put("GOOGLE", new JwksIdTokenVerifier(google.jwksUri(),
						Set.copyOf(OAuthProperties.Google.ISSUERS), Set.copyOf(google.clientIds()), google.clockSkew(),
						google.jwksCacheTtl(), google.jwksMinRefreshInterval(), google.jwksOutageTolerance(),
						google.httpTimeout(), c));
			}
		}
		return new OAuthService(verifiers, accounts, sessions, limiter, properties.limits(),
				new TransactionTemplate(transactionManager), jdbc, c, meters.getIfAvailable(), merges);
	}
}
